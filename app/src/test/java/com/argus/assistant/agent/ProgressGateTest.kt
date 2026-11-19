package com.argus.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.52.1: interim progress messages, written by the model from real results, at most one per milestone. */
class ProgressGateTest {
    @Test fun milestonesFireOnceEach() {
        assertFalse(ProgressGate.due(steps = 5, seconds = 10, fired = 0))   // too early
        assertTrue(ProgressGate.due(steps = 5, seconds = 41, fired = 0))
        val fired = ProgressGate.reached(5)
        assertEquals(1, fired)
        assertFalse(ProgressGate.due(steps = 11, seconds = 90, fired = fired))
        assertTrue(ProgressGate.due(steps = 12, seconds = 90, fired = fired))
        assertFalse(ProgressGate.due(steps = 2, seconds = 300, fired = 0))
    }

    @Test fun firstUpdateNeedsRealWork() {
        assertNotNull(ProgressGate.refuse(null, 0, steps = 1, finished = 0, live = 1, text = "Looking into it"))
        assertNull(ProgressGate.refuse(null, 0, steps = 6, finished = 0, live = 1, text = "Found three strong sources on the battery tests, checking prices next."))
        assertNotNull(ProgressGate.refuse(null, 0, steps = 6, finished = 0, live = 0, text = "x"))   // nothing working
        assertNotNull(ProgressGate.refuse(null, 0, steps = 6, finished = 0, live = 1, text = " "))
        assertNotNull(ProgressGate.refuse(null, 0, steps = 6, finished = 0, live = 1, text = "x".repeat(400)))
    }

    @Test fun oneUpdatePerMilestone() {
        val last = ProgressGate.Posted(at = 0, steps = 6, finished = 0, text = "Found three strong sources on the battery tests, checking prices next.")
        assertNotNull(ProgressGate.refuse(last, 10_000, 20, 0, 1, "Prices are in for two of them."))       // within the gap
        assertNotNull(ProgressGate.refuse(last, 60_000, 8, 0, 1, "Prices are in for two of them."))        // nothing new
        assertNull(ProgressGate.refuse(last, 60_000, 12, 0, 1, "Prices are in for two of them, merging the report now."))
        assertNull(ProgressGate.refuse(last, 60_000, 7, 1, 1, "The price agent is done; the review agent is still reading."))  // an agent finished
        assertNotNull(ProgressGate.refuse(last, 60_000, 12, 0, 1, "Found three strong sources on the battery tests, checking prices next!"))  // repeat
    }

    @Test fun skipRepliesAreRecognised() {
        listOf("SKIP", "skip.", " NO_UPDATE ", "`SKIP`").forEach { assertTrue(it, ProgressGate.skip(it)) }
        assertFalse(ProgressGate.skip("Skip the intro, here is what I found"))
    }

    @Test fun wakeTextCarriesRealFindingsAndNoTemplate() {
        val t = ProgressGate.wakeText("B3", "Budget phones", 12, 95, listOf("Searching the web", "Reading gsmarena.com"), listOf("Pixel 9a: 5,100 mAh, 8 h SOT"))
        assertTrue(t.contains("Pixel 9a"))
        assertTrue(t.contains("post_update"))
        assertTrue(t.contains(ProgressGate.SKIP))
        assertTrue(t.contains("own words"))
    }
}
