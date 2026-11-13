package com.blaze.assistant.agent

import com.blaze.assistant.BrandName
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-11-14 harness gap: the corrective re-prompts ("[Argus check] Your reply says you did this…", "Your last reply was
 * function-call text…", the RetryGuard anchor) were put into the chat history as user messages and stayed there, so every
 * later turn re-read old scoldings and the rejected answers they followed. A good harness keeps the work (tool calls and
 * their results) and drops the scaffolding.
 *
 * [skip] says which history entries of EARLIER turns are left out of the request: an app re-prompt, and the rejected final
 * answer right before it (an assistant message with no tool calls). The history itself is never edited (its indices are
 * referenced by the long-chat summary, the clear-screen mark and the message marks); only what is sent changes. The
 * current turn keeps everything, so a re-prompt still works while it matters. Pure; HistoryViewTest.
 */
object HistoryView {
    private val N = BrandName.NAME

    /** The RetryGuard anchor that every anchored re-prompt ends with. */
    const val ANCHOR = "\n[The user's latest message: \u201c"

    private val prefixes = listOf(
        "[$N check]", "[$N] Your last reply", "[Check: you just said you can't",
        "Your last reply was function-call text", "Your last reply contained tool-call text", "Your last message was only function-call text",
        "[Autopilot] Continue with the next unfinished step", "You answered in chat, but flashcards belong", "[Quick lookup didn't settle it",
    )

    /** True for a user message the app wrote as a re-prompt (never one the user typed). */
    fun isNudge(m: JSONObject?): Boolean {
        if (m == null || m.optString("role") != "user") return false
        val c = m.opt("content") as? String ?: return false
        return c.contains(ANCHOR) || prefixes.any { c.startsWith(it) }
    }

    /** Leave history[[i]] out of the request? Only before [turnAt] (the index of this turn's first user message). */
    fun skip(history: JSONArray, i: Int, turnAt: Int): Boolean {
        if (i < 0 || i >= turnAt || i >= history.length()) return false
        val m = history.optJSONObject(i) ?: return false
        if (isNudge(m)) return true
        // The rejected answer a re-prompt followed: a plain assistant reply (no tool calls) right before a nudge.
        if (m.optString("role") == "assistant" && (m.optJSONArray("tool_calls")?.length() ?: 0) == 0 && i + 1 < turnAt)
            return isNudge(history.optJSONObject(i + 1))
        return false
    }
}
