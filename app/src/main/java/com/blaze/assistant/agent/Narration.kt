package com.blaze.assistant.agent

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

    /** 0.55.9: the longest narration line shown in the chat; anything longer is the model thinking aloud. */
    const val MAX_SHOWN = 200
    private val toolNameRe = Regex("\\b[a-z][a-z0-9]*_[a-z0-9_]+\\b")
    private val aboutUserRe = Regex("\\b(the user|user's|ask_user|autopilot)\\b", RegexOption.IGNORE_CASE)

    /**
     * 0.55.9 (Finn: "during some long tasks the model spews out its reasoning"): models that don't use a reasoning channel
     * write their whole deliberation beside each tool call ("The song titles aren't matching my queries... let me try the
     * mixes/play_list route"). Narration stays a chat line only when it reads like a status a person would send: one
     * short paragraph (at most [MAX_SHOWN] chars, two sentences), no tool identifiers, not about "the user" in the third
     * person, not self-talk. Everything else moves into the turn's Thoughts fold, so nothing is lost and the chat stays clean.
     * This judges the SHAPE of text already written (a display filter), never routes a message: ModelFirstTest's rule holds.
     */
    fun shows(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_SHOWN || selfTalk(t)) return false
        if (t.lines().count { it.isNotBlank() } > 1) return false
        if (Regex("[.!?](\\s|$)").findAll(t).count() > 2) return false
        if (toolNameRe.containsMatchIn(t.replace(Regex("\\S*/\\S*"), ""))) return false
        return !aboutUserRe.containsMatchIn(t)
    }

    /** [p] with narration that isn't a status line moved from the text into the thinking (shown in the Thoughts fold). */
    fun gate(p: ReplyParts.Parts): ReplyParts.Parts =
        if (p.text.isBlank() || shows(p.text)) p
        else p.copy(text = "", thinking = listOf(p.thinking, p.text.trim()).filter { it.isNotBlank() }.joinToString("\n\n"))

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
