package com.argus.assistant.mediaapps.youtube

import com.argus.assistant.mediaapps.store.StoreError
import com.argus.assistant.mediaapps.store.StoreItem
import com.argus.assistant.mediaapps.store.StoreSource
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The plug-in's client fallback, search and wiring, over a fake transport (no network). */
class YouTubeEngineTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource("youtube/$n")!!.readText()

    /** Answers by endpoint; [player] maps a client name to (code, fixture); [refused] lists client user-agents whose probe fails. */
    private inner class Fake(val player: Map<String, Pair<Int, String>>, val refused: Set<String> = emptySet()) : YtTransport {
        val posts = ArrayList<Pair<String, JSONObject>>(); val probes = ArrayList<Pair<String, Long>>()
        val headers = ArrayList<Map<String, String>>()
        override fun post(url: String, body: String, headers: Map<String, String>): YtResponse {
            val b = JSONObject(body); posts += url to b; this.headers += headers
            return when {
                "/visitor_id" in url -> YtResponse(200, res("visitor.json"))
                "/search" in url -> YtResponse(200, res(if (b.has("continuation")) "search-next.json" else "search.json"))
                "/player" in url -> player[b.getJSONObject("context").getJSONObject("client").getString("clientName")]
                    ?.let { (c, f) -> YtResponse(c, if (f.isEmpty()) "Sorry..." else res(f)) } ?: YtResponse(500, "")
                else -> YtResponse(404, "")
            }
        }
        override fun get(url: String, headers: Map<String, String>) =
            if ("oembed" in url) YtResponse(200, res("oembed.json")) else YtResponse(404, "")
        override fun probe(url: String, ua: String, at: Long, len: Int): Boolean { probes += url to at; return ua !in refused }
    }

    private val ua = YtClients.streams.associate { it.name to it.ua }

    @Test fun searchPagesAndBlankQuery() {
        val f = Fake(emptyMap()); val e = YouTubeEngine(f)
        assertTrue(e.search("   ").items.isEmpty()); assertTrue(f.posts.isEmpty())
        val p1 = e.search(" lofi beats ")
        assertEquals(3, p1.items.size)
        val first = f.posts.last().second
        assertEquals("lofi beats", first.getString("query")); assertEquals("EgIQAQ==", first.getString("params"))
        assertEquals("WEB", first.getJSONObject("context").getJSONObject("client").getString("clientName"))
        val p2 = e.search("lofi beats", p1.next)
        assertEquals(p1.next, f.posts.last().second.getString("continuation"))
        assertEquals("aqz-KE-bpKQ", p2.items.single().id)
    }

    @Test fun searchWithNoResultsSaysSo() {
        val e = YouTubeEngine(object : YtTransport {
            override fun post(url: String, body: String, headers: Map<String, String>) = YtResponse(200, """{"contents":{}}""")
            override fun get(url: String, headers: Map<String, String>) = YtResponse(404, "")
            override fun probe(url: String, ua: String, at: Long, len: Int) = true
        })
        try { e.search("zzzz"); fail() } catch (x: StoreError) { assertTrue(x.message!!.contains("found nothing")) }
    }

    @Test fun firstClientWhoseStreamDownloadsWins() {
        val f = Fake(mapOf("VISIONOS" to (200 to "player-ok.json"), "ANDROID_VR" to (200 to "player-ok.json")), refused = setOf(ua.getValue("VISIONOS")))
        val e = YouTubeEngine(f)
        val d = e.detail(StoreItem("youtube", "aqz-KE-bpKQ", "From search", "Ch"), "audio")
        assertEquals("Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film", d.title)
        assertEquals(ua.getValue("ANDROID_VR"), e.userAgent)                          // urls handed out by ANDROID_VR
        assertEquals(listOf("https://rr1.googlevideo.com/videoplayback?itag=140&id=fix" to 1_500_000L), f.probes.distinct())
        val players = f.posts.filter { "/player" in it.first }
        assertEquals(listOf("VISIONOS", "ANDROID_VR"), players.map { it.second.getJSONObject("context").getJSONObject("client").getString("clientName") })
        assertEquals("aqz-KE-bpKQ", players[0].second.getString("videoId"))
        assertEquals("CgtWaXNpdG9yRml4", f.headers.last()["X-Goog-Visitor-Id"])
        assertEquals("28", f.headers.last()["X-YouTube-Client-Name"])
    }

    @Test fun videoProbesTheVideoStream() {
        val f = Fake(mapOf("VISIONOS" to (200 to "player-ok.json")))
        YouTubeEngine(f).detail(StoreItem("youtube", "https://youtu.be/aqz-KE-bpKQ", "", ""), "video")
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=136&id=fix", f.probes.single().first)   // 720p default
    }

    @Test fun allClientsFailingGivesOnePlainReason() {
        val f = Fake(mapOf("VISIONOS" to (200 to "player-login.json"), "ANDROID_VR" to (403 to ""), "ANDROID" to (200 to "player-ciphered.json")))
        try { YouTubeEngine(f).detail(StoreItem("youtube", "aqz-KE-bpKQ", "", ""), "audio"); fail() }
        catch (e: StoreError) {
            assertTrue(e.message, e.message!!.startsWith("YouTube didn't hand out this video: Sign in to confirm"))
        }
        assertNull(YouTubeEngine(f).refresh("aqz-KE-bpKQ", "audio"))
        assertNull(YouTubeEngine(f).refresh("not a video", "audio"))
        try { YouTubeEngine(f).detail(StoreItem("youtube", "nope", "", ""), "audio"); fail() } catch (_: StoreError) { }
    }

    @Test fun refreshGivesFreshUrls() {
        val d = YouTubeEngine(Fake(mapOf("VISIONOS" to (200 to "player-ok.json")))).refresh("aqz-KE-bpKQ", "video")
        assertNotNull(d); assertEquals(11, d!!.formats.size)
    }

    @Test fun describeALink() {
        val i = YouTubeEngine(Fake(emptyMap())).describe("https://www.youtube.com/watch?v=aqz-KE-bpKQ")!!
        assertEquals("aqz-KE-bpKQ", i.id); assertEquals("Blender", i.author); assertEquals("youtube", i.source)
        assertNull(YouTubeEngine(Fake(emptyMap())).describe("https://example.com/a.mp3"))
    }

    /** The app's lookup (StoreSources.plugin): a Kotlin object found by class name through its INSTANCE field. */
    @Test fun foundByClassNameLikeTheApp() {
        val s = Class.forName("com.argus.assistant.mediaapps.youtube.YouTubeSource").getField("INSTANCE").get(null) as StoreSource
        assertSame(YouTubeSource, s)
        assertEquals("youtube", s.id); assertEquals("YouTube", s.name); assertEquals(setOf("audio", "video"), s.kinds)
        assertNull(s.setupNeeded()); assertEquals(YtClients.streams.first().ua, s.userAgent)
        val d = YtParse.player(res("player-ok.json"), "aqz-KE-bpKQ")
        assertEquals(listOf("136", "140"), s.streamPick("video", d).map { it.key })   // the interface's default: video + audio merged
        assertEquals(listOf("140"), s.streamPick("audio", d).map { it.key })
    }
}
