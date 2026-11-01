package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.49.0 (mem1-ui-browse): the browse trigger and the page filter.
 *
 * The report: "certain questions and a couple of other styles automatically make Blaze look like it's online and it
 * fetches pages unrelated to the context". These tests pin both halves of the fix.
 */
class BrowseIntentTest {

    // ---------------------------------------------------------------- the trigger

    @Test fun bareQuestionWordsDoNotBrowse() {
        // Every one of these used to take the lookup lane and go to the web.
        listOf(
            "check the time",
            "tell me something interesting",
            "where am I",
            "is that right?",
            "can you do that again",
            "what is the capital of France",
            "how are you doing today",
            "why did you do that",
            "which one do you prefer",
            "do you like music",
            "are you there",
        ).forEach { assertEquals("should not browse: $it", BrowseIntent.Need.NONE, BrowseIntent.decide(it)) }
    }

    @Test fun explicitSearchVerbBrowses() {
        listOf(
            "search for the best budget laptop",
            "look up the weather in Lusaka",
            "google the nearest pharmacy",
            "find out who makes the Pixel",
            "search the web for cheap flights",
        ).forEach { assertEquals("should browse: $it", BrowseIntent.Need.LIVE_WEB, BrowseIntent.decide(it)) }
    }

    @Test fun liveDataMarkerBrowses() {
        listOf(
            "who won the match last night",
            "current price of bitcoin",
            "any news on the launch",
            "what is the latest version of Kotlin",
            "when is the next SpaceX launch",
            "how much is a ticket to Joburg",
            "exchange rate kwacha to dollar",
            "what is the score",
        ).forEach { assertEquals("should browse: $it", BrowseIntent.Need.LIVE_WEB, BrowseIntent.decide(it)) }
    }

    @Test fun thePhoneNeverBrowses() {
        listOf(
            "remind me to call mom at six",
            "set an alarm for seven",
            "turn on the flashlight",
            "open whatsapp",
            "what is my battery level",
            "how much storage do I have left",
            "what time is it",
            "what is today's date",
            "what is the weather",
            "2 + 2 * 3",
            "tell me a joke",
            "do you remember my sister's name",
        ).forEach { assertEquals("should not browse: $it", BrowseIntent.Need.NONE, BrowseIntent.decide(it)) }
    }

    @Test fun signInAndFormsAreInteractionNotLookup() {
        listOf(
            "book a table for two tonight",
            "buy the cheapest one",
            "sign in to my account",
            "fill in the form for me",
            "order a pizza",
        ).forEach { assertEquals("should be INTERACTION: $it", BrowseIntent.Need.INTERACTION, BrowseIntent.decide(it)) }
    }

    @Test fun aLocalAnswerSuppressesTheLookup() {
        assertEquals(BrowseIntent.Need.NONE, BrowseIntent.decide("what is the latest price of gold", hasLocalAnswer = true))
    }

    @Test fun linksCommandsAndSmallTalkAreNotLookups() {
        listOf(
            "https://example.com/page",
            "/clear",
            "@browser https://example.com",
            "hey",
            "what's up??",
            "thanks",
        ).forEach { assertEquals("should not browse: $it", BrowseIntent.Need.NONE, BrowseIntent.decide(it)) }
    }

    @Test fun isLiveLookupMatchesDecide() {
        assertTrue(BrowseIntent.isLiveLookup("search for cheap flights"))
        assertFalse(BrowseIntent.isLiveLookup("check the time"))
        assertFalse(BrowseIntent.isLiveLookup("book a table for two"))
    }

    // ---------------------------------------------------------------- the query

    @Test fun queryForStripsLeadInsAndPoliteness() {
        assertEquals("best budget laptop", BrowseIntent.queryFor("can you search for the best budget laptop please"))
        assertEquals("weather in Lusaka", BrowseIntent.queryFor("hey blaze, what's the weather in Lusaka?"))
        assertEquals("price of bitcoin", BrowseIntent.queryFor("tell me the price of bitcoin, thanks"))
        // Nothing to strip: the text comes back (minus trailing punctuation).
        assertEquals("bitcoin price", BrowseIntent.queryFor("bitcoin price"))
    }

    @Test fun queryForNeverReturnsEmpty() {
        assertEquals("please", BrowseIntent.queryFor("please"))
    }

    // ---------------------------------------------------------------- the pages

    private fun hit(t: String, u: String, s: String = "") = LookupLane.Hit(t, u, s)

    @Test fun unrelatedHitsAreDropped() {
        val hits = listOf(
            hit("Bitcoin price today", "https://example.com/btc", "BTC trades at ..."),
            hit("How to bake sourdough", "https://recipes.example/bread", "flour, water, salt"),
            hit("Zambia tourism guide", "https://travel.example/zm", "visit the falls"),
        )
        val kept = BrowseIntent.pick(hits, "bitcoin price")
        assertEquals(1, kept.size)
        assertEquals("https://example.com/btc", kept[0].url)
    }

    @Test fun aParaphrasedQueryKeepsTheOriginalList() {
        // No content word overlaps, so dropping everything would leave the lane with nothing to read.
        val hits = listOf(hit("Alpha", "https://a.example/1"), hit("Beta", "https://b.example/2"))
        assertEquals(hits, BrowseIntent.pick(hits, "zzz qqq"))
    }

    @Test fun relevanceLooksAtTitleUrlAndSnippet() {
        assertTrue(BrowseIntent.relevant(hit("Kotlin 2.1 released", "https://kotlinlang.org/x"), "kotlin release"))
        assertTrue(BrowseIntent.relevant(hit("Something", "https://example.com/kotlin-news"), "kotlin release"))
        assertTrue(BrowseIntent.relevant(hit("Something", "https://example.com/x", "all about kotlin"), "kotlin release"))
        assertFalse(BrowseIntent.relevant(hit("Something", "https://example.com/x", "nothing here"), "kotlin release"))
    }

    @Test fun anEmptyQueryKeepsEverything() {
        val hits = listOf(hit("A", "https://a.example/1"))
        assertTrue(BrowseIntent.relevant(hits[0], ""))
        assertEquals(hits, BrowseIntent.pick(hits, ""))
    }
}
