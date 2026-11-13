package com.blaze.assistant.agent

import org.json.JSONArray

/**
 * fix/chat-flat-ui: the system message(s) of the newest main chat turn, held in memory only (never saved, never synced,
 * never logged), so the folded "Thinking" section can glitch the parts of the reasoning that echo them
 * ([GlitchSpans.promptEchoes]). [LlmClient.complete] notes it for main-lane turns that stream thinking.
 */
object PromptEcho {
    @Volatile private var text: String = ""
    @Volatile private var memo: Pair<String, GlitchSpans.Index>? = null

    /** The longest prompt kept (a system prompt is far below this; it only bounds memory). */
    const val MAX_CHARS = 200_000

    /** Keeps the joined contents of the system messages in [messages] (OpenAI shape); no system message = unchanged. */
    fun note(messages: JSONArray) {
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "system") continue
            val c = m.opt("content")
            val s = when (c) {
                is String -> c
                is JSONArray -> (0 until c.length()).joinToString("\n") { k -> c.optJSONObject(k)?.optString("text").orEmpty() }
                else -> ""
            }
            if (s.isNotBlank()) { if (sb.isNotEmpty()) sb.append("\n\n"); sb.append(s) }
        }
        if (sb.isNotEmpty()) set(sb.toString())
    }

    fun set(prompt: String) { text = prompt.take(MAX_CHARS) }

    fun clear() { text = ""; memo = null }

    /** The shingle index of the current prompt (built once per prompt); null when none was seen yet. */
    fun index(): GlitchSpans.Index? {
        val t = text
        if (t.isBlank()) return null
        memo?.let { (k, v) -> if (k === t || k == t) return v }
        return GlitchSpans.index(t).also { memo = t to it }
    }
}
