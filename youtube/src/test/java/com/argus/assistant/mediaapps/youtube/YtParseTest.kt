package com.argus.assistant.mediaapps.youtube

import com.argus.assistant.mediaapps.store.StoreError
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Parsing only, from fixtures (src/test/resources/youtube); no network. */
class YtParseTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource("youtube/$n")!!.readText()

    @Test fun searchFirstPage() {
        val p = YtParse.search(res("search.json"), "youtube")
        // the live radio (LIVE badge, no length) and the duplicate are dropped
        assertEquals(listOf("_jH2oTNRe7g", "CLeZyIID9Bo", "n61ULEU7CO0"), p.items.map { it.id })
        val a = p.items[0]
        assertEquals("youtube", a.source)
        assertEquals("cozy autumn music \uD83C\uDF42 lofi beats to study / relax to", a.title)
        assertEquals("Lofi Girl", a.author)
        assertEquals("1:04:42", a.durationText); assertEquals(3882, a.durationSec)
        assertEquals("2.2M views", a.views)
        assertEquals("https://i.ytimg.com/vi/_jH2oTNRe7g/mqdefault.jpg", a.thumb)
        assertEquals("YouTube", a.licence)
        assertEquals("https://www.youtube.com/watch?v=_jH2oTNRe7g", a.extra["url"])
        // the results' own continuation, never a filter chip's
        assertEquals("EqMDEgpsb2ZpIGJlYXRzGpADRWdJUUFV_fixture_next_page", p.next)
    }

    @Test fun searchContinuationPage() {
        val p = YtParse.search(res("search-next.json"), "youtube")
        assertEquals(listOf("aqz-KE-bpKQ"), p.items.map { it.id })   // the premiere (upcomingEventData, no length) is dropped
        assertEquals("Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film", p.items[0].title)
        assertEquals("Blender", p.items[0].author)                     // longBylineText when there is no ownerText
        assertEquals(635, p.items[0].durationSec)
        assertEquals("21,345,678 views", p.items[0].views)
        assertEquals("", p.next)
    }

    @Test fun searchRejectsNonJson() {
        try { YtParse.search("<html>Sorry</html>", "youtube"); fail() } catch (e: StoreError) { assertTrue(e.message!!.contains("search")) }
    }

    @Test fun playerStreamsDetailsAndCaptions() {
        val d = YtParse.player(res("player-ok.json"), "aqz-KE-bpKQ")
        assertEquals("aqz-KE-bpKQ", d.id)
        assertEquals("Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film", d.title)
        assertEquals("Blender", d.author); assertEquals(635, d.lengthSec)
        assertEquals("https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg", d.thumb)
        assertEquals("YouTube", d.licence)
        // the ciphered 248 is skipped; everything with a plain url is kept
        assertEquals(listOf("18", "137", "299", "136", "135", "313", "242", "140", "139", "251", "250"), d.formats.map { it.key })
        val muxed = d.formats.first { it.key == "18" }
        assertTrue(muxed.hasAudio && muxed.hasVideo); assertEquals("avc1.42001E, mp4a.40.2", muxed.codecs); assertEquals(39837364L, muxed.contentLength)
        val v = d.formats.first { it.key == "137" }
        assertTrue(v.hasVideo); assertFalse(v.hasAudio); assertEquals(1080, v.height); assertEquals(60, v.fps); assertEquals("1080p60", v.qualityLabel)
        val a = d.formats.first { it.key == "140" }
        assertTrue(a.hasAudio); assertFalse(a.hasVideo); assertEquals("audio/mp4", a.mime); assertEquals(130, a.kbps); assertEquals("mp4", a.container)
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=140&id=fix", a.url)
        // captions: \u0026 decoded, duplicate vssId dropped, a track without a url skipped
        assertEquals(listOf("yt-.en", "yt-a.en"), d.subtitles.map { it.id })
        assertEquals("https://www.youtube.com/api/timedtext?v=aqz-KE-bpKQ&lang=en&fmt=srv3", d.subtitles[0].url)
        assertEquals("English", d.subtitles[0].name); assertFalse(d.subtitles[0].auto)
        assertEquals("English (auto-generated)", d.subtitles[1].name); assertTrue(d.subtitles[1].auto); assertEquals("en", d.subtitles[1].lang)
    }

    @Test fun playerErrorsArePlain() {
        fun msg(f: String) = try { YtParse.player(res(f), "x"); "" } catch (e: StoreError) { e.message.orEmpty() }
        assertEquals("Sign in to confirm you\u2019re not a bot", msg("player-login.json"))
        assertTrue(msg("player-live.json").contains("live stream"))
        assertTrue(msg("player-ciphered.json").contains("protected"))
        assertTrue(msg("visitor.json").contains("can't be played"))
        try { YtParse.player("not json", "x"); fail() } catch (e: StoreError) { assertTrue(e.message!!.contains("player reply")) }
    }

    @Test fun musicChoices() {
        val d = YtParse.player(res("player-ok.json"), "aqz-KE-bpKQ")
        val c = YtParse.choices("audio", d)
        assertEquals(listOf("a140", "a139", "o251"), c.map { it.id })
        assertEquals("High \u00b7 AAC 130 kbps", c[0].label)
        assertEquals("m4a \u00b7 10.3 MB", c[0].detail)
        assertEquals(listOf("140"), c[0].keys); assertEquals("m4a", c[0].ext); assertEquals(130, c[0].kbps)
        assertEquals("Smaller \u00b7 AAC 50 kbps", c[1].label)
        assertEquals("Opus 140 kbps", c[2].label); assertEquals("opus", c[2].ext)
        assertEquals("a140", YtParse.defaultChoice("audio", c)!!.id)
    }

    @Test fun musicFallsBackToTheVideoSound() {
        val d = YtParse.player(res("player-ok.json"), "v").let { it.copy(formats = it.formats.filter { f -> f.hasVideo }) }
        assertEquals(listOf("p18"), YtParse.choices("audio", d).map { it.id })
    }

    @Test fun videoChoices() {
        val d = YtParse.player(res("player-ok.json"), "aqz-KE-bpKQ")
        val c = YtParse.choices("video", d)
        // H.264 per height (best bitrate of 1080p: 299), each merged with the best AAC; VP9 4K skipped; 360p progressive;
        // 240p only in VP9 → merged with the best Opus into .webm
        assertEquals(listOf("v299", "v136", "v135", "p18", "w242"), c.map { it.id })
        assertEquals(listOf("242", "251"), c[4].keys); assertEquals("webm", c[4].ext); assertEquals("240p \u00b7 WebM", c[4].label)
        assertEquals(12_000_000L + 9_900_000L, c[4].bytes)
        assertEquals(listOf("299", "140"), c[0].keys)
        assertEquals(1080, c[0].height); assertEquals("1080p60", c[0].label)
        assertEquals(390_000_000L + 10_280_000L, c[0].bytes)
        assertEquals("mp4 \u00b7 400.3 MB", c[0].detail)
        assertEquals(1_200_000L * 635 / 8 + 10_280_000L, c[2].bytes)          // no contentLength: bitrate × length
        assertEquals("360p \u00b7 one file", c[3].label); assertEquals(listOf("18"), c[3].keys)
        assertEquals("v136", YtParse.defaultChoice("video", c)!!.id)           // tallest up to 720p
    }

    @Test fun videoIds() {
        assertEquals("aqz-KE-bpKQ", YtParse.videoId("aqz-KE-bpKQ"))
        assertEquals("aqz-KE-bpKQ", YtParse.videoId("yt:aqz-KE-bpKQ"))
        assertEquals("aqz-KE-bpKQ", YtParse.videoId("https://www.youtube.com/watch?v=aqz-KE-bpKQ&t=30s"))
        assertEquals("aqz-KE-bpKQ", YtParse.videoId("https://youtu.be/aqz-KE-bpKQ?si=abc"))
        assertEquals("aqz-KE-bpKQ", YtParse.videoId("https://m.youtube.com/shorts/aqz-KE-bpKQ"))
        assertEquals("aqz-KE-bpKQ", YtParse.videoId("https://music.youtube.com/watch?v=aqz-KE-bpKQ&list=RD"))
        assertEquals("aqz-KE-bpKQ", YtParse.videoId("https://www.youtube-nocookie.com/embed/aqz-KE-bpKQ"))
        assertEquals("aqz-KE-bpKQ", YtParse.videoId("youtube.com/live/aqz-KE-bpKQ"))
        assertNull(YtParse.videoId("https://example.com/watch?v=aqz-KE-bpKQ"))
        assertNull(YtParse.videoId("https://www.youtube.com/watch?v=short"))
        assertNull(YtParse.videoId("lofi beats"))
    }

    @Test fun probeOffsets() {
        assertEquals(0L, YtParse.probeAt(1_000_000))
        assertEquals(1_500_000L, YtParse.probeAt(10_000_000))
        assertEquals(1_500_000L, YtParse.probeAt(0))
    }

    @Test fun clientContext() {
        val c = YtClients.context(YtClients.streams.first()).getJSONObject("client")
        assertEquals("VISIONOS", c.getString("clientName")); assertEquals("visionOS", c.getString("osName")); assertEquals("en", c.getString("hl"))
        assertEquals(32, YtClients.context(YtClients.streams[1]).getJSONObject("client").getInt("androidSdkVersion"))
        assertEquals("CgtWaXNpdG9yRml4", YtParse.visitorData(res("visitor.json")))
        assertEquals("", YtParse.visitorData("nope"))
        assertEquals("ab", YtParse.text(JSONObject("""{"runs":[{"text":"a"},{"text":"b"}]}""")))
    }
}
