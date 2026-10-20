package io.github.ultradsu.diagnostics

import android.content.Context
import android.os.Build
import io.github.ultradsu.BuildConfig
import io.github.ultradsu.data.DsuSlot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Opt-in, app-private, bounded diagnostics. No root or whole-device logcat at startup/export. */
object DebugLog {
    private const val LIMIT = 512 * 1024L
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "UltraDSU-debug").apply { isDaemon = true } }
    private val redactor = LogRedactor()
    private var directory: File? = null
    @Volatile var enabled = false
        private set
    private var epoch = 0L
    private val progressTimes = mutableMapOf<String, Pair<Long, String>>()

    @Synchronized fun initialize(context: Context) {
        if (directory != null) return
        directory = File(context.noBackupFilesDir, "diagnostics")
        redactor.remember(context.filesDir.absolutePath, context.cacheDir.absolutePath)
        enabled = context.getSharedPreferences("settings", 0).getBoolean("debug_enabled", false)
        event("process", "started app=${BuildConfig.VERSION_NAME} sdk=${Build.VERSION.SDK_INT}")
    }
    @Synchronized fun setEnabled(context: Context, value: Boolean) {
        initialize(context)
        context.getSharedPreferences("settings", 0).edit().putBoolean("debug_enabled", value).apply()
        if (enabled == value) return
        enabled = value
        epoch++
        progressTimes.clear()
        if (value) event("debug", "collection enabled")
    }
    fun rememberSlots(slots: List<DsuSlot>) {
        slots.forEach { slot ->
            redactor.remember(slot.id, slot.slotName, slot.displayName, slot.sourceName, slot.sourceUri)
            (slot.images + slot.pendingImages).forEach { redactor.remember(it.sourceName, it.fileName) }
        }
    }
    fun remember(vararg values: String) = redactor.remember(*values)

    @Synchronized fun event(area: String, message: String, failure: Throwable? = null) {
        if (!enabled) return
        val generation = epoch
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
        val detail = buildString {
            append(stamp).append(" [").append(area).append("] ").append(message.take(16384))
            var error = failure
            repeat(4) {
                error?.let { cause ->
                    append("\n").append(cause.javaClass.simpleName).append(": ").append(cause.message.orEmpty().take(4096))
                    cause.stackTrace.take(12).forEach { append("\n  at ").append(it) }
                    error = cause.cause.takeUnless { it === cause }
                }
            }
            append('\n')
        }
        val safe = redactor.redact(detail)
        writer.execute { synchronized(this) { if (enabled && epoch == generation) runCatching { append(safe) } } }
    }
    @Synchronized fun progress(task: String, message: String, bytes: Long, total: Long) {
        if (!enabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        val phase = message.replace(Regex("[0-9.]+"), "#")
        val previous = progressTimes[task]
        if (previous?.second == phase && now - previous.first < 2000) return
        progressTimes[task] = now to phase
        while (progressTimes.size > 32) progressTimes.remove(progressTimes.keys.first())
        event("progress", "$message bytes=$bytes total=$total")
    }
    private fun append(safe: String) {
        val folder = directory ?: return
        if (!folder.isDirectory && !folder.mkdirs()) return
        val current = File(folder, "current.log")
        if (current.length() + safe.toByteArray(Charsets.UTF_8).size > LIMIT) {
            val previous = File(folder, "previous.log")
            check(!previous.exists() || previous.delete()) { "Cannot rotate debug log" }
            check(!current.exists() || current.renameTo(previous)) { "Cannot rotate debug log" }
        }
        current.appendText(safe, Charsets.UTF_8)
    }
    private suspend fun <T> queued(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        writer.execute {
            try { val result = synchronized(this) { block() }; if (continuation.isActive) continuation.resume(result) }
            catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
        }
    }
    suspend fun clear() = queued {
        epoch++
        listOf("previous.log", "current.log").forEach { name ->
            directory?.let { folder -> val file = File(folder, name); check(!file.exists() || file.delete()) { "日志清理失败" } }
        }
        progressTimes.clear()
    }
    suspend fun snapshot(): String = queued {
        buildString {
            appendLine("UltraDSU diagnostic report")
            appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) sdk=${Build.VERSION.SDK_INT}")
            appendLine("collection=$enabled; maximum retained log data=1 MiB")
            appendLine("Paths, known slot/image names, URIs and common identifiers are redacted.")
            appendLine("Only app events and explicitly requested DSU system diagnostics are included.")
            appendLine("System buffers can include older events; review before sharing. No key files are read.")
            listOf("previous.log", "current.log").forEach { name ->
                val file = directory?.let { File(it, name) }
                if (file?.isFile == true) append(redactor.redact(file.readText()))
            }
        }
    }
}
