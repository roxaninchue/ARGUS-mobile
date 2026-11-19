package com.blaze.assistant.policy

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.53.0 card fixes (owner's device test, 13 Nov 2026):
 *  1. A screen plan that sends through an app with a connector (Gmail via run_plan) gets a Connect card first, instead of
 *     the bot quietly driving the screen because the send tool was missing ([PolicyRuntime.run]).
 *  2. A screen-driven send shows a Draft card (recipients, subject, every word), not a plain approval ([of]).
 *  3. Approval text is readable: a plan's steps as numbered lines, other args as "key: value", never raw JSON ([readable]).
 * Pure Kotlin + org.json; ScreenSendTest.
 */
object ScreenSend {
    /** Screen tools whose args carry a step list. */
    val PLAN_TOOLS = setOf("run_plan", "do_actions", "phone_act", "phone_task")

    data class Step(val act: String, val target: String, val text: String, val app: String)
    data class Send(val app: String, val draft: Cards.Draft)

    private val SEND_TAP = Regex("""^\s*(send|send email|send message|post|reply|reply all|share|submit)\b""", RegexOption.IGNORE_CASE)
    private val TO_FIELD = Regex("""\b(to|recipient|recipients|cc|bcc|contact|name|search|number|phone|email address)\b""", RegexOption.IGNORE_CASE)
    private val SUBJECT_FIELD = Regex("""\bsubject\b""", RegexOption.IGNORE_CASE)

    private fun obj(json: String): JSONObject? = try { JSONObject(json.ifBlank { "{}" }) } catch (_: Exception) { null }

    /** The plan's steps (steps / actions / plan arrays), or empty when the call has none. */
    fun steps(argsJson: String): List<Step> {
        val a = obj(argsJson) ?: return emptyList()
        val arr: JSONArray = a.optJSONArray("steps") ?: a.optJSONArray("actions") ?: a.optJSONArray("plan") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            Step(s.optString("do").ifBlank { s.optString("action") }.trim().lowercase(),
                s.optString("target").ifBlank { s.optString("field") }.trim(),
                s.optString("text").trim(),
                s.optString("app").trim())
        }
    }

    /** A plan that types and then taps Send: the draft it would send, and the app it sends in. Null = not a send. */
    fun of(tool: String, argsJson: String): Send? {
        if (tool !in PLAN_TOOLS) return null
        val st = steps(argsJson)
        val sendAt = st.indexOfFirst { it.act in setOf("tap", "press", "click") && SEND_TAP.containsMatchIn(it.target) }
        if (sendAt < 0) return null
        val typed = st.take(sendAt).filter { it.act == "type" && it.text.isNotEmpty() }
        if (typed.isEmpty()) return null
        val app = st.take(sendAt).lastOrNull { it.app.isNotEmpty() }?.app.orEmpty()
        val to = typed.filter { TO_FIELD.containsMatchIn(it.target) }.map { it.text }.distinct()
        val subject = typed.lastOrNull { SUBJECT_FIELD.containsMatchIn(it.target) }?.text
        val body = (typed - typed.filter { TO_FIELD.containsMatchIn(it.target) || SUBJECT_FIELD.containsMatchIn(it.target) }.toSet())
            .maxByOrNull { it.text.length }?.text.orEmpty()
        return Send(app, Cards.Draft(tool, to, subject, body))
    }

    /** One step in words: "Open Gmail", "Type "Hi" in To", "Tap Send". */
    fun line(s: Step): String = when (s.act) {
        "open_app" -> "Open ${s.app.ifEmpty { s.target }}"
        "open_settings" -> "Open settings" + (s.target.ifEmpty { "" }.let { if (it.isEmpty()) "" else ": $it" })
        "search_in_app" -> "Search ${s.app.ifEmpty { "the app" }}" + if (s.text.isNotEmpty()) " for \u201c${s.text.take(80)}\u201d" else ""
        "type" -> "Type \u201c${s.text.take(120)}\u201d" + if (s.target.isNotEmpty()) " in ${s.target}" else ""
        "tap", "press", "click" -> "Tap ${s.target.ifEmpty { "the screen" }}"
        "long_press" -> "Hold ${s.target.ifEmpty { "the screen" }}"
        "" -> s.target.ifEmpty { "Step" }
        else -> s.act.replace('_', ' ').replaceFirstChar { it.uppercaseChar() } + if (s.target.isNotEmpty()) " ${s.target}" else ""
    }

    /** The call's args for a card: numbered steps for a plan, "key: value" lines otherwise, "" for none. Never raw JSON. */
    fun readable(tool: String, argsJson: String, max: Int = 600): String {
        val st = steps(argsJson)
        if (st.isNotEmpty()) return st.mapIndexed { i, s -> "${i + 1}. ${line(s)}" }.joinToString("\n").take(max)
        val a = obj(argsJson) ?: return argsJson.trim().take(max)
        if (a.length() == 0) return ""
        return a.keys().asSequence().mapNotNull { k ->
            val v = a.opt(k)
            val text = when (v) {
                is String -> v
                is Number, is Boolean -> v.toString()
                is JSONArray -> (0 until v.length()).joinToString(", ") { v.opt(it)?.let { x -> if (x is String) x else x.toString() }.orEmpty() }
                is JSONObject -> v.keys().asSequence().joinToString(", ") { kk -> "$kk ${v.opt(kk)}" }
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            k.replace('_', ' ') + ": " + text.take(200)
        }.joinToString("\n").take(max)
    }
}
