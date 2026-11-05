package com.argus.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.52.1 (gateway findings work/out/gw061-too-long.md, the root cause of "That took too long"): on the Workers Free plan
 * the gateway is killed at 10 ms CPU, and the app's agent requests were 133–141 KB (system prompt + up to 128 tool
 * schemas + whole tool results). Kills start above ~100 KB and grow with size, so a 15-step task almost never finished.
 *
 * Every request is fitted to [MAX_BYTES] (~60 KB of JSON) before it leaves the phone, in this order, stopping as soon as
 * it fits:
 *  1. tool results: the newest [KEEP_RECENT_RESULTS] keep up to [RECENT_RESULT_CHARS], older ones [OLD_RESULT_CHARS];
 *  2. tool schemas: at most [MAX_TOOLS] (relevance order as given; tools already called and load_tools / find_tool stay),
 *     then down to [MIN_TOOLS] while still over;
 *  3. older steps: the oldest assistant/tool steps after the system prompt are summarised into one line (which tools
 *     ran) until it fits; the system prompt and the newest user message always stay.
 * Long schema descriptions are cut to [DESC_CHARS] too. Pure (org.json only): RequestBudgetTest.
 */
object RequestBudget {
    const val MAX_BYTES = 60_000
    const val MAX_TOOLS = 32
    const val MIN_TOOLS = 24
    const val RECENT_RESULT_CHARS = 4_000
    const val OLD_RESULT_CHARS = 1_000
    const val KEEP_RECENT_RESULTS = 2
    const val DESC_CHARS = 600
    const val KEEP_GROUPS = 3
    private val ALWAYS = setOf("load_tools", "find_tool", "ask_user")

    data class Fit(val messages: JSONArray, val tools: JSONArray?, val bytesBefore: Int, val bytesAfter: Int, val toolsBefore: Int, val toolsAfter: Int)

    fun size(messages: JSONArray, tools: JSONArray?): Int = messages.toString().length + (tools?.toString()?.length ?: 0) + 200

    private fun nameOf(t: Any?): String = (t as? JSONObject)?.optJSONObject("function")?.optString("name").orEmpty()

    private fun copy(a: JSONArray): JSONArray = JSONArray(a.toString())

    private fun calledNames(m: JSONArray): Set<String> {
        val out = HashSet<String>()
        for (i in 0 until m.length()) {
            val o = m.optJSONObject(i) ?: continue
            if (o.optString("role") == "tool") o.optString("name").takeIf { it.isNotBlank() }?.let { out += it }
            o.optJSONArray("tool_calls")?.let { c -> for (j in 0 until c.length()) c.optJSONObject(j)?.optJSONObject("function")?.optString("name")?.let { out += it } }
        }
        return out
    }

    private fun cut(s: String, n: Int): String = if (s.length <= n) s else s.take(n) + "\n\u2026[trimmed ${s.length - n} chars to keep the request small]"

    /** Trims tool results in place: newest [KEEP_RECENT_RESULTS] to [RECENT_RESULT_CHARS], older to [OLD_RESULT_CHARS]. */
    fun trimResults(m: JSONArray) {
        val idx = (0 until m.length()).filter { m.optJSONObject(it)?.optString("role") == "tool" }
        idx.forEachIndexed { k, i ->
            val o = m.getJSONObject(i)
            val c = o.opt("content") as? String ?: return@forEachIndexed
            val limit = if (k >= idx.size - KEEP_RECENT_RESULTS) RECENT_RESULT_CHARS else OLD_RESULT_CHARS
            if (c.length > limit) o.put("content", cut(c, limit))
        }
    }

    /** The first [n] tools in their given order, keeping every tool already called and [ALWAYS] (they may push n a little). */
    fun capTools(tools: JSONArray, n: Int, keep: Set<String>): JSONArray {
        if (tools.length() <= n) return tools
        val must = (0 until tools.length()).filter { nameOf(tools.opt(it)) in keep || nameOf(tools.opt(it)) in ALWAYS }.toSet()
        val rest = (0 until tools.length()).filter { it !in must }.take((n - must.size).coerceAtLeast(0))
        val chosen = (must + rest).toSortedSet()
        return JSONArray().apply { chosen.forEach { put(tools.opt(it)) } }
    }

    private fun shortDescriptions(tools: JSONArray): JSONArray {
        val out = JSONArray(tools.toString())
        for (i in 0 until out.length()) {
            val f = out.optJSONObject(i)?.optJSONObject("function") ?: continue
            val d = f.optString("description")
            if (d.length > DESC_CHARS) f.put("description", d.take(DESC_CHARS).substringBeforeLast(". ", d.take(DESC_CHARS)) + ".")
        }
        return out
    }

    /**
     * Drops the oldest steps (whole groups: an assistant message with the tool results that answer it; older user lines
     * too) until the request fits. The system prompt, the newest user message and the newest [KEEP_GROUPS] steps always
     * stay; one note says which tools already ran.
     */
    fun summariseOld(m: JSONArray, tools: JSONArray?, max: Int): JSONArray {
        val list = (0 until m.length()).map { m.getJSONObject(it) }
        val sys = if (list.firstOrNull()?.optString("role") == "system") 1 else 0
        val lastUser = list.indexOfLast { it.optString("role") == "user" }
        // group starts: every non-tool message after the system prompt (tool results belong to the message before them)
        val starts = (sys until list.size).filter { list[it].optString("role") != "tool" }
        val protectedFrom = starts.filter { list[it].optString("role") == "assistant" }.takeLast(KEEP_GROUPS).firstOrNull() ?: list.size
        val dropped = BooleanArray(list.size)
        val ran = ArrayList<String>()
        fun build(): JSONArray = JSONArray().apply {
            for (k in 0 until sys) put(list[k])
            if (ran.isNotEmpty()) put(JSONObject().put("role", "user").put("content",
                "[Earlier steps were summarised to keep the request small. Tools that already ran: ${ran.distinct().take(30).joinToString(", ")}. Don't redo finished steps.]"))
            for (k in sys until list.size) if (!dropped[k]) put(list[k])
        }
        var out = build()
        for (st in starts) {
            if (size(out, tools) <= max) break
            if (st >= protectedFrom) break
            if (st == lastUser) continue
            var k = st
            val o = list[k]
            o.optJSONArray("tool_calls")?.let { c -> for (j in 0 until c.length()) c.optJSONObject(j)?.optJSONObject("function")?.optString("name")?.let { ran += it } }
            dropped[k] = true; k++
            while (k < list.size && list[k].optString("role") == "tool") { dropped[k] = true; k++ }
            out = build()
        }
        return out
    }

    /** Fits one request to [max] bytes (see the class doc). Never throws; on any error the input goes out as it was. */
    fun fit(messages: JSONArray, tools: JSONArray?, max: Int = MAX_BYTES): Fit {
        val before = size(messages, tools)
        val t0 = tools?.length() ?: 0
        return try {
            var m = messages
            var t = tools
            if (t != null && t.length() > MAX_TOOLS) t = capTools(t, MAX_TOOLS, calledNames(m))
            if (size(m, t) <= max) return Fit(m, t, before, size(m, t), t0, t?.length() ?: 0)
            m = copy(m); trimResults(m)
            if (size(m, t) > max && t != null) t = shortDescriptions(t)
            if (size(m, t) > max && t != null && t.length() > MIN_TOOLS) t = capTools(t, MIN_TOOLS, calledNames(m))
            if (size(m, t) > max) m = summariseOld(m, t, max)
            Fit(m, t, before, size(m, t), t0, t?.length() ?: 0)
        } catch (_: Throwable) { Fit(messages, tools, before, before, t0, t0) }
    }
}
