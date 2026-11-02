package com.blaze.assistant.memory

import com.blaze.assistant.store.JdbcSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CacheStoreTest {
    private var clock = 1_000L
    private fun store(cap: Long = CacheStore.CAP_BYTES) = CacheStore(JdbcSql(), Files.createTempDirectory("cache").toFile(), cap) { clock++ }

    private fun book(): String = buildString {
        append("# Garden handbook\n\n")
        for (ch in 1..6) {
            append("## Chapter $ch\n\n")
            repeat(3) { p -> append("Paragraph $p of chapter $ch talks about ").append(if (ch == 4) "tomatoes and their watering schedule" else "soil, compost and seasons")
                append(". ").append("filler words ".repeat(220)).append("\n\n") }
        }
    }

    @Test fun ingestsWithContentlessFts5AndReadsSectionsBack() {
        val s = store()
        assertEquals(2, s.fts)
        val text = book()
        val d = s.ingest("chat1", "paste", "Garden", text)
        assertTrue(d.sections >= 6)
        assertEquals(text.length.toLong(), d.chars)
        val secs = s.sections(d.id)
        assertEquals((0 until d.sections).toList(), secs.map { it.idx })
        // the sections read back are the document, verbatim, in order
        assertEquals(text, secs.joinToString("") { s.text(it) })
        assertEquals(text, s.file(d.id).readText())
        assertTrue(secs.all { it.gist.isNotBlank() && !it.gisted })
    }

    @Test fun bm25SearchFindsTheRightChapter() {
        val s = store()
        val d = s.ingest("chat1", "paste", "Garden", book())
        val hits = s.search("tomatoes watering", doc = d.id)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().title, hits.first().title.contains("Chapter 4") || s.section(d.id, hits.first().idx)!!.chapter == "Chapter 4")
        assertTrue(hits.first().snippet.contains("tomatoes", true))
        assertTrue(s.search("zzzxxqq", doc = d.id).isEmpty())
        assertTrue(s.search("", doc = d.id).isEmpty())
        // FTS5 syntax in the query is harmless
        assertTrue(s.search("tomatoes\" OR (NEAR", doc = d.id).isNotEmpty())
    }

    @Test fun searchScopesToTheChat() {
        val s = store()
        s.ingest("a", "paste", "A", "alpha beta gamma tomatoes\n")
        s.ingest("b", "paste", "B", "tomatoes delta\n")
        assertEquals(1, s.search("tomatoes", chat = "a").size)
        assertEquals(2, s.search("tomatoes").size)
    }

    @Test fun gistsAndClaims() {
        val s = store()
        val d = s.ingest("", "file", "notes.txt", "First line.\n\nSecond.\n")
        s.setGist(d.id, 0, "- About the first line\nextra")
        val sec = s.section(d.id, 0)!!
        assertEquals("About the first line", sec.gist); assertTrue(sec.gisted)
        assertTrue(s.ungisted(d.id, 10).isEmpty())
        s.claim(d.id, "chat9")
        assertEquals("chat9", s.doc(d.id)!!.chat)
        s.claim(d.id, "other")     // only an unclaimed doc can be claimed
        assertEquals("chat9", s.doc(d.id)!!.chat)
    }

    @Test fun appendGrowsTheHistoryDoc() {
        val s = store()
        val a = s.append("chat-x", "x", "history", "Earlier", "## turn 0 · user\n\nhello there\n\n")
        val b = s.append("chat-x", "x", "history", "Earlier", "## turn 1 · assistant\n\nhi, the plan is tomatoes\n\n")
        assertEquals(a.id, b.id)
        assertEquals(2, b.sections)
        assertTrue(s.text(s.section("chat-x", 1)!!).contains("tomatoes"))
        assertEquals(1, s.search("tomatoes", doc = "chat-x").size)
    }

    @Test fun deleteChatRemovesFilesRowsAndIndex() {
        val s = store()
        val d1 = s.ingest("gone", "paste", "1", book())
        val d2 = s.ingest("kept", "paste", "2", "tomatoes stay\n")
        assertEquals(1, s.deleteChat("gone"))
        assertNull(s.doc(d1.id)); assertFalse(s.file(d1.id).exists())
        assertTrue(s.sections(d1.id).isEmpty())
        assertTrue(s.search("tomatoes").all { it.doc == d2.id })
    }

    @Test fun lruEvictionKeepsTheCapAndTheNewest() {
        val s = store(cap = 120_000)
        val ids = (1..5).map { s.ingest("c", "paste", "doc$it", "x".repeat(50_000) + "\n").id }
        s.touch(ids[0])                      // the oldest was just used: it stays
        val gone = s.evict(keep = setOf(ids[4]))
        assertTrue(s.totalBytes() <= 120_000)
        assertFalse(ids[0] in gone)
        assertFalse(ids[4] in gone)
        assertTrue(ids[1] in gone)
        gone.forEach { assertFalse(s.file(it).exists()) }
    }

    @Test fun oneDocumentIsCappedAndSaysSo() {
        val s = store()
        val d = s.ingest("c", "paste", "big", ("word ".repeat(2_000) + "\n").repeat(40).reader(), maxChars = 100_000)
        assertTrue(d.truncated)
        assertTrue(d.chars in 100_000..120_000)
    }
}
