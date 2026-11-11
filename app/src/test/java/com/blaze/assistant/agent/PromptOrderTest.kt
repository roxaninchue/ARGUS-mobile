package com.blaze.assistant.agent

import com.blaze.assistant.team.Souls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-AG-03: a bot's prompt is cache-stable — memory above nothing clock-dependent, `Now:` last (05 §1.2). */
class PromptOrderTest {
    private val soul = Souls.promptText(Souls.template(Souls.Profile("atlas", "Atlas", "Travel", "travel", false)))

    private fun prompt(now: String, relevant: String = "RELEVANT NOTES\n- trip 14-20 Dec\n") = PromptParts.BotPrompt(
        core = "ARGUS CORE RULES", pinned = "ABOUT THE USER\n- Lives in Lusaka", soul = soul, playbook = "TRAVEL PLAYBOOK", skills = "SKILLS: flights",
        relevant = relevant, delegation = "FROM YOUR BRIEF\n- budget", chatTier = "SUMMARY so far", cacheTocs = "DOCS: itinerary.pdf",
        context = "TEAM: ARGUS, Ledger", now = now)

    @Test fun orderIsCoreMemorySoulThenTurnThenNow() {
        val parts = PromptParts.forBot(prompt("Now: Tue 11 Nov 2026 22:50 CAT"))
        assertEquals(listOf("core", "pinned", "soul", "playbook", "skills", "relevant", "delegation", "chat_tier", "cache_tocs", "context", "now"), parts.map { it.name })
        assertEquals("now", parts.last().name)
        assertTrue(PromptParts.assemble(parts).trimEnd().endsWith("Now: Tue 11 Nov 2026 22:50 CAT"))
    }

    @Test fun noClockAboveMemory() {
        val parts = PromptParts.forBot(prompt("Now: Tue 11 Nov 2026 22:50 CAT"))
        assertEquals(emptyList<String>(), PromptParts.clockAbove(parts))
        val bad = PromptParts.forBot(prompt("Now: x").copy(context = "It is 22:50 now"))
        assertEquals(listOf("context"), PromptParts.clockAbove(bad))
        assertEquals(listOf("pinned"), PromptParts.clockAbove(PromptParts.forBot(prompt("").copy(pinned = "Now: Monday 3"))))
    }

    @Test fun twoTurnsShareTheWholeHeadAcrossTheClock() {
        val a = PromptParts.assemble(PromptParts.forBot(prompt("Now: Tue 11 Nov 2026 22:50 CAT")))
        val b = PromptParts.assemble(PromptParts.forBot(prompt("Now: Tue 11 Nov 2026 22:51 CAT")))
        val head = PromptParts.head(PromptParts.forBot(prompt("x")))
        assertTrue(PromptParts.commonPrefix(a, b) >= head.length)
        // a new message changes only the TURN part onward; the SLOW head (core, pinned, soul…) is byte-identical
        val c = PromptParts.assemble(PromptParts.forBot(prompt("Now: Tue 11 Nov 2026 22:50 CAT", relevant = "RELEVANT NOTES\n- other\n")))
        val slow = PromptParts.forBot(prompt("x")).takeWhile { it.tier.ordinal <= PromptParts.Tier.SLOW.ordinal }.joinToString("") { it.text }
        assertTrue(PromptParts.commonPrefix(a, c) >= slow.length)
    }

    @Test fun emptyPiecesAreDropped() {
        val parts = PromptParts.forBot(PromptParts.BotPrompt(soul = "S", now = "Now: x"))
        assertEquals(listOf("soul", "now"), parts.map { it.name })
        assertEquals("S\nNow: x\n", PromptParts.assemble(parts))
    }
}
