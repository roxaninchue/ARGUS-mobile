package com.blaze.assistant.agent

/**
 * 0.52.1 (owner: "long work must not die"): a turn that is about to hit its time budget (TurnDeadline), or whose model
 * step stayed slow after its retries, is not ended with "That took too long". Once it has done real work it is moved
 * into a background agent (BgAgents) with a self-contained brief (the request, what is already done, the newest
 * results), and the chat stays free. Pure Kotlin; HandoffTest.
 */
object Handoff {
    /** Hand off when less than this is left of the turn's budget. */
    const val MARGIN_MS = 60_000L
    /** Slow model steps: tries before a step counts as failed (the first + heartbeat retries). */
    const val STEP_TRIES = 3
    /** Wait between heartbeat retries of a slow step. */
    const val RETRY_GAP_MS = 2_000L

    data class Turn(
        val lead: Boolean,            // the main chat (not a background agent, not a specialist run)
        val private: Boolean,         // private chats are never written to disk, so never handed off
        val toolsRun: Int,            // tools that ran this turn
        val handedOff: Boolean,       // already handed off once this turn
    )

    /** Is this turn worth carrying on in the background (it did real work and can be moved)? */
    fun movable(t: Turn): Boolean = t.lead && !t.private && !t.handedOff && t.toolsRun >= 1

    /** Near its end ([now] within [MARGIN_MS] of [end]) and movable. */
    fun nearEnd(t: Turn, now: Long, end: Long): Boolean = movable(t) && end != Long.MAX_VALUE && now >= end - MARGIN_MS

    /** A model step failed by time (silence, step budget, gateway timeout), as opposed to sign-in, credits or a 4xx. */
    fun slowFailure(message: String?, code: Int = 0): Boolean {
        if (code in 400..499 && code != 408 && code != 429) return false
        val m = message.orEmpty().lowercase()
        return listOf("sent nothing", "took too long", "minute limit", "timed out", "timeout", "nothing shown in time", "1102", "1101", "busy")
            .any { it in m } || code == 408 || code == 503 || code == 504
    }

    /**
     * The background agent's brief: it doesn't see the chat, so everything it needs is here. Bounded (≤ ~7 KB) so its
     * own first request stays small.
     */
    fun brief(request: String, goal: String?, done: List<String>, results: List<String>): String = buildString {
        append("Carry on with this task the main chat started; it ran out of time there. Don't redo what is already done; check it if unsure, then finish.\n")
        append("User's request: ").append(request.trim().take(1200)).append('\n')
        if (!goal.isNullOrBlank() && goal.trim() != request.trim()) append("Goal: ").append(goal.trim().take(600)).append('\n')
        val steps = done.map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeLast(20)
        if (steps.isNotEmpty()) append("Already done: ").append(steps.joinToString("; ").take(1500)).append('\n')
        val rs = results.filter { it.isNotBlank() }.takeLast(3)
        if (rs.isNotEmpty()) {
            append("Newest results so far:\n")
            rs.forEach { append("- ").append(it.replace(Regex("\\s+"), " ").take(1200)).append('\n') }
        }
        append("When finished, reply with the result only.")
    }

    /** A 2-5 word pill title from the request. */
    fun title(request: String): String = request.trim().replace(Regex("\\s+"), " ").split(' ').take(5).joinToString(" ").take(40)
}
