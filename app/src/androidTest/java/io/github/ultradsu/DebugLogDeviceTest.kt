package io.github.ultradsu

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ultradsu.diagnostics.DebugLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DebugLogDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private fun isolated() = assumeTrue("Use isolated test package", context.packageName == "io.github.ultradsu.progressverify")
    private fun node(label: String): AccessibilityNodeInfo? {
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.text?.toString() == label || current.contentDescription?.toString() == label) return current
            repeat(current.childCount) { current.getChild(it)?.let { child -> find(child)?.let { return it } } }
            return null
        }
        return automation.rootInActiveWindow?.let(::find)
    }
    private fun await(block: () -> Boolean) {
        val until = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < until) { if (block()) return; Thread.sleep(100) }
        fail("Timed out waiting for debug UI/state")
    }
    private fun shell(command: String) {
        automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    private fun tap(label: String) {
        await { node(label) != null }
        // Export/clear feedback inserts a message item and moves the lazy list.
        // Wait for layout to settle before obtaining pointer coordinates.
        Thread.sleep(600)
        if (label == "清空日志") {
            var target = node(label)
            while (target != null) {
                if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { Thread.sleep(450); return }
                target = target.parent
            }
        }
        val bounds = Rect().also { node(label)!!.getBoundsInScreen(it) }
        val x = if (label == "调试模式开关") {
            val checked = context.getSharedPreferences("settings", 0).getBoolean("debug_enabled", false)
            bounds.left + (bounds.width() * if (checked) .72f else .28f).toInt()
        } else bounds.centerX()
        shell("input tap $x ${bounds.centerY()}")
        Thread.sleep(450)
    }
    @Test fun optInRotationRedactionAndDisabledExport() = runBlocking {
        isolated()
        DebugLog.setEnabled(context, false); DebugLog.clear()
        DebugLog.event("test", "must-not-be-collected")
        assertFalse(DebugLog.snapshot().contains("must-not-be-collected"))
        DebugLog.setEnabled(context, true)
        assertTrue(context.getSharedPreferences("settings", 0).getBoolean("debug_enabled", false))
        DebugLog.remember("private-slot", "personal-image.img")
        DebugLog.event("test", "private-slot personal-image.img content://private/name /data/private/file serial=ABCD")
        val report = DebugLog.snapshot()
        listOf("private-slot", "personal-image.img", "content://private", "/data/private", "ABCD").forEach { assertFalse(report.contains(it)) }
        // Exercise actual disk rotation rather than mirroring a size arithmetic helper.
        repeat(90) { DebugLog.event("rotate", "x".repeat(15000)) }
        DebugLog.snapshot()
        val files = File(context.noBackupFilesDir, "diagnostics").listFiles()!!.filter { it.extension == "log" }
        assertEquals(2, files.size)
        assertTrue(files.all { it.length() <= 512 * 1024 })
        DebugLog.setEnabled(context, false)
        DebugLog.event("test", "disabled-after-rotation")
        assertFalse(DebugLog.snapshot().contains("disabled-after-rotation"))
        DebugLog.clear()
        assertFalse(DebugLog.snapshot().contains("[rotate]"))
    }
    @Test fun bothStylesTogglePersistExportAndClearWithoutRoot() {
        isolated()
        context.getSharedPreferences("settings", 0).edit().putBoolean("root_enabled", false).putBoolean("glass_navigation", false).commit()
        for (style in listOf("MIUIX", "MD3")) {
            context.getSharedPreferences("settings", 0).edit().putString("style", style).commit()
            DebugLog.setEnabled(context, false)
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            lateinit var model: AppViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[AppViewModel::class.java] }
            try {
                tap("设置")
                await { node("权限与能力") != null }
                // Lazy item descendants may exist outside the viewport; scroll the card fully into view.
                shell("input swipe 360 1200 360 650 350")
                Thread.sleep(600)
                await { node("调试模式开关")?.let { val bounds = Rect(); it.getBoundsInScreen(bounds); bounds.top in 250..1100 } == true }
                tap("调试模式开关")
                await { model.state.value.debugMode }
                assertTrue(context.getSharedPreferences("settings", 0).getBoolean("debug_enabled", false))
                assertNull(node("采集 DSU 系统诊断"))
                val image = automation.takeScreenshot()!!
                File(context.getExternalFilesDir("progress-verification"), "debug-$style.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                DebugLog.event("test-export", "stage=createPartition userdata error=1")
                val target = File(context.cacheDir, "debug-export.txt")
                instrumentation.runOnMainSync { model.exportDebugLog(Uri.fromFile(target)) }
                await { !model.state.value.diagnosticsBusy && target.exists() }
                assertTrue(target.readText().contains("stage=createPartition userdata error=1"))
                tap("清空日志")
                await { !model.state.value.diagnosticsBusy && model.state.value.message == "调试日志已清空" }
                assertFalse(runBlocking { DebugLog.snapshot() }.contains("test-export"))
                tap("调试模式开关")
                await { !model.state.value.debugMode }
                assertFalse(context.getSharedPreferences("settings", 0).getBoolean("debug_enabled", true))
                target.delete()
            } catch (failure: Throwable) {
                val image = automation.takeScreenshot()
                image?.let { bitmap ->
                    File(context.getExternalFilesDir("progress-verification"), "failure-$style.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                throw failure
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }
}
