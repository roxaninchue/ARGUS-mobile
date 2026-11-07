package com.blazeinchue.argus.agent.query

/**
 * 0.51.0 ArgusQuery evidence pack: the only part of ArgusQuery that enters a prompt. A few hyper-short facts with short
 * citation ids ([q1]…), each line ≤ [LINE_MAX] characters, inside a token budget scaled by the route's context window
 * ([budgetTokens]). The full fetched text stays in the cache tier (doc id in the header) for cache_search / cache_read.
 * Never a page dump. Pure (EvidencePackTest).
 */
object EvidencePack {
    const val LINE_MAX = 260
    const val CODE_MAX = 420
    const val HEADER = "\nARGUSQUERY — fresh facts fetched for this message (scored for relevance and source quality). " +
        "Use them over older knowledge when they disagree; cite as [q1] only when you rely on one. Commands, flags, APIs and versions " +
        "not covered here are UNVERIFIED: say so, or check with cache_search before running or writing them."

    /**
     * Tokens the pack may use: chat lane 220, an unknown window (gateway auto) 380, a small window (< 16k) 300, else
     * window/40 clamped to 300–900. A code turn gets 40% more (commands need room); the cap still holds.
     */
    fun budgetTokens(window: Int?, lite: Boolean, code: Boolean = false): Int {
        val base = when {
            lite -> 220
            window == null || window <= 0 -> 380
            window < 16_000 -> 300
            else -> (window / 40).coerceIn(300, 900)
        }
        return (if (code) (base * 1.4).toInt() else base).coerceAtMost(900)
    }

    fun tokens(s: String): Int = (s.length + 3) / 4

    private fun oneLine(e: Evidence): String {
        val src = e.host + if (e.date.isNotBlank()) ", ${e.date}" else ""
        val body = if (e.code) {
            val c = e.text.trim()
            val cut = if (c.length <= CODE_MAX) c else c.lines().fold(StringBuilder()) { sb, l -> if (sb.length + l.length + 1 <= CODE_MAX) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(l) }; sb }.toString()
            "```\n$cut\n```"
        } else {
            val t = e.text.replace(Regex("\\s+"), " ").trim()
            if (t.length <= LINE_MAX) t else t.take(LINE_MAX).substringBeforeLast(' ') + "…"
        }
        return "[${e.id}] $body — $src"
    }

    /** Gives every item its citation id (q1, q2… in score order). */
    fun cite(items: List<Evidence>): List<Evidence> = items.mapIndexed { i, e -> e.copy(id = "q${i + 1}") }

    /**
     * The block for [items] (already sorted best first and cited) within [budgetTok]. [doc] = the cache document holding
     * the full text (named so the model can read more), [conflicts] = disagreements to state, [note] = one status line
     * (e.g. "more sources are still being checked"). "" when nothing passed the judge.
     */
    fun build(items: List<Evidence>, budgetTok: Int, doc: String? = null, conflicts: List<String> = emptyList(), note: String = ""): String {
        if (items.isEmpty()) return ""
        val maxChars = budgetTok * 4
        val sb = StringBuilder(HEADER)
        if (doc != null) sb.append(" Full text: doc:").append(doc).append('.')
        sb.append('\n')
        var n = 0
        for (e in items) {
            val line = oneLine(e) + "\n"
            if (sb.length + line.length > maxChars) { if (n == 0 && !e.code) { sb.append(line.take((maxChars - sb.length).coerceAtLeast(0))); n++ }; continue }
            sb.append(line); n++
        }
        if (n == 0) return ""
        for (c in conflicts.take(2)) { val l = "Conflict: $c\n"; if (sb.length + l.length <= maxChars) sb.append(l) }
        items.mapNotNull { it.conflict.takeIf { c -> c.isNotBlank() }?.let { c -> "[${it.id}] $c" } }.take(2).forEach { l ->
            val s = "Check: $l\n"; if (sb.length + s.length <= maxChars) sb.append(s) }
        if (note.isNotBlank() && sb.length + note.length + 1 <= maxChars) sb.append(note).append('\n')
        return sb.toString()
    }

    /** The local-only block (Need.LOCAL): what the chat's own cache says, nothing from the web. */
    fun local(hits: List<Pair<String, String>>, budgetTok: Int): String {
        if (hits.isEmpty()) return ""
        val maxChars = (budgetTok * 4).coerceAtMost(1_200)
        val sb = StringBuilder("\nFROM THIS CHAT'S CACHE (local only, nothing fetched):\n")
        for ((ref, snip) in hits) {
            val l = "- $ref: ${snip.replace(Regex("\\s+"), " ").take(220)}\n"
            if (sb.length + l.length > maxChars) break
            sb.append(l)
        }
        return sb.toString()
    }
}
