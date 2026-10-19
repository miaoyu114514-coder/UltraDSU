package io.github.ultradsu.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ultradsu.AppState
import io.github.ultradsu.AppViewModel
import io.github.ultradsu.BuildConfig
import io.github.ultradsu.data.*
import io.github.ultradsu.dsu.PreparationState
import top.yukonga.miuix.kmp.basic.NavigationBar as MiNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem as MiNavigationItem
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost

private data class Confirmation(val title: String, val text: String, val action: () -> Unit)

@Composable fun UltraDsuApp(model: AppViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val prep by model.preparation.collectAsStateWithLifecycle()
    UltraTheme(state.style) {
        Box(Modifier.fillMaxSize()) {
            AppBody(model, state, prep)
            // SuperDialog registers content with this host. Keep it above the page
            // and glass layer so modal content is visible and receives input.
            if (state.style == UiStyle.MIUIX) MiuixPopupHost()
        }
    }
}

@Composable private fun AppBody(model: AppViewModel, state: AppState, prep: PreparationState) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var create by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var commandSlot by remember { mutableStateOf<DsuSlot?>(null) }
    var bootSlot by remember { mutableStateOf<DsuSlot?>(null) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var notificationSlot by remember { mutableStateOf<DsuSlot?>(null) }
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationSlot?.let(model::prepare); notificationSlot = null
    }
    val prepare: (DsuSlot) -> Unit = { slot ->
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationSlot = slot; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else model.prepare(slot)
    }
    val locked = state.busy || prep.busy
    val canInstall = state.device.installerAvailable || (state.root && state.device.verified && state.device.multiSlot)
    val labels = listOf("概览", "系统槽", "设置")
    val icons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.Layers, Icons.Outlined.Settings)
    val backdrop = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    val editingSlot = state.slots.find { it.id == editingId }
    val screenState = rememberSaveableStateHolder()
    val tabState = rememberSaveableStateHolder()

    AnimatedContent(targetState = editingSlot?.id,
        modifier = Modifier.fillMaxSize().background(pageColor).clipToBounds(),
        transitionSpec = { slotScreenTransition() }, label = "slot screen") { screenId ->
    val activeScreen = screenId == editingSlot?.id
    val screenSettled = transition.currentState == EnterExitState.Visible && transition.targetState == EnterExitState.Visible
    if (screenId != null) {
        state.slots.find { it.id == screenId }?.let { slot ->
            Box(Modifier.fillMaxSize().transitionInput(activeScreen)) {
                SlotEditorScreen(model, state, slot, locked || !activeScreen, prep,
                    onBack = { editingId = null }, backEnabled = activeScreen && confirmation == null,
                    readReady = screenSettled && activeScreen) { title, text, action ->
                    confirmation = Confirmation(title, text, action)
                }
            }
        }
    } else {
    screenState.SaveableStateProvider("main") {
    Box(Modifier.fillMaxSize().transitionInput(activeScreen).background(pageColor).statusBarsPadding().imePadding()) {
    Column(Modifier.fillMaxSize().onGloballyPositioned { backdropOrigin = it.positionInRoot() }.drawWithContent {
        if (state.glassNavigation) {
            backdrop.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop)
        } else drawContent()
    }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
            UText("UltraDSU", 20, true, modifier = Modifier.weight(1f))
            Tag(if (state.root) "ROOT" else "ADB", if (state.root) Color(0xFF25977B) else accent)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(targetState = tab, modifier = Modifier.fillMaxSize().clipToBounds(),
                contentAlignment = Alignment.TopCenter,
                transitionSpec = { tabContentTransition() }, label = "tab content") { page ->
            tabState.SaveableStateProvider(page) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize().transitionInput(page == tab),
                state = rememberLazyListState(),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp,
                    bottom = if (state.glassNavigation) 112.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item(key = "header") {
                    Column(Modifier.padding(top = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        UText(labels[page], if (state.style == UiStyle.MIUIX) 34 else 30, true)
                        UText(when (page) { 0 -> "让每个系统，都有自己的空间"; 1 -> "独立配置，随时探索"; else -> "按你的习惯，定义 UltraDSU" }, 14, color = mutedColor)
                    }
                }
                if (state.message != null) item(key = "message") {
                    UCard {
                        UText(state.message, 14)
                        UButton("知道了", onClick = model::dismissMessage)
                    }
                }
                if (locked) item(key = "operation") {
                    UCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(22.dp), color = accent, strokeWidth = 2.dp)
                            UText(if (prep.busy) prep.message else "正在执行操作…", 14)
                        }
                        if (prep.busy && !prep.installation) UButton("取消准备", onClick = model::cancelPreparation)
                    }
                }
                if (page == 0) {
                    item { Hero(state, onCreate = { create = true }, enabled = !locked && canInstall) }
                    item { Compatibility(state) }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            UCard(Modifier.weight(1f)) { UText("${state.slots.size}", 30, true, accent); UText("槽位配置", 13, color = mutedColor) }
                            UCard(Modifier.weight(1f)) { UText("${state.slots.count { it.state == SlotState.INSTALLED }}", 30, true, accent); UText("已核实安装", 13, color = mutedColor) }
                        }
                    }
                    item {
                        UCard {
                            UText("开始一次系统探索", 18, true)
                            UText("01   导入与你的设备兼容的 GSI\n02   设置槽标识和 userdata 空间\n03   交给系统安装，完成后选择启动", 14, color = mutedColor)
                            UButton("管理系统槽", onClick = { tab = 1 })
                        }
                    }
                } else if (page == 1) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            UButton("＋ 新建系统槽", !locked && canInstall, true, Modifier.weight(1f)) { create = true }
                            UButton("刷新", !locked, onClick = model::refresh)
                        }
                    }
                    if (!state.device.multiSlot || !state.device.internalMultiSlot || !state.device.installerAvailable || state.device.detail.isNotEmpty()) item { Compatibility(state) }
                    if (!state.root) item { UText("当前显示本地配置。安装结果请查看系统通知；在设置中开启 Root 后可扫描系统槽位。", 13, color = mutedColor) }
                    if (state.slots.isEmpty()) item {
                        UCard {
                            Icon(Icons.Outlined.Inventory2, null, tint = accent, modifier = Modifier.size(40.dp))
                            UText("这里将放下你的下一个系统", 19, true)
                            UText("点击新建，选择 GSI 镜像或 ZIP / 7z 多分区包开始。", 14, color = mutedColor)
                        }
                    }
                    items(state.slots, key = { it.id }) { slot ->
                        SlotCard(slot, state, locked, onEdit = { editingId = slot.id }, onPrepare = { prepare(slot) },
                            onInstall = {
                                if (state.root) confirmation = Confirmation("安装 ${slot.displayName}",
                                    "槽标识：${slot.slotName}\nuserdata：${slot.userdataGiB} GiB\n" + if (state.device.multiSlot)
                                        "将直接创建独立槽并写入镜像，保留其他槽。安装过程请查看本应用通知，不会自动重启。"
                                    else "将打开系统安装器；它会清理当前 DSU，确认前请备份需要的数据。") { model.install(slot) }
                                else commandSlot = slot
                            }, onBoot = { bootSlot = slot }, onDelete = {
                                confirmation = Confirmation("删除 ${slot.displayName}",
                                    if (slot.state in listOf(SlotState.DRAFT, SlotState.PREPARED)) "将删除本地配置与准备文件。"
                                    else "将先核实系统槽状态；如已安装，将删除此槽及其 userdata。此操作无法撤销。") { model.delete(slot) }
                            })
                    }
                    if (state.root && state.device.enabled) item {
                        UCard {
                            UText("下次启动：${state.device.activeSlot}", 17, true)
                            UButton("停用 DSU", !locked) {
                                confirmation = Confirmation("返回主系统", "停用 DSU 后，下次重启进入主系统。已安装槽位会保留。") { model.returnToHost(false) }
                            }
                            UButton("停用并重启", !locked) {
                                confirmation = Confirmation("停用 DSU 并立即重启", "设备将立即重启到主系统，请先保存其他应用中的工作。") { model.returnToHost(true) }
                            }
                        }
                    }
                } else {
                    item {
                        UText("外观", 13, true, mutedColor)
                        Spacer(Modifier.height(8.dp))
                        UCard {
                            UText("界面风格", 18, true)
                            UText("切换立即生效，跟随系统深浅色。", 13, color = mutedColor)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                UiStyle.entries.forEach { style ->
                                    UButton((if (state.style == style) "✓  " else "") + if (style == UiStyle.MIUIX) "miuix" else "Material 3",
                                        primary = state.style == style, modifier = Modifier.weight(1f)) { model.style(style) }
                                }
                            }
                            HorizontalDivider(color = mutedColor.copy(alpha = .15f))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f)) {
                                    UText("液态玻璃底栏", 17, true)
                                    UText("悬浮玻璃质感与流动选中效果，两种界面风格均可使用。", 13, color = mutedColor)
                                }
                                USwitch(state.glassNavigation, onChange = model::glassNavigation)
                            }
                        }
                    }
                    item {
                        UText("权限与能力", 13, true, mutedColor)
                        Spacer(Modifier.height(8.dp))
                        UCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(1f)) {
                                    UText("开启 Root 功能", 17, true)
                                    UText("开关会持久保存，重启后自动检查授权；授权成功后显示槽位管理操作。", 13, color = mutedColor)
                                }
                                USwitch(state.rootRequested, !locked, model::rootEnabled)
                            }
                            if (state.root) UText("Root 已授权 · 所有系统管理操作仍会校验槽位状态。", 13, color = Color(0xFF25977B))
                            else if (state.rootRequested) UButton("重新检查 Root 授权", !locked) { model.rootEnabled(true) }
                        }
                    }
                    item { Compatibility(state) }
                    item {
                        UCard {
                            UText("关于 UltraDSU", 18, true)
                            UText("${BuildConfig.VERSION_NAME} · Android 10+\n基于 Android Dynamic System Updates\nmiuix / Material Design 3", 14, color = mutedColor)
                            UText("仅支持与设备兼容的 GSI。启动还取决于动态分区、厂商实现和 AVB；第三方 GSI 通常需要已解锁的 Bootloader。", 13, color = mutedColor)
                            UText("功能流程参考 DSU Sideloader（Apache-2.0），未包含它的定制 gsid 或系统模块。", 13, color = mutedColor)
                        }
                    }
                    item { UText("操作记录", 13, true, mutedColor) }
                    item {
                        UCard {
                            SelectionContainer { UText(state.log.ifEmpty { listOf("尚无操作记录") }.joinToString("\n\n"), 12, color = mutedColor) }
                        }
                    }
                }
            }
            }
            }
        }
        if (!state.glassNavigation) {
            if (state.style == UiStyle.MIUIX) {
                MiNavigationBar(items = labels.mapIndexed { index, label -> MiNavigationItem(label, icons[index]) },
                    selected = tab, onClick = { tab = it })
            } else NavigationBar {
                labels.forEachIndexed { index, label -> NavigationBarItem(tab == index, { tab = index },
                    icon = { Icon(icons[index], null) }, label = { Text(label) }) }
            }
        }
    }
    if (state.glassNavigation) LiquidGlassNavigationBar(labels, icons, tab, backdrop, backdropOrigin,
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)) { tab = it }
    }
    }
    }
    }
    if (create) CreateDialog(model, state, dismiss = { create = false })
    commandSlot?.let { slot -> AdbDialog(model, slot) { commandSlot = null } }
    bootSlot?.let { slot -> BootDialog(slot, dismiss = { bootSlot = null }) { sticky, reboot ->
        bootSlot = null
        confirmation = Confirmation(if (reboot) "立即重启到 ${slot.displayName}" else "选择 ${slot.displayName}",
            if (sticky) "此槽将在每次重启时持续启动。返回主系统前需停用 DSU。${if (reboot) "\n设备将立即重启，请先保存工作。" else ""}"
            else "下次重启进入此槽，仅启动一次。${if (reboot) "\n设备将立即重启，请先保存工作。" else ""}") { model.enable(slot, sticky, reboot) }
    } }
    confirmation?.let { item ->
        UDialog(item.title, { confirmation = null }) {
            UText(item.text, 14, color = mutedColor)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                UButton("取消", modifier = Modifier.weight(1f)) { confirmation = null }
                UButton("确认", primary = true, modifier = Modifier.weight(1f)) { confirmation = null; item.action() }
            }
        }
    }
}

@Composable private fun Hero(state: AppState, onCreate: () -> Unit, enabled: Boolean) {
    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF3267F5), Color(0xFF6555DC))),
        RoundedCornerShape(28.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp)); UText("DYNAMIC SYSTEM", 12, true, Color.White.copy(alpha = .8f))
        }
        UText(if (state.device.running) "正在体验动态系统" else "一个设备，更多可能", 25, true, Color.White)
        UText(if (state.device.activeSlot.isNotEmpty()) "当前选择  /  ${state.device.activeSlot}"
            else "保留主系统，为新的 Android 开辟独立空间。", 14, color = Color.White.copy(alpha = .85f))
        UButton("＋ 添加一个系统", enabled, primary = true, onClick = onCreate)
    }
}

@Composable private fun Compatibility(state: AppState) {
    val d = state.device
    UCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            UText("设备兼容性", 17, true, modifier = Modifier.weight(1f))
            Tag("API ${d.sdk}")
        }
        UText("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", 13, color = mutedColor)
        val title = when {
            d.detail.isNotEmpty() -> "已核验内部多槽补丁"
            d.multiSlot && !d.internalMultiSlot -> "命名接口可用 · 内部存储仅默认槽"
            !d.installerAvailable && state.root && d.multiSlot && d.verified -> "Root 内置安装可用 · 缺少系统安装器"
            !d.installerAvailable -> "系统 DSU 安装器不可用"
            !d.multiSlot -> "当前系统仅支持单槽"
            d.verified -> "已检测到命名多槽接口"
            else -> "系统版本支持多槽 · 尚未核实厂商实现"
        }
        UText(title, 15, true, if (!d.multiSlot || !d.internalMultiSlot || !d.installerAvailable) Color(0xFFB57420) else accent)
        UText(when {
            d.detail.isNotEmpty() -> d.detail
            d.multiSlot && !d.internalMultiSlot -> "Android 11 起有命名槽接口，但 AOSP 到 Android 15 才放开内部多槽安装路径。本应用在此版本仅允许内部默认槽 dsu；现有槽管理和镜像准备可用，不能靠 Root 权限直接解除限制。"
            !d.installerAvailable -> "此 ROM 未提供或停用了 com.android.dynsystem。可开启 Root 核实命名多槽接口，使用内置安装。"
            d.sdk == 29 -> "Android 10 的 DSU 仅支持默认槽 dsu。可设置显示名称及 userdata 大小；Android 11 起才有命名多槽机制。"
            !d.multiSlot -> "厂商未提供可用的命名多槽接口，已禁用多槽创建。"
            else -> "AOSP 从 Android 11（API 30）引入命名多槽。需要动态分区和厂商支持；开启 Root 后可进一步核实接口与已安装槽位。"
        }, 13, color = mutedColor)
        if (d.running) Tag("当前运行在 DSU 中，安装和删除已限制", Color(0xFFB57420))
        if (d.installing) Tag("系统正在安装 DSU，请等待完成", Color(0xFFB57420))
    }
}

@Composable private fun SlotCard(slot: DsuSlot, state: AppState, locked: Boolean,
    onEdit: () -> Unit, onPrepare: () -> Unit, onInstall: () -> Unit, onBoot: () -> Unit, onDelete: () -> Unit) {
    val installed = slot.state == SlotState.INSTALLED
    val safeLocal = slot.state in listOf(SlotState.DRAFT, SlotState.PREPARED)
    UCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).background(accent.copy(alpha = .1f), RoundedCornerShape(16.dp)), Alignment.Center) {
                Icon(Icons.Outlined.Android, null, tint = accent, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f)) {
                UText(slot.displayName, 20, true)
                UText("槽  /  ${slot.slotName}", 12, color = mutedColor)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(slot.state.label)
            if (state.root && state.device.activeSlot == slot.slotName) Tag(if (state.device.running) "正在运行" else "当前选择", Color(0xFF25977B))
            Tag(if (slot.userdataGiB > 0) "userdata  ${slot.userdataGiB} GiB" else "userdata  未知")
        }
        if (slot.sourceName.isNotBlank()) UText(slot.sourceName, 13, color = mutedColor)
        if (slot.systemBytes > 0) UText("system  ${"%.2f".format(slot.systemBytes.toDouble() / GIB)} GiB", 13, color = mutedColor)
        if (slot.images.size > 1 && !installed) UText("分区清单：" + slot.images.joinToString { "${it.partition} (${"%.2f".format(it.bytes.toDouble() / GIB)} GiB)" }, 13, color = mutedColor)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (slot.state == SlotState.DRAFT) UButton("准备镜像", !locked && !state.device.running && !state.device.installing, true, onClick = onPrepare)
            if (slot.state == SlotState.PREPARED || slot.state == SlotState.SUBMITTED) UButton(
                if (!state.device.internalMultiSlot && slot.slotName != "dsu") "不支持内部命名槽" else if (state.root && state.device.multiSlot) "安装到此槽" else if (state.root) "系统安装" else "ADB 安装",
                !locked && (state.device.internalMultiSlot || slot.slotName == "dsu") && (state.device.installerAvailable || (state.root && state.device.multiSlot)) && !state.device.running && !state.device.installing,
                true, onClick = onInstall)
            if (state.root && installed) UButton("启动", !locked && !state.device.running && !state.device.installing, true, onClick = onBoot)
            UButton("编辑", !locked, onClick = onEdit)
            if (safeLocal || state.root) UButton("删除", !locked && (!state.device.running || safeLocal) && !state.device.installing, onClick = onDelete)
        }
    }
}

@Composable private fun CreateDialog(model: AppViewModel, state: AppState, dismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(if (!state.device.multiSlot) "dsu" else
        generateSequence(1) { it + 1 }.map { "slot_$it" }.first { n -> state.slots.none { it.slotName == n } }) }
    var label by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf("8") }
    var uri by rememberSaveable { mutableStateOf("") }
    var filename by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { result ->
        if (result != null) { uri = result.toString(); filename = model.sourceName(result) }
    }
    UDialog("添加系统槽", dismiss) {
        UText("选择 GSI，配置独立的系统与数据空间。", 14, color = mutedColor)
        UButton(if (filename.isEmpty()) "选择 GSI 镜像" else filename, primary = true) { picker.launch(arrayOf("*/*")) }
        UText("支持 IMG、ZIP、7z、TAR、GZ、XZ、BZ2；镜像须为 raw ext4 / EROFS。", 12, color = mutedColor)
        UText("单镜像默认写入 system；多镜像按 system.img、product.img 等分区名处理。", 12, color = mutedColor)
        UField(name, "DSU 槽标识", enabled = state.device.multiSlot) { name = it }
        UField(label, "显示名称（可选）") { label = it }
        UField(size, "userdata 大小 / GiB", numeric = true) { size = it }
        UText("槽标识安装后固定；显示名称可修改。安装后可在槽内镜像管理中重建 userdata 扩容，该操作清空槽内用户数据。", 12, color = mutedColor)
        if (!state.device.multiSlot) UText("此系统只支持默认槽 dsu。", 13, color = Color(0xFFB57420))
        error?.let { UText(it, 13, color = Color(0xFFC13D42)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UButton("取消", modifier = Modifier.weight(1f), onClick = dismiss)
            UButton("创建配置", primary = true, modifier = Modifier.weight(1f)) {
                if (model.create(name.trim(), label, size, uri, filename)) dismiss()
                else error = model.state.value.message ?: "无法保存配置"
            }
        }
    }
}
@Composable private fun AdbDialog(model: AppViewModel, slot: DsuSlot, dismiss: () -> Unit) {
    val result = remember(slot.id) { runCatching { model.adbCommand(slot) } }
    val context = LocalContext.current
    UDialog("通过 ADB 安装", dismiss) {
        UText("连接电脑并开启 USB 调试，按顺序执行以下两行命令，然后在设备上确认安装。请保留 UltraDSU 和准备文件，直到系统安装完成。", 14, color = mutedColor)
        UText("系统安装器可能先删除当前 DSU！无 Root 的 ADB 流程不能保证保留已有槽。需要保留多个系统时，请开启 Root 使用内置安装。执行前请备份已有 DSU 数据。", 13, color = Color(0xFFC13D42))
        result.fold(onSuccess = { command ->
            SelectionContainer { Text(command, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = inkColor) }
            UButton("复制命令", primary = true, modifier = Modifier.fillMaxWidth()) {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DSU install", command))
                model.notify("ADB 命令已复制")
            }
            UButton("我已执行，标记为已提交", modifier = Modifier.fillMaxWidth()) { model.submitted(slot); dismiss() }
        }, onFailure = { UText(it.message ?: "无法生成命令", 14) })
        UButton("关闭", modifier = Modifier.fillMaxWidth(), onClick = dismiss)
    }
}
@Composable private fun BootDialog(slot: DsuSlot, dismiss: () -> Unit, onBoot: (Boolean, Boolean) -> Unit) {
    var sticky by rememberSaveable { mutableStateOf(false) }
    UDialog("启动 ${slot.displayName}", dismiss) {
        UText("默认仅启动一次，之后重启返回主系统。", 14, color = mutedColor)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            UText("持续启动此槽", 15, modifier = Modifier.weight(1f))
            USwitch(sticky, onChange = { sticky = it })
        }
        UButton("设为下次启动", primary = true, modifier = Modifier.fillMaxWidth()) { onBoot(sticky, false) }
        UButton("选择并立即重启", modifier = Modifier.fillMaxWidth()) { onBoot(sticky, true) }
        UButton("取消", modifier = Modifier.fillMaxWidth(), onClick = dismiss)
    }
}
