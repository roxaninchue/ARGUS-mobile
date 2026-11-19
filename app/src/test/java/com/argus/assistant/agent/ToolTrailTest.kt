package com.blaze.assistant.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 4: results kept beside the main chat's calls, bounded, and nothing read while the setting is off. */
class ToolTrailTest {
    @After fun reset() { ToolTrail.on = true; ToolTrail.clear() }

    @Test fun keepsResultsPerChatClippedAndBounded() {
        ToolTrail.clear()
        ToolTrail.result("c1", "web_search(query=x)", 10) { "found 3" }
        ToolTrail.result("c1", "$ ls", 11) { "Error: no such dir" }
        ToolTrail.result("c2", "file_read(path=a)", 12) { "x".repeat(ToolTrail.OUTPUT_MAX + 50) }
        assertEquals(listOf("found 3", "Error: no such dir"), ToolTrail.of("c1").map { it.output })
        assertFalse(ToolTrail.of("c1")[0].failed); assertTrue(ToolTrail.of("c1")[1].failed)
        assertEquals(ToolTrail.OUTPUT_MAX + 1, ToolTrail.of("c2")[0].output.length)
        repeat(ToolTrail.PER_CHAT + 5) { ToolTrail.result("c3", "x()", it.toLong()) { "r$it" } }
        assertEquals(ToolTrail.PER_CHAT, ToolTrail.of("c3").size); assertEquals("r5", ToolTrail.of("c3").first().output)
        for (i in 0..ToolTrail.CHATS) ToolTrail.result("k$i", "x()") { "y" }
        assertTrue(ToolTrail.results.value.size <= ToolTrail.CHATS)
    }

    @Test fun offReadsNothing() {
        ToolTrail.clear(); ToolTrail.on = false
        var read = false
        ToolTrail.result("c1", "x()") { read = true; "secret" }
        assertFalse(read); assertTrue(ToolTrail.of("c1").isEmpty())
    }
}
