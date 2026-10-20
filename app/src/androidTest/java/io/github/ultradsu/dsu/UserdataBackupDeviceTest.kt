package io.github.ultradsu.dsu

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.system.Os
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ultradsu.MainActivity
import io.github.ultradsu.data.UiStyle
import io.github.ultradsu.data.UserdataBackupEntry
import io.github.ultradsu.ui.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic files and an isolated app only. Never invokes su or accesses real slots. */
@RunWith(AndroidJUnit4::class)
class UserdataBackupDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private fun isolated() = assumeTrue("Use isolated test package", context.packageName == "io.github.ultradsu.progressverify")
    private fun await(predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + 12000
        while (System.currentTimeMillis() < until) { if (predicate()) return; Thread.sleep(100) }
        fail("Timed out waiting for backup manager UI")
    }
    private fun node(text: String): AccessibilityNodeInfo? {
        fun search(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (current.text?.toString() == text || current.contentDescription?.toString() == text) return current
            repeat(current.childCount) { current.getChild(it)?.let { child -> search(child)?.let { return it } } }
            return null
        }
        return automation.rootInActiveWindow?.let(::search)
    }
    private fun disabled(text: String): Boolean {
        var current = node(text)
        while (current != null) { if (!current.isEnabled) return true; current = current.parent }
        return false
    }
    private fun scrollTo(text: String) {
        repeat(6) {
            val found = node(text)
            if (found != null) {
                val bounds = Rect().also(found::getBoundsInScreen)
                if (bounds.height() > 0 && bounds.top > 100 && bounds.bottom < automation.takeScreenshot()!!.let { image -> image.height.also { image.recycle() } } - 100) return
            }
            automation.executeShellCommand("input swipe 500 1750 500 650 300").use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            Thread.sleep(350)
        }
        fail("Cannot scroll to $text")
    }
    @Test fun androidSymlinksAndIndependentCleanup() {
        isolated()
        val work = File(context.cacheDir,"userdata-manager-${UUID.randomUUID()}").canonicalFile.apply { mkdir() }
        val live = File(work,"userdata_gsi.img.0000").apply { writeBytes(byteArrayOf(7,8,9)) }
        val root = File(work,"growth").apply { mkdir() }
        val dir = File(root,"alpha").apply { mkdir() }
        val raw = File(dir,"userdata.raw")
        val journal = File(dir,"growth.json")
        val store = UserdataBackupStore(root,listOf(live)) { NativeGrowthTools.syncDirectory(it) }
        try {
            journal.writeText(JSONObject().put("version",1).put("phase","EXPANDED").put("oldBytes",4096).put("newBytes",8192).toString())
            Os.symlink(live.absolutePath,raw.absolutePath)
            assertFalse(store.inspect("alpha").getBoolean("canDelete")); assertTrue(raw.delete())
            raw.writeBytes(ByteArray(4096))
            val row = store.inspect("alpha"); assertTrue(row.getBoolean("canDelete"))
            store.delete("alpha","growth",row.getString("revision"))
            assertFalse(dir.exists()); assertArrayEquals(byteArrayOf(7,8,9),live.readBytes())
        } finally {
            // Fixed app-cache paths, including unlink of the possible symlink itself.
            raw.delete(); journal.delete(); dir.delete(); root.delete(); live.delete(); work.delete()
        }
    }
    @Test fun bothStylesProtectCurrentAndDisableStaleBackupActions() {
        isolated()
        context.getSharedPreferences("settings",0).edit().putBoolean("root_enabled",false).commit()
        val rows = listOf(
            UserdataBackupEntry("alpha","current",18L shl 30,reason="槽当前绑定的 userdata，不可删除"),
            UserdataBackupEntry("alpha","growth",16L shl 30,canDelete=true,reason="独立备份，未作为当前 userdata 使用",revision="1".repeat(64)),
        )
        for (style in UiStyle.entries) {
            val activity = instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val error = mutableStateOf<String?>(null)
            var deleteCalls = 0
            instrumentation.runOnMainSync {
                activity.setContent {
                    UltraTheme(style) {
                        Column(Modifier.fillMaxSize().statusBarsPadding()) {
                            UText("userdata 备份管理")
                            UserdataBackupsContent(rows,true,true,error.value,null,onRefresh={},onDelete={ deleteCalls++ })
                        }
                    }
                }
            }
            try {
                await { node("当前分区不可删除") != null }
                assertTrue(disabled("当前分区不可删除"))
                scrollTo("删除备份"); assertFalse(disabled("删除备份"))
                Thread.sleep(500)
                val screenshots = File(context.getExternalFilesDir(null),"userdata-backups").apply { mkdirs() }
                val bitmap = automation.takeScreenshot()!!
                File(screenshots,"$style.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                val bounds = Rect().also { node("删除备份")!!.getBoundsInScreen(it) }
                automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
                await { deleteCalls == 1 }
                instrumentation.runOnMainSync { error.value = "备份状态已变化，请刷新后重新选择" }
                Thread.sleep(350); scrollTo("删除备份")
                await { disabled("删除备份") }
                assertEquals(1,deleteCalls)
            } catch (failure: Throwable) {
                automation.takeScreenshot()?.let { bitmap ->
                    val screenshots = File(context.getExternalFilesDir(null),"userdata-backups").apply { mkdirs() }
                    File(screenshots,"failure-$style.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                }
                throw failure
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }
}
