package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.53.1 (owner, 13 Nov 2026: a picked Qwen answered "use your clock app" although ARGUS has a schedule tool). Cause: the
 * request said `blaze_tools: "auto"`, and the gateway's trimTools (0.6.9) drops every tool schema when the last user text
 * has none of its ACTION words. "what time is it in Tokyo", "wake me at 6", "who won last night", "is it going to rain"
 * reached the model with no tools at all, so no web_search and no schedule.
 *
 * The full lane only runs turns that are tool-shaped or not plain chat (ChatLane answers small talk with no tools), so the
 * gateway may trim only when nothing is named, no model is picked by hand, and the last message is plain small talk.
 * Every other call carries the full tool set. Pure; ToolWireTest.
 */
object ToolWire {
    /** The text of the last message when it is the user's, else null (a tool result or an assistant step). */
    fun lastUser(messages: JSONArray): String? {
        val last = messages.optJSONObject(messages.length() - 1) ?: return null
        if (last.optString("role") != "user") return null
        return when (val c = last.opt("content")) {
            is String -> c
            is JSONArray -> buildString { for (i in 0 until c.length()) (c.opt(i) as? JSONObject)?.takeIf { it.optString("type") == "text" }?.let { append(it.optString("text")) } }
            else -> null
        }
    }

    /** May this call say `blaze_tools: "auto"` (let the gateway drop the schemas)? [manualPick] = a model picked by hand. */
    fun mayTrim(messages: JSONArray, choice: Any, manualPick: Boolean): Boolean {
        if (choice != "auto" || manualPick) return false
        val t = lastUser(messages)?.trim() ?: return false
        if (t.isEmpty() || t.startsWith("[")) return false
        return SmallTalk.chitChat(t) || SmallTalk.chitChat(t.replace(greetTail, ""))
    }

    /** "hi there", "hello again", "hey guys": a greeting with a filler word SmallTalk's name list doesn't carry. */
    private val greetTail = Regex("""(?i)\s+(?:there|again|everyone|everybody|all|guys|you)(?=[\s!?.,~]*$)""")
}
