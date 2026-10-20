package io.github.ultradsu.dsu;

import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import static org.junit.Assert.*;

/** Exercises real bridge orchestration against a fake gsid, without touching Android or a device. */
public class RootBridgeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private FakeGsi gsi;
    private File root;
    public static class Images {
        public final List<String> names = new ArrayList<>(Arrays.asList("system_gsi", "userdata_gsi"));
        public boolean mapped = false;
        public final Map<String, Long> sizes = new HashMap<>();
        public final Map<String, Integer> versions = new HashMap<>();
        Images() { sizes.put("system_gsi", 4096L); sizes.put("userdata_gsi", 8L << 30); versions.put("system_gsi", 1); versions.put("userdata_gsi", 1); }
        public List<String> getAllBackingImages() { return new ArrayList<>(names); }
        public boolean isImageMapped(String name) { return mapped; }
        public void deleteBackingImage(String name) { names.remove(name); }
    }
    public static class FakeGsi {
        final File root;
        public String active = "alpha";
        public boolean enabled = false, running = false, installing = false;
        public final Map<String, Images> images = new HashMap<>();
        public boolean lastOneShot;
        public boolean legacyFinish = false;
        public int globalRemoveCalls = 0;
        public String failPartition = "";
        public final List<String> changedPartitions = new ArrayList<>();
        private String newSlot;
        FakeGsi(File root) { this.root = root; }
        public List<String> getInstalledDsuSlots() {
            List<String> names = new ArrayList<>();
            File[] files = root.listFiles();
            if (files != null) for (File file : files) if (new File(file, "install_dir").isFile()) names.add(file.getName());
            Collections.sort(names); return names;
        }
        public boolean isGsiEnabled() { return enabled; }
        public boolean isGsiRunning() { return running; }
        public boolean isGsiInstallInProgress() { return installing; }
        public String getActiveDsuSlot() { return active; }
        public Images openImageService(String prefix) { return images.get(prefix.substring(4)); }
        public boolean disableGsi() { enabled = false; return true; }
        public int enableGsi(boolean once, String name) {
            if ((enabled && !installing) || !getInstalledDsuSlots().contains(name)) return 1;
            enabled = true; active = name; lastOneShot = once; installing = false; return 0;
        }
        public boolean removeGsi() throws Exception {
            globalRemoveCalls++;
            images.get(active).names.clear();
            Files.delete(new File(root, active + "/complete").toPath());
            Files.delete(new File(root, active + "/install_dir").toPath());
            enabled = false; return true;
        }
        public int openInstall(String path) throws Exception {
            // Android 14 gsid calls realpath and rejects a missing data directory.
            if (!new File(path).isDirectory() && !new File(root, new File(path).getName() + "/install_dir").isFile()) return 1;
            newSlot = new File(path).getName();
            if (new File(path).equals(new File(root.getParentFile(), "images"))) newSlot = "dsu";
            File dir = new File(root, newSlot); if (!dir.isDirectory() && !dir.mkdir()) return 1;
            Files.deleteIfExists(new File(dir, "complete").toPath());
            Files.write(new File(dir, "install_dir").toPath(), path.getBytes());
            images.putIfAbsent(newSlot, new Images()); return 0;
        }
        public int createPartition(String name, long size, boolean readOnly) {
            installing = true; changedPartitions.add(newSlot + ":" + name);
            Images current = images.get(newSlot); String image = name + "_gsi";
            if (!current.names.contains(image)) current.names.add(image);
            current.sizes.put(image, size); current.versions.put(image, current.versions.getOrDefault(image, 0) + 1);
            return name.equals(failPartition) ? 1 : 0;
        }
        public int closePartition() { return 0; }
        public int closeInstall() throws Exception {
            Files.write(new File(root, newSlot + "/complete").toPath(), "OK".getBytes());
            if (!legacyFinish) { installing = false; active = newSlot; enabled = false; }
            return 0;
        }
        public boolean cancelGsiInstall() { installing = false; return true; }
    }
    private static void set(String name, Object value) throws Exception {
        Field f = RootBridge.class.getDeclaredField(name); f.setAccessible(true); f.set(null, value);
    }
    @Before public void setup() throws Exception {
        root = temporary.newFolder("dsu"); gsi = new FakeGsi(root);
        set("api", FakeGsi.class); set("service", gsi); set("metadataRoot", root);
        set("platformSdk", 35);
        set("gsidBinary", new File(temporary.getRoot(), "missing-gsid"));
        set("testedBackportHash", "6bea182e737e238ce8c054717f48e64afc1c8a989aab53850bc9a564651bd323");
        set("imageRoot", temporary.newFolder("images"));
        set("directoryLabeler", (RootBridge.DirectoryLabeler) directory -> { });
        set("oneShotFile", new File(root, "one_shot_boot"));
        set("growthRoot", temporary.newFolder("growth"));
        set("backupDirectorySync", (RootBridge.BackupDirectorySync) directory -> { });
        set("imageWriter", (RootBridge.ImageWriter) (file, size) -> { });
        set("imageSizeReader", (RootBridge.ImageSizeReader) (service, name) -> ((Images) service).sizes.getOrDefault(name, 0L));
        add("alpha"); add("beta");
    }
    private void add(String slot) throws Exception {
        File dir = new File(root, slot); assertTrue(dir.mkdir());
        Files.write(new File(dir, "install_dir").toPath(), ("/data/gsi/" + slot).getBytes());
        Files.write(new File(dir, "complete").toPath(), "OK".getBytes());
        gsi.images.put(slot, new Images());
    }
    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = RootBridge.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    private void remove(String slot) throws Exception { invoke("remove", new Class[]{String.class}, slot); }
    @Test public void deletingInactiveSlotPreservesSelectedSlotAndBootMode() throws Exception {
        gsi.enabled = true;
        remove("beta");
        assertEquals("alpha", gsi.active); assertTrue(gsi.enabled);
        assertEquals(Arrays.asList("alpha"), gsi.getInstalledDsuSlots());
        assertEquals(2, gsi.images.get("alpha").names.size());
        assertTrue(gsi.images.get("beta").names.isEmpty());
    }
    @Test public void deletingActiveSlotSelectsSurvivorButPreservesDisabledState() throws Exception {
        remove("alpha");
        assertEquals("beta", gsi.active); assertFalse(gsi.enabled);
        assertEquals(Arrays.asList("beta"), gsi.getInstalledDsuSlots());
    }
    @Test public void deletingActiveStickySlotPreservesStickyBoot() throws Exception {
        gsi.enabled = true; remove("alpha");
        assertTrue(gsi.enabled); assertFalse(gsi.lastOneShot);
    }
    @Test public void deletingActiveOneShotSlotPreservesOneShot() throws Exception {
        Files.write(new File(root, "one_shot_boot").toPath(), new byte[0]);
        gsi.enabled = true; remove("alpha");
        assertTrue(gsi.enabled); assertTrue(gsi.lastOneShot);
    }
    @Test public void lastSlotUsesNativeRemoval() throws Exception {
        remove("beta"); remove("alpha"); assertTrue(gsi.getInstalledDsuSlots().isEmpty());
    }
    @Test public void noMutationDuringInstallationOrLiveDsu() throws Exception {
        gsi.installing = true;
        try { remove("alpha"); fail(); } catch (IllegalStateException expected) { }
        gsi.installing = false; gsi.running = true;
        try { remove("alpha"); fail(); } catch (IllegalStateException expected) { }
        assertEquals(2, gsi.getInstalledDsuSlots().size());
        assertEquals(2, gsi.images.get("alpha").names.size());
    }
    @Test public void mappedOrUnknownImagesAreNeverDeleted() throws Exception {
        gsi.images.get("beta").mapped = true;
        try { remove("beta"); fail(); } catch (IllegalStateException expected) { }
        gsi.images.get("beta").mapped = false;
        gsi.images.get("beta").names.add("unexpected");
        try { remove("beta"); fail(); } catch (IllegalStateException expected) { }
        assertEquals(3, gsi.images.get("beta").names.size());
    }
    @Test public void switchingEnabledSlotDisablesBeforeReenabling() throws Exception {
        gsi.enabled = true;
        invoke("enable", new Class[]{String.class, boolean.class}, "beta", true);
        assertEquals("beta", gsi.active); assertTrue(gsi.enabled);
    }
    @Test public void partialSlotIsNeverReportedAsInstalled() throws Exception {
        Files.delete(new File(root, "beta/complete").toPath());
        org.json.JSONObject status = (org.json.JSONObject) invoke("status", new Class[0]);
        assertEquals("[\"alpha\"]", status.getJSONArray("slots").toString());
        assertEquals(2, status.getJSONArray("occupiedSlots").length());
    }
    @Test public void idleStatusReportsActualUserdataSizesForImportedSlots() throws Exception {
        gsi.images.get("alpha").sizes.put("userdata_gsi", 16L << 30);
        org.json.JSONObject status = (org.json.JSONObject) invoke("status", new Class[0]);
        assertEquals(16L << 30, status.getJSONObject("userdataSizes").getLong("alpha"));
        assertEquals(8L << 30, status.getJSONObject("userdataSizes").getLong("beta"));
    }
    @Test public void liveDsuOrInstallationNeverTriggersCapacityMappingFromStatus() throws Exception {
        set("imageSizeReader", (RootBridge.ImageSizeReader) (service, name) -> { throw new AssertionError("Unexpected capacity mapping"); });
        gsi.running = true;
        org.json.JSONObject live = (org.json.JSONObject) invoke("status", new Class[0]);
        assertEquals(0, live.getJSONObject("userdataSizes").length());
        gsi.running = false; gsi.installing = true;
        org.json.JSONObject installing = (org.json.JSONObject) invoke("status", new Class[0]);
        assertEquals(0, installing.getJSONObject("userdataSizes").length());
    }
    @Test public void capacityFailureKeepsSlotEnumerationAndDoesNotInventSize() throws Exception {
        set("imageSizeReader", (RootBridge.ImageSizeReader) (service, name) -> { throw new java.io.IOException("Cannot map"); });
        org.json.JSONObject status = (org.json.JSONObject) invoke("status", new Class[0]);
        assertEquals(2, status.getJSONArray("slots").length());
        assertEquals(0, status.getJSONObject("userdataSizes").length());
        assertTrue(status.getJSONObject("userdataSizeErrors").getString("alpha").contains("Cannot map"));
    }
    @Test public void partialSlotCannotBeEnabled() throws Exception {
        Files.delete(new File(root, "beta/complete").toPath());
        try { invoke("enable", new Class[]{String.class, boolean.class}, "beta", true); fail(); }
        catch (IllegalStateException expected) { }
        assertEquals("alpha", gsi.active);
    }
    public static class LegacyGsi {
        public boolean disableGsiInstall() { return true; }
        public boolean removeGsiInstall() { return true; }
    }
    @Test public void android10UsesLegacyMethodNames() throws Exception {
        set("api", LegacyGsi.class); set("service", new LegacyGsi());
        assertEquals(true, invoke("call", new Class[]{String.class, Object[].class}, "disableGsi", new Object[0]));
        assertEquals(true, invoke("call", new Class[]{String.class, Object[].class}, "removeGsi", new Object[0]));
    }
    private void install(String name) throws Exception {
        File source = temporary.newFile(name + ".img.gz");
        invoke("install", new Class[]{String.class, File.class, long.class, long.class}, name, source, 1L << 30, 4096L);
    }
    @Test public void installingNewSlotNeverInvokesGlobalRemoveAndPreservesPriorSelection() throws Exception {
        gsi.enabled = true;
        install("gamma");
        assertEquals(Arrays.asList("alpha", "beta", "gamma"), gsi.getInstalledDsuSlots());
        assertEquals("alpha", gsi.active); assertTrue(gsi.enabled);
        assertEquals(0, gsi.globalRemoveCalls);
        assertEquals(2, gsi.images.get("alpha").names.size());
    }
    @Test public void newInstallCreatesAndLabelsBackingDirectoryBeforeCallingGsid() throws Exception {
        List<String> labeled = new ArrayList<>();
        set("directoryLabeler", (RootBridge.DirectoryLabeler) directory -> {
            assertTrue(directory.isDirectory()); labeled.add(directory.getName());
        });
        install("gamma");
        assertEquals(Arrays.asList("gamma"), labeled);
        assertTrue(new File(temporary.getRoot(), "images/gamma").isDirectory());
    }
    @Test public void unregisteredBackingDataIsNeverOverwritten() throws Exception {
        File directory = new File(temporary.getRoot(), "images/gamma"); assertTrue(directory.mkdir());
        File existing = new File(directory, "system_gsi.img"); Files.write(existing.toPath(), new byte[]{1, 2, 3});
        try { install("gamma"); fail(); } catch (IllegalStateException expected) { }
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(existing.toPath()));
        assertFalse(gsi.getInstalledDsuSlots().contains("gamma"));
    }
    @Test public void android14NamedInterfaceDoesNotImplyInternalMultipleInstallPaths() throws Exception {
        set("platformSdk", 34);
        try { install("gamma"); fail(); } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Android 15"));
        }
        assertEquals(Arrays.asList("alpha", "beta"), gsi.getInstalledDsuSlots());
        assertTrue(gsi.changedPartitions.isEmpty());
        assertFalse(new File(temporary.getRoot(), "images/gamma").exists());
    }
    @Test public void android14DefaultSlotUsesExactLegacyDirectory() throws Exception {
        set("platformSdk", 34); install("dsu");
        assertTrue(gsi.getInstalledDsuSlots().contains("dsu"));
        assertFalse(new File(temporary.getRoot(), "images/dsu").exists());
        assertEquals("alpha", gsi.active);
    }
    @Test public void android14VerifiedBackportAllowsIndependentInternalDirectory() throws Exception {
        set("platformSdk", 34);
        File binary = temporary.newFile("gsid"); Files.write(binary.toPath(), new byte[]{1, 2, 3});
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(binary.toPath()));
        StringBuilder hash = new StringBuilder(); for (byte value : digest) hash.append(String.format("%02x", value & 255));
        set("gsidBinary", binary); set("testedBackportHash", hash.toString());
        install("gamma");
        assertTrue(new File(temporary.getRoot(), "images/gamma").isDirectory());
        assertTrue(gsi.getInstalledDsuSlots().contains("gamma"));
        assertEquals("alpha", gsi.active);
    }
    @Test public void android14UnrecognizedBinaryDoesNotEnableBackportCapability() throws Exception {
        set("platformSdk", 34);
        File binary = temporary.newFile("unknown-gsid"); Files.write(binary.toPath(), new byte[]{4, 5, 6});
        set("gsidBinary", binary);
        try { install("gamma"); fail(); } catch (IllegalStateException expected) { }
        assertTrue(gsi.changedPartitions.isEmpty());
    }
    @Test public void unavailableBackportRejectsInternalMutationBeforeMarkingMaintenance() throws Exception {
        set("platformSdk", 34);
        Files.write(new File(root, "beta/install_dir").toPath(), "/data/gsi/dsu/beta/".getBytes());
        try { mutate("beta", specs("system"), 0); fail(); } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("多槽补丁"));
        }
        assertFalse(new File(root, "beta/ultradsu_maintenance.json").exists());
        assertTrue(gsi.changedPartitions.isEmpty());
    }
    @Test public void android11InstallationFinalizesBeforeRestoringBootSelection() throws Exception {
        gsi.legacyFinish = true;
        install("gamma");
        assertFalse(gsi.installing); assertFalse(gsi.enabled); assertEquals("alpha", gsi.active);
        assertEquals(0, gsi.globalRemoveCalls);
    }
    @Test public void failedNewSlotIsCleanedWithoutRemovingExistingSlots() throws Exception {
        set("imageWriter", (RootBridge.ImageWriter) (file, size) -> { throw new java.io.IOException("truncated"); });
        try { install("gamma"); fail(); } catch (java.io.IOException expected) { }
        assertEquals(Arrays.asList("alpha", "beta"), gsi.getInstalledDsuSlots());
        assertEquals("alpha", gsi.active); assertFalse(gsi.installing);
        assertEquals(0, gsi.globalRemoveCalls);
    }
    @Test public void firstInstallationDoesNotEnableOrRebootDsu() throws Exception {
        remove("beta"); remove("alpha");
        int priorRemoves = gsi.globalRemoveCalls;
        install("gamma");
        assertEquals(Arrays.asList("gamma"), gsi.getInstalledDsuSlots());
        assertEquals("gamma", gsi.active); assertFalse(gsi.enabled);
        assertEquals(priorRemoves, gsi.globalRemoveCalls);
    }
    @Test public void installRefusesExistingNameBeforeTouchingPartitions() throws Exception {
        try { install("alpha"); fail(); } catch (IllegalStateException expected) { }
        assertEquals(Arrays.asList("alpha", "beta"), gsi.getInstalledDsuSlots());
        assertFalse(gsi.installing); assertEquals(0, gsi.globalRemoveCalls);
    }
    private List<RootBridge.ImageSpec> specs(String... names) throws Exception {
        List<RootBridge.ImageSpec> result = new ArrayList<>();
        for (String name : names) result.add(new RootBridge.ImageSpec(name, temporary.newFile(UUID.randomUUID() + ".gz"), 4096));
        return result;
    }
    private void mutate(String slot, List<RootBridge.ImageSpec> replacements, long userdata) throws Exception {
        invoke("mutate", new Class[]{String.class, List.class, long.class}, slot, replacements, userdata);
    }
    @Test public void multiPartitionInstallCreatesAllSelectedImagesAndKeepsExistingSlots() throws Exception {
        invoke("installImages", new Class[]{String.class, List.class, long.class}, "gamma", specs("system", "product", "system_ext"), 8L << 30);
        assertEquals(Arrays.asList("gamma:userdata", "gamma:system", "gamma:product", "gamma:system_ext"), gsi.changedPartitions);
        assertEquals(4, gsi.images.get("gamma").names.size());
        assertEquals(1, (int) gsi.images.get("alpha").versions.get("system_gsi")); assertEquals(0, gsi.globalRemoveCalls);
    }
    @Test public void replacingSystemNeverRecreatesUserdataOrOtherSlot() throws Exception {
        gsi.enabled = true;
        mutate("beta", specs("system"), 0);
        assertEquals(Arrays.asList("beta:system"), gsi.changedPartitions);
        assertEquals(1, (int) gsi.images.get("beta").versions.get("userdata_gsi"));
        assertEquals("alpha", gsi.active); assertTrue(gsi.enabled); assertEquals(0, gsi.globalRemoveCalls);
        assertFalse(new File(root, "beta/ultradsu_maintenance.json").exists());
    }
    @Test public void replacingMultipleImagesCanAddPartitionsWithoutWipingUserdata() throws Exception {
        mutate("beta", specs("system", "product"), 0);
        assertTrue(gsi.images.get("beta").names.contains("product_gsi"));
        assertEquals(1, (int) gsi.images.get("beta").versions.get("userdata_gsi"));
    }
    @Test public void replacementFailureKeepsOtherPartitionsAndGuardsBootUntilRepaired() throws Exception {
        set("imageWriter", (RootBridge.ImageWriter) (file, size) -> { throw new java.io.IOException("write failed"); });
        try { mutate("beta", specs("system"), 0); fail(); } catch (IllegalStateException expected) { }
        assertEquals(1, (int) gsi.images.get("beta").versions.get("userdata_gsi"));
        assertTrue(new File(root, "beta/complete").isFile());
        assertTrue(new File(root, "beta/ultradsu_maintenance.json").isFile());
        try { invoke("enable", new Class[]{String.class, boolean.class}, "beta", true); fail(); } catch (IllegalStateException expected) { }
        set("imageWriter", (RootBridge.ImageWriter) (file, size) -> { });
        mutate("beta", specs("system"), 0);
        assertFalse(new File(root, "beta/ultradsu_maintenance.json").exists());
        invoke("enable", new Class[]{String.class, boolean.class}, "beta", true);
        assertEquals("beta", gsi.active);
    }
    @Test public void growingUserdataOnlyRecreatesUserdata() throws Exception {
        mutate("beta", Collections.emptyList(), 16L << 30);
        assertEquals(Arrays.asList("beta:userdata"), gsi.changedPartitions);
        assertEquals(16L << 30, (long) gsi.images.get("beta").sizes.get("userdata_gsi"));
        assertEquals(1, (int) gsi.images.get("beta").versions.get("system_gsi"));
        assertEquals(0, gsi.globalRemoveCalls);
    }
    @Test public void shrinkingOrSameSizeUserdataIsRejectedBeforeAnyWrite() throws Exception {
        for (long size : new long[]{4L << 30, 8L << 30}) {
            try { mutate("beta", Collections.emptyList(), size); fail(); } catch (IllegalArgumentException expected) { }
        }
        assertTrue(gsi.changedPartitions.isEmpty());
        assertFalse(new File(root, "beta/ultradsu_maintenance.json").exists());
    }
    @Test public void failedUserdataRebuildCanBeRetriedAtSameRequestedSize() throws Exception {
        gsi.failPartition = "userdata";
        try { mutate("beta", Collections.emptyList(), 16L << 30); fail(); } catch (IllegalStateException expected) { }
        gsi.failPartition = "";
        mutate("beta", Collections.emptyList(), 16L << 30);
        assertFalse(new File(root, "beta/ultradsu_maintenance.json").exists());
        assertEquals(1, (int) gsi.images.get("beta").versions.get("system_gsi"));
    }
    @Test public void modifyingActiveSlotPreservesItsPriorSingleBootMode() throws Exception {
        Files.write(new File(root, "one_shot_boot").toPath(), new byte[0]); gsi.enabled = true;
        mutate("alpha", specs("product"), 0);
        assertEquals("alpha", gsi.active); assertTrue(gsi.enabled); assertTrue(gsi.lastOneShot);
    }
    @Test public void failedActiveSlotRemainsDisabled() throws Exception {
        gsi.enabled = true; gsi.failPartition = "system";
        try { mutate("alpha", specs("system"), 0); fail(); } catch (IllegalStateException expected) { }
        assertFalse(gsi.enabled);
        assertEquals(1, (int) gsi.images.get("alpha").versions.get("userdata_gsi"));
    }
    @Test public void mappedTargetAndCriticalPartitionNamesAreRejectedBeforeWrite() throws Exception {
        gsi.images.get("beta").mapped = true;
        try { mutate("beta", specs("system"), 0); fail(); } catch (IllegalStateException expected) { }
        gsi.images.get("beta").mapped = false;
        try { mutate("beta", specs("boot"), 0); fail(); } catch (IllegalArgumentException expected) { }
        assertTrue(gsi.changedPartitions.isEmpty());
    }
    private File prepareBackupManager() throws Exception {
        Field field = RootBridge.class.getDeclaredField("imageRoot"); field.setAccessible(true); File imageRoot = (File)field.get(null);
        for (String slot : Arrays.asList("alpha", "beta")) {
            File directory = new File(imageRoot,slot); assertTrue(directory.mkdir());
            Files.write(new File(directory,"userdata_gsi.img").toPath(),new byte[]{1});
            Files.write(new File(directory,"userdata_gsi.img.0000").toPath(),new byte[]{7,8,9});
            Files.write(new File(root,slot+"/install_dir").toPath(),directory.getAbsolutePath().getBytes());
        }
        field = RootBridge.class.getDeclaredField("growthRoot"); field.setAccessible(true);
        File backup = new File((File)field.get(null),"alpha"); assertTrue(backup.mkdir());
        Files.write(new File(backup,"userdata.raw").toPath(),new byte[4096]);
        Files.write(new File(backup,"growth.json").toPath(),new org.json.JSONObject().put("version",1).put("phase","EXPANDED")
            .put("oldBytes",4096).put("newBytes",8192).toString().getBytes());
        return backup;
    }
    private org.json.JSONObject backups(String slot, String kind, String revision) throws Exception {
        return (org.json.JSONObject)invoke("userdataBackups",new Class[]{String.class,String.class,String.class},slot,kind,revision);
    }
    @Test public void backupManagerProtectsAllBoundUserdataWithoutMappingOrNativeChecks() throws Exception {
        prepareBackupManager();
        set("platformSdk",34);
        set("imageSizeReader",(RootBridge.ImageSizeReader)(service,name)->{throw new AssertionError("Manager must not map images");});
        org.json.JSONArray entries=backups(null,null,null).getJSONArray("entries"); assertEquals(3,entries.length());
        for(int i=0;i<2;i++) { assertEquals("current",entries.getJSONObject(i).getString("kind")); assertFalse(entries.getJSONObject(i).getBoolean("canDelete")); }
        assertTrue(entries.getJSONObject(2).getBoolean("canDelete"));
        assertTrue(gsi.changedPartitions.isEmpty()); assertEquals(0,gsi.globalRemoveCalls);
    }
    @Test public void backupDeletionNeverChangesLivePartitionsOrBootSelection() throws Exception {
        File backup=prepareBackupManager(); org.json.JSONObject row=backups(null,null,null).getJSONArray("entries").getJSONObject(2);
        gsi.enabled=true;
        try { backups("alpha","current",row.getString("revision")); fail(); } catch(SecurityException expected){}
        assertTrue(backup.isDirectory());
        backups("alpha","growth",row.getString("revision")); assertFalse(backup.exists());
        assertEquals("alpha",gsi.active); assertTrue(gsi.enabled); assertTrue(gsi.changedPartitions.isEmpty());
        assertEquals(Arrays.asList("system_gsi","userdata_gsi"),gsi.images.get("alpha").names);
        assertEquals(Arrays.asList("system_gsi","userdata_gsi"),gsi.images.get("beta").names);
    }
    @Test public void backupManagerRejectsLiveGuestInstallationAndUnverifiedLayouts() throws Exception {
        File backup=prepareBackupManager(); String revision=backups(null,null,null).getJSONArray("entries").getJSONObject(2).getString("revision");
        gsi.running=true; try { backups("alpha","growth",revision); fail(); } catch(IllegalStateException expected){}
        gsi.running=false; gsi.installing=true; try { backups("alpha","growth",revision); fail(); } catch(IllegalStateException expected){}
        gsi.installing=false; Files.write(new File(root,"beta/install_dir").toPath(),"/unknown/storage".getBytes());
        assertFalse(backups(null,null,null).getJSONArray("entries").getJSONObject(2).getBoolean("canDelete"));
        try { backups("alpha","growth",revision); fail(); } catch(IllegalStateException expected){}
        assertTrue(new File(backup,"userdata.raw").isFile());
    }
    @Test public void repairMarkerKeepsBackupProtectedEvenWithCompletedGrowthJournal() throws Exception {
        File backup=prepareBackupManager(); String revision=backups(null,null,null).getJSONArray("entries").getJSONObject(2).getString("revision");
        Files.write(new File(root,"alpha/ultradsu_maintenance.json").toPath(),"[\"userdata\"]".getBytes());
        assertFalse(backups(null,null,null).getJSONArray("entries").getJSONObject(2).getBoolean("canDelete"));
        try { backups("alpha","growth",revision); fail(); } catch(IllegalStateException expected){}
        assertTrue(new File(backup,"userdata.raw").isFile());
    }
}
