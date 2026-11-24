package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.55.14: a call row only takes a result from its own time (phone 24 Nov: two older calendar reads under a new step). */
class ToolTrailPickTest {
    private fun nameOf(e: String) = e.substringBefore('(')
    private fun r(exact: String, at: Long) = ToolTrail.Result(exact, "out", false, at)

    @Test fun olderSameArgsResultIsNotThisCall() {
        val res = listOf(r("calendar_events(days=2)", 1_000), r("calendar_events(days=2)", 60_000), r("calendar_events(days=2)", 200_000))
        val used = BooleanArray(res.size)
        assertEquals(2, ToolTrail.pick("calendar_events(days=2)", "calendar_events", 199_500, res, used, ::nameOf))
        assertNull(ToolTrail.pick("calendar_events(days=2)", "calendar_events", 199_500, res, used, ::nameOf))   // the old ones stay unmatched
    }

    @Test fun exactBeatsSameTool_andUnknownTimeKeepsTheOldMatch() {
        val res = listOf(r("web_search(query=a)", 10), r("web_search(query=b)", 11))
        assertEquals(1, ToolTrail.pick("web_search(query=b)", "web_search", 5, res, BooleanArray(2), ::nameOf))
        assertEquals(0, ToolTrail.pick("web_search(query=z)", "web_search", 0, res, BooleanArray(2), ::nameOf))
    }
}
