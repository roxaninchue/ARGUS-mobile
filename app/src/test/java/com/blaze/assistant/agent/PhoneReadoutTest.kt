package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.55.1 (Finn: no trigger words): the speed readout is no longer matched from the user's text. It is the speed_stats
 * catalog tool the model may call, the Settings > Developer > Speed stats page and the What happened sheet (PhoneReadout).
 */
class PhoneReadoutTest {
    private fun src(name: String) = listOf("src/main/java/com/blaze/assistant/agent/$name", "app/src/main/java/com/blaze/assistant/agent/$name").map(::File).first { it.exists() }.readText()
    private fun ui(name: String) = listOf("src/main/java/com/blaze/assistant/ui/$name", "app/src/main/java/com/blaze/assistant/ui/$name").map(::File).first { it.exists() }.readText()

    @Test fun speedStatsIsAToolTheModelCalls() {
        val d = PhoneReadout.def().getJSONObject("function")
        assertEquals("speed_stats", d.getString("name"))
        assertTrue(d.getString("description").startsWith("How fast the recent replies were"))
        PhoneReadout.source = { "Median first word 600 ms" }
        assertEquals("Median first word 600 ms", PhoneReadout.speedStats())
        PhoneReadout.source = { "" }
        assertEquals("No timed replies yet.", PhoneReadout.speedStats())
        PhoneReadout.source = { throw IllegalStateException("boom") }
        assertTrue(PhoneReadout.speedStats().startsWith("Error"))
    }

    @Test fun itIsACatalogToolNotACoreOne() {
        assertFalse(PhoneReadout.TOOL in LeanTurn.CORE)
        val a = src("Agent.kt")
        assertTrue(a.contains("t.put(PhoneReadout.def())"))
        assertTrue(a.contains("PhoneReadout.TOOL -> {"))
    }

    @Test fun noTextMatchedReadoutPathRemains() {
        assertFalse(src("PhoneReadout.kt").contains("fun match("))
        assertFalse(src("FastLane.kt").contains("suspend fun readout("))
        val q = src("QuickRules.kt")
        for (gone in listOf("\"speed_stats\" to", "\"tool_stats\" to", "\"list_processes\" to")) assertFalse(gone, q.contains(gone))
        val a = src("Agent.kt")
        assertFalse(a.contains("PhoneReadout.match("))
        assertFalse(a.contains("FastLane.readout("))
    }

    @Test fun settingsHasADeveloperSpeedStatsPage() {
        assertTrue(ui("SettingsPages.kt").contains("SettingsPage(\"speed\", \"Speed stats\""))
        assertTrue(ui("SettingsSheet.kt").contains("\"speed\" -> SpeedStatsPage()"))
    }
}
