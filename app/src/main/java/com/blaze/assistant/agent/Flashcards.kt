package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Student pack (v0.16.0): flashcards with spaced repetition, shown in the Learn view and filled by
 * the tutor skill through the `flashcards` tool. Stored in ~/learn/cards.json (Blaze's home, so the
 * terminal and skills can read it too, and updates never touch it).
 *
 * Scheduling is SM-2 (Anki-style): Again resets the card to 10 minutes, Hard / Good / Easy grow the
 * interval by the card's ease factor (min 1.3).
 *
 * 2026-11-18 redesign (owner: "just two icons, thumbs up and down"): the Learn view grades with two buttons,
 * thumbs down = [FORGOT] (Again) and thumbs up = [KNEW] (Good). Hard / Easy stay for the tool and older callers.
 * Every change the user makes (grade, edit, delete, rename) can be taken back once with [undo].
 */
data class Card(val id: String, val deck: String, val front: String, val back: String,
                val due: Long = 0L, val interval: Int = 0, val ease: Double = 2.5, val reps: Int = 0, val lapses: Int = 0) {
    /** Never studied. A missed card (Again resets reps to 0) is not new again: it waits its 10 minutes like a review. */
    val isNew: Boolean get() = reps == 0 && lapses == 0
    fun toJson(): JSONObject = JSONObject().put("id", id).put("deck", deck).put("front", front).put("back", back)
        .put("due", due).put("interval", interval).put("ease", ease).put("reps", reps).put("lapses", lapses)
    companion object {
        fun from(o: JSONObject) = Card(o.optString("id"), o.optString("deck", "Cards"), o.optString("front"), o.optString("back"),
            o.optLong("due"), o.optInt("interval"), o.optDouble("ease", 2.5).let { if (it.isNaN()) 2.5 else it }, o.optInt("reps"), o.optInt("lapses"))
    }
}

object Flashcards {
    const val AGAIN = 0; const val HARD = 1; const val GOOD = 2; const val EASY = 3
    /** The two grades the Learn view shows (thumbs down / thumbs up). */
    const val FORGOT = AGAIN; const val KNEW = GOOD
    /** The "study everything due" pseudo-deck (no real deck can be named this: [add] trims and never stores control characters). */
    const val ALL = "\u0000all"
    private const val DAY = 86_400_000L

    /** The next state of [c] after an answer [grade] at [now]. Pure (unit-tested). */
    fun review(c: Card, grade: Int, now: Long = System.currentTimeMillis()): Card {
        if (grade == AGAIN) return c.copy(due = now + 10 * 60_000L, interval = 0, ease = (c.ease - 0.2).coerceAtLeast(1.3), reps = 0, lapses = c.lapses + 1)
        val ease = (c.ease + when (grade) { HARD -> -0.15; EASY -> 0.15; else -> 0.0 }).coerceAtLeast(1.3)
        val interval = when {
            c.reps == 0 -> when (grade) { HARD -> 1; EASY -> 4; else -> 1 }
            c.reps == 1 -> when (grade) { HARD -> 3; EASY -> 8; else -> 6 }
            else -> (c.interval * when (grade) { HARD -> 1.2; EASY -> ease * 1.3; else -> ease }).toInt().coerceAtLeast(c.interval + 1)
        }.coerceAtMost(3650)
        return c.copy(due = now + interval * DAY, interval = interval, ease = ease, reps = c.reps + 1)
    }

    /** "now", "10 min", "3 h", "2 d", "3 mo" for a wait of [ms] (the done screen's "next cards in"). */
    fun until(ms: Long): String = when {
        ms <= 60_000L -> "now"
        ms < 3_600_000L -> "${ms / 60_000L} min"
        ms < DAY -> "${ms / 3_600_000L} h"
        ms < 31 * DAY -> "${ms / DAY} d"
        ms < 365 * DAY -> "${ms / (30 * DAY)} mo"
        else -> "%.1f y".format(java.util.Locale.US, ms / (365.0 * DAY))
    }

    /** "10 min", "1 d", "6 d", "3 mo" for the answer buttons. */
    fun label(c: Card, grade: Int, now: Long = System.currentTimeMillis()): String {
        val n = review(c, grade, now)
        val ms = n.due - now
        return when {
            ms < 3_600_000L -> "${ms / 60_000L} min"
            n.interval < 31 -> "${n.interval} d"
            n.interval < 365 -> "${n.interval / 30} mo"
            else -> "%.1f y".format(java.util.Locale.US, n.interval / 365.0)
        }
    }

    // ---------------- store ----------------

    @Volatile var fileOverride: File? = null
    private fun file(): File = fileOverride ?: File(com.blaze.assistant.terminal.Terminal.home(), "learn/cards.json")

    private val _cards = MutableStateFlow<List<Card>>(emptyList())
    val cards: StateFlow<List<Card>> = _cards
    @Volatile private var loaded = false

    @Synchronized fun load(): List<Card> {
        if (!loaded) {
            _cards.value = try {
                val f = file(); if (!f.isFile) emptyList() else {
                    val a = JSONObject(f.readText()).optJSONArray("cards") ?: JSONArray()
                    (0 until a.length()).map { Card.from(a.getJSONObject(it)) }.filter { it.id.isNotBlank() }
                }
            } catch (_: Exception) { emptyList() }
            loaded = true
        }
        return _cards.value
    }

    @Synchronized private fun save(list: List<Card>) {
        _cards.value = list
        try {
            val f = file(); f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("format", 1).put("cards", JSONArray(list.map { it.toJson() })).toString())
            tmp.renameTo(f)
        } catch (_: Exception) { }
    }

    fun reset() { loaded = false; _cards.value = emptyList(); _undo.value = "" ; undoTo = null }

    // ---------------- undo (one step) ----------------

    @Volatile private var undoTo: List<Card>? = null
    private val _undo = MutableStateFlow("")
    /** What [undo] would take back ("grade", "edit", "delete", "delete_deck", "rename"), or "" when nothing. */
    val undoable: StateFlow<String> = _undo

    @Synchronized private fun change(what: String, next: List<Card>) {
        undoTo = load(); _undo.value = what; save(next)
    }

    /** Puts the cards back as they were before the last change. True when something was undone. */
    @Synchronized fun undo(): Boolean {
        val back = undoTo ?: return false
        undoTo = null; _undo.value = ""; save(back); return true
    }

    data class Deck(val name: String, val total: Int, val due: Int, val fresh: Int)

    /** Cards due now plus new ones, across every deck (the "Study all" button), or in one deck. */
    fun waiting(now: Long = System.currentTimeMillis()): Int = decks(now).sumOf { it.due + minOf(it.fresh, NEW_PER_SESSION) }

    /** When the next reviewed card in [deck] ([ALL] for any) comes due after [now], or null when none is scheduled. */
    fun nextDue(deck: String, now: Long = System.currentTimeMillis()): Long? =
        load().filter { (deck == ALL || it.deck == deck) && !it.isNew && it.due > now }.minOfOrNull { it.due }

    const val NEW_PER_SESSION = 20

    fun decks(now: Long = System.currentTimeMillis()): List<Deck> = load().groupBy { it.deck }.map { (n, l) ->
        Deck(n, l.size, l.count { !it.isNew && it.due <= now }, l.count { it.isNew })
    }.sortedWith(compareByDescending<Deck> { it.due + it.fresh }.thenBy { it.name })

    /** Cards to study now in [deck]: due reviews first, then up to [newLimit] new ones. */
    fun queue(deck: String, now: Long = System.currentTimeMillis(), newLimit: Int = NEW_PER_SESSION): List<Card> {
        if (deck == ALL) return load().map { it.deck }.distinct().flatMap { queue(it, now, newLimit) }.sortedBy { if (it.isNew) 1 else 0 }
        val l = load().filter { it.deck == deck }
        return l.filter { !it.isNew && it.due <= now }.sortedBy { it.due } + l.filter { it.isNew }.take(newLimit)
    }

    /**
     * The study session's next card: the queue first, then the cards forgotten this session (thumbs down) even
     * before their 10 minutes are up, so a session never ends on a card the user just missed. [forgot] keeps
     * the order they were missed in; ids already in the queue aren't repeated.
     */
    fun next(deck: String, forgot: List<String>, now: Long = System.currentTimeMillis()): Card? {
        queue(deck, now).firstOrNull()?.let { return it }
        val byId = load().associateBy { it.id }
        return forgot.firstNotNullOfOrNull { id -> byId[id]?.takeIf { deck == ALL || it.deck == deck } }
    }

    /** A deck name as stored: no control characters (so nothing can collide with [ALL]), trimmed, at most 60 chars. */
    private fun clean(name: String) = name.filterNot { it.isISOControl() }.trim().take(60)

    fun add(deck: String, pairs: List<Pair<String, String>>): Int {
        val d = clean(deck).ifBlank { "Cards" }
        val have = load().filter { it.deck == d }.map { it.front.trim().lowercase() }.toMutableSet()
        val new = pairs.filter { it.first.isNotBlank() && it.second.isNotBlank() && have.add(it.first.trim().lowercase()) }
            .map { (f, b) -> Card("c" + java.util.UUID.randomUUID().toString().replace("-", "").take(12), d, f.trim().take(600), b.trim().take(1500)) }
        if (new.isNotEmpty()) save(load() + new)
        return new.size
    }

    fun answer(id: String, grade: Int, now: Long = System.currentTimeMillis()) { change("grade", load().map { if (it.id == id) review(it, grade, now) else it }) }
    fun deleteDeck(deck: String) = change("delete_deck", load().filter { it.deck != deck })
    fun deleteCard(id: String) = change("delete", load().filter { it.id != id })

    /** Rewrites a card's two sides (blank keeps the side). Its schedule stays. False when there's no such card. */
    fun edit(id: String, front: String, back: String): Boolean {
        val l = load(); if (l.none { it.id == id }) return false
        change("edit", l.map { if (it.id == id) it.copy(front = front.trim().ifBlank { it.front }.take(600), back = back.trim().ifBlank { it.back }.take(1500)) else it })
        return true
    }

    /** Renames [from] to [to]; when [to] already exists the two decks merge. Returns how many cards moved. */
    fun renameDeck(from: String, to: String): Int {
        val t = clean(to); if (t.isBlank() || t == from) return 0
        val l = load(); val n = l.count { it.deck == from }; if (n == 0) return 0
        change("rename", l.map { if (it.deck == from) it.copy(deck = t) else it })
        return n
    }

    // ---------------- tool ----------------

    const val TOOL = "flashcards"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "The user's flashcards in the Learn view (spaced repetition). add: create a Learn deck or add cards to one (deck name + cards [{front, back}]) whenever the user wants flashcards, a quiz or something to study, then open_view view=learn; never write the cards in chat instead. " +
            "list: decks with due counts; due: cards due now in a deck (to quiz the user in chat); rename_deck (deck, to); delete_deck.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("add", "list", "due", "rename_deck", "delete_deck"))))
            .put("deck", JSONObject().put("type", "string").put("description", "Deck name, e.g. 'CIT102 loops'"))
            .put("to", JSONObject().put("type", "string").put("description", "New deck name (rename_deck)"))
            .put("cards", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("front", JSONObject().put("type", "string")).put("back", JSONObject().put("type", "string"))))))
            .put("required", JSONArray(listOf("action")))))

    fun run(a: JSONObject): String = when (a.optString("action")) {
        "add" -> {
            val arr = a.optJSONArray("cards") ?: JSONArray()
            val pairs = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { it.optString("front") to it.optString("back") }
            val n = add(a.optString("deck"), pairs)
            "Added $n card${if (n == 1) "" else "s"} to \u201c${a.optString("deck").ifBlank { "Cards" }}\u201d" +
                (if (n < pairs.size) " (${pairs.size - n} skipped: empty or already there)" else "") + ". The user reviews them in the Learn view (/learn)."
        }
        "due" -> {
            val q = queue(a.optString("deck"))
            if (q.isEmpty()) "Nothing due in \u201c${a.optString("deck")}\u201d." else q.take(20).joinToString("\n") { "- ${it.front} → ${it.back}" }
        }
        "rename_deck" -> renameDeck(a.optString("deck"), a.optString("to")).let { n ->
            if (n == 0) "No deck \u201c${a.optString("deck")}\u201d to rename (or the new name was empty)." else "Renamed \u201c${a.optString("deck")}\u201d to \u201c${a.optString("to").trim()}\u201d ($n cards)." }
        "delete_deck" -> { deleteDeck(a.optString("deck")); "Deleted the deck \u201c${a.optString("deck")}\u201d." }
        else -> decks().let { d -> if (d.isEmpty()) "No flashcards yet." else d.joinToString("\n") { "${it.name}: ${it.total} cards, ${it.due} due, ${it.fresh} new" } }
    }
}
