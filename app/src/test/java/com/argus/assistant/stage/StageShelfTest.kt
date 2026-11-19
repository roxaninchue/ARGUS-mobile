package com.argus.assistant.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-19: cards taken off the Stage are kept for the notch's glance layer. */
class StageShelfTest {
    private fun item(id: String, kind: StageKind, vararg p: Pair<String, String>) = StageItem(id, kind, mapOf(*p))

    @Test fun closedCardsGoOnTopNewestFirst() {
        var s = StageShelf.took(emptyList(), listOf(item("timer1", StageKind.TIMER, "title" to "Study", "minutes" to "30")), 1000)
        s = StageShelf.took(s, listOf(item("note2", StageKind.NOTE, "text" to "Buy milk\nand eggs")), 2000)
        assertEquals(listOf("note2", "timer1"), s.map { it.id })
        assertEquals("Buy milk", s[0].title)
        assertEquals("Study", s[1].title)
        assertEquals("30", s[1].props["minutes"])
    }

    @Test fun reducerCloseFeedsTheShelf() {
        // what StageStore.dispatch does: the ids that left the state go on the shelf
        val st = StageReducer.reduce(StageState(), StageAction.Show(StageKind.MUSIC, mapOf("query" to "Lo-fi"), StageMode.WIDGET)).state
        val id = st.items.single().id
        val after = StageReducer.reduce(st, StageAction.Close(id)).state
        val gone = st.items.filter { it.id !in after.items.map { i -> i.id }.toSet() }
        val shelf = StageShelf.took(emptyList(), gone, 5)
        assertEquals("Lo-fi", shelf.single().title)
        assertEquals(StageKind.MUSIC, shelf.single().kind)
    }

    /** Owner, round 2: every card is kept as it comes, music / video / browser included; only the same card moves to the top. */
    @Test fun everyCardIsKeptAndTheSameCardMovesToTheTop() {
        var s = StageShelf.took(emptyList(), listOf(item("music1", StageKind.MUSIC, "query" to "Jazz")), 1)
        s = StageShelf.took(s, listOf(item("music5", StageKind.MUSIC, "query" to "Lo-fi")), 2)
        assertEquals(listOf("music5", "music1"), s.map { it.id })
        s = StageShelf.took(s, listOf(item("plan2", StageKind.PLAN)), 3)
        s = StageShelf.took(s, listOf(item("plan2", StageKind.PLAN, "title" to "Today")), 4)
        assertEquals(listOf("plan2", "music5", "music1"), s.map { it.id })
        assertEquals("Today", s[0].title)
    }

    @Test fun noCap() {
        var s = emptyList<ShelfCard>()
        for (i in 1..500) s = StageShelf.took(s, listOf(item("note$i", StageKind.NOTE, "text" to "n$i")), i.toLong())
        assertEquals(500, s.size)
        assertEquals("note500", s.first().id); assertEquals("note1", s.last().id)
    }

    @Test fun dropAndCodec() {
        val s = StageShelf.took(emptyList(), listOf(item("browser3", StageKind.BROWSER, "url" to "https://example.com"), item("quiz4", StageKind.QUIZ, "title" to "Bio")), 9)
        assertEquals(listOf("quiz4", "browser3"), StageShelf.drop(s, "nothing").map { it.id })
        assertEquals(listOf("browser3"), StageShelf.drop(s, "stage:quiz4").map { it.id })
        assertEquals(listOf("quiz4"), StageShelf.drop(s, "browser3").map { it.id })
        assertEquals(s, StageShelf.decode(StageShelf.encode(s)))
        assertEquals(emptyList<ShelfCard>(), StageShelf.decode("not json"))
        assertEquals("https://example.com", s[1].title)
    }
}
