package com.blaze.assistant.mediaapps

import com.blaze.assistant.mediaapps.store.StoreItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.55.11: Music's song shelves (never an empty Home) and their disk copy. */
class MusicFeedPlanTest {
    private fun i(id: String, sec: Int = 200, t: String = "Song $id") = StoreItem("youtube", id, t, "Artist", sec, "3:20", "https://x/$id=w544-h544-l90-rj",
        square = true, extra = mapOf("album" to "Al"))

    @Test fun newUserStillGetsShelves() {
        val p = MusicFeedPlan.plan(emptyList(), emptyList(), "", 2026)
        assertEquals(9, p.size)
        assertEquals(listOf("trending", "new"), p.take(2).map { it.id })
        assertEquals("Trending now", p[0].title)
        assertTrue(p.all { it.query.isNotBlank() && it.title.isNotBlank() })
        assertEquals(p.size, p.map { it.id }.toSet().size)
    }

    @Test fun historyComesFirst() {
        val p = MusicFeedPlan.plan(listOf("Burna Boy", "burna boy", "Tems", "Wizkid"), listOf("amapiano", "bengicela"), "Zambia", 2026)
        assertEquals("Because you listened to Burna Boy", p[0].title)
        assertEquals("Because you listened to Tems", p[1].title)
        assertEquals("Burna Boy", p[0].query)
        assertTrue(p.any { it.id == "country" && it.title == "Top hits in Zambia" })
        // A real genre from the history leads the genres; a recurring song word is not a genre.
        assertEquals("Amapiano", p.first { it.id.startsWith("genre:") }.title)
        assertFalse(p.any { it.title.contains("Bengicela", true) })
        assertEquals(9, p.size)
    }

    @Test fun shelvesHoldSongsNotMixes() {
        val s = MusicFeedPlan.songs(listOf(i("a"), i("b", 46 * 60), i("a"), i("c", 0)))
        assertEquals(listOf("a", "c"), s.map { it.id })
    }

    @Test fun recentAndStale() {
        val r = MusicFeedPlan.pushRecent(listOf(i("a"), i("b")), i("b"))
        assertEquals(listOf("b", "a"), r.map { it.id })
        val plan = MusicFeedPlan.plan(emptyList(), emptyList(), "", 2026)
        val shelves = plan.map { MusicFeedPlan.Shelf(it.id, it.title, listOf(i(it.id))) }
        assertFalse(MusicFeedPlan.stale(1000, 2000, shelves, plan))
        assertTrue(MusicFeedPlan.stale(1000, 1000 + MusicFeedPlan.STALE_MS + 1, shelves, plan))
        assertTrue(MusicFeedPlan.stale(1000, 2000, emptyList(), plan))
        assertTrue(MusicFeedPlan.stale(1000, 2000, shelves.drop(1), plan))
    }

    @Test fun diskCopyRoundTrips() {
        val shelves = listOf(MusicFeedPlan.Shelf("trending", "Trending now", listOf(i("a"), i("b"))))
        val back = MusicFeedPlan.decode(MusicFeedPlan.encode(42, shelves, listOf(i("c"))))
        assertEquals(42L, back.at)
        assertEquals(shelves, back.shelves)
        assertEquals(listOf(i("c")), back.recent)
        assertTrue(MusicFeedPlan.decode("garbage").shelves.isEmpty())
    }
}
