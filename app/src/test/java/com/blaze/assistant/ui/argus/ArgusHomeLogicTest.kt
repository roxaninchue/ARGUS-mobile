package com.blaze.assistant.ui.argus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.math.abs

/**
 * 0.52.2 the ARGUS living 3D Home, pure part (ui/argus/ArgusHomeLogic.kt + ArgusMesh): the orb's state machine and looks,
 * the greeting and its clock, the renderer / frame-rate policy (30 fps on <= 4.5 GB or battery saver, Canvas on tiny
 * phones or without GLES3, static with reduce motion), the notch's pull and spring, the tilt parallax, the result card,
 * the procedural meshes and the test tags.
 */
class ArgusHomeLogicTest {

    // ---------------------------------------------------------------- scene: states and looks

    @Test fun voiceWinsOverWork() {
        assertEquals(ArgusScene.State.IDLE, ArgusScene.stateOf(listening = false, speaking = false, working = false))
        assertEquals(ArgusScene.State.THINKING, ArgusScene.stateOf(listening = false, speaking = false, working = true))
        assertEquals(ArgusScene.State.SPEAKING, ArgusScene.stateOf(listening = false, speaking = true, working = true))
        assertEquals(ArgusScene.State.LISTENING, ArgusScene.stateOf(listening = true, speaking = true, working = true))
        assertEquals(ArgusScene.State.LISTENING, ArgusScene.stateOf(listening = true, speaking = false, working = false))
    }

    @Test fun idleIsCalmAndEveryOtherStateIsLivelier() {
        val idle = ArgusScene.look(ArgusScene.State.IDLE)
        assertEquals(ArgusScene.IDLE_RING_SPEED, idle.ringSpeed, 0f)
        // about one turn in 50 s: calm, never distracting
        assertEquals(50.0, 2 * Math.PI / idle.ringSpeed, 1.0)
        for (s in ArgusScene.State.values().filter { it != ArgusScene.State.IDLE }) {
            val l = ArgusScene.look(s)
            assertTrue("$s rings turn faster than idle", l.ringSpeed > idle.ringSpeed)
            assertTrue("$s glows more than idle", l.glow > idle.glow)
            assertTrue("$s breathes faster than idle", l.breathHz > idle.breathHz)
        }
        // thinking spins harder than speaking or quiet listening; speaking breathes fastest (the orb "talks")
        val all = ArgusScene.State.values().associateWith { ArgusScene.look(it) }
        assertTrue(all.getValue(ArgusScene.State.THINKING).ringSpeed > all.getValue(ArgusScene.State.SPEAKING).ringSpeed)
        assertTrue(all.getValue(ArgusScene.State.THINKING).ringSpeed > all.getValue(ArgusScene.State.LISTENING).ringSpeed)
        assertEquals(ArgusScene.State.SPEAKING, all.maxByOrNull { it.value.breathHz }!!.key)
    }

    @Test fun everyLookStaysInRange() {
        for (s in ArgusScene.State.values()) for (lvl in listOf(-1f, 0f, 0.3f, 1f, 7f, Float.NaN)) {
            val l = ArgusScene.look(s, lvl)
            assertTrue("$s/$lvl glow ${l.glow}", l.glow in 0f..1f)
            assertTrue("$s/$lvl streak ${l.streak}", l.streak in 0f..1f)
            assertTrue("$s/$lvl breath amp ${l.breathAmp}", l.breathAmp > 0f && l.breathAmp < 0.1f)
            assertTrue("$s/$lvl ring speed ${l.ringSpeed}", l.ringSpeed > 0f && l.ringSpeed < 1f)
            assertFalse(l.ringSpeed.isNaN() || l.ripple.isNaN() || l.breathHz.isNaN())
        }
    }

    @Test fun listeningFollowsTheMicLevel() {
        val quiet = ArgusScene.look(ArgusScene.State.LISTENING, 0f)
        val loud = ArgusScene.look(ArgusScene.State.LISTENING, 1f)
        assertTrue(loud.ringSpeed > quiet.ringSpeed)
        assertTrue(loud.breathAmp > quiet.breathAmp)
        assertTrue(loud.glow > quiet.glow)
        assertTrue(loud.ripple > quiet.ripple)
        // out-of-range levels are clamped, NaN counts as silence
        assertEquals(loud, ArgusScene.look(ArgusScene.State.LISTENING, 5f))
        assertEquals(quiet, ArgusScene.look(ArgusScene.State.LISTENING, Float.NaN))
        assertEquals(quiet, ArgusScene.look(ArgusScene.State.LISTENING, -3f))
        // the level only matters while listening
        assertEquals(ArgusScene.look(ArgusScene.State.THINKING), ArgusScene.look(ArgusScene.State.THINKING, 1f))
    }

    @Test fun stateChangesEaseAndNeverJump() {
        assertEquals(0f, ArgusScene.approach(0f, 1f, 0f), 0f)
        assertEquals(1f, ArgusScene.approach(0f, 1f, 0.016f, tau = 0f), 0f)
        val one = ArgusScene.approach(0f, 1f, 1f / 60f)
        assertTrue("one frame moves a little ($one)", one > 0f && one < 0.1f)
        // frame-rate independent: 60 small steps land where 30 double steps do
        var a = 0f; repeat(60) { a = ArgusScene.approach(a, 1f, 1f / 60f) }
        var b = 0f; repeat(30) { b = ArgusScene.approach(b, 1f, 1f / 30f) }
        assertEquals(a, b, 1e-3f)
        assertTrue("after 1 s it is nearly there ($a)", a > 0.9f && a <= 1f)
        // the Look version moves every field toward the target
        val from = ArgusScene.look(ArgusScene.State.IDLE); val to = ArgusScene.look(ArgusScene.State.THINKING)
        val mid = ArgusScene.approach(from, to, 0.1f)
        assertTrue(mid.ringSpeed > from.ringSpeed && mid.ringSpeed < to.ringSpeed)
        assertTrue(mid.glow > from.glow && mid.glow < to.glow)
        assertEquals(to, ArgusScene.approach(from, to, 0.1f, tau = 0f))
    }

    @Test fun theOrbBreathesAroundOne() {
        val l = ArgusScene.look(ArgusScene.State.SPEAKING)
        assertEquals(1f, ArgusScene.breath(0f, l), 1e-6f)
        var lo = 2f; var hi = 0f
        for (i in 0..1000) { val b = ArgusScene.breath(i / 250f, l); lo = minOf(lo, b); hi = maxOf(hi, b) }
        assertEquals(1f + l.breathAmp, hi, 1e-3f)
        assertEquals(1f - l.breathAmp, lo, 1e-3f)
    }

    @Test fun ringsFitAPortraitScreenAndTurnBothWays() {
        assertEquals(3, ArgusScene.RINGS.size)
        assertTrue(ArgusScene.RINGS.any { it.speed > 0f } && ArgusScene.RINGS.any { it.speed < 0f })
        assertTrue(ArgusScene.RINGS.all { it.radius > 1f && it.width > 0f && it.width < 0.2f })
        assertEquals(ArgusScene.RINGS.maxOf { it.radius } + 0.2f, ArgusScene.REACH, 1e-6f)
        assertTrue(ArgusScene.REACH < 2.2f)
        val r = ArgusScene.RINGS[1]
        assertEquals(2f * r.speed, ArgusScene.ringAngle(r, 2f), 1e-6f)
        // the nearly flat orbit is a thin ellipse; a hoop near 90 degrees is nearly round; never a line
        assertTrue(ArgusScene.squash(12f) < 0.25f)
        assertTrue(ArgusScene.squash(70f) > 0.9f)
        assertEquals(0.08f, ArgusScene.squash(0f), 1e-6f)
        assertEquals(1f, ArgusScene.squash(90f), 1e-6f)
    }

    // ---------------------------------------------------------------- greeting and its clock

    @Test fun greetingFollowsTheHour() {
        for (h in 5..11) assertEquals("Good morning.", ArgusGreeting.hello(h))
        for (h in 12..16) assertEquals("Good afternoon.", ArgusGreeting.hello(h))
        for (h in 17..23) assertEquals("Good evening.", ArgusGreeting.hello(h))
        for (h in 0..4) assertEquals("Still up?", ArgusGreeting.hello(h))
        // any int wraps onto the clock
        assertEquals(ArgusGreeting.hello(1), ArgusGreeting.hello(25))
        assertEquals(ArgusGreeting.hello(23), ArgusGreeting.hello(-1))
    }

    @Test fun greetingUsesOnlyAFirstName() {
        assertEquals("Good evening, Gaby.", ArgusGreeting.hello(19, "Gaby"))
        assertEquals("Good morning, Ada.", ArgusGreeting.hello(8, "  Ada Lovelace "))
        assertEquals("Still up, Gaby?", ArgusGreeting.hello(2, "Gaby"))
        assertEquals("Good afternoon.", ArgusGreeting.hello(13, null))
        assertEquals("Good afternoon.", ArgusGreeting.hello(13, "   "))
        // never an e-mail address, never an absurdly long "name"
        assertEquals("Good afternoon.", ArgusGreeting.hello(13, "gaby@example.com"))
        assertEquals("Good afternoon.", ArgusGreeting.hello(13, "x".repeat(40)))
    }

    @Test fun greetingHourComesFromTheClockInTheLocalZone() {
        val utc = TimeZone.getTimeZone("UTC")
        val lusaka = TimeZone.getTimeZone("Africa/Lusaka")          // UTC+2, no DST
        val kolkata = TimeZone.getTimeZone("Asia/Kolkata")          // UTC+5:30
        val t = 1_762_419_600_000L                                 // 2025-11-06 09:00:00 UTC
        assertEquals(9, ArgusGreeting.hourOf(t, utc))
        assertEquals(11, ArgusGreeting.hourOf(t, lusaka))
        assertEquals(14, ArgusGreeting.hourOf(t, kolkata))
        assertEquals("Good morning.", ArgusGreeting.hello(ArgusGreeting.hourOf(t, lusaka)))
        assertEquals("Good afternoon.", ArgusGreeting.hello(ArgusGreeting.hourOf(t, kolkata)))
        assertTrue(ArgusGreeting.hourNow() in 0..23)
    }

    @Test fun theGreetingIsReReadAtTheNextFullHour() {
        val utc = TimeZone.getTimeZone("UTC")
        val kolkata = TimeZone.getTimeZone("Asia/Kolkata")
        val t = 1_762_419_600_000L                                 // 09:00:00 UTC exactly
        assertEquals(ArgusGreeting.HOUR_MS, ArgusGreeting.msToNextHour(t, utc))
        assertEquals(15 * 60_000L, ArgusGreeting.msToNextHour(t + 45 * 60_000L, utc))
        // a half-hour zone: 14:30 local -> 30 minutes to 15:00
        assertEquals(30 * 60_000L, ArgusGreeting.msToNextHour(t, kolkata))
        // never a busy loop
        assertEquals(1_000L, ArgusGreeting.msToNextHour(t + ArgusGreeting.HOUR_MS - 10L, utc))
        for (z in listOf(utc, kolkata, TimeZone.getTimeZone("America/New_York"))) for (k in 0 until 50) {
            val now = t + k * 1_234_567L
            val wait = ArgusGreeting.msToNextHour(now, z)
            assertTrue(wait in 1_000L..ArgusGreeting.HOUR_MS)
            // waiting that long lands on a new hour (or within the 1 s floor of it)
            assertTrue(ArgusGreeting.hourOf(now + wait, z) != ArgusGreeting.hourOf(now, z) || wait == 1_000L)
        }
    }

    @Test fun askLineAndRotatingHint() {
        val asks = ArgusScene.State.values().map { ArgusGreeting.ask(it) }
        assertEquals(asks.size, asks.toSet().size)
        assertTrue(ArgusGreeting.TRY.size >= 4)
        for (i in ArgusGreeting.TRY.indices) assertTrue(ArgusGreeting.hint(i).startsWith("Try saying \u201c" + ArgusGreeting.TRY[i]))
        assertEquals(ArgusGreeting.hint(0), ArgusGreeting.hint(ArgusGreeting.TRY.size))
        assertEquals(ArgusGreeting.hint(ArgusGreeting.TRY.size - 1), ArgusGreeting.hint(-1))
        assertTrue(ArgusGreeting.HINT_MS >= 3_000L)
    }

    // ---------------------------------------------------------------- renderer and frame rate

    @Test fun sixtyFpsExceptLowRamOrBatterySaver() {
        assertEquals(60, ArgusFps.cap(8_192, lowRamFlag = false, powerSave = false))
        assertEquals(60, ArgusFps.cap(6_144, lowRamFlag = false, powerSave = false))
        // the 3-4 GB phones (Galaxy A07 class) and anything at or under 4.5 GB: 30
        assertEquals(30, ArgusFps.cap(4_608, lowRamFlag = false, powerSave = false))
        assertEquals(30, ArgusFps.cap(3_800, lowRamFlag = false, powerSave = false))
        assertEquals(60, ArgusFps.cap(4_609, lowRamFlag = false, powerSave = false))
        assertEquals(30, ArgusFps.cap(8_192, lowRamFlag = false, powerSave = true))
        assertEquals(30, ArgusFps.cap(8_192, lowRamFlag = true, powerSave = false))
        // RAM unknown (0) is not "low"
        assertEquals(60, ArgusFps.cap(0, lowRamFlag = false, powerSave = false))
    }

    @Test fun rendererPicksGlCanvasOrStatic() {
        assertEquals(ArgusFps.Mode.GL, ArgusFps.mode(gles3 = true, totalRamMb = 4_000, lowRamFlag = false, motionOn = true))
        assertEquals(ArgusFps.Mode.CANVAS, ArgusFps.mode(gles3 = false, totalRamMb = 8_000, lowRamFlag = false, motionOn = true))
        assertEquals(ArgusFps.Mode.CANVAS, ArgusFps.mode(gles3 = true, totalRamMb = 2_048, lowRamFlag = false, motionOn = true))
        assertEquals(ArgusFps.Mode.CANVAS, ArgusFps.mode(gles3 = true, totalRamMb = 8_000, lowRamFlag = true, motionOn = true))
        assertEquals(ArgusFps.Mode.GL, ArgusFps.mode(gles3 = true, totalRamMb = 2_560, lowRamFlag = false, motionOn = true))
        // reduce motion wins over everything: one still frame
        assertEquals(ArgusFps.Mode.STATIC, ArgusFps.mode(gles3 = true, totalRamMb = 8_000, lowRamFlag = false, motionOn = false))
        assertEquals(ArgusFps.Mode.STATIC, ArgusFps.mode(gles3 = false, totalRamMb = 1_000, lowRamFlag = true, motionOn = false))
    }

    @Test fun glesVersionAndFrameGate() {
        assertTrue(ArgusFps.gles3(0x00030000))
        assertTrue(ArgusFps.gles3(0x00030002))
        assertFalse(ArgusFps.gles3(0x00020000))
        assertFalse(ArgusFps.gles3(0))
        assertEquals(16L, ArgusFps.frameMs(60))
        assertEquals(33L, ArgusFps.frameMs(30))
        assertEquals(1000L, ArgusFps.frameMs(0))
        assertTrue(ArgusFps.due(1_000, 0, 60))
        assertFalse(ArgusFps.due(1_010, 1_000, 60))
        assertTrue(ArgusFps.due(1_014, 1_000, 60))                // 2 ms vsync slack
        assertFalse(ArgusFps.due(1_020, 1_000, 30))
        assertTrue(ArgusFps.due(1_031, 1_000, 30))
    }

    @Test fun nothingAnimatesUnseen() {
        assertTrue(ArgusFps.animate(ArgusFps.Mode.GL, resumed = true, visible = true))
        assertTrue(ArgusFps.animate(ArgusFps.Mode.CANVAS, resumed = true, visible = true))
        assertFalse(ArgusFps.animate(ArgusFps.Mode.GL, resumed = false, visible = true))
        assertFalse(ArgusFps.animate(ArgusFps.Mode.GL, resumed = true, visible = false))
        assertFalse(ArgusFps.animate(ArgusFps.Mode.STATIC, resumed = true, visible = true))
    }

    // ---------------------------------------------------------------- the pull-down notch

    @Test fun pullProgressWithRubberBand() {
        assertEquals(0f, ArgusPull.progress(-50f, 1000f), 0f)
        assertEquals(0f, ArgusPull.progress(100f, 0f), 0f)
        assertEquals(0.5f, ArgusPull.progress(500f, 1000f), 1e-6f)
        assertEquals(1f, ArgusPull.progress(1000f, 1000f), 1e-6f)
        val past = ArgusPull.progress(1500f, 1000f)
        assertTrue("past the end it overshoots a little ($past)", past > 1f && past < 1f + ArgusPull.OVERSHOOT)
        assertTrue(ArgusPull.progress(100_000f, 1000f) <= 1f + ArgusPull.OVERSHOOT)
        // monotonic all the way
        var prev = -1f
        for (d in 0..3000 step 50) { val p = ArgusPull.progress(d.toFloat(), 1000f); assertTrue(p >= prev); prev = p }
    }

    @Test fun releaseSettlesByPositionAndFling() {
        assertFalse(ArgusPull.settleOpen(0.2f, 0f, wasOpen = false))
        assertTrue(ArgusPull.settleOpen(0.4f, 0f, wasOpen = false))
        assertTrue(ArgusPull.settleOpen(0.05f, ArgusPull.FLING_DP_S + 1f, wasOpen = false))
        assertFalse(ArgusPull.settleOpen(0.95f, -ArgusPull.FLING_DP_S - 1f, wasOpen = true))
        // closing from open needs a real pull up (a third of the way)
        assertTrue(ArgusPull.settleOpen(0.8f, 0f, wasOpen = true))
        assertFalse(ArgusPull.settleOpen(0.6f, 0f, wasOpen = true))
    }

    @Test fun theSpringSettlesWithoutRunningAway() {
        for ((from, to) in listOf(0f to 1f, 1f to 0f, 1.1f to 1f)) {
            val path = ArgusPull.simulate(from, to)
            assertTrue("$from -> $to settles before the step limit (${path.size})", path.size < 600)
            assertEquals(to, path.last(), 0.01f)
            // stiffness 380 / damping 0.86: a small overshoot at most
            val over = path.maxOf { abs(it - to) * (if ((it - to) * (from - to) < 0) 1f else 0f) }
            assertTrue("$from -> $to overshoot $over", over < 0.05f)
            assertTrue("$from -> $to takes under a second (${path.size} frames)", path.size <= 60)
        }
        // a huge dt is clamped (a dropped frame never explodes the spring)
        val (x, _) = ArgusPull.step(0f, 0f, 1f, 5f)
        assertTrue(x in 0f..1.5f)
        assertTrue(ArgusPull.settled(1f, 0f, 1f))
        assertFalse(ArgusPull.settled(0.9f, 0f, 1f))
    }

    @Test fun scrimFollowsTheSheet() {
        assertEquals(0f, ArgusPull.scrim(0f), 0f)
        assertEquals(0.55f, ArgusPull.scrim(1f), 1e-6f)
        assertEquals(0.55f, ArgusPull.scrim(1.1f), 1e-6f)
        assertEquals(0f, ArgusPull.scrim(-1f), 0f)
    }

    // ---------------------------------------------------------------- parallax

    @Test fun parallaxIsOptionalSmallAndSmoothed() {
        assertFalse(ArgusParallax.enabled(setting = false, motionOn = true, powerSave = false))
        assertTrue(ArgusParallax.enabled(setting = true, motionOn = true, powerSave = false))
        assertFalse(ArgusParallax.enabled(setting = true, motionOn = false, powerSave = false))
        assertFalse(ArgusParallax.enabled(setting = true, motionOn = true, powerSave = true))
        assertEquals(0f to 0f, ArgusParallax.offset(0f, 0f))
        assertEquals(1f to -1f, ArgusParallax.offset(pitch = -3f, roll = 3f))
        assertEquals(0f to 0f, ArgusParallax.offset(Float.NaN, Float.NaN))
        assertTrue(ArgusParallax.MAX_DP <= 12f)
        assertEquals(0.12f, ArgusParallax.smooth(0f, 1f), 1e-6f)
        assertEquals(1f, ArgusParallax.smooth(0f, 1f, k = 5f), 1e-6f)
    }

    // ---------------------------------------------------------------- the result card

    private fun m(id: Long, argus: Boolean, text: String) = ArgusAnswer.Msg(id, argus, text)

    @Test fun answerCardShowsTheNewestAskAndItsReply() {
        val msgs = listOf(m(1, false, "old ask"), m(2, true, "old reply"), m(5, false, "What's the news?"), m(6, true, "## Top\n- **One** thing\n- [Two](https://x.y) things"))
        assertNull("nothing asked since the Home came up", ArgusAnswer.card(6, msgs, busy = false))
        assertNull(ArgusAnswer.card(-1, msgs, busy = true))
        val c = ArgusAnswer.card(2, msgs, busy = false)
        assertNotNull(c); c!!
        assertEquals("What's the news?", c.ask)
        assertEquals("Top One thing Two things", c.text)
        assertFalse(c.working)
    }

    @Test fun answerCardWhileWorkingAndWithoutReply() {
        val msgs = listOf(m(10, false, "Plan my day"))
        val working = ArgusAnswer.card(9, msgs, busy = true)
        assertNotNull(working)
        assertEquals("", working!!.text)
        assertTrue(working.working)
        assertNull("no reply and nothing running: no card", ArgusAnswer.card(9, msgs, busy = false))
        // a reply to an older ask doesn't count for the new one
        val two = listOf(m(10, false, "a"), m(11, true, "reply to a"), m(12, false, "b"))
        assertEquals("", ArgusAnswer.card(9, two, busy = true)!!.text)
        assertEquals("b", ArgusAnswer.card(9, two, busy = true)!!.ask)
        // blank replies (tool-only turns) are skipped
        assertNull(ArgusAnswer.card(9, two + m(13, true, "   "), busy = false))
    }

    @Test fun plainTextIsCutAtAWord() {
        assertEquals("a b", ArgusAnswer.plain("a\n\n   b"))
        assertEquals("before after", ArgusAnswer.plain("before ```kotlin\nval x = 1\n``` after"))
        assertEquals("quote item", ArgusAnswer.plain("> quote\n1. item"))
        val long = (1..200).joinToString(" ") { "word$it" }
        val cut = ArgusAnswer.plain(long)
        assertTrue(cut.length <= ArgusAnswer.MAX_CHARS + 1)
        assertTrue(cut.endsWith("\u2026"))
        assertTrue("cut at a word: $cut", cut.removeSuffix("\u2026").split(' ').all { it.startsWith("word") && it.length > 4 })
        assertEquals(91, ArgusAnswer.plain("y".repeat(200), 90).length)    // one long word: hard cut + ellipsis
    }

    // ---------------------------------------------------------------- meshes (ArgusGl.kt)

    @Test fun sphereMeshIsClosedAndIndexedInRange() {
        val (v, idx) = ArgusMesh.sphere(24, 32)
        assertEquals(25 * 33 * 3, v.size)
        assertEquals(24 * 32 * 6, idx.size)
        val n = v.size / 3
        assertTrue("16-bit indices", n < Short.MAX_VALUE)
        assertTrue(idx.all { it in 0 until n })
        for (i in 0 until n) {
            val r = Math.sqrt((v[i * 3] * v[i * 3] + v[i * 3 + 1] * v[i * 3 + 1] + v[i * 3 + 2] * v[i * 3 + 2]).toDouble())
            assertEquals("vertex $i is on the unit sphere", 1.0, r, 1e-4)
        }
    }

    @Test fun bandMeshHasPositionNormalUv() {
        val seg = 96; val radius = 1.28f; val width = 0.1f
        val (v, idx) = ArgusMesh.band(seg, radius, width)
        assertEquals((seg + 1) * 2 * 8, v.size)
        assertEquals(seg * 6, idx.size)
        val n = v.size / 8
        assertTrue(idx.all { it in 0 until n })
        for (i in 0 until n) {
            val o = i * 8
            assertEquals(radius.toDouble(), Math.hypot(v[o].toDouble(), v[o + 2].toDouble()), 1e-4)
            assertEquals(width / 2, abs(v[o + 1]), 1e-6f)
            assertEquals(1.0, Math.hypot(v[o + 3].toDouble(), v[o + 5].toDouble()), 1e-4)
            assertEquals(0f, v[o + 4], 0f)
            assertTrue(v[o + 6] in 0f..1f && (v[o + 7] == 0f || v[o + 7] == 1f))
        }
    }

    // ---------------------------------------------------------------- test tags

    @Test fun testTagsAreUniqueAndNamespaced() {
        assertEquals(ArgusHomeTags.ALL.size, ArgusHomeTags.ALL.toSet().size)
        assertTrue(ArgusHomeTags.ALL.all { it.startsWith("argus_home") && it.matches(Regex("[a-z_]+")) })
        assertTrue(ArgusHomeTags.ALL.size >= 12)
    }
}
