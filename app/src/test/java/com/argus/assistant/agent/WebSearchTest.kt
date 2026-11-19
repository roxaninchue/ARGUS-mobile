package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.32.0: a bot check is a block, not "no results"; Bing RSS parses. */
class WebSearchTest {
    @Test fun botCheckIsBlocked() {
        assertTrue(Web.blocked("<div class=\"anomaly-modal__title\">Unfortunately, bots use DuckDuckGo too.</div><form id=\"challenge-form\">"))
        assertFalse(Web.blocked("<div class=\"result__body\">x</div>"))
    }
    @Test fun bingRss() {
        val xml = "<rss><channel><title>Bing</title><link>http://www.bing.com:80/search?q=x</link>" +
            "<item><title>morluto/rea: Reverse engineer anything</title><link>https://github.com/morluto/rea</link><description>REA connects your agent</description></item>" +
            "<item><title>REA</title><link>https://rea.tools/</link><description>Find out how software works.</description></item></channel></rss>"
        val r = Web.parseRss(xml, 6)!!
        assertTrue(r.startsWith("1. morluto/rea"))
        assertTrue(r.contains("https://rea.tools/"))
        assertEquals(null, Web.parseRss("<rss></rss>", 6))
    }

    // 0.53.0: the phone's 'No results for "latest news NVIDIA CEO" on DuckDuckGo or Bing'
    @Test fun ddgRateLimitPageIsABlock() {
        assertTrue(Web.ddgBlocked(202, "<html><body><div id=\"links\"></div></body></html>"))
        assertTrue(Web.ddgBlocked(200, "<html><body>loading</body></html>"))
        assertFalse(Web.ddgBlocked(200, "<div class=\"no-results\">No results.</div>"))
        assertFalse(Web.ddgBlocked(200, "<div class=\"result__body\">x</div>"))
    }

    @Test fun bingHtmlInsteadOfAFeedIsABlock() {
        assertFalse(Web.isRss("<!DOCTYPE html><html><head><title>Bing</title>"))
        assertTrue(Web.isRss("<?xml version=\"1.0\" encoding=\"utf-8\" ?><rss version=\"2.0\">"))
    }

    @Test fun bingNewsLinksAreUnwrappedAndDated() {
        val xml = "<?xml version=\"1.0\"?><rss><channel><item><title>Nvidia CEO resigns</title>" +
            "<link>http://www.bing.com/news/apiclick.aspx?ref=FexRss&amp;aid=&amp;url=https%3a%2f%2fwww.cbsnews.com%2fnews%2fnvidia-ceo%2f&amp;c=1&amp;mkt=en-us</link>" +
            "<description>The board announced Thursday.</description><pubDate>Thu, 12 Nov 2026 14:28:00 GMT</pubDate><News:Source>CBS News</News:Source></item></channel></rss>"
        val r = Web.parseRss(xml, 6)!!
        assertTrue(r, r.contains("\n   https://www.cbsnews.com/news/nvidia-ceo/\n"))
        assertTrue(r, r.contains("(12 Nov 2026, CBS News) The board announced Thursday."))
        assertEquals(3, r.trimEnd().lines().size)   // "N. title / url / snippet": the shape LookupLane.hitsOf and SiteResolver read
    }

    @Test fun googleNewsItems() {
        val xml = "<?xml version=\"1.0\"?><rss><channel><item><title>Trump calls Huang saga sad - CNBC</title><link>https://news.google.com/rss/articles/CBMi?oc=5</link>" +
            "<pubDate>Fri, 13 Nov 2026 07:49:00 GMT</pubDate><description>&lt;a href=\"x\"&gt;Trump calls Huang saga sad&lt;/a&gt;&amp;nbsp;&lt;font&gt;CNBC&lt;/font&gt;</description>" +
            "<source url=\"https://www.cnbc.com\">CNBC</source></item></channel></rss>"
        val r = Web.parseRss(xml, 6)!!
        assertTrue(r, r.startsWith("1. Trump calls Huang saga sad - CNBC\n   https://news.google.com/rss/articles/CBMi?oc=5\n   (13 Nov 2026, CNBC)"))
    }

    @Test fun wikipediaSearch() {
        val json = "{\"query\":{\"search\":[{\"title\":\"Jensen Huang\",\"snippet\":\"co-founded <span class=\\\"searchmatch\\\">Nvidia</span>\"}]}}"
        val r = Web.parseWiki(json, 5)!!
        assertTrue(r, r.startsWith("1. Jensen Huang (Wikipedia)\n   https://en.wikipedia.org/wiki/Jensen_Huang\n   co-founded Nvidia"))
        assertNull(Web.parseWiki("{\"query\":{\"search\":[]}}", 5))
    }
}
