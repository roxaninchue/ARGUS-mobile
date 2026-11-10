package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unreleased after 0.52.3: the owed-reply ledger (every user message of a turn gets an answer, one follow-up at most). */
class ReplyLedgerTest {
    private val turn = listOf(7L, 8L, 9L)

    @Test fun unmarkedReplyCoversEverything() {
        assertEquals(emptyList<Long>(), ReplyLedger.uncovered(turn, "Here is one answer to all of it."))
        // one message: nothing to check, marked or not
        assertEquals(emptyList<Long>(), ReplyLedger.uncovered(listOf(7L), "[re:m7] Sure."))
        assertEquals(emptyList<Long>(), ReplyLedger.uncovered(emptyList(), "Hi"))
        // markers naming only messages outside the turn count as unmarked
        assertEquals(emptyList<Long>(), ReplyLedger.uncovered(turn, "[re:m3] Old one."))
    }

    @Test fun markedReplyCoversOnlyWhatItNames() {
        assertEquals(listOf(9L), ReplyLedger.uncovered(turn, "[re:m7,m8] Both done."))
        assertEquals(emptyList<Long>(), ReplyLedger.uncovered(turn, "[re:m7] A\n\n[re:m8,m9] B"))
        assertEquals(listOf(8L), ReplyLedger.uncovered(turn, "[re:m7] A\n[re:m9] C"))
        // a reply to the later correction covers the earlier message only when it names it
        assertEquals(listOf(7L), ReplyLedger.uncovered(listOf(7L, 8L), "[re:m8] Booked for 8 instead."))
        assertEquals(emptyList<Long>(), ReplyLedger.uncovered(listOf(7L, 8L), "[re:m7,m8] Booked for 8 instead."))
        // order follows the turn, duplicates ignored
        assertEquals(listOf(8L, 9L), ReplyLedger.uncovered(listOf(7L, 8L, 8L, 9L), "[re:m7] Only this."))
    }

    @Test fun asksOncePerTurn() {
        assertTrue(ReplyLedger.shouldAsk(listOf(9L), 0))
        assertFalse(ReplyLedger.shouldAsk(listOf(9L), 1))
        assertFalse(ReplyLedger.shouldAsk(emptyList(), 0))
        val n = ReplyLedger.nudge(listOf(8L, 9L))
        assertTrue(n.contains("[m8], [m9]"))
        assertTrue(n.contains("[re:m8]"))
    }

    @Test fun acceptsAndMergesTheFollowUp() {
        assertNull(ReplyLedger.accept("   ", listOf(9L)))
        assertNull(ReplyLedger.accept("[re:m9]", listOf(9L)))
        assertEquals("[re:m9] Alarm set.", ReplyLedger.accept("[re:m9] Alarm set.", listOf(9L)))
        // no marker of its own: filed under the owed ids
        assertEquals("[re:m8,m9] Both noted.", ReplyLedger.accept("Both noted.", listOf(8L, 9L)))
        val merged = ReplyLedger.merge("[re:m7] Weather is fine.", "[re:m9] Alarm set.")
        val parts = RapidMessages.split(merged, turn.toSet())
        assertEquals(2, parts.size)
        assertEquals(listOf(9L), parts[1].refs)
        assertEquals(emptyList<Long>(), ReplyLedger.uncovered(listOf(7L, 9L), merged))
    }
}
