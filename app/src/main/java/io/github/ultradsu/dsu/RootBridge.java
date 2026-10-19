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
import java.util.zip.GZIPInputStream;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final Set<String> READ_ONLY = new HashSet<>(Arrays.asList("system", "system_ext", "product", "vendor", "odm", "vendor_dlkm", "odm_dlkm", "system_dlkm"));
    private static String writingPartition = "system";
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
        if (maintenance(slot).isFile()) throw new IllegalStateException("此槽有未完成的分区修改，请先修复后再启动");
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
        System.out.println("ULTRADSU_PROGRESS:" + text); System.out.flush();
    }
    private static void closePartitionIfSupported() throws Exception {
        try { api.getMethod("closePartition"); }
        catch (NoSuchMethodException ignored) { return; }
        successCode(call("closePartition"));
    }
    private static void writeImage(File file, long size) throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try (GZIPInputStream input = new GZIPInputStream(new FileInputStream(file), 1024 * 1024);
                 OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                byte[] buffer = new byte[1024 * 1024]; long written = 0, reportAt = 0;
                for (int n; (n = input.read(buffer)) != -1; ) {
                    written += n;
                    if (written > size) throw new IllegalStateException("镜像大小与准备结果不一致");
                    output.write(buffer, 0, n);
                    if (System.currentTimeMillis() - reportAt > 1000) {
                        progress("写入 " + writingPartition + "  " + written / (1024 * 1024) + " / " + size / (1024 * 1024) + " MiB");
                        reportAt = System.currentTimeMillis();
                    }
                }
                if (written != size) throw new IllegalStateException("镜像被截断");
            } catch (Throwable e) { failure.set(e); try { pipe[1].close(); } catch (Exception ignored) { } }
        }, "UltraDSU-image-writer");
        writer.start();
        try { truth(call("commitGsiChunkFromStream", pipe[0], size)); }
        finally { pipe[0].close(); writer.join(10000); }
        if (writer.isAlive()) throw new IllegalStateException("镜像写入线程未结束");
        if (failure.get() != null) throw new IllegalStateException("镜像流失败：" + failure.get());
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
            installStep("分配 userdata", "createPartition", "userdata", userdata, false); closePartitionIfSupported();
            for (ImageSpec image : images) {
                writingPartition = image.partition;
                progress("分配 " + image.partition + "，可能需要数分钟");
                installStep("分配 " + image.partition, "createPartition", image.partition, image.bytes, true);
                imageWriter.write(image.file, image.bytes); closePartitionIfSupported();
            }
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
            throw error;
        }
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
    public static void main(String[] args) {
        JSONObject out = new JSONObject();
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root required");
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
            switch (args[0]) {
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
                case "replace": mutate(args[1], readManifest(new File(args[2])), 0); break;
                case "resize": mutate(args[1], new ArrayList<>(), Long.parseLong(args[2])); break;
                default: throw new IllegalArgumentException("Unknown operation");
            }
            out.put("ok", true);
        } catch (Throwable e) {
            while (e instanceof InvocationTargetException && e.getCause() != null) e = e.getCause();
            try { out.put("ok", false); out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); }
            catch (Exception ignored) { }
        }
        System.out.println("ULTRADSU:" + out);
        System.exit(0);
    }
}
