package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.54.6: where one turn's time went, call by call, so "does the direct lane make replies start sooner?" can be read off
 * the phone. Every model call of a turn books one [Call] (which lane served it, why a direct try fell back, how long the
 * ticket took, request to first token, the whole call, how much was sent), and every tool run books one [ToolRun].
 *
 * One instance per turn, carried on the turn's [CallState] (forks share it), so background agents and side lanes running
 * at the same time never mix their calls into this turn. [snapshot] freezes it for the "What happened" sheet
 * (TurnTrace) and the speed log ("speed stats"). Pure Kotlin + org.json (TurnTimingTest).
 */
class TurnTiming {
    /**
     * One model call. [lane]: "direct" (the gateway's Direct lane relay) or "normal" (the routed gateway path).
     * [fallback]: why a direct try handed the call to the normal route ("" = no direct try, or it answered).
     * [ticketMs]: time spent getting a ticket for this call (-1 = none fetched: cached, or no direct try).
     * [directMs]: time the failed direct try cost before the normal route started (0 = none).
     * [firstMs]: from the start of the call (ticket included) to its first token (text, thinking or a tool call); -1 = none.
     * [ms]: the whole call. [inChars]: request characters sent (messages + tool schemas). [tools]: tool schemas sent.
     * [kind]: the LlmClient lane ("main", "steps", "background").
     */
    data class Call(val lane: String, val ms: Long, val firstMs: Long, val inChars: Int, val tools: Int,
                    val ticketMs: Long = -1, val directMs: Long = 0, val fallback: String = "", val kind: String = "main") {
        val estTokens: Int get() = inChars / 4
    }

    /** One tool run from start to result ([ms]). [where]: "phone" (the agent ran it) or "cloud" (the gateway's loop ran it, 0.55.1). */
    data class ToolRun(val name: String, val ms: Long, val where: String = PHONE)

    private val calls = CopyOnWriteArrayList<Call>()
    private val tools = CopyOnWriteArrayList<ToolRun>()

    fun call(c: Call) { if (calls.size < MAX) calls.add(c) }
    fun tool(name: String, ms: Long) { if (tools.size < MAX) tools.add(ToolRun(name, ms.coerceAtLeast(0))) }
    /** 0.55.1: a tool the gateway's cloud loop ran inside the turn (no phone round trip). */
    fun cloudTool(name: String, ms: Long) { if (tools.size < MAX) tools.add(ToolRun(name, ms.coerceAtLeast(0), CLOUD)) }
    /** 0.55.1: model calls the gateway made inside cloud-loop turns. */
    private val cloudModels = java.util.concurrent.atomic.AtomicInteger(0)
    fun cloudModel() { cloudModels.incrementAndGet() }
    /** 0.55.1: how each phone round trip of a cloud-loop turn went: [RESUME] (turn id + results), [RESEND] (whole chat), [LOCAL_END] (no model call). */
    private val trips = CopyOnWriteArrayList<String>()
    fun roundTrip(kind: String) { if (trips.size < MAX) trips.add(kind) }
    fun clear() { calls.clear(); tools.clear(); trips.clear(); cloudModels.set(0) }

    fun snapshot(totalMs: Long): Snap = Snap(calls.toList(), tools.toList(), totalMs.coerceAtLeast(0), cloudModels.get(), trips.toList())

    /** A frozen turn: what the sheet and the speed log show. */
    data class Snap(val calls: List<Call>, val tools: List<ToolRun>, val totalMs: Long, val cloudModels: Int = 0, val trips: List<String> = emptyList()) {
        val cloudTools: List<ToolRun> get() = tools.filter { it.where == CLOUD }
        val phoneTools: List<ToolRun> get() = tools.filter { it.where != CLOUD }

        /** 0.55.1: "cloud 2 · phone 1 (1 resume, 1 local end)": tool round trips by where they ran. */
        fun tripsLine(): String {
            if (cloudTools.isEmpty() && trips.isEmpty()) return ""
            val kinds = trips.groupingBy { it }.eachCount().entries.joinToString(", ") { (k, n) -> "$n ${TRIP_NAMES[k] ?: k}" }
            return "cloud ${cloudTools.size} · phone ${phoneTools.size}" + if (kinds.isNotEmpty()) " ($kinds)" else ""
        }

        /** Calls on the user's own turn (main and steps), not background checks. */
        val turnCalls: List<Call> get() = calls.filter { it.kind != "background" }

        /** "direct", "normal", or "direct 2 / normal 1" when a turn used both. */
        fun lane(): String {
            val l = turnCalls.ifEmpty { calls }
            if (l.isEmpty()) return ""
            val d = l.count { it.lane == DIRECT }
            return when (d) { l.size -> DIRECT; 0 -> NORMAL; else -> "$DIRECT $d / $NORMAL ${l.size - d}" }
        }

        fun fallbacks(): List<String> = calls.map { it.fallback }.filter { it.isNotBlank() }

        /** Request to first token of the turn's first call (what the user waited for), -1 = unknown. */
        fun firstMs(): Long = (turnCalls.ifEmpty { calls }).firstOrNull()?.firstMs ?: -1

        fun ticketMs(): Long = calls.filter { it.ticketMs >= 0 }.sumOf { it.ticketMs }
        fun ticketCount(): Int = calls.count { it.ticketMs >= 0 }

        fun toJson(): JSONObject = JSONObject().put("total", totalMs)
            .put("calls", JSONArray(calls.map { c -> JSONObject().put("l", c.lane).put("ms", c.ms).put("f", c.firstMs).put("in", c.inChars)
                .put("t", c.tools).put("tk", c.ticketMs).put("d", c.directMs).put("fb", c.fallback).put("k", c.kind) }))
            .put("tools", JSONArray(tools.map { JSONObject().put("n", it.name).put("ms", it.ms).put("w", it.where) }))
            .put("cm", cloudModels).put("rt", JSONArray(trips))

        /** The "What happened" rows (label, value); empty values left out. */
        fun rows(): List<Pair<String, String>> {
            if (calls.isEmpty() && tools.isEmpty()) return emptyList()
            val tc = turnCalls
            val bg = calls.size - tc.size
            return listOf(
                "Lane" to lane(),
                "Direct fell back" to fallbacks().distinct().joinToString("; "),
                "Ticket" to if (ticketCount() > 0) "${fmt(ticketMs())} (${ticketCount()} fetched)" else if (calls.any { it.lane == DIRECT }) "cached" else "",
                "Request \u2192 first token" to tc.ifEmpty { calls }.joinToString(", ") { if (it.firstMs >= 0) fmt(it.firstMs) else "\u2013" },
                "Model calls" to if (calls.isEmpty()) "" else "${tc.size}" + (if (bg > 0) " (+$bg background)" else "") + ": " +
                    calls.joinToString(", ") { (if (it.lane == DIRECT) "d " else "") + fmt(it.ms) + if (it.directMs > 0) " (+${fmt(it.directMs)} direct try)" else "" },
                "Tool round trips" to if (phoneTools.isEmpty()) "" else "${phoneTools.size}: " + phoneTools.joinToString(", ") { "${it.name} ${fmt(it.ms)}" },
                "Cloud tools" to if (cloudTools.isEmpty()) "" else "${cloudTools.size}: " + cloudTools.joinToString(", ") { "${it.name} ${fmt(it.ms)}" } +
                    if (cloudModels > 0) " ($cloudModels model calls on the gateway)" else "",
                "Cloud vs phone" to tripsLine(),
                "Sent" to (tc.ifEmpty { calls }).firstOrNull()?.let { f ->
                    "${f.inChars} chars (~${f.estTokens} tokens), ${f.tools} tools" + if (calls.size > 1) "; all calls ${calls.sumOf { it.inChars }} chars (~${calls.sumOf { it.estTokens }} tokens)" else "" }.orEmpty(),
                "Whole turn" to fmt(totalMs),
            ).filter { it.second.isNotBlank() }
        }

        /** One line for the speed log and logcat: "lane direct · ticket 140 ms · first 610 ms · calls 2 (d 1.2 s, d 0.9 s) · tools 1 (set_alarm 40 ms) · sent 9k chars · turn 2.4 s". */
        fun line(): String {
            if (calls.isEmpty() && tools.isEmpty()) return ""
            return listOf(
                "lane " + lane(),
                fallbacks().distinct().takeIf { it.isNotEmpty() }?.let { "fell back: " + it.joinToString("; ") }.orEmpty(),
                if (ticketCount() > 0) "ticket ${fmt(ticketMs())}" else "",
                firstMs().takeIf { it >= 0 }?.let { "first ${fmt(it)}" }.orEmpty(),
                if (calls.isEmpty()) "" else "calls ${calls.size} (" + calls.joinToString(", ") { (if (it.lane == DIRECT) "d " else "") + fmt(it.ms) } + ")",
                if (phoneTools.isEmpty()) "" else "tools ${phoneTools.size} (" + phoneTools.joinToString(", ") { "${it.name} ${fmt(it.ms)}" } + ")",
                if (cloudTools.isEmpty()) "" else "cloud tools ${cloudTools.size} (" + cloudTools.joinToString(", ") { "${it.name} ${fmt(it.ms)}" } + ")",
                tripsLine().takeIf { it.isNotEmpty() }?.let { "round trips $it" }.orEmpty(),
                (tc0()?.inChars)?.let { "sent ${it / 1000}k chars ~${it / 4} tok" }.orEmpty(),
                "turn ${fmt(totalMs)}",
            ).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
        }

        private fun tc0(): Call? = turnCalls.ifEmpty { calls }.firstOrNull()

        companion object {
            fun fromJson(o: JSONObject?): Snap? = try {
                if (o == null) null else {
                    val ca = o.optJSONArray("calls") ?: JSONArray()
                    val ta = o.optJSONArray("tools") ?: JSONArray()
                    Snap((0 until ca.length()).mapNotNull { i -> ca.optJSONObject(i)?.let { c ->
                        Call(c.optString("l"), c.optLong("ms"), c.optLong("f", -1), c.optInt("in"), c.optInt("t"), c.optLong("tk", -1), c.optLong("d"), c.optString("fb"), c.optString("k", "main")) } },
                        (0 until ta.length()).mapNotNull { i -> ta.optJSONObject(i)?.let { ToolRun(it.optString("n"), it.optLong("ms"), it.optString("w", PHONE)) } },
                        o.optLong("total"), o.optInt("cm"), o.optJSONArray("rt")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList())
                }
            } catch (_: Exception) { null }
        }
    }

    companion object {
        const val DIRECT = "direct"
        const val NORMAL = "normal"
        const val PHONE = "phone"
        const val CLOUD = "cloud"
        const val RESUME = "resume"
        const val RESEND = "resend"
        const val LOCAL_END = "local_end"
        val TRIP_NAMES = mapOf(RESUME to "resume", RESEND to "full resend", LOCAL_END to "local end")
        /** Most calls / tool runs one turn keeps (a long autopilot run stays bounded). */
        const val MAX = 64
        fun fmt(ms: Long): String = if (ms < 0) "\u2013" else if (ms < 1000) "$ms ms" else "%.2f s".format(java.util.Locale.US, ms / 1000.0)
    }
}
