package com.blaze.assistant.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * 0.54.2 (owner, 15 Nov 2026: "Instead of showing thinking I want the thought to stream in"). The chat showed only the word
 * "Thinking" because a brain's thinking reached the screen only once it had WON the race, and a brain wins with its first
 * answer word, which comes after the thinking. Now the first brain that thinks streams it; a winner clears a loser's.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HedgeThoughtTest {
    private val lim = HedgeRace.Limits(firstMs = 3_000, hedgeMs = 3_000, stallMs = 20_000, thinkMs = 60_000, wholeMs = 60_000)

    @Test fun thinkingStreamsBeforeTheFirstAnswerWord() = runTest {
        val seen = mutableListOf<String>()
        val o = HedgeRace.run(listOf("one"), lim, true, call = { a ->
            for (i in 1..3) { a.think(); if (a.thoughtShows()) seen += "t$i"; delay(500) }
            assertTrue(a.visible()); "answer"
        }, now = { testScheduler.currentTime })
        assertEquals("answer", o.value)
        assertEquals(listOf("t1", "t2", "t3"), seen)
    }

    @Test fun onlyOneBrainThinksOnScreenAndTheWinnerTakesItOver() {
        val winner = AtomicReference<HedgeRace.Attempt<String>?>(null)
        val thinker = AtomicReference<HedgeRace.Attempt<String>?>(null)
        val now = { 0L }
        val a = HedgeRace.Attempt("a", 0, 0L, now, winner, thinker = thinker)
        val b = HedgeRace.Attempt("b", 1, 0L, now, winner, thinker = thinker)
        assertTrue(a.thoughtShows())
        assertFalse("a second brain thinking in parallel stays hidden", b.thoughtShows())
        assertTrue(b.visible())                              // b answers first: it wins
        assertTrue("b clears a's thought once", b.takesOverThought())
        assertFalse(b.takesOverThought())
        assertTrue(b.thoughtShows()); assertFalse(a.thoughtShows())
        assertFalse(a.shows())
    }

    @Test fun aDroppedThinkerHandsTheLaneOn() {
        val winner = AtomicReference<HedgeRace.Attempt<String>?>(null)
        val thinker = AtomicReference<HedgeRace.Attempt<String>?>(null)
        val a = HedgeRace.Attempt("a", 0, 0L, { 0L }, winner, thinker = thinker)
        val b = HedgeRace.Attempt("b", 1, 0L, { 0L }, winner, thinker = thinker)
        assertTrue(a.thoughtShows())
        a.dropped = true
        assertFalse(a.thoughtShows())
        assertTrue(b.thoughtShows())
        assertFalse("its own thought: nothing to clear", b.visible().let { b.takesOverThought() })
    }

    @Test fun llmClientGatesThinkingOnTheThinkingLaneNotOnTheWinner() {
        val f = listOf(java.io.File("src/main/java/com/blaze/assistant/agent/LlmClient.kt"), java.io.File("app/src/main/java/com/blaze/assistant/agent/LlmClient.kt")).first { it.exists() }.readText()
        assertTrue(f.contains("if (a.thoughtShows()) f(p)"))
        assertFalse(f.contains("a.think(); if (a.shows()) f(p)"))
        assertTrue(f.contains("if (a.takesOverThought()) chatThink?.invoke(\"\")"))
        assertTrue(f.contains("StreamDelta.of(delta)"))
    }
}
