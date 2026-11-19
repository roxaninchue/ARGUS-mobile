package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.45.6 P1: watchdog for silent endings. Every turn ends with something the user can read. */
class TurnEndTest {
    @Test fun silentTurnsAreDetected() {
        assertTrue(TurnEnd.silent(listOf(Role.USER, Role.BLAZE, Role.USER, Role.STEP, Role.STEP)))
        assertTrue(TurnEnd.silent(listOf(Role.USER)))
        assertFalse(TurnEnd.silent(listOf(Role.USER, Role.STEP, Role.BLAZE)))
        assertFalse(TurnEnd.silent(listOf(Role.USER, Role.STEP, Role.ERROR)))
        assertFalse("a wake with no user message isn't ours to close", TurnEnd.silent(listOf(Role.STEP, Role.STEP)))
        assertFalse(TurnEnd.silent(emptyList()))
    }

    @Test fun everyReasonHasAPlainLine() {
        for (r in TurnEnd.Reason.values()) {
            val l = TurnEnd.line(r, "Opening BlazeFX\u2026")
            assertTrue(l, l.isNotBlank() && l.contains("(last step: Opening BlazeFX)"))
        }
        assertEquals("Stopped before finishing. Tell me if you want me to carry on.", TurnEnd.line(TurnEnd.Reason.STOPPED))
        // v0.45.14: there is no paused ending any more (the pointer never pauses a run).
        assertTrue(TurnEnd.Reason.values().none { it.name == "PAUSED" })
    }

    @Test fun watchdogScenarioPausedScreenControl() {
        // 21:29 on the phone: user "play music", steps only, overlay paused, the turn ended with nothing in the chat.
        val roles = mutableListOf(Role.USER, Role.BLAZE, Role.USER, Role.STEP, Role.STEP)
        assertTrue(TurnEnd.silent(roles))
        roles += Role.BLAZE   // what the finally block adds
        assertFalse(TurnEnd.silent(roles))
    }

    /** Owner 2026-11-14: no "I finished without a reply" when a reply, a queued message or a new turn follows. */
    @Test fun doneLineWaitsAndDropsWhenAReplyFollows() {
        val silent = listOf(Role.USER, Role.BLAZE, Role.USER)
        assertTrue(TurnEnd.doneLineDue(silent, turnRunning = false, queued = false))
        assertFalse(TurnEnd.doneLineDue(silent, turnRunning = true, queued = false))
        assertFalse(TurnEnd.doneLineDue(silent, turnRunning = false, queued = true))
        assertFalse(TurnEnd.doneLineDue(silent + Role.BLAZE, turnRunning = false, queued = false))
        assertTrue(TurnEnd.DONE_GRACE_MS in 3_000L..10_000L)
    }
}
