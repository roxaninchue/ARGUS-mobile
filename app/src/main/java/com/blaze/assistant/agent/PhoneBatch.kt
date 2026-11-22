package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.55.1: the phone's dumb executor for the gateway's signed phone batches (gateway 0.9.39, docs/CLOUD-LOOP.md there).
 *
 * When the model calls phone tools inside a cloud-loop turn, the gateway sends them as ONE ordered batch
 * `{turnId, ticket, steps: [{id, tool, args, final}], sig}`. `sig` = HMAC-SHA256 over `turnId \n ticket id \n JSON(steps)`
 * with the ticket's `batch_key` (from the /v1/ticket answer). This object:
 *  - runs a batch ONLY when its sig verifies ([verify]) and every step's tool is one this turn may run ([run]'s allowed),
 *  - runs the steps in order through the agent's own runTool (so the approval cards for send / pay / delete / change
 *    stay exactly as they are), and stops at the first failure or decline (the steps after it are reported "not run"),
 *  - says what happens next ([next]): an all-final batch whose steps all succeeded ENDS the turn here with a local
 *    confirmation (no second model call); anything else resumes the turn with the results only.
 *
 * `final` comes only from the gateway (a tool name in its FINAL_TOOLS set): nothing here looks at the user's words or the
 * model's reply. No trigger words. Pure Kotlin + org.json + javax.crypto (PhoneBatchTest); nothing in Agent.runLoop.
 */
object PhoneBatch {
    data class Step(val id: String, val tool: String, val args: String, val final: Boolean)
    data class Batch(val turnId: String, val ticket: String, val steps: List<Step>, val sig: String) {
        val allFinal: Boolean get() = steps.isNotEmpty() && steps.all { it.final }
    }

    /** One step's outcome: [ok] false = failed, declined or not run. */
    data class Done(val id: String, val tool: String, val result: String, val ok: Boolean, val ran: Boolean)
    data class Outcome(val accepted: Boolean, val done: List<Done>, val reason: String = "") {
        val allOk: Boolean get() = accepted && done.isNotEmpty() && done.all { it.ok }
    }

    sealed class Next {
        /** Turn over: show [text], no model call. */
        data class End(val text: String) : Next()
        /** Resume the turn with these results (tool_call_id → content). */
        data class Resume(val results: List<Pair<String, String>>) : Next()
        /** The batch was refused (bad sig / tool not allowed): nothing ran; the caller falls back. */
        data class Refuse(val reason: String) : Next()
    }

    const val NOT_RUN = "Not run: an earlier step failed or was declined."
    /** Result starts that mean the step didn't happen (FastLane.FAIL_STARTS + refusals and gates). */
    val FAIL_STARTS = listOf("Error", "Not run", "Not done", "Not saved", "Refused", "Blocked", "Plan mode:", "Private chat:",
        "Couldn't", "Could not", "Unknown", "No app", "No installed app", "Failed", "Can't", "Cannot", "Bank mode is on")

    fun parse(o: JSONObject?): Batch? = try {
        if (o == null) null else {
            val a = o.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { s ->
                Step(s.optString("id"), s.optString("tool"), s.optString("args", "{}"), s.optBoolean("final", false)) } }
            val b = Batch(o.optString("turnId"), o.optString("ticket"), steps, o.optString("sig"))
            b.takeIf { it.turnId.isNotBlank() && it.sig.isNotBlank() && it.steps.isNotEmpty() && it.steps.all { s -> s.id.isNotBlank() && s.tool.isNotBlank() } }
        }
    } catch (_: Exception) { null }

    /** The exact text the gateway signs: `turnId \n jti \n [{"id","tool","args","final"}...]` (JSON.stringify order). */
    fun message(turnId: String, jti: String, steps: List<Step>): String = buildString {
        append(turnId).append('\n').append(jti).append('\n').append('[')
        steps.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append("{\"id\":").append(JSONObject.quote(s.id)).append(",\"tool\":").append(JSONObject.quote(s.tool))
                .append(",\"args\":").append(jsonString(s.args)).append(",\"final\":").append(s.final).append('}')
        }
        append(']')
    }

    /** JSON.stringify of a string (JSONObject.quote escapes "/" as "\/", which JavaScript doesn't). */
    fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c == '\b' -> append("\\b")
            c == '\u000c' -> append("\\f")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    private fun b64uDecode(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s.trimEnd('='))
    private fun b64u(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun sign(key: String, turnId: String, jti: String, steps: List<Step>): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(b64uDecode(key), "HmacSHA256"))
        return b64u(mac.doFinal(message(turnId, jti, steps).toByteArray(Charsets.UTF_8)))
    }

    /** The batch is for this ticket ([jti]) and its sig verifies with the ticket's [key] (constant-time compare). */
    fun verify(key: String, jti: String, b: Batch): Boolean = try {
        key.isNotBlank() && b.ticket == jti &&
            java.security.MessageDigest.isEqual(sign(key, b.turnId, jti, b.steps).toByteArray(), b.sig.toByteArray())
    } catch (_: Exception) { false }

    /** A tool result that means the step didn't happen (error, declined card, refused). */
    fun failed(result: String): Boolean {
        val r = result.trimStart()
        return r.isEmpty() || FAIL_STARTS.any { r.startsWith(it) } || r.contains("did NOT approve") || r.contains("didn't approve")
    }

    /**
     * Runs [b] in order. Refused (nothing runs) when the sig doesn't verify or a step's tool isn't [allowed]. Stops at the
     * first failed or declined step; the rest are reported [NOT_RUN]. [exec] is the agent's runTool (approval cards included).
     */
    suspend fun run(b: Batch, key: String, jti: String, allowed: (String) -> Boolean, exec: suspend (String, JSONObject) -> String,
                    isFailure: (String) -> Boolean = ::failed): Outcome {
        if (!verify(key, jti, b)) return Outcome(false, emptyList(), "bad_signature")
        b.steps.firstOrNull { !allowed(it.tool) }?.let { return Outcome(false, emptyList(), "not_allowed:${it.tool}") }
        val done = ArrayList<Done>()
        var stopped = false
        for (s in b.steps) {
            if (stopped) { done.add(Done(s.id, s.tool, NOT_RUN, ok = false, ran = false)); continue }
            val args = try { JSONObject(s.args.ifBlank { "{}" }) } catch (_: Exception) { null }
            val r = if (args == null) "Error: the arguments for ${s.tool} weren't valid JSON." else try { exec(s.tool, args) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "Error: ${e.message ?: e.javaClass.simpleName}" }
            val ok = !isFailure(r)
            done.add(Done(s.id, s.tool, r, ok, ran = true))
            if (!ok) stopped = true
        }
        return Outcome(true, done)
    }

    /** What happens after [run]: all-final + all OK ends the turn locally; else resume with the results; refused = fall back. */
    fun next(b: Batch, o: Outcome): Next = when {
        !o.accepted -> Next.Refuse(o.reason)
        b.allFinal && o.allOk -> Next.End(confirmation(o))
        else -> Next.Resume(o.done.map { it.id to it.result })
    }

    /** The local confirmation of an all-final batch: each tool's own result, first line, as the tool wrote it. */
    fun confirmation(o: Outcome): String = o.done.joinToString("\n") { d -> clean(d.result) }.ifBlank { "Done." }

    /**
     * One tool result as a user-facing line: its first line, without the registry id ("[ID A12]") or the internal alarm
     * label ("label “(Argus A12)”"): "Alarm set for 07:00, label “(Argus A12)”. [ID A12]" → "Alarm set for 07:00."
     */
    fun clean(result: String): String {
        var t = result.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().removePrefix("Done: ").trim()
        t = t.replace(Regex("""\s*\[ID [A-Za-z0-9]+]"""), "")
        t = t.replace(Regex(""",\s*label\s*\u201c\([^)]*\b[A-Z]\d+\)\u201d"""), "")
        t = t.replace(Regex("""\s+\(\w+ [A-Z]\d+\)(?=\u201d)"""), "")
        t = t.trim().trimEnd('.').trim()
        return if (t.isEmpty()) "" else "$t."
    }
}
