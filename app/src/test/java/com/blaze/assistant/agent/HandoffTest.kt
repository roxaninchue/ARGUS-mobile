package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.52.1: long work moves to a background agent instead of "That took too long". */
class HandoffTest {
    private val worked = Handoff.Turn(lead = true, private = false, toolsRun = 2, handedOff = false)

    @Test fun movesOnlyLeadTurnsThatDidWork() {
        assertTrue(Handoff.movable(worked))
        assertFalse(Handoff.movable(worked.copy(toolsRun = 0)))       // a plain question just answers or errors
        assertFalse(Handoff.movable(worked.copy(lead = false)))       // an agent never spawns another agent
        assertFalse(Handoff.movable(worked.copy(private = true)))     // private chats are never written to disk
        assertFalse(Handoff.movable(worked.copy(handedOff = true)))   // once per turn
    }

    @Test fun nearEndUsesTheMargin() {
        val end = 1_000_000L
        assertFalse(Handoff.nearEnd(worked, end - Handoff.MARGIN_MS - 1, end))
        assertTrue(Handoff.nearEnd(worked, end - Handoff.MARGIN_MS, end))
        assertFalse(Handoff.nearEnd(worked, 0, Long.MAX_VALUE))      // no hard end, never
    }

    @Test fun slowFailuresAreTimeNotAuth() {
        assertTrue(Handoff.slowFailure("That took too long. Tap Retry."))   // BrainRace.TOO_LONG
        assertTrue(Handoff.slowFailure("The model sent nothing for 60s, twice."))
        assertTrue(Handoff.slowFailure(TurnDeadline.line(300_000)))
        assertTrue(Handoff.slowFailure("error code: 1102", 503))
        assertTrue(Handoff.slowFailure("whatever", 504))
        assertFalse(Handoff.slowFailure("Sign in to Blaze Cloud", 401))
        assertFalse(Handoff.slowFailure("Credits used up.", 402))
        assertFalse(Handoff.slowFailure("bad request timeout field", 400))
        assertTrue(Handoff.slowFailure("rate limited, busy", 429))
    }

    @Test fun briefIsSelfContainedAndBounded() {
        val b = Handoff.brief("Find the 5 best budget phones and compare them", "compare 5 phones",
            List(40) { "Searching the web $it" }, List(6) { "result $it " + "x".repeat(5000) })
        assertTrue(b.contains("Find the 5 best budget phones"))
        assertTrue(b.contains("Goal: compare 5 phones"))
        assertTrue(b.contains("Don't redo"))
        assertTrue(b.contains("result 5")); assertFalse(b.contains("result 2"))   // newest 3 only
        assertTrue(b.length < 7_000)
    }

    @Test fun titleIsShort() {
        assertEquals("Find the 5 best budget", Handoff.title("Find   the 5 best budget phones and compare them"))
    }
}
