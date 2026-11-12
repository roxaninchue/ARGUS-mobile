package com.blaze.assistant.ui.team

import com.blaze.assistant.agent.ChatMeta
import com.blaze.assistant.agent.ManagerChats
import com.blaze.assistant.ui.team.ChatDrawerModel.Key
import com.blaze.assistant.ui.team.ChatDrawerModel.Msg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/** P1-SH-04: the Chat mode drawer, ported from A-team talks.js (v2.19-chat-drawer) listHTML / title / clean / search / undo. */
class ChatDrawerTest {
    private val zone = ZoneId.of("Africa/Lusaka")
    private fun at(d: Int, h: Int) = LocalDateTime.of(2026, 11, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private val now = at(12, 12)

    /** Five chats: started 12 Nov 10:00 (today), 11 Nov 23:00 (yesterday), 8 Nov (7 days), 1 Nov (older), 12 Nov 11:00 (empty). */
    private fun five(): Pair<ManagerChats, List<Msg>> {
        val m = ManagerChats.fresh()
        val starts = listOf(at(8, 9), at(11, 23), at(12, 10), at(12, 11))
        for (s in starts) m.newChat(s, liveBlank = false)
        val msgs = listOf(
            Msg(true, "Old plans for October", at(1, 9)),
            Msg(true, "Budget for the **week**", at(8, 9) + 1), Msg(false, "Here's a budget with rent and food.", at(8, 9) + 2),
            Msg(true, "Late question", at(11, 23) + 1),
            Msg(true, "Book the dentist", at(12, 10) + 1), Msg(false, "Done for Friday at 9.", at(12, 10) + 2),
        )
        return m to msgs
    }

    @Test fun groupsPinnedThenTodayYesterdaySevenDaysOlder() {
        val (m, msgs) = five()
        m.pin(1, now)   // the 8 Nov chat
        val s = ChatDrawerModel.state(m, msgs, emptyList(), now, zone)
        assertEquals(listOf("Pinned", "Today", "Yesterday", "Older"), s.sections.map { it.name })
        assertEquals("Budget for the week", s.sections[0].entries.single().title)
        assertEquals("Book the dentist", s.sections[1].entries.single().title)
        assertEquals("Late question", s.sections[2].entries.single().title)
        assertEquals("Old plans for October", s.sections[3].entries.single().title)
        assertFalse(s.entries.any { it.key == Key.Manager(4) })   // the empty newest chat isn't listed
        m.pin(1, now)
        assertEquals(listOf("Today", "Yesterday", "Previous 7 days", "Older"), ChatDrawerModel.state(m, msgs, emptyList(), now, zone).sections.map { it.name })
    }

    @Test fun groupBoundariesAreLocalMidnights() {
        assertEquals("Today", ChatDrawerModel.group(at(12, 0), now, zone))
        assertEquals("Yesterday", ChatDrawerModel.group(at(12, 0) - 1, now, zone))
        assertEquals("Previous 7 days", ChatDrawerModel.group(at(11, 0) - 1, now, zone))
        assertEquals("Previous 7 days", ChatDrawerModel.group(at(5, 0), now, zone))
        assertEquals("Older", ChatDrawerModel.group(at(5, 0) - 1, now, zone))
    }

    @Test fun titlesFollowTalksJs() {
        assertEquals("Budget for the week", ChatDrawerModel.clean("Budget for the **week**"))
        assertEquals("", ChatDrawerModel.clean("```kotlin\nval x = 1\n```"))
        val long = "Please help me plan a three day trip to Livingstone with the falls, a sunset cruise and a cheap place"
        val t = ChatDrawerModel.clean(long)
        assertTrue(t.endsWith("\u2026")); assertTrue(t.length <= 61); assertFalse(t.dropLast(1).endsWith(" "))
        val (m, msgs) = five(); m.rename(3, "Dentist")
        val s = ChatDrawerModel.state(m, msgs, emptyList(), now, zone)
        assertEquals("Dentist", s.entries.first { it.key == Key.Manager(3) }.title)
        val onlyReply = ChatDrawerModel.state(ManagerChats.fresh(), listOf(Msg(false, "A reminder fired", 5)), emptyList(), now, zone)
        assertEquals("A reminder fired", onlyReply.entries.single().title)
    }

    @Test fun searchMatchesTitlesThenTextWithASnippet() {
        val (m, msgs) = five()
        val byTitle = ChatDrawerModel.state(m, msgs, emptyList(), now, zone, query = "DENTIST")
        assertEquals(listOf("Book the dentist"), byTitle.entries.map { it.title }); assertNull(byTitle.entries.single().snippet)
        val byText = ChatDrawerModel.state(m, msgs, emptyList(), now, zone, query = "rent")
        assertEquals("Budget for the week", byText.entries.single().title)
        assertEquals("Here's a budget with rent and food.", byText.entries.single().snippet)
        val far = ChatDrawerModel.match(byText.entries.single().copy(title = "x"), listOf("a".repeat(40) + " the key word here"), "key")!!
        assertEquals("\u2026" + "a".repeat(19) + " the key word here", far.snippet)
        val none = ChatDrawerModel.state(m, msgs, emptyList(), now, zone, query = "zebra")
        assertEquals(ChatDrawerModel.NO_MATCH, none.empty); assertTrue(none.sections.isEmpty())
        assertEquals(ChatDrawerModel.NO_MATCH, ChatDrawerModel.state(m, msgs, emptyList(), now, zone, query = "(.*").empty)   // literal, no regex
        assertEquals(ChatDrawerModel.EMPTY, ChatDrawerModel.state(ManagerChats.fresh(), emptyList(), emptyList(), now, zone).empty)
    }

    @Test fun dotsLiveAndCurrent() {
        val (m, msgs) = five()
        m.replied(2, viewing = false); m.open(3)
        val s = ChatDrawerModel.state(m, msgs, emptyList(), now, zone, replying = 1)
        val e = s.entries.associateBy { it.key }
        assertTrue(e[Key.Manager(2)]!!.unseen); assertTrue(e[Key.Manager(1)]!!.live); assertTrue(e[Key.Manager(3)]!!.current)
        assertFalse(e[Key.Manager(3)]!!.unseen)
        assertEquals("Late question, new reply", e[Key.Manager(2)]!!.spoken)
        val inProject = ChatDrawerModel.state(m, msgs, emptyList(), now, zone, currentProjectChat = "p1")
        assertFalse(inProject.entries.any { it.current })
    }

    @Test fun projectChatsJoinTheListAndTheSearch() {
        val (m, msgs) = five()
        val p = ChatDrawerModel.ProjectChat(ChatMeta("p1", "Pitch deck", at(12, 9), false, "Slides done"), "ARGUS launch", listOf("We need a seed round slide"))
        val s = ChatDrawerModel.state(m, msgs, listOf(p), now, zone)
        val e = s.sections.first { it.name == "Today" }.entries
        assertEquals(listOf("Book the dentist", "Pitch deck"), e.map { it.title })
        assertEquals("ARGUS launch", e[1].project)
        assertEquals("Pitch deck", ChatDrawerModel.state(m, msgs, listOf(p), now, zone, query = "seed round").entries.single().title)
    }

    @Test fun deletedChatsLeaveTheListAndUndoBringsThemBack() {
        val (m, msgs) = five()
        val d = m.delete(2, now)!!
        assertFalse(ChatDrawerModel.state(m, msgs, emptyList(), now, zone).entries.any { it.key == Key.Manager(2) })
        assertTrue(ChatDrawerModel.Undo.open(d, now + 4_999)); assertFalse(ChatDrawerModel.Undo.open(d, now + 5_000))
        m.restore(d, replacementBlank = true)
        assertTrue(ChatDrawerModel.state(m, msgs, emptyList(), now, zone).entries.any { it.key == Key.Manager(2) })
        val d2 = m.delete(2, now)!!; m.purge(d2.g)
        m.restore(d2, replacementBlank = true)   // too late: purged stays gone
        assertFalse(ChatDrawerModel.state(m, msgs, emptyList(), now, zone).entries.any { it.key == Key.Manager(2) })
    }

    @Test fun blankCopyAndGreeting() {
        val (m, msgs) = five()
        assertTrue(ChatDrawerModel.blank(m, 4, msgs)); assertFalse(ChatDrawerModel.blank(m, 3, msgs))
        assertEquals("You: Book the dentist\n\nARGUS: Done for Friday at 9.",
            ChatDrawerModel.transcript(ChatDrawerModel.messagesOf(m, 3, msgs), "You", "ARGUS"))
        assertEquals("Evening. What's on your mind?", ChatModeCopy.greeting(19))
        assertEquals("Morning, Finn. What's on your mind?", ChatModeCopy.greeting(7, "Finn"))
        assertEquals("Late one. What's on your mind?", ChatModeCopy.greeting(2, " "))
    }

    /** Source pins: the drawer's edge swipe asks for system gesture exclusion (06: "edge-swipe uses system gesture exclusion"). */
    @Test fun drawerEdgeUsesSystemGestureExclusion() {
        val src = File("src/main/java/com/blaze/assistant/ui/team/ChatDrawer.kt").readText()
        assertTrue(src.contains("systemGestureExclusion"))
        assertTrue(src.contains("ModalNavigationDrawer"))
    }
}
