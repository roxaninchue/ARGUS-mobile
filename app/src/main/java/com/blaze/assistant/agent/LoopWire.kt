package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.55.1: the wire side of the gateway's cloud tool loop (gateway 0.9.39 POST /v1/loop + /v1/turn/resume), pure so it is
 * tested on the JVM (LoopWireTest). [CloudLoop] does the network and the agent hooks.
 *  - [EventFilter]: the loop's stream is an OpenAI stream plus `data: {"argus": {...}}` event lines; the filter hands those
 *    to a callback and passes everything else on unchanged to LlmClient.readSse.
 *  - [Recorder]: the turn's steps as chat messages (assistant tool_calls + tool results, cloud and phone), so a full
 *    resend after an expired turn carries what already happened instead of running it again.
 *  - [startBody] / [resumeBody]: the two request bodies (the resume carries the turn id + results only).
 */
object LoopWire {
    /** Line-based filter: `argus` event lines go to [onEvent], every other line passes through untouched. */
    class EventFilter(input: java.io.InputStream, private val onEvent: (JSONObject) -> Unit) : java.io.InputStream() {
        private val reader = input.bufferedReader(Charsets.UTF_8)
        private var buf: ByteArray = ByteArray(0)
        private var pos = 0
        private var eof = false

        private fun fill(): Boolean {
            while (pos >= buf.size) {
                if (eof) return false
                val line = reader.readLine() ?: run { eof = true; return false }
                val t = line.trim()
                if (t.startsWith("data:") && t.indexOf("\"argus\"") in 0..12) {
                    val o = try { JSONObject(t.removePrefix("data:").trim()).optJSONObject("argus") } catch (_: Exception) { null }
                    if (o != null) { try { onEvent(o) } catch (_: Exception) { }; continue }
                }
                buf = (line + "\n").toByteArray(Charsets.UTF_8); pos = 0
            }
            return true
        }
        override fun read(): Int = if (!fill()) -1 else buf[pos++].toInt() and 0xff
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, n); pos += n
            return n
        }
        override fun close() { reader.close() }
    }

    fun startBody(messages: JSONArray, tools: JSONArray?, toolChoice: Any?, maxTokens: Int, available: Collection<String>, cloud: Collection<String>? = null): JSONObject {
        val b = JSONObject().put("stream", true).put("messages", messages).put("max_tokens", maxTokens)
        if (tools != null && tools.length() > 0) { b.put("tools", tools); b.put("tool_choice", toolChoice ?: "auto") }
        if (available.isNotEmpty()) b.put("argus_tools_available", JSONArray(available.distinct().take(2_000)))
        if (cloud != null) b.put("argus_cloud_tools", JSONArray(cloud.toList()))
        return b
    }

    fun resumeBody(turnId: String, results: List<Pair<String, String>>, tools: JSONArray? = null): JSONObject {
        val b = JSONObject().put("turnId", turnId).put("stream", true)
            .put("tool_results", JSONArray(results.map { (id, c) -> JSONObject().put("tool_call_id", id).put("content", c) }))
        if (tools != null && tools.length() > 0) b.put("tools", tools)
        return b
    }

    /** The schemas of [names] from [all] (an OpenAI tools array), in [names] order. */
    fun schemas(all: JSONArray?, names: Collection<String>): JSONArray {
        val by = HashMap<String, Any>()
        if (all != null) for (i in 0 until all.length()) all.optJSONObject(i)?.let { t -> t.optJSONObject("function")?.optString("name")?.let { by[it] = t } }
        return JSONArray(names.mapNotNull { by[it] })
    }

    /** A pause: why, its turn id, whether the gateway kept the turn, and what it waits for. */
    data class Pause(val reason: String, val turnId: String, val resumable: Boolean, val calls: List<String>, val needTools: List<String>)

    fun pause(o: JSONObject): Pause = Pause(o.optString("reason"), o.optString("turn_id"), o.optBoolean("resumable"),
        strings(o.optJSONArray("calls")), strings(o.optJSONArray("need_tools")))

    fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    /**
     * The turn's steps as chat messages, from the loop's events (cloud calls and results) and the phone batches' outcomes.
     * Each model step that called tools is one assistant message with its tool_calls, followed by one tool message per call.
     */
    class Recorder {
        private class Unit(var text: String = "", val calls: MutableList<Triple<String, String, String>> = ArrayList(), val results: MutableMap<String, String> = LinkedHashMap())
        private val units = ArrayList<Unit>()
        /** Cloud tool runs this turn (name, ms) and phone round trips (resumes and full resends). */
        val cloud = ArrayList<Pair<String, Long>>()

        private fun cur(): Unit = units.lastOrNull() ?: Unit().also { units.add(it) }

        fun event(o: JSONObject) {
            when (o.optString("type")) {
                "model" -> if (o.optInt("calls") > 0) units.add(Unit())
                "tool" -> { val u = cur(); if (u.text.isBlank()) u.text = o.optString("text"); addCall(u, o.optString("id"), o.optString("name"), o.optString("arguments", "{}")) }
                "tool_result" -> { cur().results[o.optString("id")] = o.optString("content"); cloud.add(o.optString("name") to o.optLong("ms")) }
            }
        }

        fun batch(b: PhoneBatch.Batch) { val u = cur(); for (s in b.steps) addCall(u, s.id, s.tool, s.args) }
        fun results(r: List<Pair<String, String>>) { val u = cur(); for ((id, c) in r) u.results[id] = c }

        private fun addCall(u: Unit, id: String, name: String, args: String) { if (u.calls.none { it.first == id }) u.calls.add(Triple(id, name, args)) }

        /** Steps whose every call has a result, as messages (an unanswered step is left out: it never happened). */
        fun messages(): JSONArray {
            val out = JSONArray()
            for (u in units) {
                if (u.calls.isEmpty() || u.calls.any { it.first !in u.results }) continue
                out.put(JSONObject().put("role", "assistant").put("content", if (u.text.isBlank()) JSONObject.NULL else u.text)
                    .put("tool_calls", JSONArray(u.calls.map { (id, n, a) -> JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", n).put("arguments", a)) })))
                for ((id, n, _) in u.calls) out.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("name", n).put("content", u.results[id]))
            }
            return out
        }

        /** [base] + what already happened this turn (a full resend after an expired turn). */
        fun resend(base: JSONArray): JSONArray = JSONArray().also { a -> for (i in 0 until base.length()) a.put(base.get(i)); messages().let { m -> for (i in 0 until m.length()) a.put(m.get(i)) } }

        /** Every result this turn, cloud and phone, for a last-resort answer when the model can't be reached after phone tools ran. */
        fun phoneResultsText(): String = units.flatMap { u -> u.calls.mapNotNull { (id, n, _) -> u.results[id]?.let { "$n: ${it.lineSequence().firstOrNull().orEmpty()}" } } }.joinToString("\n")
    }
}
