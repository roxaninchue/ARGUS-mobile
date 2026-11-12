package com.blaze.assistant.ui.argus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-SH-01: the ARGUS tokens (04 §2). The contrast matrix is the ticket's "3 grounds × glass levels" check: every text
 * token at 4.5:1 and every status dot at 3:1, in both modes, on every ground and glass level.
 */
class ArgusTokensTest {
    private val modes = listOf(ArgusTokens.Dark, ArgusTokens.Light)

    @Test fun contrastMatrixPassesOnEveryGroundAndLevel() {
        val fails = mutableListOf<String>()
        for (p in modes) for (g in Ground.entries) for (l in GlassLevel.entries) {
            val s = ArgusTokens.surfaces(p, g, l)
            for ((n, c) in ArgusTokens.textTokens(p)) ArgusContrast.worst(c, s).let { if (it < ArgusContrast.AA_TEXT) fails += "${if (p.dark) "dark" else "light"} $g $l $n ${"%.2f".format(it)}" }
            for ((n, c) in ArgusTokens.uiTokens(p)) ArgusContrast.worst(c, s).let { if (it < ArgusContrast.AA_UI) fails += "${if (p.dark) "dark" else "light"} $g $l $n ${"%.2f".format(it)}" }
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun inkOnPrimaryFillsReads() {
        for (p in modes) assertTrue(ArgusContrast.ratio(p.onInk, p.ink) >= 7.0)   // send, user bubble, .btn.p
    }

    @Test fun theOriginalATeamValuesFailWhereTheAaVariantsSaySo() {
        // why the variants exist: A-team's dark mute on raised glass, light success text on the page
        val raised = ArgusTokens.surfaces(ArgusTokens.Dark, Ground.PLAIN, GlassLevel.RAISED)
        assertTrue(ArgusContrast.worst(0xFF9B9BA3, raised) < 4.5)
        assertTrue(ArgusContrast.worst(0xFF0E7A4E, ArgusTokens.surfaces(ArgusTokens.Light, Ground.PLAIN, GlassLevel.FLAT)) < 4.5)
        // and 04's 60 % Orbit veil isn't enough for flat rows over the Earth's limb
        val p = ArgusTokens.Dark
        val at60 = listOf(ArgusTokens.ORBIT_LO, ArgusTokens.ORBIT_HI).map { ArgusContrast.over(ArgusTokens.a(0x000000, 0.60f), ArgusContrast.grey(it)) }
        assertTrue(ArgusContrast.worst(p.mute, at60) < 4.5)
    }

    @Test fun dimIsDecorativeOnly() {
        // dim never carries text: it is in neither contrast list, and meta (timestamps) is the AA one
        for (p in modes) {
            assertFalse(p.dim in ArgusTokens.textTokens(p).values)
            assertTrue(ArgusContrast.ratio(p.meta, p.bg) >= 4.5)
        }
    }

    @Test fun groundsAreOpaqueAndDistinct() {
        for (p in modes) {
            val plain = ArgusTokens.groundSamples(p, Ground.PLAIN)
            assertEquals(4, plain.size)
            for (g in Ground.entries) assertTrue(ArgusTokens.groundSamples(p, g).all { ArgusContrast.alpha(it) == 1.0 })
            assertTrue(ArgusTokens.groundSamples(p, Ground.COBALT_NIGHT).first() != p.bg)
        }
        assertEquals(Ground.PLAIN, Ground.of(null)); assertEquals(Ground.ORBIT, Ground.of(" orbit ")); assertEquals(Ground.COBALT_NIGHT, Ground.of("COBALT_NIGHT"))
        assertEquals(Ground.PLAIN, Ground.of("living"))
        assertTrue(ArgusTokens.orbitVeil(true) in 0.6f..1f && ArgusTokens.orbitVeil(false) in 0.6f..1f)
    }

    @Test fun valuesMatchTheDesignDoc() {
        val d = ArgusTokens.Dark; val l = ArgusTokens.Light
        assertEquals(0xFF0B0B0DL, d.bg); assertEquals(0xFFEEF0F4L, l.bg)
        assertEquals(0xFFF2F2F4L, d.ink); assertEquals(0xFF111114L, l.ink)
        assertEquals(0xFF64646CL, l.mute)
        assertEquals(0x0EFFFFFFL, d.glassFill)          // white 5.5 %
        assertEquals(0x9EFFFFFFL, l.glassFill)          // white 62 %
        assertEquals(0x940E0E12L, d.chrome)             // rgba(14,14,18,.58)
        assertEquals(0xFF3466FFL, d.brandCobalt); assertEquals(0xFF0D28FFL, l.brandCobalt)
        assertEquals(8, ArgusTokens.BOT_COLORS.size); assertEquals(10, ArgusTokens.DOMAIN_COLORS.size); assertEquals(11, ArgusTokens.BH_COLOR_MAP.size)
        assertEquals(29, ArgusTokens.Radius.COMPOSER); assertEquals(20, ArgusTokens.Radius.CARD); assertEquals(16, ArgusTokens.Space.PAGE)
        assertEquals(68, ArgusTokens.Space.CHIPS_OFFSET)
        assertEquals(19, ArgusTypeScale.all.size)
        assertEquals(16f, ArgusTypeScale.field.size, 0f)   // inputs stay 16 sp
        assertTrue(ArgusTypeScale.meta.tabular && ArgusTypeScale.section.upper)
    }

    @Test fun typeScaleIsOrdered() {
        val t = ArgusTypeScale
        val bySize = listOf(t.display, t.serifHello, t.title1, t.title2, t.topTitle, t.rowTitle, t.body, t.preview, t.meta, t.section, t.pill)
        assertEquals(bySize.sortedByDescending { it.size }, bySize)
        for ((n, s) in t.all) assertTrue(n, s.line >= s.size && s.weight in 100..900)
    }

    @Test fun botColoursParse() {
        assertEquals(0xFF8B5CF6L, ArgusTokens.botColor("violet"))
        assertEquals(0xFF8B5CF6L, ArgusTokens.botColor("#8b5cf6"))
        assertEquals(0xFFF93498L, ArgusTokens.botColor("magenta"))   // BH name
        assertEquals(0xFFCBD5E1L, ArgusTokens.botColor("black"))     // BH black → silver orb
        assertNull(ArgusTokens.botColor("#12345")); assertNull(ArgusTokens.botColor("teal")); assertNull(ArgusTokens.botColor(null))
    }

    @Test fun materialRolesReadInBothModes() {
        for (p in modes) {
            val m = ArgusTokens.m3(p)
            for ((fg, bg) in ArgusTokens.M3_TEXT_PAIRS) {
                val r = ArgusContrast.ratio(m.getValue(fg), m.getValue(bg))
                assertTrue("${if (p.dark) "dark" else "light"} $fg on $bg = $r", r >= 4.5)
            }
            assertTrue(m.values.all { ArgusContrast.alpha(it) == 1.0 || it == p.scrim })   // M3 roles are opaque
        }
    }

    @Test fun glassBlursOnlyFixedLayersOnCapablePhones() {
        assertEquals(ArgusGlassRule.Mode.BLUR, ArgusGlassRule.mode(GlassLevel.CHROME, 31, lowRam = false))
        assertEquals(ArgusGlassRule.Mode.TINT, ArgusGlassRule.mode(GlassLevel.CHROME, 30, lowRam = false))
        assertEquals(ArgusGlassRule.Mode.TINT, ArgusGlassRule.mode(GlassLevel.SHEET, 34, lowRam = true))
        assertEquals(ArgusGlassRule.Mode.TINT, ArgusGlassRule.mode(GlassLevel.SHEET, 34, lowRam = false, thermalBlur = false))
        for (l in listOf(GlassLevel.FLAT, GlassLevel.SURFACE, GlassLevel.RAISED)) assertEquals(ArgusGlassRule.Mode.NONE, ArgusGlassRule.mode(l, 34, false))
        assertEquals(24, ArgusGlassRule.blurDp(GlassLevel.CHROME)); assertEquals(30, ArgusGlassRule.blurDp(GlassLevel.SHEET))
        // the fallback tint is the token at 92 % (never lower than the token's own alpha)
        val t = ArgusGlassRule.tint(ArgusTokens.Dark, GlassLevel.CHROME, ArgusGlassRule.Mode.TINT)!!
        assertEquals(0.92, ArgusContrast.alpha(t), 0.005)
        assertEquals(ArgusTokens.Dark.chrome, ArgusGlassRule.tint(ArgusTokens.Dark, GlassLevel.CHROME, ArgusGlassRule.Mode.BLUR))
        assertEquals(0.94, ArgusContrast.alpha(ArgusGlassRule.tint(ArgusTokens.Light, GlassLevel.SHEET, ArgusGlassRule.Mode.TINT)!!), 0.005)
    }

    @Test fun reducedMotionCapsEveryDuration() {
        assertEquals(100, ArgusTokens.Motion.duration(ArgusTokens.Motion.SHEET_IN_MS, reduced = true))
        assertEquals(200, ArgusTokens.Motion.duration(ArgusTokens.Motion.SHEET_IN_MS, reduced = false))
        assertEquals(80, ArgusTokens.Motion.duration(80, reduced = true))
    }

    /** P1-SH-01 turned it on (06: "`argus_look` default on"); it was off through P0. */
    @Test fun theLookIsOnByDefault() {
        assertTrue(ArgusLookFlag.DEFAULT)
        assertEquals("argus_look", ArgusLookFlag.KEY)
    }

    @Test fun contrastMathMatchesWcag() {
        assertEquals(21.0, ArgusContrast.ratio(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, ArgusContrast.ratio(0xFF777777, 0xFF777777), 0.001)
        assertEquals(0xFF808080L, ArgusContrast.over(0x80FFFFFF, 0xFF000000))
    }
}
