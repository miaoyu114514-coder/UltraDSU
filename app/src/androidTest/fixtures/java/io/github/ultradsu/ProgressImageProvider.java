package io.github.ultradsu;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;

/** Included only in the isolated UI verification app, never in a normal Debug or Release APK. */
public final class ProgressImageProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private long size(Uri uri) { return "invalid".equals(uri.getLastPathSegment()) ? 8192L : 64L * 1024 * 1024; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor result = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.SIZE.equals(columns[i])) row[i] = size(uri);
            else if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "system.img";
        }
        result.addRow(row);
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only fixture");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    byte[] block = new byte[256 * 1024];
                    boolean valid = !"invalid".equals(uri.getLastPathSegment());
                    if (valid) { block[1080] = 0x53; block[1081] = (byte) 0xef; }
                    long remaining = size(uri);
                    while (remaining > 0) {
                        int count = (int) Math.min(remaining, block.length);
                        output.write(block, 0, count);
                        remaining -= count;
                        block[1080] = 0; block[1081] = 0;
                        if (valid) Thread.sleep(60);
                    }
                } catch (Exception ignored) { /* Closing the reader is expected on cancellation. */ }
            }, "progress-image-fixture").start();
            return pipe[0];
        } catch (java.io.IOException error) { throw new FileNotFoundException(error.getMessage()); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
}
