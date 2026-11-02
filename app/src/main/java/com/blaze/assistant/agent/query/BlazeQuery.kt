package com.blaze.assistant.agent.query

import com.blaze.assistant.composer.ModelChoice
import com.blaze.assistant.memory.CacheStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 0.51.0 BlazeQuery — "involuntary omniscience". Runs AUTOMATICALLY the moment the user sends a message (not a tool the
 * model may or may not call), in parallel with prompt assembly, and puts a compact, judged evidence pack into the prompt
 * through the tiered-memory cache tier. See docs/BLAZEQUERY.md.
 *
 * Hot path ([hot], hard budget [HOT_MS]):
 *   need detection (no model) → LOCAL: the chat's own cache only · NONE: nothing ·
 *   EXTERNAL / CODE: reuse a stored topic (instant) → else plan sub-queries (heuristic) → search them in parallel + ask the
 *   package registries → heuristic judge → evidence pack. Whatever is not ready inside the budget is NOT waited for.
 * Background (never blocks the reply): read the best trusted pages → extract passages (code kept whole) → judge again →
 *   the selected tier (Spark / Flare / Inferno) scores the evidence → stored in cache.db as a "blazequery" document plus a
 *   topic row with a TTL → [hot]'s `onLate` hands the finished pack to later model steps of the same turn; later turns on
 *   the same topic reuse it; cache_search / cache_read reach the full text.
 *
 * The network, the store and the model are injected ([Env]) so the whole pipeline runs in JVM tests (BlazeQueryTest).
 */
object BlazeQuery {
    /** Most the reply ever waits for the pack (from the send). */
    const val HOT_MS = 1_200L
    const val SEARCH_MS = 4_000L
    const val FETCH_MS = 6_000L
    const val JUDGE_MS = 25_000L
    const val PAGES = 3
    const val PAGE_CHARS = 60_000
    const val REGISTRY_CHARS = 2_000_000
    const val TTL_MS = 6 * 3_600_000L
    const val NEWS_TTL_MS = 3_600_000L
    const val VERSION_TTL_MS = 3 * 3_600_000L
    const val REUSE_SIMILAR = 0.6
    const val SETTING = "blazequery"
    const val KIND = "blazequery"

    data class Hit(val title: String, val url: String, val snippet: String = "")

    interface Net {
        suspend fun search(q: String): List<Hit>
        /** The page / API body as text, or null when it failed. */
        suspend fun fetch(url: String, max: Int): String?
        fun online(): Boolean = true
    }

    class Env(
        val net: Net,
        val store: suspend () -> CacheStore?,
        /** The model call for the background judge (prompt, tier) → raw answer; null = no model (offline, tests). */
        val ask: (suspend (String, ModelChoice.Family) -> String)?,
        /** Where the background pass runs: a scope that outlives the turn (never the turn's own scope). */
        val scope: CoroutineScope,
        val now: () -> Long = { System.currentTimeMillis() },
    )

    enum class Status { OFF, SKIPPED, LOCAL, REUSED, FRESH, LATE, OFFLINE, EMPTY }

    data class Pack(val block: String, val status: Status, val evidence: List<Evidence> = emptyList(), val doc: String? = null,
                    val ms: Long = 0, val reason: String = "", val topic: String = "")

    data class Request(val chat: String, val text: String, val recent: List<String> = emptyList(),
                       val family: ModelChoice.Family = ModelChoice.Family.AUTO, val budgetTok: Int = 380, val private: Boolean = false)

    // ---------------- telemetry (local only, never uploaded) ----------------

    object Stats {
        private val m = ConcurrentHashMap<String, Long>()
        fun count(k: String, by: Long = 1) { m.merge(k, by) { a, b -> a + b } }
        fun get(k: String): Long = m[k] ?: 0L
        fun snapshot(): Map<String, Long> = HashMap(m)
        fun reset() = m.clear()
        fun line(): String = snapshot().toSortedMap().entries.joinToString(" · ") { "${it.key} ${it.value}" }.ifEmpty { "no BlazeQuery activity yet" }
    }

    // ---------------- (de)serialising the judged evidence (the topic row's pack) ----------------

    fun encode(items: List<Evidence>, conflicts: List<String> = emptyList()): String = JSONObject()
        .put("items", JSONArray().apply { items.forEach { e -> put(JSONObject().put("t", e.text).put("u", e.url).put("ti", e.title).put("d", e.date)
            .put("o", e.origin.name).put("c", e.code).put("i", e.intent.id).put("s", e.score).put("a", e.authority).put("j", e.judged).put("x", e.conflict)) } })
        .put("conflicts", JSONArray(conflicts)).toString()

    fun decode(s: String): Pair<List<Evidence>, List<String>> = try {
        val o = JSONObject(s)
        val a = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until a.length()).mapNotNull { k -> a.optJSONObject(k)?.let { e ->
            Evidence(e.optString("t"), e.optString("u"), e.optString("ti"), e.optString("d"),
                runCatching { Origin.valueOf(e.optString("o")) }.getOrDefault(Origin.CACHE), e.optBoolean("c"), Intent.of(e.optString("i")),
                score = e.optDouble("s", 0.5), authority = e.optDouble("a", 0.5), judged = e.optBoolean("j"), conflict = e.optString("x")) } }
        val c = o.optJSONArray("conflicts")
        items to (if (c == null) emptyList() else (0 until c.length()).map { c.optString(it) })
    } catch (_: Exception) { emptyList<Evidence>() to emptyList() }

    fun ttl(intents: Set<Intent>): Long = when {
        Intent.NEWS in intents -> NEWS_TTL_MS
        Intent.VERSION in intents -> VERSION_TTL_MS
        else -> TTL_MS
    }

    /** A live topic for [key]: exact first, else the most similar live one (≥ [REUSE_SIMILAR]). */
    fun findReuse(s: CacheStore, key: String): CacheStore.Bq? {
        if (key.isBlank()) return null
        s.bqGet(key)?.let { return it }
        val best = s.bqAlive().maxByOrNull { QueryPlanner.similar(it.topic, key) } ?: return null
        return if (QueryPlanner.similar(best.topic, key) >= REUSE_SIMILAR) s.bqGet(best.topic) else null
    }

    // ---------------- the hot path ----------------

    /**
     * The pack for this message, within [hotMs] of the call. Never throws (a failure is an empty pack). The background
     * pass keeps going after this returns and calls [onLate] with the finished pack.
     */
    suspend fun hot(req: Request, env: Env, hotMs: Long = HOT_MS, onLate: (Pack) -> Unit = {}): Pack {
        val t0 = env.now()
        fun ms() = env.now() - t0
        return try {
            if (req.private) return Pack("", Status.SKIPPED, reason = "private chat")
            val d = NeedDetect.decide(req.text, req.recent)
            when (d.need) {
                NeedDetect.Need.NONE -> { Stats.count("skipped"); return Pack("", Status.SKIPPED, reason = d.reason) }
                NeedDetect.Need.LOCAL -> {
                    Stats.count("local")
                    val block = withTimeoutOrNull(hotMs) { localBlock(req, env) } ?: ""
                    return Pack(block, Status.LOCAL, ms = ms(), reason = d.reason)
                }
                else -> { }
            }
            val key = QueryPlanner.topicKey(req.text, req.recent)
            val store = withTimeoutOrNull((hotMs - ms()).coerceAtLeast(1)) { env.store() }
            val reused = store?.let { s -> withTimeoutOrNull((hotMs - ms()).coerceAtLeast(1)) { runCatching { findReuse(s, key) }.getOrNull() } }
            if (reused != null) {
                val (items, conflicts) = decode(reused.pack)
                val cited = EvidencePack.cite(items)
                Stats.count("reused")
                return Pack(EvidencePack.build(cited, req.budgetTok, reused.doc, conflicts), Status.REUSED, cited, reused.doc, ms(), d.reason, reused.topic)
            }
            if (!env.net.online()) { Stats.count("offline"); return Pack("", Status.OFFLINE, ms = ms(), reason = "offline", topic = key) }
            val first = CompletableDeferred<List<Evidence>>()
            env.scope.launch {
                try { pipeline(req, d, key, env, first, onLate) }
                catch (e: CancellationException) { first.complete(emptyList()); throw e }
                catch (e: Throwable) { first.complete(emptyList()); Stats.count("errors") }
            }
            val got = withTimeoutOrNull((hotMs - ms()).coerceAtLeast(1)) { first.await() }
            if (got == null) { Stats.count("late"); return Pack("", Status.LATE, ms = ms(), reason = d.reason, topic = key) }
            if (got.isEmpty()) { Stats.count("empty"); return Pack("", Status.EMPTY, ms = ms(), reason = d.reason, topic = key) }
            val cited = EvidencePack.cite(got)
            Stats.count("injected")
            Pack(EvidencePack.build(cited, req.budgetTok, null, Judge.conflicts(got), "More sources are still being checked (cache_search doc=all later)."),
                Status.FRESH, cited, null, ms(), d.reason, key)
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { Stats.count("errors"); Pack("", Status.EMPTY, ms = ms(), reason = "error: ${e.javaClass.simpleName}") }
    }

    /** Need.LOCAL: the best hits in this chat's cached documents (nothing from the web). */
    private suspend fun localBlock(req: Request, env: Env): String {
        val s = env.store() ?: return ""
        val q = QueryPlanner.terms(req.text).joinToString(" ")
        if (q.isBlank()) return ""
        val hits = runCatching { s.search(q, chat = req.chat, limit = 3) }.getOrDefault(emptyList())
        return EvidencePack.local(hits.map { "doc:${it.doc} §${it.idx} ${it.title}".trim() to it.snippet }, minOf(req.budgetTok, 200))
    }

    // ---------------- the pipeline (stage A feeds the hot path; stage B runs on in the background) ----------------

    internal suspend fun pipeline(req: Request, d: NeedDetect.Decision, key: String, env: Env, first: CompletableDeferred<List<Evidence>>, onLate: (Pack) -> Unit) {
        val subs = QueryPlanner.plan(req.text, req.recent, d)
        val terms = QueryPlanner.terms(req.text + " " + subs.joinToString(" ") { it.text }).take(12)
        val pkgs = Registries.detect(req.text)
        coroutineScope {
            val reg = pkgs.map { p -> async {
                val body = withTimeoutOrNull(FETCH_MS) { env.net.fetch(Registries.url(p), REGISTRY_CHARS) } ?: return@async null
                Registries.parse(p, body)?.let { (line, date) -> Evidence(line, Registries.page(p), p.eco.label + " " + p.name, date, Origin.REGISTRY, true, Intent.VERSION) }
            } }
            val searched = subs.map { s -> async { (withTimeoutOrNull(SEARCH_MS) { runCatching { env.net.search(s.text) }.getOrNull() } ?: emptyList()).map { s to it } } }.awaitAll().flatten()
            val blocked = searched.count { !SourcePolicy.allowed(it.second.url) }
            Stats.count("blocked_sources", blocked.toLong())
            val snippets = searched.filter { SourcePolicy.allowed(it.second.url) && it.second.snippet.length >= 30 }
                .map { (s, h) -> Evidence(h.snippet, h.url, h.title, Extract.date(h.snippet), Origin.SNIPPET, false, s.intent) }
            val regNow = reg.filter { it.isCompleted }.mapNotNull { runCatching { it.await() }.getOrNull() }
            val a = Judge.heuristic(regNow + snippets, terms, env.now())
            first.complete(a)

            // ---- stage B (background) ----
            val pages = searched.map { it.second }.distinctBy { it.url.substringBefore('#').trimEnd('/') }
                .filter { SourcePolicy.trust(it.url) >= SourcePolicy.UNKNOWN }
                .sortedByDescending { SourcePolicy.trust(it.url) }.take(PAGES)
            val intentOf = searched.associate { it.second.url to it.first.intent }
            val fetched = pages.map { h -> async { h to withTimeoutOrNull(FETCH_MS) { runCatching { env.net.fetch(h.url, PAGE_CHARS) }.getOrNull() } } }.awaitAll()
                .filter { (_, t) -> t != null && !t.startsWith("Error") && t.length >= 200 }
            Stats.count("pages", fetched.size.toLong())
            val pageEv = fetched.flatMap { (h, t) ->
                val date = Extract.date(t!!)
                Extract.pieces(t, terms, 3).map { p -> Evidence(p.text, h.url, h.title, date, Origin.PAGE, p.code, intentOf[h.url] ?: Intent.FACT) }
            }
            val regAll = reg.awaitAll().filterNotNull()
            var b = Judge.heuristic(regAll + snippets + pageEv, terms, env.now())
            val ask = env.ask
            if (ask != null && b.isNotEmpty()) {
                val fam = Judge.family(req.family)
                val size = Judge.batchSize(fam)
                val head = b.take(size * 2)
                val judged = withTimeoutOrNull(JUDGE_MS) {
                    val out = ArrayList<Evidence>()
                    for (batch in head.chunked(size)) {
                        val raw = try { ask(Judge.prompt(req.text, batch, fam), fam) } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
                        val sc = Judge.parse(raw, batch.size)
                        out += if (sc.isEmpty()) batch else Judge.blend(batch, sc)
                    }
                    out
                }
                if (judged != null) { b = (judged + b.drop(head.size)).sortedByDescending { it.score }; Stats.count("judged") }
            }
            val conflicts = Judge.conflicts(b)
            val s = env.store()
            var docId: String? = null
            if (s != null && b.isNotEmpty() && !req.private) try {
                val full = document(subs, b, fetched.map { (h, t) -> h to Extract.pieces(t!!, terms, 8) })
                val doc = s.ingest(req.chat, KIND, "BlazeQuery: " + (subs.firstOrNull()?.text ?: key).take(80), full)
                s.bqPut(key, doc.id, req.chat, ttl(d.intents), encode(b.take(12), conflicts), b.any { it.judged })
                s.evict(setOf(doc.id))
                if (Math.random() < 0.2) s.bqExpire()
                docId = doc.id
            } catch (e: Exception) { Stats.count("store_errors") }
            val cited = EvidencePack.cite(b)
            if (cited.isNotEmpty()) onLate(Pack(EvidencePack.build(cited, req.budgetTok, docId, conflicts), Status.FRESH, cited, docId, 0, d.reason, key))
        }
    }

    /** The cache document: every judged item and the longer passages of each page, with source and date. */
    fun document(subs: List<SubQuery>, items: List<Evidence>, pages: List<Pair<Hit, List<Extract.Piece>>>): String = buildString {
        append("# BlazeQuery\nQueries: ").append(subs.joinToString(" | ") { "${it.text} (${it.intent.id})" }).append("\n\n")
        append("## Judged evidence\n")
        items.forEachIndexed { i, e ->
            append("[q").append(i + 1).append("] (").append(e.host).append(if (e.date.isNotBlank()) ", ${e.date}" else "")
                .append(", ").append(SourcePolicy.label(e.authority)).append(", score %.2f".format(e.score)).append(") ").append(e.url).append('\n')
            append(if (e.code) "```\n${e.text}\n```" else e.text).append("\n\n")
        }
        for ((h, ps) in pages) {
            if (ps.isEmpty()) continue
            append("## ").append(h.title.ifBlank { SourcePolicy.host(h.url) }).append('\n').append(h.url).append("\n\n")
            ps.forEach { p -> append(if (p.code) "```\n${p.text}\n```" else p.text).append("\n\n") }
        }
    }
}
