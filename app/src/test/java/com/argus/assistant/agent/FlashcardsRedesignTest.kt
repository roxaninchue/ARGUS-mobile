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
        assertEquals("1 min", Flashcards.label(c, Flashcards.FORGOT, now))
        assertEquals("4 d", Flashcards.label(c, Flashcards.KNEW, now))
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
        assertEquals("new q", c.front); assertEquals("a", c.back); assertEquals(1, c.reps); assertEquals(now + 4 * day, c.due)
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
        Flashcards.answer(b1, Flashcards.KNEW, now - 5 * day)   // 4 days, so due a day ago
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
        assertTrue(Flashcards.queue("D", now).isEmpty())         // its relearning minute isn't up
        assertEquals(id, Flashcards.next("D", listOf(id), now)?.id)   // but the session asks it again
        Flashcards.answer(id, Flashcards.KNEW, now)
        assertNull(Flashcards.next("D", emptyList(), now))
        assertEquals(now + 600_000L, Flashcards.nextDue("D", now)); assertEquals(now + 600_000L, Flashcards.nextDue(Flashcards.ALL, now))
    }

    // ---------------- phase 2: FSRS, relearning steps, daily new limit ----------------

    @Test fun recallIsNinetyPercentAtTheStability() {
        assertEquals(0.9, Flashcards.recall(10.0, 10.0), 1e-9)
        assertTrue(Flashcards.recall(30.0, 10.0) < 0.9 && Flashcards.recall(1.0, 10.0) > 0.9)
    }

    @Test fun aMissRelearnsInOneThenTenMinutes() {
        var c = Flashcards.review(Card("a", "D", "q", "a"), Flashcards.KNEW, now)
        val t = now + c.interval * day
        c = Flashcards.review(c, Flashcards.FORGOT, t)
        assertEquals(1, c.step); assertEquals(t + 60_000L, c.due)
        c = Flashcards.review(c, Flashcards.KNEW, t + 60_000L)
        assertEquals(2, c.step); assertEquals(t + 660_000L, c.due)
        c = Flashcards.review(c, Flashcards.KNEW, t + 660_000L)
        assertEquals(0, c.step); assertTrue(c.interval >= 1); assertEquals(1, c.lapses)
        // a harder card after a miss: its next good review spaces less than a never-missed one
        assertTrue(c.difficulty > Flashcards.review(Card("b", "D", "q", "a"), Flashcards.KNEW, now).difficulty)
    }

    @Test fun waitingLongerGrowsTheIntervalMore() {
        val c = Flashcards.review(Card("a", "D", "q", "a"), Flashcards.KNEW, now)
        val onTime = Flashcards.review(c, Flashcards.KNEW, now + 4 * day).interval
        val late = Flashcards.review(c, Flashcards.KNEW, now + 12 * day).interval
        assertTrue("$late > $onTime", late > onTime)
    }

    @Test fun oldSm2CardsMigrateOnLoad() {
        val f = Flashcards.fileOverride!!
        f.parentFile.mkdirs()
        f.writeText("""{"format":1,"cards":[{"id":"x","deck":"D","front":"q","back":"a","due":$now,"interval":15,"ease":2.5,"reps":3,"lapses":0},
            {"id":"y","deck":"D","front":"q2","back":"a2","due":0,"interval":0,"ease":2.5,"reps":0,"lapses":0}]}""")
        Flashcards.reset()
        val x = Flashcards.load().single { it.id == "x" }
        assertEquals(15.0, x.stability, 1e-9); assertEquals(6.0, x.difficulty, 1e-9); assertEquals(now - 15 * day, x.last)
        assertTrue(Flashcards.load().single { it.id == "y" }.isNew)
        val next = Flashcards.review(x, Flashcards.KNEW, now)
        assertTrue(next.interval > 15)
        Flashcards.answer("x", Flashcards.KNEW, now); Flashcards.reset()
        assertEquals(Flashcards.load().single { it.id == "x" }.stability, next.stability, 1e-6)   // round-trips with its FSRS fields
    }

    @Test fun twentyNewCardsADayThenStudyMore() {
        Flashcards.add("Big", (1..30).map { "q$it" to "a$it" })
        assertEquals(20, Flashcards.queue("Big", now).size)
        assertEquals(20, Flashcards.decks(now).single().today); assertEquals(30, Flashcards.decks(now).single().fresh)
        Flashcards.queue("Big", now).take(5).forEach { Flashcards.answer(it.id, Flashcards.KNEW, now) }
        assertEquals(15, Flashcards.newLeft("Big", now)); assertEquals(15, Flashcards.queue("Big", now).size)
        assertEquals(10, Flashcards.heldBack("Big", now)); assertEquals(15, Flashcards.waiting(now))
        assertEquals(20, Flashcards.queue("Big", now, more = true).size)
        val tomorrow = Flashcards.dayStart(now) + day + 3_600_000L
        assertEquals(20, Flashcards.newLeft("Big", tomorrow))
    }

    // ---------------- phase 3: sources, typed answers, the study reminder ----------------

    @Test fun cardsKeepTheirSource() {
        val out = Flashcards.run(JSONObject().put("action", "add").put("deck", "NET103").put("cards", org.json.JSONArray()
            .put(JSONObject().put("front", "TCP is?").put("back", "Connection-oriented").put("source", "NET103.pdf p. 4"))
            .put(JSONObject().put("front", "UDP is?").put("back", "Connectionless"))))
        assertTrue(out, out.startsWith("Added 2 cards"))
        Flashcards.reset()
        assertEquals("NET103.pdf p. 4", Flashcards.load().single { it.front == "TCP is?" }.source)
        assertEquals("", Flashcards.load().single { it.front == "UDP is?" }.source)
        assertTrue(Flashcards.tool().toString().contains("source"))
    }

    @Test fun theModelsVerdictIsRead() {
        assertEquals(true, Flashcards.verdict("Right. Connection-oriented services confirm delivery."))
        assertEquals(true, Flashcards.verdict("**Correct**: same idea."))
        assertEquals(false, Flashcards.verdict("wrong - that's UDP, which is connectionless."))
        assertEquals(null, Flashcards.verdict("Hmm, partly."))
        assertEquals("that's UDP, which is connectionless.", Flashcards.verdictNote("wrong - that's UDP, which is connectionless."))
        val p = Flashcards.gradePrompt(Card("a", "D", "Which is more reliable?", "Connection-oriented"), "  tcp style ")
        assertTrue(p.contains("Expected answer: Connection-oriented") && p.contains("Student's answer: tcp style") && p.contains("right or wrong"))
    }

    @Test fun theReminderFiresAtSixAndOnlyWhenCardsWait() {
        val tz = java.util.TimeZone.getTimeZone("Africa/Lusaka")
        val noon = 1_800_000_000_000L.let { Flashcards.dayStart(it, tz) + 12 * 3_600_000L }
        assertEquals(Flashcards.dayStart(noon, tz) + 18 * 3_600_000L, Flashcards.nudgeAt(noon, tz = tz))
        val evening = noon + 7 * 3_600_000L
        assertEquals(Flashcards.dayStart(noon, tz) + day + 18 * 3_600_000L, Flashcards.nudgeAt(evening, tz = tz))
        assertNull(Flashcards.nudgeText(now))
        Flashcards.add("NET103", listOf("q" to "a", "q2" to "a2"))
        assertEquals("2 cards waiting in NET103", Flashcards.nudgeText(now))
        Flashcards.add("CIT102", listOf("q" to "a"))
        assertEquals("3 cards waiting in 2 decks", Flashcards.nudgeText(now))
    }

    @Test fun waitsReadShort() {
        assertEquals("now", Flashcards.until(0)); assertEquals("10 min", Flashcards.until(600_000))
        assertEquals("3 h", Flashcards.until(3 * 3_600_000L)); assertEquals("2 d", Flashcards.until(2 * day))
        assertEquals("2 mo", Flashcards.until(60 * day))
    }
}
