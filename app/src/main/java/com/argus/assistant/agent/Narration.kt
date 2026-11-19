package com.argus.assistant.agent

/**
 * 0.53.3 (owner: "like Claude models I want tools and answers coming together, final reply at the end"): the words a model
 * writes beside its tool calls ("I'll check your Gmail first.") are kept as a step message with [META], so the chat draws
 * them as text between the turn's tool groups instead of folding them into a pill. Being a step, it is never sent back to
 * the model as an assistant reply and never spoken.
 */
object Narration {
    const val META = "narr:"
    fun isNarration(meta: String): Boolean = meta == META

    private val selfTalkRe = Regex("^(let me|let's|i need to|i'll now|i will now|now i'll|now i will|i should|i must|i have to)\\b", RegexOption.IGNORE_CASE)

    /**
     * 0.53.6: the model talking to itself beside a tool call ("Let me write a complete landing page and pass it in properly",
     * "I need to pass the actual content") is not shown as a chat line; the step pill already says what is happening.
     */
    fun selfTalk(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 240) return false
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.isNotEmpty() && lines.all { selfTalkRe.containsMatchIn(it) }
    }
}
