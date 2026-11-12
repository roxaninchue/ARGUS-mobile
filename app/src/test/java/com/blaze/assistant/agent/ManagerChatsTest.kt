package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-SH-04: ManagerChats ports A-team talks.js (v2.19-chat-drawer) newChat / del / restore / purge / repair / migrate. */
class ManagerChatsTest {
    @Test fun migrateMakesTheWholeHistoryTheFirstChat() {
        val m = ManagerChats.read(null)
        assertEquals(listOf(0), m.all().map { it.g })
        assertEquals(0, m.cur); assertEquals(0, m.live); assertFalse(m.readOnly)
        assertEquals(0, m.chatOf(0L)); assertEquals(0, m.chatOf(1_700_000_000_000L))
    }

    @Test fun newChatStaysPutWhileTheOpenChatIsEmpty() {
        val m = ManagerChats.fresh()
        assertEquals(ManagerChats.New.Stayed, m.newChat(1_000, liveBlank = true))
        assertEquals(1, m.all().size)
        val n = m.newChat(2_000, liveBlank = false) as ManagerChats.New.Started
        assertEquals(1, n.g); assertEquals(2_000, n.start)
        assertEquals(1, m.cur); assertEquals(1, m.live)
        assertEquals(0, m.chatOf(1_999)); assertEquals(1, m.chatOf(2_000))
        assertEquals(0L until 2_000L, m.window(0)); assertEquals(Long.MAX_VALUE, m.window(1)!!.last + 1)
    }

    @Test fun anOlderChatIsReadOnlyAndNewChatGoesBackToTheNewest() {
        val m = ManagerChats.fresh(); m.newChat(2_000, false)
        assertTrue(m.open(0)); assertTrue(m.readOnly)
        assertEquals(ManagerChats.New.Stayed, m.newChat(3_000, liveBlank = true))
        assertEquals(1, m.cur); assertFalse(m.readOnly)
    }

    @Test fun repliesDotOnlyChatsYouAreNotLookingAt() {
        val m = ManagerChats.fresh(); m.newChat(2_000, false)
        m.replied(1, viewing = true); assertFalse(m.get(1)!!.unseen)
        m.replied(1, viewing = false); assertTrue(m.get(1)!!.unseen)
        m.open(0); assertTrue(m.anyUnseen())
        m.open(1); assertFalse(m.get(1)!!.unseen); assertFalse(m.anyUnseen())
    }

    @Test fun renameTrimsCapsAndBlankGoesBackToAuto() {
        val m = ManagerChats.fresh()
        m.rename(0, "  Trip   to  Lusaka ")
        assertEquals("Trip to Lusaka", m.get(0)!!.title); assertTrue(m.get(0)!!.named)
        m.rename(0, "x".repeat(200)); assertEquals(80, m.get(0)!!.title!!.length)
        m.rename(0, "   "); assertNull(m.get(0)!!.title); assertFalse(m.get(0)!!.named)
    }

    @Test fun pinToggles() {
        val m = ManagerChats.fresh()
        assertTrue(m.pin(0, 5)); assertEquals(5L, m.get(0)!!.pin)
        assertFalse(m.pin(0, 6)); assertNull(m.get(0)!!.pin)
    }

    @Test fun deletingTheNewestOpensAFreshOneAndUndoPutsItBack() {
        val m = ManagerChats.fresh(); m.newChat(2_000, false)
        val d = m.delete(1, 3_000)!!
        assertTrue(d.wasCur && d.wasLive); assertEquals(2, d.replacement)
        assertEquals(2, m.live); assertEquals(2, m.cur); assertTrue(m.hidden(2_500))
        assertFalse(m.visible().any { it.g == 1 })
        m.restore(d, replacementBlank = true)
        assertEquals(listOf(0, 1), m.all().map { it.g }); assertEquals(1, m.cur); assertEquals(1, m.live); assertFalse(m.hidden(2_500))
    }

    @Test fun undoKeepsTheReplacementOnceSomethingWasSaidInIt() {
        val m = ManagerChats.fresh(); m.newChat(2_000, false)
        val d = m.delete(1, 3_000)!!
        m.restore(d, replacementBlank = false)
        assertEquals(listOf(0, 1, 2), m.visible().map { it.g }); assertEquals(2, m.live)
    }

    @Test fun deletingAnOlderChatKeepsTheOpenOne() {
        val m = ManagerChats.fresh(); m.newChat(2_000, false)
        val d = m.delete(0, 3_000)!!
        assertFalse(d.wasCur); assertNull(d.replacement); assertEquals(1, m.cur)
        assertTrue(m.hidden(100)); assertNull(m.delete(0, 3_001))
    }

    @Test fun purgeIsForeverAndRepairFinishesAnInterruptedDelete() {
        val m = ManagerChats.fresh(); m.newChat(2_000, false); m.newChat(4_000, false)
        m.delete(1, 5_000)
        val back = ManagerChats.read(m.toJson())          // the app closed inside the Undo window
        assertTrue(back.get(1)!!.purged); assertTrue(back.hidden(3_000))
        assertFalse(back.open(1))
        assertEquals(0, back.chatOf(1_999)); assertEquals(2, back.chatOf(4_000))   // windows of the others unchanged
    }

    @Test fun jsonRoundTripsAndBrokenTextStartsFresh() {
        val m = ManagerChats.fresh(); m.newChat(2_000, false); m.rename(1, "Plans"); m.pin(1, 9); m.replied(0, viewing = true)
        val back = ManagerChats.read(m.toJson())
        assertEquals(m.all(), back.all()); assertEquals(1, back.cur)
        assertEquals(listOf(0), ManagerChats.read("{not json").all().map { it.g })
        assertEquals(listOf(0), ManagerChats.read("{\"v\":1,\"list\":[]}").all().map { it.g })
        assertNotNull(ManagerChats.read("{\"v\":1,\"cur\":7,\"list\":[{\"g\":0,\"c\":0}]}").get(0))
        assertEquals(0, ManagerChats.read("{\"v\":1,\"cur\":7,\"list\":[{\"g\":0,\"c\":0}]}").cur)
    }

    @Test fun whenEveryChatIsGoneRepairOpensANewOne() {
        val back = ManagerChats.read("{\"v\":1,\"cur\":0,\"list\":[{\"g\":0,\"c\":0,\"purged\":true}]}")
        assertEquals(1, back.live); assertEquals(1, back.cur); assertFalse(back.get(1)!!.gone)
    }
}
