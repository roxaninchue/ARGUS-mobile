package com.argus.assistant.agent.query

import com.argus.assistant.agent.LookupLane
import com.argus.assistant.agent.Settings
import com.argus.assistant.agent.Web
import com.argus.assistant.composer.ModelChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.51.0 BlazeQuery on the phone: the real network, store and model behind [BlazeQuery.Env], the research tool and the
 * settings. Retrieval is plain HTTP from the app (Web.search / Web.fetch: DuckDuckGo HTML → Bing RSS → GitHub API, then
 * the registries' JSON APIs), never the browser UI and never the screen (terminal-first, docs/TERMINAL-FIRST.md).
 *
 * Optional gateway search proxy (`bq_gateway`, default OFF; the gateway endpoint is written on gateway branch
 * feat/blazequery but not deployed): GET <gateway>/v1/query/search?q=… ; any failure falls back to on-phone search.
 */
object QueryRuntime {
    /** The background pass outlives the turn that started it (and never runs on the main thread). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set by the agent: is the phone online right now. */
    @Volatile var online: () -> Boolean = { true }
    /** Set by the agent: one background model call with the given tier as the gateway's family hint. */
    @Volatile var ask: (suspend (String, ModelChoice.Family) -> String)? = null

    fun enabled(): Boolean = try { Settings.bool(BlazeQuery.SETTING, true) } catch (_: Throwable) { true }
    private fun gatewayOn(): Boolean = try { Settings.bool("bq_gateway", false) } catch (_: Throwable) { false }

    val net = object : BlazeQuery.Net {
        override suspend fun search(q: String): List<BlazeQuery.Hit> {
            if (gatewayOn()) gatewaySearch(q)?.takeIf { it.isNotEmpty() }?.let { return it }
            return LookupLane.hitsOf(Web.search(q, 6)).map { BlazeQuery.Hit(it.title, it.url, it.snippet) }
        }
        override suspend fun fetch(url: String, max: Int): String? {
            if (!SourcePolicy.allowed(url)) return null
            return Web.fetch(url, max).takeIf { !it.startsWith("Error") }
        }
        override fun online(): Boolean = try { online.invoke() } catch (_: Throwable) { true }
    }

    /** The (undeployed) gateway proxy: null on any failure, so the phone searches by itself. */
    private suspend fun gatewaySearch(q: String): List<BlazeQuery.Hit>? = withContext(Dispatchers.IO) {
        try {
            val tok = Settings.gatewayToken()
            if (tok.isBlank()) return@withContext null
            val (code, body) = com.argus.assistant.connect.Http.req("GET", com.argus.assistant.agent.Providers.GATEWAY_BASE.trimEnd('/') + "/query/search?q=" +
                java.net.URLEncoder.encode(q, "UTF-8"), headers = mapOf("Authorization" to "Bearer $tok", "Accept" to "application/json"), timeoutMs = 4_000)
            if (code !in 200..299) return@withContext null
            val arr = JSONObject(body).optJSONArray("results") ?: return@withContext null
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { BlazeQuery.Hit(it.optString("title"), it.optString("url"), it.optString("snippet")) }
                .filter { it.url.startsWith("http") }
        } catch (_: Exception) { null }
    }

    fun env(): BlazeQuery.Env = BlazeQuery.Env(net, { com.argus.assistant.memory.CacheTier.store() }, ask, scope)

    // ---------------- research tool (an extra: the core of BlazeQuery is the automatic pack) ----------------

    const val TOOL = "blaze_research"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "BlazeQuery research: multi-round web research with source checks (official docs, registries, release notes; never Wikipedia or content farms), " +
            "judged by the selected model tier, returns a short cited brief and caches the evidence (cache_search). depth=quick (1 round, /query) or deep (up to 3 rounds, /research). " +
            "Use for questions that need several sources or careful checking; simple facts already come in the BLAZEQUERY block.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("question", JSONObject().put("type", "string").put("description", "What to research, self-contained (resolve 'it' / 'that' from the chat)"))
            .put("depth", JSONObject().put("type", "string").put("enum", JSONArray(listOf("quick", "deep"))).put("description", "quick or deep (default deep)")))
            .put("required", JSONArray(listOf("question")))))

    suspend fun runTool(args: JSONObject, chat: String, recent: List<String>, private: Boolean, progress: (String) -> Unit): String {
        val q = args.optString("question").trim()
        if (q.isEmpty()) return "Error: give the question to research."
        if (!net.online()) return "BlazeQuery research needs the internet; the phone is offline. Answer from what you know and say it is unchecked."
        val limits = if (args.optString("depth").equals("quick", true)) Research.QUICK else Research.DEEP
        val env = if (private) BlazeQuery.Env(net, { null }, ask, scope) else env()
        val b = Research.run(q, recent, chat, ModelChoice.current.value, env, limits, progress)
        return Research.render(b)
    }
}
