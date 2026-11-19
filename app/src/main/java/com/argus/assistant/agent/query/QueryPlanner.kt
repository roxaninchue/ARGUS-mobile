package com.blaze.assistant.agent.query

import com.blaze.assistant.agent.SearchRoute
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.51.0 BlazeQuery query planning: 1–4 targeted sub-queries written from the conversation, never the raw user sentence.
 *
 *  - [plan] (hot path, no model, < 1 ms): the conversation-aware query of 0.50 ([SearchRoute.query]: pronouns resolved from
 *    earlier turns, filler dropped), plus intent-specific variants: the error line itself for an error, "release notes" for a
 *    version question, "documentation" for API work, the year for news.
 *  - [modelPrompt] / [parseModel] (research mode and the background pass): the model writes the sub-queries with intent tags.
 *
 * [topicKey] is the dedupe / reuse key of a topic in the cache tier: the same subject asked again within the TTL reuses the
 * stored facts instead of searching again. Pure (QueryPlannerTest).
 */
object QueryPlanner {
    const val MAX = 4

    private val errorExtract = Regex("""(?m)^.*?((?:[A-Z][A-Za-z]+(?:Exception|Error)|error(?:\[E\d+\])?:|npm ERR!|E/AndroidRuntime|command not found|cannot find symbol|unresolved reference|No such file or directory|ModuleNotFoundError|SyntaxError|BUILD FAILED|Could not resolve).{0,140})$""")

    /** The most specific line of an error (paths, line numbers and hex addresses stripped so the search finds others with it). */
    fun errorQuery(text: String): String? {
        val m = errorExtract.find(text) ?: return null
        return m.groupValues[1]
            .replace(Regex("""(?:/[\w.@-]+){2,}"""), " ")                // file paths
            .replace(Regex("""0x[0-9a-fA-F]+|\b\d{3,}\b|:\d+(?::\d+)?"""), " ")   // addresses, ids, line:col
            .replace(Regex("""['"`]"""), " ")
            .replace(Regex("\\s+"), " ").trim().take(110).trim()
            .takeIf { it.length >= 8 }
    }

    /** The planned sub-queries for [text] in the light of [recent] (earlier turns, newest last). */
    fun plan(text: String, recent: List<String>, decision: NeedDetect.Decision, max: Int = 3): List<SubQuery> {
        val base = SearchRoute.query(text, recent, max = 110).ifBlank { text.take(110) }
        val out = ArrayList<SubQuery>()
        fun add(q: String, i: Intent) { val c = q.replace(Regex("\\s+"), " ").trim(); if (c.length >= 3 && out.none { it.text.equals(c, true) }) out += SubQuery(c, i) }
        val intents = decision.intents.ifEmpty { NeedDetect.intents(text) }
        if (Intent.ERROR in intents) errorQuery(text)?.let { add(it, Intent.ERROR) }
        when {
            Intent.VERSION in intents -> { add(base, Intent.VERSION); add(stripVersionWords(base) + " release notes", Intent.VERSION) }
            Intent.HOWTO in intents -> { add(base, Intent.HOWTO); if (decision.need == NeedDetect.Need.CODE) add("$base documentation", Intent.DOCS) }
            Intent.NEWS in intents -> { add(base, Intent.NEWS); add("$base ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)}", Intent.NEWS) }
            Intent.DOCS in intents -> { add(base, Intent.DOCS); add("$base official documentation", Intent.DOCS) }
            else -> add(base, intents.firstOrNull() ?: Intent.FACT)
        }
        return out.take(max.coerceIn(1, MAX))
    }

    private fun stripVersionWords(q: String) = q.replace(Regex("(?i)\\b(latest|newest|current|stable|version|release|what|which)\\b"), " ").replace(Regex("\\s+"), " ").trim()

    /** Words of a topic, for [topicKey] and relevance. */
    fun terms(q: String): List<String> = q.lowercase().split(Regex("[^\\p{L}\\p{N}+#.]+")).map { it.trim('.') }
        .filter { it.length >= 2 && it !in stop }.distinct()

    private val stop = setOf("the", "and", "for", "with", "from", "that", "this", "what", "how", "who", "when", "where", "why", "which", "are", "is",
        "was", "can", "does", "did", "use", "about", "you", "your", "me", "my", "an", "of", "to", "in", "on", "at", "it", "its", "do", "be", "or",
        "if", "we", "our", "us", "just", "there", "they", "them", "then", "now", "here", "want", "need", "know", "like", "some", "any", "more",
        "also", "will", "would", "should", "could", "has", "have", "had", "been", "official", "documentation", "release", "notes", "tell", "please")

    /** The reuse key of a topic: its sorted content words (at most 6). Two wordings of one subject share it. */
    fun topicKey(text: String, recent: List<String> = emptyList()): String =
        terms(SearchRoute.query(text, recent, max = 110)).take(8).sorted().take(6).joinToString(" ")

    /** Jaccard similarity of two topic keys (reuse when ≥ 0.6). */
    fun similar(a: String, b: String): Double {
        val x = a.split(' ').filter { it.isNotBlank() }.toSet(); val y = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    // ---------------- model planning (research mode / background) ----------------

    fun modelPrompt(text: String, recent: List<String>, max: Int = MAX): String = buildString {
        append("Write 1 to ").append(max).append(" web search queries that would find accurate, current facts for answering the user's latest message. ")
        append("Use the conversation to resolve what \"it\" / \"that\" mean. Prefer queries that hit official docs, release notes, package registries, ")
        append("man pages and primary sources. Tag each with one intent: docs, version, error, news, how-to, fact. ")
        append("Reply ONLY with JSON: [{\"q\":\"...\",\"intent\":\"docs\"}]\n\n")
        if (recent.isNotEmpty()) { append("Earlier turns (oldest first):\n"); recent.takeLast(4).forEach { append("- ").append(it.take(300).replace('\n', ' ')).append('\n') } }
        append("\nLatest message:\n").append(text.take(1_500))
    }

    /** Lenient: a JSON array (code fences / prose around it tolerated), or one query per line. */
    fun parseModel(raw: String, max: Int = MAX): List<SubQuery> {
        val s = raw.substringAfterLast("</think>")
        val a = s.indexOf('['); val b = s.lastIndexOf(']')
        val out = ArrayList<SubQuery>()
        if (a >= 0 && b > a) try {
            val arr = JSONArray(s.substring(a, b + 1))
            for (i in 0 until arr.length()) {
                val o = arr.opt(i)
                val q = when (o) { is JSONObject -> o.optString("q").ifBlank { o.optString("query") }; is String -> o; else -> "" }.trim()
                val intent = (o as? JSONObject)?.optString("intent")
                if (q.length >= 3 && out.none { it.text.equals(q, true) }) out += SubQuery(q.take(160), Intent.of(intent))
            }
        } catch (_: Exception) { }
        if (out.isEmpty()) s.lines().map { it.trim().trimStart('-', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim('"') }
            .filter { it.length in 3..160 && !it.startsWith("{") && !it.startsWith("[") }
            .forEach { if (out.none { o -> o.text.equals(it, true) }) out += SubQuery(it, Intent.FACT) }
        return out.take(max)
    }
}
