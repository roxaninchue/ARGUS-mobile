package com.argus.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Owner, 15 Nov 2026 ("I want the thought to stream in"): what one streamed `delta` (or a whole non-streamed `message`)
 * carries as reasoning and as reply text, whatever shape the provider uses. Pure (StreamDeltaTest).
 *
 *  - `reasoning_content` (DeepSeek, vLLM ≤ 0.11.0, many OpenAI-compatible routes) or `reasoning` (OpenRouter, vLLM 0.11.1+);
 *  - Mistral's reasoning models: `content` turns into a LIST of chunks while they think,
 *    `[{"type":"thinking","thinking":[{"type":"text","text":"…"}]}]`, then a list with the closing thinking chunk and the
 *    first `{"type":"text","text":"…"}`, then plain strings (docs.mistral.ai/inference/conversations/reasoning). Before, a
 *    list was read with optString and its raw JSON could reach the reply;
 *  - `<think>…</think>` written inside the text itself stays text here: [ReplyParts.split] moves it into the fold.
 */
object StreamDelta {
    data class Part(val think: String, val text: String)

    private val EMPTY = Part("", "")

    /** The reasoning and the reply text in one streamed delta ("" = none of that kind). */
    fun of(delta: JSONObject?): Part {
        if (delta == null) return EMPTY
        val think = StringBuilder()
        for (k in REASONING_KEYS) {
            if (!delta.has(k) || delta.isNull(k)) continue
            val v = delta.opt(k)
            val s = if (v is String) v else chunkText(v)
            if (s.isNotEmpty()) { think.append(s); break }
        }
        val text = StringBuilder()
        if (delta.has("content") && !delta.isNull("content")) when (val c = delta.opt("content")) {
            is String -> text.append(c)
            is JSONArray -> chunks(c, think, text)
            is JSONObject -> chunks(JSONArray().put(c), think, text)
        }
        return if (think.isEmpty() && text.isEmpty()) EMPTY else Part(think.toString(), text.toString())
    }

    /**
     * A non-streamed assistant message whose `content` is a chunk list (Mistral) becomes a plain string, its thinking
     * returned for `_reasoning`. A message with string content is left as it is ("" returned).
     */
    fun flatten(message: JSONObject): String {
        val c = message.opt("content") as? JSONArray ?: return ""
        val think = StringBuilder(); val text = StringBuilder()
        chunks(c, think, text)
        message.put("content", text.toString())
        return think.toString()
    }

    private val REASONING_KEYS = listOf("reasoning_content", "reasoning")

    private fun chunks(arr: JSONArray, think: StringBuilder, text: StringBuilder) {
        for (i in 0 until arr.length()) {
            val o = arr.opt(i)
            if (o is String) { text.append(o); continue }
            if (o !is JSONObject) continue
            when (o.optString("type")) {
                "thinking", "reasoning", "thought" -> think.append(chunkText(o.opt("thinking") ?: o.opt("reasoning") ?: o.opt("text")))
                "text", "" -> text.append(o.optString("text", ""))
            }
        }
    }

    /** Text out of a value that may be a string, a {text} object or a list of them. */
    private fun chunkText(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is String -> v
        is JSONObject -> v.optString("text", "")
        is JSONArray -> buildString { for (i in 0 until v.length()) append(chunkText(v.opt(i))) }
        else -> ""
    }
}
