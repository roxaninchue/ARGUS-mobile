package com.blaze.assistant.agent

/**
 * 0.54.1: a final reply the model didn't finish is shown as cut, never as a finished answer.
 *
 * Root cause (owner, 15 Nov 2026 19:31, a reply that stopped at "1. Debonairs Pizza –"): a thinking model spends its
 * reasoning tokens out of max_tokens, and chat turns ran on a small or default budget. The budget is fixed at the source
 * (LlmClient sends the model's own output limit on every call; gateway 0.9.17 passes it through). What can still end
 * early (a model that reasons past even its own limit, a stream that closes) is marked here: a visible line under the
 * text and a "Continue" chip the user taps. Nothing re-prompts the model on its own and the reply text isn't rewritten.
 *
 * [LlmClient.FINISH] / [CallState.finish] carry why the reply ended: "length" (output limit) or [DROPPED] (the stream
 * closed with neither a finish_reason nor [DONE]; Cloud runs report it as the done event's `cut`). Pure; CutReplyTest.
 */
object CutReply {
    const val DROPPED = "dropped"
    const val CHIP = "Continue"
    const val LENGTH_LINE = "\u2014 Cut off here: this reply hit the model's output limit. Tap Continue for the rest."
    const val DROPPED_LINE = "\u2014 Cut off here: the connection closed before this reply finished. Tap Continue for the rest."
    /** 0.54.2: the model ended its reply mid-sentence (e.g. it stopped at "discovery (" to call a tool and never came back). */
    const val STOPPED = "stopped"
    const val STOPPED_LINE = "\u2014 This reply stopped mid-sentence. Tap Continue for the rest."

    /** The cut this finish reason means, or null for a reply that ended properly. */
    fun reason(finish: String?): String? = when (finish?.trim()?.lowercase()) {
        "length", "max_tokens" -> "length"
        DROPPED -> DROPPED
        else -> null
    }

    /**
     * 0.54.2 (owner, 16 Nov 2026: a Gemini reply ended "discovery (" after a memory call): [STOPPED] when the reply's last
     * character opens something it never closes (an open bracket, a comma, a colon, a dash). Code blocks still open,
     * and replies ending in a finished sentence, are left alone. Marking only: the text is never changed or re-sent.
     */
    fun unfinished(text: String): String? {
        val t = text.trimEnd()
        if (t.isEmpty() || Regex("```").findAll(t).count() % 2 == 1 || t.endsWith("```")) return null
        val last = t.last()
        return if (last in "([{,:\u2013\u2014" || t.endsWith(" -") || endsOnOpener(t)) STOPPED else null
    }

    /**
     * 0.55.10 (Finn, 23 Nov: after "Thought for 3m 1s" the whole reply was "So", shown as a finished answer): the last
     * word is one that only ever starts more ("So", "and", "Let me", "the"; a sign-off word like "now" or "okay" is not one) and nothing closes the sentence after it.
     */
    private val openers = setOf("so", "and", "but", "or", "because", "if", "the", "a", "an", "to", "of", "let", "i", "i'll", "i'm", "we'll", "here's")
    fun endsOnOpener(text: String): Boolean {
        val t = text.trimEnd()
        if (t.isEmpty() || !t.last().isLetter()) return false
        val word = t.takeLastWhile { !it.isWhitespace() }.lowercase().replace('\u2019', '\'')
        if (word in openers) return true
        // Two-word openers: "Let me", "Allow me" (the last word alone, "me", also closes "tell me").
        val two = t.lowercase().replace('\u2019', '\'').split(Regex("\\s+")).takeLast(2).joinToString(" ")
        return two == "let me" || two == "allow me"
    }

    /** A reply that is only a few words and stops on an opener ("So", "Okay so", "Let me"): no answer was given yet. */
    fun stub(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && t.split(Regex("\\s+")).size <= 4 && endsOnOpener(t)
    }

    /** The one follow-up call after a [stub] reply. */
    const val STUB_NUDGE = "[${com.blaze.assistant.BrandName.NAME}] Your last reply stopped after a few words, before any answer. Write the full answer to the user now, " +
        "from the tool results above. Don't call a tool unless the answer truly needs one."

    /** The shown text with the cut line under it (unchanged when [reason] is null). */
    fun mark(text: String, reason: String?): String = when (reason) {
        null -> text
        "length" -> text.trimEnd() + "\n\n" + LENGTH_LINE
        STOPPED -> text.trimEnd() + "\n\n" + STOPPED_LINE
        else -> text.trimEnd() + "\n\n" + DROPPED_LINE
    }

    /** The reply's chips with "Continue" first when it was cut (three at most). */
    fun chips(chips: List<String>, reason: String?): List<String> =
        if (reason == null) chips else (listOf(CHIP) + chips.filterNot { it.trim().equals(CHIP, ignoreCase = true) }).take(3)
}
