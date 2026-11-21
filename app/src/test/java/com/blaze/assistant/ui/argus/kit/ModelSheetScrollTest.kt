package com.blaze.assistant.ui.argus.kit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-17: scrolling the model list never drags the sheet. 21 Nov (owner: it closed mid-scroll, and closed "in bits"):
 * the list keeps every leftover, and only a pull or flick down that began at the top closes the sheet, once.
 */
class ModelSheetScrollTest {
    private val down = Offset(0f, 120f)

    @Test fun onlyAPullFromTheTopCloses() {
        assertTrue(ModelSheetScroll.closes(true, true, 200f, 0f, 200f))
        assertTrue(ModelSheetScroll.closes(true, true, 0f, 2000f, 200f))
        assertFalse(ModelSheetScroll.closes(false, true, 900f, 4000f, 200f))   // scrolled back up into the top: stays open
        assertFalse(ModelSheetScroll.closes(true, false, 900f, 4000f, 200f))
        assertFalse(ModelSheetScroll.closes(true, true, 100f, 500f, 200f))
    }

    @Test fun theListKeepsEveryLeftoverAndClosesOnce() = runBlocking {
        var top = false; var closed = 0
        val h = ModelSheetScroll.Hold({ top }, 200f) { closed++ }
        h.down(listAtTop = false)
        top = true
        repeat(5) { assertEquals(down, h.onPostScroll(Offset.Zero, down, NestedScrollSource.UserInput)) }
        assertEquals(Velocity(0f, 5000f), h.onPostFling(Velocity.Zero, Velocity(0f, 5000f)))
        assertEquals(Velocity.Zero, h.onPreFling(Velocity(0f, 5000f)))
        assertEquals(0, closed)
        h.down(listAtTop = true)
        h.onPostScroll(Offset.Zero, down, NestedScrollSource.UserInput); assertEquals(0, closed)
        h.onPostScroll(Offset.Zero, down, NestedScrollSource.UserInput); assertEquals(1, closed)
        h.onPostScroll(Offset.Zero, down, NestedScrollSource.UserInput); h.onPreFling(Velocity(0f, 5000f)); assertEquals(1, closed)
    }

    @Test fun aFlickDownFromTheTopCloses() = runBlocking {
        var closed = 0
        val h = ModelSheetScroll.Hold({ true }, 200f) { closed++ }
        h.down(listAtTop = true)
        assertEquals(Velocity(0f, 3000f), h.onPreFling(Velocity(0f, 3000f)))
        assertEquals(1, closed)
    }
}
