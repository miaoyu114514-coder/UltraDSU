package io.github.ultradsu

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ultradsu.data.SlotState
import io.github.ultradsu.data.SlotStore
import io.github.ultradsu.dsu.PreparationService
import io.github.ultradsu.dsu.RootClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only: installs the user's already prepared new slot; never reboots. */
@RunWith(AndroidJUnit4::class)
class GsidBackportDeviceTest {
    @Test fun installPreparedNewSlotAndPreserveExistingDsu(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit device-install arguments", arguments.getString("allowDsuInstall") == "NEW_SLOT_ONLY")
        val id = arguments.getString("slotId") ?: error("slotId required")
        val name = arguments.getString("slotName") ?: error("slotName required")
        require(name == "los") { "This manual verification is restricted to the requested los slot" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = RootClient(context)
        check(root.authorize())
        val before = root.bridge("status")
        check(before.getBoolean("internalMultiSlot"))
        check(!before.getBoolean("running") && !before.getBoolean("installing"))
        check(before.getJSONArray("slots").toString().contains("\"dsu\""))
        check(!before.getJSONArray("occupiedSlots").toString().contains("\"$name\""))
        val slot = SlotStore(context).read().single { it.id == id }
        check(slot.slotName == name && slot.state == SlotState.PREPARED && slot.images.isNotEmpty())
        val fileStatCommand = "stat -c '%n|%d|%i|%s|%Y' /data/gsi/dsu/*_gsi.img*"
        val metadataCommand = "sha256sum /metadata/gsi/dsu/dsu/complete /metadata/gsi/dsu/dsu/install_dir"
        val filesBefore = root.shell(fileStatCommand)
        val metadataBefore = root.shell(metadataCommand)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        context.startForegroundService(Intent(context, PreparationService::class.java).setAction("install").putExtra("slotId", id))
        val finished = withTimeout(10 * 60 * 1000L) {
            PreparationService.state.onEach { Log.i("UltraDSU-GsidTest", it.message) }
                .first { it.id == id && !it.busy && it.finishedAt > 0 }
        }
        assertTrue(finished.message, finished.message.startsWith("新槽安装完成"))
        val after = root.bridge("status")
        Log.i("UltraDSU-GsidTest", "Final status: $after")
        assertTrue(after.getJSONArray("slots").toString().contains("\"$name\""))
        assertTrue(after.getJSONArray("slots").toString().contains("\"dsu\""))
        assertEquals(before.getString("active"), after.getString("active"))
        assertEquals(before.getBoolean("enabled"), after.getBoolean("enabled"))
        assertEquals(filesBefore, root.shell(fileStatCommand))
        assertEquals(metadataBefore, root.shell(metadataCommand))
        assertEquals(SlotState.INSTALLED, SlotStore(context).read().single { it.id == id }.state)
        Log.i("UltraDSU-GsidTest", "Original DSU file identities, sizes, mtimes and registration preserved")
    }
}
