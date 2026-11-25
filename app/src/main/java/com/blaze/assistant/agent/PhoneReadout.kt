package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's own speed readout. 0.55.1 (Finn: no trigger words): nothing matches the user's text any more. The 0.55.0
 * phrase path ("speed stats", "tool stats", "what's running" answered online by FastLane.readout) and the offline
 * QuickRules phrases for them are gone. The same readout now shows
 *  - in Settings > Developer > Speed stats (SettingsSheet "speed" page),
 *  - in each reply's What happened sheet (TurnTiming rows, cloud vs phone tool round trips included),
 *  - and as the `speed_stats` catalog tool ([def], NOT core: find_tools finds it) that the model may choose to call.
 * TurnTiming / SpeedLog data is unchanged. PhoneReadoutTest.
 */
object PhoneReadout {
    const val TOOL = "speed_stats"

    /** The readout text (the same lines FastLane's speed_stats gave): tests replace it. */
    @Volatile var source: () -> String = {
        SpeedLog.summary() + SpeedLog.firstWordLine() + SpeedLog.lastPhases().let { if (it.isBlank()) "" else "\nLast turn: $it" } +
            SpeedLog.timingLines()
    }

    fun speedStats(): String = try { source().ifBlank { "No timed replies yet." } } catch (e: Throwable) { "Error: couldn't read the speed log (${e.message})" }

    fun def(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", TOOL)
        .put("description", "How fast the recent replies were: first token, model time, tools sent, and cloud vs phone tool round trips. Read-only; use it when the user asks how fast you are or where the time went.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray())))
}
