package io.github.ultradsu.dsu

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ultradsu.MainActivity
import io.github.ultradsu.data.UiStyle
import io.github.ultradsu.ui.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Renders synthetic progress only; clicking cancel does not call Root or any service. */
class InstallExportUiDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.uiAutomation
    private fun node(text: String): AccessibilityNodeInfo? {
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if(current.text?.toString()==text || current.contentDescription?.toString()==text)return current
            repeat(current.childCount){current.getChild(it)?.let{child->find(child)?.let{return it}}}
            return null
        }
        return automation.rootInActiveWindow?.let(::find)
    }
    private fun await(text: String) {
        val deadline=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<deadline){if(node(text)!=null)return;Thread.sleep(50)}
        fail("Missing UI: $text")
    }
    private fun click(text: String) {
        await(text)
        var current=node(text)
        while(current!=null){if(current.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;current=current.parent}
        val bounds=Rect().also{node(text)!!.getBoundsInScreen(it)}
        automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
    }
    @Test fun bothStylesShowPartitionInCollapsedAndExpandedProgressAndAcknowledgeCancel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ULTRADSU_INSTALL_TEST")=="MOTO_EXPLICIT")
        val context=instrumentation.targetContext
        val output=File(context.getExternalFilesDir(null),"install-export-verification").apply{mkdirs()}
        for(style in UiStyle.entries){
            val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val progress=mutableStateOf(PreparationState(busy=true,id="synthetic",bytes=25,totalBytes=100,message="写入 product",installation=true,cancellable=true,title="安装系统槽",taskId="ui-$style"))
            var cancelled=0
            instrumentation.runOnMainSync{
                activity.setContent{UltraTheme(style){Box(Modifier.fillMaxSize()){
                    OperationBubble(progress.value,null,onCancel={cancelled++;progress.value=progress.value.copy(cancelling=true)},onDismiss={})
                }}}
            }
            try{
                await("写入 product");await("25%")
                automation.takeScreenshot()?.let{image->File(output,"$style-collapsed.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
                click("展开任务进度");await("写入 product");await("取消安装")
                automation.takeScreenshot()?.let{image->File(output,"$style-expanded.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
                click("取消安装");await("正在取消…");assertEquals(1,cancelled)
                var current=node("正在取消…");var disabled=false
                while(current!=null){if(!current.isEnabled)disabled=true;current=current.parent}
                assertTrue(disabled)
            } finally {instrumentation.runOnMainSync{activity.finish()}}
        }
    }
}
