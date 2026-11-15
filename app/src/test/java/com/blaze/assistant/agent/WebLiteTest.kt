package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.53.6: lite.duckduckgo.com's page (markup as served 15 Nov 2026) reads as results; ads and empty pages don't. */
class WebLiteTest {
    private val page = """
        <table border="0">
        <tr><td valign="top">1.&nbsp;</td><td>
          <a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.tripadvisor.com%2FRestaurants%2Dg479764%2DNdola_Copperbelt_Province.html&amp;rut=ba24" class='result-link'>THE 10 BEST Restaurants in Ndola (Updated November 2026)</a>
        </td></tr>
        <tr><td>&nbsp;&nbsp;&nbsp;</td>
          <td class='result-snippet'>
            Best Dining in <b>Ndola</b>, Copperbelt Province: See 245 Tripadvisor traveler reviews of 31 <b>Ndola</b> <b>restaurants</b>.
          </td></tr>
        <tr><td>&nbsp;&nbsp;&nbsp;</td><td><span class='link-text'>www.tripadvisor.com/Restaurants-g479764</span></td></tr>
        <tr><td valign="top">2.&nbsp;</td><td>
          <a rel="nofollow" href="https://duckduckgo.com/y.js?ad_domain=x.com&amp;u3=1" class='result-link'>Sponsored thing</a>
        </td></tr>
        <tr><td valign="top">3.&nbsp;</td><td>
          <a rel="nofollow" href="https://africalistings.com/ndola/restaurants" class='result-link'>List of Restaurants in Ndola - africalistings.com</a>
        </td></tr>
        </table>"""

    @Test fun liteResultsParse() {
        val r = Web.parseDdgLite(page, 6)!!
        val lines = r.trim().lines()
        assertEquals("1. THE 10 BEST Restaurants in Ndola (Updated November 2026)", lines[0])
        assertEquals("   https://www.tripadvisor.com/Restaurants-g479764-Ndola_Copperbelt_Province.html", lines[1])
        assertTrue(lines[2], lines[2].contains("See 245 Tripadvisor traveler reviews of 31 Ndola restaurants."))
        assertTrue(r.contains("2. List of Restaurants in Ndola - africalistings.com\n   https://africalistings.com/ndola/restaurants"))
        assertTrue(!r.contains("Sponsored"))
        assertNull(Web.parseDdgLite("<html>No results.</html>", 6))
    }

    @Test fun liteIsInTheChainAndTheGivingUpLineSaysWhatToDoNext() {
        assertTrue(SearchChain.Source.DDG_LITE in SearchChain.order(false))
        assertEquals(SearchChain.Source.DDG_LITE, SearchChain.order(false)[2])
        val src = listOf(java.io.File("src/main/java/com/blaze/assistant/agent/Web.kt"), java.io.File("app/src/main/java/com/blaze/assistant/agent/Web.kt")).first { it.exists() }.readText()
        assertTrue(!src.contains("Try different words before concluding it doesn't exist."))
        assertTrue(src.contains("At most one retry with different words"))
    }
}
