package com.blaze.assistant.team

/**
 * P3-AG-06 (05 §1.3, 06 R8, owner decision D6): how one bot's turns are routed. Pure (ModelPolicyTest).
 *
 * D6: **Automatic everywhere.** A bot's Engine tab offers only
 *  - **Depth** — Automatic / Insight (fast) / Oracle (balanced) / Panoptes (deepest); ids spark / flare / inferno,
 *    sent as the gateway's `blaze_family` hint exactly like the 0.52 composer chip (ModelChoice);
 *  - **effort** — low / medium / high / xhigh, which moves the on-phone ThinkBudget (quick / normal / deep);
 *  - optionally the bot's **own Gemini key** with a Gemini model from Google's own list (A-team v2.14 per-bot Gemini,
 *    with its 429 cool-down: Retry-After, else 60 s, 1 h for a used-up daily quota; while cooling the gateway answers);
 *  - **zero retention** (BrainOrder's privacy rule: only zero-retention models).
 * There is **no model-id or provider picker** and no free-text model id: [sanitize] drops any `pinned` value that is
 * not `gemini:<a Gemini model id>`. A policy equal to the default ([isAutomatic]) yields **no route** ([route] = null),
 * so the request the gateway gets is byte-for-byte what it got before P3 (ModelPolicyTest.automaticChangesNothing).
 */
object ModelPolicies {
    enum class Depth(val id: String?, val label: String, val sub: String) {
        AUTO(null, "Automatic", "Picks for you"),
        SPARK("spark", "Insight", "Fast"),
        FLARE("flare", "Oracle", "Balanced"),
        INFERNO("inferno", "Panoptes", "Deepest");

        companion object { fun of(id: String?): Depth = values().firstOrNull { it.id != null && it.id == id?.trim()?.lowercase() } ?: AUTO }
    }

    val LANES = listOf("chat", "quick", "code", "vision", "long", "agent")
    val EFFORTS = listOf("low", "medium", "high", "xhigh")
    const val GEMINI_PREFIX = "gemini:"
    private val geminiId = Regex("^gemini-[a-z0-9][a-z0-9.\\-]{1,60}$")

    /** The policy every bot starts with (= 05 §9.1 ModelPolicy()). */
    val AUTOMATIC = ModelPolicy()

    /** Cleans a stored / imported / synced policy: unknown lane → agent, unknown tier/effort → default, pinned only `gemini:<id>`. */
    fun sanitize(p: ModelPolicy): ModelPolicy = ModelPolicy(
        lane = p.lane.trim().lowercase().takeIf { it in LANES } ?: AUTOMATIC.lane,
        tier = Depth.of(p.tier).id,
        pinned = geminiModel(p.pinned)?.let { GEMINI_PREFIX + it },
        effort = p.effort.trim().lowercase().takeIf { it in EFFORTS } ?: AUTOMATIC.effort,
        zeroRetention = p.zeroRetention,
    )

    /** The Gemini model id in a pinned value ("gemini:gemini-3.5-flash" or an A-team "models/gemini-…"), or null. */
    fun geminiModel(pinned: String?): String? {
        val raw = pinned?.trim()?.lowercase()?.removePrefix(GEMINI_PREFIX)?.removePrefix("models/") ?: return null
        return raw.takeIf { geminiId.matches(it) }
    }

    fun isAutomatic(p: ModelPolicy): Boolean = sanitize(p) == AUTOMATIC

    // ---------------------------------------------------------------- edits the Engine tab makes

    fun withDepth(p: ModelPolicy, d: Depth) = sanitize(p.copy(tier = d.id))
    fun withEffort(p: ModelPolicy, e: String) = sanitize(p.copy(effort = e))
    fun withZeroRetention(p: ModelPolicy, on: Boolean) = sanitize(p.copy(zeroRetention = on))
    /** Pins a Gemini model from the key's own list ([offered]); anything not offered is refused (null). */
    fun withGemini(p: ModelPolicy, model: String?, offered: List<String>): ModelPolicy? {
        if (model == null) return sanitize(p.copy(pinned = null))
        val m = geminiModel(model) ?: return null
        if (offered.map { geminiModel(it) }.none { it == m }) return null
        return sanitize(p.copy(pinned = GEMINI_PREFIX + m))
    }
    /** "Back to Automatic". */
    fun reset() = AUTOMATIC

    // ---------------------------------------------------------------- one turn

    enum class Think { QUICK, NORMAL, DEEP }

    /** What the turn is (from the existing pure classifiers: Cloud.task, ModelRoute.heavy, ThinkBudget.level). */
    data class Turn(val task: String, val heavy: Boolean, val hasTools: Boolean, val adaptive: Think)

    /**
     * The route for one request when the policy is not Automatic:
     * [task] the gateway lane (`blaze_task`), [family] the `blaze_family` depth hint, [think] the thinking budget,
     * [gemini] a Gemini model to call directly with the bot's own key (null = the gateway), [note] one plain line for the
     * thread when the own key is resting ("Gemini is resting until 14:05; ARGUS answered instead").
     */
    data class Route(val task: String, val family: String?, val think: Think, val gemini: String?, val zeroRetention: Boolean, val note: String? = null)

    /**
     * null = Automatic: the caller sends exactly what it sends today. Otherwise:
     *  - lane: a picture always goes to vision and an action turn with tools to agent (ModelRoute's rule wins); a bot whose
     *    lane is code/long keeps it for plain turns; quick/chat only for turns that are not action turns.
     *  - think: effort low → QUICK, medium → the adaptive level, high → at least NORMAL, xhigh → DEEP; then depth Insight
     *    caps at NORMAL, Panoptes floors at NORMAL.
     *  - gemini: only with a key ([hasKey]) and not cooling ([cooling] = the cool-down message or null).
     */
    fun route(p0: ModelPolicy, t: Turn, hasKey: Boolean = false, cooling: String? = null): Route? {
        val p = sanitize(p0)
        if (p == AUTOMATIC) return null
        val task = when {
            t.task == "vision" -> "vision"
            t.heavy && t.hasTools -> "agent"
            t.task == "long" -> "long"
            p.lane in setOf("code", "long") -> p.lane
            p.lane in setOf("chat", "quick") -> p.lane
            p.lane == "agent" && t.hasTools -> "agent"
            else -> t.task
        }
        var think = when (p.effort) { "low" -> Think.QUICK; "high" -> maxOf(t.adaptive, Think.NORMAL); "xhigh" -> Think.DEEP; else -> t.adaptive }
        when (Depth.of(p.tier)) { Depth.SPARK -> if (think == Think.DEEP) think = Think.NORMAL; Depth.INFERNO -> think = maxOf(think, Think.NORMAL); else -> {} }
        val gm = geminiModel(p.pinned)
        val useGem = gm != null && hasKey && cooling == null && !p.zeroRetention
        val note = when {
            gm == null -> null
            !hasKey -> "No Gemini key for this bot, so ARGUS answered instead."
            cooling != null -> "$cooling ARGUS answered instead."
            p.zeroRetention -> null
            else -> null
        }
        return Route(task, Depth.of(p.tier).id, think, if (useGem) gm else null, p.zeroRetention, note)
    }

    /** The gateway request fields for [r] (Cloud.route); null leaves [body] exactly as it was. */
    fun applyTo(body: org.json.JSONObject, r: Route?): org.json.JSONObject {
        if (r == null) return body
        body.put("blaze_task", r.task)
        r.family?.let { body.put("blaze_family", it) }
        if (r.zeroRetention) body.put("blaze_zero_retention", true)
        return body
    }

    /** Plain words for the Engine tab ("Automatic", "Oracle · thinks harder · own Gemini key"). Never a model id. */
    fun describe(p0: ModelPolicy, hasKey: Boolean): String {
        val p = sanitize(p0)
        if (p == AUTOMATIC) return "Automatic"
        val bits = ArrayList<String>()
        bits += Depth.of(p.tier).label
        when (p.effort) { "low" -> bits += "answers fast"; "high" -> bits += "thinks harder"; "xhigh" -> bits += "thinks hardest" }
        if (geminiModel(p.pinned) != null) bits += if (hasKey) "own Gemini key" else "own Gemini key (missing)"
        if (p.zeroRetention) bits += "private models only"
        return bits.joinToString(" · ")
    }
}

/**
 * A-team gemini.js quota cool-down, per key owner (a bot's own key, or "shared"). Pure: the caller passes the clock.
 * 429 / RESOURCE_EXHAUSTED / "rate limit" → rest for Retry-After seconds, else 1 h for a daily quota, else 60 s.
 * While resting nothing calls Gemini; the gateway answers and the thread says so once. No retries.
 */
class GeminiCooldown {
    data class Rest(val until: Long, val daily: Boolean)
    private val rest = HashMap<String, Rest>()

    fun isQuota(status: Int, msg: String?): Boolean = status == 429 || Regex("(?i)RESOURCE_EXHAUSTED|quota|rate limit|too many requests").containsMatchIn(msg.orEmpty())

    /** Records a quota failure; returns how long it rests (ms). */
    @Synchronized fun onQuota(owner: String, now: Long, retryAfterSec: Int = 0, msg: String? = null): Long {
        val daily = Regex("(?i)per ?day|daily").containsMatchIn(msg.orEmpty())
        val ms = when { retryAfterSec > 0 -> retryAfterSec * 1000L; daily -> 3_600_000L; else -> 60_000L }
        rest[owner] = Rest(now + ms, daily)
        return ms
    }

    /** The plain line while [owner] rests ("Gemini hit its limit (daily quota); resting until 14:05."), else null. */
    @Synchronized fun cooling(owner: String, now: Long, clock: (Long) -> String = { "%tR".format(it) }): String? {
        val r = rest[owner] ?: return null
        if (r.until <= now) { rest.remove(owner); return null }
        return "Gemini hit its limit${if (r.daily) " (daily quota)" else ""}; resting until ${clock(r.until)}."
    }

    @Synchronized fun clear(owner: String) { rest.remove(owner) }
}
