package com.blaze.assistant.agent.query

import com.blaze.assistant.composer.ModelChoice
import org.json.JSONArray

/**
 * 0.51.0 BlazeQuery quality judging, in two passes:
 *
 *  1. [heuristic] (hot path, no model, microseconds): relevance (query terms present), authority ([SourcePolicy]),
 *     freshness (the page's date against what the intent needs) and cross-source agreement (the same versions / numbers on
 *     independent hosts). Blocked hosts never pass; anything under [MIN_SCORE] is dropped. Version conflicts between
 *     trusted sources are flagged ([conflicts]) instead of silently picking one.
 *  2. The model pass (background, never on the reply's critical path): the tier the user picked on the composer
 *     (Spark / Flare / Inferno; Auto → Flare) scores the surviving evidence. Spark judges cheaply in one big batch with
 *     scores only; Flare in smaller batches; Inferno in the smallest batches and also checks consistency and names
 *     conflicts. [prompt] / [parse] / [blend] are pure; the call itself is the engine's.
 *
 * Pure (JudgeTest).
 */
object Judge {
    const val MIN_SCORE = 0.38
    const val W_REL = 0.40; const val W_AUTH = 0.30; const val W_FRESH = 0.15; const val W_AGREE = 0.15

    /** Fraction of the query's terms in [text] (title counts), with a bonus when two consecutive terms appear together. */
    fun relevance(text: String, terms: List<String>): Double {
        val t = text.lowercase()
        val ts = terms.map { it.lowercase() }.filter { it.length >= 2 }.distinct()
        if (ts.isEmpty()) return 0.0
        val have = ts.count { t.contains(it) }
        val pair = ts.zipWithNext().count { (a, b) -> t.contains("$a $b") }
        return (have.toDouble() / ts.size + 0.1 * pair).coerceIn(0.0, 1.0)
    }

    /** 0..1 for a yyyy-MM-dd [date]: what counts as fresh depends on the intent (news ages in days, docs in years). */
    fun freshness(date: String, intent: Intent, nowMs: Long): Double {
        val y = date.take(4).toIntOrNull()
        if (y == null) return when (intent) { Intent.NEWS -> 0.35; Intent.VERSION -> 0.45; else -> 0.6 }
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowY = cal.get(java.util.Calendar.YEAR); val nowM = cal.get(java.util.Calendar.MONTH) + 1
        val m = date.substring(minOf(5, date.length)).take(2).toIntOrNull() ?: 6
        val ageMonths = (nowY - y) * 12 + (nowM - m)
        return when (intent) {
            Intent.NEWS -> when { ageMonths <= 0 -> 1.0; ageMonths <= 1 -> 0.75; ageMonths <= 6 -> 0.4; else -> 0.15 }
            Intent.VERSION -> when { ageMonths <= 3 -> 1.0; ageMonths <= 12 -> 0.75; ageMonths <= 24 -> 0.45; else -> 0.2 }
            else -> when { ageMonths <= 12 -> 1.0; ageMonths <= 36 -> 0.75; ageMonths <= 72 -> 0.5; else -> 0.3 }
        }
    }

    private val versionRe = Regex("""\bv?(\d+\.\d+(?:\.\d+){0,2}(?:[-.](?:alpha|beta|rc|m)\d*)?)\b""", RegexOption.IGNORE_CASE)
    private val numberRe = Regex("""\b\d[\d,.]*\d\b|\b\d\b""")

    fun versions(text: String): Set<String> = versionRe.findAll(text).map { it.groupValues[1].lowercase() }.toSet()

    /** For each item: how many OTHER hosts state one of its versions / numbers (0..1, 2+ hosts = 1). */
    fun agreement(items: List<Evidence>): List<Double> {
        val facts = items.map { e -> versions(e.text) + numberRe.findAll(e.text).map { it.value.replace(",", "") }.filter { it.length >= 3 }.toSet() }
        return items.mapIndexed { i, e ->
            if (facts[i].isEmpty()) 0.5
            else {
                val others = items.indices.filter { j -> j != i && items[j].host != e.host && facts[j].intersect(facts[i]).isNotEmpty() }.map { items[it].host }.distinct().size
                when (others) { 0 -> 0.3; 1 -> 0.75; else -> 1.0 }
            }
        }
    }

    /** Scores, drops blocked / weak items, de-duplicates and sorts best first. */
    fun heuristic(items: List<Evidence>, terms: List<String>, nowMs: Long = System.currentTimeMillis()): List<Evidence> {
        val ok = dedupe(items.filter { SourcePolicy.allowed(it.url) && it.text.isNotBlank() })
        val agree = agreement(ok)
        return ok.mapIndexed { i, e ->
            val rel = relevance(e.title + " " + e.text, terms)
            val auth = SourcePolicy.trust(e.url)
            val fresh = freshness(e.date, e.intent, nowMs)
            // a registry API answer is the source of record for its own version
            val ag = if (e.origin == Origin.REGISTRY) 1.0 else agree[i]
            val total = W_REL * rel + W_AUTH * auth + W_FRESH * fresh + W_AGREE * ag + (if (e.origin == Origin.REGISTRY) 0.15 else 0.0) + (if (e.code && rel > 0.3) 0.05 else 0.0)
            e.copy(relevance = rel, authority = auth, freshness = fresh, agreement = ag, score = total.coerceIn(0.0, 1.0))
        }.filter { it.score >= MIN_SCORE && it.relevance > 0.0 }.sortedByDescending { it.score }
    }

    /** Same text (normalised) or the same url+text start: kept once, the more trusted copy wins. */
    fun dedupe(items: List<Evidence>): List<Evidence> {
        val seen = HashMap<String, Evidence>()
        val order = ArrayList<String>()
        for (e in items) {
            val k = norm(e.text).take(160)
            val prev = seen[k]
            if (prev == null) { seen[k] = e; order += k }
            else if (SourcePolicy.trust(e.url) > SourcePolicy.trust(prev.url)) seen[k] = e
        }
        return order.map { seen.getValue(it) }
    }

    fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Version answers that differ between two trusted (≥ OK) sources, for VERSION evidence. */
    fun conflicts(items: List<Evidence>): List<String> {
        val vs = items.filter { it.intent == Intent.VERSION && it.authority >= SourcePolicy.OK }
            .mapNotNull { e -> versions(e.text).maxWithOrNull(compareBy<String>({ it.split('.').getOrNull(0)?.toIntOrNull() ?: 0 }, { it.split('.').getOrNull(1)?.toIntOrNull() ?: 0 }, { it.split('.').getOrNull(2)?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }))?.let { it to e.host } }
        val distinct = vs.distinctBy { it.first }
        if (distinct.size < 2) return emptyList()
        return listOf("Sources disagree on the version: " + distinct.take(3).joinToString(" vs ") { "${it.first} (${it.second})" } + ". Prefer the registry / release page.")
    }

    // ---------------- the model pass ----------------

    /** The tier that judges: the composer's pick; Auto → Flare for the background pass, Spark on anything time-critical. */
    fun family(choice: ModelChoice.Family, hot: Boolean = false): ModelChoice.Family = when {
        hot -> ModelChoice.Family.SPARK
        choice == ModelChoice.Family.AUTO -> ModelChoice.Family.FLARE
        else -> choice
    }

    fun batchSize(f: ModelChoice.Family): Int = when (f) { ModelChoice.Family.INFERNO -> 5; ModelChoice.Family.FLARE -> 8; else -> 12 }

    fun prompt(question: String, items: List<Evidence>, f: ModelChoice.Family): String = buildString {
        append("You check evidence before an assistant uses it. Question: \"").append(question.take(600).replace('\n', ' ')).append("\"\n")
        append("Score each numbered item 0-10 for: rel (answers the question), auth (source is authoritative for this), fresh (current enough), ")
        append("cons (consistent with the other items).")
        if (f == ModelChoice.Family.INFERNO) append(" Also check commands, flags, API names and versions against each other; in \"conflict\" name any contradiction or outdated claim in a few words.")
        append("\nReply ONLY with a JSON array, one object per item, in order: [{\"i\":1,\"rel\":8,\"auth\":9,\"fresh\":7,\"cons\":8")
        append(if (f == ModelChoice.Family.INFERNO) ",\"conflict\":\"\"}]\n\n" else "}]\n\n")
        items.forEachIndexed { k, e ->
            append(k + 1).append(". [").append(e.host).append(if (e.date.isNotBlank()) ", ${e.date}" else "").append("] ")
            append(e.text.take(if (f == ModelChoice.Family.SPARK) 280 else 480).replace('\n', ' ')).append('\n')
        }
    }

    data class ModelScore(val rel: Double, val auth: Double, val fresh: Double, val cons: Double, val conflict: String = "") {
        val total: Double get() = (0.45 * rel + 0.25 * auth + 0.15 * fresh + 0.15 * cons).coerceIn(0.0, 1.0)
    }

    /** item index (0-based) → score. Lenient: fences, prose, 0-10 or 0-1 scales, missing fields. */
    fun parse(raw: String, n: Int): Map<Int, ModelScore> {
        val s = raw.substringAfterLast("</think>")
        val a = s.indexOf('['); val b = s.lastIndexOf(']')
        if (a < 0 || b <= a) return emptyMap()
        val arr = try { JSONArray(s.substring(a, b + 1)) } catch (_: Exception) { return emptyMap() }
        val out = HashMap<Int, ModelScore>()
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val idx = (if (o.has("i")) o.optInt("i", k + 1) else k + 1) - 1
            if (idx !in 0 until n) continue
            // one scale per item: 0-10 when any score is above 1 (so "rel":1 next to "auth":5 means 1/10), else 0-1
            val ten = listOf("rel", "auth", "fresh", "cons").any { o.optDouble(it, 0.0) > 1.0 }
            fun v(key: String): Double { val x = o.optDouble(key, Double.NaN); return if (x.isNaN()) 0.5 else if (ten) (x / 10.0).coerceIn(0.0, 1.0) else x.coerceIn(0.0, 1.0) }
            out[idx] = ModelScore(v("rel"), v("auth"), v("fresh"), v("cons"), o.optString("conflict").trim().take(160))
        }
        return out
    }

    /** Heuristic and model scores together (equal weight); an item the model rates as irrelevant (rel < 0.3) is dropped. */
    fun blend(items: List<Evidence>, scores: Map<Int, ModelScore>): List<Evidence> = items.mapIndexedNotNull { i, e ->
        val m = scores[i] ?: return@mapIndexedNotNull e
        if (m.rel < 0.3) return@mapIndexedNotNull null
        e.copy(score = (0.5 * e.score + 0.5 * m.total).coerceIn(0.0, 1.0), judged = true, conflict = m.conflict.takeIf { it.isNotBlank() && !it.equals("none", true) } ?: e.conflict)
    }.filter { it.score >= MIN_SCORE }.sortedByDescending { it.score }

}
