package com.argus.assistant.composer


import com.argus.assistant.BrandName
import com.argus.assistant.agent.ChatMessage
import com.argus.assistant.agent.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.49.0 (RC5 a): the chat over Home shows tool steps, the live work line and follow-ups. */
class ChatOverlayRowsTest {
    private fun m(id: Long, role: Role, text: String, f: List<String> = emptyList()) = ChatMessage(id, role, text, followups = f)

    @Test fun stepsBetweenUserAndReplyBecomeOneChip() {
        val all = listOf(m(1, Role.USER, "open accessibility"), m(2, Role.STEP, "Opening accessibility settings"), m(3, Role.STEP, "Checking the screen"),
            m(4, Role.BLAZE, "Switch on ${BrandName.NAME} there."))
        val rows = ChatOverlayLogic.rows(all, 0, busy = false)
        assertEquals(3, rows.size)
        val s = rows[1] as ChatOverlayLogic.Row.Steps
        assertEquals(listOf("Opening accessibility settings", "Checking the screen"), s.lines)
        assertEquals(false, s.live)
        assertEquals("2 steps \u00b7 Checking the screen", ChatOverlayLogic.stepsLabel(s))
    }

    @Test fun runningStepsAreLiveAndUnsafeTextIsHidden() {
        val all = listOf(m(1, Role.USER, "search"), m(2, Role.STEP, "Fetching https://example.com/x"))
        val s = ChatOverlayLogic.rows(all, 0, busy = true).last() as ChatOverlayLogic.Row.Steps
        assertTrue(s.live)
        assertEquals("Working on it", ChatOverlayLogic.stepsLabel(s))
    }

    @Test fun workLineOrder() {
        assertEquals("Using the browser", ChatOverlayLogic.workLine("Using the browser", "Task", "Thinking", "step"))
        assertEquals("Booking", ChatOverlayLogic.workLine(null, "Booking", "Thinking", "step"))
        assertEquals("Searching the web", ChatOverlayLogic.workLine(" ", "", "Searching the web", null))
        assertEquals("Opening settings", ChatOverlayLogic.workLine(null, "", "", "Opening settings"))
        assertNull(ChatOverlayLogic.workLine(null, "", "", null))
    }

    @Test fun followupsOnlyUnderTheNewestIdleReply() {
        val all = listOf(m(1, Role.USER, "hi"), m(2, Role.BLAZE, "Hello", listOf("Weather?")))
        assertEquals(2L, ChatOverlayLogic.followupsFor(ChatOverlayLogic.rows(all, 0, false), busy = false))
        assertNull(ChatOverlayLogic.followupsFor(ChatOverlayLogic.rows(all, 0, true), busy = true))
        assertNull(ChatOverlayLogic.followupsFor(ChatOverlayLogic.rows(all + m(3, Role.USER, "x"), 0, false), busy = false))
    }

    @Test fun sinceIdStillApplies() {
        val all = listOf(m(1, Role.USER, "a"), m(2, Role.STEP, "s"), m(3, Role.BLAZE, "b"), m(4, Role.USER, "c"))
        assertEquals(listOf("m4"), ChatOverlayLogic.rows(all, 3, false).map { it.key })
    }
}
