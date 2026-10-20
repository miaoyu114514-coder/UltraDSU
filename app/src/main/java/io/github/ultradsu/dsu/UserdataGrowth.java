package io.github.ultradsu.dsu;

import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;

/** Durable copy/verify/grow transaction. Only Backend may allocate or map a slot. */
final class UserdataGrowth {
    interface Mapping extends AutoCloseable { File device(); void close() throws Exception; }
    interface Backend {
        long size() throws Exception;
        JSONObject inspect(boolean check) throws Exception;
        Mapping map() throws Exception;
        void allocate(long bytes) throws Exception;
        void expand(long oldFilesystemBytes, long newBlockBytes) throws Exception;
        default void syncDirectory(File directory) throws Exception { }
        default String keyIdentity() throws Exception { return "plain"; }
    }
    interface Progress { void update(String phase, long bytes, long total) throws Exception; }
    private final File directory, journal, backup, partial;
    private final Backend backend;
    private final Progress progress;
    private static final String[] PHASES = {"BACKUP_PENDING", "BACKUP_VERIFIED", "REPLACING", "RAW_RESTORED",
        "EXPANDED", "RECOVERY_REQUIRED", "ROLLING_BACK", "ROLLED_BACK", "ROLLED_BACK_RAW"};

    UserdataGrowth(File directory, Backend backend, Progress progress) throws Exception {
        this.directory = directory;
        if (!directory.getAbsoluteFile().equals(directory.getCanonicalFile())) throw new SecurityException("备份目录不能是符号链接");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建扩容备份目录");
        journal = child("growth.json"); backup = child("userdata.raw"); partial = child("userdata.partial");
        this.backend = backend; this.progress = progress;
    }
    private File child(String name) throws Exception {
        File file = new File(directory, name);
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new SecurityException("拒绝访问符号链接备份");
        return file;
    }
    private JSONObject read() throws Exception {
        if (journal.length() > 8192) throw new IOException("扩容记录损坏");
        JSONObject state = new JSONObject(new String(Files.readAllBytes(journal.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        if (state.getInt("version") != 1 || !Arrays.asList(PHASES).contains(state.getString("phase")))
            throw new IOException("不支持的扩容记录");
        long old = state.getLong("oldBytes"), target = state.getLong("newBytes");
        if (old <= 0 || old % 4096 != 0 || target <= old || target % 4096 != 0 || target > (512L << 30))
            throw new IOException("扩容容量记录损坏");
        return state;
    }
    private void save(JSONObject state, String phase) throws Exception {
        state.put("phase", phase);
        File temp = child("growth.partial");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.getFD().sync();
        }
        Files.move(temp.toPath(), journal.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        backend.syncDirectory(directory);
    }
    static boolean blocked(File directory) {
        File journal = new File(directory, "growth.json");
        if (!journal.exists()) return false;
        try {
            String phase = new JSONObject(new String(Files.readAllBytes(journal.toPath()), java.nio.charset.StandardCharsets.UTF_8)).getString("phase");
            return !Arrays.asList("EXPANDED", "ROLLED_BACK", "ROLLED_BACK_RAW", "BACKUP_PENDING", "BACKUP_VERIFIED").contains(phase);
        } catch (Exception error) { return true; }
    }
    JSONObject status() throws Exception {
        if (!journal.exists()) return new JSONObject().put("exists", false);
        JSONObject result = read();
        String phase = result.getString("phase");
        boolean untouched = phase.equals("BACKUP_PENDING") || phase.equals("BACKUP_VERIFIED");
        JSONObject publicState = new JSONObject(result.toString()); publicState.remove("keyIdentity");
        return publicState.put("exists", true).put("backupBytes", backup.isFile() ? backup.length() : 0)
            .put("canDiscard", untouched || phase.equals("EXPANDED") || phase.equals("ROLLED_BACK") || phase.equals("ROLLED_BACK_RAW"))
            .put("canRollback", !untouched && backup.isFile() && backup.length() == result.getLong("oldBytes")
                && result.optString("sha256").matches("[a-f0-9]{64}"));
    }
    void grow(long newBytes) throws Exception {
        if (journal.exists() || backup.exists() || partial.exists()) throw new IllegalStateException("已有扩容备份或中断记录，请先恢复或清理");
        long oldBytes = backend.size();
        if (newBytes <= oldBytes || oldBytes <= 0 || oldBytes % 4096 != 0 || newBytes % 4096 != 0 || newBytes > (512L << 30))
            throw new IllegalArgumentException("新容量必须大于实际容量");
        JSONObject geometry = backend.inspect(true);
        long fsBytes = geometry.getLong("filesystemBytes");
        if (geometry.getLong("blockBytes") != oldBytes || fsBytes <= 0 || fsBytes > oldBytes) throw new IOException("文件系统容量检查失败");
        if (directory.getUsableSpace() < Math.max(oldBytes, newBytes)) throw new IOException("空间不足以创建完整备份及扩容镜像");
        JSONObject state = new JSONObject().put("version", 1).put("oldBytes", oldBytes).put("newBytes", newBytes)
            .put("filesystemBytes", fsBytes).put("filesystem", geometry.getString("filesystem"))
            .put("keyIdentity", backend.keyIdentity()).put("assisted", geometry.optBoolean("assisted"));
        save(state, "BACKUP_PENDING");
        boolean replacing = false;
        try {
            String hash;
            try (Mapping mapping = backend.map()) { hash = copy(mapping.device(), partial, oldBytes, "备份 userdata", false); }
            if (!hash.equals(hash(partial, oldBytes, "校验备份"))) throw new IOException("备份校验失败，原 userdata 未修改");
            Files.move(partial.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE);
            backend.syncDirectory(directory);
            state.put("sha256", hash); save(state, "BACKUP_VERIFIED");
            // The durable recovery record always precedes the first destructive call.
            save(state, "REPLACING"); replacing = true;
            backend.allocate(newBytes);
            if (!state.getString("keyIdentity").equals(backend.keyIdentity())) throw new IOException("槽位密钥标识发生变化，备份已保留");
            if (backend.size() != newBytes) throw new IOException("新块设备容量不匹配");
            try (Mapping mapping = backend.map()) {
                copy(backup, mapping.device(), oldBytes, "恢复原始数据", true);
                if (!hash.equals(hash(mapping.device(), oldBytes, "校验迁移数据"))) throw new IOException("迁移数据校验失败");
            }
            save(state, "RAW_RESTORED");
            progress.update("扩展文件系统并校验加密记录", 0, 0);
            backend.expand(fsBytes, newBytes);
            JSONObject result = backend.inspect(true);
            if (result.getLong("blockBytes") != newBytes || result.getLong("filesystemBytes") <= fsBytes ||
                result.getLong("filesystemBytes") > newBytes) throw new IOException("扩容后的文件系统容量未通过验证");
            save(state, "EXPANDED");
        } catch (Exception failure) {
            if (replacing) {
                try { save(state, "RECOVERY_REQUIRED"); } catch (Exception saveError) { failure.addSuppressed(saveError); }
            }
            throw failure;
        }
    }
    void rollback() throws Exception {
        JSONObject state = read();
        if (!state.getString("keyIdentity").equals(backend.keyIdentity())) throw new IOException("槽位密钥已改变，拒绝将旧备份写入新槽");
        long oldBytes = state.getLong("oldBytes");
        String expected = state.getString("sha256");
        if (!expected.matches("[a-f0-9]{64}") || !backup.isFile() || backup.length() != oldBytes ||
            !expected.equals(hash(backup, oldBytes, "校验恢复备份"))) throw new IOException("备份缺失或校验失败，未修改当前镜像");
        save(state, "ROLLING_BACK");
        try {
            backend.allocate(oldBytes);
            if (!state.getString("keyIdentity").equals(backend.keyIdentity())) throw new IOException("槽位密钥标识发生变化，拒绝写入旧备份");
            if (backend.size() != oldBytes) throw new IOException("恢复容量不匹配");
            try (Mapping mapping = backend.map()) {
                copy(backup, mapping.device(), oldBytes, "回退 userdata", true);
                if (!expected.equals(hash(mapping.device(), oldBytes, "校验回退数据"))) throw new IOException("回退校验失败");
            }
            boolean checked = false;
            try { backend.inspect(true); checked = true; }
            catch (Exception unavailable) {
                if (!state.optBoolean("assisted")) throw unavailable;
                // The complete original bytes and original sealed-key identity were
                // independently verified. Lost host credentials must not prevent
                // booting this exact restored original in its working guest.
                progress.update("原始备份已逐字节恢复，请启动客体核对数据", 0, 0);
            }
            save(state, checked ? "ROLLED_BACK" : "ROLLED_BACK_RAW");
        } catch (Exception failure) {
            try { save(state, "RECOVERY_REQUIRED"); } catch (Exception saveError) { failure.addSuppressed(saveError); }
            throw failure;
        }
    }
    void discard() throws Exception {
        JSONObject state = status();
        if (state.optBoolean("exists") && !state.optBoolean("canDiscard")) throw new IllegalStateException("扩容尚未恢复，不能删除唯一备份");
        Files.deleteIfExists(child("userdata.partial").toPath());
        Files.deleteIfExists(child("userdata.raw").toPath());
        Files.deleteIfExists(child("growth.partial").toPath());
        Files.deleteIfExists(journal.toPath());
        backend.syncDirectory(directory);
    }
    private String copy(File source, File destination, long bytes, String phase, boolean device) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(source);
             RandomAccessFile target = new RandomAccessFile(destination, "rw")) {
            if (!device) target.setLength(0);
            target.seek(0);
            OutputStream output = new OutputStream() {
                @Override public void write(int value) throws IOException { target.write(value); }
                @Override public void write(byte[] buffer, int offset, int length) throws IOException { target.write(buffer, offset, length); }
            };
            transfer(input, output, bytes, phase, digest); target.getFD().sync();
        }
        return hex(digest.digest());
    }
    private String hash(File source, long bytes, String phase) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(source)) { transfer(input, null, bytes, phase, digest); }
        return hex(digest.digest());
    }
    private void transfer(InputStream input, OutputStream output, long total, String phase, MessageDigest digest) throws Exception {
        byte[] buffer = new byte[1024 * 1024]; long completed = 0, reportedAt = 0;
        progress.update(phase, 0, total);
        while (completed < total) {
            int count = input.read(buffer, 0, (int)Math.min(buffer.length, total - completed));
            if (count < 0) throw new EOFException("块数据提前结束");
            if (count == 0) continue;
            digest.update(buffer, 0, count);
            if (output != null) output.write(buffer, 0, count);
            completed += count;
            long now = System.nanoTime();
            if (completed == total || now - reportedAt >= 1_000_000_000L) { progress.update(phase, completed, total); reportedAt = now; }
        }
    }
    private static String hex(byte[] hash) {
        StringBuilder result = new StringBuilder(64);
        for (byte value : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
