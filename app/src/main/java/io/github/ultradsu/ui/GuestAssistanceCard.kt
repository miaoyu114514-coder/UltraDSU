package io.github.ultradsu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.ultradsu.AppState
import io.github.ultradsu.AppViewModel

@Composable internal fun GuestAssistanceCard(model: AppViewModel, state: AppState, enabled: Boolean,
    confirm: (String, String, () -> Unit) -> Unit) {
    val info = state.guestAssistance ?: return
    UCard {
        UText("客体协作扩容", 19, true)
        UText("当前客体：${info.slot}", 14, color = mutedColor)
        if (info.phase == "CAPTURED") {
            UText("协作信息已加密保存，可返回主系统检查本槽的扩容与备份状态。已完成扩容时，请先核对客体中的文件与应用。", 14, color = mutedColor)
        } else {
            UText("主系统正在等待这个槽的协作信息。采集只交接加密的维护信息，不修改原有密钥或扩容 userdata。", 14, color = mutedColor)
            UButton("加密采集协作信息", enabled, primary = true, modifier = Modifier.fillMaxWidth()) { model.captureGuestAssistance() }
        }
        UButton("返回主系统", enabled, modifier = Modifier.fillMaxWidth()) {
            confirm("返回主系统", if (info.phase == "CAPTURED") "将重启返回主系统，之后可继续本槽的扩容预检。" else "尚未完成采集。仍要重启返回主系统吗？") { model.returnFromGuestAssistance() }
        }
    }
}
