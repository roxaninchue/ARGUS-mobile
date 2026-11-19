package com.argus.assistant.mediaapps.youtube

import com.argus.assistant.mediaapps.store.StoreError
import com.argus.assistant.mediaapps.store.StoreItem
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live check against YouTube, only with YT_LIVE=1 (youtube-daily.yml; skipped in every other build).
 * Search must work. Streams are checked too, but data-centre runners often get YouTube's bot check on the player, so a
 * refused player only fails the test with YT_LIVE_STRICT=1; otherwise it is printed as a warning.
 */
class YouTubeLiveSmokeTest {
    private val live = System.getenv("YT_LIVE") == "1"
    private val strict = System.getenv("YT_LIVE_STRICT") == "1"

    @Test fun searchAndStreams() {
        assumeTrue("live smoke only with YT_LIVE=1", live)
        val e = YouTubeEngine(HttpTransport)
        val p = e.search("big buck bunny")
        println("YT-SMOKE search: ${p.items.size} results, next page: ${p.next.isNotBlank()}; first: ${p.items.firstOrNull()?.let { "${it.id} ${it.title} (${it.durationText})" }}")
        assertTrue("search returned results", p.items.isNotEmpty())
        val p2 = if (p.next.isNotBlank()) e.search("big buck bunny", p.next) else null
        println("YT-SMOKE search page 2: ${p2?.items?.size ?: "no token"}")
        val item = StoreItem("youtube", "aqz-KE-bpKQ", "Big Buck Bunny", "")
        for (kind in listOf("audio", "video")) {
            try {
                val d = e.detail(item, kind)
                val c = YtParse.choices(kind, d)
                println("YT-SMOKE $kind: ${d.title} / ${d.author} / ${d.lengthSec}s, ${d.formats.size} streams, choices ${c.map { it.label }}, subtitles ${d.subtitles.size}")
                assertTrue("$kind choices", c.isNotEmpty())
            } catch (x: StoreError) {
                println("YT-SMOKE WARNING $kind streams: ${x.message}")
                if (strict) throw x
            }
        }
    }
}
