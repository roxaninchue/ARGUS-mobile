package com.blaze.assistant.ui.argus.kit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** 2026-11-17: scrolling the model list never drags the sheet, unless the drag began with the list at the top. */
class ModelSheetScrollTest {
    @Test fun leftoverStaysWithTheListUnlessTheDragBeganAtTheTop() {
        val down = Offset(0f, 120f)
        // scrolled down, then scrolled back up into the top: the sheet stays
        assertEquals(down, ModelSheetScroll.leftoverForSheet(false, NestedScrollSource.UserInput, down))
        // a fling that hits the top never moves the sheet
        assertEquals(down, ModelSheetScroll.leftoverForSheet(true, NestedScrollSource.SideEffect, down))
        assertEquals(down, ModelSheetScroll.leftoverForSheet(false, NestedScrollSource.SideEffect, down))
        // at the top already, a new pull down goes to the sheet (closes it)
        assertEquals(Offset.Zero, ModelSheetScroll.leftoverForSheet(true, NestedScrollSource.UserInput, down))
    }
}
