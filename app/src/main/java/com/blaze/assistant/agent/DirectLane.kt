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
 * 0.54.5: the gateway's Direct lane (gateway 0.9.35, docs/DIRECT-LANE.md in the gateway repo). A plain streaming chat turn asks
 * the gateway once for a signed turn ticket (5 min, bound to this account and this phone), then streams the reply from
 * `/v1/direct`: one model call, no routing waits, the server puts its rules back in.
 *
 * Never stuck: no first word within [firstMs] (about the model's p95, 1.5 s), any error, a refused ticket, or the lane
 * switched off (404) gives `null`, and [LlmClient] sends the same turn down the normal gateway route, showing
 * [BUSY_LINE] ("Busy, retrying", never "unreachable"). A 404 rests the lane for [OFF_MS] so a switched-off lane costs
 * nothing. Turns with tools, pictures or the background lanes never come here (tool calls keep the normal route, its
 * /v1/me/execute path and its in-app confirmations).
 */
object DirectLane {
    const val BUSY_LINE = "Busy, retrying"
    const val FIRST_MS_DEFAULT = 1_500L
    /** A ticket is reused until this close to its expiry. */
    const val RENEW_BEFORE_MS = 30_000L
    /** Lane switched off on the gateway (404): don't ask again for this long. */
    const val OFF_MS = 10 * 60_000L
    /** Any other refusal (no green model, rate limit, tools service down): rest a little. */
    const val REST_MS = 30_000L
    const val DEVICE_KEY = "direct_device_id"

    data class Ticket(val token: String, val exp: Long, val routes: List<String>, val task: String)

    /** 0.54.6 timing: what one direct try cost (TurnTiming): the ticket fetch (-1 = a cached ticket or none) and why it fell back. */
    class Try { @Volatile var ticketMs = -1L; @Volatile var fallback = "" }

    @Volatile private var ticket: Ticket? = null
    @Volatile private var restUntil = 0L
    /** Diagnostics: why the last direct try fell back ("" = it answered). */
    @Volatile var lastFallback: String = ""

    /** Settings "direct_lane" (default on; the gateway's own switch decides) and not resting after a refusal. */
    fun enabled(now: Long = System.currentTimeMillis()): Boolean =
        now >= restUntil && try { Settings.bool("direct_lane", true) } catch (_: Throwable) { false }

    /** Only a plain streaming chat turn on the main lane. */
    fun eligible(task: String, streaming: Boolean, main: Boolean, tools: JSONArray?, messages: JSONArray): Boolean =
        task == "chat" && streaming && main && (tools == null || tools.length() == 0) && !hasImages(messages)

    fun hasImages(messages: JSONArray): Boolean {
        for (i in 0 until messages.length()) {
            val c = messages.optJSONObject(i)?.opt("content") as? JSONArray ?: continue
            for (j in 0 until c.length()) if (c.optJSONObject(j)?.optString("type") == "image_url") return true
        }
        return false
    }

    /** A cached ticket still worth using. */
    fun fresh(t: Ticket?, now: Long): Boolean = t != null && t.exp - RENEW_BEFORE_MS > now

    /** The gateway's ticket answer, or null when it isn't one. */
    fun parseTicket(body: String): Ticket? = try {
        val j = JSONObject(body)
        val tok = j.optString("ticket")
        val exp = j.optLong("exp")
        val routes = j.optJSONArray("models")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("route")?.takeIf { r -> r.isNotBlank() } } } ?: emptyList()
        if (tok.count { it == '.' } == 1 && exp > 0 && routes.isNotEmpty()) Ticket(tok, exp, routes, j.optString("task", "chat")) else null
    } catch (_: Exception) { null }

    /** How long to rest after the gateway refused a ticket with [code]. */
    fun restFor(code: Int): Long = if (code == 404) OFF_MS else REST_MS

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

    private fun newTicket(token: String, dev: String, inTokens: Int): Ticket? {
        val c = UrlSafety.open("${base()}/ticket")
        c.requestMethod = "POST"; c.connectTimeout = 4_000; c.readTimeout = 4_000; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("X-Argus-Device", dev)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("task", "chat").put("in_tokens", inTokens).toString().toByteArray()) }
        val code = c.responseCode
        val body = read(c, code)
        if (code !in 200..299) { restUntil = System.currentTimeMillis() + restFor(code); lastFallback = "ticket $code"; return null }
        return parseTicket(body).also { if (it == null) { restUntil = System.currentTimeMillis() + REST_MS; lastFallback = "ticket unreadable" } }
    }

    /**
     * One direct try. Streams into [onText] like [LlmClient.call] and returns the assistant message, or null to fall back.
     * A stream that showed some words and then broke also returns null: the normal route's reply replaces them.
     */
    suspend fun tryChat(messages: JSONArray, maxTokens: Int, onText: (String) -> Unit, onThink: ((String) -> Unit)?,
                        firstMs: Long = FIRST_MS_DEFAULT, info: Try = Try()): JSONObject? = withContext(Dispatchers.IO) {
        val token = try { Settings.gatewayToken() } catch (_: Throwable) { "" }
        if (token.isBlank() || !enabled()) return@withContext null
        val dev = deviceId()
        try {
            val now = System.currentTimeMillis()
            val t = ticket?.takeIf { fresh(it, now) }
                ?: newTicket(token, dev, (messages.toString().length / 4).coerceAtMost(1_000_000)).also { info.ticketMs = System.currentTimeMillis() - now }?.also { ticket = it }
                ?: run { info.fallback = lastFallback; return@withContext null }
            val conn = UrlSafety.open("${base()}/direct")
            conn.requestMethod = "POST"; conn.connectTimeout = 4_000; conn.readTimeout = 30_000; conn.doOutput = true
            conn.setRequestProperty("X-Argus-Ticket", t.token)
            conn.setRequestProperty("X-Argus-Device", dev)
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("stream", true).put("messages", messages).put("max_tokens", maxTokens)
            // 0.54.6 timing: this call's clock (LlmClient.readSse marks the first token on the turn's CallState).
            CallState.current().let { st -> st.callStart = System.currentTimeMillis(); st.gotFirst = false; st.firstTokenMs = 0; st.firstTokenAt = 0; st.headersMs = 0; st.firstTextMs = 0; st.firstTextAt = 0 }
            val gotWord = java.util.concurrent.atomic.AtomicBoolean(false)
            val out = coroutineScope {
                // The first-word deadline: past it, the socket closes and the read below throws (we fall back).
                val watchdog = launch { delay(firstMs); if (!gotWord.get()) try { conn.disconnect() } catch (_: Throwable) { } }
                try {
                    LlmClient.cancellable(conn) {
                        conn.outputStream.use { it.write(SecretVault.scrub(body.toString()).toByteArray()) }
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            read(conn, code)
                            if (code == 404) restUntil = System.currentTimeMillis() + OFF_MS
                            if (code == 401) ticket = null   // expired or refused: a new one next turn
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
                LlmClient.lastServedBy = "direct"
            } else info.fallback = lastFallback
            out
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            lastFallback = (e.message ?: e.javaClass.simpleName).take(120)
            info.fallback = lastFallback
            null
        }
    }

    /** Tests: forget the cached ticket and any rest. */
    internal fun resetForTests() { ticket = null; restUntil = 0L; lastFallback = "" }
}
