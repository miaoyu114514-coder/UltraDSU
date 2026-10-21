package io.github.ultradsu.dsu

import android.content.Context
import android.net.Uri
import io.github.ultradsu.data.Shell
import io.github.ultradsu.diagnostics.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class RootClient(private val context: Context) {
    companion object { @Volatile private var sessionAuthorized = false }
    // The persisted opt-in controls startup checks; an old successful authorization is
    // never persisted as proof of current privilege. Every new process rechecks su.
    val authorized get() = sessionAuthorized

    suspend fun authorize(): Boolean = withContext(Dispatchers.IO) {
        sessionAuthorized = runCatching { execute("id -u").trim() == "0" }.getOrDefault(false)
        DebugLog.event("root", "authorized=$sessionAuthorized")
        authorized
    }
    fun revoke() { sessionAuthorized = false }

    private fun command(action: String, args: List<String>): String =
        "ulimit -c 0; CLASSPATH=${Shell.quote(context.applicationInfo.sourceDir)} " +
            "exec /system/bin/app_process /system/bin io.github.ultradsu.dsu.RootBridge " +
            (listOf(action) + args).joinToString(" ") { Shell.quote(it) }

    private fun result(output: String): JSONObject {
        val payload = output.lineSequence().firstOrNull { it.startsWith("ULTRADSU:") }
            ?.removePrefix("ULTRADSU:") ?: error("Root 服务未返回结果：${output.takeLast(1200)}")
        return JSONObject(payload).also {
            if (it.optBoolean("cancelled")) throw CancellationException("任务已取消，临时资源已清理")
            check(it.optBoolean("ok")) { it.optString("error", "DSU 操作失败") }
        }
    }

    suspend fun bridge(action: String, vararg args: String, progress: ((OperationProgress) -> Unit)? = null,
        secretInput: ByteArray? = null, cancellation: OperationCancellation? = null): JSONObject = withContext(Dispatchers.IO) {
        check(authorized) { "请先在设置中开启并授权 Root" }
        check(secretInput == null || cancellation == null) { "协作密钥操作不能使用安装取消通道" }
        val command = command(action, args.toList() + when {
            secretInput != null -> listOf("--assist")
            cancellation != null -> listOf("--cancellable")
            else -> emptyList()
        })
        val started = android.os.SystemClock.elapsedRealtime()
        DebugLog.event("bridge", "start action=$action")
        try {
        val output = execute(command, if (action in setOf("install", "install-package", "replace", "resize", "grow", "grow-rollback")) 6 * 60 * 60 else 120, progress, secretInput, cancellation)
        result(output).also {
            DebugLog.event("bridge", "success action=$action elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
        }
        } catch (failure: Exception) {
            DebugLog.event("bridge", "failed action=$action elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}", failure)
            throw failure
        }
    }

    suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        check(authorized) { "Root 未开启" }
        execute(command)
    }

    private fun drain(processOutput: java.io.InputStream, progress: ((OperationProgress) -> Unit)?): String {
        val tail = StringBuilder()
        processOutput.bufferedReader().useLines { lines -> lines.forEach {
            if (it.startsWith("ULTRADSU_PROGRESS:")) progress?.invoke(OperationProgress.fromBridge(it.removePrefix("ULTRADSU_PROGRESS:")))
            tail.append(it).append('\n')
            if (tail.length > 16384) tail.delete(0, tail.length - 16384)
        } }
        return tail.toString()
    }

    /** Raw bytes use stdout; control/progress use stderr. No root-owned export file or second full copy. */
    suspend fun exportPartition(slot: String, partition: String, destination: Uri, cancellation: OperationCancellation,
        progress: (OperationProgress) -> Unit): JSONObject = withContext(Dispatchers.IO) {
        check(authorized) { "请先授权 Root" }
        cancellation.check()
        val process = ProcessBuilder("su", "-c", command("export", listOf(slot, partition, "--cancellable"))).start()
        val pool = Executors.newFixedThreadPool(2)
        val control = pool.submit<String> { drain(process.errorStream, progress) }
        val digest = MessageDigest.getInstance("SHA-256")
        val copied = pool.submit<Long> {
            val target = context.contentResolver.openOutputStream(destination, "wt") ?: error("无法打开导出位置")
            target.buffered(1024 * 1024).use { output ->
                process.inputStream.use { source ->
                    val buffer = ByteArray(1024 * 1024); var bytes = 0L
                    while (true) {
                        val count = source.read(buffer); if (count < 0) break
                        bytes += count
                        check(bytes <= 512L * 1024 * 1024 * 1024) { "导出超过最大分区容量" }
                        output.write(buffer, 0, count); digest.update(buffer, 0, count)
                    }
                    output.flush(); bytes
                }
            }
        }
        cancellation.attach { runCatching { process.outputStream.write(1); process.outputStream.flush() } }
        try {
            // If the destination fails (full, disconnected, provider error), close the
            // reader so root receives EPIPE and releases its mapping before returning.
            val deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(6)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (copied.isDone) {
                    try { copied.get() } catch (failure: Exception) {
                        cancellation.request(); process.inputStream.close()
                        check(process.waitFor(120, TimeUnit.SECONDS)) { "导出停止超时，请刷新分区状态" }
                        throw failure.cause ?: failure
                    }
                }
                check(System.nanoTime() < deadline) { "导出超时" }
            }
            val output = control.get(10, TimeUnit.SECONDS)
            val json = result(output)
            check(process.exitValue() == 0) { "Root 导出进程异常退出" }
            val bytes = copied.get(30, TimeUnit.SECONDS)
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(bytes == json.getLong("bytes") && hash == json.getString("sha256")) { "导出文件传输校验失败" }
            json
        } finally {
            cancellation.detach()
            runCatching { process.outputStream.close() }
            process.destroy(); pool.shutdownNow()
        }
    }

    private fun execute(command: String, seconds: Long = 30, progress: ((OperationProgress) -> Unit)? = null,
        secretInput: ByteArray? = null, cancellation: OperationCancellation? = null): String {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val executor = Executors.newSingleThreadExecutor()
        val reader = executor.submit<String> { drain(process.inputStream, progress) }
        try {
            if (cancellation != null) cancellation.attach { runCatching { process.outputStream.write(1); process.outputStream.flush() } }
            else process.outputStream.use { if (secretInput != null) it.write(secretInput) }
            check(process.waitFor(seconds, TimeUnit.SECONDS)) { "Root 操作超时，请检查授权管理器" }
            val output = reader.get(5, TimeUnit.SECONDS)
            check(process.exitValue() == 0) { output.ifBlank { "Root 请求被拒绝" } }
            return output
        } finally {
            cancellation?.detach(); runCatching { process.outputStream.close() }
            process.destroy(); executor.shutdownNow()
        }
    }
}
