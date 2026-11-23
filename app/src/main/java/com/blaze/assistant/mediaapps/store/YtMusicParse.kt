package com.blaze.assistant.mediaapps.store

import com.blaze.assistant.mediaapps.SongTitle
import org.json.JSONArray
import org.json.JSONObject

/** Pure parsing of YouTube Music search replies. */
object YtMusicParse {
    private fun text(o: JSONObject?): List<JSONObject> = o?.optJSONObject("text")?.optJSONArray("runs")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

    /** The song rows of a search reply (or a continuation), and the next-page token. */
    fun songs(json: String, source: String): StorePage {
        val out = ArrayList<StoreItem>(); var next = ""
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> {
                    x.optJSONObject("musicResponsiveListItemRenderer")?.let { r -> row(r, source)?.let { if (out.none { o -> o.id == it.id }) out += it }; return }
                    x.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf { it.isNotBlank() }?.let { next = it }
                    x.keys().forEach { walk(x.opt(it)) }
                }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(try { JSONObject(json) } catch (_: Exception) { JSONObject() })
        return StorePage(out, next)
    }

    /** One row: column 1 = the title (its watch endpoint has the video id), column 2 = "Artists • Album • 3:45". */
    fun row(r: JSONObject, source: String): StoreItem? {
        val cols = r.optJSONArray("flexColumns") ?: return null
        val c0 = text(cols.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer"))
        val c1 = text(cols.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer"))
        val id = r.optJSONObject("playlistItemData")?.optString("videoId").orEmpty().ifBlank {
            c0.firstNotNullOfOrNull { it.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { v -> v.isNotBlank() } }.orEmpty()
        }
        val title = c0.joinToString("") { it.optString("text") }.trim()
        if (id.isBlank() || title.isBlank()) return null
        // Split column 2 on the " • " runs.
        val groups = ArrayList<MutableList<JSONObject>>().apply { add(ArrayList()) }
        for (run in c1) { if (run.optString("text").trim() == "\u2022") groups.add(ArrayList()) else groups.last().add(run) }
        val segs = groups.filter { it.isNotEmpty() }
        fun kind(run: JSONObject) = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType").orEmpty()
        val clock = segs.lastOrNull()?.joinToString("") { it.optString("text") }?.trim()?.takeIf { Regex("^\\d{1,2}(:\\d{2}){1,2}$").matches(it) }.orEmpty()
        var artistSeg = segs.firstOrNull { s -> s.any { kind(it) == "MUSIC_PAGE_TYPE_ARTIST" } } ?: segs.firstOrNull()
        // A leading type label ("Song") on unfiltered searches.
        if (artistSeg != null && artistSeg.joinToString("") { it.optString("text") }.trim().equals("Song", true) && segs.size > 1) artistSeg = segs[1]
        val artist = artistSeg?.joinToString("") { it.optString("text") }?.trim().orEmpty()
        val album = segs.firstOrNull { s -> s.any { kind(it) == "MUSIC_PAGE_TYPE_ALBUM" } }?.joinToString("") { it.optString("text") }?.trim().orEmpty()
        val thumbs = r.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumb = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty().ifBlank { "https://i.ytimg.com/vi/$id/mqdefault.jpg" }
        val clean = SongTitle.clean(title, artist, fromMusic = true)
        return StoreItem(source, id, clean.title, clean.artist, StoreText.parseClock(clock).coerceAtLeast(0), clock, SongTitle.art(thumb), "",
            square = true, extra = if (album.isNotBlank()) mapOf("album" to album, "ytm" to "1") else mapOf("ytm" to "1"))
    }

    /** A plain YouTube search result as a song: title / artist cleaned, the frame kept (cropped square on screen). */
    fun fromVideo(i: StoreItem): StoreItem {
        val c = SongTitle.clean(i.title, i.author)
        return i.copy(title = c.title, author = c.artist, licence = "", square = true, views = "")
    }
}
