package com.argus.assistant.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader

class ChunkerTest {
    private val c = Chunker()

    private fun para(words: Int, w: String = "lorem") = (1..words).joinToString(" ") { "$w$it" } + ".\n\n"

    @Test fun piecesAreTheTextVerbatim() {
        val text = "# Title\n\nIntro line.\n\n" + para(900) + "## Part two\n\n" + para(1500) + "tail without newline"
        val ps = c.chunk(text)
        assertEquals(text, ps.joinToString("") { it.text })
        assertTrue(ps.size >= 2)
    }

    @Test fun headingsStartSectionsAndSetChapters() {
        val text = "# Book\n\n" + para(400) + "## Chapter One\n\n" + para(400) + "### A subsection\n\n" + para(400) + "## Chapter Two\n\n" + para(400)
        val ps = c.chunk(text)
        assertTrue(ps.any { it.title.startsWith("Chapter One") })
        assertTrue(ps.any { it.title.startsWith("Chapter Two") })
        val two = ps.first { it.title.startsWith("Chapter Two") }
        assertEquals("Chapter Two", two.chapter)
        assertEquals(2, two.level)
        // a subsection keeps its chapter
        assertTrue(ps.filter { it.text.contains("A subsection") }.all { it.chapter == "Chapter One" })
    }

    @Test fun tinySectionsMergeInsteadOfOnePerHeading() {
        val text = (1..30).joinToString("") { "### Note $it\n\nshort body $it.\n\n" }
        val ps = c.chunk(text)
        assertTrue("got ${ps.size}", ps.size <= 3)
        assertTrue(ps.first().title.contains("Note 1"))
    }

    @Test fun hugeSingleParagraphIsCutAtSentencesWithinTheHardMax() {
        val sentence = "This is a sentence about memory tiers and budgets. "
        val text = sentence.repeat(4_000)   // ~200k chars, no blank line, no newline
        val ps = c.chunk(text)
        assertEquals(text, ps.joinToString("") { it.text })
        assertTrue(ps.size >= 20)
        ps.forEach { assertTrue("piece of ${it.text.length}", it.text.length <= Chunker.HARD_MAX) }
        ps.dropLast(1).forEach { assertTrue(it.text.trimEnd().endsWith(".")) }
    }

    @Test fun noSpacesAtAllStillCutsAndNeverSplitsSurrogates() {
        val emoji = "\uD83D\uDE00"  // 😀, a surrogate pair
        val text = ("ab" + emoji).repeat(20_000)
        val ps = c.chunk(text)
        assertEquals(text, ps.joinToString("") { it.text })
        ps.forEach { p ->
            assertFalse(Character.isLowSurrogate(p.text.first()))
            assertFalse(Character.isHighSurrogate(p.text.last()))
            assertTrue(p.text.length <= Chunker.HARD_MAX)
        }
    }

    @Test fun unicodeTextAndHeadingsWork() {
        val text = "# Capítulo único\n\n" + "日本語の文章です。".repeat(1200) + "\n\n# Ελληνικά\n\n" + "Καλημέρα κόσμε. ".repeat(800)
        val ps = c.chunk(text)
        assertEquals(text, ps.joinToString("") { it.text })
        assertTrue(ps.first().title.startsWith("Capítulo único"))
        assertTrue(ps.any { it.title.startsWith("Ελληνικά") })
        ps.forEach { assertTrue(it.text.length <= Chunker.HARD_MAX) }
    }

    @Test fun streamsFromAReaderInSmallReads() {
        val text = para(5_000)
        // a reader that returns at most 7 chars a call
        val r = object : Reader() {
            var i = 0
            override fun read(b: CharArray, off: Int, len: Int): Int {
                if (i >= text.length) return -1
                val n = minOf(len, 7, text.length - i); text.toCharArray(i, i + n).copyInto(b, off); i += n; return n
            }
            override fun close() {}
        }
        val out = StringBuilder(); var n = 0
        c.chunk(r) { out.append(it.text); n++ }
        assertEquals(text, out.toString())
        assertTrue(n > 3)
    }

    @Test fun codeFencesAreNotHeadings() {
        val text = "# Real\n\n```bash\n# not a heading\necho hi\n```\n\n" + para(100)
        val ps = c.chunk(text)
        assertEquals(1, ps.size)
        assertEquals("Real", ps[0].title)
    }

    @Test fun headingKindsAndGists() {
        assertEquals(2 to "Chapter 3: The Sea", Chunker.heading("Chapter 3: The Sea"))
        assertEquals(1, Chunker.heading("PART II")?.first)
        assertEquals(2, Chunker.heading("INTRODUCTION")?.first)
        assertEquals(null, Chunker.heading("A normal sentence that is not a heading."))
        assertEquals("The cache keeps text on the phone.", Chunker.fallbackGist("# Cache\n\nThe cache keeps text on the phone. More words here."))
        assertTrue(Chunker.fallbackGist("x".repeat(500)).length <= 121)
    }
}
