package io.github.ultradsu.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.ultradsu.AppState
import io.github.ultradsu.AppViewModel
import io.github.ultradsu.data.*

/** A full secondary page. Basic settings remain available without Root. */
@Composable internal fun SlotEditorScreen(
    model: AppViewModel, state: AppState, slot: DsuSlot, locked: Boolean,
    onBack: () -> Unit, backEnabled: Boolean, onManageBackups: () -> Unit,
    readReady: Boolean, confirm: (String, String, () -> Unit) -> Unit,
) {
    var label by rememberSaveable(slot.id) { mutableStateOf(slot.displayName) }
    var size by rememberSaveable(slot.id) { mutableStateOf(slot.userdataGiB.toString()) }
    var feedback by rememberSaveable(slot.id) { mutableStateOf<String?>(null) }
    var target by rememberSaveable(slot.id) { mutableStateOf("system") }
    var capacity by rememberSaveable(slot.id) { mutableStateOf((slot.userdataGiB.takeIf { it > 0 }?.plus(8) ?: 16).toString()) }
    var acknowledgement by rememberSaveable(slot.id) { mutableStateOf("") }
    var autoReadRequested by remember(slot.id) { mutableStateOf(false) }
    val installed = slot.state in listOf(SlotState.INSTALLED, SlotState.INCOMPLETE)
    val showPartitions = state.root && state.device.multiSlot && installed
    val canManage = showPartitions && !state.device.running && !state.device.installing
    val inventory = state.inventories[slot.id]
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.stageImages(slot, uri, model.sourceName(uri), target)
    }
    var exportTarget by rememberSaveable(slot.id) { mutableStateOf("") }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && exportTarget.isNotBlank()) model.exportPartition(slot, exportTarget, uri)
    }
    BackHandler(enabled = backEnabled, onBack = onBack)
    LaunchedEffect(slot.id, canManage, locked, readReady) {
        if (readReady && canManage && !locked && !autoReadRequested) {
            autoReadRequested = true
            model.loadImages(slot)
        }
    }

    Column(Modifier.fillMaxSize().background(pageColor).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = backEnabled) {
                Icon(Icons.Outlined.ArrowBack, "返回系统槽", tint = inkColor)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                UText("编辑 ${slot.slotName}", 21, true)
                UText(slot.displayName, 13, color = mutedColor)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 22.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.message?.let { message -> UCard {
                    UText(message, 13, color = mutedColor)
                    UButton("知道了", onClick = model::dismissMessage)
                } }
                UCard {
                    UText("基本设置", 18, true)
                    UText("槽标识：${slot.slotName} · ${slot.state.label}", 13, color = mutedColor)
                    UField(label, "显示名称", !locked) { label = it; feedback = null }
                    if (slot.state == SlotState.DRAFT) {
                        UField(size, "userdata 大小 / GiB", !locked, numeric = true) { size = it; feedback = null }
                    } else {
                        UText(if (slot.userdataGiB > 0) "userdata：${slot.userdataGiB} GiB" else "userdata：容量未知", 13, color = mutedColor)
                    }
                    feedback?.let { UText(it, 13, color = accent) }
                    UButton("保存基本设置", !locked, primary = true, modifier = Modifier.fillMaxWidth()) {
                        feedback = if (model.edit(slot, label, size)) "已保存" else model.state.value.message ?: "无法保存配置"
                    }
                }
                if (slot.sourceName.isNotBlank() || slot.images.isNotEmpty()) UCard {
                    UText("镜像信息", 18, true)
                    if (slot.sourceName.isNotBlank()) UText(slot.sourceName, 13, color = mutedColor)
                    if (slot.preparedFilesCleaned) UText("安装后的准备文件已自动清理，槽内分区保留。", 12, color = mutedColor)
                    slot.images.forEach { image -> UText("${image.partition} · ${"%.2f".format(image.bytes.toDouble() / GIB)} GiB", 13, color = mutedColor) }
                }
                if (showPartitions) {
                    if (!canManage) UCard {
                        UText("槽内镜像", 18, true)
                        UText(if (state.device.running) "请先返回主系统，再管理分区镜像。" else "系统正在安装 DSU，请等待完成后管理分区镜像。", 13, color = mutedColor)
                    }
                    UCard {
                        UText("槽内镜像", 18, true)
                        UText("提取为完整 raw .img，保存位置需要足够空间；userdata 导出的是原始加密镜像，不能直接读取其中的文件。", 12, color = mutedColor)
                        if (inventory?.pending?.isNotEmpty() == true) {
                            UText("待修复：${inventory.pending.joinToString()}。已阻止此槽启动，请重新写入对应分区；其他分区保留。", 13, color = Color(0xFFC13D42))
                        }
                        if (inventory == null) UText("读取后可查看实际分区和容量。", 13, color = mutedColor)
                        inventory?.partitions?.forEach { image ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    UText(image.name, 15, true)
                                    UText(if (image.bytes > 0) "${"%.2f".format(image.bytes.toDouble() / GIB)} GiB" else "容量未知", 12, color = mutedColor)
                                    if (image.mapped) UText("当前仍映射，暂不可替换", 12, color = Color(0xFFC13D42))
                                }
                                if (image.name in PartitionRules.readOnly) UButton("替换", !locked && canManage && !image.mapped) {
                                    target = image.name; picker.launch(arrayOf("*/*"))
                                }
                                if (image.name in PartitionRules.readOnly || image.name == "userdata") UButton("提取",
                                    !locked && canManage && !image.mapped && image.bytes > 0 && image.name !in inventory.pending) {
                                    exportTarget = image.name
                                    exportPicker.launch("${slot.slotName}-${image.name}.img")
                                }
                            }
                        }
                        UButton("刷新分区", !locked && canManage, modifier = Modifier.fillMaxWidth()) { model.loadImages(slot) }
                    }
                    UCard {
                        UText("导入替换镜像", 18, true)
                        UText("单镜像默认替换 system；点分区旁的“替换”可指定其他目标。多镜像包按文件名分区，可同时替换多个分区。", 12, color = mutedColor)
                        UButton("导入镜像 / ZIP / 7z", !locked && canManage, modifier = Modifier.fillMaxWidth()) {
                            target = "system"; picker.launch(arrayOf("*/*"))
                        }
                        if (slot.pendingImages.isNotEmpty()) {
                            UText("待写入清单", 16, true, accent)
                            slot.pendingImages.forEach { image ->
                                UText("${image.partition} ← ${image.sourceName}\n${"%.2f".format(image.bytes.toDouble() / GIB)} GiB", 13)
                            }
                            UButton("确认分区并写入", !locked && canManage, primary = true, modifier = Modifier.fillMaxWidth()) {
                                confirm("写入 ${slot.slotName} 的选定分区",
                                    "将替换或添加：${slot.pendingImages.joinToString { it.partition }}\nuserdata 和其余分区保留。所选分区会重建；失败时该槽需要修复后才能启动。") { model.applyImages(slot) }
                            }
                        }
                    }
                    UCard {
                        UText("userdata 备份管理", 18, true)
                        UText("在用 userdata 始终保留；可单独清理已完成扩容后留下的备份。", 13, color = mutedColor)
                        UButton("管理 userdata 备份", !locked, modifier = Modifier.fillMaxWidth(), onClick = onManageBackups)
                    }
                    UserdataGrowthCard(model, state, slot, !locked && !state.device.running, confirm, onManageBackups)
                    UCard {
                        UText("重建 userdata 扩容", 18, true)
                        UText("此操作清除 ${slot.slotName} 槽的全部应用、账号、文件和设置。system 等镜像保留。这是重建，不是无损扩容。", 13, color = Color(0xFFC13D42))
                        UField(capacity, "新容量 / GiB（必须大于现有容量）", !locked && canManage, numeric = true) { capacity = it }
                        UField(acknowledgement, "输入槽标识 ${slot.slotName} 确认清除数据", !locked && canManage) { acknowledgement = it }
                        UButton("重建 userdata", !locked && canManage && acknowledgement == slot.slotName && capacity.toIntOrNull() in 1..512,
                            modifier = Modifier.fillMaxWidth()) {
                            confirm("清空并重建 ${slot.slotName} 的 userdata",
                                "将删除该槽原 userdata，并创建 ${capacity} GiB 新分区。原数据不可恢复，其他分区与其他槽保留。") { model.rebuildUserdata(slot, capacity) }
                        }
                    }
                }
            }
        }
    }
}
