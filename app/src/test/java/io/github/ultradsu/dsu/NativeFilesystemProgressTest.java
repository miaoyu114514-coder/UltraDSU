package io.github.ultradsu.dsu;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class NativeFilesystemProgressTest {
    @Test public void localAndFallbackReportActualStageRange() throws Exception {
        JSONObject local=NativeFilesystemProgress.parse("ULTRADSU_FS_PROGRESS:1:4096:8192");
        assertNotNull(local); assertEquals(4096,local.getLong("bytes")); assertEquals(8192,local.getLong("totalBytes"));
        assertTrue(local.getString("message").contains("局部"));
        assertTrue(NativeFilesystemProgress.parse("ULTRADSU_FS_PROGRESS:2:0:137438953472").getString("message").contains("全量"));
    }
    @Test public void metadataStagesRemainIndeterminate() throws Exception {
        for(int phase=3;phase<=6;phase++) assertEquals(0,NativeFilesystemProgress.parse("ULTRADSU_FS_PROGRESS:"+phase+":0:0").getLong("totalBytes"));
    }
    @Test public void malformedUnboundedOrArbitraryOutputIsNotForwarded() {
        for(String text:new String[]{"secret table", "ULTRADSU_FS_PROGRESS:7:0:0", "ULTRADSU_FS_PROGRESS:1:-1:2",
            "ULTRADSU_FS_PROGRESS:1:3:2", "ULTRADSU_FS_PROGRESS:1:0:549755813889", "ULTRADSU_FS_PROGRESS:1:0:0:extra",
            "ULTRADSU_FS_PROGRESS:1:0:999999999999999999999999", "ULTRADSU_FS_PROGRESS:1:0:1\nkey", "ULTRADSU_FS_PROGRESS:01:0x0:1"})
            assertNull(text,NativeFilesystemProgress.parse(text));
    }
}
