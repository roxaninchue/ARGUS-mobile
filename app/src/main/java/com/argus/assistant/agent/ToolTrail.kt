package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Milestone 4 (expandable tool rows): what each tool call of the main chat gave back, so a call row can open to its
 * output. The chat's step messages already carry the call ([ChatMessage.meta] = [Friendly.exact]); this keeps the
 * results beside them, in memory only (a restarted app shows its calls without output), keyed by chat and by that
 * same exact text. The loop reports a result from `runTool` (one existing line in Agent.kt); [output] is a lambda so
 * nothing is scrubbed or copied while the `tool_rows` setting is off.
 *
 * Pure Kotlin (ToolTrailTest). Bounded: [PER_CHAT] results per chat, [CHATS] chats, each output clipped to [OUTPUT_MAX].
 */
object ToolTrail {
    data class Result(val exact: String, val output: String, val failed: Boolean, val at: Long)

    const val PER_CHAT = 200
    const val CHATS = 6
    const val OUTPUT_MAX = 4_000

    /** Off = record nothing (the `tool_rows` setting; the app sets it at start and when the switch moves). */
    @Volatile var on: Boolean = true

    private val _results = MutableStateFlow<Map<String, List<Result>>>(emptyMap())
    /** Chat id → its recorded results, oldest first. */
    val results: StateFlow<Map<String, List<Result>>> = _results.asStateFlow()

    /** A call in [chatId] finished. [exact] = [Friendly.exact] of the call; [output] is read only when [on]. */
    fun result(chatId: String, exact: String, at: Long = System.currentTimeMillis(), output: () -> String) {
        if (!on || chatId.isBlank()) return
        val out = try { output() } catch (_: Throwable) { return }
        val r = Result(exact, clip(out), failed(out), at)
        synchronized(this) {
            val cur = _results.value
            val list = (cur[chatId].orEmpty() + r).takeLast(PER_CHAT)
            val next = LinkedHashMap<String, List<Result>>()
            for ((k, v) in cur) if (k != chatId) next[k] = v
            next[chatId] = list
            while (next.size > CHATS) next.remove(next.keys.first())
            _results.value = next
        }
    }

    fun clear(chatId: String? = null) = synchronized(this) {
        _results.value = if (chatId == null) emptyMap() else _results.value - chatId
    }

    fun of(chatId: String): List<Result> = _results.value[chatId].orEmpty()

    fun clip(s: String): String = if (s.length > OUTPUT_MAX) s.take(OUTPUT_MAX) + "\u2026" else s

    /** A result the loop itself marks as an error or a refusal. */
    fun failed(out: String): Boolean {
        val t = out.trimStart()
        return t.startsWith("Error", ignoreCase = true) || t.startsWith("Failed", ignoreCase = true) ||
            t.startsWith("The user did NOT approve") || t.startsWith("Refused", ignoreCase = true)
    }
}
