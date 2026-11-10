package com.blaze.assistant.agent

import com.blaze.assistant.BrandName
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unreleased round after 0.52.3, pinned facts. Finn: "the user can say 'pin this' / 'never forget X' or long-press a
 * message → Pin; pinned facts are stored separately, always injected in the system prompt, never compacted".
 *
 * A pinned fact is the user's own word for something that must hold in every chat. It is kept apart from the memory
 * table (PinnedStore, Settings `pinned_facts_v1`), goes into every system prompt as one block ([block], like GoalPin's),
 * and is never part of the history, so compaction, the LongChat summary and windowing never touch it. The list is capped
 * at [MAX_FACTS] facts and about [MAX_TOKENS] tokens; a pin that doesn't fit says so plainly ([add]) and nothing is
 * dropped to make room. The "What Argus is holding" page lists them with Unpin.
 * Pure Kotlin, unit-tested in PinnedFactsTest.
 */
object PinnedFacts {
    data class Fact(val id: Long, val text: String, val at: Long)

    /** At most this many pinned facts. */
    const val MAX_FACTS = 20
    /** At most about this many tokens for all of them together (4 characters a token, as elsewhere). */
    const val MAX_TOKENS = 800
    /** One fact is at most this long. */
    const val MAX_CHARS = 400

    enum class Outcome { ADDED, SAME, EMPTY, TOO_LONG, FULL }

    /** What [add] did: the new [list] (unchanged unless [outcome] is ADDED) and the line to tell the user. */
    data class Add(val outcome: Outcome, val list: List<Fact>, val message: String)

    private val HOLDING = "What ${BrandName.NAME} is holding (/holding)"

    /** A fact as typed, tidied: no "that " lead, no wrapping quotes, one line, no final full stop. */
    fun clean(text: String): String = text.trim()
        .replace(Regex("""\s+"""), " ")
        .removePrefix("that ").removePrefix("That ")
        .trim().trim('"', '\u201c', '\u201d', '\'').trim()
        .removeSuffix(".").trim()

    fun tokens(list: List<Fact>): Int = list.sumOf { (it.text.length + 3) / 4 + 2 }

    /** Pins [text] onto [list] ([id] and [now] for the new fact). Never drops an older fact to make room. */
    fun add(list: List<Fact>, text: String, id: Long, now: Long): Add {
        val t = clean(text)
        if (t.isEmpty()) return Add(Outcome.EMPTY, list, "There's nothing to pin. Say \u201cpin: \u2026\u201d with what to keep.")
        if (t.length > MAX_CHARS) return Add(Outcome.TOO_LONG, list,
            "That's too long to pin (over $MAX_CHARS characters). Say \u201cpin: \u2026\u201d with just the part to keep.")
        if (list.any { it.text.equals(t, ignoreCase = true) }) return Add(Outcome.SAME, list, "Already pinned: $t")
        val next = list + Fact(id, t, now)
        if (list.size >= MAX_FACTS) return Add(Outcome.FULL, list,
            "Pinned facts are full ($MAX_FACTS of $MAX_FACTS). Unpin one in $HOLDING, then pin this again.")
        if (tokens(next) > MAX_TOKENS) return Add(Outcome.FULL, list,
            "Pinned facts are full (about $MAX_TOKENS tokens). Unpin one in $HOLDING, then pin this again.")
        return Add(Outcome.ADDED, next, "Pinned: $t. I'll keep it in mind in every chat; unpin it any time in $HOLDING.")
    }

    fun remove(list: List<Fact>, id: Long): List<Fact> = list.filterNot { it.id == id }

    /** The facts whose text contains every word of [query] (case-insensitive); empty for a blank query. */
    fun matching(list: List<Fact>, query: String): List<Fact> {
        val words = query.lowercase().split(Regex("""\s+""")).map { it.trim('.', ',', '"', '\'') }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return list.filter { f -> val t = f.text.lowercase(); words.all { it in t } }
    }

    /** The system-prompt block ("" when nothing is pinned). Outside the history, so nothing ever summarises or cuts it. */
    fun block(list: List<Fact>): String {
        if (list.isEmpty()) return ""
        return "\nPINNED BY THE USER (always true; keep to these in every reply, never drop or reword them): " +
            list.joinToString(" | ") { "\u201c${it.text}\u201d" } + "."
    }

    fun encode(list: List<Fact>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at)) }
    }.toString()

    fun decode(raw: String): List<Fact> {
        if (raw.isBlank()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
                val t = o.optString("text").trim()
                if (t.isEmpty()) null else Fact(o.optLong("id"), t, o.optLong("at"))
            } }.distinctBy { it.id }.take(MAX_FACTS)
        } catch (_: Exception) { emptyList() }
    }
}
