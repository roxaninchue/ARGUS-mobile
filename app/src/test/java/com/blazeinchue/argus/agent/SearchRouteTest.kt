package com.blazeinchue.argus.agent


import com.blazeinchue.argus.BrandName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.50.0 (owner, 2026-11-01 16:32: 'what "/" commands do I have available?' searched the web and answered about Windows help
 * pages). No keyword-triggered web search: questions go to the model, questions about Argus / the chat / the user's own data
 * stay local, weather goes to the weather tool, and the quick lane runs only on an explicit "search…" request.
 */
class SearchRouteTest {
    @org.junit.Before fun drainLeaks() = LeakedMainLaunches.drain()

    @Test fun slashCommandsQuestionNeverSearches() {
        listOf("what \"/\" commands do I have available?", "what / commands do I have", "What slash commands are there?",
            "which commands can I use here?", "list the / commands", "what commands do you have?", "/help").forEach {
            assertEquals(it, SearchRoute.Route.COMMANDS, SearchRoute.route(it))
            assertFalse(it, LookupLane.isLookup(it))
            assertNotNull(it, SearchRoute.webNote(it))
        }
    }

    @Test fun weatherGoesToTheWeatherTool() {
        assertEquals(SearchRoute.Route.WEATHER, SearchRoute.route("what's the weather in Ndola"))
        assertEquals(SearchRoute.Route.WEATHER, SearchRoute.route("will it rain tomorrow?"))
        assertFalse(LookupLane.isLookup("what's the weather in Ndola"))
        assertNull(SearchRoute.webNote("what's the weather in Ndola"))
        // the weather tool is in the turn's tools
        assertTrue(ToolPick.ESSENTIAL.contains("get_weather"))
    }

    @Test fun questionWordsAloneNoLongerSearch() {
        // the 0.46.3 lane took all of these to the web because they start with a question word
        listOf("What is the capital of Zambia?", "how does a heat pump work", "who wrote Things Fall Apart", "is it worth learning Rust",
            "can I freeze cooked rice", "why is the sky blue").forEach {
            assertTrue(it, LookupLane.questionShaped(it))
            assertFalse(it, LookupLane.isLookup(it))
            assertEquals(it, SearchRoute.Route.MODEL, SearchRoute.route(it))
            assertNull(it, SearchRoute.webNote(it))   // the model may still search when it decides it needs to
        }
    }

    @Test fun explicitSearchStillTakesTheQuickLane() {
        listOf("search the web for the price of a Raspberry Pi 5", "google Kafue bridge latest news", "look up the opening hours of the national museum",
            "Please search for flights from Lusaka to Ndola").forEach {
            assertEquals(it, SearchRoute.Route.SEARCH, SearchRoute.route(it))
            assertTrue(it, LookupLane.isLookup(it))
        }
        assertEquals("the price of a Raspberry Pi 5", SearchRoute.explicitTopic("search the web for the price of a Raspberry Pi 5"))
    }

    @Test fun aboutArgusTheChatAndMyDataStayLocal() {
        listOf("what can you do?", "which features does ${BrandName.NAME} have", "how do I turn on Halo in ${BrandName.NAME} settings", "what did I say earlier about the trip",
            "what's on my calendar today", "how many photos do I have", "what version are you", "do I have any alarms set?",
            "what skills do you have").forEach {
            assertEquals(it, SearchRoute.Route.LOCAL, SearchRoute.route(it))
            assertFalse(it, LookupLane.isLookup(it))
            assertNotNull(it, SearchRoute.webNote(it))
        }
        // a task that mixes the user's data with the outside world still searches
        assertNull(SearchRoute.webNote("check my calendar and then find flights to Ndola for that weekend"))
        // scheduled routines and bare go-aheads are never refused
        assertNull(SearchRoute.webNote("[Scheduled routine: Morning brief] weather, news and my calendar"))
        assertNull(SearchRoute.webNote("yes"))
    }

    @Test fun smallTalkIsChat() {
        listOf("hi!", "can't sleep honestly", "thanks").forEach { assertFalse(it, LookupLane.isLookup(it)) }
        assertEquals(SearchRoute.Route.CHAT, SearchRoute.route("hi!"))
    }

    @Test fun queryIsBuiltFromTheConversation() {
        val recent = listOf("I'm thinking of buying a Raspberry Pi 5 for a home server", "Good choice. The 8 GB model is plenty for that.")
        val q = SearchRoute.query("search how much it costs in Zambia", recent)
        assertTrue(q, q.contains("Zambia"))
        assertTrue(q, q.contains("Raspberry"))
        assertFalse(q, q.lowercase().startsWith("search"))
        // a self-contained request keeps its own words only
        assertEquals("price Raspberry Pi", SearchRoute.query("search the web for the price of a Raspberry Pi", recent))
        assertTrue(SearchRoute.query("google it", listOf("Kafue bridge collapse")).contains("Kafue"))
    }
}
