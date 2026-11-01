package com.blaze.assistant.phone

import com.blaze.assistant.ui.EdgeParticleMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.50.0: the edge particles show while Blaze acts on the screen, linger briefly, never stick, and stay on the edges. */
class ScreenWorkTest {
    @Test fun onWhileAStepRunsThenLingersThenOff() {
        val c = ScreenWork.Clock(linger = 1_000, max = 60_000)
        assertFalse(c.visible(0))
        val a = c.begin(100)
        assertTrue(c.visible(500))
        val b = c.begin(600)            // overlapping steps (parallel calls)
        c.end(a, 700); assertTrue(c.visible(800))
        c.end(b, 900); assertTrue(c.visible(1_800))   // linger: one steady glow between steps
        assertFalse(c.visible(1_950))
        assertEquals(0, c.openCount())
    }

    @Test fun aStepThatNeverEndsIsDropped() {
        val c = ScreenWork.Clock(linger = 1_000, max = 60_000)
        c.begin(0)
        assertTrue(c.visible(59_000))
        assertFalse(c.visible(61_500))
    }

    @Test fun onlyScreenToolsLightIt() {
        listOf("tap", "type_text", "scroll", "run_plan", "open_app", "phone_task").forEach { assertTrue(it, ScreenWork.isScreenTool(it)) }
        listOf("web_search", "memory_save", "get_weather", "read_screen").forEach { assertFalse(it, ScreenWork.isScreenTool(it)) }
    }

    @Test fun dotsStayInTheEdgeBand() {
        val w = 1080f; val h = 2400f
        for (i in 0..400) {
            val o = EdgeParticleMath.at(i / 400f * 3.3f - 1f, (i % 11) / 10f, w, h)
            assertTrue("$o", o.x in 0f..w && o.y in 0f..h)
            assertTrue("$o", EdgeParticleMath.inBand(o, w, h))
        }
        assertTrue(EdgeParticleMath.MAX_PASS_THROUGH_ALPHA <= 0.8f)   // Android 12+ untrusted-touch limit: taps pass through
    }
}
