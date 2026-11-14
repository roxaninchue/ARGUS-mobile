package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * P0-API-01: which model to ask, from the gateway's live list of healthy models (port of A-team `app/www/models.js`
 * v2.13; LiveModelsTest ports its evals/test/v29 + v212 cases). The gateway (0.9.2+) serves `GET /v1/models/live`:
 * the caller's plan, probe-verified in the last ~6 h, fastest first. The app picks the first entry that fits the task,
 * keeps that pick for the whole turn and sends it in `X-Blaze-Prefer`. If the pick fails the gateway itself answers with
 * the next healthy model in the lane and says so in the response headers ([Wire.STATUS] / [Wire.ROUTE] / [Wire.FALLBACK]).
 * Auto (no pick) is always the last resort, and the whole chain when no list is known. No model ids are typed here.
 *
 * Behind the [FLAG] setting, default ON since 0.53.0 (owner, 13 Nov 2026: A-team routing in the app); off = Auto only.
 * Pure Kotlin + org.json; the HTTP fetch is injected ([Cache]).
 */
object LiveModels {
    /** Settings key; off by default until the owner turns it on. */
    const val FLAG = "live_models"

    /** Wire names the deployed gateway reads and writes. Change only with a gateway redeploy that takes old and new. */
    object Wire {
        const val PREFER = "X-Blaze-Prefer"
        const val AUTO = "blaze-auto"
        const val RESP_PREFIX = "x-blaze-"
        const val AVOID = "X-Blaze-Avoid"
        const val LIVE = "/v1/models/live"
        const val STATUS = "x-blaze-prefer-status"
        const val ROUTE = "x-blaze-route"
        const val FALLBACK = "x-blaze-fallback"
    }

    const val TTL_MS = 5 * 60_000L
    const val MAX_AGE_MS = 6 * 3_600_000L
    const val AVOID_MAX = 8
    const val SKIP_ERR_MS = 5 * 60_000L
    const val SKIP_PLAN_MS = 24 * 3_600_000L

    data class Member(val key: String, val pref: String?, val name: String, val prov: String, val model: String = "", val route: String = "",
                      val lanes: List<String> = emptyList(), val ms: Int = 0, val success: Double? = null, val tools: Boolean = false, val vision: Boolean = false,
                      /** Gateway 0.9.11+: reasoning efforts this model takes ("low", "medium", "high"); empty = no effort control. */
                      val efforts: List<String> = emptyList(), val defaultEffort: String? = null)

    /** Auto: no pick, the gateway's lane chooses. */
    val AUTO = Member("auto", null, "Auto", "ARGUS gateway")
    val CHAIN: List<Member> = listOf(AUTO)

    /** The live URL for a gateway base such as "https://…/v1/". */
    fun liveUrl(base: String): String = base.trimEnd('/').removeSuffix("/v1") + Wire.LIVE

    // ---------------- names ----------------
    private val ACR = mapOf("gpt" to "GPT", "deepseek" to "DeepSeek", "minimax" to "MiniMax", "oss" to "OSS", "glm" to "GLM", "ai" to "AI", "it" to "IT", "vl" to "VL", "moe" to "MoE")
    private val PROV = mapOf("groq" to "Groq", "cerebras" to "Cerebras", "gemini" to "Gemini", "openrouter" to "OpenRouter", "infron" to "Infron",
        "sambanova" to "SambaNova", "cloudflare" to "Cloudflare", "workers" to "Cloudflare", "mistral" to "Mistral", "nvidia" to "NVIDIA", "ap" to "Apinex")

    private val DATE_STAMP = Regex("^(\\d{4}|\\d{6}|\\d{8})$")
    private val VERSION_PART = Regex("^[A-Za-z]?\\d{1,2}(\\.\\d{1,2})*$")

    /** "openai/gpt-oss-120b:free" → "GPT OSS 120b"; "claude-opus-5-5" → "Claude Opus 5.5"; "mimo-v2-6-pro" → "Mimo V2.6 Pro". */
    fun prettyName(model: String?): String {
        val m = model.orEmpty()
        val last = m.substringAfterLast('/').replace(Regex(":free$", RegexOption.IGNORE_CASE), "")
        val words = last.split(Regex("[-_\\s]+")).filter { it.isNotEmpty() }.toMutableList()
        // Owner 21:10 screenshot, "Deepseek V4 1 Flash 1017": a release date stamp (1017, 2507, 20251017, 2024-08-06) is not part of the name.
        if (words.size > 3 && words.takeLast(3).let { (y, m, d) -> y.length == 4 && m.length == 2 && d.length == 2 && (y + m + d).all { it.isDigit() } }) repeat(3) { words.removeAt(words.size - 1) }
        while (words.size > 1 && DATE_STAMP.matches(words.last())) words.removeAt(words.size - 1)
        // "claude-opus-5-5" / "gemini-3-8-flash": short number runs are one version ("5.5", "3.8"), not two words.
        val joined = ArrayList<String>()
        for (w in words) { val p = joined.lastOrNull(); if (p != null && w.length <= 2 && w.all { it.isDigit() } && VERSION_PART.matches(p)) joined[joined.size - 1] = "$p.$w" else joined.add(w) }
        val out = joined.joinToString(" ") { w -> ACR[w.lowercase()] ?: if (w[0].isDigit()) w else w[0].uppercaseChar() + w.substring(1) }
        return out.ifEmpty { m }
    }

    fun provName(p: String?): String = PROV[p.orEmpty().lowercase()] ?: p.orEmpty().replaceFirstChar { it.uppercaseChar() }

    // ---------------- the live list ----------------
    /** A capability the gateway's probe confirmed (`verified` as a list or an object), else the entry's own flag. */
    private fun has(e: JSONObject, cap: String): Boolean = when (val v = e.opt("verified")) {
        is JSONArray -> (0 until v.length()).any { v.optString(it) == cap }
        is JSONObject -> if (v.has(cap)) v.optBoolean(cap) else e.optBoolean(cap)
        else -> e.optBoolean(cap)
    }

    private fun member(e: JSONObject): Member {
        val prov0 = e.optString("provider"); val model0 = e.optString("model")
        val id = e.optString("id").ifEmpty { if (prov0.isNotEmpty() && model0.isNotEmpty()) "$prov0/$model0" else "" }
        val cut = id.indexOf('/')
        val prov = prov0.ifEmpty { if (cut > 0) id.substring(0, cut) else "" }
        val model = model0.ifEmpty { if (cut >= 0) id.substring(cut + 1) else id }
        val lanes = e.optJSONArray("lanes")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        return Member(id, id, prettyName(model), provName(prov), model, e.optString("route"), lanes,
            e.optDouble("latency_ms", 0.0).let { if (it.isNaN()) 0 else it.toInt() },
            if (e.has("success") && !e.isNull("success")) e.optDouble("success") else null, has(e, "tools"), has(e, "vision"),
            e.optJSONArray("efforts")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it in EFFORTS } } ?: emptyList(),
            e.optString("default_effort").takeIf { it in EFFORTS })
    }

    /** The list from GET [Wire.LIVE] in the gateway's order (fastest first). Bad entries, duplicates and Auto are dropped. */
    fun parseLive(json: JSONObject?): List<Member> {
        val data = json?.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Member>(); val seen = HashSet<String>()
        for (i in 0 until data.length()) {
            val e = data.optJSONObject(i) ?: continue
            val m = member(e)
            if (m.key.indexOf('/') < 1 || m.key == Wire.AUTO || !seen.add(m.key)) continue
            out += m
        }
        return out
    }

    fun parseLive(text: String?): List<Member> = try { parseLive(text?.let { JSONObject(it) }) } catch (_: Exception) { emptyList() }

    // ---------------- picking ----------------
    /** What the call needs: tools (agent runs) want a tools-verified model in the agent lane, images want vision. */
    data class Need(val lane: String? = null, val tools: Boolean = false, val vision: Boolean = false, val avoid: Collection<String> = emptyList())

    private fun fits(m: Member, n: Need) = (!n.tools || m.tools) && (!n.vision || m.vision)

    /** First live member for the task: lane + capabilities, then capabilities in any lane, else null. */
    fun pick(list: List<Member>?, need: Need = Need()): Member? {
        val lane = need.lane ?: if (need.tools) "agent" else "chat"
        val av = need.avoid.toSet()
        val pool = list.orEmpty().filter { it.key !in av && it.route !in av }
        return pool.firstOrNull { lane in it.lanes && fits(it, need) } ?: pool.firstOrNull { fits(it, need) }
    }

    /** The try order for one call: the turn's pick ([keep], while still listed and fitting) or a fresh pick, then Auto. */
    fun plan(list: List<Member>?, need: Need = Need(), keep: Member? = null): List<Member> {
        val still = keep?.pref?.let { list.orEmpty().firstOrNull { m -> m.key == keep.key && fits(m, need) } }
        val first = still ?: pick(list, need)
        return if (first != null) listOf(first, AUTO) else listOf(AUTO)
    }

    /** The efforts the gateway may list, in order. */
    val EFFORTS = listOf("low", "medium", "high")

    /** Cloud.task → lane. The gateway's lanes have the same names as its task hints. */
    fun laneFor(task: String): String = if (task in LANES) task else "chat"
    val LANES = setOf("chat", "quick", "code", "vision", "long", "agent")

    // ---------------- after the answer ----------------
    data class Answered(val status: String, val route: String, val fallback: Boolean, val model: Member?)

    /** Which model answered, from the response headers (any case). [Answered.model] = the member whose route matches. */
    fun answered(headers: Map<String?, Any?>?, list: List<Member>?): Answered {
        val h = HashMap<String, String>()
        headers?.forEach { (k, v) -> if (k != null) h[k.lowercase()] = (if (v is List<*>) v.firstOrNull() else v)?.toString().orEmpty() }
        val route = h[Wire.ROUTE].orEmpty(); val status = h[Wire.STATUS].orEmpty(); val fb = h[Wire.FALLBACK]
        return Answered(status, route, status == "fallback" || fb == "1", if (route.isNotEmpty()) list.orEmpty().firstOrNull { it.route == route } else null)
    }

    /** [Wire.AVOID] value for routes the turn saw fail (max 8, first kept), or "" for none. */
    fun avoidHeader(keys: List<String?>?): String = keys.orEmpty().filterNotNull().filter { it.isNotEmpty() }.distinct().take(AVOID_MAX).joinToString(", ")

    // ---------------- failures ----------------
    data class Fail(val kind: String, val next: Boolean, val skipMs: Long = 0, val retryNoTools: Boolean = false, val fatal: Boolean = false)

    private val planWords = Regex("""model_not_in_plan|\b(plan|upgrade|subscription|not (allowed|available|included)|paid|credits?|premium|tier)\b""", RegexOption.IGNORE_CASE)
    fun planErr(status: Int, msg: String?): Boolean = status == 402 || status == 403 || planWords.containsMatchIn(msg.orEmpty())

    /** What to do after a model gave no usable answer before streaming started (status 0 = network; ok = empty answer). */
    fun onFail(status: Int = 0, ok: Boolean = false, error: String = "", withTools: Boolean = false): Fail = when {
        status == 401 -> Fail("auth", next = false, fatal = true)
        withTools && status == 400 && Regex("tool|function", RegexOption.IGNORE_CASE).containsMatchIn(error) -> Fail("no_tools", next = false, retryNoTools = true)
        ok -> Fail("empty", next = true)
        planErr(status, error) -> Fail(if (Regex("model_not_in_plan", RegexOption.IGNORE_CASE).containsMatchIn(error)) "model_not_in_plan" else "plan", true, SKIP_PLAN_MS)
        status == 0 -> Fail("unreachable", true, SKIP_ERR_MS)
        else -> Fail("http_$status", true, SKIP_ERR_MS)
    }

    // ---------------- the cache ----------------
    /**
     * The list, fetched at most every [ttlMs] by [fetch] (the body of GET [Wire.LIVE], or null on any failure). A failed
     * fetch keeps the last good list until it is [maxAgeMs] old; after that the list is empty and every call is Auto.
     */
    class Cache(private val now: () -> Long = System::currentTimeMillis, private val ttlMs: Long = TTL_MS, private val maxAgeMs: Long = MAX_AGE_MS) {
        @Volatile private var members: List<Member> = emptyList()
        @Volatile private var goodAt = Long.MIN_VALUE / 2
        @Volatile private var triedAt = Long.MIN_VALUE / 2

        fun due(): Boolean = now() - triedAt >= ttlMs

        /** Ticks after every fetch (good or not), so the chip re-reads the list the moment it lands, not on its next open. */
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)

        /** A list is in hand (owner 2026-11-14: until then a pick is "checking", never "unavailable"). */
        fun known(): Boolean = list().isNotEmpty()

        /** Refreshes when due; returns true when it fetched. */
        @Synchronized fun refresh(fetch: () -> String?): Boolean {
            if (!due()) return false
            triedAt = now()
            val got = try { fetch() } catch (_: Exception) { null }
            val l = parseLive(got)
            if (l.isNotEmpty()) { members = l; goodAt = triedAt }
            changes.value = changes.value + 1
            return true
        }

        fun list(): List<Member> = if (now() - goodAt <= maxAgeMs) members else emptyList()

        /** When the list last came back good (ModelPick: a model hidden after a miss comes back only on a newer list). */
        fun goodAtMs(): Long = goodAt

        fun clear() { members = emptyList(); goodAt = Long.MIN_VALUE / 2; triedAt = Long.MIN_VALUE / 2; changes.value = changes.value + 1 }
    }

    /** The app's one cache (LiveModelsFeed fills it). */
    val shared = Cache()
}
