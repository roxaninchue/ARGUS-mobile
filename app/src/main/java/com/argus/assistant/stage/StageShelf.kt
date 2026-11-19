package com.blaze.assistant.stage

import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-11-19 (owner spec, feat/notch-glance): the Stage's shelf. A card that leaves the Stage (closed by the user or
 * ARGUS, pushed out by the widget cap, cleared by a layout or Reset) is kept here, newest first, so the notch's glance
 * layer can show it and put it back. Only the card's description is kept (kind, props, title); nothing keeps running.
 * Owner, round 2: no cap; every card is kept (glance/GlanceShelf.kt) and read a page at a time. StageShelfTest.
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
    /** Props too big to be worth keeping (a quiz's questions are kept; a browser card needs only its url). */
    private const val MAX_PROP = 4000

    /** The shelf id the glance layer and the tool use. */
    fun glanceId(card: ShelfCard): String = "stage:" + card.id

    fun bare(id: String): String = id.removePrefix("stage:")

    /** A card's title as people would name it: its own title, else what it plays / shows, else its kind. */
    fun title(item: StageItem): String {
        val p = item.props
        val own = p["title"].orEmpty().ifBlank { p["deck"].orEmpty() }.ifBlank { p["query"].orEmpty() }
            .ifBlank { p["text"].orEmpty().lineSequence().firstOrNull().orEmpty() }.ifBlank { p["url"].orEmpty() }.trim()
        return (if (own.isEmpty()) item.kind.label else own).take(60)
    }

    /** What is kept of a card that left the Stage. */
    fun card(item: StageItem, now: Long): ShelfCard = ShelfCard(item.id, item.kind, item.props.filterValues { it.length <= MAX_PROP }, title(item), now)

    /**
     * Cards that just left the Stage go on top, newest first. Owner, round 2: no cap, every card is kept as it comes;
     * only the same card (same id) closed again moves to the top instead of showing twice.
     */
    fun took(shelf: List<ShelfCard>, gone: List<StageItem>, now: Long): List<ShelfCard> {
        var out = shelf
        for (item in gone) out = listOf(card(item, now)) + out.filter { it.id != item.id }
        return out
    }

    /** Take a card off the shelf (removed, or put back on the Stage). */
    fun drop(shelf: List<ShelfCard>, id: String): List<ShelfCard> {
        val b = bare(id)
        return shelf.filter { it.id != b }
    }

    fun encode(shelf: List<ShelfCard>): String = JSONArray(shelf.map { it.toJson() }).toString()

    fun decode(s: String?): List<ShelfCard> = try {
        JSONArray(s ?: "[]").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ShelfCard::from) } }
    } catch (_: Exception) { emptyList() }
}
