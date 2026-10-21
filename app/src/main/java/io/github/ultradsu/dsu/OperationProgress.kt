package io.github.ultradsu.dsu

import org.json.JSONObject
import java.util.Locale

/** Counts always describe the named phase, never an estimated whole-task percentage. */
data class OperationProgress(val message: String, val bytes: Long = 0, val totalBytes: Long = 0,
    val cancellable: Boolean? = null) {
    val fraction: Float?
        get() = if (totalBytes > 0 && bytes >= 0 && bytes <= totalBytes)
            (bytes.toDouble() / totalBytes).toFloat() else null

    companion object {
        fun fromBridge(payload: String): OperationProgress = runCatching {
            val json = JSONObject(payload)
            OperationProgress(json.getString("message"), json.optLong("bytes").coerceAtLeast(0),
                json.optLong("totalBytes").coerceAtLeast(0),
                if (json.has("cancellable")) json.getBoolean("cancellable") else null)
        }.getOrElse { OperationProgress(payload) }
    }
}

fun progressBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> "${bytes.coerceAtLeast(0)} B"
}
