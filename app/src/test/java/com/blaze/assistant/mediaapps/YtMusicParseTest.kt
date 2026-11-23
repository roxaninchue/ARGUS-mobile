package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.YtMusicParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.55.11: YouTube Music song rows (a recorded "Songs" search, test/resources/store/ytm-search.json). */
class YtMusicParseTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource("store/$n")!!.readText()

    @Test fun songRows() {
        val p = YtMusicParse.songs(res("ytm-search.json"), "youtube")
        assertEquals(3, p.items.size)
        val a = p.items[0]
        assertEquals("xdKDy5ihWsA", a.id); assertEquals("youtube", a.source)
        assertEquals("Say You Love Me", a.title)
        assertEquals("Shekhinah & Brandon Dhludhlu", a.author)
        assertEquals("Say You Love Me", a.extra["album"])
        assertEquals("6:34", a.durationText); assertEquals(394, a.durationSec)
        assertTrue(a.square && a.thumb.endsWith("=w544-h544-l90-rj"))
        assertEquals("", a.licence)
        assertEquals("Lwetse (feat. DJ Maphorisa & Mluusician)", p.items[2].title)
        assertEquals("NEXT123", p.next)
    }

    @Test fun badReplyIsEmpty() {
        assertTrue(YtMusicParse.songs("not json", "youtube").items.isEmpty())
        assertTrue(YtMusicParse.songs("{}", "youtube").items.isEmpty())
    }

    @Test fun videoResultsBecomeSongs() {
        val v = StoreItem("youtube", "abc", "Tems - Free Mind (Official Video)", "TemsVEVO", 200, "3:20", "https://i.ytimg.com/vi/abc/mqdefault.jpg", "YouTube", views = "1M views")
        val s = YtMusicParse.fromVideo(v)
        assertEquals("Free Mind", s.title); assertEquals("Tems", s.author); assertEquals("", s.licence); assertEquals("", s.views)
    }
}
