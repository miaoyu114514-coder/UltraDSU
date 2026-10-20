package io.github.ultradsu.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ultradsu.AppState
import io.github.ultradsu.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun DebugSettings(model: AppViewModel, state: AppState, locked: Boolean) {
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let(model::exportDebugLog)
    }
    UCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                UText("调试模式", 17, true)
                UText("记录操作阶段、进度与错误，开关重启后保留。关闭后停止记录，已有日志仍可导出或清空。", 13, color = mutedColor)
            }
            Box(Modifier.semantics { contentDescription = "调试模式开关" }) {
                USwitch(state.debugMode, !state.diagnosticsBusy, model::debugMode)
            }
        }
        UText("日志仅保存在应用内，最多 1 MiB；默认脱敏路径、已知槽名和镜像名。不会自动上传或读取加密密钥，分享前请检查内容。", 13, color = mutedColor)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UButton(if (state.diagnosticsBusy) "正在处理日志…" else "导出日志", !state.diagnosticsBusy,
                modifier = Modifier.weight(1f)) {
                export.launch("UltraDSU-debug-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt")
            }
            UButton("清空日志", !state.diagnosticsBusy, modifier = Modifier.weight(1f), onClick = model::clearDebugLog)
        }
        if (state.debugMode && state.root) {
            UButton("采集 DSU 系统诊断", !locked && !state.diagnosticsBusy, onClick = model::collectDsuDiagnostics)
            UText("手动采集加密状态及 gsid / vold / fs_mgr 的近期日志。主系统缓冲区可能没有客体系统首次启动的报错。", 13, color = mutedColor)
        }
    }
}
