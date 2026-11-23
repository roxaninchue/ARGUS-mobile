package com.blaze.assistant.ui.argus.kit

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finn 2026-11-23 (model picker): a scroll sometimes didn't register. The sheet hold consumed every pointer move, and
 * Compose's drag detectors drop a gesture when a move is consumed before touch slop, so slow drags lost the list.
 * The hold must share the touch like a nested scrollable: a no-op vertical draggable plus a post-scroll catch.
 */
class SheetScrollTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    private fun holdSheet(): String {
        val s = src("ui/argus/kit/KitOverlays.kt")
        val start = s.indexOf("private fun Modifier.holdSheet()")
        assertTrue("holdSheet exists", start >= 0)
        return s.substring(start)
    }

    @Test fun theHoldNeverConsumesRawMoves() {
        val hold = holdSheet()
        assertFalse("no raw pointer loop in the hold", hold.contains("pointerInput"))
        assertFalse("no consumed moves before slop", hold.contains(".consume()"))
        assertFalse(hold.contains("positionChange"))
    }

    @Test fun theHoldIsANestedDraggable() {
        val hold = holdSheet()
        assertTrue(hold.contains(".draggable(NoDrag, androidx.compose.foundation.gestures.Orientation.Vertical)"))
        assertTrue("one shared state, not one per recomposition", hold.contains("private val NoDrag = androidx.compose.foundation.gestures.DraggableState { }"))
        assertTrue(hold.contains(".nestedScroll(SheetHold)"))
        assertTrue("list ends never move the sheet", hold.contains("onPostScroll") && hold.contains("onPostFling"))
    }

    @Test fun onlyNoDragSheetsAreHeld() {
        val s = src("ui/argus/kit/KitOverlays.kt")
        assertTrue(s.contains("if (dragToClose) Modifier else Modifier.holdSheet()"))
        assertTrue(src("ui/argus/kit/ModelPicker.kt").contains("dragToClose = false"))
    }
}
