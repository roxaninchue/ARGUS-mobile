package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** 0.55.0: the phone's own readouts answer on the phone online too; commands and questions don't (PhoneReadout). */
class PhoneReadoutTest {
    @Test fun speedStatsIsAReadout() {
        for (t in listOf("speed stats", "Speed stats", "show my speed stats", "how fast are you", "speed stats please"))
            assertEquals(t, "speed_stats", PhoneReadout.match(t)?.first)
    }
    @Test fun toolStatsAndProcessesAreReadouts() {
        assertEquals("tool_stats", PhoneReadout.match("tool stats")?.first)
        assertEquals("list_processes", PhoneReadout.match("what's running")?.first)
    }
    @Test fun actionsAndQuestionsStayWithTheModelOnline() {
        for (t in listOf("set an alarm for 7", "turn on bluetooth", "Hi!", "open whatsapp", "what's the speed of light", "speed stats\nand more"))
            assertNull(t, PhoneReadout.match(t))
    }
    @Test fun everyReadoutIsReadOnly() {
        assertEquals(setOf("speed_stats", "tool_stats", "list_processes"), PhoneReadout.NAMES)
    }

    private fun src(name: String) = listOf("src/main/java/com/blaze/assistant/agent/$name", "app/src/main/java/com/blaze/assistant/agent/$name").map(::File).first { it.exists() }.readText()

    @Test fun onlineTheAgentSendsOnlyReadoutsToThePhone() {
        val a = src("Agent.kt")
        assertTrue(a.contains("val readout = !offline && PhoneReadout.match(shown) != null"))
        assertTrue(a.contains("if (readout) FastLane.readout(app, shown, key = turnUser)"))
        assertTrue("offline keeps every direct command", a.contains("else FastLane.tryHandle(app, shown, offline, key = turnUser)"))
    }

    @Test fun theReadoutPathSkipsTheIntentCacheAndTheOnDeviceModel() {
        val f = src("FastLane.kt")
        val body = f.substring(f.indexOf("suspend fun readout("), f.indexOf("/** The messages (by id) the shortcut already handled"))
        assertTrue(body.contains("PhoneReadout.match(text) ?: return null"))
        for (no in listOf("IntentCache", "useModel", "/complete")) assertFalse(no, body.contains(no))
    }
}
