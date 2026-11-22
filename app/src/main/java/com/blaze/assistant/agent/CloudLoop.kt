package com.blaze.assistant.agent

import com.blaze.assistant.connect.UrlSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection

/**
 * 0.55.1: the gateway's cloud tool loop (gateway 0.9.39: POST /v1/loop, POST /v1/turn/resume; docs/CLOUD-LOOP.md there).
 *
 * One model call from the agent's point of view: [DirectLane.tryChat] hands a tool turn here when its ticket advertises
 * the loop. The gateway runs web_search, fetch_page, find_tools and load_skill itself and calls the model again; the phone
 * only sees text, step events (shown as the usual work-card steps, booked as cloud tool runs in TurnTiming) and, when the
 * model calls phone tools, ONE signed batch. [PhoneBatch] runs that batch through the agent's own runTool (approval cards
 * unchanged, stops at the first failure or decline); an all-final batch that succeeds ends the turn here with no second
 * model call; otherwise the turn resumes with the turn id + results only. An expired turn (410) is sent again whole, with
 * the steps that already happened ([LoopWire.Recorder]), so nothing runs twice.
 *
 * Returns the final assistant message, or null to fall back to the normal route, which only happens while nothing has run
 * on the phone yet. Nothing here is in Agent.runLoop (64 KB limit); the agent's hooks come in on the turn's CallState.
 * No trigger words: the model alone picks tools, `final` comes from the gateway's tool-name set.
 */
object CloudLoop {
    const val PREF = "cloud_loop"
    /** Resumes one turn may make (the gateway caps model calls per request and per turn too). */
    const val MAX_LEGS = 12

    /** Settings "cloud_loop" (default on). */
    fun enabled(): Boolean = try { Settings.bool(PREF, true) } catch (_: Throwable) { true }

    /** The loop can take this turn: the ticket offers it and the agent gave its tool runner (main chat turns). */
    fun usable(t: DirectLane.Ticket, st: CallState): Boolean = t.loop && t.batchKey.isNotBlank() && t.jti.isNotBlank() && st.phoneTool != null && enabled()

    private class Res(val code: Int, val msg: JSONObject?)

    private fun read(conn: HttpURLConnection, code: Int): String =
        try { ((if (code in 200..299) conn.inputStream else conn.errorStream)?.use { String(it.readBytes()) }).orEmpty() } catch (_: Exception) { "" }

    /** One POST to the loop; the stream's argus events go to [onEvent]. [firstWait] > 0: no first token by then = timeout. */
    private suspend fun post(url: String, t: DirectLane.Ticket, dev: String, body: JSONObject, onText: (String) -> Unit, onThink: ((String) -> Unit)?,
                             firstWait: Long, gotWord: java.util.concurrent.atomic.AtomicBoolean, onEvent: (JSONObject) -> Unit): Res {
        val st = CallState.current()
        val conn = UrlSafety.open(url)
        conn.requestMethod = "POST"; conn.connectTimeout = 4_000; conn.readTimeout = 70_000; conn.doOutput = true
        conn.setRequestProperty("X-Argus-Ticket", t.token)
        conn.setRequestProperty("X-Argus-Device", dev)
        conn.setRequestProperty("Content-Type", "application/json")
        return coroutineScope {
            val watchdog = if (firstWait > 0) launch { delay(firstWait); if (!gotWord.get() && !st.gotFirst) try { conn.disconnect() } catch (_: Throwable) { } } else null
            try {
                LlmClient.cancellable(conn) {
                    conn.outputStream.use { it.write(SecretVault.scrub(body.toString()).toByteArray()) }
                    val code = conn.responseCode
                    if (st.headersMs == 0L) st.headersMs = System.currentTimeMillis() - st.callStart
                    conn.getHeaderField("Server-Timing")?.let { v -> st.gw = mapOf("server-timing" to v) }
                    if (code !in 200..299) { read(conn, code); Res(code, null) }
                    else Res(code, LlmClient.readSse(LoopWire.EventFilter(conn.inputStream, onEvent), { s -> if (s.isNotEmpty()) gotWord.set(true); onText(s) }, onThink))
                }
            } finally { watchdog?.cancel() }
        }
    }

    /**
     * Runs the turn on the loop. [messages] / [tools] / [toolChoice]: what the direct lane would send. [firstWait]: the
     * first-token deadline of the first request. Null = fall back (only before anything ran on the phone).
     */
    suspend fun run(t: DirectLane.Ticket, dev: String, base: String, messages: JSONArray, tools: JSONArray?, toolChoice: Any?, maxTokens: Int,
                    onText: (String) -> Unit, onThink: ((String) -> Unit)?, firstWait: Long, gotWord: java.util.concurrent.atomic.AtomicBoolean): JSONObject? = withContext(Dispatchers.IO) {
        val st = CallState.current()
        val exec = st.phoneTool ?: return@withContext null
        val rec = LoopWire.Recorder()
        val offered = LinkedHashSet(DirectLane.toolNames(tools))
        var addedTools = JSONArray()
        var batch: PhoneBatch.Batch? = null
        var pause: LoopWire.Pause? = null
        var failed = ""
        var phoneRan = false
        var resent = false
        val onEvent: (JSONObject) -> Unit = { o ->
            rec.event(o)
            when (o.optString("type")) {
                "tool" -> st.stepLine?.invoke(Friendly.exact(o.optString("name"), try { JSONObject(o.optString("arguments", "{}")) } catch (_: Exception) { JSONObject() }) + " \u00b7 cloud")
                "tool_result" -> st.timing.cloudTool(o.optString("name"), o.optLong("ms"))
                "model" -> st.timing.cloudModel()
                "batch" -> batch = PhoneBatch.parse(o)
                "pause" -> pause = LoopWire.pause(o)
                "error" -> failed = o.optString("reason", "error")
            }
        }
        fun lastResort(): JSONObject = JSONObject().put("role", "assistant")
            .put("content", "Here's what I did before the connection dropped:\n" + rec.phoneResultsText()).also { onText(it.optString("content")) }
        suspend fun send(path: String, body: JSONObject, wait: Long = 0): Res {
            batch = null; pause = null; failed = ""
            return post("$base$path", t, dev, body, onText, onThink, wait, gotWord, onEvent)
        }
        suspend fun resendWhole(): Res? {
            if (resent) return null
            resent = true
            st.timing.roundTrip(TurnTiming.RESEND)
            val all = JSONArray().also { a -> for (i in 0 until (tools?.length() ?: 0)) a.put(tools!!.get(i)); for (i in 0 until addedTools.length()) a.put(addedTools.get(i)) }
            return send("/loop", LoopWire.startBody(rec.resend(messages), all, toolChoice, maxTokens, available()))
        }

        var res = send("/loop", LoopWire.startBody(messages, tools, toolChoice, maxTokens, available()), firstWait)
        var legs = 0
        while (true) {
            if (res.code == 410) { res = resendWhole() ?: return@withContext if (phoneRan) lastResort() else null; continue }
            if (res.code !in 200..299 || res.msg == null) return@withContext if (phoneRan) (resendWhole()?.takeIf { it.code in 200..299 && pause == null && failed.isEmpty() }?.msg ?: lastResort()) else null
            val p = pause
            if (p == null) {
                if (failed.isNotEmpty()) return@withContext if (phoneRan) lastResort() else null
                return@withContext res.msg   // the answer (or the model's own last word)
            }
            if (++legs > MAX_LEGS) return@withContext if (phoneRan) lastResort() else null
            when (p.reason) {
                "need_tools" -> {
                    val s = LoopWire.schemas(LeanTurn.lastAll ?: tools, p.needTools)
                    for (i in 0 until s.length()) { s.optJSONObject(i)?.optJSONObject("function")?.optString("name")?.let { offered.add(it) }; addedTools.put(s.get(i)) }
                    LeanTurn.current()?.found?.addAll(p.needTools)
                    if (!p.resumable) { res = resendWhole() ?: return@withContext if (phoneRan) lastResort() else null; continue }
                    st.timing.roundTrip(TurnTiming.RESUME)
                    res = send("/turn/resume", LoopWire.resumeBody(p.turnId, emptyList(), s))
                }
                "phone_tool" -> {
                    val b = batch ?: return@withContext if (phoneRan) lastResort() else null
                    rec.batch(b)
                    val out = coroutineScope {
                        // A step may wait on an approval card: keep the turn's watchdog from reading that as a hung call.
                        val beat = launch { while (true) { st.beat(); delay(1_000) } }
                        try { PhoneBatch.run(b, t.batchKey, t.jti, { it in offered }, exec) } finally { beat.cancel() }
                    }
                    if (out.done.any { it.ran }) phoneRan = true
                    when (val n = PhoneBatch.next(b, out)) {
                        is PhoneBatch.Next.Refuse -> { st.errors.add("phone batch refused: ${n.reason}"); return@withContext if (phoneRan) lastResort() else null }
                        is PhoneBatch.Next.End -> {
                            rec.results(out.done.map { it.id to it.result })
                            st.timing.roundTrip(TurnTiming.LOCAL_END)
                            onText(n.text)
                            return@withContext JSONObject().put("role", "assistant").put("content", n.text)
                        }
                        is PhoneBatch.Next.Resume -> {
                            rec.results(n.results)
                            if (!p.resumable) { res = resendWhole() ?: return@withContext lastResort(); continue }
                            st.timing.roundTrip(TurnTiming.RESUME)
                            res = send("/turn/resume", LoopWire.resumeBody(p.turnId, n.results))
                        }
                    }
                }
                else -> {   // step_cap / time_cap: nothing to run, the turn goes on
                    if (!p.resumable) { res = resendWhole() ?: return@withContext if (phoneRan) lastResort() else null; continue }
                    st.timing.roundTrip(TurnTiming.RESUME)
                    res = send("/turn/resume", LoopWire.resumeBody(p.turnId, emptyList()))
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    /** Every tool this phone could send (find_tools on the gateway offers only these). */
    private fun available(): List<String> = DirectLane.toolNames(LeanTurn.lastAll)
}
