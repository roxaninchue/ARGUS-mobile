package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Student pack (v0.16.0): flashcards with spaced repetition, shown in the Learn view and filled by
 * the tutor skill through the `flashcards` tool. Stored in ~/learn/cards.json (Argus's home, so the
 * terminal and skills can read it too, and updates never touch it).
 *
 * Scheduling (phase 2, 2026-11-18) is FSRS 4.5: each card keeps a stability and a difficulty; a miss relearns
 * in 1 min, then 10 min; new cards are capped at [Flashcards.NEW_PER_DAY] a deck a day (Study more goes past it).
 * Cards saved under the old SM-2 scheduler migrate on load.
 *
 * 2026-11-18 redesign (owner: "just two icons, thumbs up and down"): the Learn view grades with two buttons,
 * thumbs down = [FORGOT] (Again) and thumbs up = [KNEW] (Good). Hard / Easy stay for the tool and older callers.
 * Every change the user makes (grade, edit, delete, rename) can be taken back once with [undo].
 */
data class Card(val id: String, val deck: String, val front: String, val back: String,
                val due: Long = 0L, val interval: Int = 0, val ease: Double = 2.5, val reps: Int = 0, val lapses: Int = 0,
                /** FSRS memory: days until recall falls to 90 % ([stability]), how hard the card is 1..10 ([difficulty]). 0 = unset. */
                val stability: Double = 0.0, val difficulty: Double = 0.0,
                /** Relearning step after a miss: 0 = none, 1 = back in 1 min, 2 = back in 10 min. */
                val step: Int = 0,
                /** When it was first studied ([Flashcards.NEW_PER_DAY] counts these) and last reviewed. 0 = never. */
                val first: Long = 0L, val last: Long = 0L,
                /** Where the card came from ("NET103.pdf p. 4", "lecture 3 slides"), shown under the answer. "" = unknown. */
                val source: String = "") {
    /** Never studied. A missed card (Again resets reps to 0) is not new again: it waits its step like a review. */
    val isNew: Boolean get() = reps == 0 && lapses == 0
    fun toJson(): JSONObject = JSONObject().put("id", id).put("deck", deck).put("front", front).put("back", back)
        .put("due", due).put("interval", interval).put("ease", ease).put("reps", reps).put("lapses", lapses)
        .put("s", stability).put("d", difficulty).put("step", step).put("first", first).put("last", last)
        .apply { if (source.isNotEmpty()) put("source", source) }
    companion object {
        private fun num(o: JSONObject, k: String, def: Double) = o.optDouble(k, def).let { if (it.isNaN()) def else it }
        fun from(o: JSONObject): Card {
            val c = Card(o.optString("id"), o.optString("deck", "Cards"), o.optString("front"), o.optString("back"),
                o.optLong("due"), o.optInt("interval"), num(o, "ease", 2.5), o.optInt("reps"), o.optInt("lapses"),
                num(o, "s", 0.0), num(o, "d", 0.0), o.optInt("step"), o.optLong("first"), o.optLong("last"), o.optString("source"))
            return if (o.has("s")) c else Flashcards.migrate(c)
        }
    }
}

object Flashcards {
    const val AGAIN = 0; const val HARD = 1; const val GOOD = 2; const val EASY = 3
    /** The two grades the Learn view shows (thumbs down / thumbs up). */
    const val FORGOT = AGAIN; const val KNEW = GOOD
    /** The "study everything due" pseudo-deck (no real deck can be named this: [add] trims and never stores control characters). */
    const val ALL = "\u0000all"
    private const val DAY = 86_400_000L

    // ---------------- scheduling (FSRS 4.5, phase 2 of the 2026-11-18 redesign) ----------------
    // Free Spaced Repetition Scheduler with its published default weights, 90 % target recall (so the interval
    // equals the stability in days). A miss goes through two short relearning steps (1 min, 10 min) before the
    // card is spaced out again. Grades: AGAIN 0, HARD 1, GOOD 2, EASY 3 = FSRS ratings 1..4.

    private val W = doubleArrayOf(0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031, 1.6474, 0.1367,
        1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755)
    private const val DECAY = -0.5
    private const val FACTOR = 19.0 / 81.0
    private const val MINUTE = 60_000L
    private val STEPS = longArrayOf(MINUTE, 10 * MINUTE)

    private fun d0(r: Int) = (W[4] - (r - 3) * W[5]).coerceIn(1.0, 10.0)
    /** Chance of recalling a card [days] after its last review. */
    fun recall(days: Double, stability: Double): Double = Math.pow(1 + FACTOR * days / stability.coerceAtLeast(0.01), DECAY)
    private fun nextD(d: Double, r: Int) = (W[7] * d0(4) + (1 - W[7]) * (d - W[6] * (r - 3))).coerceIn(1.0, 10.0)
    private fun recallS(d: Double, s: Double, rec: Double, r: Int) = s * (1 + Math.exp(W[8]) * (11 - d) * Math.pow(s, -W[9]) *
        (Math.exp(W[10] * (1 - rec)) - 1) * (if (r == 2) W[15] else 1.0) * (if (r == 4) W[16] else 1.0))
    private fun forgetS(d: Double, s: Double, rec: Double) =
        minOf(s, W[11] * Math.pow(d, -W[12]) * (Math.pow(s + 1, W[13]) - 1) * Math.exp(W[14] * (1 - rec)))
    private fun days(s: Double) = Math.round(s).toInt().coerceIn(1, 3650)

    /** Cards saved before FSRS (SM-2 fields only) get a stability and difficulty from their interval and ease. */
    fun migrate(c: Card): Card = when {
        c.isNew -> c
        c.reps == 0 -> c.copy(stability = 1.0, difficulty = 7.0, step = 1, last = c.due - STEPS[1])   // was waiting out a miss
        else -> c.copy(stability = c.interval.coerceAtLeast(1).toDouble(),
            difficulty = (11 - (c.ease - 1.3) / 1.2 * 5).coerceIn(1.0, 10.0), last = c.due - c.interval.coerceAtLeast(1) * DAY)
    }

    /** The next state of [c] after an answer [grade] at [now]. Pure (unit-tested). */
    fun review(c: Card, grade: Int, now: Long = System.currentTimeMillis()): Card {
        val r = grade.coerceIn(AGAIN, EASY) + 1
        val first = if (c.first == 0L) now else c.first
        val elapsed = if (c.last > 0L) (now - c.last).coerceAtLeast(0L) / DAY.toDouble() else c.interval.toDouble()
        if (c.isNew) {
            val s = W[r - 1]; val d = d0(r)
            if (r == 1) return c.copy(stability = s, difficulty = d, step = 1, due = now + STEPS[0], interval = 0, lapses = 1, first = first, last = now)
            return c.copy(stability = s, difficulty = d, step = 0, due = now + days(s) * DAY, interval = days(s), reps = 1, first = first, last = now)
        }
        if (r == 1) {   // missed: forget, then relearn
            val s = if (c.step > 0) c.stability else forgetS(c.difficulty, c.stability, recall(elapsed, c.stability))
            return c.copy(stability = s.coerceAtLeast(0.1), difficulty = nextD(c.difficulty, 1), step = 1, due = now + STEPS[0],
                interval = 0, reps = 0, lapses = c.lapses + 1, first = first, last = now)
        }
        if (c.step > 0) {   // relearning steps: Hard repeats the step, Good moves on, Easy (or the last Good) graduates
            val next = when (r) { 2 -> c.step; 3 -> c.step + 1; else -> STEPS.size + 1 }
            if (next <= STEPS.size) return c.copy(step = next, due = now + STEPS[next - 1], first = first, last = now)
            return c.copy(step = 0, due = now + days(c.stability) * DAY, interval = days(c.stability), reps = c.reps + 1, first = first, last = now)
        }
        val s = recallS(c.difficulty, c.stability, recall(elapsed, c.stability), r)
        return c.copy(stability = s, difficulty = nextD(c.difficulty, r), due = now + days(s) * DAY, interval = days(s),
            reps = c.reps + 1, first = first, last = now)
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

    /** [fresh] counts every never-studied card; [today] how many of them today's limit lets in. */
    data class Deck(val name: String, val total: Int, val due: Int, val fresh: Int, val today: Int = fresh)

    /** Cards due now plus new ones, across every deck (the "Study all" button), or in one deck. */
    fun waiting(now: Long = System.currentTimeMillis()): Int = decks(now).sumOf { it.due + it.today }

    /** When the next reviewed card in [deck] ([ALL] for any) comes due after [now], or null when none is scheduled. */
    /** New cards held back by today's limit in [deck] ([ALL] for any): the done screen's Study more. */
    fun heldBack(deck: String, now: Long = System.currentTimeMillis()): Int =
        decks(now).filter { deck == ALL || it.name == deck }.sumOf { it.fresh - it.today }

    fun nextDue(deck: String, now: Long = System.currentTimeMillis()): Long? =
        load().filter { (deck == ALL || it.deck == deck) && !it.isNew && it.due > now }.minOfOrNull { it.due }

    /** New cards a deck introduces per day (owner 2026-11-18: keep it, with Study more to go past it). */
    const val NEW_PER_DAY = 20

    /** Local midnight before [now]. */
    fun dayStart(now: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): Long =
        java.util.Calendar.getInstance(tz).apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis

    /** New cards [deck] can still introduce today. */
    fun newLeft(deck: String, now: Long = System.currentTimeMillis()): Int {
        val start = dayStart(now)
        return (NEW_PER_DAY - load().count { it.deck == deck && it.first >= start }).coerceAtLeast(0)
    }

    fun decks(now: Long = System.currentTimeMillis()): List<Deck> = load().groupBy { it.deck }.map { (n, l) ->
        l.count { it.isNew }.let { f -> Deck(n, l.size, l.count { !it.isNew && it.due <= now }, f, minOf(f, newLeft(n, now))) }
    }.sortedWith(compareByDescending<Deck> { it.due + it.fresh }.thenBy { it.name })

    /** Cards to study now in [deck]: due reviews first, then the new ones today's limit lets in ([more]: another day's worth). */
    fun queue(deck: String, now: Long = System.currentTimeMillis(), more: Boolean = false): List<Card> {
        if (deck == ALL) return load().map { it.deck }.distinct().flatMap { queue(it, now, more) }.sortedBy { if (it.isNew) 1 else 0 }
        val l = load().filter { it.deck == deck }
        return l.filter { !it.isNew && it.due <= now }.sortedBy { it.due } + l.filter { it.isNew }.take(if (more) NEW_PER_DAY else newLeft(deck, now))
    }

    /**
     * The study session's next card: the queue first, then the cards forgotten this session (thumbs down) even
     * before their 10 minutes are up, so a session never ends on a card the user just missed. [forgot] keeps
     * the order they were missed in; ids already in the queue aren't repeated.
     */
    fun next(deck: String, forgot: List<String>, now: Long = System.currentTimeMillis(), more: Boolean = false): Card? {
        queue(deck, now, more).firstOrNull()?.let { return it }
        val byId = load().associateBy { it.id }
        return forgot.firstNotNullOfOrNull { id -> byId[id]?.takeIf { deck == ALL || it.deck == deck } }
    }

    /** A deck name as stored: no control characters (so nothing can collide with [ALL]), trimmed, at most 60 chars. */
    private fun clean(name: String) = name.filterNot { it.isISOControl() }.trim().take(60)

    /** Adds cards to [deck]; [sources] (same order as [pairs], may be shorter) says where each came from. */
    fun add(deck: String, pairs: List<Pair<String, String>>, sources: List<String> = emptyList()): Int {
        val d = clean(deck).ifBlank { "Cards" }
        val have = load().filter { it.deck == d }.map { it.front.trim().lowercase() }.toMutableSet()
        val new = pairs.withIndex().filter { (_, p) -> p.first.isNotBlank() && p.second.isNotBlank() && have.add(p.first.trim().lowercase()) }
            .map { (i, p) -> Card("c" + java.util.UUID.randomUUID().toString().replace("-", "").take(12), d, p.first.trim().take(600), p.second.trim().take(1500),
                source = sources.getOrNull(i).orEmpty().trim().take(120)) }
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

    // ---------------- phase 3: typed answers and the study reminder (pure parts) ----------------

    /** What the model is asked when the user types an answer: it compares meaning, not wording. */
    fun gradePrompt(c: Card, typed: String): String =
        "Grade a flashcard answer by meaning, not wording or spelling. Start your reply with the word right or wrong, then " +
            "give one short sentence the student can learn from.\nQuestion: ${c.front}\nExpected answer: ${c.back}\nStudent's answer: ${typed.trim()}"

    /** The model's verdict from its reply: true = right, false = wrong, null = it didn't say. */
    fun verdict(reply: String): Boolean? {
        val w = reply.trim().trimStart('*', '_', '"', '\u201c').takeWhile { it.isLetter() }.lowercase()
        return when (w) { "right", "correct", "yes" -> true; "wrong", "incorrect", "no" -> false; else -> null }
    }

    /** The one sentence after the verdict word, for the card. */
    fun verdictNote(reply: String): String =
        reply.trim().trimStart('*', '_', '"', '\u201c').dropWhile { it.isLetter() }.trimStart('*', '_', '"', '\u201d', '.', ',', ':', '!', ' ', '-', '\u2014').trim().take(240)

    /** The study reminder's hour (local), and the next time it fires after [now]. */
    const val NUDGE_HOUR = 18
    fun nudgeAt(now: Long, hour: Int = NUDGE_HOUR, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        val today = dayStart(now, tz) + hour * 3_600_000L
        return if (today > now) today else java.util.Calendar.getInstance(tz).apply { timeInMillis = dayStart(now, tz); add(java.util.Calendar.DAY_OF_YEAR, 1) }.timeInMillis + hour * 3_600_000L
    }

    /** The reminder's text, or null when nothing waits (no notification then). */
    fun nudgeText(now: Long = System.currentTimeMillis()): String? {
        val d = decks(now).filter { it.due + it.today > 0 }
        val n = d.sumOf { it.due + it.today }
        if (n == 0) return null
        return if (d.size == 1) "$n card${if (n == 1) "" else "s"} waiting in ${d[0].name}" else "$n cards waiting in ${d.size} decks"
    }

    // ---------------- tool ----------------

    const val TOOL = "flashcards"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "The user's flashcards in the Learn view (spaced repetition). add: create a Learn deck or add cards to one (deck name + cards [{front, back, source}]; source says where it came from, e.g. 'NET103.pdf p. 4'; for fill-in-the-blank put ___ in the front) whenever the user wants flashcards, a quiz or something to study, then open_view view=learn; never write the cards in chat instead. " +
            "list: decks with due counts; due: cards due now in a deck (to quiz the user in chat); rename_deck (deck, to); delete_deck.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("add", "list", "due", "rename_deck", "delete_deck"))))
            .put("deck", JSONObject().put("type", "string").put("description", "Deck name, e.g. 'CIT102 loops'"))
            .put("to", JSONObject().put("type", "string").put("description", "New deck name (rename_deck)"))
            .put("cards", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("front", JSONObject().put("type", "string")).put("back", JSONObject().put("type", "string")).put("source", JSONObject().put("type", "string"))))))
            .put("required", JSONArray(listOf("action")))))

    fun run(a: JSONObject): String = when (a.optString("action")) {
        "add" -> {
            val arr = a.optJSONArray("cards") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            val pairs = items.map { it.optString("front") to it.optString("back") }
            val n = add(a.optString("deck"), pairs, items.map { it.optString("source") })
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
