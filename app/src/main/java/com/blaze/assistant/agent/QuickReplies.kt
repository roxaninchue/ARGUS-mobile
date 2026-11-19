package com.blaze.assistant.agent

/**
 * 0.45.24: the chips under a reply are quick replies the user would tap next (an answer to a question Blaze just asked,
 * or the logical next action), never a mirror of what the user just said. Seen on a phone: "plan a trip to Livingstone"
 * came back with "Plan a trip to Livingstone" / "Plan my Livingstone trip" as chips.
 *
 * [filter] drops a chip whose words overlap the user's message too much (Jaccard of normalized tokens above
 * [ECHO], or every word of the chip already in the message), and a chip too close to an earlier chip in the same row.
 * Nothing is better than an echo, so the row can end up empty. App-made chips ([KEEP]) always stay. Pure (QuickRepliesTest).
 */
object QuickReplies {
    const val ECHO = 0.6
    const val TWIN = 0.6

    /** Chips the app adds itself (Try again, Continue where you stopped): never filtered. */
    val KEEP = setOf(NudgePolicy.TRY_AGAIN, StopResume.CHIP)

    /** The prompt line asking the model for quick replies. */
    const val RULE = "At the very end of each final answer (never when calling tools), add one last line spelled exactly " +
        FollowUpMarker.MARK + " (key follow-ups, | between items) with up to three quick replies the user would tap next " +
        "(max 6 words each): short answers to a question you just asked " +
        "(\"Yes, book it\", \"Tomorrow morning\"), or the logical next step after your answer. Never repeat or rephrase the user's message, " +
        "never restate your answer, and leave the line out when nothing useful fits."

    private val STOP = setOf("a", "an", "the", "to", "of", "and", "or", "for", "in", "on", "at", "my", "me", "i", "you", "your",
        "is", "are", "it", "this", "that", "with", "can", "could", "please", "pls", "blaze", "argus", "do", "be", "some", "about", "just")

    /** Lowercase words without punctuation, stop words and a trailing plural "s". */
    fun tokens(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}']+").findAll(s.lowercase()).map { it.value.trim('\'') }
            .filter { it.isNotEmpty() && it !in STOP }
            .map { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
            .toSet()

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val inter = a.count { it in b }
        val union = a.size + b.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /** True when [chip] mostly says what [user] already said. */
    fun echoes(chip: String, user: String): Boolean {
        val c = tokens(chip)
        val u = tokens(user)
        if (c.isEmpty() || u.isEmpty()) return false
        if (jaccard(c, u) > ECHO) return true
        // Every content word of a 2+ word chip is already in the message ("Livingstone trip" after "plan a trip to Livingstone").
        return c.size >= 2 && u.containsAll(c)
    }

    /** The chips worth showing after [user] said something. */
    fun filter(chips: List<String>, user: String): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableListOf<Set<String>>()
        for (raw in chips) {
            val chip = raw.trim()
            if (chip.isEmpty()) continue
            if (chip in KEEP) { out += chip; continue }
            if (echoes(chip, user)) continue
            val t = tokens(chip)
            if (out.any { it.equals(chip, ignoreCase = true) }) continue
            if (t.isNotEmpty() && seen.any { jaccard(it, t) > TWIN }) continue
            out += chip
            seen += t
        }
        return out
    }
}

/**
 * 2026-11-19 (Finn's phone): the model ended a reply with "<<follow-offs: Turn on calendar access | Make tonight 2 hours |
 * Start a 25-min timer now>>" and the whole marker showed in the bubble, because only "follow-ups" spellings were read.
 * [split] now reads any near-miss key (follow-offs, followups, follow ups, follow-up, suggestions, quick replies, chips;
 * any case, optional spaces, "<" or "<<", a stray "/"), items split by | or ;, and a marker the model never closed
 * (no ">>" at the end of the final reply). Every other <<word>> / <<word: …>> control marker is cut from the text too
 * (outside ``` code), and while streaming a half-typed "<<…" at the end waits instead of flashing. Pure (QuickRepliesTest).
 */
object FollowUpMarker {
    /** The one spelling the prompt asks for (QuickReplies.RULE). */
    const val MARK = "<<follow-ups: first | second | third>>"

    private const val KEY = "(?:follow[\\s_-]*[a-z]{0,8}|suggest[a-z]{0,6}(?:[\\s_-]*(?:replies|chips|actions))?|quick[\\s_-]*repl[a-z]{0,4}|chips?)"
    /** A follow-up marker: group 1 the items, group 2 the closing ">"/">>" (empty when it runs to the end of the text). */
    val ANY = Regex("<{1,2}\\s*/?\\s*$KEY\\s*:([^>]*)(>{1,2}|$)", RegexOption.IGNORE_CASE)
    /** Any other closed control marker: <<done>>, <<status: x>>, <<follow up on this: …>>. */
    private val CONTROL = Regex("(?<!<)<</?[a-z][a-z0-9_-]*(?:[ \\t]+[a-z0-9_-]+){0,4}\\s*(?::[^<>\\n]*)?>>", RegexOption.IGNORE_CASE)
    /** An unclosed "<<word: …" running to the end of the text. */
    private val OPEN_TAIL = Regex("^<</?[a-z][a-z0-9_-]*(?:[ \\t]+[a-z0-9_-]+){0,4}\\s*:[^<>\\n]*$", RegexOption.IGNORE_CASE)
    /** A half-typed marker head at the end: "<", "<<", "<</", "<<sugg", "<<some_word". */
    private val HEAD = Regex("^<{1,2}\\s*/?\\s*([a-z][a-z0-9 _-]{0,30})?$", RegexOption.IGNORE_CASE)
    private val KNOWN = listOf("follow-ups", "followups", "follow ups", "follow_ups", "suggestions", "suggested replies", "quick replies", "chips")
    private val FOLLOWISH = Regex("^follow[\\s_-]*[a-z]{0,8}\\s*$", RegexOption.IGNORE_CASE)

    data class Result(val text: String, val chips: List<String>)

    /** [text] without any marker, and the chips its follow-up marker carried (unfiltered, in order). */
    fun split(text: String, final: Boolean): Result {
        if (!text.contains('<')) return Result(text, emptyList())
        var t = text
        var chips = emptyList<String>()
        val m = ANY.find(t)
        if (m != null) {
            val body = m.groupValues[1]
            if (m.groupValues[2].isNotEmpty() || (final && body.isNotBlank())) chips = items(body)
            t = t.substring(0, m.range.first)
        }
        t = stripControl(t)
        return Result(stripTail(t, final), chips)
    }

    /** "A | B | C" or "A; B; C" as chips. */
    fun items(body: String): List<String> {
        val sep = if (body.contains('|')) '|' else ';'
        return body.split(sep).map { it.trim().trim('"', '-', '\u2022', ' ', '<', '>', '/') }.filter { it.isNotEmpty() }
    }

    private fun inCode(t: String, at: Int): Boolean = Regex("```").findAll(t.substring(0, at)).count() % 2 == 1

    private fun stripControl(t: String): String {
        if (!t.contains("<<")) return t
        var out = t
        for (c in CONTROL.findAll(t).toList().asReversed()) if (!inCode(t, c.range.first))
            out = out.removeRange(c.range)
        return out
    }

    private fun stripTail(t: String, final: Boolean): String {
        val cut = t.lastIndexOf('<')
        if (cut < 0 || inCode(t, cut)) return t
        val tail = t.substring(cut).trimEnd()
        val start = if (cut > 0 && t[cut - 1] == '<') cut - 1 else cut
        val tail2 = t.substring(start).trimEnd()
        if (OPEN_TAIL.matches(tail2)) return t.substring(0, start)
        val h = HEAD.matchEntire(tail2) ?: HEAD.matchEntire(tail) ?: return t
        val from = if (h.value == tail2) start else cut
        val word = h.groupValues[1].trim().lowercase()
        val known = word.isEmpty() || KNOWN.any { it.startsWith(word) } || FOLLOWISH.matches(word)
        val unknownWhileStreaming = !final && h.value.startsWith("<<") && word.isNotEmpty()
        return if (known || unknownWhileStreaming) t.substring(0, from) else t
    }
}
