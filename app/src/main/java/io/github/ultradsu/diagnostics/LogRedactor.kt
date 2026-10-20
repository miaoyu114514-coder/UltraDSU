package io.github.ultradsu.diagnostics

/** Apply at ingestion and export. Never pass keys, credentials, manifests or shell commands here. */
class LogRedactor {
    private val privateValues = linkedSetOf<String>()
    @Synchronized fun remember(vararg values: String) {
        values.filter { it.isNotBlank() }.forEach { privateValues.add(it) }
        while (privateValues.size > 2048) privateValues.remove(privateValues.first())
    }
    @Synchronized fun redact(text: String): String {
        var result = text
        privateValues.sortedByDescending { it.length }.forEach {
            result = if (it.length >= 3) result.replace(it, "<private>")
            else result.replace(Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(it)}(?![\\p{L}\\p{N}_])"), "<private>")
        }
        result = result.replace(Regex("(?i)(?:content|file|https?)://[^\\s\\\"'<>]+"), "<uri>")
        result = result.replace(Regex("(?i)[a-z]:[\\\\/][^\\r\\n\\\"'<>]+"), "<path>")
        result = result.replace(Regex("(?<![\\w])/(?:[^\\s\\\"'<>:,;]+)"), "<path>")
        // Do not scan an arbitrarily long run of word characters from every offset.
        if ('@' in result) result = result.replace(Regex("(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "<email>")
        result = result.replace(Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b"), "<ip>")
        result = result.replace(Regex("(?i)\\b[0-9a-f]{24,}\\b"), "<hex>")
        result = result.replace(Regex("(?i)\\b(serial(?:no)?|imei|imsi|android_id|token|password|secret|keyblob)\\s*[:=]\\s*[^\\s,;]+"), "$1=<redacted>")
        return result.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "?")
    }
}
