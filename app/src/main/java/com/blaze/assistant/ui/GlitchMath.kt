package com.blaze.assistant.ui

/**
 * fix/chat-flat-ui: the numbers behind one frame of the glitch effect ([GlitchText]), so both the shader path (API 33+)
 * and the pure-Compose fallback move the same way. Pure and deterministic (GlitchMathTest): the same tick and seed always
 * give the same frame, and a still frame ([motion] = false) has the colour split only.
 */
object GlitchMath {
    /** Animation ticks per second (the effect steps, it doesn't glide). */
    const val TICKS_PER_SECOND = 11
    /** Ticks in one loop of the infinite transition. */
    const val LOOP_TICKS = 440
    /** Horizontal slices a glitched line is cut into. */
    const val SLICES = 3
    /** Colour split, in dp: the moving look and the still (reduced-motion) look. */
    const val SPLIT_DP = 1.6f
    const val SPLIT_STILL_DP = 1.2f
    /** The furthest a slice jumps sideways, in dp. */
    const val JITTER_DP = 3.5f
    /** Share of ticks with a slice out of line, and with a flicker. */
    const val JUMP_RATE = 0.30f
    const val FLICKER_RATE = 0.07f
    /** How far the text fades in a flicker (0 = none, 1 = gone). */
    const val FLICKER_DIP = 0.55f

    class Frame(
        /** Colour split in dp (red one way, cyan the other). */
        val splitDp: Float,
        /** Sideways shift of each slice, in dp. */
        val sliceDp: FloatArray,
        /** 0 = solid, up to [FLICKER_DIP] = faded this tick. */
        val flicker: Float,
    )

    /** A number in [0, 1) from two ints (a small integer hash; no java.util.Random, so frames are reproducible). */
    fun unit(a: Int, b: Int): Float {
        var h = a * 0x27d4eb2d + b * 0x165667b1
        h = h xor (h ushr 15); h *= 0x2c1b3c6d; h = h xor (h ushr 12); h *= 0x297a2d39; h = h xor (h ushr 15)
        return (h ushr 8) / 16_777_216f
    }

    /** The frame for [tick] of the span with [seed]. Still ([motion] false): split only, every tick the same. */
    fun frame(tick: Int, seed: Int, motion: Boolean): Frame {
        if (!motion) return Frame(SPLIT_STILL_DP, FloatArray(SLICES), 0f)
        val split = SPLIT_DP * (0.75f + 0.5f * unit(seed, tick * 3 + 1))
        val slices = FloatArray(SLICES) { i ->
            if (unit(seed + i * 31, tick) < JUMP_RATE) (unit(seed + i * 17, tick + 7) * 2f - 1f) * JITTER_DP else 0f
        }
        val flicker = if (unit(seed xor 0x5bd1, tick) < FLICKER_RATE) FLICKER_DIP else 0f
        return Frame(split, slices, flicker)
    }

    /** The tick at [ms] since the effect started. */
    fun tickAt(ms: Long): Int = ((ms * TICKS_PER_SECOND) / 1000L % LOOP_TICKS).toInt()
}
