package io.github.ultradsu.dsu;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Root-owned cross-boot mailbox. Only public requests and authenticated ciphertext are stored. */
final class GuestAssistance {
    private static final File ROOT = new File("/metadata/ultradsu/assist");
    static File directory(String slot) throws Exception {
        if (!slot.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,31}")) throw new SecurityException("无效的协作槽");
        File file = new File(ROOT, slot);
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new SecurityException("协作目录不能是符号链接");
        return file;
    }
    private static File child(File directory, String name) throws Exception {
        File file = new File(directory, name);
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new SecurityException("协作文件不能是符号链接");
        return file;
    }
    static JSONObject read(File directory, String name) throws Exception {
        File file = child(directory, name);
        if (!file.isFile() || file.length() > 16384) throw new IOException("协作记录缺失或损坏");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    static JSONObject request(String slot) throws Exception {
        JSONObject request = read(directory(slot), "request.json");
        if (request.getInt("version") != 1 || !slot.equals(request.getString("slot")) ||
                !AssistanceProtocol.validSession(request.getString("session"))) throw new SecurityException("协作请求无效");
        AssistanceProtocol.publicKey(request.getString("publicKey"));
        return request;
    }
    static void save(File directory, String name, JSONObject value) throws Exception {
        File target = child(directory, name), temp = child(directory, name + ".partial");
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 16384) throw new IOException("协作记录过大");
        try (FileOutputStream stream = new FileOutputStream(temp)) { stream.write(bytes); stream.getFD().sync(); }
        android.system.Os.chmod(temp.getPath(), 0600);
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        NativeGrowthTools.syncDirectory(directory);
    }
    static JSONObject status(String slot, boolean envelope) throws Exception {
        File directory = directory(slot);
        if (!child(directory, "request.json").exists()) return new JSONObject().put("phase", "NONE");
        JSONObject request = request(slot);
        JSONObject result = new JSONObject().put("phase", "PREPARED").put("slot", slot).put("session", request.getString("session"));
        if (envelope) result.put("request", request);
        if (child(directory, "envelope.json").exists()) {
            JSONObject sealed = read(directory, "envelope.json");
            if (!request.getString("session").equals(sealed.getString("session")) || !slot.equals(sealed.getString("slot")))
                throw new SecurityException("协作响应与请求不匹配");
            result.put("phase", "CAPTURED").put("guestSdk", sealed.optInt("guestSdk"));
            if (envelope) result.put("envelope", sealed);
        }
        return result;
    }
    static void prepare(String slot, String session, String publicKey) throws Exception {
        if (!AssistanceProtocol.validSession(session)) throw new SecurityException("无效的协作会话");
        AssistanceProtocol.publicKey(publicKey);
        NativeGrowthTools.privateDirectory(ROOT.getParentFile()); NativeGrowthTools.privateDirectory(ROOT);
        File directory = directory(slot); NativeGrowthTools.privateDirectory(directory);
        // Drop a prior response before atomically publishing a fresh request.
        Files.deleteIfExists(child(directory, "envelope.json").toPath());
        save(directory, "request.json", new JSONObject().put("version", 1).put("slot", slot).put("session", session)
            .put("publicKey", publicKey).put("created", System.currentTimeMillis()));
    }
    static void captured(String slot, JSONObject sealed) throws Exception {
        JSONObject request = request(slot);
        if (!slot.equals(sealed.getString("slot")) || !request.getString("session").equals(sealed.getString("session")) ||
                !AssistanceProtocol.digest(AssistanceProtocol.publicKey(request.getString("publicKey")).getEncoded()).equals(sealed.getString("publicKeyHash")))
            throw new SecurityException("协作采集结果与请求不匹配");
        save(directory(slot), "envelope.json", sealed);
    }
    static void clear(String slot) throws Exception {
        File directory = directory(slot);
        if (!directory.isDirectory()) return;
        for (String name : new String[]{"request.json", "envelope.json", "request.json.partial", "envelope.json.partial"})
            Files.deleteIfExists(child(directory, name).toPath());
        NativeGrowthTools.syncDirectory(directory);
        if (!directory.delete()) throw new IOException("协作目录含有未知文件，已保留");
        NativeGrowthTools.syncDirectory(ROOT);
    }
}
