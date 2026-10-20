package io.github.ultradsu.dsu

import io.github.ultradsu.data.UserdataGrowthInfo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UserdataGrowthCheckTest {
    @Test fun `keymint invalid argument explains possible version binding rather than broken userdata`() {
        val info = UserdataGrowthCheck.read(JSONObject("""{"supported":false,"growth":{"exists":false},
            "reason":"Cannot unseal existing slot key: KEYSTORE_INVALID_ARGUMENT(-38); no key was generated"}"""), 36)
        assertFalse(info.supported)
        assertFalse(info.checkFailed)
        assertTrue(info.reason.contains("版本绑定"))
        assertTrue(info.reason.contains("安全补丁"))
        assertTrue(info.reason.contains("不会重建密钥或清空数据"))
        assertFalse(info.reason.contains("损坏"))
    }

    @Test fun `other key retrieval failures do not claim a confirmed invalid argument`() {
        val info = UserdataGrowthCheck.read(JSONObject("""{"supported":false,"growth":{"exists":false},
            "reason":"Cannot unseal existing slot key; no key was generated"}"""), 36)
        assertTrue(info.reason.contains("无法解封装"))
        assertFalse(info.reason.contains("INVALID_ARGUMENT"))
    }

    @Test fun `moto legacy slot rejection explains layout and native API requirement`() {
        val info = UserdataGrowthCheck.failed(null,
            "IllegalStateException: 保留数据扩容仅支持已适配的内部多槽标准布局", 34)
        assertTrue(info.checkFailed)
        assertFalse(info.supported)
        assertTrue(info.reason.contains("布局"))
        assertTrue(info.reason.contains("API 34"))
        assertTrue(info.reason.contains("Android 16+"))
    }

    @Test fun `failed refresh keeps last backup facts but revokes stale actions`() {
        val previous = UserdataGrowthInfo(supported = true, exists = true, phase = "EXPANDED",
            backupBytes = 8L shl 30, oldBytes = 8L shl 30, newBytes = 10L shl 30,
            canRollback = true, canDiscard = true)
        val failed = UserdataGrowthCheck.failed(previous, "另一个 UltraDSU 操作正在进行", 37)
        assertTrue(failed.exists)
        assertEquals(previous.backupBytes, failed.backupBytes)
        assertEquals(previous.phase, failed.phase)
        assertFalse(failed.supported)
        assertFalse(failed.canRollback)
        assertFalse(failed.canDiscard)
        assertTrue(failed.reason.contains("等待完成后重新检查"))
        assertFalse(failed.reason.contains("不支持"))
    }

    @Test fun `unsupported native tools still expose authoritative recovery state`() {
        val info = UserdataGrowthCheck.read(JSONObject("""{
            "supported":false,"reason":"保留数据扩容暂支持 Android 16+ 的 arm64 设备",
            "growth":{"exists":true,"phase":"RECOVERY_REQUIRED","backupBytes":8589934592,
            "oldBytes":8589934592,"newBytes":10737418240,"canRollback":true,"canDiscard":false}}
        """), 34)
        assertFalse(info.checkFailed)
        assertFalse(info.supported)
        assertTrue(info.exists)
        assertTrue(info.canRollback)
        assertFalse(info.canDiscard)
        assertEquals(8L shl 30, info.backupBytes)
        assertTrue(info.reason.contains("API 34"))
    }

    @Test fun `successful retry clears failure and uses fresh capacity and backup state`() {
        val info = UserdataGrowthCheck.read(JSONObject("""{"supported":true,
            "filesystem":{"filesystem":"f2fs","encrypted":true,"blockBytes":10737418240,
            "filesystemBytes":10737418240},"growth":{"exists":false}}"""), 37)
        assertTrue(info.supported)
        assertFalse(info.checkFailed)
        assertFalse(info.exists)
        assertEquals(10L shl 30, info.actualBytes)
        assertEquals("f2fs", info.filesystem)
        assertTrue(info.encrypted)
        assertEquals("", info.reason)
    }

    @Test fun `unknown and empty failures explain retry and log export without claiming incompatibility`() {
        for (reason in listOf("", "Timed out", "Root 服务未返回结果")) {
            val info = UserdataGrowthCheck.failed(null, reason, 37)
            assertTrue(info.checkFailed)
            assertTrue(info.reason.contains("重试"))
            assertTrue(info.reason.contains("导出日志"))
            assertFalse(info.reason.contains("不支持"))
        }
    }
}
