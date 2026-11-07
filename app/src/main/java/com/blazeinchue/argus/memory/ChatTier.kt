package com.blazeinchue.argus.memory

import com.blazeinchue.argus.agent.LongChat
import org.json.JSONArray

/**
 * Tiered memory, chat tier (0.49.0): what of the conversation is sent word for word.
 *
 * The working context is the rolling summary (LongChat's state note) plus the most recent turns, kept under a token
 * [budget]: ~6k tokens by default, so a small free route (Groq 8B at 6–8k tokens a minute) still fits, and scaled up
 * with the chosen route's context window when it is known. Older turns are never dropped: they fold into the
 * summary and are paged verbatim into the cache tier (doc `chat-<id>`), where cache_search / search_chat find them.
 *
 * The recent tail is also capped in tokens ([tailStart]), not only in messages, so twelve long messages can't
 * blow the budget by themselves. The newest user message and everything after it are always kept.
 *
 * Pure; ChatTierTest.
 */
object ChatTier {
    const val DEFAULT_BUDGET = 6_000
    const val MAX_BUDGET = 24_000
    /** Share of the budget the word-for-word tail may use (the summary and the memory block need the rest). */
    const val TAIL_SHARE = 70

    /** History budget in tokens for a route with [window] tokens of context (0 = unknown → [DEFAULT_BUDGET]). */
    fun budget(window: Int): Int = when {
        window <= 0 -> DEFAULT_BUDGET
        window < 16_384 -> (window * 45 / 100).coerceAtLeast(1_500)
        else -> (window / 8).coerceIn(DEFAULT_BUDGET, MAX_BUDGET)
    }

    fun tailBudget(budget: Int): Int = budget * TAIL_SHARE / 100

    /**
     * Where the word-for-word tail starts: at most [keep] messages back (LongChat.tailStart) and at most [maxTok]
     * tokens, always on a user message, and never after the newest user message.
     */
    fun tailStart(h: JSONArray, keep: Int, maxTok: Int): Int {
        val byCount = LongChat.tailStart(h, keep)
        val n = h.length()
        if (n == 0) return 0
        var lastUser = n - 1
        while (lastUser > 0 && h.optJSONObject(lastUser)?.optString("role") != "user") lastUser--
        if (maxTok <= 0) return byCount
        var i = byCount
        while (i < lastUser && LongChat.tokens(h, i) > maxTok) {
            i++
            while (i < lastUser && h.optJSONObject(i)?.optString("role") != "user") i++
        }
        return minOf(i, lastUser).coerceAtLeast(byCount)
    }

    /** The paged-out text of history[from, to): the verbatim transcript (tool output whole), for the cache tier. */
    fun pageText(h: JSONArray, from: Int, to: Int): String {
        val b = StringBuilder()
        for (i in from until minOf(to, h.length())) {
            val m = h.optJSONObject(i) ?: continue
            val c = LongChat.text(m)
            if (c.isBlank()) continue
            b.append("## turn ").append(i).append(" · ").append(m.optString("role")).append("\n\n").append(c.trim()).append("\n\n")
        }
        return b.toString()
    }

    /** Cache doc id of a chat's paged-out history. */
    fun docId(chat: String): String = "chat-" + chat.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)
}
