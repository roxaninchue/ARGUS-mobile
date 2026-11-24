package com.blaze.assistant.mediaapps

import android.content.Context
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.YtMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

/**
 * 0.55.17 (Finn, 24 Nov: "give those cards cover images, and they should not be a quick button to Argus playing random
 * downloaded music claiming it's that genre"): the Browse all tiles on Music Search. Each tile is a genre or a mood with a
 * cover taken from that genre's top song on YouTube, and opens its own page of that genre's songs ([MusicGenrePage]),
 * which keeps loading as you scroll until YouTube has no more. Covers are kept on disk and refreshed weekly.
 * The pure parts ([tiles], [bigCover], [fresh]) are tested in MusicGenresTest.
 */
object MusicGenres {
    data class Tile(val name: String, val query: String, val mood: Boolean = false)

    const val COVER_MS = 7L * 24 * 60 * 60 * 1000

    /** Genres, then moods, each name once ("Chill" is a genre and a mood: one tile). */
    fun tiles(genres: List<String> = MusicFeedPlan.genres, moods: List<String> = DjPlan.Vibe.values().filter { it != DjPlan.Vibe.AUTO }.map { it.label }): List<Tile> =
        (genres.map { Tile(it, "$it songs") } + moods.map { Tile(it, "$it music", mood = true) }).distinctBy { it.name.trim().lowercase() }

    /** A YouTube Music cover at tile size (its thumbnails come as small as 60 px). */
    fun bigCover(url: String): String = url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")

    /** The cover for a genre: its first song with a picture. */
    fun coverFrom(items: List<StoreItem>): String = items.firstOrNull { it.thumb.isNotBlank() }?.thumb?.let(::bigCover).orEmpty()

    fun fresh(savedAt: Long, now: Long) = savedAt > 0 && now - savedAt < COVER_MS

    private val _covers = MutableStateFlow<Map<String, String>>(emptyMap())
    val covers: StateFlow<Map<String, String>> = _covers
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loading = false

    private fun file(ctx: Context) = File(File(ctx.filesDir, "mediaapps").apply { mkdirs() }, "genre-covers.json")

    /** Shows the saved covers at once and fetches the missing or week-old ones in the background. */
    fun open(ctx: Context) {
        val app = ctx.applicationContext
        if (loading) return
        loading = true
        scope.launch {
            try {
                val f = file(app)
                val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
                val saved = o.optJSONObject("covers") ?: JSONObject()
                val m = LinkedHashMap<String, String>(); saved.keys().forEach { k -> m[k] = saved.optString(k) }
                _covers.value = m.toMap()
                val stale = !fresh(o.optLong("at"), System.currentTimeMillis())
                var changed = false
                for (t in tiles()) {
                    if (!stale && !m[t.name].isNullOrBlank()) continue
                    val c = runCatching { withTimeout(15_000) { coverFrom(YtMusic.songs("${t.name} hits", 3)) } }.getOrDefault("")
                    if (c.isNotBlank()) { m[t.name] = c; changed = true; _covers.value = m.toMap() }
                }
                if (changed) f.writeText(JSONObject().put("at", System.currentTimeMillis()).put("covers", JSONObject(m as Map<*, *>)).toString())
            } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("MusicGenres.open", e) } finally { loading = false }
        }
    }
}
