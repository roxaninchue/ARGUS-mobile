package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/chat-flat-ui: the glitch frames are reproducible, bounded, and still with reduced motion. */
class GlitchMathTest {
    @Test fun stillFrameHasSplitOnly() {
        for (tick in 0 until 50) {
            val f = GlitchMath.frame(tick, 42, motion = false)
            assertEquals(GlitchMath.SPLIT_STILL_DP, f.splitDp, 0f)
            assertEquals(0f, f.flicker, 0f)
            assertTrue(f.sliceDp.all { it == 0f })
            assertEquals(GlitchMath.SLICES, f.sliceDp.size)
        }
    }

    @Test fun movingFramesAreBoundedAndReproducible() {
        var jumps = 0; var flickers = 0
        for (tick in 0 until GlitchMath.LOOP_TICKS) {
            val a = GlitchMath.frame(tick, 7, motion = true); val b = GlitchMath.frame(tick, 7, motion = true)
            assertEquals(a.splitDp, b.splitDp, 0f); assertTrue(a.sliceDp.contentEquals(b.sliceDp)); assertEquals(a.flicker, b.flicker, 0f)
            assertTrue(a.splitDp >= GlitchMath.SPLIT_DP * 0.75f - 1e-4f && a.splitDp <= GlitchMath.SPLIT_DP * 1.25f + 1e-4f)
            assertTrue(a.sliceDp.all { kotlin.math.abs(it) <= GlitchMath.JITTER_DP })
            assertTrue(a.flicker == 0f || a.flicker == GlitchMath.FLICKER_DIP)
            if (a.sliceDp.any { it != 0f }) jumps++
            if (a.flicker > 0f) flickers++
        }
        // it actually glitches, but mostly holds still
        assertTrue("jumps $jumps", jumps in 60..330)
        assertTrue("flickers $flickers", flickers in 5..80)
    }

    @Test fun unitStaysInRange() {
        for (a in -50..50) for (b in 0..50) { val u = GlitchMath.unit(a, b); assertTrue(u >= 0f && u < 1f) }
    }

    @Test fun ticksLoop() {
        assertEquals(0, GlitchMath.tickAt(0))
        assertEquals(GlitchMath.TICKS_PER_SECOND, GlitchMath.tickAt(1_000))
        assertTrue(GlitchMath.tickAt(10_000_000L) in 0 until GlitchMath.LOOP_TICKS)
    }

    /** Owner 2026-11-14: glitched words can't be read, and the motion is no faster than before (motion sickness). */
    @Test fun wordsAreBlurredAndMotionIsNotFaster() {
        assertTrue(GlitchMath.BLUR_DP >= 2.5f)
        assertTrue(GlitchMath.BLUR_TAPS_X % 2 == 1 && GlitchMath.BLUR_TAPS_Y % 2 == 1)
        assertTrue(GlitchMath.TICKS_PER_SECOND <= 11)
        assertTrue(GlitchMath.JUMP_RATE <= 0.30f && GlitchMath.FLICKER_RATE <= 0.07f)
    }
}
