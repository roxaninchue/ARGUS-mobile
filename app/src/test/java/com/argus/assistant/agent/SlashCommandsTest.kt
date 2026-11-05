package com.argus.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.50.0: the composer's "/" menu and Blaze's answer to "what / commands do I have" come from one registry. */
class SlashCommandsTest {
    @org.junit.Before fun drainLeaks() = LeakedMainLaunches.drain()

    @Test fun menuListsEveryCommandOnce() {
        val all = SlashCommands.entries()
        assertEquals(all.size, all.map { it.name }.distinct().size)
        assertTrue(all.any { it.name == "clear" } && all.any { it.name == "translate" } && all.any { it.name == "music" })
        assertEquals(all, SlashCommands.filter("/"))
    }

    @Test fun filtersAsYouType() {
        val f = SlashCommands.filter("/tr")
        assertEquals("translate", f.first().name)
        assertTrue(SlashCommands.filter("/mus").first().name == "music")
        // description matches come after name matches
        assertTrue(SlashCommands.filter("/grammar").any { it.name == "fix" })
        assertTrue(SlashCommands.filter("/translate fr hello").isEmpty())
        assertTrue(SlashCommands.filter("/zzzz").isEmpty())
    }

    @Test fun tapRunsOrInserts() {
        val clear = SlashCommands.entries().first { it.name == "clear" }
        assertTrue(clear.runNow); assertEquals("/clear", clear.fill)
        val tr = SlashCommands.entries().first { it.name == "translate" }
        assertFalse(tr.runNow); assertEquals("/translate ", tr.fill)
    }

    @Test fun menuShowsOnlyWhileTypingTheName() {
        assertTrue(SlashCommands.menuFor("/")); assertTrue(SlashCommands.menuFor("/cl"))
        assertFalse(SlashCommands.menuFor("/clear now")); assertFalse(SlashCommands.menuFor("hello /")); assertFalse(SlashCommands.menuFor("//x"))
    }

    @Test fun answerUsesTheSameList() {
        val a = SlashCommands.answer()
        SlashCommands.entries().forEach { assertTrue(it.name, a.contains("/" + it.name)) }
        assertFalse(a.contains("Windows"))
        assertEquals(SearchRoute.Route.COMMANDS, SearchRoute.route("what \"/\" commands do I have available?"))
    }
}
