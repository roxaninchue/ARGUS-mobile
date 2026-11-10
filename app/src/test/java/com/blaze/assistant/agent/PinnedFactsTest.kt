package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unreleased after 0.52.3: pinned facts (cap, prompt block, storage), the pin / unpin asks, and the "holding" page's parts. */
class PinnedFactsTest {
    private fun fact(i: Long, t: String = "fact $i") = PinnedFacts.Fact(i, t, i)

    @Test fun pinsTidyAndRefusesDuplicates() {
        val a = PinnedFacts.add(emptyList(), "  that I'm allergic to peanuts. ", 1, 1)
        assertEquals(PinnedFacts.Outcome.ADDED, a.outcome)
        assertEquals("I'm allergic to peanuts", a.list.single().text)
        assertTrue(a.message.startsWith("Pinned: I'm allergic to peanuts"))
        val b = PinnedFacts.add(a.list, "i'm allergic to PEANUTS", 2, 2)
        assertEquals(PinnedFacts.Outcome.SAME, b.outcome)
        assertEquals(a.list, b.list)
        assertEquals(PinnedFacts.Outcome.EMPTY, PinnedFacts.add(emptyList(), " \"\" ", 3, 3).outcome)
        assertEquals(PinnedFacts.Outcome.TOO_LONG, PinnedFacts.add(emptyList(), "x".repeat(PinnedFacts.MAX_CHARS + 1), 4, 4).outcome)
    }

    @Test fun aFullListSaysSoAndDropsNothing() {
        val twenty = (1L..PinnedFacts.MAX_FACTS).map { fact(it) }
        val r = PinnedFacts.add(twenty, "one more", 99, 99)
        assertEquals(PinnedFacts.Outcome.FULL, r.outcome)
        assertEquals(twenty, r.list)
        assertTrue(r.message.contains("20 of 20") && r.message.contains("/holding"))
        // the token cap: a few long facts fill it before 20 do
        val long = (1L..7L).map { fact(it, "y".repeat(400)) }
        val t = PinnedFacts.add(long, "z".repeat(400), 100, 100)
        assertEquals(PinnedFacts.Outcome.FULL, t.outcome)
        assertTrue(t.message.contains("${PinnedFacts.MAX_TOKENS} tokens"))
        assertTrue(PinnedFacts.tokens(t.list) <= PinnedFacts.MAX_TOKENS)
    }

    @Test fun blockRemoveMatchAndRoundTrip() {
        assertEquals("", PinnedFacts.block(emptyList()))
        val list = listOf(fact(1, "Coffee black, no sugar"), fact(2, "My sister is Thandi"))
        val b = PinnedFacts.block(list)
        assertTrue(b.contains("PINNED BY THE USER") && b.contains("\u201cCoffee black, no sugar\u201d") && b.contains("Thandi"))
        assertEquals(listOf(list[1]), PinnedFacts.remove(list, 1))
        assertEquals(listOf(list[0]), PinnedFacts.matching(list, "coffee sugar"))
        assertEquals(emptyList<PinnedFacts.Fact>(), PinnedFacts.matching(list, "  "))
        assertEquals(list, PinnedFacts.decode(PinnedFacts.encode(list)))
        assertEquals(emptyList<PinnedFacts.Fact>(), PinnedFacts.decode("not json"))
        assertEquals(emptyList<PinnedFacts.Fact>(), PinnedFacts.decode(""))
    }

    @Test fun pinAndUnpinAsks() {
        assertEquals(MemoryIntent.PinAsk("", true), MemoryIntent.pinAsk("pin this"))
        assertEquals(MemoryIntent.PinAsk("", true), MemoryIntent.pinAsk("Please pin that message."))
        assertEquals(MemoryIntent.PinAsk("", true), MemoryIntent.pinAsk("never forget this"))
        assertEquals(MemoryIntent.PinAsk("I'm vegetarian", false), MemoryIntent.pinAsk("pin: I'm vegetarian"))
        assertEquals(MemoryIntent.PinAsk("I'm vegetarian", false), MemoryIntent.pinAsk("pin that I'm vegetarian"))
        assertEquals(MemoryIntent.PinAsk("my mum's birthday is 3 May", false), MemoryIntent.pinAsk("never forget my mum's birthday is 3 May"))
        assertEquals(MemoryIntent.PinAsk("that I work nights", false), MemoryIntent.pinAsk("Always remember that I work nights"))
        // not pin requests
        assertNull(MemoryIntent.pinAsk("pin the orb"))
        assertNull(MemoryIntent.pinAsk("pin-up posters for my room"))
        assertNull(MemoryIntent.pinAsk("/pin coffee"))
        assertNull(MemoryIntent.pinAsk("can you never forget things?"))
        assertNull(MemoryIntent.pinAsk("what's my pin code"))
        assertEquals("coffee", MemoryIntent.unpinAsk("unpin coffee"))
        assertEquals("the sister one", MemoryIntent.unpinAsk("please unpin the sister one."))
        assertNull(MemoryIntent.unpinAsk("unpin"))
        assertNull(MemoryIntent.unpinAsk("pin this"))
        // the memory list request is still its own thing
        assertTrue(MemoryIntent.isListRequest("show me your memories"))
    }

    @Test fun holdingSections() {
        val empty = Holding.sections(Holding.Snapshot.EMPTY)
        assertEquals(listOf("This chat's summary", "Current task", "Skills loaded for the last request"), empty.map { it.title })
        assertTrue(empty.all { it.lines.isEmpty() && it.empty.isNotBlank() })
        val s = Holding.sections(Holding.Snapshot(emptyList(), "We planned the Lusaka trip.", GoalPin.Goal("book a table for 4", listOf("under K500")), listOf("restaurants", "restaurants", " ")))
        assertEquals(listOf("We planned the Lusaka trip."), s[0].lines)
        assertEquals(listOf("book a table for 4", "Constraint: under K500"), s[1].lines)
        assertEquals(listOf("restaurants"), s[2].lines)
        assertEquals("Pinned facts \u00b7 3 of ${PinnedFacts.MAX_FACTS}", Holding.pinnedTitle(3))
    }
}
