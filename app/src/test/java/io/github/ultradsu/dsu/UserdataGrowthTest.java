package io.github.ultradsu.dsu;

import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class UserdataGrowthTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File directory, device;
    private byte[] original;
    private FakeBackend backend;
    private UserdataGrowth growth;
    private static final int OLD = 1024 * 1024, NEW = 2 * OLD;
    private class FakeBackend implements UserdataGrowth.Backend {
        int allocations, mapped; boolean failAllocate, failExpand, failRestore, assisted, failInspect;
        String keyIdentity = "original-key-record";
        long filesystemBytes = OLD;
        public String keyIdentity() { return keyIdentity; }
        public long size() { return device.length(); }
        public JSONObject inspect(boolean check) throws Exception {
            if (failInspect) throw new IOException("host credential unavailable");
            return new JSONObject().put("filesystem", "f2fs").put("filesystemBytes", filesystemBytes).put("blockBytes", device.length()).put("assisted", assisted);
        }
        public UserdataGrowth.Mapping map() {
            mapped++;
            return new UserdataGrowth.Mapping() {
                public File device() { return device; }
                public void close() { mapped--; }
            };
        }
        public void allocate(long bytes) throws Exception {
            assertTrue(UserdataGrowth.blocked(directory));
            allocations++;
            try (RandomAccessFile file = new RandomAccessFile(device, "rw")) { file.setLength(0); file.setLength(bytes); }
            if (failAllocate) throw new IOException("simulated interrupted allocation");
            filesystemBytes = OLD;
        }
        public void expand(long oldFs, long newBytes) throws Exception {
            assertArrayEquals(original, Arrays.copyOf(Files.readAllBytes(device.toPath()), OLD));
            if (failExpand) throw new IOException("simulated fs tool rejection");
            filesystemBytes = newBytes;
        }
    }
    @Before public void setup() throws Exception {
        directory = temporary.newFolder("backup"); device = temporary.newFile("device");
        original = new byte[OLD]; new Random(182).nextBytes(original); Files.write(device.toPath(), original);
        backend = new FakeBackend(); growth = new UserdataGrowth(directory, backend, (p, b, t) -> { });
    }
    @Test public void successfulGrowthPreservesPrefixAndRetainsVerifiedBackup() throws Exception {
        growth.grow(NEW);
        assertEquals("EXPANDED", growth.status().getString("phase"));
        assertFalse(UserdataGrowth.blocked(directory)); assertEquals(0, backend.mapped);
        assertEquals(NEW, device.length());
        assertArrayEquals(original, Files.readAllBytes(new File(directory, "userdata.raw").toPath()));
        assertArrayEquals(original, Arrays.copyOf(Files.readAllBytes(device.toPath()), OLD));
    }
    @Test public void interruptedAllocationCanRecoverAfterNewEngineInstance() throws Exception {
        backend.failAllocate = true;
        try { growth.grow(NEW); fail(); } catch (IOException expected) { }
        assertTrue(UserdataGrowth.blocked(directory));
        assertEquals("RECOVERY_REQUIRED", growth.status().getString("phase"));
        backend.failAllocate = false;
        new UserdataGrowth(directory, backend, (p,b,t)->{}).rollback();
        assertArrayEquals(original, Files.readAllBytes(device.toPath()));
        assertFalse(UserdataGrowth.blocked(directory)); assertEquals(0, backend.mapped);
    }
    @Test public void filesystemFailureRetainsBackupAndBlocksDiscardUntilRollback() throws Exception {
        backend.failExpand = true;
        try { growth.grow(NEW); fail(); } catch (IOException expected) { }
        try { growth.discard(); fail(); } catch (IllegalStateException expected) { }
        growth.rollback(); assertArrayEquals(original, Files.readAllBytes(device.toPath()));
        assertEquals("ROLLED_BACK", growth.status().getString("phase"));
    }
    @Test public void tamperedBackupNeverTriggersDestructiveRecovery() throws Exception {
        growth.grow(NEW);
        try (RandomAccessFile file = new RandomAccessFile(new File(directory,"userdata.raw"),"rw")) { file.write(99); }
        int allocated = backend.allocations;
        try { growth.rollback(); fail(); } catch (IOException expected) { }
        assertEquals(allocated, backend.allocations); assertEquals(NEW, device.length());
    }
    @Test public void recreatedSlotWithDifferentKeyCannotReceiveOldBackup() throws Exception {
        growth.grow(NEW);
        backend.keyIdentity = "replacement-key-record";
        int allocated = backend.allocations;
        try { growth.rollback(); fail(); } catch (IOException expected) { }
        assertEquals(allocated, backend.allocations); assertEquals(NEW, device.length());
        assertEquals("EXPANDED", growth.status().getString("phase"));
    }
    @Test public void backupFailureLeavesOriginalUntouchedAndCanBeDiscarded() throws Exception {
        UserdataGrowth failing = new UserdataGrowth(directory, backend, (p,b,t)-> { if (p.equals("备份 userdata") && b > 0) throw new IOException("disk full"); });
        try { failing.grow(NEW); fail(); } catch (IOException expected) { }
        assertEquals(0, backend.allocations); assertEquals(0, backend.mapped);
        assertArrayEquals(original, Files.readAllBytes(device.toPath()));
        assertFalse(UserdataGrowth.blocked(directory));
        failing.discard(); assertFalse(failing.status().getBoolean("exists"));
    }
    @Test public void retainedBackupMustBeExplicitlyDiscardedBeforeAnotherGrowth() throws Exception {
        growth.grow(NEW);
        try { growth.grow(NEW * 2L); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, backend.allocations);
        growth.discard(); assertFalse(growth.status().getBoolean("exists"));
    }
    @Test public void smallerOrEqualRequestsCannotAllocate() throws Exception {
        for (long bytes : new long[]{OLD, OLD/2, NEW+1}) {
            try { growth.grow(bytes); fail(); } catch (IllegalArgumentException expected) { }
        }
        assertEquals(0, backend.allocations);
    }
    @Test public void lostAssistanceCredentialsStillAllowExactOriginalRecovery() throws Exception {
        backend.assisted = true;
        growth.grow(NEW);
        backend.failInspect = true;
        UserdataGrowth restarted = new UserdataGrowth(directory, backend, (p,b,t)->{});
        restarted.rollback();
        assertEquals("ROLLED_BACK_RAW", restarted.status().getString("phase"));
        assertArrayEquals(original, Files.readAllBytes(device.toPath()));
        assertFalse(UserdataGrowth.blocked(directory));
        assertTrue(restarted.status().getBoolean("canDiscard"));
        assertEquals(0, backend.mapped);
    }
    @Test public void unassistedRecoveryStillRequiresItsFilesystemCheck() throws Exception {
        growth.grow(NEW); backend.failInspect = true;
        assertThrows(IOException.class, () -> growth.rollback());
        assertTrue(UserdataGrowth.blocked(directory));
        assertEquals("RECOVERY_REQUIRED", growth.status().getString("phase"));
    }
    @Test public void missingCredentialsBeforeAssistedGrowthCannotModifyTheOriginal() throws Exception {
        backend.assisted = true; backend.failInspect = true;
        assertThrows(IOException.class, () -> growth.grow(NEW));
        assertEquals(0, backend.allocations); assertArrayEquals(original, Files.readAllBytes(device.toPath()));
        assertFalse(growth.status().getBoolean("exists"));
    }
    @Test public void assistedRawRecoveryStillRejectsDamagedBackupBeforeAllocation() throws Exception {
        backend.assisted = true; growth.grow(NEW); backend.failInspect = true;
        try (RandomAccessFile file = new RandomAccessFile(new File(directory, "userdata.raw"), "rw")) { file.write(99); }
        int allocated = backend.allocations;
        assertThrows(IOException.class, () -> growth.rollback());
        assertEquals(allocated, backend.allocations); assertEquals(NEW, device.length());
    }
}
