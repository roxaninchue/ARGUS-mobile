package com.argus.assistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.em
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.54.2 (owner, 15 Nov 2026: "the text layout looks ugly"): chat replies' inline code chips and hanging list items. */
class MdChipsTest {
    private val md = "Take `[5, 2, 4]`:\n\ni=1, key=2\n- `arr[0]=5 > 2` shift 5 right, j becomes `-1`\n  - nested item\n3. third\nSo `j` ends."

    @Test fun codeIsTaggedWithPaddingAndNoLineBoxBackground() {
        val r = Md.render(md, Color.Blue, Color.Gray, rich = true)
        val runs = r.getStringAnnotations(MdChips.TAG, 0, r.length)
        assertEquals(4, runs.size)
        val first = r.text.substring(runs[0].start, runs[0].end)
        assertEquals(MdChips.PAD + "[5, 2, 4]" + MdChips.PAD, first)
        assertTrue("no span background in rich mode", r.spanStyles.none { it.item.background != Color.Unspecified })
        assertTrue("code a size smaller", r.spanStyles.any { it.item.fontSize == MdChips.CODE_EM.em && r.text.substring(it.start, it.end) == "j" })
        assertFalse(r.text.contains("`"))
    }

    @Test fun listItemsHangUnderTheirText() {
        val r = Md.render(md, Color.Blue, Color.Gray, rich = true)
        val paras = r.paragraphStyles
        assertEquals(3, paras.size)
        fun ind(i: Int) = paras[i].item.textIndent!!.let { assertTrue(it.firstLine.isEm && it.restLine.isEm); it.firstLine.value to it.restLine.value }
        assertEquals(0f, ind(0).first, 1e-4f); assertEquals(1.2f, ind(0).second, 1e-4f)   // "• " = 2 mono chars
        assertEquals(1.2f, ind(1).first, 1e-4f); assertEquals(2.4f, ind(1).second, 1e-4f) // nested one level
        assertEquals(0f, ind(2).first, 1e-4f); assertEquals(1.8f, ind(2).second, 1e-4f)   // "3. " = 3 mono chars
        assertTrue(r.text.substring(paras[0].start, paras[0].end).startsWith("\u2022 "))
        // no '\n' next to a list paragraph (a paragraph already breaks the line; one more would be a blank line)
        for (p in paras) {
            assertFalse(r.text.substring(p.start, p.end).contains('\n'))
            if (p.start > 0) assertTrue("no newline before", r.text[p.start - 1] != '\n')
            if (p.end < r.length) assertTrue("no newline after", r.text[p.end] != '\n')
        }
        assertTrue("paragraph text and its blank line are kept", r.text.startsWith("Take "))
        assertTrue(r.text.contains("i=1, key=2"))
    }

    @Test fun plainRenderIsUnchanged() {
        val r = Md.render("Sync with `git`\n- a", Color.Blue, Color.Gray)
        assertEquals("Sync with  git \n•  a", r.text)
        assertTrue(r.paragraphStyles.isEmpty())
        assertTrue(r.getStringAnnotations(MdChips.TAG, 0, r.length).isEmpty())
    }

    @Test fun chipGeometryIsTightAndRound() {
        val (top, bottom) = MdChips.vertical(baseline = 100f, codePx = 40f)
        assertEquals(64f, top, 0.01f); assertEquals(112f, bottom, 0.01f)
        assertTrue("chip shorter than a 23/15 line box", (bottom - top) / (40f / MdChips.CODE_EM) < 23f / 15f)
        assertTrue(MdChips.RADIUS_DP > 0f)
        assertTrue(MdChips.fill(true).alpha in 0.05f..0.14f); assertTrue(MdChips.fill(false).alpha in 0.03f..0.1f)
        assertEquals(1.2f, MdChips.indentEm(0, 2).second, 1e-4f)
        assertEquals(2.4f, MdChips.indentEm(2, 3).first, 1e-4f); assertEquals(4.2f, MdChips.indentEm(2, 3).second, 1e-4f)
    }

    @Test fun aWrappedRunGetsOneChipPerLine() {
        // lines: [0,10) [10,20) [20,30); visible ends trim a trailing space at 9 and 19
        val lineOf = { o: Int -> o / 10 }
        val start = { l: Int -> l * 10 }
        val vis = { l: Int -> l * 10 + 9 }
        assertEquals(listOf(Triple(0, 5, 9), Triple(1, 10, 19), Triple(2, 20, 24)), MdChips.segments(5, 24, lineOf, start, vis))
        assertEquals(listOf(Triple(0, 2, 6)), MdChips.segments(2, 6, lineOf, start, vis))
        assertTrue(MdChips.segments(6, 6, lineOf, start, vis).isEmpty())
    }

    private fun src(rel: String): String =
        listOf(File("src/main/java/com/argus/assistant/$rel"), File("app/src/main/java/com/argus/assistant/$rel")).first { it.exists() }.readText()

    @Test fun chatRepliesUseTheRichLayout() {
        val a = src("ui/AnswerCards.kt")
        assertTrue(a.contains("Md.render(md, c.accent, codeBg, live = live, rich = true)"))
        assertTrue(a.contains("drawCodeChips(l, shown, 15.sp.toPx(), chipFill)"))
        assertFalse("no square chips", src("ui/MdChips.kt").contains("RectangleShape"))
    }
}
