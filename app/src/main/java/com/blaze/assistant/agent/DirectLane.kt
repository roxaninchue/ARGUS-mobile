package com.blaze.assistant.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import com.blaze.assistant.connect.UrlSafety
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * 0.54.5: the gateway's Direct lane (gateway 0.9.35, docs/DIRECT-LANE.md in the gateway repo). A streaming turn on the main
 * lane asks the gateway once for a signed turn ticket (5 min, bound to this account and this phone), then streams the
 * reply from `/v1/direct`: one model call, no routing waits, the server puts its rules back in.
 *
 * 0.54.6: tool turns go direct too. The ticket is asked for the turn's task (`chat`, or `agent` for a turn with tools)
 * with `tools: true` and `device_tools` = every tool name the request carries (plus names seen on earlier turns, so one
 * ticket serves the next turns too). The relay drops any tool the ticket doesn't list, so before using it the app reads
 * the ticket's own payload (`tools` + `dtools`, [Ticket.allowed]) and goes direct only when it covers EVERY tool it sends
 * ([covers]); otherwise the turn takes the normal route with all its tools (none is ever lost). Only the transport
 * changes: tool calls come back to the agent loop as before and run the same way (phone tools on the phone, service
 * tools through their own path, the in-app confirms for send / pay / delete / change).
 *
 * Never stuck: no first token (text, thinking or a tool call) within [firstDeadline] (the ticket model's measured p95
 * when the gateway sends it, else a bound that grows with the prompt), any error, a refused ticket, or the lane switched
 * off (404) gives `null`, and [LlmClient] sends the same turn down the normal gateway route, showing [BUSY_LINE] ("Busy,
 * retrying", never "unreachable"). A 404 rests the lane for [OFF_MS]; two first-token timeouts in a row rest that task
 * for [SLOW_REST_MS], so a slow lane can't make every turn slower. Pictures and the background lanes never come here.
 */
object DirectLane {
    const val BUSY_LINE = "Busy, retrying"
    /** First-token wait with no measured p95, for a prompt up to [SIZE_FREE_TOKENS]. */
    const val FIRST_MS_DEFAULT = 1_500L
    /** Bounds for any first-token wait. */
    const val FIRST_MS_MIN = 1_000L
    const val FIRST_MS_MAX = 8_000L
    /** Prompt tokens a first-token wait covers before it grows (default bound / measured p95: typical main-chat requests). */
    const val SIZE_FREE_TOKENS = 2_000
    const val P95_SIZE_FREE_TOKENS = 8_000
    /** Extra wait per 1,000 prompt tokens past those (prefill). */
    const val MS_PER_1K_TOKENS = 100L
    /** A measured p95 gets this headroom (x1.25) plus the relay hop. */
    const val P95_HOP_MS = 300L
    /** A ticket is reused until this close to its expiry. */
    const val RENEW_BEFORE_MS = 30_000L
    /** Lane switched off on the gateway (404): don't ask again for this long. */
    const val OFF_MS = 10 * 60_000L
    /** Any other refusal (no green model, rate limit, tools service down): rest a little. */
    const val REST_MS = 30_000L
    /** Two first-token timeouts in a row: this task rests this long (each timeout costs the user its wait). */
    const val SLOW_REST_MS = 2 * 60_000L
    const val DEVICE_KEY = "direct_device_id"
    /** The tasks the gateway issues tickets for (ticket.ts DIRECT_TASKS). */
    val TASKS = setOf("chat", "agent")
    /** Tool names the gateway accepts in a ticket (ticket.ts TOOL_NAME_RE, TICKET_MAX_TOOLS). */
    val TOOL_NAME_RE = Regex("^[A-Za-z0-9_.:-]{1,128}$")
    const val MAX_TOOLS = 256
    /** The gateway reads at most 4,096 bytes of ticket body (readJSON); stay under it. */
    const val TICKET_BODY_MAX = 3_800

    /**
     * A ticket. [allowed]: the tool names the relay keeps (the payload's `tools` + `dtools`); [withTools]: asked with
     * `tools: true` (its models were picked to take tools); [p95]: route token → measured p95 first byte (gateway 0.9.37).
     */
    data class Ticket(val token: String, val exp: Long, val routes: List<String>, val task: String,
                      val allowed: Set<String> = emptySet(), val withTools: Boolean = false, val p95: Map<String, Long> = emptyMap())

    /** 0.54.6 timing: what one direct try cost (TurnTiming): the ticket fetch (-1 = a cached ticket or none) and why it fell back. */
    class Try { @Volatile var ticketMs = -1L; @Volatile var fallback = "" }

    private val tickets = java.util.concurrent.ConcurrentHashMap<String, Ticket>()
    private val restUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val slowStreak = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile private var offUntil = 0L
    /** Tool names seen on recent turns, newest last: a ticket names them all so it serves the next turns too. */
    private val known = LinkedHashSet<String>()
    /** Diagnostics: why the last direct try fell back ("" = it answered). */
    @Volatile var lastFallback: String = ""

    /** Settings "direct_lane" (default on; the gateway's own switch decides) and not switched off / resting for [task]. */
    fun enabled(now: Long = System.currentTimeMillis(), task: String = ""): Boolean =
        !resting(task, now) && try { Settings.bool("direct_lane", true) } catch (_: Throwable) { false }

    /** Switched off (404) or [task] resting after a refusal or two slow tries (pure: no Settings). */
    fun resting(task: String, now: Long = System.currentTimeMillis()): Boolean = now < offUntil || now < (restUntil[task] ?: 0L)

    /**
     * A streaming turn on the main lane, for a task the gateway tickets, with no pictures; tools are fine when every name
     * is one a ticket can carry (a ticket must then also [covers] them).
     */
    fun eligible(task: String, streaming: Boolean, main: Boolean, tools: JSONArray?, messages: JSONArray): Boolean =
        task in TASKS && streaming && main && !hasImages(messages) && toolsOk(tools)

    fun toolsOk(tools: JSONArray?): Boolean {
        val n = toolNames(tools)
        return n.size <= MAX_TOOLS && n.size == (tools?.length() ?: 0) && n.all { TOOL_NAME_RE.matches(it) }
    }

    /** Function names of an OpenAI tools array ("" for an entry without one). */
    fun toolNames(tools: JSONArray?): List<String> = if (tools == null) emptyList()
        else (0 until tools.length()).map { tools.optJSONObject(it)?.optJSONObject("function")?.optString("name").orEmpty() }.filter { it.isNotBlank() }

    fun hasImages(messages: JSONArray): Boolean {
        for (i in 0 until messages.length()) {
            val c = messages.optJSONObject(i)?.opt("content") as? JSONArray ?: continue
            for (j in 0 until c.length()) if (c.optJSONObject(j)?.optString("type") == "image_url") return true
        }
        return false
    }

    /** A cached ticket still worth using. */
    fun fresh(t: Ticket?, now: Long): Boolean = t != null && t.exp - RENEW_BEFORE_MS > now

    /** The ticket lets every one of [names] through (and was picked for tools when there are any). */
    fun covers(t: Ticket, names: Collection<String>): Boolean = names.isEmpty() || (t.withTools && t.allowed.containsAll(names))

    /** A cached ticket for [task] that is fresh and covers [names], or null. */
    fun usable(t: Ticket?, task: String, names: Collection<String>, now: Long): Ticket? =
        t?.takeIf { it.task == task && fresh(it, now) && covers(it, names) }

    /** The gateway's ticket answer, or null when it isn't one. [withTools]: what we asked for. */
    fun parseTicket(body: String, withTools: Boolean = false): Ticket? = try {
        val j = JSONObject(body)
        val tok = j.optString("ticket")
        val exp = j.optLong("exp")
        val ms = j.optJSONArray("models") ?: JSONArray()
        val routes = (0 until ms.length()).mapNotNull { ms.optJSONObject(it)?.optString("route")?.takeIf { r -> r.isNotBlank() } }
        val p95 = (0 until ms.length()).mapNotNull { i -> ms.optJSONObject(i)?.let { m -> m.optLong("p95_ms", 0).takeIf { it > 0 }?.let { m.optString("route") to it } } }.toMap()
        if (tok.count { it == '.' } == 1 && exp > 0 && routes.isNotEmpty())
            Ticket(tok, exp, routes, j.optString("task", "chat"), payloadTools(tok), withTools, p95) else null
    } catch (_: Exception) { null }

    /** The tool names a ticket's (unencrypted, signed) payload lets through: `tools` + `dtools`. Empty when unreadable. */
    fun payloadTools(ticket: String): Set<String> = try {
        val p = JSONObject(String(b64u(ticket.substringBefore('.')), Charsets.UTF_8))
        fun arr(k: String) = p.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        (arr("tools") + arr("dtools")).toSet()
    } catch (_: Exception) { emptySet() }

    /** base64url without padding (java.util.Base64, so it reads the same on the phone and in tests). */
    private fun b64u(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s.trimEnd('='))

    /** The ticket request body: task, stated input size, and for a tool turn `tools: true` + the names to keep. */
    fun ticketBody(task: String, inTokens: Int, names: List<String>, extra: Collection<String> = emptyList()): JSONObject {
        val b = JSONObject().put("task", task).put("in_tokens", inTokens.coerceIn(0, 1_000_000))
        if (names.isEmpty()) return b
        b.put("tools", true)
        val list = JSONArray()
        var bytes = b.toString().length + 20
        // The turn's own names first (all of them, or no ticket is worth asking for), then earlier turns' while they fit.
        for (n in (names + extra.filter { it !in names }).distinct()) {
            if (!TOOL_NAME_RE.matches(n) || list.length() >= MAX_TOOLS) continue
            if (bytes + n.length + 3 > TICKET_BODY_MAX) break
            list.put(n); bytes += n.length + 3
        }
        return b.put("device_tools", list)
    }

    /**
     * How long to wait for the first token before the normal route takes the turn. With the ticket model's measured
     * [p95] (gateway 0.9.37): p95 x 1.25 + the relay hop, growing past [P95_SIZE_FREE_TOKENS] prompt tokens. Without one:
     * [FIRST_MS_DEFAULT], growing past [SIZE_FREE_TOKENS]. A big prompt legitimately takes longer to its first token
     * (prefill), so a fixed 1.5 s would throw away good answers and make those turns slower; [FIRST_MS_MAX] caps it.
     */
    fun firstDeadline(p95: Long?, inTokens: Int): Long {
        fun extra(free: Int) = (inTokens - free).coerceAtLeast(0) / 1000L * MS_PER_1K_TOKENS
        val ms = if (p95 != null && p95 > 0) p95 * 5 / 4 + P95_HOP_MS + extra(P95_SIZE_FREE_TOKENS) else FIRST_MS_DEFAULT + extra(SIZE_FREE_TOKENS)
        return ms.coerceIn(FIRST_MS_MIN, FIRST_MS_MAX)
    }

    /** How long to rest after the gateway refused a ticket with [code]. */
    fun restFor(code: Int): Long = if (code == 404) OFF_MS else REST_MS

    /** A first-token timeout: the second in a row rests [task] for [SLOW_REST_MS]. True when it now rests. */
    fun noteSlow(task: String, now: Long = System.currentTimeMillis()): Boolean {
        val n = slowStreak.merge(task, 1, Int::plus) ?: 1
        if (n < 2) return false
        slowStreak.remove(task); restUntil[task] = now + SLOW_REST_MS
        return true
    }

    private fun noteAnswered(task: String) { slowStreak.remove(task) }

    private fun rest(task: String, code: Int) {
        val until = System.currentTimeMillis() + restFor(code)
        if (code == 404) offUntil = until else restUntil[task] = until
    }

    /** Remembers the tool names a turn sent (newest last, at most [MAX_TOOLS]). */
    fun remember(names: Collection<String>) = synchronized(known) {
        for (n in names) { known.remove(n); known.add(n) }
        while (known.size > MAX_TOOLS) known.remove(known.first())
    }

    private fun knownNames(): List<String> = synchronized(known) { known.toList().asReversed() }

    /** This phone's id for ticket binding: random, made once, never derived from hardware ids. */
    fun deviceId(): String {
        val have = try { Settings.str(DEVICE_KEY) } catch (_: Throwable) { "" }
        if (DEVICE_RE.matches(have)) return have
        val b = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val id = "a" + android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        try { Settings.setStr(DEVICE_KEY, id) } catch (_: Throwable) { }
        return id
    }
    val DEVICE_RE = Regex("^[A-Za-z0-9_-]{8,64}$")

    private fun base(): String = Providers.GATEWAY_BASE.trimEnd('/')

    private fun read(conn: HttpURLConnection, code: Int): String =
        try { ((if (code in 200..299) conn.inputStream else conn.errorStream)?.use { String(it.readBytes()) }).orEmpty() } catch (_: Exception) { "" }

    private fun newTicket(token: String, dev: String, task: String, inTokens: Int, names: List<String>): Ticket? {
        val c = UrlSafety.open("${base()}/ticket")
        c.requestMethod = "POST"; c.connectTimeout = 4_000; c.readTimeout = 4_000; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("X-Argus-Device", dev)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(ticketBody(task, inTokens, names, knownNames()).toString().toByteArray()) }
        val code = c.responseCode
        val body = read(c, code)
        if (code !in 200..299) { rest(task, code); lastFallback = "ticket $code"; return null }
        val t = parseTicket(body, names.isNotEmpty())
        if (t == null) { rest(task, 0); lastFallback = "ticket unreadable"; return null }
        if (!covers(t, names)) { lastFallback = "ticket lacks " + names.filter { it !in t.allowed }.take(3).joinToString(", "); return null }
        return t
    }

    /**
     * 0.54.6: while the user types (LlmClient.prewarm), get a tools ticket ready for the next turn when the cached one is
     * missing or near its end, so the turn itself skips the ticket round trip. Only once a turn has shown which tools it sends.
     */
    fun prewarm(token: String) {
        val names = knownNames()
        if (token.isBlank() || names.isEmpty() || !enabled(task = "agent")) return
        val now = System.currentTimeMillis()
        if (usable(tickets["agent"], "agent", names, now) != null) return
        try { newTicket(token, deviceId(), "agent", 0, names)?.let { tickets["agent"] = it } } catch (_: Exception) { }
    }

    /**
     * One direct try. Streams into [onText] like [LlmClient.call] and returns the assistant message, or null to fall back.
     * [tools] / [toolChoice]: the request's tools as the normal route would send them (null = a plain turn). A stream that
     * showed some words and then broke also returns null: the normal route's reply replaces them.
     */
    suspend fun tryChat(messages: JSONArray, maxTokens: Int, onText: (String) -> Unit, onThink: ((String) -> Unit)?,
                        task: String = "chat", tools: JSONArray? = null, toolChoice: Any? = null,
                        firstMs: Long = -1, info: Try = Try()): JSONObject? = withContext(Dispatchers.IO) {
        val token = try { Settings.gatewayToken() } catch (_: Throwable) { "" }
        if (token.isBlank() || !enabled(task = task)) return@withContext null
        val names = toolNames(tools)
        val dev = deviceId()
        val inTokens = ((messages.toString().length + (tools?.toString()?.length ?: 0)) / 4).coerceAtMost(1_000_000)
        val st = CallState.current()
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        var wait = 0L
        val res = try {
            val now = System.currentTimeMillis()
            if (names.isNotEmpty()) remember(names)
            val t = usable(tickets[task], task, names, now)
                ?: newTicket(token, dev, task, inTokens, names).also { info.ticketMs = System.currentTimeMillis() - now }?.also { tickets[task] = it }
                ?: run { info.fallback = lastFallback; return@withContext null }
            wait = if (firstMs > 0) firstMs else firstDeadline(t.routes.firstNotNullOfOrNull { t.p95[it] }, inTokens)
            val conn = UrlSafety.open("${base()}/direct")
            conn.requestMethod = "POST"; conn.connectTimeout = 4_000; conn.readTimeout = 30_000; conn.doOutput = true
            conn.setRequestProperty("X-Argus-Ticket", t.token)
            conn.setRequestProperty("X-Argus-Device", dev)
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("stream", true).put("messages", messages).put("max_tokens", maxTokens)
            if (tools != null && tools.length() > 0) { body.put("tools", tools); body.put("tool_choice", toolChoice ?: "auto") }
            // 0.54.6 timing: this call's clock (LlmClient.readSse marks the first token on the turn's CallState).
            st.let { s -> s.callStart = System.currentTimeMillis(); s.gotFirst = false; s.firstTokenMs = 0; s.firstTokenAt = 0; s.headersMs = 0; s.firstTextMs = 0; s.firstTextAt = 0 }
            val gotWord = java.util.concurrent.atomic.AtomicBoolean(false)
            val out = coroutineScope {
                // The first-token deadline (text, thinking or a tool call all count): past it, the socket closes and the read
                // below throws (we fall back).
                val watchdog = launch { delay(wait); if (!gotWord.get() && !st.gotFirst) { timedOut.set(true); try { conn.disconnect() } catch (_: Throwable) { } } }
                try {
                    LlmClient.cancellable(conn) {
                        conn.outputStream.use { it.write(SecretVault.scrub(body.toString()).toByteArray()) }
                        val code = conn.responseCode
                        st.headersMs = System.currentTimeMillis() - st.callStart
                        conn.getHeaderField("Server-Timing")?.let { v -> st.gw = mapOf("server-timing" to v) }
                        if (code !in 200..299) {
                            read(conn, code)
                            if (code == 404) offUntil = System.currentTimeMillis() + OFF_MS
                            if (code == 401) tickets.remove(task)   // expired or refused: a new one next turn
                            lastFallback = "direct $code"
                            null
                        } else {
                            LlmClient.readSse(conn.inputStream, { s -> if (s.isNotEmpty()) gotWord.set(true); onText(s) }, onThink)
                        }
                    }
                } finally { watchdog.cancel() }
            }
            if (out != null) {
                if (!gotWord.get() && out.optJSONArray("tool_calls") == null && out.optString("content").isBlank()) { lastFallback = "empty"; info.fallback = lastFallback; return@withContext null }
                lastFallback = ""
                noteAnswered(task)
                LlmClient.lastServedBy = "direct"
            } else info.fallback = lastFallback
            out
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lastFallback = if (timedOut.get()) "$SLOW_PREFIX$wait ms" else (e.message ?: e.javaClass.simpleName).take(120)
            info.fallback = lastFallback
            null
        }
        // Two first-token timeouts in a row rest this task's lane for a while (each one cost the user its wait).
        if (res == null && timedOut.get()) noteSlow(task)
        res
    }

    /** Fallback reasons that start with this are first-token timeouts. */
    const val SLOW_PREFIX = "no first token in "

    /** Tests: forget cached tickets, rests and remembered tool names. */
    internal fun resetForTests() { tickets.clear(); restUntil.clear(); slowStreak.clear(); offUntil = 0L; lastFallback = ""; synchronized(known) { known.clear() } }
}
