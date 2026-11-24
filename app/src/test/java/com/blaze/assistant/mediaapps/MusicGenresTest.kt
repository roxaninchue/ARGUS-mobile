package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.YtMusic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.55.17 (Finn): genre tiles have covers and open their own endless page of that genre, never the DJ. */
class MusicGenresTest {
    @Test fun eachNameIsOneTileAndMoodsSearchAsMusic() {
        val t = MusicGenres.tiles(listOf("Pop", "Chill"), listOf("Chill", "Focus"))
        assertEquals(listOf("Pop", "Chill", "Focus"), t.map { it.name })
        assertEquals("Pop songs", t[0].query)
        assertEquals("Focus music", t[2].query); assertTrue(t[2].mood)
        assertEquals(MusicGenres.tiles().size, MusicGenres.tiles().map { it.name.lowercase() }.toSet().size)
    }

    @Test fun coversComeFromTheGenresTopSongAtTileSize() {
        val items = listOf(StoreItem("youtube", "a", "No cover", "x"), StoreItem("youtube", "b", "Song", "y", thumb = "https://lh3.googleusercontent.com/abc=w60-h60-l90-rj"))
        assertEquals("https://lh3.googleusercontent.com/abc=w544-h544-l90-rj", MusicGenres.coverFrom(items))
        assertEquals("", MusicGenres.coverFrom(emptyList()))
        assertTrue(MusicGenres.fresh(1_000, 1_000 + MusicGenres.COVER_MS - 1)); assertFalse(MusicGenres.fresh(1_000, 1_000 + MusicGenres.COVER_MS + 1))
    }

    @Test fun aGenrePageKeepsGoingUntilYouTubeHasNoMore() {
        assertEquals("m:tok", YtMusic.nextAfterMusic("tok"))
        assertEquals("y", YtMusic.nextAfterMusic(""))   // YouTube Music ran out: the plain search carries on
    }

    @Test fun theTilesNeverStartTheDj() {
        val src = listOf(File("src/main/java/com/blaze/assistant/mediaapps/MusicShell.kt"), File("app/src/main/java/com/blaze/assistant/mediaapps/MusicShell.kt")).first { it.isFile }.readText()
        val browse = src.substringAfter("Browse: genres and moods").substringBefore("return@Column")
        assertFalse(browse.contains("onVibe("))
        assertTrue(browse.contains("genre = t"))
    }
}
