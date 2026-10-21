package io.github.ultradsu.dsu;

import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.nio.file.Files;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.zip.GZIPInputStream;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;

/** Root-only app_process entry point. Uses the device's own AIDL Proxy, never hardcoded transactions. */
public final class RootBridge {
    private static Class<?> api;
    private static Object service;
    private static File metadataRoot = new File("/metadata/gsi/dsu");
    private static File imageRoot = new File("/data/gsi/dsu");
    private static int platformSdk = Build.VERSION.SDK_INT;
    private static File gsidBinary = new File("/system/bin/gsid");
    private static String testedBackportHash = "6bea182e737e238ce8c054717f48e64afc1c8a989aab53850bc9a564651bd323";
    private static File oneShotFile = new File("/metadata/gsi/dsu/one_shot_boot");
    private static File growthRoot = new File("/data/adb/ultradsu/growth");
    private static File growthDirectory(String slot) { return new File(growthRoot, slot); }
    interface BackupDirectorySync { void sync(File directory) throws Exception; }
    private static BackupDirectorySync backupDirectorySync = NativeGrowthTools::syncDirectory;
    private static final Set<String> READ_ONLY = new HashSet<>(Arrays.asList("system", "system_ext", "product", "vendor", "odm", "vendor_dlkm", "odm_dlkm", "system_dlkm"));
    private static String writingPartition = "system";
    private static final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private static boolean cancellationEnabled;
    private static boolean acceptingCancellation;
    private static boolean exportMode;
    private static synchronized void requestCancellation() {
        if (acceptingCancellation) cancellationRequested.set(true);
    }
    private static void checkpoint() {
        if (cancellationRequested.get()) throw new CancellationException("任务已取消");
    }
    private static synchronized void finishCancellation() throws Exception {
        checkpoint(); acceptingCancellation = false;
        JSONObject event = new JSONObject().put("message", exportMode ? "正在校验导出结果" : "正在完成安装并恢复启动选择")
            .put("cancellable", false);
        (exportMode ? System.err : System.out).println("ULTRADSU_PROGRESS:" + event);
    }
    // Only the owning install invokes cancelGsiInstall, while its own allocation
    // is outstanding. The stdin monitor itself never calls a global Binder API.
    private static Object installCall(String method, Object... args) throws Exception {
        checkpoint();
        if (!cancellationEnabled) return call(method, args);
        FutureTask<Object> task = new FutureTask<>(() -> call(method, args));
        Thread binder = new Thread(task, "UltraDSU-install-binder"); binder.start();
        try {
            for (;;) {
                try { Object result = task.get(100, TimeUnit.MILLISECONDS); checkpoint(); return result; }
                catch (TimeoutException waiting) {
                    if (cancellationRequested.get()) {
                        // gsid sets its abort flag before waiting for the allocation lock.
                        truth(call("cancelGsiInstall"));
                        try { task.get(); } catch (ExecutionException ignored) { }
                        checkpoint();
                    }
                }
            }
        } catch (ExecutionException error) {
            checkpoint();
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception)cause;
            throw new IllegalStateException(cause);
        } finally { binder.join(); }
    }
    static final class ImageSpec {
        final String partition; final File file; final long bytes;
        ImageSpec(String partition, File file, long bytes) { this.partition = partition; this.file = file; this.bytes = bytes; }
    }
    interface ImageSizeReader { long size(Object service, String name) throws Exception; }
    private static ImageSizeReader imageSizeReader = RootBridge::readImageSize;
    interface ImageWriter { void write(File file, long size) throws Exception; }
    private static ImageWriter imageWriter = RootBridge::writeImage;
    interface DirectoryLabeler { void label(File directory) throws Exception; }
    private static DirectoryLabeler directoryLabeler = directory -> {
        android.system.Os.chmod(directory.getPath(), 0755);
        Object result = Class.forName("android.os.SELinux").getMethod("restorecon", File.class).invoke(null, directory);
        if (!Boolean.TRUE.equals(result)) throw new IllegalStateException("无法设置安装目录 SELinux 标签：" + directory);
    };

    private static boolean internalMultiSlot() {
        if (platformSdk >= 35) return true;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(gsidBinary.toPath()));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return testedBackportHash.equals(hex.toString());
        } catch (Exception ignored) { return false; }
    }

    private static File prepareInstallDirectory(String slot) throws Exception {
        boolean internalMulti = internalMultiSlot();
        if (!internalMulti && !slot.equals("dsu"))
            throw new IllegalStateException("本机 gsid 的内部存储仅支持默认槽 dsu；原生内部多槽需要 Android 15+，Root 本身不能解除路径限制");
        File directory = !internalMulti ? imageRoot : new File(imageRoot, slot);
        if (!slot.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}") ||
                !imageRoot.getCanonicalFile().equals(imageRoot.getAbsoluteFile()) ||
                !directory.getCanonicalFile().equals(directory.getAbsoluteFile()))
            throw new SecurityException("无效的安装路径或符号链接");
        if (new File(growthDirectory(slot), "growth.json").exists())
            throw new IllegalStateException("同名槽仍有扩容恢复记录，请先恢复或清理备份");
        if (!imageRoot.isDirectory()) {
            if (!imageRoot.mkdir()) throw new IllegalStateException("无法创建系统 DSU 数据目录：" + imageRoot);
            directoryLabeler.label(imageRoot);
        }
        if (directory.exists()) {
            String[] children = directory.list();
            if (children == null || children.length != 0)
                throw new IllegalStateException("安装目录已有未登记的数据，已阻止覆盖：" + directory);
        } else if (!directory.mkdir()) throw new IllegalStateException("无法创建安装目录：" + directory);
        directoryLabeler.label(directory);
        return directory;
    }

    private static void installStep(String step, String method, Object... args) throws Exception {
        try { successCode(call(method, args)); }
        catch (Exception error) { throw new IllegalStateException(step + "失败：" + error.getMessage(), error); }
    }

    private static Object call(String name, Object... args) throws Exception {
        if (!multi() && name.equals("removeGsi")) name = "removeGsiInstall";
        if (!multi() && name.equals("disableGsi")) name = "disableGsiInstall";
        for (Method method : api.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == args.length) {
                return method.invoke(service, args);
            }
        }
        throw new NoSuchMethodException(name);
    }
    private static boolean flag(String name) throws Exception { return Boolean.TRUE.equals(call(name)); }
    private static void truth(Object result) {
        if (!Boolean.TRUE.equals(result)) throw new IllegalStateException("系统拒绝 DSU 操作");
    }
    private static void successCode(Object result) {
        if (!(result instanceof Integer) || ((Integer) result) != 0)
            throw new IllegalStateException("DSU 错误码：" + result);
    }
    @SuppressWarnings("unchecked")
    private static List<String> slots() throws Exception { return (List<String>) call("getInstalledDsuSlots"); }
    private static boolean multi() {
        try { api.getMethod("getInstalledDsuSlots"); api.getMethod("enableGsi", boolean.class, String.class); return true; }
        catch (Exception e) { return false; }
    }
    private static void idle() throws Exception {
        if (flag("isGsiInstallInProgress")) throw new IllegalStateException("系统正在安装 DSU，请稍后重试");
        if (flag("isGsiRunning")) throw new IllegalStateException("请先返回主系统，再管理槽位");
    }
    private static void validateSlot(String slot) throws Exception {
        if (!slot.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}") || !slots().contains(slot))
            throw new IllegalArgumentException("系统中不存在此槽位");
    }
    private static void enable(String slot, boolean oneShot) throws Exception {
        if (!multi()) throw new IllegalStateException("此系统没有命名多槽接口");
        validateSlot(slot);
        if (maintenance(slot).isFile() || UserdataGrowth.blocked(growthDirectory(slot))) throw new IllegalStateException("此槽有未完成的分区修改或扩容，请先恢复后再启动");
        File marker = new File(slotDirectory(slot), "complete");
        if (!marker.isFile() || !"OK".equals(new String(Files.readAllBytes(marker.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim()))
            throw new IllegalStateException("此槽尚未完成安装，无法启动");
        if (flag("isGsiEnabled")) truth(call("disableGsi"));
        successCode(call("enableGsi", oneShot, slot));
        if (!slot.equals(activeSlot())) throw new IllegalStateException("槽位切换校验失败");
    }
    private static JSONObject status() throws Exception {
        JSONObject out = new JSONObject();
        JSONObject userdataSizes = new JSONObject(), userdataSizeErrors = new JSONObject();
        boolean multi = multi();
        out.put("multiSlot", multi);
        boolean internalMulti = multi && internalMultiSlot();
        out.put("internalMultiSlot", internalMulti);
        out.put("internalMultiSlotSource", internalMulti && platformSdk < 35 ? "tested_moto_backport" : "platform");
        out.put("running", flag("isGsiRunning"));
        out.put("enabled", flag("isGsiEnabled"));
        out.put("installing", flag("isGsiInstallInProgress"));
        if (out.getBoolean("running")) out.put("guestAssistance", GuestAssistance.status(activeSlot(), false));
        if (multi) {
            List<String> complete = new ArrayList<>();
            for (String slot : slots()) {
                if (!slot.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}")) continue;
                File marker = new File(slotDirectory(slot), "complete");
                if (!maintenance(slot).isFile() && marker.isFile() && "OK".equals(new String(Files.readAllBytes(marker.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim())) complete.add(slot);
            }
            out.put("slots", new JSONArray(complete));
            out.put("occupiedSlots", new JSONArray(slots()));
            out.put("active", activeSlot());
            // Imported slots have no local configured capacity. Refresh actual sizes
            // while idle instead of waiting for the partition-management dialog.
            // Never map or unmap guest images during booted DSU or installation.
            if (!out.getBoolean("running") && !out.getBoolean("installing")) {
                for (String slot : complete) {
                    try {
                        Object images = call("openImageService", "dsu/" + slot);
                        long size = imageSizeReader.size(images, "userdata_gsi");
                        if (size > 0) userdataSizes.put(slot, size);
                        else userdataSizeErrors.put(slot, "userdata 容量暂不可读，可能已映射或系统未提供容量查询");
                    } catch (Exception error) {
                        userdataSizeErrors.put(slot, error.getClass().getSimpleName() + ": " + error.getMessage());
                    }
                }
            }
        } else {
            boolean installed = flag("isGsiInstalled");
            out.put("slots", installed ? new JSONArray().put("dsu") : new JSONArray());
            out.put("active", installed ? "dsu" : "");
        }
        out.put("userdataSizes", userdataSizes);
        out.put("userdataSizeErrors", userdataSizeErrors);
        return out;
    }
    private static File slotDirectory(String slot) throws Exception {
        if (!slot.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}")) throw new IllegalArgumentException("Invalid slot");
        File dir = new File(metadataRoot, slot);
        if (!dir.getCanonicalFile().equals(new File(metadataRoot.getCanonicalFile(), slot)))
            throw new SecurityException("拒绝访问符号链接槽目录");
        return dir;
    }
    private static String activeSlot() throws Exception {
        // getActiveDsuSlot() can describe the last installation rather than the boot selection.
        File file = new File(metadataRoot, "active");
        return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim() : (String) call("getActiveDsuSlot");
    }
    private static File maintenance(String slot) throws Exception { return new File(slotDirectory(slot), "ultradsu_maintenance.json"); }
    private static Object imageCall(Object image, String name, Object... args) throws Exception {
        for (Method method : image.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == args.length) {
                method.setAccessible(true);
                return method.invoke(image, args);
            }
        }
        throw new NoSuchMethodException(name);
    }
    // AOSP removeGsi() also deletes shared boot status, invalidating other slots on R.
    // For multiple slots, use the slot-scoped image service and remove only that slot's
    // two registration markers. No recursive removal, no direct partition/image writes.
    @SuppressWarnings("unchecked")
    private static void remove(String target) throws Exception {
        idle();
        if (new File(growthDirectory(target), "growth.json").exists()) throw new IllegalStateException("此槽保留着扩容备份，请先恢复或确认清理备份再删除槽");
        if (!multi()) {
            if (!"dsu".equals(target)) throw new IllegalArgumentException("仅支持默认槽");
            truth(call("removeGsi")); return;
        }
        validateSlot(target);
        List<String> all = slots();
        File directory = slotDirectory(target);
        File installDir = new File(directory, "install_dir");
        File complete = new File(directory, "complete");
        if (!installDir.isFile() || !directory.canWrite())
            throw new IllegalStateException("非标准的 DSU 元数据布局，无法安全删除此槽");
        for (File marker : new File[]{installDir, complete}) {
            if (!marker.getCanonicalFile().equals(new File(directory.getCanonicalFile(), marker.getName())))
                throw new SecurityException("拒绝访问符号链接元数据");
        }
        Object images = call("openImageService", "dsu/" + target);
        List<String> backing = (List<String>) imageCall(images, "getAllBackingImages");
        for (String image : backing) {
            if (!image.matches("[a-zA-Z0-9_-]+_gsi") || Boolean.TRUE.equals(imageCall(images, "isImageMapped", image)))
                throw new IllegalStateException("存在未知或仍映射的镜像，已阻止删除");
        }
        String previous = activeSlot();
        boolean wasEnabled = flag("isGsiEnabled");
        if (all.size() == 1 && complete.isFile() && previous.equals(target)) {
            if (!previous.equals(target)) enable(target, true);
            truth(call("removeGsi"));
        } else {
            if (previous.equals(target)) {
                String replacement = null;
                for (String candidate : all) {
                    File marker = new File(slotDirectory(candidate), "complete");
                    if (!candidate.equals(target) && !maintenance(candidate).isFile() && marker.isFile() && "OK".equals(new String(Files.readAllBytes(marker.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim())) { replacement = candidate; break; }
                }
                if (replacement == null) throw new IllegalStateException("没有可保留的完整槽，无法安全删除当前槽");
                enable(replacement, oneShotFile.isFile());
                if (!wasEnabled) truth(call("disableGsi"));
            }
            for (String image : backing) imageCall(images, "deleteBackingImage", image);
            if (!((List<?>) imageCall(images, "getAllBackingImages")).isEmpty())
                throw new IllegalStateException("镜像尚未完全删除，保留槽位记录以便重试");
            Files.deleteIfExists(complete.toPath());
            Files.delete(installDir.toPath());
        }
        if (slots().contains(target)) throw new IllegalStateException("槽位仍存在，请刷新后检查系统日志");
        Files.deleteIfExists(maintenance(target).toPath());
    }
    private static void progress(String text) {
        java.io.PrintStream output = exportMode ? System.err : System.out;
        output.println("ULTRADSU_PROGRESS:" + text); output.flush();
    }
    private static void progress(String text, long bytes, long totalBytes) throws Exception {
        JSONObject event = new JSONObject().put("message", text).put("bytes", bytes).put("totalBytes", totalBytes);
        java.io.PrintStream output = exportMode ? System.err : System.out;
        output.println("ULTRADSU_PROGRESS:" + event); output.flush();
    }
    private static void closePartitionIfSupported() throws Exception {
        try { api.getMethod("closePartition"); }
        catch (NoSuchMethodException ignored) { return; }
        successCode(call("closePartition"));
    }
    private static void writeImage(File file, long size) throws Exception {
        progress("写入 " + writingPartition, 0, size);
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try (GZIPInputStream input = new GZIPInputStream(new FileInputStream(file), 1024 * 1024);
                 OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                byte[] buffer = new byte[1024 * 1024]; long written = 0, reportAt = 0;
                for (int n; (n = input.read(buffer)) != -1; ) {
                    checkpoint();
                    written += n;
                    if (written > size) throw new IllegalStateException("镜像大小与准备结果不一致");
                    output.write(buffer, 0, n);
                    if (System.currentTimeMillis() - reportAt > 1000) {
                        progress("写入 " + writingPartition, written, size);
                        reportAt = System.currentTimeMillis();
                    }
                }
                if (written != size) throw new IllegalStateException("镜像被截断");
            } catch (Throwable e) { failure.set(e); try { pipe[1].close(); } catch (Exception ignored) { } }
        }, "UltraDSU-image-writer");
        writer.start();
        try { truth(installCall("commitGsiChunkFromStream", pipe[0], size)); }
        finally { pipe[0].close(); writer.join(10000); }
        if (writer.isAlive()) throw new IllegalStateException("镜像写入线程未结束");
        if (failure.get() != null) throw new IllegalStateException("镜像流失败：" + failure.get());
        progress("已写入 " + writingPartition, size, size);
    }
    // The stock system installer calls remove() before installing, so named extras alone
    // cannot preserve another installation. This root path never calls global remove().
    private static void install(String slot, File source, long userdata, long system) throws Exception {
        installImages(slot, Arrays.asList(new ImageSpec("system", source, system)), userdata);
    }
    private static void validateImages(List<ImageSpec> images, boolean requireSystem) throws Exception {
        if (images.isEmpty() || images.size() > 32) throw new IllegalArgumentException("无效的分区清单");
        Set<String> names = new HashSet<>();
        for (ImageSpec image : images) {
            if (!READ_ONLY.contains(image.partition) || !names.add(image.partition) || !image.file.isFile() || image.bytes <= 0 || image.bytes > (128L << 30) || image.bytes % 4096 != 0)
                throw new IllegalArgumentException("无效或重复分区：" + image.partition);
        }
        if (requireSystem && !names.contains("system")) throw new IllegalArgumentException("安装新槽必须包含 system");
    }
    private static List<ImageSpec> readManifest(File manifest) throws Exception {
        if (manifest.length() > 128 * 1024) throw new IllegalArgumentException("分区清单过大");
        JSONArray array = new JSONArray(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
        List<ImageSpec> images = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.getJSONObject(i);
            images.add(new ImageSpec(entry.getString("partition"), new File(entry.getString("path")), entry.getLong("bytes")));
        }
        return images;
    }
    private static void installImages(String slot, List<ImageSpec> images, long userdata) throws Exception {
        checkpoint();
        idle();
        if (!multi()) throw new IllegalStateException("内置多槽安装需要 Android 11+ 命名接口");
        slotDirectory(slot);
        if (slots().contains(slot)) throw new IllegalStateException("已有同名槽或未完成安装，拒绝覆盖");
        validateImages(images, true);
        if (userdata < (1L << 30) || userdata > (512L << 30))
            throw new IllegalArgumentException("无效的镜像或分区大小");
        String previous = activeSlot();
        boolean enabled = flag("isGsiEnabled"), once = oneShotFile.isFile();
        boolean opened = false, completed = false;
        try {
            progress("创建槽 " + slot);
            File directory = prepareInstallDirectory(slot);
            installStep("打开安装目录 " + directory, "openInstall", directory.getPath());
            opened = true;
            progress("分配 userdata，可能需要数分钟");
            successCode(installCall("createPartition", "userdata", userdata, false)); closePartitionIfSupported();
            for (ImageSpec image : images) {
                checkpoint();
                writingPartition = image.partition;
                progress("分配 " + image.partition + "，可能需要数分钟");
                successCode(installCall("createPartition", image.partition, image.bytes, true));
                imageWriter.write(image.file, image.bytes); closePartitionIfSupported();
            }
            finishCancellation();
            progress("正在完成安装并校验槽位");
            installStep("完成安装", "closeInstall");
            File complete = new File(slotDirectory(slot), "complete");
            if (!complete.isFile() || !"OK".equals(new String(Files.readAllBytes(complete.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim()))
                throw new IllegalStateException("系统未写入完整安装标记");
            // R keeps installer_ alive until enableGsi; later Android finalizes it in closeInstall.
            if (flag("isGsiInstallInProgress")) successCode(call("enableGsi", true, slot));
            completed = true;
            progress("恢复启动选择");
            if (!previous.isEmpty() && slots().contains(previous)) {
                enable(previous, once);
                if (!enabled) truth(call("disableGsi"));
            } else if (flag("isGsiEnabled")) truth(call("disableGsi"));
        } catch (Exception error) {
            if (opened && !completed) {
                try {
                    truth(call("cancelGsiInstall"));
                    if (slots().contains(slot)) remove(slot);
                } catch (Exception cleanup) {
                    throw new IllegalStateException(error.getMessage() + "；新槽清理失败，请在系统槽中清理未完成安装：" + cleanup.getMessage(), error);
                }
            }
            if (completed) throw new IllegalStateException("镜像已安装，但恢复启动选择失败，请刷新检查：" + error.getMessage(), error);
            checkpoint();
            throw error;
        }
    }
    interface ExportSource extends AutoCloseable {
        long size();
        java.io.InputStream input();
        void close() throws Exception;
    }
    interface ExportOpener { ExportSource open(Object images, String name) throws Exception; }
    private static ExportOpener exportOpener = RootBridge::openExport;
    private static ExportSource openExport(Object images, String name) throws Exception {
        Object mapping = Class.forName("android.gsi.MappedImage").getConstructor().newInstance();
        boolean mapped = false;
        try {
            imageCall(images, "mapImageDevice", name, 10000, mapping); mapped = true;
            String path = (String)mapping.getClass().getField("path").get(mapping);
            if (!path.matches("/dev/block/dm-[0-9]+")) throw new SecurityException("非标准 DSU 块映射");
            java.lang.Process query = new ProcessBuilder("/system/bin/blockdev", "--getsize64", path).redirectErrorStream(true).start();
            long size;
            try {
                if (!query.waitFor(5, TimeUnit.SECONDS) || query.exitValue() != 0) throw new IllegalStateException("无法读取分区容量");
                byte[] result = new byte[128]; int length = query.getInputStream().read(result);
                size = Long.parseLong(new String(result, 0, Math.max(0, length), StandardCharsets.US_ASCII).trim());
            } finally { query.destroy(); }
            FileInputStream input = new FileInputStream(path);
            final long bytes = size;
            return new ExportSource() {
                public long size() { return bytes; }
                public java.io.InputStream input() { return input; }
                public void close() throws Exception {
                    try { input.close(); } finally { imageCall(images, "unmapImageDevice", name); }
                }
            };
        } catch (Exception failure) {
            if (mapped) try { imageCall(images, "unmapImageDevice", name); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    @SuppressWarnings("unchecked")
    private static JSONObject exportPartition(String slot, String partition, OutputStream output) throws Exception {
        idle(); if (!multi()) throw new IllegalStateException("分区提取需要 Android 11+ 和 Root"); validateSlot(slot);
        if (!READ_ONLY.contains(partition) && !partition.equals("userdata")) throw new IllegalArgumentException("不支持提取此分区");
        if (pendingPartitions(slot).contains(partition)) throw new IllegalStateException("此分区尚待修复，无法提取完整镜像");
        Object images = call("openImageService", "dsu/" + slot);
        String name = partition + "_gsi";
        if (!((List<String>)imageCall(images, "getAllBackingImages")).contains(name)) throw new IllegalStateException("此分区不存在");
        if (Boolean.TRUE.equals(imageCall(images, "isImageMapped", name))) throw new IllegalStateException("此分区正在使用或映射，暂不可提取");
        checkpoint();
        JSONObject result;
        try (ExportSource source = exportOpener.open(images, name)) {
            String hash = RawImageExport.copy(source.input(), output, source.size(), RootBridge::checkpoint,
                (done, total) -> progress("提取 " + partition, done, total));
            result = new JSONObject().put("bytes", source.size()).put("sha256", hash);
        }
        finishCancellation();
        return result;
    }
    private static long readImageSize(Object images, String name) throws Exception {
        if (Boolean.TRUE.equals(imageCall(images, "isImageMapped", name))) return 0;
        boolean mapped = false;
        try {
            Object mapping = Class.forName("android.gsi.MappedImage").getConstructor().newInstance();
            imageCall(images, "mapImageDevice", name, 10000, mapping); mapped = true;
            String path = (String) mapping.getClass().getField("path").get(mapping);
            java.lang.Process process = new ProcessBuilder("/system/bin/blockdev", "--getsize64", path).redirectErrorStream(true).start();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) return 0;
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[1024]; int n;
                while ((n = process.getInputStream().read(buffer)) != -1 && out.size() < 8192) out.write(buffer, 0, n);
                return Long.parseLong(new String(out.toByteArray(), StandardCharsets.UTF_8).trim());
            } finally { process.destroy(); }
        } catch (ReflectiveOperationException | java.io.IOException | NumberFormatException ignored) { return 0; }
        finally { if (mapped) imageCall(images, "unmapImageDevice", name); }
    }
    private static Set<String> pendingPartitions(String slot) throws Exception {
        Set<String> pending = new LinkedHashSet<>(); File file = maintenance(slot);
        if (UserdataGrowth.blocked(growthDirectory(slot))) pending.add("userdata");
        if (file.isFile()) {
            JSONArray array = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                String name = array.getString(i);
                if (!READ_ONLY.contains(name) && !name.equals("userdata")) throw new IllegalStateException("分区维护记录损坏");
                pending.add(name);
            }
        }
        return pending;
    }
    private static void savePending(String slot, Set<String> pending) throws Exception {
        File file = maintenance(slot);
        if (pending.isEmpty()) { Files.deleteIfExists(file.toPath()); return; }
        File temp = new File(file.getPath() + ".partial");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
            out.write(new JSONArray(pending).toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    @SuppressWarnings("unchecked")
    private static JSONObject inventory(String slot) throws Exception {
        idle(); if (!multi()) throw new IllegalStateException("槽内分区管理需要 Android 11+"); validateSlot(slot);
        Object images = call("openImageService", "dsu/" + slot); JSONArray partitions = new JSONArray();
        for (String name : (List<String>) imageCall(images, "getAllBackingImages")) {
            if (!name.matches("[a-zA-Z0-9_-]+_gsi")) continue;
            boolean mapped = Boolean.TRUE.equals(imageCall(images, "isImageMapped", name));
            partitions.put(new JSONObject().put("name", name.substring(0, name.length() - 4))
                .put("bytes", mapped ? 0 : imageSizeReader.size(images, name)).put("mapped", mapped));
        }
        return new JSONObject().put("partitions", partitions).put("pending", new JSONArray(pendingPartitions(slot)));
    }
    @SuppressWarnings("unchecked")
    private static void mutate(String slot, List<ImageSpec> replacements, long userdata) throws Exception {
        idle(); if (!multi()) throw new IllegalStateException("分区替换需要 Android 11+"); validateSlot(slot);
        if (UserdataGrowth.blocked(growthDirectory(slot))) throw new IllegalStateException("此槽扩容未完成，请使用扩容备份恢复，不能重写分区");
        if (userdata > 0 && new File(growthDirectory(slot), "growth.json").exists()) throw new IllegalStateException("请先清理已确认的扩容备份，再执行清空重建");
        if (userdata == 0) validateImages(replacements, false);
        else if (userdata < (1L << 30) || userdata > (512L << 30)) throw new IllegalArgumentException("userdata 容量须在 1–512 GiB");
        File directory = slotDirectory(slot), complete = new File(directory, "complete");
        File installFile = new File(directory, "install_dir");
        String path = new String(Files.readAllBytes(installFile.toPath()), StandardCharsets.UTF_8).trim().replaceAll("/+$", "");
        if ((!path.startsWith("/data/gsi/") && !path.startsWith("/mnt/media_rw/")) || !path.endsWith("/" + slot) || path.contains("/../"))
            throw new IllegalStateException("非标准的槽镜像目录，拒绝修改");
        if (path.startsWith("/data/gsi/dsu/") && !internalMultiSlot())
            throw new IllegalStateException("当前 gsid 不支持此内部槽路径，请先重新启用已适配的多槽补丁，再修改分区；槽内容保持不变");
        Object images = call("openImageService", "dsu/" + slot);
        List<String> existing = (List<String>) imageCall(images, "getAllBackingImages");
        Set<String> originalPending = pendingPartitions(slot);
        Set<String> targets = new LinkedHashSet<>();
        if (userdata > 0) {
            boolean repairing = originalPending.contains("userdata");
            if (!existing.contains("userdata_gsi") && !repairing) throw new IllegalStateException("槽内没有 userdata");
            if (Boolean.TRUE.equals(imageCall(images, "isImageMapped", "userdata_gsi"))) throw new IllegalStateException("userdata 仍映射，拒绝重建");
            long current = existing.contains("userdata_gsi") ? imageSizeReader.size(images, "userdata_gsi") : 0;
            if (current <= 0 && !repairing) throw new IllegalStateException("无法读取现有 userdata 容量，不能核实扩容，请检查 blockdev / gsid 支持");
            if (userdata < current || (!repairing && userdata == current)) throw new IllegalArgumentException("新容量必须大于现有 userdata 容量");
            targets.add("userdata");
        } else for (ImageSpec image : replacements) {
            if (existing.contains(image.partition + "_gsi") && Boolean.TRUE.equals(imageCall(images, "isImageMapped", image.partition + "_gsi")))
                throw new IllegalStateException(image.partition + " 仍映射，拒绝替换");
            targets.add(image.partition);
        }
        if (!existing.contains("system_gsi")) originalPending.add("system");
        if (!existing.contains("userdata_gsi")) originalPending.add("userdata");
        Set<String> affected = new LinkedHashSet<>(originalPending);
        affected.addAll(targets);
        String previous = activeSlot(); boolean wasEnabled = flag("isGsiEnabled"), once = oneShotFile.isFile();
        boolean opened = false, successful = false;
        savePending(slot, affected);
        if (previous.equals(slot) && wasEnabled) truth(call("disableGsi"));
        try {
            opened = true; successCode(call("openInstall", path));
            // Retain the slot registration during maintenance. Otherwise gsid startup cleanup
            // could reclaim unrelated partitions after an interrupted replacement.
            Files.write(complete.toPath(), "OK".getBytes(StandardCharsets.UTF_8));
            if (userdata > 0) {
                progress("重建 userdata · 原数据将清除");
                successCode(call("createPartition", "userdata", userdata, false)); closePartitionIfSupported();
            } else for (ImageSpec image : replacements) {
                writingPartition = image.partition; progress("替换 " + image.partition);
                successCode(call("createPartition", image.partition, image.bytes, true));
                imageWriter.write(image.file, image.bytes); closePartitionIfSupported();
            }
            progress("正在确认分区修改结果");
            successCode(call("closeInstall"));
            if (flag("isGsiInstallInProgress")) successCode(call("enableGsi", true, slot));
            Set<String> remaining = new LinkedHashSet<>(originalPending); remaining.removeAll(targets); savePending(slot, remaining);
            successful = true;
        } catch (Exception error) {
            if (opened) {
                try { truth(call("cancelGsiInstall")); Files.write(complete.toPath(), "OK".getBytes(StandardCharsets.UTF_8)); }
                catch (Exception cleanup) { error.addSuppressed(cleanup); }
            }
            throw new IllegalStateException("分区修改未完成，其他分区保留；该槽已阻止启动，请重写 " + targets + "：" + error.getMessage(), error);
        } finally {
            if (!previous.isEmpty() && slots().contains(previous) && !maintenance(previous).isFile()) {
                enable(previous, once); if (!wasEnabled) truth(call("disableGsi"));
            } else if (flag("isGsiEnabled")) truth(call("disableGsi"));
            if (!successful && previous.equals(slot)) progress("该槽需修复，已保持停用");
        }
    }
    private static long blockSize(File path) throws Exception {
        java.lang.Process process = new ProcessBuilder("/system/bin/blockdev", "--getsize64", path.getPath()).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IllegalStateException("无法读取块设备容量");
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                return Long.parseLong(reader.readLine().trim());
            }
        } finally { process.destroy(); }
    }
    private static String growthKeyIdentity(String slot) throws Exception {
        File key = new File("/metadata/vold/metadata_encryption/dsu/" + slot + "/key/encrypted_key");
        if (!key.exists()) return "plain";
        if (!key.getCanonicalFile().equals(key.getAbsoluteFile()) || key.length() > 16384) throw new SecurityException("非标准的槽位密钥记录");
        return AssistanceProtocol.digest(Files.readAllBytes(key.toPath()));
    }
    private static void assistanceHost(String slot) throws Exception {
        if (flag("isGsiRunning")) throw new IllegalStateException("请先返回主系统"); validateSlot(slot);
        String path = new String(Files.readAllBytes(new File(slotDirectory(slot), "install_dir").toPath()), StandardCharsets.UTF_8).trim().replaceAll("/+$", "");
        if (platformSdk < 36 || !multi() || !internalMultiSlot() || !path.equals("/data/gsi/dsu/" + slot))
            throw new IllegalStateException("客体协作需要 Android 16+ 主系统及标准内部多槽布局");
    }
    private static JSONObject assistance(String action, String[] args) throws Exception {
        if (action.equals("assist-capture") || action.equals("assist-return")) {
            if (!flag("isGsiRunning")) throw new IllegalStateException("此步骤必须在目标客体系统中完成");
            String slot = activeSlot(); validateSlot(slot);
            JSONObject request = GuestAssistance.request(slot);
            if (action.equals("assist-capture")) {
                if (GuestAssistance.status(slot, false).optString("phase").equals("CAPTURED")) return GuestAssistance.status(slot, false);
                JSONObject sealed = NativeGrowthTools.captureGuest(slot, request.getString("session"), request.getString("publicKey"));
                GuestAssistance.captured(slot, sealed);
            } else truth(call("disableGsi"));
            return GuestAssistance.status(slot, false);
        }
        String slot = args[1]; assistanceHost(slot);
        if (!action.equals("assist-info")) idle();
        File directory = GuestAssistance.directory(slot);
        File boot = new File(directory, "boot.json");
        if (action.equals("assist-info") && boot.exists() && !flag("isGsiInstallInProgress")) {
            if (activeSlot().equals(slot)) restoreGrowthBoot(directory, GuestAssistance.read(directory, "boot.json"));
            else { Files.delete(boot.toPath()); NativeGrowthTools.syncDirectory(directory); }
        }
        switch (action) {
            case "assist-prepare":
                if (new File(growthDirectory(slot), "growth.json").exists()) throw new IllegalStateException("请先恢复或清理已有扩容备份，再准备新的协作");
                GuestAssistance.prepare(slot, args[2], args[3]); break;
            case "assist-boot":
                GuestAssistance.request(slot);
                if (!boot.exists()) saveGrowthBoot(directory, activeSlot(), flag("isGsiEnabled"), oneShotFile.isFile());
                enable(slot, true); break;
            case "assist-clear":
                if (new File(growthDirectory(slot), "growth.json").exists()) throw new IllegalStateException("已有扩容备份，必须保留协作凭据供恢复");
                if (boot.exists()) restoreGrowthBoot(directory, GuestAssistance.read(directory, "boot.json"));
                GuestAssistance.clear(slot); break;
            case "assist-info": break;
            default: throw new IllegalArgumentException("未知协作操作");
        }
        return GuestAssistance.status(slot, action.equals("assist-info"));
    }
    private static void saveGrowthBoot(File directory, String active, boolean enabled, boolean once) throws Exception {
        File temp = new File(directory, "boot.partial"), target = new File(directory, "boot.json");
        if (!temp.getCanonicalFile().equals(temp.getAbsoluteFile()) || !target.getCanonicalFile().equals(target.getAbsoluteFile())) throw new SecurityException("无效的启动恢复记录");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(temp)) {
            output.write(new JSONObject().put("active", active).put("enabled", enabled).put("once", once).toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
        }
        android.system.Os.chmod(temp.getPath(), 0600);
        Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        NativeGrowthTools.syncDirectory(directory);
    }
    private static void restoreGrowthBoot(File directory, JSONObject boot) throws Exception {
        String active = boot.getString("active");
        if (!active.isEmpty() && active.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}") && slots().contains(active) &&
                !maintenance(active).isFile() && !UserdataGrowth.blocked(growthDirectory(active))) {
            enable(active, boot.getBoolean("once")); if (!boot.getBoolean("enabled")) truth(call("disableGsi"));
        } else if (flag("isGsiEnabled")) truth(call("disableGsi"));
        Files.deleteIfExists(new File(directory, "boot.json").toPath()); NativeGrowthTools.syncDirectory(directory);
    }
    private static JSONObject growth(String slot, String action, long bytes) throws Exception {
        if (flag("isGsiRunning")) throw new IllegalStateException("请先返回主系统");
        if (!multi()) throw new IllegalStateException("设备没有多槽维护接口"); validateSlot(slot);
        if (maintenance(slot).isFile()) throw new IllegalStateException("请先完成现有分区修复");
        File slotMeta = slotDirectory(slot), installFile = new File(slotMeta, "install_dir"), complete = new File(slotMeta, "complete");
        String installPath = new String(Files.readAllBytes(installFile.toPath()), StandardCharsets.UTF_8).trim().replaceAll("/+$", "");
        if (!installPath.equals("/data/gsi/dsu/" + slot) || !new File(installPath).getCanonicalPath().equals(installPath) || !internalMultiSlot())
            throw new IllegalStateException("保留数据扩容仅支持已适配的内部多槽标准布局");
        NativeGrowthTools.privateDirectory(growthRoot.getParentFile()); NativeGrowthTools.privateDirectory(growthRoot);
        File directory = growthDirectory(slot); NativeGrowthTools.privateDirectory(directory);
        File bootFile = new File(directory, "boot.json"); JSONObject interruptedBoot = null;
        if (bootFile.exists()) {
            if (!bootFile.getCanonicalFile().equals(bootFile.getAbsoluteFile()) || bootFile.length() > 4096) throw new SecurityException("启动恢复记录损坏");
            interruptedBoot = new JSONObject(new String(Files.readAllBytes(bootFile.toPath()), StandardCharsets.UTF_8));
        }
        if (flag("isGsiInstallInProgress")) {
            if (interruptedBoot == null || !installPath.equals(String.valueOf(call("getInstalledGsiImageDir")).replaceAll("/+$", "")))
                throw new IllegalStateException("其他 DSU 安装会话正在进行，拒绝接管");
            successCode(call("closeInstall"));
        }
        if (interruptedBoot != null) restoreGrowthBoot(directory, interruptedBoot);
        Object images = call("openImageService", "dsu/" + slot);
        File journalFile = new File(directory, "growth.json");
        if (journalFile.exists() && journalFile.length() <= 8192) {
            String phase = new JSONObject(new String(Files.readAllBytes(journalFile.toPath()), StandardCharsets.UTF_8)).getString("phase");
            if (!phase.equals("EXPANDED") && !phase.equals("ROLLED_BACK") && !phase.equals("ROLLED_BACK_RAW")) {
                NativeGrowthTools.run("cleanup", slot, new File("/dev/block/dm-0"));
                if (Boolean.TRUE.equals(imageCall(images, "isImageMapped", "userdata_gsi"))) imageCall(images, "unmapImageDevice", "userdata_gsi");
            }
        }
        if (Boolean.TRUE.equals(imageCall(images, "isImageMapped", "userdata_gsi"))) throw new IllegalStateException("userdata 当前仍映射，拒绝扩容");
        String previous = activeSlot(); boolean wasEnabled = flag("isGsiEnabled"), once = oneShotFile.isFile();
        boolean[] opened = {false};
        UserdataGrowth.Backend backend = new UserdataGrowth.Backend() {
            public UserdataGrowth.Mapping map() throws Exception {
                if (Boolean.TRUE.equals(imageCall(images, "isImageMapped", "userdata_gsi"))) throw new IllegalStateException("userdata 已被其他操作映射");
                Object mapping = Class.forName("android.gsi.MappedImage").getConstructor().newInstance();
                imageCall(images, "mapImageDevice", "userdata_gsi", 10000, mapping);
                String path = (String)mapping.getClass().getField("path").get(mapping);
                if (!path.matches("/dev/block/dm-[0-9]+")) { imageCall(images,"unmapImageDevice","userdata_gsi"); throw new SecurityException("非标准 DSU 块映射"); }
                return new UserdataGrowth.Mapping() {
                    public File device() { return new File(path); }
                    public void close() throws Exception { imageCall(images, "unmapImageDevice", "userdata_gsi"); }
                };
            }
            public long size() throws Exception { try (UserdataGrowth.Mapping mapping = map()) { return blockSize(mapping.device()); } }
            public JSONObject inspect(boolean check) throws Exception {
                try (UserdataGrowth.Mapping mapping = map()) { return NativeGrowthTools.run(check ? "check" : "probe", slot, mapping.device()).put("assisted", NativeGrowthTools.assisted()); }
            }
            public String keyIdentity() throws Exception {
                return growthKeyIdentity(slot);
            }
            public void allocate(long target) throws Exception {
                if (!opened[0]) {
                    saveGrowthBoot(directory, previous, wasEnabled, once);
                    if (previous.equals(slot) && flag("isGsiEnabled")) truth(call("disableGsi"));
                    successCode(call("openInstall", installPath)); opened[0] = true;
                    Files.write(complete.toPath(), "OK".getBytes(StandardCharsets.UTF_8));
                }
                progress("分配 userdata，完整恢复备份已保留");
                installStep("分配 userdata", "createPartition", "userdata", target, false); closePartitionIfSupported();
            }
            public void expand(long oldFs, long target) throws Exception {
                try (UserdataGrowth.Mapping mapping = map()) { NativeGrowthTools.run("resize", slot, mapping.device(), oldFs, target); }
            }
            public void syncDirectory(File folder) throws Exception { NativeGrowthTools.syncDirectory(folder); }
        };
        UserdataGrowth transaction = new UserdataGrowth(directory, backend, RootBridge::progress);
        try {
            switch (action) {
                case "grow-info":
                    JSONObject info = new JSONObject().put("growth", transaction.status()).put("assistance", GuestAssistance.status(slot, false));
                    try { info.put("filesystem", backend.inspect(true)); info.put("supported", true); }
                    catch (Exception problem) {
                        String reason = String.valueOf(problem.getMessage());
                        info.put("supported", false).put("reason", reason).put("canAssist", !info.getJSONObject("growth").optBoolean("exists") &&
                            (reason.contains("Cannot unseal") || reason.contains("FBE key records")));
                    }
                    return info;
                case "grow":
                    if (bytes < (1L << 30) || bytes > (512L << 30)) throw new IllegalArgumentException("新容量须为 1–512 GiB");
                    long reserve = 0;
                    if (Boolean.TRUE.equals(Class.forName("android.os.SystemProperties").getMethod("getBoolean", String.class, boolean.class)
                            .invoke(null, "ro.virtual_ab.enabled", false))) reserve = blockSize(new File("/dev/block/by-name/super"));
                    if (directory.getUsableSpace() < backend.size() + bytes + reserve + (256L << 20))
                        throw new IllegalStateException("空间不足：需同时容纳完整备份、新镜像及系统 Virtual A/B 预留空间");
                    transaction.grow(bytes); break;
                case "grow-rollback": transaction.rollback(); break;
                case "grow-discard": transaction.discard(); break;
                default: throw new IllegalArgumentException("未知扩容操作");
            }
            return new JSONObject().put("growth", transaction.status());
        } finally {
            if (opened[0]) {
                // Never cancelGsiInstall: it may remove other partitions in this slot.
                successCode(call("closeInstall"));
                restoreGrowthBoot(directory, new JSONObject().put("active", previous).put("enabled", wasEnabled).put("once", once));
            }
        }
    }
    /** Listing and cleanup need neither a native resizer nor access to encryption keys. */
    private static JSONObject userdataBackups(String deleteSlot, String kind, String revision) throws Exception {
        idle();
        List<String> installed = multi() ? slots() : (flag("isGsiInstalled") ? Arrays.asList("dsu") : new ArrayList<>());
        JSONArray entries = new JSONArray(); List<File> protectedFiles = new ArrayList<>();
        String protectionError = "";
        for (String slot : installed) {
            if (!slot.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}")) throw new SecurityException("系统槽列表含无效标识");
            long bytes = 0; String detail = "槽当前绑定的 userdata，不可删除（未启动时同样受保护）";
            try {
                File record = new File(slotDirectory(slot), "install_dir");
                if (!record.getCanonicalFile().equals(record.getAbsoluteFile()) || !record.isFile() || record.length() > 4096)
                    throw new SecurityException();
                File dir = new File(new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8).trim());
                if (!imageRoot.getCanonicalFile().equals(imageRoot.getAbsoluteFile()) ||
                    !dir.getCanonicalFile().equals(dir.getAbsoluteFile()) ||
                    (!dir.equals(imageRoot) && !dir.equals(new File(imageRoot, slot)))) throw new SecurityException();
                File[] files = dir.listFiles(); if (files == null) throw new IllegalStateException();
                boolean found = false;
                for (File file : files) {
                    if (!file.getName().matches("userdata_gsi\\.img(?:\\.[0-9]+)?")) continue;
                    if (!file.getCanonicalFile().equals(file.getAbsoluteFile()) || !file.isFile()) throw new SecurityException();
                    protectedFiles.add(file); found = true;
                    if (!file.getName().equals("userdata_gsi.img")) bytes = Math.addExact(bytes, file.length());
                }
                if (!found) throw new IllegalStateException();
            } catch (Exception unavailable) {
                detail = "无法完整核实在用 userdata，仍禁止删除";
                protectionError = "无法核实全部在用镜像，已保护备份；请检查槽位布局后刷新";
            }
            entries.put(new JSONObject().put("slot", slot).put("kind", "current").put("bytes", bytes)
                .put("modified", 0).put("canDelete", false).put("reason", detail));
        }
        UserdataBackupStore store = new UserdataBackupStore(growthRoot, protectedFiles, backupDirectorySync::sync);
        if (deleteSlot != null) {
            if (!"growth".equals(kind)) throw new SecurityException("槽当前绑定的 userdata 不可删除");
            if (!protectionError.isEmpty()) throw new IllegalStateException(protectionError);
            // A maintenance marker can be the only evidence of another interrupted transaction.
            if (maintenance(deleteSlot).exists()) throw new IllegalStateException("此槽仍需修复，恢复备份不可删除");
            store.delete(deleteSlot, kind, revision);
        }
        JSONArray backups = store.list();
        for (int i = 0; i < backups.length(); i++) {
            JSONObject row = backups.getJSONObject(i);
            if (!protectionError.isEmpty() || maintenance(row.getString("slot")).exists())
                row.put("canDelete", false).put("reason", protectionError.isEmpty() ? "此槽仍需修复，恢复备份不可删除" : protectionError);
            entries.put(row);
        }
        return new JSONObject().put("entries", entries);
    }
    public static void main(String[] args) {
        exportMode = args.length > 0 && args[0].equals("export");
        JSONObject out = new JSONObject();
        RandomAccessFile lockFile = null; java.nio.channels.FileLock lock = null;
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root required");
            File operationRoot = new File("/data/adb/ultradsu"); NativeGrowthTools.privateDirectory(operationRoot);
            File lockPath = new File(operationRoot, "operation.lock");
            if (!lockPath.getCanonicalFile().equals(lockPath.getAbsoluteFile())) throw new SecurityException("无效的操作锁");
            lockFile = new RandomAccessFile(lockPath, "rw"); lock = lockFile.getChannel().tryLock();
            if (lock == null) throw new IllegalStateException("另一个 UltraDSU 操作正在进行");
            // app_process runs outside an app sandbox; exempt reflection on hidden platform APIs.
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vm.getDeclaredMethod("getRuntime").invoke(null);
            vm.getDeclaredMethod("setHiddenApiExemptions", String[].class).invoke(runtime, (Object) new String[]{"L"});
            Class<?> manager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) manager.getMethod(Build.VERSION.SDK_INT >= 30 ? "waitForService" : "getService", String.class)
                .invoke(null, "gsiservice");
            if (binder == null) throw new IllegalStateException("设备未提供 gsid 服务");
            api = Class.forName("android.gsi.IGsiService");
            service = Class.forName("android.gsi.IGsiService$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
            if (args.length > 0 && args[args.length - 1].equals("--cancellable")) {
                args = Arrays.copyOf(args, args.length - 1);
                if (!Arrays.asList("install-package", "export").contains(args[0])) throw new SecurityException("此操作不允许取消");
                cancellationEnabled = true; acceptingCancellation = true;
                Thread monitor = new Thread(() -> {
                    try { System.in.read(); requestCancellation(); }
                    catch (java.io.IOException closed) { requestCancellation(); }
                }, "UltraDSU-cancel-monitor");
                monitor.setDaemon(true); monitor.start();
            }
            if (args.length > 0 && args[args.length - 1].equals("--assist")) {
                args = Arrays.copyOf(args, args.length - 1);
                if (!Arrays.asList("grow-info", "grow", "grow-rollback").contains(args[0]) || flag("isGsiRunning")) throw new SecurityException("不允许使用协作交接");
                validateSlot(args[1]);
                android.system.Os.prctl(android.system.OsConstants.PR_SET_DUMPABLE, 0, 0, 0, 0);
                byte[] packet = new byte[196];
                try {
                    new java.io.DataInputStream(System.in).readFully(packet);
                    if (System.in.read() != -1) throw new SecurityException("协作交接数据过多");
                    AssistanceProtocol.validatePacket(packet, GuestAssistance.request(args[1]), growthKeyIdentity(args[1]));
                    NativeGrowthTools.setAssistance(args[1], packet);
                } finally { Arrays.fill(packet, (byte)0); }
            }
            switch (args[0]) {
                case "assist-info": case "assist-prepare": case "assist-boot": case "assist-clear":
                case "assist-capture": case "assist-return": out = assistance(args[0], args); break;
                case "status": out = status(); break;
                case "enable":
                    idle();
                    if (multi()) enable(args[1], !"sticky".equals(args[2]));
                    else {
                        if (!"dsu".equals(args[1])) throw new IllegalArgumentException("仅支持默认槽");
                        if (flag("isGsiEnabled")) truth(call("disableGsi"));
                        successCode(call("setGsiBootable", !"sticky".equals(args[2])));
                    }
                    break;
                case "disable":
                    if (flag("isGsiInstallInProgress")) throw new IllegalStateException("安装中无法停用");
                    truth(call("disableGsi")); break;
                case "remove": remove(args[1]); break;
                case "install":
                    install(args[1], new File(args[2]), Long.parseLong(args[3]), Long.parseLong(args[4])); break;
                case "install-package":
                    installImages(args[1], readManifest(new File(args[2])), Long.parseLong(args[3])); break;
                case "images": out = inventory(args[1]); break;
                case "export": out = exportPartition(args[1], args[2], new java.io.FileOutputStream(java.io.FileDescriptor.out)); break;
                case "userdata-backups": out = userdataBackups(null, null, null); break;
                case "userdata-backup-delete": out = userdataBackups(args[1], args[2], args[3]); break;
                case "replace": mutate(args[1], readManifest(new File(args[2])), 0); break;
                case "resize": mutate(args[1], new ArrayList<>(), Long.parseLong(args[2])); break;
                case "grow-info": case "grow-rollback": case "grow-discard": out = growth(args[1], args[0], 0); break;
                case "grow": out = growth(args[1], args[0], Long.parseLong(args[2])); break;
                default: throw new IllegalArgumentException("Unknown operation");
            }
            out.put("ok", true);
        } catch (Throwable e) {
            while (e instanceof InvocationTargetException && e.getCause() != null) e = e.getCause();
            try { out.put("ok", false); out.put("cancelled", e instanceof CancellationException); out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); }
            catch (Exception ignored) { }
        } finally {
            NativeGrowthTools.clearAssistance();
            try { if (lock != null) lock.release(); if (lockFile != null) lockFile.close(); } catch (Exception ignored) { }
        }
        (exportMode ? System.err : System.out).println("ULTRADSU:" + out);
        System.exit(0);
    }
}
