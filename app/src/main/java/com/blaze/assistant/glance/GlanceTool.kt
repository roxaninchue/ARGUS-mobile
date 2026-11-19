package com.blaze.assistant.glance

import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-11-19 (owner spec, feat/notch-glance): ARGUS's one tool for the glance layer, with a few actions rather than a
 * tool each. Pure half (definition, step line, args -> [Glance.Op]); GlanceStore.run does it on the phone. GlanceTest.
 */
object GlanceTool {
    const val NAME = "glance"

    fun def(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", NAME)
        .put("description", "The glance layer: what the user sees over the softly blurred page when they tap the top notch " +
            "(a clock, the next reminder, the task in progress, and Stage cards that were taken off the Stage). " +
            "list = one page of it, newest first, with ids (offset / limit; the answer says where the next page starts); pin / unpin = keep an item near the top; remove = take an item off " +
            "(a Stage card is forgotten; the clock, reminder or task stays hidden until pinned again); " +
            "restore = put a Stage card back on the Stage.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(Glance.Op.entries.map { it.name.lowercase() })))
            .put("id", JSONObject().put("type", "string").put("description", "item id from list (clock, reminder, task, stage:<card id>) or its title"))
            .put("offset", JSONObject().put("type", "integer").put("description", "list: skip this many (default 0)"))
            .put("limit", JSONObject().put("type", "integer").put("description", "list: at most this many (default ${Glance.LIST_DEFAULT}, max ${Glance.LIST_MAX})")))
            .put("required", JSONArray(listOf("action")))))

    /** The step line in the chat while it runs. */
    fun step(a: JSONObject): String = when (Glance.Op.of(a.optString("action"))) {
        Glance.Op.LIST, null -> "Checking the glance layer"
        Glance.Op.RESTORE -> "Putting a card back on the Stage"
        else -> "Arranging the glance layer"
    }

    /** Args -> (op, id), or null + the answer for a bad action. */
    fun parse(a: JSONObject): Pair<Pair<Glance.Op, String>?, String> {
        val op = Glance.Op.of(a.optString("action")) ?: return null to
            "Unknown action \u201c${a.optString("action")}\u201d. Actions: ${Glance.Op.entries.joinToString(" | ") { it.name.lowercase() }}."
        return (op to a.optString("id").trim()) to ""
    }

    /** `list`'s page: offset (>= 0) and limit (1..[Glance.LIST_MAX], default [Glance.LIST_DEFAULT]); bad values fall back. */
    fun paging(a: JSONObject): Pair<Int, Int> {
        val off = a.optInt("offset", 0).coerceAtLeast(0)
        val lim = a.optInt("limit", Glance.LIST_DEFAULT).let { if (it <= 0) Glance.LIST_DEFAULT else it.coerceAtMost(Glance.LIST_MAX) }
        return off to lim
    }
}
