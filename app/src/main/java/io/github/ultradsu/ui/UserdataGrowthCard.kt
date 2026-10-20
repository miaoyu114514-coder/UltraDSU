package io.github.ultradsu.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.ultradsu.AppState
import io.github.ultradsu.AppViewModel
import io.github.ultradsu.data.DsuSlot
import io.github.ultradsu.dsu.progressBytes

@Composable internal fun UserdataGrowthCard(model: AppViewModel, state: AppState, slot: DsuSlot,
    enabled: Boolean, confirm: (String, String, () -> Unit) -> Unit, onManageBackups: () -> Unit) {
    val info = state.growthInfo[slot.id]
    val checking = state.growthCheckingSlotId == slot.id
    var capacity by rememberSaveable(slot.id) { mutableStateOf((slot.userdataGiB + 2).toString()) }
    var acknowledgement by rememberSaveable(slot.id) { mutableStateOf("") }
    UCard {
        UText("保留数据扩容 · 实验性", 18, true)
        UText("先完整备份并校验原 userdata，再迁移到更大的镜像并扩展文件系统。备份会占用原分区大小的空间，完成后保留，供核对数据或回退。", 13, color = mutedColor)
        UText("目前保留数据扩容需要 Android 16+、arm64 和已适配的内部多槽布局。此检查只读取兼容性与已有备份状态，不会开始备份或扩容。", 13, color = mutedColor)
        UButton(if (checking) "正在检查…" else "检查兼容性与备份", enabled && !checking, modifier = Modifier.fillMaxWidth()) { model.loadGrowth(slot) }
        // Keep the previous result in place during refresh so the scroll position stays stable.
        info?.let {
            if (it.checkFailed) UText("检查未完成，请确认下方原因后重试。", 15, true)
            if (it.supported) {
                if (it.assisted) UText("客体协作已验证", 15, true, color = accent)
                UText("${it.filesystem.uppercase()} · ${if (it.encrypted) "元数据加密" else "无元数据加密"}\n块设备 ${progressBytes(it.actualBytes)} · 文件系统 ${progressBytes(it.filesystemBytes)}", 13, color = mutedColor)
            } else UText(it.reason.ifBlank { "此环境暂不支持保留数据扩容。" }, 13, color = Color(0xFFC13D42))
            if (!it.checkFailed && !it.exists) UText("未发现本槽的扩容备份。", 13, color = mutedColor)
            if (!it.exists && (it.canAssist || it.assistancePhase != "NONE")) {
                val ready = enabled && !it.checkFailed
                UText("客体协作", 16, true)
                when (it.assistancePhase) {
                    "PREPARED" -> {
                        UText("主系统已准备好。启动这个槽，在客体安装同版本 UltraDSU 并开启 Root，采集协作信息后返回主系统。", 13, color = mutedColor)
                        UButton("启动客体进行协作", ready, modifier = Modifier.fillMaxWidth()) {
                            confirm("启动 ${slot.slotName}", "将重启进入目标槽。请在客体安装同版本 UltraDSU、开启 Root，再在概览页完成协作采集。") { model.bootForGuestAssistance(slot) }
                        }
                    }
                    "CAPTURED" -> UText("已收到加密协作信息。预检通过后即可使用下方保留数据扩容；恢复备份清理前会保留协作凭据。", 13, color = mutedColor)
                    else -> {
                        UText("可让正常运行的客体加密交接离线维护所需信息，再返回主系统备份和扩容。原有槽位密钥不会重建。", 13, color = mutedColor)
                        UButton("准备客体协作", ready, modifier = Modifier.fillMaxWidth()) {
                            confirm("准备客体协作", "需要启动 ${slot.slotName}，在客体安装同版本 UltraDSU 并开启 Root，完成采集后返回主系统。此步骤只准备加密交接，不开始扩容。") { model.prepareGuestAssistance(slot) }
                        }
                    }
                }
                if (it.assistancePhase != "NONE") UButton("清理协作准备", ready, modifier = Modifier.fillMaxWidth()) {
                    confirm("清理协作准备", "将删除本次加密协作信息与临时凭据。槽内数据不变，再次协作需要重新准备。") { model.clearGuestAssistance(slot) }
                }
            }
            if (it.exists) {
                val phase = when (it.phase) {
                    "EXPANDED" -> "扩容完成，等待你核对数据"
                    "ROLLED_BACK" -> "已回退到扩容前状态"
                    "ROLLED_BACK_RAW" -> "原始备份已逐字节恢复，请启动客体核对数据"
                    "BACKUP_PENDING", "BACKUP_VERIFIED" -> "备份阶段中断，原 userdata 未修改"
                    else -> "扩容未完成，该槽已阻止启动，请从备份恢复"
                }
                UText(if (it.checkFailed) "上次确认：$phase（本次未能刷新）" else phase, 15, true)
                UText("原容量 ${progressBytes(it.oldBytes)} → 目标 ${progressBytes(it.newBytes)}\n保留备份 ${progressBytes(it.backupBytes)}", 13, color = mutedColor)
                if (it.canRollback) UButton("从扩容备份回退", enabled, modifier = Modifier.fillMaxWidth()) {
                    confirm("回退 ${slot.slotName} 的 userdata",
                        "将完整恢复扩容前的 userdata。扩容之后新增的文件、设置和应用数据会丢失；system 等分区不变。") { model.rollbackGrowth(slot) }
                }
                UButton("查看与管理备份", enabled, modifier = Modifier.fillMaxWidth(), onClick = onManageBackups)
            } else if (it.supported) {
                UField(capacity, "扩容后的容量 / GiB", enabled, numeric = true) { value -> capacity = value }
                UField(acknowledgement, "输入槽标识 ${slot.slotName} 确认目标", enabled) { value -> acknowledgement = value }
                UButton("保留数据扩容", enabled && acknowledgement == slot.slotName &&
                    capacity.toIntOrNull()?.let { value -> value in 1..512 && value.toLong() * (1L shl 30) > it.actualBytes } == true,
                    primary = true, modifier = Modifier.fillMaxWidth()) {
                    confirm("保留数据扩容 ${slot.slotName}",
                        "将扩容至 $capacity GiB。先备份和校验原始数据，成功后保留备份；发生中断时可从这里恢复。期间请保持供电，并避免用其他工具操作 DSU。") { model.growUserdata(slot, capacity) }
                }
            }
        }
    }
}
