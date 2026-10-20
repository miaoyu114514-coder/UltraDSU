package io.github.ultradsu.dsu;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Manages only this app's growth backups, never gsid backing images or caller-supplied paths. */
final class UserdataBackupStore {
    interface Sync { void directory(File directory) throws Exception; }
    private static final List<String> FILES = Arrays.asList("userdata.raw", "userdata.partial", "growth.partial", "growth.json", "boot.json");
    private final File root;
    private final List<File> liveImages;
    private final Sync sync;

    UserdataBackupStore(File root, List<File> liveImages, Sync sync) throws Exception {
        this.root = root; this.liveImages = liveImages; this.sync = sync;
        canonical(root);
    }
    private static void canonical(File file) throws Exception {
        if (Files.isSymbolicLink(file.toPath()) || !file.getCanonicalFile().equals(file.getAbsoluteFile()))
            throw new SecurityException("备份路径含符号链接，已阻止删除");
    }
    private File directory(String slot) throws Exception {
        if (!slot.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}")) throw new SecurityException("无效的备份槽标识");
        canonical(root);
        File dir = new File(root, slot); canonical(dir);
        return dir;
    }
    private File checkedFile(File dir, String name) throws Exception {
        File file = new File(dir, name); canonical(file);
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
                throw new SecurityException("备份包含非普通文件，已阻止删除");
            for (File live : liveImages) {
                canonical(live);
                if (Files.exists(live.toPath(), LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(live.toPath(), file.toPath()))
                    throw new SecurityException("此文件关联在用 userdata，不可删除");
            }
        }
        return file;
    }
    private static byte[] metadata(File file) throws Exception {
        if (file.length() > 8192) throw new IOException("备份记录过大或损坏");
        return Files.readAllBytes(file.toPath());
    }
    private String revision(File dir) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String name : FILES) {
            File file = checkedFile(dir, name);
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            if (!file.exists()) { digest.update((byte)0); continue; }
            BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            digest.update((attrs.size() + ":" + attrs.lastModifiedTime() + ":" + attrs.fileKey()).getBytes(StandardCharsets.UTF_8));
            if (name.endsWith(".json") || name.equals("growth.partial")) digest.update(metadata(file));
        }
        return AssistanceProtocol.hex(digest.digest());
    }
    JSONArray list() throws Exception {
        JSONArray result = new JSONArray();
        if (!root.exists()) return result;
        File[] directories = root.listFiles();
        if (directories == null) throw new IOException("无法读取 userdata 备份目录");
        Arrays.sort(directories, Comparator.comparing(File::getName));
        for (File dir : directories) {
            if (!dir.getName().matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}")) continue;
            JSONObject row = inspect(dir.getName());
            if (row != null) result.put(row);
        }
        return result;
    }
    JSONObject inspect(String slot) throws Exception {
        File dir = new File(root, slot);
        if (!Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) return null;
        JSONObject row = new JSONObject().put("slot", slot).put("kind", "growth").put("bytes", 0)
            .put("modified", 0).put("canDelete", false).put("phase", "UNKNOWN");
        try {
            dir = directory(slot);
            String[] names = dir.list();
            if (names == null) throw new IOException("无法读取备份目录");
            if (names.length == 0) return null;
            for (String name : names) if (!FILES.contains(name)) throw new IOException("备份目录含未知文件，请先检查恢复状态");
            long bytes = 0, modified = 0;
            for (String name : FILES) {
                File file = checkedFile(dir, name);
                if (file.exists()) {
                    modified = Math.max(modified, file.lastModified());
                    if (name.startsWith("userdata.")) bytes += file.length();
                }
            }
            row.put("bytes", bytes).put("modified", modified);
            File journal = checkedFile(dir, "growth.json");
            if (journal.exists()) {
                JSONObject state = new JSONObject(new String(metadata(journal), StandardCharsets.UTF_8));
                String phase = state.getString("phase"); row.put("phase", phase);
                long oldBytes = state.getLong("oldBytes"), newBytes = state.getLong("newBytes");
                if (state.getInt("version") != 1 || oldBytes <= 0 || oldBytes % 4096 != 0 ||
                        newBytes <= oldBytes || newBytes % 4096 != 0 || newBytes > (512L << 30))
                    throw new IOException("扩容记录损坏，请先恢复或检查备份");
                if (!Arrays.asList("EXPANDED", "ROLLED_BACK", "ROLLED_BACK_RAW", "BACKUP_PENDING", "BACKUP_VERIFIED").contains(phase))
                    throw new IllegalStateException("扩容尚未恢复，此备份受保护，请先从扩容备份回退");
            } else {
                row.put("phase", "ORPHANED");
                // A partial journal can be the only evidence of an interrupted replacement.
                if (checkedFile(dir, "growth.partial").exists()) throw new IOException("存在未提交的恢复记录，请先检查扩容状态");
            }
            if (checkedFile(dir, "boot.json").exists()) throw new IllegalStateException("存在未完成的启动恢复记录，请先在槽编辑页检查扩容状态");
            return row.put("canDelete", true).put("reason", "独立备份，未作为槽的当前 userdata 使用")
                .put("revision", revision(dir));
        } catch (Exception error) {
            return row.put("canDelete", false).put("reason", error.getMessage() == null ? "无法确认备份状态，已阻止删除" : error.getMessage());
        }
    }
    void delete(String slot, String kind, String expectedRevision) throws Exception {
        if (!"growth".equals(kind)) throw new SecurityException("槽当前绑定的 userdata 不可删除");
        File dir = directory(slot);
        JSONObject row = inspect(slot);
        if (row == null || !row.optBoolean("canDelete"))
            throw new IllegalStateException(row == null ? "备份已不存在，请刷新" : row.optString("reason"));
        if (!expectedRevision.matches("[a-f0-9]{64}") || !expectedRevision.equals(row.getString("revision")))
            throw new IllegalStateException("备份状态已变化，请刷新后重新选择");
        // Validate the complete fixed set again before unlinking anything. Keep the
        // journal until every data file is removed so an interrupted cleanup is retryable.
        for (String name : FILES) checkedFile(dir, name);
        for (String name : Arrays.asList("userdata.partial", "userdata.raw", "growth.partial", "growth.json")) {
            Files.deleteIfExists(new File(dir, name).toPath()); sync.directory(dir);
        }
        if (!dir.delete()) throw new IOException("备份数据已清理，但目录仍有记录，请刷新检查");
        sync.directory(root);
    }
}
