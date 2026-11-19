package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * 0.54.0 (harness musts #2): the model's own thinking goes back to it for the rest of the task.
 * A thinking model that calls a tool must see, on the next step, why it made that call; DeepSeek's thinking-with-tools
 * mode expects `reasoning_content` on those assistant messages, and opencode replays it for every provider
 * (packages/ai/src/protocols/openai-chat.ts). We used to strip it, so each step the model re-planned from scratch.
 * Only steps of the current task are replayed: a step is tagged with its turn, and once a new turn starts the old
 * thinking stays out of the request (it would only cost tokens).
 */
object ReasoningReplay {
    const val REASONING = "_reasoning"
    const val TURN = "_rturn"
    /** Per step; keeps a long think inside the gateway's request size limit. The tail is what led to the call. */
    const val MAX_CHARS = 12_000

    /** After a reply arrives: keep its thinking (tagged with [turn]) when it calls tools, drop it otherwise. */
    fun keep(reply: JSONObject) = keep(reply, currentTurn())

    fun keep(reply: JSONObject, turn: String?) {
        val r = reply.optString(REASONING)
        val calls = reply.optJSONArray("tool_calls")?.length() ?: 0
        if (r.isBlank() || calls == 0 || turn.isNullOrBlank()) { reply.remove(REASONING); reply.remove(TURN); return }
        reply.put(REASONING, if (r.length > MAX_CHARS) r.takeLast(MAX_CHARS) else r)
        reply.put(TURN, turn)
    }

    /** The wire field for an assistant message being sent during [turn] (null = send none). */
    fun wire(m: JSONObject, turn: String): String? {
        if (turn.isBlank() || m.optString("role") != "assistant") return null
        if (m.optString(TURN) != turn) return null
        return m.optString(REASONING).takeIf { it.isNotBlank() }
    }

    /** The current turn's id (the main chat's TurnModel), "" when none. */
    fun currentTurn(): String = try { TurnModel.current?.id.orEmpty() } catch (_: Throwable) { "" }
}
