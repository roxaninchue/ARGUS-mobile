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

    /** The cut this finish reason means, or null for a reply that ended properly. */
    fun reason(finish: String?): String? = when (finish?.trim()?.lowercase()) {
        "length", "max_tokens" -> "length"
        DROPPED -> DROPPED
        else -> null
    }

    /** The shown text with the cut line under it (unchanged when [reason] is null). */
    fun mark(text: String, reason: String?): String = when (reason) {
        null -> text
        "length" -> text.trimEnd() + "\n\n" + LENGTH_LINE
        else -> text.trimEnd() + "\n\n" + DROPPED_LINE
    }

    /** The reply's chips with "Continue" first when it was cut (three at most). */
    fun chips(chips: List<String>, reason: String?): List<String> =
        if (reason == null) chips else (listOf(CHIP) + chips.filterNot { it.trim().equals(CHIP, ignoreCase = true) }).take(3)
}
