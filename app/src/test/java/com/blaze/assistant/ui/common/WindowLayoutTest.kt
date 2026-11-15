package com.blaze.assistant.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Landscape overhaul: the window rules (phone upright 393x851, phone on its side 851x393, tablet 1280x800). */
class WindowLayoutTest {
    private val upright = WindowLayout.Shape(393f, 851f)
    private val side = WindowLayout.Shape(851f, 393f)
    private val tablet = WindowLayout.Shape(1280f, 800f)

    @Test fun shapes() {
        assertTrue(upright.portraitPhone); assertFalse(upright.short); assertFalse(upright.landscape)
        assertTrue(side.short); assertTrue(side.landscape); assertFalse(side.portraitPhone)
        assertEquals(WindowLayout.Width.EXPANDED, side.width)
        assertEquals(WindowLayout.Width.EXPANDED, tablet.width); assertFalse(tablet.short)
        assertEquals(WindowLayout.Width.MEDIUM, WindowLayout.widthClass(700f))
        assertEquals(WindowLayout.Width.COMPACT, WindowLayout.widthClass(599f))
    }

    @Test fun gutterLeavesUprightPhonesAlone() {
        assertEquals(0f, WindowLayout.gutter(393f), 0.01f)
        assertEquals(12f, WindowLayout.gutter(393f, minDp = 12f), 0.01f)
        assertEquals(65.5f, WindowLayout.gutter(851f), 0.01f)
        assertEquals(280f, WindowLayout.gutter(1280f), 0.01f)
        assertEquals(720f, WindowLayout.contentWidth(1280f), 0.01f)
        assertEquals(393f, WindowLayout.contentWidth(393f), 0.01f)
    }

    @Test fun twoPaneOnlyWhenWideAndNotTall() {
        assertFalse(WindowLayout.twoPane(upright.widthDp, upright.heightDp))
        assertTrue(WindowLayout.twoPane(side.widthDp, side.heightDp))
        assertTrue(WindowLayout.twoPane(tablet.widthDp, tablet.heightDp))
        assertFalse(WindowLayout.twoPane(800f, 1280f))   // tablet upright: one pane
        assertEquals(360f, WindowLayout.listPaneWidth(1280f), 0.01f)
        assertEquals(315f, WindowLayout.listPaneWidth(700f), 0.01f)
    }

    @Test fun sideBySideHeroOnlyOnShortWideWindows() {
        assertTrue(WindowLayout.sideBySide(side.widthDp, side.heightDp))
        assertFalse(WindowLayout.sideBySide(upright.widthDp, upright.heightDp))
        assertFalse(WindowLayout.sideBySide(tablet.widthDp, tablet.heightDp))
        assertFalse(WindowLayout.sideBySide(500f, 400f))   // short but too narrow for two columns
    }

    @Test fun gridKeepsTileSize() {
        assertEquals(3, WindowLayout.gridColumns(3, 393f))
        assertEquals(6, WindowLayout.gridColumns(3, 851f))
        assertEquals(10, WindowLayout.gridColumns(4, 1280f))   // capped
        assertEquals(2, WindowLayout.gridColumns(2, 300f))     // never below the base
        assertEquals(1, WindowLayout.gridColumns(0, 900f))
    }

    @Test fun cardColumns() {
        assertEquals(2, WindowLayout.cardColumns(369f, 150f))
        assertEquals(4, WindowLayout.cardColumns(827f, 150f))
        assertEquals(1, WindowLayout.cardColumns(100f, 150f))
        assertEquals(4, WindowLayout.cardColumns(5000f, 150f))
    }

    @Test fun sheetsFitShortWindows() {
        // on its side: 393 tall, 24 dp status bar -> 361 for the sheet
        assertEquals(361f, WindowLayout.sheetMaxHeight(393f, 24f), 0.01f)
        assertEquals(160f, WindowLayout.sheetMaxHeight(150f, 24f), 0.01f)
        // the plan list keeps its 360 upright, shrinks to fit beside title + actions on its side
        assertEquals(360f, WindowLayout.sheetBodyMax(360f, 851f, 24f), 0.01f)
        assertEquals(221f, WindowLayout.sheetBodyMax(360f, 393f, 24f), 0.01f)
        assertEquals(120f, WindowLayout.sheetBodyMax(360f, 200f, 24f), 0.01f)
        assertTrue(WindowLayout.compactHeader(393f)); assertFalse(WindowLayout.compactHeader(851f))
        assertEquals(72f, WindowLayout.heroOrbDp(851f), 0.01f)
        assertTrue(WindowLayout.heroOrbDp(393f) < 72f)
    }
}
