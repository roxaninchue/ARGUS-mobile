package com.blaze.assistant.agent

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 0.53.4 Attach any file. Photos, PDFs and text files are read into the message as before; every other file (a zip,
 * an APK, audio, video, an office document, a binary) is copied into ARGUS's home under [DIR] and the message tells the
 * model its name, type, size and path, so it works on it with its tools. Code and config files count as text.
 * Pure: AnyFileTest.
 */
object AnyFile {
    const val DIR = "attachments"
    const val MAX_BYTES = 512L * 1024 * 1024
    const val ZIP_ROWS = 60

    private val textExt = setOf("txt", "md", "csv", "tsv", "json", "jsonl", "xml", "html", "htm", "css", "js", "mjs", "ts", "tsx", "jsx", "kt", "kts",
        "java", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "swift", "dart", "php", "sh", "bash", "zsh", "yml", "yaml", "toml", "ini", "cfg",
        "conf", "properties", "gradle", "sql", "log", "env", "gitignore", "dockerfile", "makefile", "lua", "r", "tex", "srt", "vtt", "svg")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** Text the model can read inline (besides text/ mime types). */
    fun isText(name: String, mime: String): Boolean {
        val m = mime.lowercase()
        if (m in setOf("application/json", "application/xml", "application/javascript", "application/x-sh", "application/x-yaml", "image/svg+xml")) return true
        val base = name.substringAfterLast('/').lowercase()
        return ext(name) in textExt || base in setOf("dockerfile", "makefile", "readme", "license")
    }

    fun isZip(name: String, mime: String): Boolean =
        ext(name) in setOf("zip", "apk", "aab", "jar", "mcaddon", "mcpack", "mcworld", "epub", "docx", "xlsx", "pptx") || mime.contains("zip")

    /** A free file name in [dir] for [name] ("a.zip", then "a (2).zip" …), never leaving [dir]. */
    fun target(dir: File, name: String): File {
        val safe = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_").trim().ifBlank { "file" }.take(120)
        var f = File(dir, safe); var n = 2
        val stem = safe.substringBeforeLast('.', safe); val dot = if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""
        while (f.exists()) { f = File(dir, "$stem ($n)$dot"); n++ }
        return f
    }

    /** Copies at most [max] bytes; returns the size, or -1 when the file was bigger. */
    fun copyCapped(inp: InputStream, out: OutputStream, max: Long): Long {
        val buf = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val k = inp.read(buf); if (k < 0) break
            total += k
            if (total > max) return -1
            out.write(buf, 0, k)
        }
        return total
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    fun zipListing(entries: List<Pair<String, Long>>): String {
        if (entries.isEmpty()) return "(empty archive)"
        val rows = entries.take(ZIP_ROWS).joinToString("\n") { (n, s) -> "  $n" + if (s >= 0 && !n.endsWith("/")) "  ${size(s)}" else "" }
        return "${entries.size} entr${if (entries.size == 1) "y" else "ies"}:\n$rows" + if (entries.size > ZIP_ROWS) "\n  … ${entries.size - ZIP_ROWS} more" else ""
    }

    /** What the message carries for a saved file. */
    fun note(name: String, mime: String, bytes: Long, path: String, listing: String): String =
        "File attached: $name (${mime.ifBlank { "unknown type" }}, ${size(bytes)}), saved at $path. Open it with your tools " +
            "(run_shell, file_list, file_read)." + if (listing.isNotBlank()) "\nContents: $listing" else ""
}
