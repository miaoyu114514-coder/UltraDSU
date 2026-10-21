package io.github.ultradsu

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ultradsu.data.*
import io.github.ultradsu.dsu.PreparationService
import io.github.ultradsu.dsu.RootClient
import io.github.ultradsu.dsu.UserdataGrowthCheck
import io.github.ultradsu.dsu.GuestAssistanceClient
import io.github.ultradsu.diagnostics.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    val debugMode: Boolean = false,
    val diagnosticsBusy: Boolean = false,
    val slots: List<DsuSlot> = emptyList(),
    val device: DeviceStatus,
    val root: Boolean = false,
    val rootRequested: Boolean = false,
    val busy: Boolean = false,
    val operationLabel: String = "",
    val message: String? = null,
    val log: List<String> = emptyList(),
    val inventories: Map<String, PartitionInventory> = emptyMap(),
    val growthInfo: Map<String, UserdataGrowthInfo> = emptyMap(),
    val growthCheckingSlotId: String? = null,
    val guestAssistance: GuestAssistanceInfo? = null,
    val userdataBackups: List<UserdataBackupEntry> = emptyList(),
    val userdataBackupsLoaded: Boolean = false,
    val userdataBackupsError: String? = null,
    val userdataBackupsMessage: String? = null,
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
        debugMode = preferences.getBoolean("debug_enabled", false),
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
                    reload(); notify(prep.message, showMessage = false)
                    if (prep.installation && state.value.root) {
                        if (prep.title in setOf("保留数据扩容", "回退 userdata")) operation("刷新扩容结果") {
                            syncDevice()
                            state.value.slots.find { it.id == prep.id }?.let { loadImagesInternal(it); loadGrowthInternal(it) }
                        } else refresh()
                    }
                }
            }
        }
    }
    fun dismissMessage() { mutableState.update { it.copy(message = null) } }
    fun dismissTaskResult() { PreparationService.dismissResult() }
    fun notify(message: String, showMessage: Boolean = true) {
        DebugLog.event("app", message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        mutableState.update { it.copy(message = if (showMessage) message else null,
            log = (listOf("$time  $message") + it.log).take(100)) }
    }
    private fun reload() {
        runCatching { store.read() }.onSuccess { list -> DebugLog.rememberSlots(list); mutableState.update { it.copy(slots = list) } }
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
    fun debugMode(enabled: Boolean) {
        DebugLog.setEnabled(context, enabled)
        mutableState.update { it.copy(debugMode = enabled) }
    }
    fun exportDebugLog(uri: Uri) = diagnostics {
        val report = DebugLog.snapshot()
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开导出文件")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
        }
        notify("调试日志已导出，请检查内容后再分享")
    }
    fun clearDebugLog() = diagnostics { DebugLog.clear(); notify("调试日志已清空", showMessage = true) }
    fun collectDsuDiagnostics() = diagnostics {
        check(state.value.debugMode && state.value.root) { "请先开启调试模式并授权 Root" }
        DebugLog.rememberSlots(store.read())
        // Fixed allowlist. Never dump getprop, keys, manifests, dm tables or the entire logcat.
        val properties = listOf("ro.crypto.state", "ro.crypto.type", "ro.crypto.metadata.enabled",
            "ro.crypto.dm_default_key.options_format.version", "ro.gsid.image_running")
        for (property in properties) DebugLog.event("system-property", "$property=${root.shell("getprop $property").trim()}")
        val logs = root.shell("logcat -b all -d -t 200 -v brief -s gsid:V vold:V fs_mgr:V '*:S'")
        DebugLog.event("system-log", logs.ifBlank { "No matching retained system logs; reproduce first. Guest boot failures may need guest/recovery logs." })
        notify("已采集 DSU 系统诊断，可导出日志")
    }
    private fun diagnostics(block: suspend () -> Unit) {
        if (state.value.diagnosticsBusy) return
        mutableState.update { it.copy(diagnosticsBusy = true) }
        viewModelScope.launch {
            try { block() }
            catch (e: Exception) { DebugLog.event("diagnostics", "failed", e); notify(e.message ?: "日志操作失败") }
            finally { mutableState.update { it.copy(diagnosticsBusy = false) } }
        }
    }
    private fun operation(label: String, showFailureMessage: Boolean = true, block: suspend () -> Unit) {
        if (state.value.busy || preparation.value.busy) return
        mutableState.update { it.copy(busy = true, operationLabel = label) }
        DebugLog.event("operation", "start $label")
        viewModelScope.launch {
            try { block(); DebugLog.event("operation", "success $label") }
            catch (e: Exception) {
                DebugLog.event("operation", "failed $label", e)
                if (showFailureMessage) notify(e.message ?: "操作失败")
            }
            finally { mutableState.update { it.copy(busy = false, operationLabel = "") } }
        }
    }
    fun rootEnabled(enabled: Boolean) {
        if (state.value.busy || preparation.value.busy) return
        preferences.edit().putBoolean("root_enabled", enabled).apply()
        mutableState.update { it.copy(rootRequested = enabled) }
        if (!enabled) {
            if (state.value.busy) return
            root.revoke()
            mutableState.update { it.copy(root = false, rootRequested = false, device = initialDevice,
                userdataBackups = emptyList(), userdataBackupsLoaded = false, userdataBackupsError = null, userdataBackupsMessage = null) }
            return
        }
        operation("检查 Root 授权") {
            check(root.authorize()) { "Root 授权失败或被拒绝，Root 功能保持隐藏" }
            mutableState.update { it.copy(root = true) }
            syncDevice()
        }
    }
    fun refresh() { reload(); if (state.value.root) operation("刷新系统槽") { syncDevice() } }
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
            store.write(merged)
            // Only a live Root status can confirm a submitted system install or
            // migrate retained files from older app versions. Never trust the old label.
            if (!device.installing && !preparation.value.busy) {
                merged.filter { it.slotName in names }.forEach { slot ->
                    runCatching { store.cleanupPrepared(slot.id, java.io.File(context.filesDir, "images"), installationConfirmed = true) }
                        .onSuccess { if (it > 0) DebugLog.event("prepared-cleanup", "releasedBytes=$it") }
                        .onFailure { DebugLog.event("prepared-cleanup", "Cleanup deferred; slot contents preserved", it) }
                }
            }
            store.read()
        }
        DebugLog.rememberSlots(local)
        DebugLog.event("device", "multiSlot=${device.multiSlot} internalMultiSlot=${device.internalMultiSlot} running=${device.running} installing=${device.installing} slots=${local.size}")
        json.optJSONObject("userdataSizeErrors")?.takeIf { it.length() > 0 }?.let {
            DebugLog.event("userdata-size", it.toString())
        }
        val assistance = json.optJSONObject("guestAssistance")?.takeIf { it.optString("phase") != "NONE" }
            ?.let { GuestAssistanceInfo(it.getString("slot"), it.getString("phase")) }
        mutableState.update { it.copy(device = device, slots = local, guestAssistance = assistance) }
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
    fun install(slot: DsuSlot) = operation("检查安装条件") {
        val device = if (state.value.root) syncDevice() else state.value.device
        check(slot.state in listOf(SlotState.DRAFT, SlotState.MISSING, SlotState.PREPARED, SlotState.SUBMITTED)) { "此槽状态不允许安装" }
        check(!device.running && !device.installing) { "请先返回主系统并等待当前 DSU 安装结束" }
        check(slot.slotName !in device.occupiedSlots) { "系统已有同名槽位或未完成的安装，已阻止覆盖" }
        check(device.multiSlot || device.occupiedSlots.isEmpty()) { "当前系统不支持多个 DSU 槽" }
        check(device.multiSlot || slot.slotName == "dsu") { "当前系统仅支持默认槽 dsu，请重新创建配置" }
        if (state.value.root && device.multiSlot) {
            check(device.internalMultiSlot || (slot.slotName == "dsu" && device.occupiedSlots.isEmpty())) {
                "此系统 gsid 不支持在内部存储新增命名槽；AOSP Android 15 起支持内部多槽。现有系统和准备镜像已保留，不能通过 Root 权限直接绕过此限制。"
            }
            context.startForegroundService(Intent(context, PreparationService::class.java)
                .setAction("install-auto").putExtra("slotId", slot.id))
            return@operation
        }
        check(device.installerAvailable) { "系统 DSU 安装器不可用" }
        check(device.internalMultiSlot || slot.slotName == "dsu") { "此系统不支持内部命名槽，请使用默认槽 dsu" }
        context.startForegroundService(Intent(context, PreparationService::class.java)
            .setAction(if (state.value.root) "install-legacy" else "prepare-install-adb").putExtra("slotId", slot.id))
    }
    fun exportPartition(slot: DsuSlot, partition: String, destination: Uri) {
        viewModelScope.launch {
            // Returning from DocumentsUI can trigger onResume's read-only refresh.
            // Do not lose the newly created document while that refresh is finishing.
            if (state.value.busy && state.value.operationLabel == "刷新系统槽") state.first { !it.busy }
            if (state.value.busy || preparation.value.busy) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
                notify("已有任务正在处理，请完成后重新提取")
                return@launch
            }
            operation("检查镜像提取条件") {
                try {
                    check(state.value.root && state.value.device.multiSlot) { "分区提取需要 Root 和 Android 11+" }
                    check(partition in PartitionRules.readOnly || partition == "userdata") { "不支持提取此分区" }
                    val device = syncDevice()
                    check(!device.running && !device.installing) { "请先返回主系统并等待当前安装结束" }
                    runCatching { context.contentResolver.takePersistableUriPermission(destination, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    context.startForegroundService(Intent(context, PreparationService::class.java).setAction("export")
                        .putExtra("slotId", slot.id).putExtra("partition", partition).putExtra("destination", destination.toString()))
                } catch (failure: Exception) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
                    runCatching { context.contentResolver.releasePersistableUriPermission(destination, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    throw failure
                }
            }
        }
    }
    fun enable(slot: DsuSlot, sticky: Boolean, reboot: Boolean) = operation("设置启动槽") {
        root.bridge("enable", slot.slotName, if (sticky) "sticky" else "once")
        syncDevice()
        notify("已选择 ${slot.slotName}，${if (sticky) "持续启动" else "仅启动一次"}")
        if (reboot) root.shell("svc power reboot")
    }
    fun returnToHost(reboot: Boolean) = operation("停用 DSU") {
        root.bridge("disable"); syncDevice(); notify("已停用 DSU，下次启动进入主系统")
        if (reboot) root.shell("svc power reboot")
    }
    fun delete(slot: DsuSlot) = operation("删除 ${slot.slotName}") {
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
    fun loadImages(slot: DsuSlot) = operation("读取 ${slot.slotName} 的分区") {
        loadImagesInternal(slot)
    }
    private fun updateUserdataBackups(result: JSONObject) {
        val entries = result.getJSONArray("entries")
        val rows = (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            UserdataBackupEntry(item.getString("slot"), item.getString("kind"), item.optLong("bytes"),
                item.optLong("modified"), item.optBoolean("canDelete"), item.optString("reason"),
                item.optString("phase"), item.optString("revision"))
        }
        mutableState.update { it.copy(userdataBackups = rows, userdataBackupsLoaded = true, userdataBackupsError = null, userdataBackupsMessage = null) }
    }
    fun loadUserdataBackups() = operation("读取 userdata 备份", showFailureMessage = false) {
        try {
            check(state.value.root) { "请先开启并授权 Root" }
            updateUserdataBackups(root.bridge("userdata-backups"))
        } catch (failure: Exception) {
            mutableState.update { it.copy(userdataBackupsError = failure.message ?: "无法读取备份，请重试") }
            throw failure
        }
    }
    fun deleteUserdataBackup(entry: UserdataBackupEntry) = operation("删除 userdata 备份", showFailureMessage = false) {
        try {
            check(state.value.root && !entry.current && entry.kind == "growth" && entry.canDelete) { "此 userdata 受保护，不可删除" }
            val result = root.bridge("userdata-backup-delete", entry.slot, entry.kind, entry.revision)
            updateUserdataBackups(result)
            // Drop stale compatibility results immediately; they must not offer rollback
            // after the actual backup has been removed through the manager.
            val ids = state.value.slots.filter { it.slotName == entry.slot }.map { it.id }.toSet()
            mutableState.update { it.copy(growthInfo = it.growthInfo.filterKeys { id -> id !in ids }) }
            val cleanup = if (Build.VERSION.SDK_INT >= 36 && state.value.device.internalMultiSlot &&
                entry.slot in state.value.device.occupiedSlots) runCatching { GuestAssistanceClient(root).clear(entry.slot) }.exceptionOrNull() else null
            if (cleanup != null) DebugLog.event("userdata-backup", "credential cleanup unavailable", cleanup)
            val message = if (cleanup == null) "${entry.slot} 的备份已删除，当前 userdata 保留" else
                "备份已删除，当前 userdata 保留；协作准备未能清理，可在槽编辑页重试"
            mutableState.update { it.copy(userdataBackupsMessage = message) }
            notify(message, showMessage = false)
        } catch (failure: Exception) {
            // A stale list must never keep a destructive action enabled after rejection.
            mutableState.update { it.copy(userdataBackupsError = failure.message ?: "删除失败，请刷新后重试") }
            throw failure
        }
    }
    private suspend fun loadImagesInternal(slot: DsuSlot) {
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
    fun loadGrowth(slot: DsuSlot) = operation("检查保留数据扩容", showFailureMessage = false) {
        loadGrowthInternal(slot)
    }
    private suspend fun loadGrowthInternal(slot: DsuSlot) {
        mutableState.update { it.copy(growthCheckingSlotId = slot.id) }
        try {
            check(state.value.root && !state.value.device.running) { "请先返回主系统并授权 Root" }
            val result = GuestAssistanceClient(root).bridge("grow-info", slot.slotName)
            result.optString("reason").takeIf { it.isNotBlank() }?.let { DebugLog.event("grow-probe", it) }
            val info = UserdataGrowthCheck.read(result, Build.VERSION.SDK_INT)
            mutableState.update { it.copy(growthInfo = it.growthInfo + (slot.id to info)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(growthInfo = it.growthInfo + (slot.id to
                UserdataGrowthCheck.failed(it.growthInfo[slot.id], failure.message.orEmpty(), Build.VERSION.SDK_INT))) }
            throw failure
        } finally {
            mutableState.update { it.copy(growthCheckingSlotId = null) }
        }
    }
    fun growUserdata(slot: DsuSlot, size: String) = operation("检查扩容条件") {
        val value = size.toIntOrNull()
        check(value != null && value in 1..512 && value > slot.userdataGiB) { "新容量必须大于现有容量，且在 1–512 GiB 之间" }
        val device = syncDevice()
        check(state.value.root && device.multiSlot && !device.running && !device.installing) { "当前状态不允许扩容" }
        check(state.value.growthInfo[slot.id]?.supported == true) { "请先检查本机兼容性" }
        context.startForegroundService(Intent(context, PreparationService::class.java).setAction("grow")
            .putExtra("slotId", slot.id).putExtra("userdataGiB", value))
    }
    fun rollbackGrowth(slot: DsuSlot) = operation("检查扩容回退条件") {
        val device = syncDevice()
        check(state.value.root && !device.running) { "请先返回主系统" }
        context.startForegroundService(Intent(context, PreparationService::class.java).setAction("grow-rollback").putExtra("slotId", slot.id))
    }
    fun discardGrowth(slot: DsuSlot) = operation("清理扩容备份") {
        check(state.value.root && !state.value.device.running) { "请先返回主系统并授权 Root" }
        root.bridge("grow-discard", slot.slotName)
        GuestAssistanceClient(root).clear(slot.slotName)
        loadGrowthInternal(slot)
        notify("扩容备份已清理")
    }
    fun prepareGuestAssistance(slot: DsuSlot) = operation("准备客体协作") {
        check(state.value.root && !state.value.device.running) { "请先返回主系统并授权 Root" }
        GuestAssistanceClient(root).prepare(slot.slotName)
        loadGrowthInternal(slot)
    }
    fun clearGuestAssistance(slot: DsuSlot) = operation("清理客体协作准备") {
        GuestAssistanceClient(root).clear(slot.slotName)
        loadGrowthInternal(slot)
    }
    fun bootForGuestAssistance(slot: DsuSlot) = operation("启动客体进行协作") {
        root.bridge("assist-boot", slot.slotName)
        root.shell("reboot")
    }
    fun captureGuestAssistance() = operation("加密采集客体协作信息") {
        root.bridge("assist-capture")
        syncDevice()
        notify("客体协作信息已加密保存，请返回主系统，在本槽检查兼容性后扩容")
    }
    fun returnFromGuestAssistance() = operation("返回主系统") {
        root.bridge("assist-return")
        root.shell("reboot")
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
    fun applyImages(slot: DsuSlot) = operation("检查分区替换条件") {
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
        operation("检查 userdata 重建条件") {
            val device = syncDevice()
            check(!device.running && !device.installing) { "请先返回主系统，并等待当前安装结束" }
            context.startForegroundService(Intent(context, PreparationService::class.java).setAction("resize")
                .putExtra("slotId", slot.id).putExtra("userdataGiB", value))
        }
        return true
    }
}
