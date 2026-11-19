package com.blaze.assistant.agent


import com.blaze.assistant.BrandName
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One mind's pure logic: dedupe, pinned core, index, the context ladder, extraction parsing (JVM, no Android). */
class MindTest {
    private fun m(id: Long, kind: String, subject: String, text: String, updated: Long = id) = Mind.Mem(id, kind, subject, text, updated = updated)

    @Test fun newestLineWinsAndNoDuplicates() {
        val have = listOf(m(1, "profile", "You", "Lives in Ndola, Zambia"), m(2, "person", "Gloria", "Gloria is the user's cousin"))
        // Identical (case and spacing aside): no-op.
        assertEquals(Mind.Decision.Same(1), Mind.decide(have, "profile", "you", "lives in  Ndola, zambia"))
        // Same kind + subject and enough overlap: rewrite that row.
        assertEquals(Mind.Decision.Update(1), Mind.decide(have, "profile", "You", "Lives in Lusaka, Zambia"))
        // Different subject: a new row.
        assertEquals(Mind.Decision.Insert, Mind.decide(have, "person", "Mutale", "Mutale is the user's lecturer"))
        // Same subject but a different fact: a new row.
        assertEquals(Mind.Decision.Insert, Mind.decide(have, "person", "Gloria", "Birthday on 3 March"))
        // The identical line under another subject is still a duplicate.
        assertEquals(Mind.Decision.Same(2), Mind.decide(have, "fact", "People", "Gloria is the user's cousin"))
    }

    @Test fun conditionsAndInferenceAreKeptExactly() {
        assertEquals("Prefers window seats on work trips", Mind.clean("  Prefers window seats   on work trips. "))
        assertEquals("Likes spicy food (inferred)", Mind.clean("Likes spicy food", inferred = true))
        assertEquals("Likes spicy food (inferred)", Mind.clean("Likes spicy food (inferred)", inferred = true))
        // "(inferred)" doesn't make two lines look different to the dedupe.
        assertTrue(Mind.jaccard("Likes spicy food (inferred)", "Likes spicy food") >= Mind.SAME)
        assertEquals("fact", Mind.normKind("nonsense"))
        assertEquals("preference", Mind.normKind(" Preference "))
    }

    @Test fun coreHoldsProfileThenPreferencesAndIndexCountsSubjects() {
        val mems = listOf(
            m(1, "preference", "food", "No pork, on weekdays and weekends"),
            m(2, "profile", "You", "Studies at Northrise University"),
            m(3, "person", "Gloria", "Cousin"), m(4, "person", "Gloria", "Lives in Chingola"), m(5, "person", "Mutale", "Lecturer"),
            m(6, "project", "${BrandName.NAME}", "One mind memory in progress"),
        )
        val core = Mind.core(mems)
        assertTrue(core.indexOf("Northrise") < core.indexOf("No pork"))
        assertFalse(core.contains("Gloria"))
        val idx = Mind.index(mems)
        assertTrue(idx, idx.contains("- person: Gloria (2), Mutale (1)"))
        assertTrue(idx.contains("- project: ${BrandName.NAME} (1)"))
        val block = Mind.block(mems)
        assertTrue(block.contains("ONE MIND") && block.contains("WHAT YOU KNOW") && block.contains("MEMORY INDEX"))
        // The core never passes its cap.
        val many = (1..200L).map { m(it, "profile", "You", "Profile line number $it with some words to make it longer") }
        assertTrue(Mind.core(many).length <= Mind.CORE_MAX)
    }

    @Test fun ladderThresholds() {
        val budget = LongChat.budget(32_768)   // 60% of 32k = 19,660 > 12k → 12k
        assertEquals(12_000, budget)
        assertEquals(Mind.Rung.RECENT, Mind.rung(12_000, budget, false, 0, 0, 32_768))
        assertEquals(Mind.Rung.SUMMARY, Mind.rung(12_001, budget, true, 800, 5_000, 32_768))
        // Summary + tail past 90% of the window: lookup.
        assertEquals(Mind.Rung.LOOKUP, Mind.rung(40_000, budget, true, 800, 29_000, 32_768))
        // Over budget with no summary (summarising failed) but within 60% of the window: still word for word (2026-11-18).
        assertEquals(Mind.Rung.RECENT, Mind.rung(12_001, budget, false, 0, 1_000, 32_768))
        // Past 60% of the window with no summary: lookup.
        assertEquals(Mind.Rung.LOOKUP, Mind.rung(19_700, budget, false, 0, 1_000, 32_768))
        // Small window: the budget is 60% of it.
        assertEquals(4_915, LongChat.budget(8_192))
        assertEquals(Mind.Rung.RECENT, Mind.rung(4_900, LongChat.budget(8_192), false, 0, 0, 8_192))
        assertEquals(Mind.Rung.LOOKUP, Mind.rung(9_000, LongChat.budget(8_192), true, 800, 7_000, 8_192))
        // Unknown window counts as 32k.
        assertEquals(Mind.Rung.SUMMARY, Mind.rung(20_000, 12_000, true, 800, 20_000, 0))
        assertEquals(Mind.Rung.RECENT, Mind.rung(19_000, 6_000, false, 0, 0, 0))
        assertEquals(Mind.Rung.LOOKUP, Mind.rung(20_000, 6_000, false, 0, 0, 0))
    }

    @Test fun aPdfInAShortChatStaysInThePrompt() {
        // 2026-11-18 (owner screenshot): a 9-page PDF (~7.2k tokens of page images) over the 6k ChatTier budget, no summary
        // yet, on a 128k model: the chat stays word for word instead of dropping to lookup and losing the PDF.
        assertEquals(Mind.Rung.RECENT, Mind.rung(7_600, 6_000, false, 0, 7_600, 128_000))
        assertEquals(Mind.Rung.RECENT, Mind.rung(70_000, 6_000, false, 0, 1_000, 128_000))
        assertEquals(Mind.Rung.LOOKUP, Mind.rung(80_000, 6_000, false, 0, 1_000, 128_000))
        // Under pressure (the whole request nears the window) there is no word-for-word escape.
        assertEquals(Mind.Rung.LOOKUP, Mind.rung(7_600, 6_000, false, 0, 7_600, 128_000, pressure = true))
    }

    @Test fun pressureUsesTheSummaryUnderBudget() {
        // v0.26.0: the history alone is under the budget, but the whole request nears the window.
        assertEquals(Mind.Rung.RECENT, Mind.rung(8_000, 12_000, true, 800, 3_000, 16_384))
        assertEquals(Mind.Rung.SUMMARY, Mind.rung(8_000, 12_000, true, 800, 3_000, 16_384, pressure = true))
        // No summary yet: nothing to switch to.
        assertEquals(Mind.Rung.RECENT, Mind.rung(8_000, 12_000, false, 0, 3_000, 16_384, pressure = true))
    }

    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    @Test fun lookupTailStartsAtAUserMessage() {
        val h = JSONArray()
        for (n in 0 until 20) { h.put(msg("user", "q$n")); h.put(msg("assistant", "a$n")) }
        val s = Mind.lookupStart(h)
        assertEquals("user", h.getJSONObject(s).getString("role"))
        // The tail holds the last LOOKUP_USER_TURNS user turns.
        assertEquals(Mind.LOOKUP_USER_TURNS * 2, h.length() - s)
        // A long tool loop after the last user message: the tail goes back to that user message.
        val t = JSONArray().put(msg("user", "do the thing"))
        for (n in 0 until 10) t.put(JSONObject().put("role", "tool").put("content", "r$n"))
        assertEquals(0, Mind.lookupStart(t))
        // Never before the context window's start.
        assertTrue(Mind.lookupStart(h, 38) >= 38)
        assertEquals(38, Mind.lookupStart(h, 38))
    }

    @Test fun lookupTailKeepsThreeUserTurnsPastAToolLoop() {
        val h = JSONArray()
        for (n in 0 until 5) { h.put(msg("user", "q$n")); h.put(msg("assistant", "a$n")) }
        h.put(msg("user", "make flashcards"))
        for (n in 0 until 12) h.put(JSONObject().put("role", "tool").put("content", "r$n"))
        h.put(msg("assistant", "done"))
        h.put(msg("user", "what was the first topic"))
        val s = Mind.lookupStart(h)
        val users = (s until h.length()).count { h.getJSONObject(it).getString("role") == "user" }
        assertEquals(Mind.LOOKUP_USER_TURNS, users)
        assertEquals("q4", h.getJSONObject(s).getString("content"))
    }

    @Test fun extractionParsesTolerantlyAndDropsSecrets() {
        val raw = "Sure!\n```json\n[{\"kind\":\"preference\",\"subject\":\"food\",\"text\":\"No pork on weekdays\",\"related\":\"You\"}," +
            "{\"kind\":\"weird\",\"subject\":\"${BrandName.NAME}\",\"text\":\"Ships v0.18 next\"}," +
            "{\"kind\":\"fact\",\"subject\":\"bank\",\"text\":\"Card 4111 1111 1111 1111\"}]\n```"
        val items = Mind.parseExtract(raw)
        assertEquals(2, items.size)
        assertEquals(Mind.Item("preference", "food", "No pork on weekdays", "You"), items[0])
        assertEquals("fact", items[1].kind)
        assertTrue(Mind.parseExtract("nothing here").isEmpty())
        assertTrue(Mind.parseExtract("[]").isEmpty())
    }

    @Test fun chunksFitTheContext() {
        val turns = (1..100L).map { Mind.Turn(it, "c", it.toInt(), "user", "x".repeat(500)) }
        val c = Mind.chunk(turns, 12_000)
        assertTrue(c.isNotEmpty() && c.size < turns.size)
        assertTrue(Mind.render(c).length <= 12_000 + c.size * 20)
        // One huge turn still makes a chunk of one.
        assertEquals(1, Mind.chunk(listOf(Mind.Turn(1, "c", 0, "user", "y".repeat(50_000))), 12_000).size)
    }

    @Test fun oldFactsGetAKind() {
        assertEquals("person", Mind.guessKind("Sister is Mary, lives in Ndola").first)
        assertEquals("Mary", Mind.guessKind("Sister is Mary, lives in Ndola").second)
        assertEquals("preference", Mind.guessKind("Prefers short answers").first)
        assertEquals("profile", Mind.guessKind("Studies computer science at Northrise").first)
        assertEquals("fact", Mind.guessKind("Owns a Tecno Spark 10").first)
    }

    @Test fun sharedPromptTextMatchesDesktop() {
        assertTrue(Mind.MIND_RULE.startsWith("ONE MIND\n- This is one continuous conversation with the user; there are no separate chats."))
        assertTrue(Mind.LOOKUP_RULE.startsWith("Older turns of this chat aren't in this prompt, to save room; the newest ones are."))
        // Calm, model-first wording: no shouting, names the chat's own read-back tools.
        assertFalse(Mind.LOOKUP_RULE.contains("NOT") || Mind.LOOKUP_RULE.contains("RESET"))
        assertTrue(Mind.LOOKUP_RULE.contains("search_chat") && Mind.LOOKUP_RULE.contains("read_turn"))
        assertTrue(Mind.EXTRACT_PROMPT.endsWith("\"related\":\"other subjects, comma-separated\"}]"))
    }
}
