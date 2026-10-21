package io.github.ultradsu.dsu

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ultradsu.MainActivity
import io.github.ultradsu.AppViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.ultradsu.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPOutputStream

/** Explicit Moto opt-in. Only creates its own never-booted synthetic slot and app-cache files. */
class InstallExportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private fun optIn() {
        assumeTrue(arguments.getString("ULTRADSU_INSTALL_TEST") == "MOTO_EXPLICIT")
        assumeTrue(context.packageName == "io.github.ultradsu")
        assumeTrue(android.os.Build.MODEL == "XT2251-1" || android.os.Build.MODEL == "XT2251_1")
    }
    private fun await(message: String, seconds: Int = 180, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("$message: ${PreparationService.state.value}")
    }
    private fun hash(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val bytes=ByteArray(1024*1024); while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)} }
        return digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
    }
    private fun sameExisting(before: JSONObject, after: JSONObject) {
        assertEquals(before.getJSONArray("slots").toString(),after.getJSONArray("slots").toString())
        assertEquals(before.getString("active"),after.getString("active")); assertEquals(before.getBoolean("enabled"),after.getBoolean("enabled"))
        assertEquals(before.getJSONObject("userdataSizes").toString(),after.getJSONObject("userdataSizes").toString())
        assertFalse(after.getBoolean("running")); assertFalse(after.getBoolean("installing"))
    }
    @Test fun androidCleanupPreservesPendingFilesAndRejectsSymlinks() {
        optIn()
        val root=File(context.cacheDir,"cleanup-${UUID.randomUUID()}").apply{mkdir()}
        val id=UUID.randomUUID().toString();val group="prepared_${UUID.randomUUID()}"
        val image=File(root,"$id/$group/${UUID.randomUUID()}.img.gz").apply{parentFile.mkdirs();writeBytes(ByteArray(64))}
        val original=File(root,"original.7z").apply{writeBytes(byteArrayOf(7,8,9))}
        val link=File(image.parentFile,"unsafe-link")
        val slot=DsuSlot(id=id,slotName="fixture",displayName="Fixture",state=SlotState.INSTALLED,
            images=listOf(PartitionImage("system","$group/${image.name}",4096,"system.img")))
        try {
            val cleaner=PreparedImageCleaner(root)
            assertEquals(0L,cleaner.clean(slot.copy(pendingImages=slot.images),true));assertTrue(image.exists())
            android.system.Os.symlink(original.absolutePath,link.absolutePath)
            try {cleaner.clean(slot,true);fail("Symlink must block cleanup")}catch(expected:IllegalStateException){}
            assertTrue(image.exists());assertArrayEquals(byteArrayOf(7,8,9),original.readBytes())
            assertTrue(link.delete());assertEquals(64L,cleaner.clean(slot,true));assertFalse(image.exists())
            assertArrayEquals(byteArrayOf(7,8,9),original.readBytes())
        } finally {link.delete();root.walkBottomUp().forEach{it.delete()}}
    }
    @Test fun combinedInstallationExportAndCancellationPreserveExistingSlots() = runBlocking {
        optIn()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var app: AppViewModel
        instrumentation.runOnMainSync { app=ViewModelProvider(activity)[AppViewModel::class.java] }
        await("Initial refresh did not finish"){!app.state.value.busy}
        val root=RootClient(context); assertTrue(root.authorize())
        val baseline=root.bridge("status"); assertTrue(baseline.getBoolean("internalMultiSlot"))
        assertFalse(baseline.getBoolean("running")); assertFalse(baseline.getBoolean("installing"))
        val name="udsu_test_"+UUID.randomUUID().toString().take(8)
        assertFalse(baseline.getJSONArray("occupiedSlots").toString().contains(name))
        val source=File(context.cacheDir,"$name.img")
        val exported=File(context.cacheDir,"$name-export.img")
        val zipped=File(context.cacheDir,"$name-large.img.gz")
        val manifest=File(context.cacheDir,"$name-operation.json")
        val block=ByteArray(1024*1024){index -> (index * 13).toByte()}
        source.outputStream().buffered().use { output ->
            block[1080]=0x53; block[1081]=0xef.toByte(); output.write(block)
            block[1080]=0; block[1081]=0
            repeat(63){output.write(block)}
        }
        val slot=DsuSlot(slotName=name,displayName="安装与提取验证",userdataGiB=1,sourceUri=Uri.fromFile(source).toString(),sourceName="system.img")
        val store=SlotStore(context); store.write(store.read()+slot)
        try {
            context.startForegroundService(Intent(context,PreparationService::class.java).setAction("install-auto").putExtra("slotId",slot.id))
            await("Combined task never started"){PreparationService.state.value.let{it.id==slot.id && it.busy}}
            await("Combined task did not finish"){PreparationService.state.value.let{it.id==slot.id && !it.busy && it.outcome!=null}}
            Thread.sleep(500); await("Install refresh still busy"){!app.state.value.busy}
            assertEquals(PreparationService.state.value.message,TaskOutcome.SUCCESS,PreparationService.state.value.outcome)
            assertEquals(SlotState.INSTALLED,store.read().first{it.id==slot.id}.state)
            val installed=store.read().first{it.id==slot.id}
            assertTrue(installed.preparedFilesCleaned)
            assertTrue(installed.images.all{it.fileName.isEmpty()})
            assertFalse(PreparationService.slotDirectory(context,slot.id).walkTopDown().any{it.isFile && it.name.endsWith(".img.gz")})
            assertTrue(source.exists())
            assertTrue(root.bridge("status").getJSONArray("slots").toString().contains(name))

            // Replacing a partition after initial cleanup must resolve its NEW pending file.
            PreparationService.dismissResult()
            context.startForegroundService(Intent(context,PreparationService::class.java).setAction("stage-update")
                .putExtra("slotId",slot.id).putExtra("sourceUri",Uri.fromFile(source).toString()).putExtra("sourceName","system.img").putExtra("partition","system"))
            await("Replacement preparation did not finish"){PreparationService.state.value.let{it.id==slot.id && !it.busy && it.outcome!=null}}
            assertEquals(TaskOutcome.SUCCESS,PreparationService.state.value.outcome)
            val pending=store.read().first{it.id==slot.id}
            assertTrue(pending.pendingImages.isNotEmpty())
            assertTrue(PreparationService.resolve(context,pending,pending.pendingImages.first()).isFile)
            PreparationService.dismissResult()
            context.startForegroundService(Intent(context,PreparationService::class.java).setAction("replace").putExtra("slotId",slot.id))
            await("Replacement did not finish"){PreparationService.state.value.let{it.id==slot.id && !it.busy && it.outcome!=null}}
            Thread.sleep(500);await("Replacement refresh still busy"){!app.state.value.busy}
            assertEquals(PreparationService.state.value.message,TaskOutcome.SUCCESS,PreparationService.state.value.outcome)
            assertTrue(store.read().first{it.id==slot.id}.preparedFilesCleaned)
            assertFalse(PreparationService.slotDirectory(context,slot.id).walkTopDown().any{it.isFile && it.name.endsWith(".img.gz")})
            val events=mutableListOf<OperationProgress>()
            val result=root.exportPartition(name,"system",Uri.fromFile(exported),OperationCancellation()){events.add(it)}
            assertEquals(source.length(),result.getLong("bytes")); assertEquals(hash(source),hash(exported))
            assertTrue(events.any{it.message=="提取 system" && it.totalBytes==source.length()})
            assertFalse(root.bridge("images",name).getJSONArray("partitions").getJSONObject(0).getBoolean("mapped"))
            val exportCancel=OperationCancellation()
            try {
                root.exportPartition(name,"system",Uri.fromFile(exported),exportCancel){if(it.message=="提取 system" && it.bytes==0L)exportCancel.request()}
                fail("Export cancellation was not acknowledged")
            } catch(expected:java.util.concurrent.CancellationException) { }
            val inventory=root.bridge("images",name).getJSONArray("partitions")
            repeat(inventory.length()){assertFalse(inventory.getJSONObject(it).getBoolean("mapped"))}
            root.bridge("remove",name)
            sameExisting(baseline,root.bridge("status"))

            // Exercise actual allocation cancellation through the same foreground service.
            store.update(slot.id){it.copy(state=SlotState.DRAFT,preparedFilesCleaned=false)}
            PreparationService.dismissResult()
            context.startForegroundService(Intent(context,PreparationService::class.java).putExtra("slotId",slot.id))
            await("Retry preparation did not finish"){PreparationService.state.value.let{it.id==slot.id && !it.busy && it.outcome!=null}}
            assertEquals(TaskOutcome.SUCCESS,PreparationService.state.value.outcome)
            PreparationService.dismissResult()
            context.startForegroundService(Intent(context,PreparationService::class.java).setAction("install-auto").putExtra("slotId",slot.id))
            await("No cancellable userdata allocation"){PreparationService.state.value.let{it.busy && it.message.startsWith("分配 userdata")}}
            context.startService(Intent(context,PreparationService::class.java).setAction("cancel"))
            await("Cancelled allocation did not finish"){PreparationService.state.value.let{!it.busy && it.outcome!=null}}
            Thread.sleep(500); await("Cancel refresh still busy"){!app.state.value.busy}
            assertEquals(PreparationService.state.value.message,TaskOutcome.CANCELLED,PreparationService.state.value.outcome)
            assertEquals(SlotState.PREPARED,store.read().first{it.id==slot.id}.state)
            val retry=store.read().first{it.id==slot.id}
            assertFalse(retry.preparedFilesCleaned)
            assertTrue(PreparationService.resolve(context,retry,retry.images.first()).isFile)
            sameExisting(baseline,root.bridge("status"))

            // A compressed synthetic 512 MiB stream leaves time to cancel an actual Binder write.
            GZIPOutputStream(zipped.outputStream().buffered(),1024*1024).use{output->repeat(512){output.write(block)}}
            manifest.writeText(JSONArray().put(JSONObject().put("partition","system").put("path",zipped.absolutePath).put("bytes",512L*1024*1024)).toString())
            val writeCancel=OperationCancellation(); var sawPartition=false
            try {
                root.bridge("install-package",name,manifest.absolutePath,GIB.toString(),cancellation=writeCancel,progress={
                    if(it.message=="写入 system" && it.bytes==0L){sawPartition=true;writeCancel.request()}
                }); fail("Write cancellation was not acknowledged")
            } catch(expected:java.util.concurrent.CancellationException) { }
            assertTrue(sawPartition); sameExisting(baseline,root.bridge("status"))
        } finally {
            if(PreparationService.state.value.busy && PreparationService.state.value.cancellable){
                context.startService(Intent(context,PreparationService::class.java).setAction("cancel"))
                await("Cleanup still running"){!PreparationService.state.value.busy}
            }
            val status=root.bridge("status")
            if(status.getJSONArray("occupiedSlots").toString().contains(name)) root.bridge("remove",name)
            PreparationService.clearFiles(context,slot.id); store.write(store.read().filterNot{it.id==slot.id})
            PreparationService.dismissResult(); listOf(source,exported,zipped,manifest).forEach{it.delete()}
            instrumentation.runOnMainSync{activity.finish()}
        }
    }
}
