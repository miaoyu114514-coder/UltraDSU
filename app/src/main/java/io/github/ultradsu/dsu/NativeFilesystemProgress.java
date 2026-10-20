package io.github.ultradsu.dsu;

import org.json.JSONObject;

/** Accepts only the resizer's bounded numeric protocol, never forwards arbitrary native output. */
final class NativeFilesystemProgress {
    static JSONObject parse(String line) {
        if (line == null || line.length() > 192 || !line.startsWith("ULTRADSU_FS_PROGRESS:")) return null;
        String[] parts = line.split(":", -1);
        if (parts.length != 4) return null;
        try {
            for (int i = 1; i < parts.length; i++) if (!parts[i].matches("[0-9]{1,16}")) return null;
            int phase = Integer.parseInt(parts[1]);
            long done = Long.parseLong(parts[2]), total = Long.parseLong(parts[3]);
            if (done > total || total > (512L << 30)) return null;
            String message;
            switch (phase) {
                case 1: message = "局部整理扩容区域"; break;
                case 2: message = "全量扫描并搬移数据区域"; break;
                case 3: message = "迁移文件系统节点表"; break;
                case 4: message = "更新文件系统段信息"; break;
                case 5: message = "迁移文件系统摘要"; break;
                case 6: message = "提交扩容检查点"; break;
                default: return null;
            }
            return new JSONObject().put("message", message).put("bytes", done).put("totalBytes", total);
        } catch (Exception invalid) { return null; }
    }
}
