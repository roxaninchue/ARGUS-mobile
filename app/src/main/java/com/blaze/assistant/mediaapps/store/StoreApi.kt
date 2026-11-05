package com.blaze.assistant.mediaapps.store

/**
 * Core models and interfaces for media store sources.
 */

interface StoreSource {
    val id: String
    val name: String
    val kinds: Set<String>
    val browsable: Boolean get() = false
    val userAgent: String get() = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    fun setupNeeded(): String? = null
    suspend fun search(kind: String, query: String, next: String): StorePage
    suspend fun detail(item: StoreItem, kind: String): StoreDetail
    fun choices(kind: String, d: StoreDetail): List<Choice> = emptyList()
    fun defaultChoice(kind: String, choices: List<Choice>): Choice? = choices.firstOrNull()
    suspend fun refresh(itemId: String, kind: String): StoreDetail? = null
    fun streamPick(kind: String, d: StoreDetail): List<StoreFormat> =
        defaultChoice(kind, choices(kind, d))?.let { ch -> ch.keys.mapNotNull { k -> d.formats.firstOrNull { it.key == k } } }
            ?: d.formats.take(1)
}

data class StoreItem(
    val source: String,
    val id: String,
    val title: String,
    val author: String,
    val durationSec: Int = 0,
    val durationText: String = "",
    val thumb: String = "",
    val licence: String = "",
    val licenceUrl: String = "",
    val views: String = "",
    val square: Boolean = false,
    val extra: Map<String, String> = emptyMap(),
    val downloads: String = ""
) {
    constructor(
        source: String,
        id: String,
        title: String,
        author: String,
        durationSec: Int,
        durationText: String,
        thumb: String,
        licence: String,
        licenceUrl: String,
        downloads: String,
        square: Boolean
    ) : this(
        source = source,
        id = id,
        title = title,
        author = author,
        durationSec = durationSec,
        durationText = durationText,
        thumb = thumb,
        licence = licence,
        licenceUrl = licenceUrl,
        views = "",
        square = square,
        extra = emptyMap(),
        downloads = downloads
    )
}

data class StoreDetail(
    val id: String,
    val title: String,
    val author: String,
    val durationSec: Int,
    val thumb: String,
    val formats: List<StoreFormat> = emptyList(),
    val licence: String = "",
    val licenceUrl: String = "",
    val subtitles: List<StoreSubtitle> = emptyList()
) {
    val lengthSec: Int get() = durationSec
}

data class StoreFormat(
    val key: String,
    val url: String,
    val mime: String,
    val codecs: String = "",
    val bitrate: Int = 0,
    val contentLength: Long = 0L,
    val height: Int = 0,
    val qualityLabel: String = "",
    val hasAudio: Boolean = false,
    val hasVideo: Boolean = false,
    val fps: Int = 0,
    val name: String = "",
    private val _container: String = "",
    val kbps: Int = if (bitrate > 0) bitrate / 1000 else 0,
    val userAgent: String = ""
) {
    val container: String get() = if (_container.isNotBlank()) _container else if (codecs in listOf("mp3", "mp4", "m4a", "webm", "ogg", "flac")) codecs else when {
        mime.contains("mp4") || mime.contains("m4a") -> "mp4"
        mime.contains("webm") -> "webm"
        mime.contains("ogg") -> "ogg"
        mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
        mime.contains("flac") -> "flac"
        else -> "mp4"
    }

    val muxed: Boolean get() = hasAudio && hasVideo
}

data class StorePage(
    val items: List<StoreItem>,
    val next: String = ""
)

data class Choice(
    val id: String,
    val label: String,
    val detail: String,
    val keys: List<String>,
    val ext: String,
    val bytes: Long = 0L,
    val kbps: Int = 0,
    val height: Int = 0,
    val title: String = ""
) {
    val desc: String get() = detail
}

data class StoreSubtitle(
    val id: String,
    val url: String,
    val lang: String,
    val name: String,
    val auto: Boolean = false
)

class StoreError(message: String, val code: Int = 0) : Exception(message)

object StoreText {
    fun clock(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val s = totalSec % 60
        val m = (totalSec / 60) % 60
        val h = totalSec / 3600
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun parseClock(s: String): Int {
        val parts = s.trim().split(':')
        return try {
            when (parts.size) {
                1 -> parts[0].toIntOrNull() ?: 0
                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                else -> 0
            }
        } catch (_: Exception) { 0 }
    }

    fun size(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "%.1f GB".format(gb)
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.0f KB".format(kb)
            else -> "$bytes B"
        }
    }
}

object Licence {
    fun isFree(lic: String): Boolean {
        val l = lic.lowercase()
        return l.contains("creativecommons.org") || l.contains("publicdomain") || l.contains("public domain")
    }

    fun label(lic: String): String {
        val l = lic.lowercase()
        return when {
            "publicdomain" in l || "public domain" in l -> "Public domain"
            "by-nc-sa" in l -> "CC BY-NC-SA"
            "by-nc-nd" in l -> "CC BY-NC-ND"
            "by-nc" in l -> "CC BY-NC"
            "by-nd" in l -> "CC BY-ND"
            "by-sa" in l -> "CC BY-SA"
            "/by/" in l || "-by" in l -> "CC BY"
            "zero" in l || "cc0" in l -> "CC0"
            "creativecommons" in l -> "Creative Commons"
            else -> lic.trim()
        }
    }
}
