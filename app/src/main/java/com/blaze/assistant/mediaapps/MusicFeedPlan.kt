package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.StoreItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.55.11: which song shelves Music shows on Home, Search and an empty Library, and how they are kept on disk.
 * Every screen opens on these (from the disk copy at once, refreshed in the background), so no page is ever empty.
 * Pure (MusicFeedPlanTest).
 */
object MusicFeedPlan {
    data class Spec(val id: String, val title: String, val query: String)
    data class Shelf(val id: String, val title: String, val items: List<StoreItem>)

    /** Refresh the shelves when the disk copy is older than this (or the plan changed, or a shelf is empty). */
    const val STALE_MS = 6L * 60 * 60 * 1000
    const val MAX_ITEMS = 20
    /** Longer than this is a mix or a full album, not a song (left out of the shelves). */
    const val MAX_SONG_SEC = 15 * 60
    const val RECENT_MAX = 30

    /** Genres for the shelves and the Search page's tiles (what the user plays comes first). */
    val genres = listOf("Afrobeats", "Amapiano", "Hip hop", "R&B", "Pop", "Gospel", "Dancehall", "Chill", "Workout", "Love songs")
    /** Styles a listening history may name that are real genres (a recurring title word only counts when it is one). */
    private val known = genres + listOf("Jazz", "Rock", "Soul", "Reggae", "Drill", "Trap", "House", "Afro house", "Lo-fi", "Lofi", "Country",
        "Rumba", "Kalindula", "Bongo flava", "Gqom", "Kizomba", "Rap", "Afropop", "Afro", "Highlife", "Bolingo", "Zouk", "EDM", "Worship")

    /**
     * The shelves, in order: "Because you listened to …" for the two artists played most, "Trending now", the
     * country's top hits, "New releases", then genres (styles from the listening history first). [country] is a
     * display name ("Zambia"), "" to leave that shelf out.
     */
    fun plan(topArtists: List<String>, styles: List<String>, country: String, year: Int, max: Int = 9): List<Spec> {
        val out = ArrayList<Spec>()
        topArtists.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(2).forEach {
            out += Spec("artist:" + it.lowercase(), "Because you listened to $it", it)
        }
        out += Spec("trending", "Trending now", "top hits $year")
        if (country.isNotBlank()) out += Spec("country", "Top hits in $country", "$country top hits $year")
        out += Spec("new", "New releases", "new songs $year")
        val liked = styles.mapNotNull { s -> known.firstOrNull { it.equals(s.trim(), true) } }
        val g = (liked + genres).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        for (x in g) { if (out.size >= max) break; out += Spec("genre:" + x.lowercase(), x, "$x hits $year") }
        return out.take(max)
    }

    /** Items fit for a shelf: songs (not hour-long mixes), each once. */
    fun songs(items: List<StoreItem>): List<StoreItem> =
        items.filter { it.durationSec == 0 || it.durationSec <= MAX_SONG_SEC }.distinctBy { it.id }.take(MAX_ITEMS)

    /** The newest play first, each song once. */
    fun pushRecent(recent: List<StoreItem>, item: StoreItem): List<StoreItem> =
        (listOf(item) + recent.filter { it.id != item.id }).take(RECENT_MAX)

    /** Whether the saved shelves should be fetched again. */
    fun stale(savedAt: Long, now: Long, saved: List<Shelf>, plan: List<Spec>): Boolean =
        saved.isEmpty() || now - savedAt > STALE_MS || plan.map { it.id } != saved.map { it.id } || saved.any { it.items.isEmpty() }

    // ---------------------------------------------------------------- disk copy

    fun item(o: JSONObject): StoreItem? {
        val id = o.optString("id"); if (id.isBlank()) return null
        val extra = o.optJSONObject("x")?.let { x -> x.keys().asSequence().associateWith { x.optString(it) } } ?: emptyMap()
        return StoreItem(o.optString("s", "youtube"), id, o.optString("t"), o.optString("a"), o.optInt("d"), o.optString("dt"), o.optString("p"),
            square = true, extra = extra)
    }

    fun json(i: StoreItem): JSONObject = JSONObject().put("s", i.source).put("id", i.id).put("t", i.title).put("a", i.author)
        .put("d", i.durationSec).put("dt", i.durationText).put("p", i.thumb).apply { if (i.extra.isNotEmpty()) put("x", JSONObject(i.extra)) }

    fun encode(savedAt: Long, shelves: List<Shelf>, recent: List<StoreItem>): String = JSONObject().put("at", savedAt)
        .put("shelves", JSONArray(shelves.map { s -> JSONObject().put("id", s.id).put("t", s.title).put("items", JSONArray(s.items.map(::json))) }))
        .put("recent", JSONArray(recent.map(::json))).toString()

    data class Saved(val at: Long, val shelves: List<Shelf>, val recent: List<StoreItem>)

    fun decode(s: String): Saved = try {
        val o = JSONObject(s)
        fun items(a: JSONArray?) = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let(::item) } } ?: emptyList()
        val sh = o.optJSONArray("shelves")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { x ->
            Shelf(x.optString("id"), x.optString("t"), items(x.optJSONArray("items"))) } } } ?: emptyList()
        Saved(o.optLong("at"), sh.filter { it.id.isNotBlank() }, items(o.optJSONArray("recent")))
    } catch (_: Exception) { Saved(0, emptyList(), emptyList()) }
}
