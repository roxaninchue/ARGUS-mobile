package com.blaze.assistant.stage

import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-11-19 (owner spec, feat/notch-glance): the Stage's shelf. A card that leaves the Stage (closed by the user or
 * ARGUS, pushed out by the widget cap, cleared by a layout or Reset) is kept here, newest first, so the notch's glance
 * layer can show it and put it back. Only the card's description is kept (kind, props, title); nothing keeps running.
 * Pure (StageShelfTest); StageStore records into it and saves it.
 */
data class ShelfCard(val id: String, val kind: StageKind, val props: Map<String, String> = emptyMap(), val title: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.id).put("title", title).put("at", at)
        .put("props", JSONObject().also { o -> props.forEach { (k, v) -> o.put(k, v) } })

    companion object {
        fun from(o: JSONObject): ShelfCard? {
            val kind = StageKind.of(o.optString("kind")) ?: return null
            val id = o.optString("id").ifBlank { return null }
            val p = o.optJSONObject("props")
            val props = LinkedHashMap<String, String>()
            p?.keys()?.forEach { k -> props[k] = p.optString(k) }
            return ShelfCard(id, kind, props, o.optString("title").ifBlank { kind.label }, o.optLong("at"))
        }
    }
}

object StageShelf {
    /** Cards kept (pinned ones are kept on top of this). */
    const val CAP = 6
    /** Props too big to be worth keeping (a quiz's questions are kept; a browser card needs only its url). */
    private const val MAX_PROP = 4000
    /** Kinds there is only ever one of: a newer one replaces the older on the shelf. */
    private val single = setOf(StageKind.MUSIC, StageKind.VIDEO, StageKind.BROWSER)

    /** The shelf id the glance layer and the tool use. */
    fun glanceId(card: ShelfCard): String = "stage:" + card.id

    /** A card's title as people would name it: its own title, else what it plays / shows, else its kind. */
    fun title(item: StageItem): String {
        val p = item.props
        val own = p["title"].orEmpty().ifBlank { p["deck"].orEmpty() }.ifBlank { p["query"].orEmpty() }
            .ifBlank { p["text"].orEmpty().lineSequence().firstOrNull().orEmpty() }.ifBlank { p["url"].orEmpty() }.trim()
        return (if (own.isEmpty()) item.kind.label else own).take(60)
    }

    /**
     * Cards that just left the Stage go on top. A card already there moves to the top; for music / video / browser the
     * older card of that kind goes. Over [cap], the oldest unpinned card leaves ([keep] = pinned glance ids).
     */
    fun took(shelf: List<ShelfCard>, gone: List<StageItem>, now: Long, keep: Set<String> = emptySet(), cap: Int = CAP): List<ShelfCard> {
        if (gone.isEmpty()) return shelf
        var out = shelf
        for (item in gone) {
            val card = ShelfCard(item.id, item.kind, item.props.filterValues { it.length <= MAX_PROP }, title(item), now)
            out = listOf(card) + out.filter { it.id != item.id && !(item.kind in single && it.kind == item.kind && glanceId(it) !in keep) }
        }
        return trim(out, keep, cap)
    }

    fun trim(shelf: List<ShelfCard>, keep: Set<String>, cap: Int = CAP): List<ShelfCard> {
        var free = cap
        return shelf.filter { c -> if (glanceId(c) in keep) true else if (free > 0) { free--; true } else false }
    }

    /** Take a card off the shelf (removed, or put back on the Stage). */
    fun drop(shelf: List<ShelfCard>, id: String): List<ShelfCard> {
        val bare = id.removePrefix("stage:")
        return shelf.filter { it.id != bare }
    }

    fun encode(shelf: List<ShelfCard>): String = JSONArray(shelf.map { it.toJson() }).toString()

    fun decode(s: String?): List<ShelfCard> = try {
        JSONArray(s ?: "[]").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ShelfCard::from) } }
    } catch (_: Exception) { emptyList() }
}
