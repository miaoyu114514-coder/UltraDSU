package io.github.ultradsu

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ultradsu.dsu.GuestAssistanceClient
import io.github.ultradsu.dsu.RootClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.KeyStore
import java.security.PrivateKey
import io.github.ultradsu.dsu.AssistanceProtocol
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit V50 handoff verification. Never runs during ordinary connected tests. */
class GuestAssistanceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val slot = "v50-hyperos4-r1"
    private fun selected(phase: String) {
        assumeTrue("Explicit V50 test opt-in required", args.getString("ULTRADSU_ASSIST_TEST") == "V50_EXPLICIT")
        assumeTrue("Select one test phase", args.getString("phase") == phase)
        assumeTrue(context.packageName == "io.github.ultradsu")
    }
    @Test fun hostHardwareEnvelopeOpensAndValidatesTheRealSlot() = runBlocking {
        selected("host-check")
        val root = RootClient(context); assertTrue(root.authorize())
        assertFalse(root.bridge("status").getBoolean("running"))
        val result = GuestAssistanceClient(root).bridge("grow-info", slot)
        assertTrue(result.optString("reason"), result.getBoolean("supported"))
        assertTrue(result.getJSONObject("filesystem").getBoolean("assisted"))
        assertEquals("CAPTURED", result.getJSONObject("assistance").getString("phase"))
    }
    @Test fun restoreUserCancelled128GiBGrowthFromItsVerifiedBackup() = runBlocking {
        selected("restore-cancelled-128")
        val root = RootClient(context); assertTrue(root.authorize())
        val status = root.bridge("status")
        assertFalse(status.getBoolean("running")); assertFalse(status.getBoolean("installing"))
        assertEquals(128L shl 30, status.getJSONObject("userdataSizes").getLong(slot))
        val restored = GuestAssistanceClient(root).bridge("grow-rollback", slot).getJSONObject("growth")
        assertTrue(restored.getString("phase") in setOf("ROLLED_BACK", "ROLLED_BACK_RAW"))
        assertEquals(16L shl 30, restored.getLong("oldBytes"))
        assertEquals(16L shl 30, root.bridge("status").getJSONObject("userdataSizes").getLong(slot))
    }
    @Test fun benchmarkLocalRelocationOnIndependentV50Copy() = runBlocking {
        selected("local-benchmark")
        val root=RootClient(context); assertTrue(root.authorize())
        val status=root.bridge("status")
        assertFalse(status.getBoolean("running")); assertFalse(status.getBoolean("installing"))
        assertEquals(18L shl 30, status.getJSONObject("userdataSizes").getLong(slot))
        val info=root.bridge("assist-info",slot)
        val request=info.getJSONObject("request")
        val key=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey("ultradsu.guest_assist.${request.getString("session")}",null) as PrivateKey
        val packet=AssistanceProtocol.decrypt(request,info.getJSONObject("envelope"),key)
        val command="ulimit -c 0; CLASSPATH=/data/adb/ultradsu/local-growth-test/benchmark.jar:${context.applicationInfo.sourceDir} /system/bin/app_process /system/bin io.github.ultradsu.dsu.LocalGrowthBenchmark"
        val child=ProcessBuilder("su","-c",command).redirectErrorStream(true).start()
        val pool=Executors.newSingleThreadExecutor()
        val result=pool.submit<String> { child.inputStream.bufferedReader().readText() }
        try {
            child.outputStream.use { it.write(packet) }; packet.fill(0)
            assertTrue("Independent-copy benchmark timed out",child.waitFor(5,TimeUnit.MINUTES))
            val output=result.get(10,TimeUnit.SECONDS)
            java.io.File(context.filesDir,"local-growth-benchmark.txt").writeText(output)
            println(output)
            assertEquals(output,0,child.exitValue())
            assertTrue(output.contains("BENCH_RESIZE_SECONDS:"))
        }finally { packet.fill(0); child.destroyForcibly(); pool.shutdownNow() }
    }
    @Test fun benchmark128GiBOnIndependentV50Copy() = runBlocking {
        selected("large-local-benchmark")
        val root=RootClient(context); assertTrue(root.authorize())
        val status=root.bridge("status")
        assertFalse(status.getBoolean("running")); assertFalse(status.getBoolean("installing"))
        assertEquals(16L shl 30,status.getJSONObject("userdataSizes").getLong(slot))
        val info=root.bridge("assist-info",slot)
        val request=info.getJSONObject("request")
        val keys=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey=keys.getKey("ultradsu.guest_assist.${request.getString("session")}",null) as PrivateKey
        val packet=AssistanceProtocol.decrypt(request,info.getJSONObject("envelope"),privateKey)
        val command="ulimit -c 0; CLASSPATH=/data/adb/ultradsu/large-growth-test/benchmark.jar:${context.applicationInfo.sourceDir} app_process /system/bin io.github.ultradsu.dsu.LargeGrowthBenchmark"
        val child=ProcessBuilder("su","-c",command).redirectErrorStream(true).start()
        val pool=Executors.newSingleThreadExecutor()
        val result=pool.submit<String> { child.inputStream.bufferedReader().readText() }
        try {
            child.outputStream.use { it.write(packet) }; packet.fill(0)
            assertTrue("Independent 128 GiB benchmark timed out",child.waitFor(20,TimeUnit.MINUTES))
            val output=result.get(10,TimeUnit.SECONDS)
            java.io.File(context.filesDir,"large-growth-benchmark.txt").writeText(output)
            println(output)
            assertEquals(output,0,child.exitValue())
            assertTrue(output.contains("BENCH_128_GEOMETRY_AND_FBE_PASSED"))
            assertTrue("Native block progress missing",output.contains("ULTRADSU_FS_PROGRESS:1:"))
        }finally { packet.fill(0); child.destroyForcibly(); pool.shutdownNow() }
    }
    @Test fun deliberatelyForgetOnlyThisTemporaryHostCredentialWithVerifiedBackupPresent() = runBlocking {
        selected("forget-key")
        val root = RootClient(context); assertTrue(root.authorize())
        assertFalse(root.bridge("status").getBoolean("running"))
        val state = GuestAssistanceClient(root).bridge("grow-info", slot).getJSONObject("growth")
        assertEquals("EXPANDED", state.getString("phase"))
        assertTrue(state.getBoolean("assisted"))
        assertEquals(16L shl 30, state.getLong("oldBytes"))
        assertEquals(16L shl 30, state.getLong("backupBytes"))
        assertTrue(state.getString("sha256").matches(Regex("[a-f0-9]{64}")))
        val info = root.bridge("assist-info", slot)
        val session = info.getString("session")
        assertTrue(session.matches(Regex("[a-f0-9]{32}")))
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "ultradsu.guest_assist.$session"
        assertTrue(keys.containsAlias(alias))
        keys.deleteEntry(alias)
        assertFalse(keys.containsAlias(alias))
        val result = GuestAssistanceClient(root).bridge("grow-info", slot)
        assertFalse(result.getBoolean("supported"))
        assertTrue(result.getJSONObject("growth").getBoolean("canRollback"))
        assertTrue(result.optString("assistanceError").isNotBlank())
    }
}
