package com.argus.assistant.ui.team

import com.argus.assistant.policy.NeedsYou
import com.argus.assistant.policy.NeedsYou.CardSrc
import com.argus.assistant.policy.NeedsYou.Filter
import com.argus.assistant.team.ActionClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-SF-03 InboxTest: the Inbox list's state (filter, selection, bulk enablement, chips from the one list). */
class InboxModelTest {
    private val now = 50_000_000L
    private fun a(id: String, cls: ActionClass, bot: String = "nova") = CardSrc(id, "approval", bot, bot, cls, id, "", now - 60_000, now + 10 * 60_000)
    private fun q(id: String, bot: String = "nova") = CardSrc(id, "question", bot, bot, null, id, "", now - 120_000)
    private val items = NeedsYou.build(listOf(a("a1", ActionClass.READ), a("a2", ActionClass.WRITE_REMOTE), a("a3", ActionClass.SPEND),
        a("b1", ActionClass.READ, bot = "atlas"), q("q1"), q("q2")), emptyList(), now)

    @Test fun chipsCountFromTheSameList() {
        val chips = InboxModel.chips(items, InboxModel.State())
        assertEquals(Filter.entries.size, chips.size)
        assertEquals(items.size, chips.first { it.filter == Filter.ALL }.count)
        assertEquals(4, chips.first { it.filter == Filter.APPROVALS }.count)
        assertTrue(chips.first { it.filter == Filter.ALL }.on)
    }

    @Test fun filterShowsOnlyThatKindAndClearsSelection() {
        var s = InboxModel.toggle(InboxModel.State(), "a1")
        s = InboxModel.setFilter(s, Filter.QUESTIONS)
        assertTrue(s.selected.isEmpty())
        assertEquals(listOf("q1", "q2"), InboxModel.visible(items, s).map { it.id })
    }

    @Test fun bulkOnlyOneBotOneKindNoMoney() {
        var s = InboxModel.State(Filter.APPROVALS)
        s = InboxModel.toggle(InboxModel.toggle(s, "a1"), "a2")
        assertTrue(InboxModel.bulkEnabled(items, s))
        assertFalse(InboxModel.bulkEnabled(items, InboxModel.toggle(s, "a3")))   // SPEND in selection
        assertFalse(InboxModel.bulkEnabled(items, InboxModel.toggle(s, "b1")))   // second bot
        assertFalse(InboxModel.bulkEnabled(items, InboxModel.toggle(InboxModel.State(), "a1")))  // one item
        val qs = InboxModel.toggle(InboxModel.toggle(InboxModel.State(), "q1"), "q2")
        assertFalse(InboxModel.bulkEnabled(items, qs))                           // questions are answered one by one
    }

    @Test fun answeredItemsDropOutOfSelection() {
        val s = InboxModel.toggle(InboxModel.toggle(InboxModel.State(), "a1"), "a2")
        val after = items.filter { it.id != "a1" }
        assertEquals(setOf("a2"), InboxModel.prune(after, s).selected)
        assertTrue(InboxModel.selecting(s)); assertFalse(InboxModel.selecting(InboxModel.clear(s)))
    }

    @Test fun rowTime() {
        assertEquals("10 min left", InboxModel.time(items.first { it.id == "a1" }, now))
        assertEquals("2m ago", InboxModel.time(items.first { it.id == "q1" }, now))
        assertEquals("Nothing needs you.", InboxModel.empty(Filter.ALL))
        assertEquals("No failed waiting.", InboxModel.empty(Filter.FAILED))
    }
}
