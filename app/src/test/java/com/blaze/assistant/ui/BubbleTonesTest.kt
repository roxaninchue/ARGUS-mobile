package com.blaze.assistant.ui


import com.blaze.assistant.BrandName
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52.2: one ARGUS look (the Living Themes and their twelve bubble tone sets are gone). The user's bubble takes the
 * accent (ARGUS cobalt by default, or the one picked in Look & feel), ARGUS's a quiet steel blue, and every text in or on a
 * bubble reads at WCAG AA (4.5:1) in light and dark over black, white, the page and the ARGUS Home's own grounds.
 */
class BubbleTonesTest {
    private val modes = listOf(false, true)
    /** null = the ARGUS accent; then every Look & feel preset, then a near-white one (the hardest case in dark). */
    private val accents: List<Long?> = listOf<Long?>(null) + LookLogic.ACCENTS.mapNotNull { LookLogic.parseAccent(it.first) } + 0xFFF8F8F8

    /** Everything that can sit behind a bubble now: the page extremes plus obsidian, cobalt night, Argus Blue, Electric, Steel. */
    private fun backdrops(c: BlazeColors): List<Color> = listOf(Color.Black, Color.White, c.paper.copy(alpha = 1f),
        Color(0xFF000000), Color(0xFF06103D), Color(0xFF0056FF), Color(0xFF1286FF), Color(0xFF9AA0B3), Color(0xFF5AA9FF), Color(0xFF0A1020))

    @Test fun everyPaletteDefinesBubbleColours() {
        for (dark in modes) for (a in accents) {
            val c = blazePalette(dark, a)
            assertTrue("dark=$dark accent=$a: no user bubble colour", c.bubbleMe != Color.Unspecified)
            assertTrue("dark=$dark accent=$a: no ${BrandName.NAME} bubble colour", c.bubbleBlaze != Color.Unspecified)
            assertEquals("opaque base", 1f, c.bubbleMe.alpha, 0f)
        }
    }

    @Test fun theAccentTintsTheUserBubbleOnly() {
        for (dark in modes) {
            val fills = accents.map { BubblePalette.fill(blazePalette(dark, it), true) }
            assertEquals("dark=$dark: two accents share a user bubble", fills.size, fills.toSet().size)
            val blaze = accents.map { BubblePalette.fill(blazePalette(dark, it), false) }.toSet()
            assertEquals("dark=$dark: ${BrandName.NAME}'s bubble never follows the accent", 1, blaze.size)
        }
        assertTrue(blazePalette(true).bubbleMe != blazePalette(false).bubbleMe)
    }

    @Test fun everyPaletteReadsAtAA_lightAndDark() {
        var checked = 0
        for (dark in modes) for (acc in accents) {
            val c = blazePalette(dark, acc)
            for (mine in listOf(true, false)) {
                val a = BubblePalette.fill(c, mine).alpha
                assertTrue("dark=$dark accent=$acc mine=$mine alpha $a", a >= BubblePalette.FROST && a < 1f)
                for (g in backdrops(c)) {
                    val fill = Contrast.over(BubblePalette.fill(c, mine), g)
                    val text = Contrast.ratio(Contrast.over(BubblePalette.text(c, mine), fill), fill)
                    val meta = Contrast.ratio(Contrast.over(BubblePalette.meta(c, mine), fill), fill)
                    assertTrue("dark=$dark accent=$acc mine=$mine on $g text $text", text >= Contrast.AA_TEXT)
                    assertTrue("dark=$dark accent=$acc mine=$mine on $g time $meta", meta >= Contrast.AA_TEXT)
                    checked += 2
                }
            }
        }
        assertTrue(checked > 500)
    }

    @Test fun aPaleCustomAccentStillReads() {
        // A near-white Look & feel accent in dark mode: the tone is mixed in less until the text reads.
        val c = blazePalette(true, 0xFFF8F8F8)
        val solid = Contrast.over(c.bubbleMe, Color.Black)
        assertTrue(Contrast.ratio(c.ink, solid) >= BubbleTones.SOLID_MIN)
        for (g in listOf(Color.Black, Color.White, c.paper.copy(alpha = 1f))) {
            val fill = Contrast.over(BubblePalette.fill(c, true), g)
            assertTrue(Contrast.ratio(Contrast.over(BubblePalette.text(c, true), fill), fill) >= Contrast.AA_TEXT)
        }
    }
}
