package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "What Argus is holding" without pinned facts (owner 2026-11-15: pinned facts removed as a useless feature).
 * Replaces PinnedFactsTest's holding case, and pins that nothing of the pinned-facts feature is left in the app.
 */
class HoldingTest {
    @Test fun holdingSections() {
        val empty = Holding.sections(Holding.Snapshot.EMPTY)
        assertEquals(listOf("This chat's summary", "Current task", "Skills loaded for the last request"), empty.map { it.title })
        assertTrue(empty.all { it.lines.isEmpty() && it.empty.isNotBlank() })
        val s = Holding.sections(Holding.Snapshot("We planned the Lusaka trip.", GoalPin.Goal("book a table for 4", listOf("under K500")), listOf("restaurants", "restaurants", " ")))
        assertEquals(listOf("We planned the Lusaka trip."), s[0].lines)
        assertEquals(listOf("book a table for 4", "Constraint: under K500"), s[1].lines)
        assertEquals(listOf("restaurants"), s[2].lines)
        assertFalse(Holding.INTRO.contains("Pinned", ignoreCase = true))
    }

    @Test fun theMemoryListRequestIsStillItsOwnThing() {
        assertTrue(MemoryIntent.isListRequest("show me your memories"))
        assertFalse(MemoryIntent.isListRequest("pin this"))
    }

    private fun src(rel: String): String {
        val roots = listOf(File("src/main/java/com/blaze/assistant"), File("app/src/main/java/com/blaze/assistant"))
        val root = roots.first { it.isDirectory }
        return File(root, rel).readText()
    }

    @Test fun pinnedFactsAreGoneFromTheApp() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.first { it.isDirectory }
        assertFalse(File(root, "com/blaze/assistant/agent/PinnedStore.kt").exists())
        assertFalse(File(root, "com/blaze/assistant/agent/PinnedFacts.kt").exists())
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("PinnedStore") || it.contains("PinnedFacts") || it.contains("pinAsk(") } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
        // no typed "pin this" lane in the agent, no /pin command
        assertFalse(src("agent/Agent.kt").contains("\"pin this\""))
        assertFalse(Regex("""Command\("pin",""").containsMatchIn(src("agent/Commands.kt")))
        assertFalse(src("ui/CommandRunner.kt").contains("\"pin\" ->"))
        assertFalse(src("ui/HoldingPage.kt").contains("Unpin"))
    }
}
