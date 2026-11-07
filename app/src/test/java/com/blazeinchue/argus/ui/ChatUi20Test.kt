package com.blazeinchue.argus.ui


import com.blazeinchue.argus.BrandName
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar

/**
 * 0.45.20 chat UI (spec A, B, C), the pure half (runs on every JVM; the rendered half is ChatUi20ShotsTest). Strict 5/5.
 * Contrast: 3 themes x light / dark, every text in or on a bubble at >= 4.5:1 over the darkest and brightest thing that
 * can be behind it (pure black, pure white, the page, the theme's ground and particle colours).
 */
class ChatUi20Test {
    private fun five(f: () -> Unit) = repeat(5) { f() }

    /** 0.52.2: one ARGUS look; the accents under test: ARGUS (none picked), Electric and Amber from Look & feel. */
    private val themes: List<Long?> = listOf(null, 0xFF1286FF, 0xFFF59E0B)

    /** The palette ArgusTheme builds for accent [id] in [dark] (same rule as Theme.kt). */
    private fun palette(id: Long?, dark: Boolean): ArgusColors {
        return argusPalette(dark, id)
    }

    /** Pure black, pure white, the page, and the ARGUS grounds (obsidian, cobalt night, Argus Blue, Electric, Steel). */
    private fun backdrops(id: Long?, c: ArgusColors): List<Color> {
        return listOf(Color.Black, Color.White, c.paper.copy(alpha = 1f), Color(0xFF06103D), Color(0xFF0056FF), Color(0xFF1286FF), Color(0xFF9AA0B3)) +
            (id?.let { listOf(Color(it)) } ?: emptyList())
    }

    @Test fun contrast_everyText_3themes_lightAndDark() = five {
        var checked = 0
        for (id in themes) for (dark in listOf(false, true)) {
            val c = palette(id, dark)
            for (g in backdrops(id, c)) {
                for (mine in listOf(true, false)) {
                    val fill = Contrast.over(BubblePalette.fill(c, mine), g)
                    val text = Contrast.ratio(Contrast.over(BubblePalette.text(c, mine), fill), fill)
                    val meta = Contrast.ratio(Contrast.over(BubblePalette.meta(c, mine), fill), fill)
                    assertTrue("$id dark=$dark mine=$mine on $g text $text", text >= 4.5)
                    assertTrue("$id dark=$dark mine=$mine on $g time $meta", meta >= 4.5)
                    checked += 2
                }
                val cap = Contrast.over(BubblePalette.capsule(c), g)
                val capInk = Contrast.ratio(Contrast.over(BubblePalette.capsuleInk(c), cap), cap)
                assertTrue("$id dark=$dark capsule (delivery / day chip / step pill) $capInk", capInk >= 4.5)
                checked++
            }
        }
        assertTrue(checked > 100)
    }

    @Test fun bubbles_areSemiOpaque_notSolid_andThemeAware() = five {
        for (id in themes) for (dark in listOf(false, true)) {
            val c = palette(id, dark)
            for (mine in listOf(true, false)) {
                val a = BubblePalette.fill(c, mine).alpha
                assertTrue("$id $dark $mine alpha $a", a >= BubblePalette.FROST && a < 1f)
            }
        }
        // 0.52.2: your bubble takes the accent; ARGUS's keeps its own steel tone; never WhatsApp green.
        val electric = palette(0xFF1286FF, true); val amber = palette(0xFFF59E0B, true)
        assertTrue(BubblePalette.fill(electric, true) != BubblePalette.fill(amber, true))
        assertTrue(BubblePalette.fill(electric, false) == BubblePalette.fill(amber, false))
        // Argus's own greys and the theme accent, never WhatsApp's green.
        for (id in themes) for (dark in listOf(false, true)) for (mine in listOf(true, false)) {
            val f = BubblePalette.fill(palette(id, dark), mine)
            assertTrue("$id $dark $mine greenish", !(f.green > f.red + 0.08f && f.green > f.blue + 0.08f))
        }
    }

    @Test fun grouping_tailOnFirstOfARun_tighterSpacing() = five {
        val p = Bubbles.positions(listOf(Side.ME, Side.ME, Side.ME, Side.ARGUS, Side.ARGUS, Side.ME), listOf(1, 2, 3, 4, 5, 6))
        assertEquals(listOf(GroupPos.FIRST, GroupPos.MIDDLE, GroupPos.LAST, GroupPos.FIRST, GroupPos.LAST, GroupPos.SINGLE), p)
        assertEquals(listOf(true, false, false, true, false, true), p.map { it.starts })   // tail
        assertTrue(Bubbles.gapAbove(GroupPos.MIDDLE, false) < Bubbles.gapAbove(GroupPos.FIRST, false))
        assertEquals(0.80f, BubblePalette.MAX_SHARE)
    }

    @Test fun dayChips_todayYesterday() = five {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 23, 11, 0) }.timeInMillis
        val y = now - 24 * 3600_000L
        val old = Calendar.getInstance().apply { set(2025, Calendar.MARCH, 2, 9, 0) }.timeInMillis
        assertEquals("Today", BubbleDays.label(now, now))
        assertEquals("Yesterday", BubbleDays.label(y, now))
        assertEquals("2 Mar 2025", BubbleDays.label(old, now, java.util.Locale.UK))
        assertTrue(BubbleDays.startsDay(0, now))
        assertTrue(BubbleDays.startsDay(y, now))
        assertTrue(!BubbleDays.startsDay(now - 60_000, now))
        assertTrue(!BubbleDays.startsDay(now, 0))
        assertEquals(5, BubbleDays.time(now).length)
    }

    @Test fun chatScreen_hooks() = five {
        val src = listOf(File("src/main/java/com/blazeinchue/argus/ui/ChatScreen.kt"), File("app/src/main/java/com/blazeinchue/argus/ui/ChatScreen.kt")).first { it.exists() }.readText()
        assertTrue("delivery only under the latest user bubble", src.contains("delivery = if (item.m.id == lastUserId)"))
        assertTrue("action row only on the latest ${BrandName.NAME} reply", src.contains("showActs = item.m.id == lastArgusId && mood == Mood.IDLE"))
        assertTrue(src.contains("StepPillRow(") && src.contains("DayChip(") && src.contains("BubbleMenu("))
        // No ticks anywhere in the label.
        val label = listOf(File("src/main/java/com/blazeinchue/argus/ui/DeliveryStatus.kt"), File("app/src/main/java/com/blazeinchue/argus/ui/DeliveryStatus.kt")).first { it.exists() }.readText()
        assertTrue(!label.contains("\u2713") && !label.contains("Icons.") && label.contains("LiveRegionMode.Polite"))
    }
}
