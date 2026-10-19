package io.github.ultradsu

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ultradsu.data.*
import io.github.ultradsu.dsu.PreparationService
import io.github.ultradsu.dsu.RootClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppState(
    val style: UiStyle = UiStyle.MIUIX,
    val glassNavigation: Boolean = false,
    val slots: List<DsuSlot> = emptyList(),
    val device: DeviceStatus,
    val root: Boolean = false,
    val rootRequested: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val log: List<String> = emptyList(),
    val inventories: Map<String, PartitionInventory> = emptyMap(),
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val preferences = context.getSharedPreferences("settings", 0)
    private val store = SlotStore(context)
    private val root = RootClient(context)
    private val component = ComponentName("com.android.dynsystem", "com.android.dynsystem.VerificationActivity")
    private val initialDevice = DeviceStatus(Build.VERSION.SDK_INT,
        context.packageManager.resolveActivity(Intent().setComponent(component), 0) != null)
    private val mutableState = MutableStateFlow(AppState(
        style = runCatching { UiStyle.valueOf(preferences.getString("style", "MIUIX")!!) }.getOrDefault(UiStyle.MIUIX),
        glassNavigation = preferences.getBoolean("glass_navigation", false),
        device = initialDevice, rootRequested = preferences.getBoolean("root_enabled", false),
        root = root.authorized && preferences.getBoolean("root_enabled", false)))
    val state = mutableState.asStateFlow()
    val preparation = PreparationService.state

    init {
        reload()
        if (state.value.rootRequested && !state.value.root) rootEnabled(true)
        viewModelScope.launch {
            preparation.collect { prep ->
                if (!prep.busy && prep.finishedAt > 0) {
                    reload(); notify(prep.message)
                    if (prep.installation && state.value.root) refresh()
                }
            }
        }
    }
    fun dismissMessage() { mutableState.update { it.copy(message = null) } }
    fun notify(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        mutableState.update { it.copy(message = message, log = (listOf("$time  $message") + it.log).take(100)) }
    }
    private fun reload() {
        runCatching { store.read() }.onSuccess { list -> mutableState.update { it.copy(slots = list) } }
            .onFailure { notify("槽位记录读取失败，已保留原文件：${it.message}") }
    }
    fun style(style: UiStyle) {
        preferences.edit().putString("style", style.name).apply()
        mutableState.update { it.copy(style = style) }
    }
    fun glassNavigation(enabled: Boolean) {
        preferences.edit().putBoolean("glass_navigation", enabled).apply()
        mutableState.update { it.copy(glassNavigation = enabled) }
    }
    private fun operation(block: suspend () -> Unit) {
        if (state.value.busy || preparation.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block() } catch (e: Exception) { notify(e.message ?: "操作失败") }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    fun rootEnabled(enabled: Boolean) {
        if (state.value.busy || preparation.value.busy) return
        preferences.edit().putBoolean("root_enabled", enabled).apply()
        mutableState.update { it.copy(rootRequested = enabled) }
        if (!enabled) {
            if (state.value.busy) return
            root.revoke()
            mutableState.update { it.copy(root = false, rootRequested = false, device = initialDevice) }
            return
        }
        operation {
            check(root.authorize()) { "Root 授权失败或被拒绝，Root 功能保持隐藏" }
            mutableState.update { it.copy(root = true) }
            syncDevice()
        }
    }
    fun refresh() { reload(); if (state.value.root) operation { syncDevice() } }
    private suspend fun syncDevice(): DeviceStatus {
        val json = root.bridge("status")
        val array = json.getJSONArray("slots")
        val names = (0 until array.length()).map { array.getString(it) }
        val occupiedArray = json.optJSONArray("occupiedSlots") ?: array
        val occupied = (0 until occupiedArray.length()).map { occupiedArray.getString(it) }
        val userdataSizes = json.optJSONObject("userdataSizes") ?: JSONObject()
        fun userdataGiB(name: String): Int? {
            val bytes = userdataSizes.optLong(name)
            return if (bytes in GIB..(512 * GIB) && bytes % GIB == 0L) (bytes / GIB).toInt() else null
        }
        val device = initialDevice.copy(multiSlot = json.getBoolean("multiSlot"), verified = true,
            internalMultiSlot = json.optBoolean("internalMultiSlot", Build.VERSION.SDK_INT >= 35),
            detail = if (json.optString("internalMultiSlotSource") == "tested_moto_backport") "已按二进制哈希核验 moto Android 14 内部多槽回移版 gsid；可使用内部多槽安装引擎。" else "",
            installedSlots = names, occupiedSlots = occupied, activeSlot = json.optString("active"), running = json.getBoolean("running"),
            enabled = json.getBoolean("enabled"), installing = json.getBoolean("installing"))
        val local = withContext(Dispatchers.IO) {
            val old = store.read()
            val merged = old.map { s -> when {
                s.slotName in names -> s.copy(state = SlotState.INSTALLED)
                s.slotName in occupied -> s.copy(state = SlotState.INCOMPLETE)
                s.state == SlotState.INSTALLED -> s.copy(state = SlotState.MISSING)
                else -> s
            }.let { updated -> userdataGiB(s.slotName)?.let { size -> updated.copy(userdataGiB = size) } ?: updated } } + occupied.filter { name -> old.none { it.slotName == name } }.map {
                DsuSlot(slotName = it, displayName = it, userdataGiB = userdataGiB(it) ?: 0,
                    state = if (it in names) SlotState.INSTALLED else SlotState.INCOMPLETE)
            }
            store.write(merged); merged
        }
        mutableState.update { it.copy(device = device, slots = local) }
        return device
    }
    fun sourceName(uri: Uri): String {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "system.img"
    }
    fun create(name: String, label: String, size: String, uri: String, fileName: String): Boolean {
        if (state.value.busy || preparation.value.busy) return false
        val problem = SlotRules.validate(name, size, if (state.value.device.multiSlot) Build.VERSION.SDK_INT else 29, state.value.slots)
        if (problem != null) { notify(problem); return false }
        if (!state.value.device.multiSlot && state.value.slots.isNotEmpty()) { notify("当前系统仅支持单槽"); return false }
        if (uri.isBlank()) { notify("请先选择 GSI 镜像"); return false }
        return runCatching {
            store.write(store.read() + DsuSlot(slotName = name, displayName = label.trim().ifBlank { name },
                userdataGiB = size.toInt(), sourceUri = uri, sourceName = fileName))
            reload(); notify("已创建槽位配置：$name"); true
        }.getOrElse { notify("保存失败：${it.message}"); false }
    }
    fun edit(slot: DsuSlot, label: String, size: String): Boolean {
        if (state.value.busy || preparation.value.busy) return false
        val editable = slot.state == SlotState.DRAFT
        if (editable && size.toIntOrNull() !in 1..512) { notify("userdata 须为 1–512 GiB"); return false }
        return runCatching {
            store.update(slot.id) { it.copy(displayName = label.trim().ifBlank { slot.slotName },
                userdataGiB = if (editable) size.toInt() else it.userdataGiB) }
            reload(); true
        }.getOrElse { notify("保存失败：${it.message}"); false }
    }
    fun prepare(slot: DsuSlot) {
        if (state.value.busy || preparation.value.busy) return
        runCatching {
            check(state.value.device.installerAvailable || (state.value.root && state.value.device.multiSlot)) { "此 ROM 未提供系统 DSU 安装器，请开启 Root 核实内置安装能力" }
            check(!state.value.device.running) { "请先返回主系统" }
            check(!state.value.device.installing) { "系统正在安装另一个 DSU" }
            val intent = Intent(context, PreparationService::class.java).putExtra("slotId", slot.id)
            context.startForegroundService(intent)
        }.onFailure { notify(it.message ?: "无法启动准备任务") }
    }
    fun cancelPreparation() { context.startService(Intent(context, PreparationService::class.java).setAction("cancel")) }

    fun preparedUri(slot: DsuSlot): Uri {
        val file = PreparationService.installerFile(context, slot)
        check(file.isFile && slot.systemBytes > 0) { "镜像文件不存在，请重新创建配置并准备" }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.images", file)
        context.grantUriPermission("com.android.dynsystem", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return uri
    }
    fun adbCommand(slot: DsuSlot): String {
        check(state.value.device.installerAvailable) { "系统 DSU 安装器不可用" }
        check(state.value.device.internalMultiSlot || slot.slotName == "dsu") { "此系统不支持内部命名槽；默认槽为 dsu，不能提交会先清理现有系统的失败安装请求" }
        check(Build.VERSION.SDK_INT >= 30 || slot.images.size <= 1) { "Android 10 的系统安装器不支持多分区包" }
        val uri = preparedUri(slot)
        return "adb shell setprop persist.sys.fflag.override.settings_dynamic_system true\n" +
            "adb shell " + Shell.install(uri.toString(), slot, Build.VERSION.SDK_INT)
    }
    fun submitted(slot: DsuSlot) {
        runCatching {
            store.update(slot.id) { it.copy(state = SlotState.SUBMITTED) }; reload()
            notify("已标记为提交；安装结果请查看系统 DSU 通知，或开启 Root 后刷新核实")
        }.onFailure { notify("请求已发出但本地状态保存失败：${it.message}") }
    }
    fun install(slot: DsuSlot) = operation {
        val device = syncDevice()
        check(!device.running && !device.installing) { "请先返回主系统并等待当前 DSU 安装结束" }
        check(slot.slotName !in device.occupiedSlots) { "系统已有同名槽位或未完成的安装，已阻止覆盖" }
        check(device.multiSlot || device.occupiedSlots.isEmpty()) { "当前系统不支持多个 DSU 槽" }
        check(device.multiSlot || slot.slotName == "dsu") { "当前系统仅支持默认槽 dsu，请重新创建配置" }
        if (device.multiSlot) {
            check(device.internalMultiSlot || (slot.slotName == "dsu" && device.occupiedSlots.isEmpty())) {
                "此系统 gsid 不支持在内部存储新增命名槽；AOSP Android 15 起支持内部多槽。现有系统和准备镜像已保留，不能通过 Root 权限直接绕过此限制。"
            }
            context.startForegroundService(Intent(context, PreparationService::class.java)
                .setAction("install").putExtra("slotId", slot.id))
            return@operation
        }
        check(device.installerAvailable) { "系统 DSU 安装器不可用" }
        val uri = preparedUri(slot)
        val output = root.shell("setprop persist.sys.fflag.override.settings_dynamic_system true && " + Shell.install(uri.toString(), slot, Build.VERSION.SDK_INT))
        check(!output.contains("Error:", true) && !output.contains("Exception", true) && output.contains("Status: ok", true)) {
            "系统未确认启动安装器：${output.takeLast(1200)}"
        }
        submitted(slot)
    }
    fun enable(slot: DsuSlot, sticky: Boolean, reboot: Boolean) = operation {
        root.bridge("enable", slot.slotName, if (sticky) "sticky" else "once")
        syncDevice()
        notify("已选择 ${slot.slotName}，${if (sticky) "持续启动" else "仅启动一次"}")
        if (reboot) root.shell("svc power reboot")
    }
    fun returnToHost(reboot: Boolean) = operation {
        root.bridge("disable"); syncDevice(); notify("已停用 DSU，下次启动进入主系统")
        if (reboot) root.shell("svc power reboot")
    }
    fun delete(slot: DsuSlot) = operation {
        if (slot.state in listOf(SlotState.INSTALLED, SlotState.SUBMITTED, SlotState.INCOMPLETE, SlotState.MISSING)) {
            val device = syncDevice()
            if (slot.slotName in device.occupiedSlots) root.bridge("remove", slot.slotName)
            syncDevice()
        }
        withContext(Dispatchers.IO) {
            val file = runCatching { PreparationService.installerFile(context, slot) }.getOrNull()
            if (file != null && file.exists()) {
                val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.images", file)
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            PreparationService.clearFiles(context, slot.id)
            store.write(store.read().filterNot { it.id == slot.id })
        }
        reload(); notify("已删除 ${slot.displayName}")
    }
    fun loadImages(slot: DsuSlot) = operation {
        check(state.value.root && state.value.device.multiSlot) { "槽内分区管理需要 Root 和 Android 11+" }
        val json = root.bridge("images", slot.slotName)
        val list = json.getJSONArray("partitions")
        val partitions = (0 until list.length()).map { val image = list.getJSONObject(it)
            InstalledPartition(image.getString("name"), image.optLong("bytes"), image.optBoolean("mapped")) }
        val pending = json.getJSONArray("pending")
        val inventory = PartitionInventory(partitions, (0 until pending.length()).map { pending.getString(it) })
        mutableState.update { it.copy(inventories = it.inventories + (slot.id to inventory)) }
        val bytes = partitions.find { it.name == "userdata" }?.bytes ?: 0
        if (bytes >= GIB && bytes / GIB <= 512) { store.update(slot.id) { it.copy(userdataGiB = (bytes / GIB).toInt()) }; reload() }
    }
    fun stageImages(slot: DsuSlot, uri: Uri, name: String, partition: String) {
        if (state.value.busy || preparation.value.busy) return
        runCatching {
            check(state.value.root && state.value.device.multiSlot) { "请先开启并授权 Root" }
            check(!state.value.device.running && !state.value.device.installing) { "请先回到主系统，并等待当前安装结束" }
            context.startForegroundService(Intent(context, PreparationService::class.java).setAction("stage-update")
                .putExtra("slotId", slot.id).putExtra("sourceUri", uri.toString()).putExtra("sourceName", name).putExtra("partition", partition))
        }.onFailure { notify(it.message ?: "无法准备替换镜像") }
    }
    fun applyImages(slot: DsuSlot) = operation {
        val device = syncDevice()
        check(state.value.root && device.multiSlot && !device.running && !device.installing) { "当前状态不允许替换分区" }
        check(slot.pendingImages.isNotEmpty()) { "请先导入替换镜像" }
        context.startForegroundService(Intent(context, PreparationService::class.java).setAction("replace").putExtra("slotId", slot.id))
    }
    fun rebuildUserdata(slot: DsuSlot, size: String): Boolean {
        val value = size.toIntOrNull()
        val repairing = state.value.inventories[slot.id]?.pending?.contains("userdata") == true
        if (value == null || value !in 1..512 || (slot.userdataGiB > 0 && (value < slot.userdataGiB || (!repairing && value == slot.userdataGiB)))) {
            notify("新容量必须大于当前 userdata 容量，且在 1–512 GiB 之间"); return false
        }
        if (!state.value.root || !state.value.device.multiSlot || state.value.busy || preparation.value.busy) return false
        operation {
            val device = syncDevice()
            check(!device.running && !device.installing) { "请先返回主系统，并等待当前安装结束" }
            context.startForegroundService(Intent(context, PreparationService::class.java).setAction("resize")
                .putExtra("slotId", slot.id).putExtra("userdataGiB", value))
        }
        return true
    }
}
