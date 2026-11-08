package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52.2 ONE look (ui/ArgusLook.kt): what stayed when the Living Themes went. Colour mixing, light / dark / auto, the
 * Look & feel accent presets (the ARGUS blues first), the long-press light / dark toggle, and that the file carries no
 * theme machinery any more. Bubble contrast is BubbleTonesTest's job.
 */
class ArgusLookTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    @Test fun mixIsPerChannelAndKeepsAlpha() {
        assertEquals(0xFF000000, LookLogic.mix(0xFF000000, 0xFFFFFFFF, 0f))
        assertEquals(0xFFFFFFFF, LookLogic.mix(0xFF000000, 0xFFFFFFFF, 1f))
        assertEquals(0xFF7F7F7F, LookLogic.mix(0xFF000000, 0xFFFFFFFF, 0.5f))
        // k is clamped
        assertEquals(LookLogic.mix(0xFF102030, 0xFF405060, 1f), LookLogic.mix(0xFF102030, 0xFF405060, 3f))
        assertEquals(0xFF102030, LookLogic.mix(0xFF102030, 0xFF405060, -1f))
        // alpha comes from the first colour
        assertEquals(0x80000000L, LookLogic.mix(0x80000000, 0xFFFFFFFF, 0f) and 0xFF000000)
        // each channel moves on its own
        val m = LookLogic.mix(0xFF000000, 0xFF0056FF, 0.5f)
        assertEquals(0L, (m shr 16) and 0xFF)
        assertEquals(0x2BL, (m shr 8) and 0xFF)
        assertEquals(0x7FL, m and 0xFF)
    }

    @Test fun lightDarkAuto() {
        assertFalse(LookLogic.darkFor("light", 23))
        assertTrue(LookLogic.darkFor("dark", 12))
        // Auto: dark from 19:30 to 05:00
        assertFalse(LookLogic.darkFor("auto", 12))
        assertFalse(LookLogic.darkFor("auto", 19, 29))
        assertTrue(LookLogic.darkFor("auto", 19, 30))
        assertTrue(LookLogic.darkFor("auto", 23, 59))
        assertTrue(LookLogic.darkFor("auto", 0))
        assertTrue(LookLogic.darkFor("auto", 4, 59))
        assertFalse(LookLogic.darkFor("auto", 5, 0))
        // anything else follows the system (dark-first)
        assertTrue(LookLogic.darkFor("system", 12))
        assertFalse(LookLogic.darkFor("system", 12, systemDark = false))
        assertTrue(LookLogic.darkFor("", 12))
        // an old Living Theme name left in the pref is just "system" now
        assertFalse(LookLogic.darkFor("aurora", 12, systemDark = false))
    }

    @Test fun accentPresetsAreTheArgusBluesFirst() {
        val ids = LookLogic.ACCENTS.map { it.first }
        assertEquals("", ids[0])                                    // "" = the ARGUS accent
        assertEquals("ARGUS", LookLogic.ACCENTS[0].second)
        assertEquals(0xFF0056FF, LookLogic.parseAccent(ids[1]))     // Argus Blue
        assertEquals(0xFF1286FF, LookLogic.parseAccent(ids[2]))     // Electric
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(LookLogic.ACCENTS.size, LookLogic.ACCENTS.map { it.second }.toSet().size)
        for (id in ids.drop(1)) {
            val a = LookLogic.parseAccent(id)
            assertTrue("$id parses", a != null)
            assertEquals("$id is opaque", 0xFFL, (a!! shr 24) and 0xFF)
        }
    }

    @Test fun parseAccentIsStrict() {
        assertEquals(0xFF0056FF, LookLogic.parseAccent("#FF0056FF"))
        assertEquals(0xFF0056FF, LookLogic.parseAccent("  ff0056ff "))
        assertNull(LookLogic.parseAccent(""))
        assertNull(LookLogic.parseAccent(null))
        assertNull(LookLogic.parseAccent("0056FF"))                 // needs alpha (8 digits)
        assertNull(LookLogic.parseAccent("FF0056FG"))
        assertNull(LookLogic.parseAccent("FF0056FF00"))
    }

    @Test fun longPressFlipsLightDark() {
        assertEquals("light" to "Light theme", LightDark.toggle(currentlyDark = true))
        assertEquals("dark" to "Dark theme", LightDark.toggle(currentlyDark = false))
        assertTrue(LightDark.MIN_MS in 300L..LightDark.MAX_MS)
    }

    @Test fun argusToneIsCobaltAndSteel() {
        assertEquals(0xFF3D7EFF, BubbleTones.ARGUS.mine)
        assertEquals(0xFF5A6070, BubbleTones.ARGUS.blaze)
        assertEquals(1.0, BubbleTones.ratio(0xFF0056FF, 0xFF0056FF), 1e-9)
        assertEquals(21.0, BubbleTones.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
        // a pale accent is deepened for light grounds, a deep one is left alone
        assertTrue(BubbleTones.luminance(BubbleTones.onLight(0xFFF8F8F8)) < BubbleTones.luminance(0xFFF8F8F8))
        assertEquals(0xFF0056FF, BubbleTones.onLight(0xFF0056FF))
    }

    @Test fun appSizeSnapsToItsSteps() {
        assertEquals(1.00f, LookLogic.parseScale(null), 0.001f)
        assertEquals(1.00f, LookLogic.parseScale(""), 0.001f)
        assertEquals(1.00f, LookLogic.parseScale("abc"), 0.001f)
        assertEquals(0.90f, LookLogic.parseScale("0.9"), 0.001f)
        assertEquals(0.80f, LookLogic.parseScale("0.2"), 0.001f)   // below the range: the smallest step
        assertEquals(1.20f, LookLogic.parseScale("3"), 0.001f)     // above it: the largest
        assertEquals(0.85f, LookLogic.snapScale(0.86f), 0.001f)
        assertEquals(4, LookLogic.scaleIndex(1f))
        assertEquals("Default", LookLogic.scaleLabel(1f))
        assertEquals("90%", LookLogic.scaleLabel(0.9f))
        assertEquals("120%", LookLogic.scaleLabel(1.2f))
        // smaller steps first, so the slider runs small to large
        assertEquals(LookLogic.SCALES.sorted(), LookLogic.SCALES)
    }

    @Test fun theLookFileHasNoThemeMachinery() {
        val t = src("ui/ArgusLook.kt")
        for (gone in listOf("LivingThemes", "ThemeScenes", "WeatherLook", "Look.theme", "look_theme\")", "screenGround", "ThemePicker"))
            assertFalse("ArgusLook.kt still mentions $gone", gone in t.substringAfter("*/"))
        // the accent is the only live Look & feel state, and it reads / writes its own pref
        assertTrue("Settings.setStr(\"look_accent\"" in t)
        assertFalse("ui/LookLogic.kt is merged into ArgusLook.kt", java.io.File(root, "com/blaze/assistant/ui/LookLogic.kt").exists())
    }
}
