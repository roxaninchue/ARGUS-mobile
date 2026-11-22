package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * 0.55.0: readouts the phone answers itself, ONLINE too: "speed stats", "tool stats", "what's running". They read only
 * what is on this phone (the speed log, the tool scoreboard, the process registry), so a model can't answer them better and
 * a round trip only makes them slower.
 *
 * Bug fixed (Finn, 2026-11-22): since the 2026-11-16 model-first change the fast lane ran only when OFFLINE, so online
 * "speed stats" went to the model, which has no speed log. Agent now sends a message [match]es here through
 * FastLane.readout (no IntentCache, no on-device model, nothing that acts on the phone) whether online or not; every other
 * direct command still runs on the phone only offline. Pure (QuickRules); PhoneReadoutTest.
 */
object PhoneReadout {
    /** The fast-lane tool names that only read and report. */
    val NAMES = setOf("speed_stats", "tool_stats", "list_processes")

    /** The readout [text] asks for (fast-lane tool name + arguments), or null. */
    fun match(text: String): Pair<String, JSONObject>? {
        if (text.length > 80 || text.contains('\n')) return null
        val p = try { QuickRules.parse(text) } catch (_: Throwable) { null } ?: return null
        return p.takeIf { it.first in NAMES }
    }
}
