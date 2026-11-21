package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-21 view shelf (docs/VIEW-SHELF.md): every view, one line each, every turn; the model picks. */
class ViewShelfTest {

    private val on = ViewShelf.promptBlock(stageOn = true)
    private val off = ViewShelf.promptBlock(stageOn = false)

    @Test fun everyViewHasOneLineWithTheToolThatWritesThere() {
        for (s in ViewShelf.specs) {
            val line = ViewShelf.line(s)
            assertTrue(s.id, on.contains(line))
            assertTrue("${s.id} names a writing tool", line.endsWith(" \u2014 ${ViewShelf.writer(s)}\n"))
            assertTrue("${s.id}'s tool is one of its own", ViewShelf.writer(s) in s.tools)
            assertFalse("${s.id} is one line", line.trimEnd('\n').contains('\n'))
        }
        assertEquals(ViewShelf.specs.size, on.lines().count { it.startsWith("- ") })
        assertEquals("open_view only opens, so it is never the named tool", 0, ViewShelf.specs.count { ViewShelf.writer(it) == "open_view" })
        assertEquals(ViewShelf.specs.map { it.id }.distinct(), ViewShelf.specs.map { it.id })
    }

    @Test fun theStageLineFollowsTheStageSwitch() {
        assertTrue(on.contains("- stage \u2014"))
        assertFalse(off.contains("- stage \u2014"))
        assertEquals(on.lines().size - 1, off.lines().size)
    }

    @Test fun byteStableSmallAndCalm() {
        // The same switch gives the same bytes, so it stays in the cached prefix (PromptCacheBenchTest).
        assertEquals(on, ViewShelf.promptBlock(stageOn = true))
        assertTrue("${on.length} chars", on.length < ViewShelf.BUDGET_CHARS)
        // No clock, no date, nothing from the message.
        assertFalse(Regex("\\b(20\\d\\d|\\d{1,2}:\\d\\d)\\b").containsMatchIn(on))
        // MODEL-FIRST: no shouting, no absolutes, no per-turn orders.
        for (w in listOf("HARD RULE", "REQUIRED", "ALWAYS", "NEVER", "MUST", "Never ", "Do NOT")) assertFalse(w, on.contains(w))
        // Plain answers stay in chat: the shelf says so, so a question about flashcards isn't turned into a deck.
        assertTrue(on.contains("Plain answers, explanations and a quick diagram or chart in the reply stay in chat"))
        assertTrue(on.contains("open_view opens any view by id"))
    }

    @Test fun archiveIsSearchedNotOpened() {
        val a = ViewShelf.byId("archive")!!
        assertTrue(a.holds.contains("there is no screen") && ViewShelf.writer(a) == "search_chat")
        assertFalse("open_view" in a.tools)
    }

    @Test fun claimGuardStillCoversEveryViewButArchive() {
        // ClaimGuard reads the same specs, so a claim about any view (but read-only Archive) is still checked.
        for (s in ViewShelf.specs) if (s.id != "archive")
            assertTrue(s.id, ClaimGuard.claims("I've updated the ${s.name.lowercase()}.", s.id).isNotEmpty() || s.id in setOf("browser", "memory"))
        // What the model reached for this turn, by tool name: the structural replacement for the routed view.
        assertEquals(setOf("design"), ClaimGuard.viewsTried(listOf("design")))
        assertEquals(setOf("learn"), ClaimGuard.viewsTried(listOf("flashcards", "web_search")))
        assertEquals(setOf("stage"), ClaimGuard.viewsTried(listOf("stage_show")))
        assertEquals(setOf("browser"), ClaimGuard.viewsTried(listOf("browser_open")))
        assertEquals(emptySet<String>(), ClaimGuard.viewsTried(listOf("web_search", "open_view")))
    }
}
