package com.blaze.assistant.agent

import com.blaze.assistant.orchestra.OrchestraRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNoteTest {
    private fun bp(done: Int, total: Int, last: String = "Searching: the 2021 OpenAI split") = BgAgents.Bg("B1", "How Anthropic was created",
        OrchestraRuns.encode("How Anthropic was created", "research", ""), "main", "bg-1", status = BgAgents.RUNNING,
        specialist = OrchestraRuns.SPECIALIST, steps = 9, last = last, done = done, total = total, started = 1)

    @Test fun blueprintSaysPartOfPlanAndWhatItIsDoing() {
        val t = AgentNote.build(listOf(bp(6, 14)))
        assertEquals("Deep research: How Anthropic was created", t.title)
        assertEquals("Part 6 of 14 \u00b7 Searching: the 2021 OpenAI split", t.body)
        assertEquals(14, t.total)
    }

    @Test fun plainAgentHasNoMadeUpTotal() {
        val a = BgAgents.Bg("B2", "Flight prices", "x", "main", "bg-2", status = BgAgents.RUNNING, steps = 23, last = "Reading kayak.com")
        assertEquals("Background agent: Flight prices", AgentNote.build(listOf(a)).title)
        assertEquals("Step 23 \u00b7 Reading kayak.com", AgentNote.build(listOf(a)).body)
    }

    @Test fun waitingSaysWhatYouNeedToDo() {
        val t = AgentNote.build(listOf(bp(3, 14).copy(waiting = true)))
        assertTrue(t.body.startsWith("Part 3 of 14 \u00b7 Waiting for you"))
    }

    @Test fun severalRunsAreCountedAndEachListed() {
        val q = BgAgents.Bg("B3", "Gift ideas", "x", "main", "bg-3", status = BgAgents.QUEUED)
        val t = AgentNote.build(listOf(bp(6, 14), q))
        assertEquals("2 runs: 1 working, 1 queued", t.title)
        assertEquals(2, t.lines.size)
        assertTrue(t.lines[1].contains("Queued: starts when a slot frees (1 running)"))
    }
}
