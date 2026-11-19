package com.argus.assistant.notify

import com.argus.assistant.notify.Inbox.Item
import com.argus.assistant.notify.Inbox.Kind
import com.argus.assistant.phone.PhoneControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 16 Nov 2026: the Inbox (Argus's messages per chat, your replies, what isn't working) and the 5 s reconnecting banner. */
class InboxTest {
    private fun msg(id: Long, chat: String = "", text: String = "hi $id") = Item(id, id, Kind.MESSAGE, text, chat)
    private fun problem(id: Long, key: String, text: String = "broken $id") = Item(id, id, Kind.PROBLEM, text, key = key)

    @Test fun aProblemIsListedOnceAndFixedWhenItRecovers() {
        var l = Inbox.add(emptyList(), problem(1, "phone_control"))
        l = Inbox.add(l, problem(2, "phone_control", "still broken"))
        assertEquals(1, Inbox.problems(l).size)
        assertEquals("still broken", Inbox.problems(l).single().text)
        assertEquals(1, Inbox.unread(l))
        l = Inbox.resolve(l, "phone_control")
        assertTrue(Inbox.problems(l).isEmpty())
        assertEquals(0, Inbox.unread(l))
        // A new drop after the fix is a new open problem; the fixed one stays as history.
        l = Inbox.add(l, problem(3, "phone_control"))
        assertEquals(1, Inbox.problems(l).size)
        assertEquals(2, l.size)
    }

    @Test fun messagesGroupIntoThreadsPerChatNewestFirst() {
        var l = Inbox.add(emptyList(), msg(1, "a"))
        l = Inbox.add(l, msg(2, "b"))
        l = Inbox.add(l, Item(3, 3, Kind.YOU, "ok", "a"))
        val t = Inbox.threads(l)
        assertEquals(listOf("a", "b"), t.map { it.first })
        assertEquals(listOf(Kind.MESSAGE, Kind.YOU), t.first().second.map { it.kind })
        assertEquals("your own reply arrives read", 2, Inbox.unread(l))
        assertEquals(0, Inbox.unread(Inbox.readAll(l)))
        assertTrue(Inbox.threads(Inbox.add(l, problem(4, "x"))).none { (_, items) -> items.any { it.kind == Kind.PROBLEM } })
    }

    @Test fun itKeepsAtMostMaxAndRoundTripsJson() {
        var l = emptyList<Item>()
        for (i in 1..(Inbox.MAX + 20)) l = Inbox.add(l, msg(i.toLong()))
        assertEquals(Inbox.MAX, l.size)
        assertEquals(21L, l.first().id)
        val back = Inbox.decode(Inbox.encode(l))
        assertEquals(l, back)
        assertTrue(Inbox.decode("not json").isEmpty())
        assertTrue(Inbox.decode(null).isEmpty())
        assertEquals(Inbox.TEXT_MAX, Inbox.add(emptyList(), msg(1, text = "x".repeat(Inbox.TEXT_MAX + 50))).single().text.length)
    }

    @Test fun reconnectingShowsForFiveSecondsOffAlwaysShows() {
        val t0 = 1_000_000L
        assertTrue(PhoneControl.showBanner(PhoneControl.State.RECONNECTING, t0, t0 + 1_000))
        assertFalse(PhoneControl.showBanner(PhoneControl.State.RECONNECTING, t0, t0 + PhoneControl.RECONNECTING_SHOW_MS))
        assertTrue(PhoneControl.showBanner(PhoneControl.State.OFF, t0, t0 + 60_000))
        assertFalse(PhoneControl.showBanner(PhoneControl.State.CONNECTED, t0, t0))
        assertEquals(5_000L, PhoneControl.RECONNECTING_SHOW_MS)
    }

    @Test fun inboxWords() {
        val now = 10_000_000L
        assertEquals("now", com.argus.assistant.ui.InboxText.ago(now - 5_000, now))
        assertEquals("3 min ago", com.argus.assistant.ui.InboxText.ago(now - 180_000, now))
        assertEquals("2 h ago", com.argus.assistant.ui.InboxText.ago(now - 7_200_000, now))
        assertEquals("Chat", com.argus.assistant.ui.InboxText.threadTitle(""))
        assertEquals("Chat", com.argus.assistant.ui.InboxText.threadTitle(com.argus.assistant.agent.Mind.MAIN))
        assertEquals("Another chat", com.argus.assistant.ui.InboxText.threadTitle("p_123"))
    }
}
