package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.53.3: search health, relevance, Google News links and the reader fallback (WebFix). */
class WebFixTest {
    @Test fun offTopicResultsAreDropped() {
        val q = "official benchmarks for Dots 3 Note Preview model"
        val list = "1. Rednote Releases First Open-Weight Model in the dots series\n   https://finance.yahoo.com/x\n   s\n" +
            "2. DOTS | Home\n   https://dots.com\n   home page\n"
        val r = WebFix.relevant(q, list)!!
        assertTrue(r.startsWith("1. Rednote")); assertFalse(r.contains("DOTS | Home"))
        assertNull(WebFix.relevant("dots.llm1 benchmarks", "1. Introducing dots - OpenAI\n   https://openai.com\n   s\n"))
    }

    @Test fun blockedSourceCoolsDown() {
        var t = 0L
        val h = WebFix.Health { t }
        h.blocked("DDG"); assertTrue(h.cooling("DDG"))
        assertEquals(listOf("BING"), h.usable(listOf("DDG", "BING")) { it })
        t += WebFix.Health.BASE_MS + 1; assertFalse(h.cooling("DDG"))
        h.blocked("DDG"); h.blocked("BING"); assertEquals(listOf("DDG", "BING"), h.usable(listOf("DDG", "BING")) { it })
        assertTrue(WebFix.isBlockNote("Error: DuckDuckGo HTTP 202")); assertFalse(WebFix.isBlockNote("Bing: no results"))
    }

    @Test fun googleNewsLinks() {
        assertEquals("CBMabc_-1", WebFix.gnId("https://news.google.com/rss/articles/CBMabc_-1?oc=5"))
        assertNull(WebFix.gnId("https://example.com/rss/articles/x"))
        assertEquals("SG" to "123", WebFix.gnParams("<c-wiz data-n-a-sg=\"SG\" data-n-a-ts=\"123\">"))
        assertEquals("https://site.com/a", WebFix.gnParse(")]}'\n\n[[\"wrb.fr\",\"Fbv4je\",\"[\\\"garturlres\\\",\\\"https://site.com/a\\\",1]\",null,null,null,\"generic\"]]"))
    }

    @Test fun readerOnlyForRealFailures() {
        assertTrue(WebFix.readerWorth("Error: couldn't open https://x (timeout)"))
        assertTrue(WebFix.readerWorth("Error: HTTP 403 from https://x"))
        assertFalse(WebFix.readerWorth("Error: HTTP 404 from https://x"))
        assertFalse(WebFix.readerWorth("# Title\n" + "A real paragraph of text that is longer than forty characters.\n".repeat(5)))
    }

    @Test fun nearCopyOfAGoodSearchIsNotRun() {
        val g = SearchGuard()
        assertNull(g.check("dots llm benchmarks", "dots benchmarks"))
        g.done("dots llm benchmarks", ok = true)
        assertTrue(g.check("benchmarks dots llm", "dots benchmarks")!!.startsWith("Not run"))
        val g2 = SearchGuard(); g2.check("dots llm benchmarks", "dots"); g2.done("dots llm benchmarks", ok = false)
        assertNull("a failed search may be tried again", g2.check("dots llm benchmarks", "dots"))
    }
}
