package com.blaze.assistant.ui.argus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-SH-02: the bot orb's geometry and states (A-team botSVG numbers, 04 §2.7). */
class OrbGeometryTest {
    @Test fun sixFacesWithATeamTilts() {
        assertEquals(listOf("ORBIT", "TILT", "STEEP", "PURE", "TWIN", "POLAR"), OrbFace.entries.map { it.name })
        assertEquals(listOf(-16f, 14f, -34f, 0f, -20f, 62f), OrbFace.entries.map { it.tiltDeg })
        assertEquals(OrbFace.ORBIT, OrbFace.of(6)); assertEquals(OrbFace.POLAR, OrbFace.of(-1))
    }

    @Test fun ringsPerFace() {
        assertTrue(OrbGeometry.rings(OrbFace.PURE).isEmpty())
        assertEquals(2, OrbGeometry.rings(OrbFace.TWIN).size)
        for (f in OrbFace.entries - OrbFace.PURE - OrbFace.TWIN) assertEquals(listOf(OrbRing(30f, 8f, 2f, 0.95f)), OrbGeometry.rings(f))
        // every ring stays inside the halo
        for (f in OrbFace.entries) for (r in OrbGeometry.rings(f)) assertTrue(r.rx + r.width / 2 <= OrbGeometry.HALO_R + 0.01f)
    }

    @Test fun faceFromNameMatchesATeam() {
        // A-team: [...name].reduce((h, ch) => h + ch.charCodeAt(0), 0) % 6
        assertEquals(OrbFace.of("Nova".sumOf { it.code }), OrbFace.forName("Nova"))
        assertEquals(OrbFace.of(('N'.code + 'o'.code + 'v'.code + 'a'.code) % 6), OrbFace.forName("Nova"))
        assertEquals(OrbFace.ORBIT, OrbFace.forName(null))
    }

    @Test fun coloursMixLikeMixHex() {
        val c = OrbGeometry.colors(0xFF8B5CF6)
        assertEquals(0xFF8B5CF6L, c.body)
        assertEquals(OrbGeometry.mix(0xFF8B5CF6, 0xFFFFFF, 0.7f), c.hi)
        assertEquals(0xFFFFFFFFL, OrbGeometry.mix(0xFF000000, 0xFFFFFF, 1f))
        assertEquals(0xFF808080L, OrbGeometry.mix(0xFF000000, 0xFFFFFF, 0.5f))
        assertTrue(ArgusContrast.luminance(c.hi) > ArgusContrast.luminance(c.body) && ArgusContrast.luminance(c.lo) < ArgusContrast.luminance(c.body))
        assertTrue(OrbGeometry.isLight(0xFFCBD5E1)); assertFalse(OrbGeometry.isLight(0xFF8B5CF6))
    }

    @Test fun statesMoveOnlyWhenTheyShould() {
        val f = OrbFace.ORBIT
        assertEquals(f.tiltDeg, OrbGeometry.ringAngle(f, OrbState.IDLE, 4000), 0f)
        assertEquals(f.tiltDeg + 180f, OrbGeometry.ringAngle(f, OrbState.WORKING, 4000), 0.01f)       // 8 s per turn
        assertEquals(f.tiltDeg, OrbGeometry.ringAngle(f, OrbState.WORKING, 4000, reducedMotion = true), 0f)
        assertEquals(1.08f, OrbGeometry.bodyScale(OrbState.TALKING, 1f), 0.0001f)
        assertEquals(1.08f, OrbGeometry.bodyScale(OrbState.TALKING, 3f), 0.0001f)
        assertEquals(1f, OrbGeometry.bodyScale(OrbState.IDLE, 1f), 0f)
        val away = OrbGeometry.stateColor(0xFFEF4444, OrbState.AWAY)
        fun sat(c: Long): Int { val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF; return (maxOf(r, g, b) - minOf(r, g, b)).toInt() }
        assertTrue(sat(away) < sat(0xFFEF4444))
        assertEquals(0xFFEF4444L, OrbGeometry.stateColor(0xFFEF4444, OrbState.WORKING))
    }

    @Test fun statusDots() {
        val p = ArgusTokens.Dark
        assertEquals(p.success, OrbGeometry.dotColor(OrbState.WORKING, p))
        assertEquals(p.waiting, OrbGeometry.dotColor(OrbState.WAITING, p))
        assertNull(OrbGeometry.dotColor(OrbState.IDLE, p)); assertNull(OrbGeometry.dotColor(OrbState.AWAY, p))
        assertEquals(8f, OrbGeometry.dotDp(OrbSize.MD), 0f); assertEquals(4.5f, OrbGeometry.dotDp(OrbSize.XS), 0f)
    }

    @Test fun onlyTheManagerIsCobalt() {
        for (p in listOf(ArgusTokens.Dark, ArgusTokens.Light)) {
            assertEquals(p.brandCobalt, OrbGeometry.managerColor(p))
            assertFalse(p.brandCobalt in ArgusTokens.BOT_COLORS.values)
            assertFalse(p.brandCobalt in ArgusTokens.DOMAIN_COLORS.values)
        }
    }

    @Test fun sizes() {
        assertEquals(listOf(18f, 34f, 50f, 72f, 84f, 120f), OrbSize.ALL)
    }
}
