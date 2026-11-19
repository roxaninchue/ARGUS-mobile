package com.argus.assistant.glance

import com.argus.assistant.stage.ShelfCard
import com.argus.assistant.stage.StageKind
import com.argus.assistant.store.JdbcSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 2026-11-19 (owner, round 2): the shelf keeps every card (SQLite, no cap) and is read a page at a time, newest first. */
class GlanceShelfTest {
    private fun card(i: Int, at: Long = i.toLong(), kind: StageKind = StageKind.NOTE) =
        ShelfCard("note$i", kind, mapOf("text" to "n$i"), "Note $i", at)

    @Test fun unboundedAndPagedNewestFirst() {
        val db = ShelfDb(JdbcSql())
        for (i in 1..1500) db.add(listOf(card(i)))
        assertEquals(1500, db.count())
        assertEquals(listOf("note1500", "note1499", "note1498"), db.page(0, 3).map { it.id })
        assertEquals(listOf("note1", "note2").reversed(), db.page(1498, 10).map { it.id })
        assertTrue(db.page(1500, 10).isEmpty()); assertTrue(db.page(0, 0).isEmpty())
        // pages stitched together = the whole shelf, no gaps, no repeats
        val all = (0 until 1500 step 64).flatMap { db.page(it, 64).map { c -> c.id } }
        assertEquals(1500, all.size); assertEquals(1500, all.toSet().size)
    }

    @Test fun sameCardMovesToTheTopAndTiesKeepArrivalOrder() {
        val db = ShelfDb(JdbcSql())
        db.add(listOf(card(1, 10), card(2, 10), card(3, 10)))   // one Close all: the last to leave is the newest
        assertEquals(listOf("note3", "note2", "note1"), db.page(0, 10).map { it.id })
        db.add(listOf(card(1, 20).copy(title = "Again")))
        assertEquals(listOf("note1", "note3", "note2"), db.page(0, 10).map { it.id })
        assertEquals(3, db.count()); assertEquals("Again", db.byId("stage:note1")?.title)
        assertEquals(mapOf("text" to "n2"), db.byId("note2")?.props)
    }

    @Test fun skipLookupAndDrop() {
        val db = ShelfDb(JdbcSql())
        for (i in 1..10) db.add(listOf(card(i)))
        val skip = setOf("stage:note10", "stage:note8", "clock")
        assertEquals(8, db.count(skip))
        assertEquals(listOf("note9", "note7", "note6"), db.page(0, 3, skip).map { it.id })
        assertEquals(listOf("note4"), db.byTitle("note 4").map { it.id })
        assertTrue(db.drop("stage:note4")); assertFalse(db.drop("note4"))
        assertNull(db.byId("note4")); assertEquals(9, db.count())
    }

    @Test fun theLayerOverTheDatabase() {
        val db = ShelfDb(JdbcSql())
        for (i in 1..200) db.add(listOf(card(i)))
        val inp = Glance.Inputs(1_000_000L, ZoneId.of("Africa/Lusaka"), shelf = db)
        val p = Glance.Prefs(pins = listOf("stage:note3"))
        val first = Glance.window(inp, p, 0, Glance.PAGE)
        assertEquals(listOf("clock", "stage:note3", "stage:note200", "stage:note199"), first.take(4).map { it.id })
        assertEquals(Glance.PAGE, first.size)
        assertEquals(201, Glance.total(inp, p))   // clock + the pinned card + the other 199
        val whole = (0 until 201 step Glance.PAGE).flatMap { Glance.window(inp, p, it, Glance.PAGE) }
        assertEquals(201, whole.map { it.id }.toSet().size)
        // remove goes to the database; restore names the card
        val o = Glance.act(Glance.Op.REMOVE, "Note 150", inp, p)
        assertEquals("stage:note150", o.dropShelf)
        assertEquals("stage:note7", Glance.act(Glance.Op.RESTORE, "note7", inp, p).restore)
    }
}
