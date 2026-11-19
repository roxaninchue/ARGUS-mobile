package com.argus.assistant.mediaapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaImportTest {
    @Test fun kindFromMimeOrExtension() {
        assertEquals("audio", ImportRules.kindOf("audio/mp4", "x.m4a"))
        assertEquals("audio", ImportRules.kindOf("application/octet-stream", "ACIDO III.opus"))
        assertEquals("audio", ImportRules.kindOf(null, "song.flac"))
        assertEquals("video", ImportRules.kindOf("video/x-matroska", "film.mkv"))
        assertEquals("video", ImportRules.kindOf("", "clip.MP4"))
        assertNull(ImportRules.kindOf("application/zip", "blaze.zip"))
    }

    @Test fun titlesFromFileNames() {
        assertEquals("Youngblood" to "5 Seconds of Summer", ImportRules.titleArtist("5 Seconds of Summer - Youngblood.opus"))
        assertEquals("Blurry Lights (with Albert Hammond Jr)" to "Ax and the Hatchetmen",
            ImportRules.titleArtist("Ax and the Hatchetmen - Blurry Lights (with Albert Hammond Jr) (Official Video).m4a"))
        assertEquals("Attention" to "Charlie Puth", ImportRules.titleArtist("Charlie Puth - Attention (Lyrics).m4a"))
        assertEquals("BAILA LENTO" to "", ImportRules.titleArtist("BAILA LENTO.opus"))
        assertEquals("-I'm God- by Clams Casino and Imogen Heap" to "", ImportRules.titleArtist("-I'm God- by Clams Casino and Imogen Heap.m4a"))
        assertEquals("my song" to "", ImportRules.titleArtist("my_song.mp3"))
    }

    @Test fun tagsAndSummary() {
        assertNull(ImportRules.goodTag("<unknown>")); assertNull(ImportRules.goodTag("  ")); assertEquals("Aurora", ImportRules.goodTag(" Aurora "))
        assertEquals("Imported 3 songs \u00b7 1 already in your library", ImportRules.summary(3, 1, 0, "audio"))
        assertEquals("Imported 1 video", ImportRules.summary(1, 0, 0, "video"))
        assertEquals("Nothing imported", ImportRules.summary(0, 0, 0, "audio"))
    }
}
