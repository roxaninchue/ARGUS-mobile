package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2026-11-18: PDF pages can be shown again after their pictures leave the prompt (PdfPages). */
class PdfPagesTest {
    private val id = PdfPages.idFor("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

    @Test fun theReferenceNamesTheDocAndTheTool() {
        assertEquals("0123456789abcdef", id)
        val r = PdfPages.ref(id, "NET103 \"Week 2\".pdf", 14, 6)
        assertTrue(r, r.startsWith("[PDF pdf:$id ") && r.contains("14 page(s)") && r.contains("pages 1 to 6") && r.contains("pdf_page(doc=\"pdf:$id\", page=N)"))
        assertFalse("quotes in the name can't break the note", r.contains("\"Week"))
        assertTrue(PdfPages.ref(id, "a.pdf", 3, 3).contains("all 3 page(s)"))
        assertEquals(listOf(id), PdfPages.ids("text " + r + " again pdf:$id"))
    }

    @Test fun onlyOurIdsAreAccepted() {
        assertEquals(id, PdfPages.parseId("pdf:$id"))
        assertEquals(id, PdfPages.parseId(id.uppercase()))
        assertNull(PdfPages.parseId("pdf:../../shared_prefs/x"))
        assertNull(PdfPages.parseId("pdf:abc"))
        assertNull(PdfPages.parseId(""))
    }

    @Test fun pagesAreClampedToTheDocAndTheSpan() {
        assertEquals(0..0, PdfPages.span(1, null, 10))
        assertEquals(6..8, PdfPages.span(7, 20, 10))   // at most MAX_SPAN pages
        assertEquals(8..9, PdfPages.span(9, 40, 10))   // not past the end
        assertEquals(4..4, PdfPages.span(5, 2, 10))    // "to" before "page" → just the page
        assertNull(PdfPages.span(0, null, 10))
        assertNull(PdfPages.span(11, null, 10))
        assertNull(PdfPages.span(1, null, 0))
    }

    @Test fun thePictureIsLabelledAsAPdfPage() {
        assertEquals(PdfPages.NOTE to "data:image/jpeg;base64,AA", PdfPages.noteAndUrl(PdfPages.MARK + "data:image/jpeg;base64,AA", "[Screenshot]"))
        assertEquals("[Screenshot]" to "data:x", PdfPages.noteAndUrl("data:x", "[Screenshot]"))
        val f = PdfPages.tool().getJSONObject("function")
        assertEquals("pdf_page", f.getString("name"))
        assertEquals(listOf("doc", "page"), f.getJSONObject("parameters").getJSONArray("required").let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    @Test fun theToolIsAlwaysOnHandAndWired() {
        assertTrue(ToolPick.cueNames(setOf(ToolPick.HISTORY)).contains(PdfPages.TOOL))
        val root = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        fun src(p: String) = File(root, "app/src/main/java/com/blaze/assistant/$p").readText()
        assertTrue(src("agent/ToolRouter.kt").contains("\"cache_summarize\", \"pdf_page\","))
        val agent = src("agent/Agent.kt")
        assertTrue(agent.contains("t.put(PdfPages.tool())"))
        assertTrue(agent.contains("name == PdfPages.TOOL ->"))
        assertEquals(2, Regex("""PdfPages\.noteAndUrl\(shot, SHOT_NOTE\)""").findAll(agent).count())
        assertTrue(src("agent/Attachments.kt").contains("PdfPages.ref(kept, name, pdf.pageCount, n)"))
    }
}
