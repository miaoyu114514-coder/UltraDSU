package io.github.ultradsu.dsu;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Root-owned extraction of tools from this signed APK; never accepts an executable path from UI. */
final class NativeGrowthTools {
    private static byte[] assistance;
    private static String assistanceSlot;
    static boolean assisted() { return assistance != null; }
    static void setAssistance(String slot, byte[] packet) {
        if (packet.length != 196) throw new SecurityException("协作交接长度无效");
        assistance = Arrays.copyOfRange(packet, 68, 196); assistanceSlot = slot;
    }
    static void clearAssistance() { if (assistance != null) Arrays.fill(assistance, (byte)0); assistance = null; assistanceSlot = null; }
    static void privateDirectory(File folder) throws Exception {
        if (!folder.getCanonicalFile().equals(folder.getAbsoluteFile())) throw new SecurityException("维护目录不能是符号链接");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("无法创建维护目录");
        if (Os.stat(folder.getPath()).st_uid != 0) throw new SecurityException("维护目录必须由 Root 所有");
        Os.chmod(folder.getPath(), 0700);
    }
    static void syncDirectory(File folder) throws Exception {
        java.io.FileDescriptor fd = Os.open(folder.getPath(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
        try { Os.fsync(fd); } finally { Os.close(fd); }
    }
    private static File install() throws Exception {
        if (Build.VERSION.SDK_INT < 36 || !Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a"))
            throw new IllegalStateException("保留数据扩容暂支持 Android 16+ 的 arm64 设备，仍需通过本机预检");
        String apk = System.getenv("CLASSPATH");
        if (apk == null || apk.contains(":")) throw new SecurityException("无法定位当前 APK");
        String[] names = {"ultradsu-maintenance", "resize.f2fs", "android.system.keystore2-V5-ndk.so", "android.hardware.security.keymint-V4-ndk.so"};
        List<byte[]> assets = new ArrayList<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (ZipFile zip = new ZipFile(apk)) {
            for (String name : names) {
                ZipEntry entry = zip.getEntry("assets/userdata-grow/arm64-v8a/" + name);
                if (entry == null || entry.getSize() <= 0 || entry.getSize() > 4 * 1024 * 1024) throw new IOException("此构建未包含扩容维护工具");
                byte[] data;
                try (InputStream input = zip.getInputStream(entry)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                    while ((n = input.read(buffer)) != -1) { if (bytes.size() + n > 4 * 1024 * 1024) throw new IOException("维护工具过大"); bytes.write(buffer, 0, n); }
                    data = bytes.toByteArray();
                }
                digest.update(data); assets.add(data);
            }
        }
        StringBuilder hash = new StringBuilder(); for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
        File root = new File("/data/adb/ultradsu"); privateDirectory(root);
        File parent = new File(root, "native"); privateDirectory(parent);
        File folder = new File(parent, hash.toString().substring(0, 24)); privateDirectory(folder);
        for (int i = 0; i < names.length; i++) {
            File target = new File(folder, names[i]);
            if (!target.getCanonicalFile().equals(target.getAbsoluteFile())) throw new SecurityException("维护工具不能是符号链接");
            byte[] data = assets.get(i);
            if (!target.isFile() || !Arrays.equals(Files.readAllBytes(target.toPath()), data)) {
                File partial = new File(folder, names[i] + ".partial");
                if (!partial.getCanonicalFile().equals(partial.getAbsoluteFile())) throw new SecurityException("拒绝工具临时符号链接");
                try (FileOutputStream output = new FileOutputStream(partial)) { output.write(data); output.getFD().sync(); }
                Os.chmod(partial.getPath(), i < 2 ? 0500 : 0400);
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        syncDirectory(folder);
        return folder;
    }
    static JSONObject run(String operation, String slot, File block, long... sizes) throws Exception {
        File tools = install();
        List<String> args = new ArrayList<>(Arrays.asList(new File(tools, "ultradsu-maintenance").getPath(), operation, slot, block.getPath()));
        for (long size : sizes) args.add(Long.toString(size));
        boolean assisted = slot.equals(assistanceSlot) && Arrays.asList("probe", "check", "resize").contains(operation);
        if (assisted) args.add("--assist");
        return execute(tools, args, assisted ? assistance : null);
    }
    static JSONObject captureGuest(String slot, String session, String publicKey) throws Exception {
        File tools = install();
        return execute(tools, Arrays.asList(new File(tools, "ultradsu-maintenance").getPath(), "assist-capture", slot, session, publicKey), null);
    }
    private static JSONObject execute(File tools, List<String> args, byte[] secret) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(args).redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH", tools.getPath() + ":/system/lib64");
        Process process = builder.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> result = executor.submit(() -> {
            StringBuilder tail = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line; while ((line = reader.readLine()) != null) {
                    JSONObject progress = NativeFilesystemProgress.parse(line);
                    if (progress != null) { System.out.println("ULTRADSU_PROGRESS:" + progress); System.out.flush(); }
                    tail.append(line).append('\n'); if (tail.length() > 65536) tail.delete(0, tail.length() - 65536);
                }
            }
            return tail.toString();
        });
        try {
            try (OutputStream stream = process.getOutputStream()) { if (secret != null) stream.write(secret); }
            // Large-volume maintenance must not be killed at one hour while still
            // doing valid I/O. The outer root operation has the same six-hour cap.
            if (!process.waitFor(args.size() > 1 && args.get(1).equals("resize") ? 6 : 1, TimeUnit.HOURS))
                throw new IOException("文件系统维护超时，备份已保留");
            String output = result.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IOException("扩容预检或文件系统维护失败：" + output.substring(Math.max(0, output.length() - 3000)));
            for (String line : output.split("\n")) if (line.startsWith("ULTRADSU_NATIVE:")) return new JSONObject(line.substring("ULTRADSU_NATIVE:".length()));
            throw new IOException("维护工具没有返回文件系统信息");
        } finally { process.destroy(); executor.shutdownNow(); }
    }
}
