package io.github.ultradsu

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ultradsu.data.DsuSlot
import io.github.ultradsu.data.SlotState
import io.github.ultradsu.data.SlotStore
import io.github.ultradsu.dsu.PreparationService
import io.github.ultradsu.dsu.TaskOutcome
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires an isolated applicationId; never edits the user's installed app or any DSU slot. */
@RunWith(AndroidJUnit4::class)
class OperationBubbleDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private fun await(message: String, timeout: Long = 15000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        screenshot("test-failure")
        fail("$message; ${PreparationService.state.value}")
    }
    private fun node(label: String): AccessibilityNodeInfo? {
        fun search(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.text?.toString() == label || current.contentDescription?.toString() == label) return current
            repeat(current.childCount) { index -> current.getChild(index)?.let { search(it)?.let { found -> return found } } }
            return null
        }
        return automation.rootInActiveWindow?.let(::search)
    }
    private fun click(label: String) {
        await("Missing UI: $label") { node(label) != null }
        val bounds = Rect().also { node(label)!!.getBoundsInScreen(it) }
        var current = node(label)
        while (current != null) {
            if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            current = current.parent
        }
        automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
        }
    }
    private fun screenshot(name: String) {
        Thread.sleep(350)
        val image = automation.takeScreenshot() ?: error("Screenshot unavailable")
        val folder = context.getExternalFilesDir("progress-verification")!!
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun start(id: String) {
        context.startForegroundService(Intent(context, PreparationService::class.java).putExtra("slotId", id))
    }
    private fun setup(style: String): DsuSlot {
        assumeTrue("Use isolated progressverify applicationId", context.packageName == "io.github.ultradsu.progressverify")
        context.getSharedPreferences("settings", 0).edit().putBoolean("root_enabled", false).putString("style", style).commit()
        PreparationService.dismissResult()
        return DsuSlot(slotName = "progress_demo", displayName = "进度测试", userdataGiB = 1,
            sourceUri = "content://io.github.ultradsu.progress.fixture/image", sourceName = "system.img").also {
            SlotStore(context).write(listOf(it))
        }
    }
    private fun cleanup(slot: DsuSlot) {
        if (PreparationService.state.value.busy && !PreparationService.state.value.installation) {
            context.startService(Intent(context, PreparationService::class.java).setAction("cancel"))
            await("Preparation did not cancel") { !PreparationService.state.value.busy }
        }
        PreparationService.clearFiles(context, slot.id)
        SlotStore(context).write(emptyList())
        PreparationService.dismissResult()
    }
    @Test fun realPreparationProgressSurvivesEditorAndTabsAndKeepsSuccessAndFailure() {
        val slot = setup("MIUIX")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            click("系统槽"); click("编辑")
            await("Editor missing") { node("编辑 progress_demo") != null }
            start(slot.id)
            await("No real byte progress") { PreparationService.state.value.let { it.busy && it.bytes > 0 && it.totalBytes == 64L * 1024 * 1024 } }
            click("展开任务进度")
            await("Determinate progress missing") { node("当前阶段进度") != null }
            screenshot("miuix-editor-expanded")
            click("收起任务进度")
            await("Bubble did not collapse") { node("展开任务进度") != null }
            Thread.sleep(350)
            val bounds = Rect().also { node("展开任务进度")!!.getBoundsInScreen(it) }
            automation.executeShellCommand("input swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX()} ${bounds.centerY() + 140} 450").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
            }
            await("Bubble disappeared while dragging") { node("展开任务进度") != null }
            val moved = Rect().also { node("展开任务进度")!!.getBoundsInScreen(it) }
            assertTrue("Bubble did not move", moved.top > bounds.top + 50)
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            click("设置")
            await("Overlay disappeared on tab change") { node("展开任务进度") != null }
            screenshot("miuix-settings-collapsed")
            await("Preparation failed or stalled", 30000) { PreparationService.state.value.outcome != null }
            assertEquals(TaskOutcome.SUCCESS, PreparationService.state.value.outcome)
            assertEquals(SlotState.PREPARED, SlotStore(context).read().single().state)
            click("展开任务进度"); screenshot("miuix-completed")
            click("关闭任务结果")
            await("Completed bubble did not dismiss") { node("展开任务进度") == null }
            Thread.sleep(500)
            SlotStore(context).update(slot.id) { it.copy(state = SlotState.DRAFT, sourceUri = "content://io.github.ultradsu.progress.fixture/invalid") }
            start(slot.id)
            await("Invalid image did not fail") { PreparationService.state.value.outcome == TaskOutcome.FAILED }
            click("展开任务进度"); screenshot("miuix-failed")
        } finally {
            cleanup(slot)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    @Test fun materialProgressCanBeCancelledWithoutMarkingTheSlotPrepared() {
        val slot = setup("MD3")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            start(slot.id)
            await("No read progress") { PreparationService.state.value.let { it.busy && it.bytes > 0 } }
            click("展开任务进度"); screenshot("md3-expanded")
            click("取消准备")
            await("Cancel result missing") { PreparationService.state.value.outcome == TaskOutcome.CANCELLED }
            assertEquals(SlotState.DRAFT, SlotStore(context).read().single().state)
            screenshot("md3-cancelled")
        } finally {
            cleanup(slot)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
