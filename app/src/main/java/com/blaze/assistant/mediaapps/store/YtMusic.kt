package com.blaze.assistant.mediaapps.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.55.11: song search on YouTube Music (InnerTube WEB_REMIX, the "Songs" filter, no sign-in). It gives what a music
 * app shows: the song title, the artists, the album, the length and a square cover, instead of a video title and a
 * channel. Items use the "youtube" source id, so playing, downloading and refreshing go through [YouTubeStoreSource].
 * When YouTube Music doesn't answer, [songs] falls back to the plain YouTube search with the titles cleaned
 * ([SongTitle]). Parsing is pure ([YtMusicParse], YtMusicParseTest).
 */
object YtMusic {
    private const val VERSION = "1.20251110.01.00"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    /** The "Songs" filter of a YouTube Music search. */
    const val SONGS = "EgWKAQIIAWoKEAoQCRADEAQQBRAF"

    suspend fun searchRaw(query: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX")
            .put("clientVersion", VERSION).put("hl", "en").put("gl", "US"))).put("query", query).put("params", SONGS)
        val h = URL("https://music.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            h.requestMethod = "POST"; h.doOutput = true; h.connectTimeout = 12_000; h.readTimeout = 15_000
            h.setRequestProperty("Content-Type", "application/json")
            h.setRequestProperty("User-Agent", UA)
            h.setRequestProperty("Origin", "https://music.youtube.com")
            h.setRequestProperty("X-YouTube-Client-Name", "67")
            h.setRequestProperty("X-YouTube-Client-Version", VERSION)
            h.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = h.responseCode
            val text = (if (code in 200..299) h.inputStream else h.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw StoreError("Music search answered $code", code)
            text
        } finally { h.disconnect() }
    }

    /** Songs for [query]: YouTube Music first, the plain search (cleaned) when that fails or finds nothing. */
    suspend fun songs(query: String, max: Int = 20): List<StoreItem> {
        val q = query.trim(); if (q.isEmpty()) return emptyList()
        val music = runCatching { YtMusicParse.songs(searchRaw(q), YouTubeStoreSource.id).items }.getOrDefault(emptyList())
        if (music.isNotEmpty()) return music.take(max)
        val page = LinkParse.ytSearch(LinkYt.search(q, ""), YouTubeStoreSource.id)
        return page.items.filter { it.durationSec in 1..20 * 60 }.map { YtMusicParse.fromVideo(it) }.take(max)
    }
}
