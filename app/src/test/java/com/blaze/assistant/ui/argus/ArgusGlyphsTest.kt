package com.blaze.assistant.ui.argus

import com.blaze.assistant.ui.argus.icons.ArgusGlyphs
import com.blaze.assistant.ui.argus.icons.LucidePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArgusGlyphsTest {
    /** Owner 2026-11-14: the chat-history button is three lines, the top longest, then shorter, then shortest. */
    @Test fun menuTaperIsThreeLinesLongestOnTop() {
        val lines = ArgusGlyphs.PATHS.getValue(ArgusGlyphs.MENU_TAPER)
        assertEquals(3, lines.size)
        val re = Regex("""^M(\d+) (\d+)h(\d+)$""")
        val parsed = lines.map { re.matchEntire(it)!!.groupValues.drop(1).map(String::toInt) }
        assertEquals("all start at the same x", 1, parsed.map { it[0] }.toSet().size)
        assertTrue("top to bottom", parsed[0][1] < parsed[1][1] && parsed[1][1] < parsed[2][1])
        assertTrue("longest, shorter, shortest", parsed[0][2] > parsed[1][2] && parsed[1][2] > parsed[2][2])
        assertTrue("inside the 24 grid", parsed.all { it[0] + it[2] <= 24 })
        assertFalse("never shadows a Lucide icon", LucidePaths.ICONS.containsKey(ArgusGlyphs.MENU_TAPER))
    }

    @Test fun chatHistoryButtonUsesIt() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val src = File(root, "com/blaze/assistant/ui/team/ChatDrawer.kt").readText()
        assertTrue(src.contains("ArgusGlyphs.MENU_TAPER, \"Chat history\""))
        assertFalse(src.contains("\"rows-3\", \"Chat history\""))
    }
}
