package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Unreleased after 0.54.1: make_document's pure half (Markdown → Word / PowerPoint, and the file a turn owes). */
class DocFilesTest {
    private val md = """# How the Kwacha Moved in 2026

The kwacha gained **12%** this year, driven by copper and *IMF* support.

## Key numbers

- Rate on 1 Jan: K27.1
  - Low point in March
- Copper at a [record](https://example.com/copper)

## Comparison

| Month | Rate |
|---|---|
| Jan | 27.1 |
| Nov | 23.9 |

```chart
{"title":"Rate by month"}
```

## Sources

1. Bank of Zambia, 2026
"""

    private fun parts(zip: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        return out
    }

    @Test fun markdownBecomesBlocks() {
        val b = DocFiles.blocks(md)
        assertEquals(DocFiles.Block.Heading(1, "How the Kwacha Moved in 2026"), b.first())
        assertTrue(b.contains(DocFiles.Block.Item("Low point in March", "•", 1)))
        assertTrue(b.contains(DocFiles.Block.Table(listOf(listOf("Month", "Rate"), listOf("Jan", "27.1"), listOf("Nov", "23.9")))))
        assertTrue(b.contains(DocFiles.Block.Quote("Chart: Rate by month")))
        assertTrue(b.contains(DocFiles.Block.Item("Bank of Zambia, 2026", "1.", 0)))
        assertEquals(listOf(DocFiles.Run("Copper at a "), DocFiles.Run("record (https://example.com/copper)")), DocFiles.inline("Copper at a [record](https://example.com/copper)"))
        assertEquals(listOf(DocFiles.Run("a "), DocFiles.Run("b", bold = true), DocFiles.Run(" "), DocFiles.Run("c", italic = true)), DocFiles.inline("a **b** *c*"))
    }

    @Test fun wordFileHasItsParts() {
        val p = parts(DocFiles.docx(md, "Kwacha"))
        assertTrue(p.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels")))
        val d = p.getValue("word/document.xml")
        assertTrue(d.contains("<w:pStyle w:val=\"Title\"/>") && d.contains("<w:pStyle w:val=\"Heading1\"/>") && d.contains("<w:tbl>"))
        assertTrue(d.contains("<w:b/></w:rPr><w:t xml:space=\"preserve\">12%"))
        assertFalse(d.contains("<w:b/><w:b/>"))
    }

    @Test fun deckIsACoverThenOneSlidePerSection() {
        val s = DocFiles.slides(md, "How the Kwacha Moved in 2026")
        assertEquals(listOf("How the Kwacha Moved in 2026", "Key numbers", "Comparison", "Comparison (cont.)", "Sources"), s.map { it.title })
        assertTrue(s[0].subtitle.startsWith("The kwacha gained 12%"))
        assertEquals(3, s[2].table!!.size)
        val p = parts(DocFiles.pptx(md, "Kwacha"))
        assertEquals(5, p.keys.count { Regex("ppt/slides/slide\\d+\\.xml").matches(it) })
        assertTrue(p.getValue("ppt/presentation.xml").contains("r:id=\"rId15\""))
        assertTrue(p.getValue("ppt/slides/slide3.xml").contains("<a:tbl>"))
        val long = "# T\n\n## Many\n" + (1..10).joinToString("\n") { "- point $it" }
        assertEquals(listOf("T", "Many", "Many (cont.)"), DocFiles.slides(long, "T").map { it.title })
    }

    @Test fun theAskNamesTheFile() {
        mapOf("make it a PDF" to "pdf", "Send PDF" to "pdf", "can you send me the pdf version" to "pdf", "make a powerpoint about this" to "pptx",
            "turn it into slides" to "pptx", "give me a deck on copper" to "pptx", "export as a word doc" to "docx", "write me a report as a docx" to "docx")
            .forEach { (ask, f) -> assertEquals(ask, f, DocFiles.wantsFile(ask)) }
        listOf("summarize this pdf", "what's in the pdf", "turn this pdf into notes", "thanks!", "how do slides work in keynote?", "can you make a docs and PowerPoint maker too?", "add a PDF export feature", "how do I make a pdf?")
            .forEach { assertNull(it, DocFiles.wantsFile(it)) }
        assertEquals("how-the-kwacha-moved-in-2026.pptx", DocFiles.fileName("", "How the Kwacha Moved in 2026", "pptx"))
        assertEquals("kwacha.docx", DocFiles.fileName("Kwacha.pdf", "x", "docx"))
        assertTrue(DocFiles.madeIn("pdf", "Saved to Downloads/Argus/kwacha.pdf. Tell the user"))
        assertFalse(DocFiles.madeIn("pdf", "Saved to Downloads/Argus/kwacha.md. Tell the user", null))
    }
}
