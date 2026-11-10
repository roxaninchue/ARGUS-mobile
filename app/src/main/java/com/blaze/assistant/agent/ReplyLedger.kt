package com.blaze.assistant.agent

/**
 * Unreleased round after 0.52.3, owed-reply ledger. Finn: "nothing checks every user message of the turn got answered".
 *
 * Every user message of a turn has an id ([RapidMessages.tag], `[m7]`) and the model starts each reply with `[re:m7]`
 * markers. At the end of the turn the ledger compares the two:
 *  - a reply with no marker at all is one answer to the whole turn: it covers every message ([uncovered] is empty);
 *  - a reply with markers covers exactly the ids it names. A reply to a later correction ("actually make it 8") covers the
 *    earlier message only when its marker names it too (`[re:m7,m8]`); otherwise the earlier one is still owed.
 * When something is owed, Agent asks the model ONCE ([nudge], no tools, short timeout) for the missing answers and appends
 * them ([merge]); if that fails too, the reply goes out as it was. At most [MAX_ASKS] per turn, never a loop.
 * Pure Kotlin, unit-tested in ReplyLedgerTest.
 */
object ReplyLedger {
    /** Follow-up asks per turn. */
    const val MAX_ASKS = 1
    /** The one follow-up call is cut off after this long (the reply then goes out as it was). */
    const val TIMEOUT_MS = 15_000L
    /** Never ask about more than this many messages at once (a long burst gets a short ask). */
    const val MAX_IDS = 6

    /**
     * The turn's user message ids ([turnIds], oldest first) that the final reply [reply], raw text with its markers,
     * leaves unanswered, oldest first. Empty when the turn has fewer than two messages or the reply has no marker at all.
     */
    fun uncovered(turnIds: Collection<Long>, reply: String): List<Long> {
        val ids = turnIds.distinct()
        if (ids.size < 2 || reply.isBlank()) return emptyList()
        val parts = RapidMessages.split(reply, ids.toSet())
        val covered = parts.flatMap { it.refs }.toSet()
        if (covered.isEmpty()) return emptyList()   // unmarked (or only unknown ids): one answer to everything
        return ids.filter { it !in covered }
    }

    /** True when the follow-up may run: something is owed, nothing was asked this turn yet. */
    fun shouldAsk(owed: List<Long>, asked: Int): Boolean = owed.isNotEmpty() && asked < MAX_ASKS

    /** The hidden user line asking for the owed answers only. */
    fun nudge(owed: List<Long>): String {
        val tags = owed.take(MAX_IDS).joinToString(", ") { "[" + RapidMessages.tag(it) + "]" }
        val first = RapidMessages.tag(owed.first())
        return "[Reply check] Your answer above doesn't answer $tags yet. Answer only those now, briefly, each starting with its " +
            "marker on its own line, like [re:$first]. If a later message already settled one, say so in one line under its marker. " +
            "Don't repeat what you already said and don't call tools."
    }

    /**
     * The follow-up answer [extra] (raw model text, reasoning already removed) ready to append, or null when it is empty.
     * Without a marker of its own it is filed under all the [owed] ids, so it still gets its quote chip.
     */
    fun accept(extra: String, owed: List<Long>): String? {
        val t = extra.trim()
        if (owed.isEmpty() || RapidMessages.display(t).isBlank()) return null
        val parts = RapidMessages.split(t, owed.toSet())
        if (parts.any { it.refs.isNotEmpty() }) return t
        return "[re:" + owed.take(MAX_IDS).joinToString(",") { RapidMessages.tag(it) } + "] " + RapidMessages.display(t)
    }

    /** The final reply with the follow-up appended (separate bubbles, because both carry markers). */
    fun merge(reply: String, extra: String): String = reply.trimEnd() + "\n\n" + extra.trim()
}
