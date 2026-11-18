package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 2026-11-18 flashcards redesign: two-grade study (thumbs down / up), undo, edit, rename, study all, session re-asks. */
class FlashcardsRedesignTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Before fun setUp() {
        Flashcards.fileOverride = File(Files.createTempDirectory("learn").toFile(), "cards.json"); Flashcards.reset()
    }
    @After fun tearDown() { Flashcards.fileOverride = null; Flashcards.reset() }

    @Test fun twoGradesMapToAgainAndGood() {
        assertEquals(Flashcards.AGAIN, Flashcards.FORGOT); assertEquals(Flashcards.GOOD, Flashcards.KNEW)
        val c = Card("a", "D", "q", "a")
        assertEquals("10 min", Flashcards.label(c, Flashcards.FORGOT, now))
        assertEquals("1 d", Flashcards.label(c, Flashcards.KNEW, now))
    }

    @Test fun undoTakesBackTheLastChangeOnly() {
        Flashcards.add("NET103", listOf("q1" to "a1", "q2" to "a2"))
        assertEquals("", Flashcards.undoable.value)
        val id = Flashcards.queue("NET103", now).first().id
        Flashcards.answer(id, Flashcards.KNEW, now)
        assertEquals("grade", Flashcards.undoable.value)
        assertEquals(1, Flashcards.load().single { it.id == id }.reps)
        assertTrue(Flashcards.undo())
        assertEquals(0, Flashcards.load().single { it.id == id }.reps)
        assertFalse(Flashcards.undo())
        Flashcards.deleteCard(id); assertEquals(1, Flashcards.load().size)
        assertEquals("delete", Flashcards.undoable.value)
        assertTrue(Flashcards.undo()); assertEquals(2, Flashcards.load().size)
        Flashcards.deleteDeck("NET103"); assertTrue(Flashcards.load().isEmpty())
        assertTrue(Flashcards.undo()); assertEquals(2, Flashcards.load().size)
        Flashcards.reset(); assertEquals(2, Flashcards.load().size)   // the undone state is what's on disk
    }

    @Test fun editKeepsScheduleAndBlankSides() {
        Flashcards.add("D", listOf("q" to "a"))
        val id = Flashcards.load().single().id
        Flashcards.answer(id, Flashcards.KNEW, now)
        assertTrue(Flashcards.edit(id, "  new q ", ""))
        val c = Flashcards.load().single()
        assertEquals("new q", c.front); assertEquals("a", c.back); assertEquals(1, c.reps); assertEquals(now + day, c.due)
        assertFalse(Flashcards.edit("nope", "x", "y"))
        assertTrue(Flashcards.undo()); assertEquals("q", Flashcards.load().single().front)
    }

    @Test fun renameMovesAndMergesDecks() {
        Flashcards.add("A", listOf("1" to "x", "2" to "y")); Flashcards.add("B", listOf("3" to "z"))
        assertEquals(2, Flashcards.renameDeck("A", " B "))
        assertEquals(listOf("B"), Flashcards.decks(now).map { it.name })
        assertEquals(0, Flashcards.renameDeck("missing", "C")); assertEquals(0, Flashcards.renameDeck("B", "  "))
        val r = Flashcards.run(JSONObject().put("action", "rename_deck").put("deck", "B").put("to", "NET103 week 1"))
        assertTrue(r, r.contains("3 cards")); assertEquals("NET103 week 1", Flashcards.decks(now).single().name)
    }

    @Test fun studyAllCoversEveryDeckDueFirst() {
        Flashcards.add("A", listOf("a1" to "x")); Flashcards.add("B", listOf("b1" to "y", "b2" to "z"))
        val b1 = Flashcards.load().single { it.front == "b1" }.id
        Flashcards.answer(b1, Flashcards.KNEW, now - 2 * day)   // due a day ago
        val q = Flashcards.queue(Flashcards.ALL, now)
        assertEquals(3, q.size); assertEquals("b1", q.first().front)
        assertEquals(3, Flashcards.waiting(now))
        assertFalse(Flashcards.decks(now).any { it.name == Flashcards.ALL })
        assertEquals("all", Flashcards.add(Flashcards.ALL, listOf("q" to "a")).let { Flashcards.load().last().deck })
    }

    @Test fun aForgottenCardComesBackBeforeTheSessionEnds() {
        Flashcards.add("D", listOf("q" to "a"))
        val id = Flashcards.load().single().id
        Flashcards.answer(id, Flashcards.FORGOT, now)
        assertTrue(Flashcards.queue("D", now).isEmpty())         // its 10 minutes aren't up
        assertEquals(id, Flashcards.next("D", listOf(id), now)?.id)   // but the session asks it again
        Flashcards.answer(id, Flashcards.KNEW, now)
        assertNull(Flashcards.next("D", emptyList(), now))
        assertEquals(now + day, Flashcards.nextDue("D", now)); assertEquals(now + day, Flashcards.nextDue(Flashcards.ALL, now))
    }

    @Test fun waitsReadShort() {
        assertEquals("now", Flashcards.until(0)); assertEquals("10 min", Flashcards.until(600_000))
        assertEquals("3 h", Flashcards.until(3 * 3_600_000L)); assertEquals("2 d", Flashcards.until(2 * day))
        assertEquals("2 mo", Flashcards.until(60 * day))
    }
}
