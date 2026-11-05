package com.argus.assistant.agent

import org.json.JSONArray

/**
 * 0.46.3 (A3; incident 26 Oct 2026: the gateway Worker was killed by Cloudflare error 1102, the free plan's CPU limit, so its
 * in-request Nous reserve never ran and the phone's reserve retry sent the same heavy request to the same Worker). Once a
 * gateway call looks like a Worker kill, the next gateway call is slim: the last [KEEP] messages, the tools those messages
 * used plus at most [MAX_TOOLS] others, after a short pause. Worker CPU scales with the body size. Pure (ReserveBodyTest).
 */
object ReserveBody {
    const val KEEP = 6
    const val MAX_TOOLS = 24
    /** 0.52.1 (gw061): a killed Worker is retried at once (the kill is CPU-probabilistic; a second, slimmer try usually passes). */
    const val PAUSE_MS = 0L

    /** A Cloudflare Worker kill (1102 / "exceeded" / a non-JSON 503 or 0), not the gateway's own JSON "models busy". */
    fun looksLikeWorkerKill(code: Int, message: String): Boolean {
        if (code != 503 && code != 0 && code != 500 && code != 502) return false
        val t = message.trim()
        if (t.startsWith("{") && t.contains("\"error\"")) return t.contains("1102")
        val l = t.lowercase()
        // 0.52.1 (gw061): Cloudflare 1101 (worker threw) and 1102 (CPU limit) and any HTML 503 page are a killed Worker.
        return "1102" in l || "1101" in l || "exceeded" in l || "worker" in l || "<html" in l || "<!doctype" in l ||
            (t.isNotEmpty() && !t.startsWith("{") && code == 503 && "busy" !in l)
    }

    /** The tools the last [KEEP] messages called, then others up to [max] in their original order. */
    fun slimForReserve(tools: JSONArray?, msgs: JSONArray, max: Int = MAX_TOOLS): JSONArray? {
        if (tools == null) return null
        val used = HashSet<String>()
        for (i in maxOf(0, msgs.length() - KEEP) until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            if (m.optString("role") == "tool") used += m.optString("name")
            val c = m.optJSONArray("tool_calls") ?: continue
            for (j in 0 until c.length()) used += c.optJSONObject(j)?.optJSONObject("function")?.optString("name").orEmpty()
        }
        fun name(i: Int) = tools.optJSONObject(i)?.optJSONObject("function")?.optString("name").orEmpty()
        val out = JSONArray()
        val idx = (0 until tools.length()).toList()
        idx.filter { name(it) in used }.forEach { out.put(tools.get(it)) }
        idx.filter { name(it) !in used }.take(max).forEach { out.put(tools.get(it)) }
        return out
    }
}
