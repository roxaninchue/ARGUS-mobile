package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.52.1: the song-download loop (memory_search over and over, never media_download). */
class MemoryLoopTest {
    @Test fun concreteSongs() {
        assertEquals("Blinding Lights by The Weeknd", MemoryLoop.concrete("download Blinding Lights by The Weeknd"))
        assertEquals("Essence by Wizkid", MemoryLoop.concrete("please get me the song Essence by Wizkid."))
        assertEquals("Calm Down", MemoryLoop.concrete("download \u201cCalm Down\u201d"))
        assertEquals("Calm Down by Rema", MemoryLoop.concrete("Rema - Calm Down"))
        assertNotNull(MemoryLoop.concrete("""{"query":"Blinding Lights by The Weeknd"}"""))
        assertNull(MemoryLoop.concrete("download that song I liked"))
        assertNull(MemoryLoop.concrete("download the song from yesterday"))
    }

    @Test fun songAsks() {
        assertTrue(MemoryLoop.songAsk("download that song I liked"))
        assertTrue(MemoryLoop.songAsk("download Blinding Lights by The Weeknd"))
        assertTrue(!MemoryLoop.songAsk("what's the weather"))
    }

    /** The loop as it happened: a vague ask, one lookup finds the song, the next lookups are refused. */
    @Test fun loopStopsOnceTheSongIsKnown() {
        val user = "download that song I liked last week"
        var lookups = 0; var resolved = ""
        assertNull(MemoryLoop.gate("memory_search", user, lookups, resolved)); lookups++
        resolved = MemoryLoop.learn("memory_search", """{"query":"song I liked"}""", "User liked \"Essence\" by Wizkid (saved 2 Nov).") ?: ""
        assertTrue(resolved.contains("Essence"))
        val g = MemoryLoop.gate("memory_search", user, lookups, resolved)
        assertNotNull(g); assertTrue(g!!.contains("media_download"))
        assertNotNull(MemoryLoop.gate("recall_memory", user, lookups, resolved))
        assertNull(MemoryLoop.gate("media_download", user, lookups, resolved))     // the download itself always runs
    }

    @Test fun lookupsAreCappedPerTurn() {
        val user = "what did I tell you about my trip"
        for (n in 0 until MemoryLoop.MAX_LOOKUPS) assertNull(MemoryLoop.gate("memory_search", user, n, ""))
        val g = MemoryLoop.gate("memory_search", user, MemoryLoop.MAX_LOOKUPS, "")
        assertNotNull(g); assertTrue(g!!.startsWith("Not run"))
        assertNull(MemoryLoop.gate("web_search", user, 99, ""))
    }

    /** A named song never needs memory at all. */
    @Test fun namedSongSkipsMemory() {
        val user = "download Blinding Lights by The Weeknd"
        assertNotNull(MemoryLoop.gate("memory_search", user, 0, MemoryLoop.concrete(user).orEmpty()))
    }
}
