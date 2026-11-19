package com.blaze.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.52.0 smarter rich replies: gallery, link cards, document card, collapsible section (pure parse + block expansion). */
class RichMediaTest {
    private fun blocks(md: String) = RichReplies.expand(AnswerCards.parse(md))

    @Test fun gallerySongsAreAListWithArtwork() {
        val g = RichMedia.gallery("""{"title":"Amapiano 2025","items":[{"title":"Song A","artist":"Artist A","artwork":"https://img/a.jpg","url":"https://s/a"},{"title":"Song B","subtitle":"B · 2025","image":"not-a-url"}]}""")!!
        assertEquals("Amapiano 2025", g.title)
        assertFalse(g.grid)
        assertEquals("Artist A", g.items[0].subtitle)
        assertEquals("https://img/a.jpg", g.items[0].image)
        assertEquals("", g.items[1].image)                      // never a made-up / broken url
    }

    @Test fun postersWithoutSubtitlesAreAGrid() {
        val g = RichMedia.gallery("""{"items":[{"title":"Film 1","poster":"https://p/1.jpg"},{"title":"Film 2","poster":"https://p/2.jpg"}]}""")!!
        assertTrue(g.grid)
        assertFalse(RichMedia.gallery("""{"layout":"list","items":[{"title":"Film 1","poster":"https://p/1.jpg"}]}""")!!.grid)
    }

    @Test fun linksNeedRealUrlsAndAreDeduped() {
        val l = RichMedia.links("""{"items":[{"title":"BBC","url":"https://www.bbc.co.uk/x"},{"title":"dup","url":"https://www.bbc.co.uk/x"},{"title":"bad","url":"ftp://x"}]}""")!!
        assertEquals(1, l.items.size)
        assertEquals("bbc.co.uk", l.items[0].source)
        assertNull(RichMedia.links("""{"items":[{"title":"no url"}]}"""))
    }

    @Test fun docAndDetails() {
        val d = RichMedia.doc("""{"title":"Solar in Zambia","path":"/storage/emulated/0/Download/Blaze/solar.md","summary":"5 sections"}""")!!
        assertEquals("md", d.format); assertEquals("MD", RichMedia.badge(d.format))
        assertNull(RichMedia.doc("""{"title":"x"}"""))
        val det = RichMedia.details("""{"title":"How I worked it out","body":"1. a\n2. b"}""")!!
        assertEquals("How I worked it out", det.title)
    }

    @Test fun repliesExpandIntoTheNewBlocks() {
        val md = "Here are the top picks.\n\n```gallery\n{\"title\":\"Top\",\"items\":[{\"title\":\"A\",\"subtitle\":\"x\"}]}\n```\n\n" +
            "```links\n{\"items\":[{\"title\":\"Read\",\"url\":\"https://e.org/r\"}]}\n```\n\n" +
            "```doc\n{\"title\":\"Report\",\"path\":\"/x/r.md\"}\n```\n\n```details\n{\"title\":\"Working\",\"body\":\"long\"}\n```"
        val b = blocks(md)
        assertTrue(b.any { it is RichReplies.Block.Gallery })
        assertTrue(b.any { it is RichReplies.Block.Links })
        assertTrue(b.any { it is RichReplies.Block.Doc })
        assertTrue(b.any { it is RichReplies.Block.Details })
    }

    @Test fun aBrokenBlockFallsBackToTextNotRawJson() {
        val b = blocks("```gallery\n{\"items\":[]}\n```")
        assertTrue(b.none { it is RichReplies.Block.Gallery })
        assertTrue(b.none { it is RichReplies.Block.Plain && (it.seg as? AnswerCards.Seg.Code) != null })
    }

    @Test fun streamingBlocksShowAPlaceholder() {
        val b = blocks("```gallery\n{\"title\":\"Top\",\"items\":[{\"title\":")
        val p = b.filterIsInstance<RichReplies.Block.Pending>().single()
        assertEquals("gallery", p.kind)
        assertEquals("Laying out the list\u2026", RichReplies.pendingLabel("gallery"))
    }

    @Test fun thePromptAsksForVisualsAndShortText() {
        assertTrue(RichReplies.PROMPT.contains("```gallery"))
        assertTrue(RichReplies.PROMPT.contains("show, don't tell"))
        assertNotNull(RichKit.LANGS["doc"])
    }

    @Test fun orchestraBlocksRenderAsRichBlocks() {
        val doc = com.blaze.assistant.orchestra.DocCard.block("Solar", "/x/solar.md", "# Solar\n\n## A\nx\n\n## B\ny")
        assertTrue(blocks(doc).any { it is RichReplies.Block.Doc })
        val gal = com.blaze.assistant.orchestra.Pipelines.gallery(org.json.JSONObject("""{"title":"T","items":[{"title":"A","subtitle":"x","image":"https://i/a.jpg","url":"https://u/a","why":"#1"}]}"""))
        assertTrue(blocks(gal).any { it is RichReplies.Block.Gallery })
    }
}
