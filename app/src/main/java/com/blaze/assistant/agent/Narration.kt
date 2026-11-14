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
}
