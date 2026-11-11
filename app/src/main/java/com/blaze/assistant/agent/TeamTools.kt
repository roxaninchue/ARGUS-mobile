package com.blaze.assistant.agent

import org.json.JSONArray

/** P0-AG-01: Android glue for [TeamHooks]: a bound chat's tool list keeps only the bot's subset (load_tools always stays). */
object TeamTools {
    fun keep(chatId: String, tools: JSONArray): JSONArray {
        if (!TeamHooks.isBound(chatId)) return tools
        val out = JSONArray()
        for (i in 0 until tools.length()) tools.optJSONObject(i)?.let { t ->
            val n = t.optJSONObject("function")?.optString("name").orEmpty()
            if (n == "load_tools" || TeamHooks.allows(chatId, n)) out.put(t)
        }
        return out
    }
}
