package com.argus.assistant.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The native sketch's animation pass: spin / bob / pulse, the fallback when three.js can't draw. */
class PartsSketchTest {
    @Test fun animationMovesTheGeometry() {
        fun part(kind: String, amp: Double) = JSONObject().put("id", "p").put("shape", "box")
            .put("size", JSONArray(listOf(100, 20, 10)))
            .put("animation", JSONObject().put("kind", kind).put("speed", 1.0).put("amplitude", amp))
        fun spec(vararg extra: JSONObject) = JSONObject().put("scale", 0.001).put("parts", JSONArray(extra.toList()))
        fun width(fs: List<PartsSketch.Face>) = fs.flatMap { it.pts }.let { pts -> pts.maxOf { it.first } - pts.minOf { it.first } }

        val spin = spec(part("spin", 0.0))
        assertNotEquals("spin turns the part", PartsSketch.faces(spin, 200f, 200f), PartsSketch.faces(spin, 200f, 200f, time = 1.0))

        val bob = spec(part("bob", 0.5))
        val y0 = PartsSketch.faces(bob, 200f, 200f).flatMap { it.pts }.map { it.second }.average()
        val y1 = PartsSketch.faces(bob, 200f, 200f, time = 0.25).flatMap { it.pts }.map { it.second }.average()
        assertTrue("bob lifts the part", kotlin.math.abs(y1 - y0) > 1.0)

        val pulse = spec(part("pulse", 1.0))
        assertTrue("pulse grows it", width(PartsSketch.faces(pulse, 200f, 200f, time = 0.25)) > width(PartsSketch.faces(pulse, 200f, 200f)))

        val still = spec(JSONObject().put("id", "p").put("shape", "box").put("size", JSONArray(listOf(100, 20, 10))))
        assertEquals("no block, no motion", PartsSketch.faces(still, 200f, 200f), PartsSketch.faces(still, 200f, 200f, time = 3.0))
        assertFalse(PartsSketch.hasAnimation(still))
        assertTrue(PartsSketch.hasAnimation(spin))
    }

    @Test fun speedFactorIsPauseToFullSpeed() {
        assertTrue("1x stays 1x", kotlin.math.abs(PartsSketch.speedFactor(1f) - 1f) < 1e-6f)
        assertTrue("slow is kept", kotlin.math.abs(PartsSketch.speedFactor(0.35f) - 0.35f) < 1e-6f)
        assertTrue("0 is paused", PartsSketch.speedFactor(0f) == 0f)
        assertTrue("never faster than real time", PartsSketch.speedFactor(4f) == 1f)
        assertTrue("a broken value means normal speed", PartsSketch.speedFactor(Float.NaN) == 1f)
    }
}
