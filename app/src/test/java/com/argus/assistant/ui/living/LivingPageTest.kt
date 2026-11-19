package com.blaze.assistant.ui.living

import com.blaze.assistant.ui.living.LivingPage.ShotRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Living UI 0.2.0: what a browser card shows of a page and which picture it takes (LivingPage). */
class LivingPageTest {
    @Test fun toolWordsNeverReachTheCard() {
        val (title, body) = LivingPage.read("Opened Google Maps (https://www.google.com/maps).\nScroll: 0%\n12. [button] Search\nFind places near you, with live traffic.")
        assertEquals("Google Maps", title)
        assertEquals("Find places near you, with live traffic.", body)
        assertFalse(body.contains("https://"))
        // no tool header: the first short line is the title and is not repeated in the words
        assertEquals("Gatus" to "Automated developer-oriented status page", LivingPage.read("Gatus\nAutomated developer-oriented status page"))
    }

    @Test fun mastheadSiteAndLead() {
        assertEquals("gsmarena.com", LivingPage.site("https://m.gsmarena.com/x.php"))
        assertEquals("gsmarena.com", LivingPage.site("https://www.gsmarena.com/"))
        assertEquals("A long enough first sentence of the page.", LivingPage.lead("Short\nA long enough first sentence of the page."))
    }

    @Test fun pickTakesTheNewestMatchingShotInTheCardsWindow() {
        val shots = listOf(ShotRef("https://a.com/", 100), ShotRef("https://b.com/x", 2_000), ShotRef("https://a.com/", 2_500), ShotRef("https://c.com", 9_000))
        assertEquals(2, LivingPage.pick(shots, "https://a.com", 1_000, 5_000))
        assertEquals(1, LivingPage.pick(shots, "https://b.com/x", 1_000, 5_000))
        // nothing in the window: no picture
        assertNull(LivingPage.pick(shots, "https://a.com", 5_000, 8_000))
        // open-ended window (the newest page card)
        assertEquals(3, LivingPage.pick(shots, "https://c.com/", 8_000, null))
        assertTrue(LivingPage.blank(intArrayOf(5, 5, 5)) && !LivingPage.blank(intArrayOf(5, 6)))
    }
}
