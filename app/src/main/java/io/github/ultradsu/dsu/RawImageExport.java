package io.github.ultradsu.dsu;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.EOFException;
import java.security.MessageDigest;

/** Streams exactly one raw partition; never opens a destination path as root. */
final class RawImageExport {
    interface Checkpoint { void check() throws Exception; }
    interface Progress { void update(long bytes, long total) throws Exception; }

    static String copy(InputStream source, OutputStream target, long size, Checkpoint checkpoint, Progress progress) throws Exception {
        if (size <= 0 || size > (512L << 30) || size % 4096 != 0) throw new IllegalArgumentException("分区容量无效");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        long done = 0, reported = 0;
        progress.update(0, size);
        while (done < size) {
            checkpoint.check();
            int read = source.read(buffer, 0, (int)Math.min(buffer.length, size - done));
            if (read < 0) throw new EOFException("分区读取提前结束，导出不完整");
            if (read == 0) continue;
            target.write(buffer, 0, read); digest.update(buffer, 0, read); done += read;
            if (System.currentTimeMillis() - reported > 250) { progress.update(done, size); reported = System.currentTimeMillis(); }
        }
        checkpoint.check(); target.flush(); progress.update(done, size);
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }
}
