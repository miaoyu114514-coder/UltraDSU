package io.github.ultradsu.dsu

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import io.github.ultradsu.MainActivity
import io.github.ultradsu.R
import io.github.ultradsu.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class PreparationState(val busy: Boolean = false, val id: String = "", val bytes: Long = 0,
    val message: String = "", val finishedAt: Long = 0, val installation: Boolean = false)

class PreparationService : Service() {
    companion object {
        private val mutableState = MutableStateFlow(PreparationState())
        val state = mutableState.asStateFlow()
        fun imageFile(context: android.content.Context, id: String) = File(context.filesDir, "images/$id.img.gz")
        fun slotDirectory(context: android.content.Context, id: String) = File(context.filesDir, "images/$id")
        fun images(slot: DsuSlot): List<PartitionImage> = slot.images.ifEmpty {
            listOf(PartitionImage("system", "", slot.systemBytes, slot.sourceName))
        }
        fun resolve(context: android.content.Context, slot: DsuSlot, image: PartitionImage): File {
            val file = if (image.fileName.isEmpty()) imageFile(context, slot.id) else File(slotDirectory(context, slot.id), image.fileName)
            val base = File(context.filesDir, "images").canonicalFile
            check(file.canonicalPath.startsWith(base.path + File.separator)) { "无效的准备文件路径" }
            check(file.isFile) { "准备文件已丢失：${image.sourceName}" }
            return file
        }
        fun installerFile(context: android.content.Context, slot: DsuSlot): File {
            val images = images(slot)
            return if (images.size == 1) resolve(context, slot, images.single()) else {
                val parent = resolve(context, slot, images.first()).parentFile!!
                File(parent, "package.zip").also { check(it.isFile) { "多分区安装包不存在，请重新准备" } }
            }
        }
        fun clearFiles(context: android.content.Context, id: String) {
            imageFile(context, id).delete()
            val directory = slotDirectory(context, id)
            val root = File(context.filesDir, "images").canonicalFile
            check(directory.canonicalFile.parentFile == root) { "拒绝清理非法目录" }
            if (directory.isDirectory) directory.walkBottomUp().forEach { check(it.delete()) { "删除准备文件失败" } }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private lateinit var notifications: NotificationManager
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        // A killed preparation cannot resume. Keep every directory referenced by a slot,
        // including staged repairs, and reclaim only our own unreferenced UUID directories.
        cacheDir.listFiles()?.filter { it.isFile && it.name.matches(Regex("source-[a-f0-9-]{36}")) }?.forEach { it.delete() }
        runCatching { SlotStore(this).read() }.onSuccess { slots ->
            slots.forEach { slot ->
                val parent = slotDirectory(this, slot.id)
                val referenced = (slot.images + slot.pendingImages).map { it.fileName.substringBefore('/') }.toSet()
                parent.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("(prepared|update)_[a-f0-9-]{36}")) && it.name !in referenced }
                    ?.forEach { directory ->
                        if (directory.canonicalFile.parentFile == parent.canonicalFile) directory.walkBottomUp().forEach { file ->
                            if (file.canonicalPath.startsWith(parent.canonicalPath + File.separator)) file.delete()
                        }
                    }
            }
        }
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("prepare", "镜像准备与安装", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, "prepare").setSmallIcon(R.drawable.ic_stat_dsu)
            .setContentTitle(if (mutableState.value.installation) "UltraDSU · 写入系统槽" else "UltraDSU · 准备镜像")
            .setContentText(text).setContentIntent(open).setOngoing(true).setProgress(0, 0, true)
        if (!mutableState.value.installation) builder.addAction(Notification.Action.Builder(null, "取消", cancel).build())
        return builder.build()
    }
    private fun report(id: String, message: String, writing: Boolean) {
        mutableState.value = PreparationState(true, id, message = message, installation = writing)
        notifications.notify(100, notification(message))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            if (!mutableState.value.installation) { if (work?.isActive == true) work?.cancel() else stopSelf() }
            return START_NOT_STICKY
        }
        if (work?.isActive == true) return START_NOT_STICKY
        val id = intent?.getStringExtra("slotId") ?: run { stopSelf(); return START_NOT_STICKY }
        val action = intent.action ?: "prepare"
        val writing = action in setOf("install", "replace", "resize")
        mutableState.value = PreparationState(true, id, message = "正在启动任务", installation = writing)
        startForeground(100, notification(mutableState.value.message))
        work = scope.launch {
            val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UltraDSU:work")
            wake.acquire(6 * 60 * 60 * 1000L)
            try {
                when (action) {
                    "install", "replace", "resize" -> write(id, action, intent)
                    else -> prepare(id, intent, action == "stage-update")
                }
                mutableState.value = PreparationState(id = id, message = when (action) {
                    "install" -> "新槽安装完成，已保留其他系统"
                    "replace" -> "所选分区写入完成，其他分区与 userdata 保留"
                    "resize" -> "userdata 已重建为新容量，原数据已清除"
                    "stage-update" -> "替换镜像已准备，请核对分区清单后确认写入"
                    else -> "镜像准备完成，请核对分区清单"
                }, finishedAt = System.currentTimeMillis(), installation = writing)
            } catch (e: Exception) {
                android.util.Log.e("UltraDSU", "Task $action failed for slot $id", e)
                mutableState.value = PreparationState(id = id, message = if (e is CancellationException) "任务已取消，请刷新核实状态"
                    else "任务失败：${e.message}", finishedAt = System.currentTimeMillis(), installation = writing)
            } finally { if (wake.isHeld) wake.release(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    private suspend fun prepare(id: String, intent: Intent, update: Boolean) {
        val store = SlotStore(this); val slot = store.read().first { it.id == id }
        if (update) check(RootClient(this).authorized) { "请先授权 Root" }
        else check(slot.state in listOf(SlotState.DRAFT, SlotState.MISSING)) { "此槽已准备或已提交" }
        val uri = if (update) intent.getStringExtra("sourceUri") ?: error("未选择镜像") else slot.sourceUri
        val name = if (update) intent.getStringExtra("sourceName") ?: "system.img" else slot.sourceName
        val target = intent.getStringExtra("partition") ?: "system"
        val directory = File(slotDirectory(this, id), "${if (update) "update" else "prepared"}_${UUID.randomUUID()}")
        directory.mkdirs()
        val input = File(cacheDir, "source-${UUID.randomUUID()}")
        var successful = false
        try {
            val coroutine = currentCoroutineContext()
            val checkpoint = { coroutine.ensureActive() }
            report(id, "正在读取 $name", false)
            contentResolver.openInputStream(Uri.parse(uri))?.use { source ->
                input.outputStream().buffered(1024 * 1024).use { output ->
                    val buffer = ByteArray(1024 * 1024); var bytes = 0L; var reported = 0L
                    val started = System.nanoTime()
                    while (true) { checkpoint(); val n = source.read(buffer); if (n < 0) break
                        bytes += n; check(bytes <= 128 * GIB && cacheDir.usableSpace > 256 * 1024 * 1024) { "源文件过大或存储空间不足" }
                        output.write(buffer, 0, n)
                        if (System.currentTimeMillis() - reported > 1000) {
                            val seconds = (System.nanoTime() - started) / 1_000_000_000.0
                            report(id, "读取 $name · ${bytes / 1048576} MiB · ${"%.1f".format(java.util.Locale.ROOT, bytes / 1048576.0 / seconds)} MiB/s", false)
                            reported = System.currentTimeMillis()
                        }
                    }
                }
            } ?: error("无法读取文件，请重新选择")
            val preparer = ArchivePreparer({ report(id, it, false) }, checkpoint, sevenZip = NativeSevenZip)
            val prepared = preparer.prepare(input, name, directory, target, requireSystem = !update)
            if (!update) {
                check(filesDir.usableSpace > prepared.sumOf { it.bytes } + slot.userdataBytes + GIB) { "空间不足：安装还需全部分区 + userdata + 系统预留空间" }
                if (prepared.size > 1) preparer.installerZip(prepared, directory)
            }
            val images = prepared.map { it.copy(fileName = "${directory.name}/${it.fileName}") }
            store.update(id) {
                if (update) it.copy(pendingImages = images, pendingSourceName = name)
                else it.copy(state = SlotState.PREPARED, images = images, systemBytes = images.first { p -> p.partition == "system" }.bytes)
            }
            successful = true
        } finally {
            input.delete()
            if (!successful) directory.walkBottomUp().forEach { it.delete() }
        }
    }
    private suspend fun write(id: String, action: String, intent: Intent) {
        val root = RootClient(this); check(root.authorized) { "Root 尚未授权" }
        val store = SlotStore(this); val slot = store.read().first { it.id == id }
        val callback: (String) -> Unit = { report(id, it, true) }
        if (action == "resize") {
            val size = intent.getIntExtra("userdataGiB", 0)
            check(size in 1..512) { "新容量须在 1–512 GiB 之间" }
            root.bridge("resize", slot.slotName, (size * GIB).toString(), progress = callback)
            store.update(id) { it.copy(userdataGiB = size) }; return
        }
        val images = if (action == "replace") slot.pendingImages else images(slot)
        check(images.isNotEmpty()) { "请先准备镜像" }
        if (action == "install") check(slot.state in listOf(SlotState.PREPARED, SlotState.SUBMITTED)) { "槽状态不允许安装" }
        val manifest = File(slotDirectory(this, id).apply { mkdirs() }, "operation-${UUID.randomUUID()}.json")
        val array = JSONArray().apply { images.forEach { image -> put(JSONObject().apply {
            put("partition", image.partition); put("path", resolve(this@PreparationService, slot, image).canonicalPath); put("bytes", image.bytes)
        }) } }
        manifest.writeText(array.toString())
        try {
            if (action == "install") {
                store.update(id) { it.copy(state = SlotState.SUBMITTED) }
                try {
                    root.bridge("install-package", slot.slotName, manifest.absolutePath, slot.userdataBytes.toString(), progress = callback)
                } catch (e: Exception) {
                    // Keep validated images reusable after a rejected install. Root refresh
                    // marks a surviving partial system slot INCOMPLETE instead.
                    store.update(id) { it.copy(state = SlotState.PREPARED) }
                    throw e
                }
                store.update(id) { it.copy(state = SlotState.INSTALLED) }
            } else {
                root.bridge("replace", slot.slotName, manifest.absolutePath, progress = callback)
                store.update(id) { it.copy(pendingImages = emptyList(), pendingSourceName = "",
                    systemBytes = images.find { image -> image.partition == "system" }?.bytes ?: it.systemBytes,
                    images = it.images.filterNot { old -> images.any { new -> new.partition == old.partition } } + images) }
            }
        } finally { manifest.delete() }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { work?.cancel(); stopSelf() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
