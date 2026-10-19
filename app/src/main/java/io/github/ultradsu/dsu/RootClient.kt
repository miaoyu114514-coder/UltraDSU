package io.github.ultradsu.dsu

import android.content.Context
import io.github.ultradsu.data.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class RootClient(private val context: Context) {
    companion object { @Volatile private var sessionAuthorized = false }
    // The persisted opt-in controls startup checks; an old successful authorization is
    // never persisted as proof of current privilege. Every new process rechecks su.
    val authorized get() = sessionAuthorized

    suspend fun authorize(): Boolean = withContext(Dispatchers.IO) {
        sessionAuthorized = runCatching { execute("id -u").trim() == "0" }.getOrDefault(false)
        authorized
    }
    fun revoke() { sessionAuthorized = false }

    suspend fun bridge(action: String, vararg args: String, progress: ((String) -> Unit)? = null): JSONObject = withContext(Dispatchers.IO) {
        check(authorized) { "请先在设置中开启并授权 Root" }
        val command = "CLASSPATH=${Shell.quote(context.applicationInfo.sourceDir)} " +
            "/system/bin/app_process /system/bin io.github.ultradsu.dsu.RootBridge " +
            (listOf(action) + args).joinToString(" ") { Shell.quote(it) }
        val output = execute(command, if (action in setOf("install", "install-package", "replace", "resize")) 6 * 60 * 60 else 120, progress)
        val json = output.lineSequence().firstOrNull { it.startsWith("ULTRADSU:") }
            ?.removePrefix("ULTRADSU:") ?: error("Root 服务未返回结果：${output.takeLast(1200)}")
        JSONObject(json).also { check(it.optBoolean("ok")) { it.optString("error", "DSU 操作失败") } }
    }

    suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        check(authorized) { "Root 未开启" }
        execute(command)
    }

    private fun execute(command: String, seconds: Long = 30, progress: ((String) -> Unit)? = null): String {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val executor = Executors.newSingleThreadExecutor()
        val reader = executor.submit<String> {
            // Always drain the pipe to prevent a child deadlock; keep a bounded diagnostic tail.
            val tail = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach {
                if (it.startsWith("ULTRADSU_PROGRESS:")) progress?.invoke(it.removePrefix("ULTRADSU_PROGRESS:"))
                tail.append(it).append('\n')
                if (tail.length > 16384) tail.delete(0, tail.length - 16384)
            } }
            tail.toString()
        }
        try {
            check(process.waitFor(seconds, TimeUnit.SECONDS)) { "Root 操作超时，请检查授权管理器" }
            val output = reader.get(5, TimeUnit.SECONDS)
            check(process.exitValue() == 0) { output.ifBlank { "Root 请求被拒绝" } }
            return output
        } finally { process.destroy(); executor.shutdownNow() }
    }
}
