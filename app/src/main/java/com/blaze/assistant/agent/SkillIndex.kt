package com.blaze.assistant.agent

import kotlin.math.ln

/**
 * v0.26.2 skills and tools by lookup. A small full-text index ranked exactly like Argus Desktop's store
 * (internal/store: FTS5 bm25 over name / description / when-to-use, weights 10 / 5 / 1; tools 10 / 3 / 2 over
 * name / description / group). It backs find_skill and find_tool (2026-11-20: the per-turn "best matches" block is gone;
 * SkillShelf lists every skill instead). It lives in memory and is rebuilt whenever the skill set changes (install,
 * toggle, learn, delete): SkillMatch keys it on a signature of the candidates.
 *
 * Desktop's own SQLite FTS5 store (store/BlazeDb.kt) needs a bundled SQLite on Android; until memory moves
 * there this index is the same ranking without a database. Pure (SkillIndexTest).
 */
object SkillIndex {
    /** One searchable row: [fields] in column order (e.g. name, description, when-to-use). */
    data class Doc(val key: String, val fields: List<String>, val label: String = "", val note: String = "")

    const val K1 = 1.2
    const val B = 0.75

    private val stop = ("a an and are as at be but by can could did do does for from had has have he her his how i if in into is it its just me my no not of on or our she so " +
        "than that the their them then there these they this to too up us was we were what when where which who why will with would you your yes ok okay please thanks " +
        "thank hi hello hey let lets make want need get got use using also very really some any all about blaze").split(' ').toSet()

    /** Lowercase word stems (letters and digits), stop words out: the same text form for rows and queries. */
    fun tokens(s: String): List<String> = s.lowercase().replace('_', ' ').replace('-', ' ').split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 && it !in stop }.map { Playbooks.stem(it) }

    /** The distinct query terms, newest first (like Desktop's ftsQuery), at most [max]. */
    fun terms(q: String, max: Int = 32): List<String> = tokens(q).asReversed().distinct().take(max)

    class Index(val docs: List<Doc>, private val weights: DoubleArray) {
        private val tf: List<List<Map<String, Int>>> = docs.map { d -> d.fields.map { f -> tokens(f).groupingBy { it }.eachCount() } }
        private val len: IntArray = IntArray(docs.size) { i -> tf[i].sumOf { it.values.sum() } }
        private val avg: Double = if (docs.isEmpty()) 1.0 else len.average().coerceAtLeast(1.0)
        private val df: Map<String, Int> = HashMap<String, Int>().apply {
            for (row in tf) row.flatMap { it.keys }.toSet().forEach { this[it] = (this[it] ?: 0) + 1 }
        }

        /** FTS5's bm25(): Σ idf · f·(k1+1) / (f + k1·(1 − b + b·|D|/avgdl)), f = the column-weighted term count. */
        fun score(i: Int, q: List<String>): Double {
            var s = 0.0; val n = docs.size
            for (t in q) {
                val nt = df[t] ?: continue
                var f = 0.0
                tf[i].forEachIndexed { c, m -> f += (weights.getOrElse(c) { 1.0 }) * (m[t] ?: 0) }
                if (f == 0.0) continue
                val idf = ln((n - nt + 0.5) / (nt + 0.5)).let { if (it <= 0.0) 1e-6 else it }
                s += idf * (f * (K1 + 1)) / (f + K1 * (1 - B + B * len[i] / avg))
            }
            return s
        }

        /** The best rows for [query], best first (only rows that match at least one term). */
        fun search(query: String, limit: Int): List<Pair<Doc, Double>> {
            val q = terms(query); if (q.isEmpty() || docs.isEmpty()) return emptyList()
            return docs.indices.map { it to score(it, q) }.filter { it.second > 0.0 }
                .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { docs[it.first].key })
                .take(limit).map { docs[it.first] to it.second }
        }
    }

    /** Skills: name ×10, description ×5, when-to-use / keywords ×1 (Desktop's bm25(skills_fts, 0, 10, 5, 1)). */
    fun skills(docs: List<Doc>) = Index(docs, doubleArrayOf(10.0, 5.0, 1.0))

    /** Tools: name ×10, description ×3, group ×2 (Desktop's bm25(tools_fts, 0, 10, 3, 2)). */
    fun tools(docs: List<Doc>) = Index(docs, doubleArrayOf(10.0, 3.0, 2.0))

    /** find_skill's answer: up to [limit] matches with their descriptions. */
    fun findLine(index: Index, query: String, limit: Int = 8): String {
        val hits = index.search(query, limit)
        if (hits.isEmpty()) return "No skill matches \u201c$query\u201d. Do the task with your tools, or install_skill / write_skill one."
        return "Skills matching \u201c$query\u201d (read one with use_skill):\n" + hits.joinToString("\n") { (d, _) ->
            "- ${d.label.ifBlank { d.key }}: ${d.fields.getOrNull(1).orEmpty().replace(Regex("\\s+"), " ").take(160)}${d.note}" }
    }
}
