package com.blazeinchue.argus.agent.query

import com.blazeinchue.argus.composer.ModelChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 0.51.0 ArgusQuery research mode: multi-round research on the same retrieval / judging / cache machinery, as a reusable API
 * that is not tied to the reply path. Used by the `argus_research` tool and the /research and /query slash commands.
 *
 *   round 1: plan (the model writes sub-queries; heuristic plan offline) → retrieve (search + trusted pages + registries)
 *            → judge (heuristic, then the selected tier) → gap analysis (what is still unanswered / conflicting)
 *   round n: follow-up queries from the gaps … until the model says it is covered, nothing new was found, or a limit hits.
 *   brief:   the model writes a short cited brief from the top evidence ([q1]…); without a model, the top facts as a list.
 *
 * Bounded by [Limits] (rounds, queries, pages, wall time). Everything found is stored in the cache tier as one
 * "argusquery" document, so later turns and cache_search reach it. Pure orchestration over [ArgusQuery.Env] (ResearchTest).
 */
object Research {
    data class Limits(val rounds: Int = 3, val queriesPerRound: Int = 3, val pagesPerRound: Int = 4, val totalMs: Long = 90_000, val keep: Int = 14)

    val QUICK = Limits(rounds = 1, queriesPerRound = 3, pagesPerRound = 3, totalMs = 30_000, keep = 10)
    val DEEP = Limits()

    data class Brief(val text: String, val evidence: List<Evidence>, val rounds: Int, val queries: List<SubQuery>, val doc: String?,
                     val gaps: List<String>, val stopped: String)

    data class Gaps(val gaps: List<String>, val followups: List<SubQuery>, val done: Boolean)

    fun gapPrompt(question: String, items: List<Evidence>, asked: List<SubQuery>): String = buildString {
        append("Research question: \"").append(question.take(800)).append("\"\nQueries already run: ")
        append(asked.joinToString("; ") { it.text }).append("\nEvidence so far:\n")
        items.take(12).forEachIndexed { i, e -> append(i + 1).append(". [").append(e.host).append("] ").append(e.text.take(300).replace('\n', ' ')).append('\n') }
        append("\nWhat parts of the question are still unanswered, uncertain or contradicted? Reply ONLY with JSON: ")
        append("{\"done\":false,\"gaps\":[\"...\"],\"followups\":[{\"q\":\"...\",\"intent\":\"docs\"}]} (done=true when the evidence answers it well; at most 3 followups).")
    }

    fun parseGaps(raw: String): Gaps {
        val s = raw.substringAfterLast("</think>")
        val a = s.indexOf('{'); val b = s.lastIndexOf('}')
        if (a < 0 || b <= a) return Gaps(emptyList(), emptyList(), true)
        return try {
            val o = JSONObject(s.substring(a, b + 1))
            val g = o.optJSONArray("gaps"); val f = o.optJSONArray("followups")
            val gaps = if (g == null) emptyList() else (0 until g.length()).map { g.optString(it).trim() }.filter { it.isNotEmpty() }
            val fu = if (f == null) emptyList() else QueryPlanner.parseModel(f.toString(), 3)
            Gaps(gaps, fu, o.optBoolean("done", fu.isEmpty()))
        } catch (_: Exception) { Gaps(emptyList(), emptyList(), true) }
    }

    fun briefPrompt(question: String, items: List<Evidence>, conflicts: List<String>): String = buildString {
        append("Write a short, accurate research brief that answers: \"").append(question.take(800)).append("\"\n")
        append("Use ONLY the numbered evidence. Cite with the ids in brackets, e.g. [q2]. Keep commands, flags, versions and code exactly as given. ")
        append("Say plainly what the evidence does not settle. 4-10 short bullet points or a few short paragraphs; no preamble.\n\n")
        items.forEach { e -> append("[").append(e.id).append("] (").append(e.host).append(if (e.date.isNotBlank()) ", ${e.date}" else "").append(") ")
            append(e.text.take(600)).append('\n') }
        conflicts.forEach { append("Conflict: ").append(it).append('\n') }
    }

    /** No model: the top facts as a cited list. */
    fun plainBrief(items: List<Evidence>, conflicts: List<String>): String =
        (items.take(8).joinToString("\n") { "- " + it.text.replace(Regex("\\s+"), " ").take(300) + " [${it.id}]" } +
            conflicts.joinToString("") { "\n- Conflict: $it" }).ifBlank { "Nothing reliable was found." }

    fun sources(items: List<Evidence>): String = items.joinToString("\n") { "[${it.id}] ${it.title.ifBlank { it.host }.take(80)} — ${it.url}" }

    /**
     * Runs the research. [progress] gets plain lines ("Round 2: 3 searches"). Never throws for network / model failures
     * (an empty brief says so); cancellation propagates.
     */
    suspend fun run(question: String, recent: List<String>, chat: String, family: ModelChoice.Family, env: ArgusQuery.Env,
                    limits: Limits = DEEP, progress: (String) -> Unit = {}): Brief {
        val t0 = env.now()
        val fam = Judge.family(family)
        val asked = ArrayList<SubQuery>()
        val seenUrls = HashSet<String>()
        var pool = emptyList<Evidence>()
        var gaps = emptyList<String>()
        var stopped = "rounds"
        var round = 0
        val ask = env.ask
        var next: List<SubQuery> = kotlin.run {
            val modelPlan = if (ask != null) withTimeoutOrNull(15_000) { try { QueryPlanner.parseModel(ask(QueryPlanner.modelPrompt(question, recent, limits.queriesPerRound), fam), limits.queriesPerRound) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() } } else null
            modelPlan?.takeIf { it.isNotEmpty() } ?: QueryPlanner.plan(question, recent, NeedDetect.decide(question, recent).let { if (it.external) it else it.copy(need = NeedDetect.Need.EXTERNAL, intents = NeedDetect.intents(question)) }, limits.queriesPerRound)
        }
        while (round < limits.rounds) {
            if (env.now() - t0 > limits.totalMs) { stopped = "time"; break }
            val qs = next.filter { q -> asked.none { it.text.equals(q.text, true) } }.take(limits.queriesPerRound)
            if (qs.isEmpty()) { stopped = if (round == 0) "no queries" else "no new queries"; break }
            round++
            asked += qs
            progress("Round $round: ${qs.size} search${if (qs.size == 1) "" else "es"}")
            val terms = QueryPlanner.terms(question + " " + qs.joinToString(" ") { it.text }).take(14)
            val found = coroutineScope {
                val pkgs = if (round == 1) Registries.detect(question) else emptyList()
                val reg = pkgs.map { p -> async { withTimeoutOrNull(ArgusQuery.FETCH_MS) { env.net.fetch(Registries.url(p), ArgusQuery.REGISTRY_CHARS) }
                    ?.let { Registries.parse(p, it) }?.let { (l, d) -> Evidence(l, Registries.page(p), p.eco.label + " " + p.name, d, Origin.REGISTRY, true, Intent.VERSION) } } }
                val hits = qs.map { q -> async { (withTimeoutOrNull(ArgusQuery.SEARCH_MS) { runCatching { env.net.search(q.text) }.getOrNull() } ?: emptyList()).map { q to it } } }.awaitAll().flatten()
                val snippets = hits.filter { SourcePolicy.allowed(it.second.url) && it.second.snippet.length >= 30 }
                    .map { (q, h) -> Evidence(h.snippet, h.url, h.title, Extract.date(h.snippet), Origin.SNIPPET, false, q.intent) }
                val pages = hits.map { it.second }.distinctBy { it.url }.filter { SourcePolicy.trust(it.url) >= SourcePolicy.UNKNOWN && seenUrls.add(it.url) }
                    .sortedByDescending { SourcePolicy.trust(it.url) }.take(limits.pagesPerRound)
                progress("Round $round: reading ${pages.size} page${if (pages.size == 1) "" else "s"}")
                val intentOf = hits.associate { it.second.url to it.first.intent }
                val pageEv = pages.map { h -> async { withTimeoutOrNull(ArgusQuery.FETCH_MS) { runCatching { env.net.fetch(h.url, ArgusQuery.PAGE_CHARS) }.getOrNull() }
                    ?.takeIf { !it.startsWith("Error") && it.length >= 200 }?.let { t -> val d = Extract.date(t)
                        Extract.pieces(t, terms, 4).map { p -> Evidence(p.text, h.url, h.title, d, Origin.PAGE, p.code, intentOf[h.url] ?: Intent.FACT) } } ?: emptyList() } }.awaitAll().flatten()
                reg.awaitAll().filterNotNull() + snippets + pageEv
            }
            val before = pool.size
            pool = Judge.heuristic(pool + found, QueryPlanner.terms(question + " " + asked.joinToString(" ") { it.text }).take(16), env.now()).take(limits.keep * 2)
            if (pool.size == before && found.isEmpty()) { stopped = "nothing new"; break }
            if (round >= limits.rounds || ask == null) { if (ask == null) stopped = "no model for gap analysis"; break }
            val g = withTimeoutOrNull(20_000) { try { parseGaps(ask(gapPrompt(question, pool, asked), fam)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }
            if (g == null) { stopped = "gap analysis failed"; break }
            gaps = g.gaps
            if (g.done) { stopped = "covered"; break }
            next = g.followups
        }
        // the selected tier's judge over the final pool
        if (ask != null && pool.isNotEmpty()) {
            val size = Judge.batchSize(fam)
            val head = pool.take(size * 2)
            val judged = withTimeoutOrNull(ArgusQuery.JUDGE_MS) {
                head.chunked(size).flatMap { b -> val raw = try { ask(Judge.prompt(question, b, fam), fam) } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
                    val sc = Judge.parse(raw, b.size); if (sc.isEmpty()) b else Judge.blend(b, sc) }
            }
            if (judged != null) pool = (judged + pool.drop(head.size)).sortedByDescending { it.score }
        }
        val top = EvidencePack.cite(pool.take(limits.keep))
        val conflicts = Judge.conflicts(top)
        val text = if (top.isEmpty()) "Nothing reliable was found for this (searched: ${asked.joinToString("; ") { it.text }})."
            else if (ask != null) (withTimeoutOrNull(30_000) { try { ask(briefPrompt(question, top, conflicts), fam).substringAfterLast("</think>").trim() } catch (e: CancellationException) { throw e } catch (_: Exception) { "" } } ?: "").ifBlank { plainBrief(top, conflicts) }
            else plainBrief(top, conflicts)
        var doc: String? = null
        val s = env.store()
        if (s != null && top.isNotEmpty()) try {
            val d = s.ingest(chat, ArgusQuery.KIND, "Research: " + question.take(80), "# Research brief\n$text\n\n" + ArgusQuery.document(asked, top, emptyList()))
            s.bqPut(QueryPlanner.topicKey(question, recent), d.id, chat, ArgusQuery.TTL_MS, ArgusQuery.encode(top, conflicts), ask != null)
            s.evict(setOf(d.id)); doc = d.id
        } catch (_: Exception) { }
        ArgusQuery.Stats.count("research_runs")
        return Brief(text, top, round, asked, doc, gaps, stopped)
    }

    /** What the tool returns: the brief, its sources and where the full text lives. */
    fun render(b: Brief): String = buildString {
        append(b.text.trim()).append("\n\nSources:\n").append(sources(b.evidence))
        append("\n\n(").append(b.rounds).append(" round").append(if (b.rounds == 1) "" else "s").append(", ").append(b.queries.size).append(" queries")
        if (b.doc != null) append("; full evidence cached as doc:").append(b.doc).append(" — cache_search / cache_read")
        append(")")
        if (b.gaps.isNotEmpty()) append("\nStill open: ").append(b.gaps.take(3).joinToString("; "))
    }
}
