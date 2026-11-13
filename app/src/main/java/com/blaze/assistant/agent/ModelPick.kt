package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 0.53.0 manual model + effort picker (owner, 13 Nov 2026: "manual selection of model and effort; only models the gateway
 * deems healthy can be selected"). The picker only offers what GET /v1/models/live lists for this account: probe-verified
 * in the last hours and allowed on its plan (gateway 0.9.11 hides Claude Opus 5.5 / Fable 5.2 on free plans), so a model
 * id is never typed here. Auto (the gateway's lane) stays the first option and the default.
 *
 * A pick is sent on every call of a turn (X-Blaze-Prefer on chat calls, `blaze_pick` on Cloud runs) while it is on the
 * live list and fits the call (a tool call needs a tools-verified model, an image a vision one). Off the list (it failed
 * its probes) or not fitting, the call goes Auto and [status] says why, so the chip can show it. The effort is sent as
 * `reasoning_effort` only when the picked model lists it; the gateway drops it again for a fallback model without efforts.
 * Pure Kotlin + Settings (str / setStr); ModelPickTest.
 */
object ModelPick {
    const val PICK = "model_pick"
    const val EFFORT = "model_effort"

    data class Choice(val key: String = "", val effort: String = "") { val auto: Boolean get() = key.isBlank() }

    enum class Status { AUTO, ON, OFF_LIST, NO_TOOLS, NO_VISION }

    private val _current = MutableStateFlow(load())
    val current: StateFlow<Choice> = _current

    private fun load(): Choice = try { Choice(Settings.str(PICK, "").trim(), Settings.str(EFFORT, "").trim().lowercase()) } catch (_: Throwable) { Choice() }

    /** Pick [key] ("" = Auto) with [effort] ("" = the model's default). */
    fun set(key: String, effort: String = "") {
        val c = Choice(key.trim(), effort.trim().lowercase().takeIf { it in LiveModels.EFFORTS }.orEmpty())
        _current.value = c
        try { Settings.setStr(PICK, c.key); Settings.setStr(EFFORT, c.effort) } catch (_: Throwable) { }
    }

    fun auto() = set("")

    /** Where the pick stands for one call: the member it routes to, or why the call goes Auto. */
    fun status(list: List<LiveModels.Member>, tools: Boolean = false, vision: Boolean = false, c: Choice = current.value): Pair<Status, LiveModels.Member?> {
        if (c.auto) return Status.AUTO to null
        val m = list.firstOrNull { it.key == c.key } ?: return Status.OFF_LIST to null
        if (tools && !m.tools) return Status.NO_TOOLS to m
        if (vision && !m.vision) return Status.NO_VISION to m
        return Status.ON to m
    }

    /** The route to prefer for one call, or null (Auto). */
    fun route(list: List<LiveModels.Member>, tools: Boolean = false, vision: Boolean = false, c: Choice = current.value): String? =
        status(list, tools, vision, c).let { (s, m) -> if (s == Status.ON) m?.key else null }

    /** The effort to send for the picked model: the chosen one if the model lists it, else its default; null = send none. */
    fun effort(list: List<LiveModels.Member>, c: Choice = current.value): String? {
        if (c.auto) return null
        val m = list.firstOrNull { it.key == c.key } ?: return null
        if (m.efforts.isEmpty()) return null
        return c.effort.takeIf { it in m.efforts } ?: m.defaultEffort?.takeIf { it in m.efforts }
    }

    /** "Auto", "Kimi K3 · Medium", "Kimi K3 · unavailable" for the chip. */
    fun label(list: List<LiveModels.Member>, c: Choice = current.value): String {
        if (c.auto) return "Auto"
        val m = list.firstOrNull { it.key == c.key }
            ?: return LiveModels.prettyName(c.key.substringAfter('/')) + " · unavailable"
        return m.name + (effort(list, c)?.let { " · " + it.replaceFirstChar { ch -> ch.uppercaseChar() } } ?: "")
    }

    /** One picker row's detail: "Apinex · 1.3 s · tools · vision". */
    fun detail(m: LiveModels.Member): String = buildList {
        add(m.prov)
        if (m.ms > 0) add(if (m.ms < 1000) "${m.ms} ms" else String.format(java.util.Locale.US, "%.1f s", m.ms / 1000.0))
        if (m.tools) add("tools")
        if (m.vision) add("vision")
    }.joinToString(" · ")
}
