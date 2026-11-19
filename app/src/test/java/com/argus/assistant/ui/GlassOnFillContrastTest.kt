package com.argus.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2026-11-18: text and icons on the glass look's fills meet WCAG AA (GlassContrast.inkFor). */
class GlassOnFillContrastTest {
    // Glass.blue / orange / cyan / raised / raised2 (ArgusGlass.kt) as ARGB.
    private val blue = 0xFFE6E6EA.toInt()
    private val orange = 0xFFBDBDC2.toInt()
    private val cyan = 0xFFD0D0D6.toInt()
    private val raised = 0x1AFFFFFF
    private val raised2 = 0x26FFFFFF
    private fun alpha(c: Int, a: Float) = (Math.round(a * 255) shl 24) or (c and 0xFFFFFF)

    @Test fun whiteOnTheAccentWasUnreadable() {
        // What the "Ask instead" chip drew before: light ink on the light accent.
        assertTrue(GlassContrast.ratio(GlassContrast.ON_DARK, blue) < 1.5)
    }

    @Test fun lightFillsGetDarkInkAndPassAA() {
        for (f in listOf(blue, orange, cyan, alpha(blue, 0.85f))) {
            assertEquals(GlassContrast.ON_FILL, GlassContrast.inkFor(f))
            val r = GlassContrast.ratio(GlassContrast.ON_FILL, GlassContrast.over(f))
            assertTrue("fill ${Integer.toHexString(f)} ratio $r", r >= 4.5)
        }
    }

    @Test fun darkAndSeeThroughFillsKeepLightInk() {
        for (f in listOf(raised, raised2, GlassContrast.GROUND, 0xFF141416.toInt(), 0x00000000)) {
            assertEquals(GlassContrast.ON_DARK, GlassContrast.inkFor(f))
            assertTrue(GlassContrast.ratio(GlassContrast.ON_DARK, GlassContrast.over(f)) >= 4.5)
        }
    }

    @Test fun luminanceAndCompositing() {
        assertEquals(0.0, GlassContrast.luminance(0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, GlassContrast.luminance(0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(21.0, GlassContrast.ratio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01)
        assertEquals(0xFF000000.toInt(), GlassContrast.over(0x00FFFFFF, 0xFF000000.toInt()))
    }

    /** The screens fixed here no longer hard-code white ink on an accent fill. */
    @Test fun fixedScreensUseTheFillsInk() {
        val root = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        fun src(p: String) = File(root, "app/src/main/java/com/argus/assistant/$p").readText()
        val ask = src("browser/PageAskSheet.kt")
        assertTrue(ask.contains("val ink = Glass.inkFor(fill)"))
        assertTrue(src("ui/ArgusGlass.kt").contains("else Glass.inkFor(bg)"))
        for (p in listOf("browser/PagePointerLayer.kt", "phone/MagicHandUi.kt", "browser/BrowserVaultUi.kt"))
            assertTrue(p, src(p).contains("Glass.inkFor("))
        val magic = src("phone/MagicHandUi.kt")
        assertTrue(!magic.contains("color = if (enabled) Color.White else Glass.weak"))
    }
}
