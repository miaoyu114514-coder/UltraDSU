package io.github.ultradsu.dsu;

import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class UserdataBackupStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File root, dir, live;
    private UserdataBackupStore store;
    @Before public void setup() throws Exception {
        root = temporary.newFolder("growth"); dir = new File(root,"alpha"); assertTrue(dir.mkdir());
        live = temporary.newFile("userdata_gsi.img.0000"); Files.write(live.toPath(), new byte[]{7,8,9});
        store = new UserdataBackupStore(root, Arrays.asList(live), path -> {});
    }
    private void backup(String phase) throws Exception {
        Files.write(new File(dir,"userdata.raw").toPath(), new byte[4096]);
        Files.write(new File(dir,"growth.json").toPath(), new JSONObject().put("version",1).put("phase",phase)
            .put("oldBytes",4096).put("newBytes",8192).toString().getBytes(StandardCharsets.UTF_8));
    }
    private void rejected(String slot, String kind, String revision) throws Exception {
        try { store.delete(slot,kind,revision); fail("Unsafe deletion accepted"); }
        catch (SecurityException | IllegalStateException expected) {}
        assertArrayEquals(new byte[]{7,8,9}, Files.readAllBytes(live.toPath()));
    }
    @Test public void deletesCompletedIndependentBackupAndLeavesCurrentBytesUntouched() throws Exception {
        backup("EXPANDED"); JSONObject row = store.list().getJSONObject(0);
        assertEquals(4096,row.getLong("bytes")); assertTrue(row.getBoolean("canDelete"));
        store.delete("alpha","growth",row.getString("revision"));
        assertFalse(dir.exists()); assertArrayEquals(new byte[]{7,8,9},Files.readAllBytes(live.toPath()));
    }
    @Test public void currentImageCannotBeDeletedEvenWithValidBackupToken() throws Exception {
        backup("EXPANDED"); rejected("alpha","current",store.inspect("alpha").getString("revision"));
        assertTrue(new File(dir,"userdata.raw").isFile());
    }
    @Test public void recoveryPhasesAndUnknownPhasesProtectTheOnlyBackup() throws Exception {
        for (String phase : Arrays.asList("REPLACING","RAW_RESTORED","RECOVERY_REQUIRED","ROLLING_BACK","UNKNOWN")) {
            backup(phase); assertFalse(store.inspect("alpha").getBoolean("canDelete"));
            rejected("alpha","growth","0".repeat(64)); assertTrue(new File(dir,"userdata.raw").isFile());
        }
    }
    @Test public void staleSelectionAfterPhaseChangeCannotDeleteBackup() throws Exception {
        backup("EXPANDED"); String revision = store.inspect("alpha").getString("revision");
        backup("RECOVERY_REQUIRED"); rejected("alpha","growth",revision);
    }
    @Test public void replacedBackupRequiresNewSelectionEvenIfLengthIsUnchanged() throws Exception {
        backup("EXPANDED"); String revision = store.inspect("alpha").getString("revision");
        File raw = new File(dir,"userdata.raw"); long previous = raw.lastModified();
        Files.delete(raw.toPath()); Files.write(raw.toPath(),new byte[4096]); assertTrue(raw.setLastModified(previous + 2000));
        rejected("alpha","growth",revision); assertEquals(4096,raw.length());
    }
    @Test public void orphanDataWithoutPendingJournalCanBeCleaned() throws Exception {
        Files.write(new File(dir,"userdata.partial").toPath(),new byte[1024]);
        JSONObject row = store.inspect("alpha"); assertTrue(row.getBoolean("canDelete")); assertEquals("ORPHANED",row.getString("phase"));
        store.delete("alpha","growth",row.getString("revision")); assertFalse(dir.exists());
    }
    @Test public void pendingBootRecordAndUnknownFilesBlockDeletion() throws Exception {
        backup("EXPANDED"); Files.write(new File(dir,"boot.json").toPath(),"{}".getBytes());
        assertFalse(store.inspect("alpha").getBoolean("canDelete")); Files.delete(new File(dir,"boot.json").toPath());
        Files.write(new File(dir,"unexpected.img").toPath(),new byte[4096]);
        assertFalse(store.inspect("alpha").getBoolean("canDelete")); rejected("alpha","growth","0".repeat(64));
        assertTrue(new File(dir,"unexpected.img").exists());
    }
    @Test public void corruptOrIncompleteJournalIsNeverTreatedAsDisposable() throws Exception {
        backup("EXPANDED"); Files.write(new File(dir,"growth.json").toPath(),"broken".getBytes());
        assertFalse(store.inspect("alpha").getBoolean("canDelete")); Files.delete(new File(dir,"growth.json").toPath());
        Files.write(new File(dir,"growth.partial").toPath(),"{}".getBytes());
        assertFalse(store.inspect("alpha").getBoolean("canDelete")); rejected("alpha","growth","0".repeat(64));
    }
    @Test public void symlinksNeverReachDeletion() throws Exception {
        backup("EXPANDED"); File raw = new File(dir,"userdata.raw"); Files.delete(raw.toPath());
        try { Files.createSymbolicLink(raw.toPath(),live.toPath()); }
        catch (FileSystemException unavailable) { Assume.assumeNoException("Host cannot create symbolic links; also covered by Android device test",unavailable); }
        assertFalse(store.inspect("alpha").getBoolean("canDelete")); rejected("alpha","growth","0".repeat(64));
    }
    @Test public void directoryEscapeNeverReachesDeletion() throws Exception {
        backup("EXPANDED");
        rejected("../outside","growth","0".repeat(64));
        assertTrue(new File(dir,"userdata.raw").isFile());
    }
    @Test public void hardlinkToLiveBackingImageIsProtected() throws Exception {
        backup("EXPANDED"); File raw = new File(dir,"userdata.raw"); Files.delete(raw.toPath()); Files.createLink(raw.toPath(),live.toPath());
        assertFalse(store.inspect("alpha").getBoolean("canDelete")); rejected("alpha","growth","0".repeat(64));
        assertTrue(raw.isFile());
    }
    @Test public void completedAndUntouchedPhasesAreDeletableWithoutNativeInspection() throws Exception {
        for (String phase : Arrays.asList("EXPANDED","ROLLED_BACK","ROLLED_BACK_RAW","BACKUP_PENDING","BACKUP_VERIFIED")) {
            backup(phase); assertTrue(store.inspect("alpha").getBoolean("canDelete"));
        }
    }
}
