package com.argus.assistant.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18 Nov 2026 (owner): text assist, five levels of prompt help that keep the user's intent and never send unseen text. */
class TextAssistTest {
    @After fun reset() = TextAssist.set(TextAssist.Level.OFF)

    @Test fun fiveLevelsInOrderOffByDefault() {
        assertEquals(listOf("Off", "Low", "Med", "High", "Full"), TextAssist.Level.entries.map { it.label })
        assertEquals(TextAssist.Level.OFF, TextAssist.Level.of(null))
        assertEquals(TextAssist.Level.OFF, TextAssist.Level.of("turbo"))
        assertEquals(TextAssist.Level.HIGH, TextAssist.Level.of(" High "))
        TextAssist.set(TextAssist.Level.FULL)
        assertEquals(TextAssist.Level.FULL, TextAssist.level.value)
    }

    @Test fun offSendsAsTypedAndADraftIsImprovedOnce() {
        assertFalse(TextAssist.shouldRewrite(TextAssist.Level.OFF, "fix the bug", null))
        assertFalse(TextAssist.shouldRewrite(TextAssist.Level.LOW, "   ", null))
        assertTrue(TextAssist.shouldRewrite(TextAssist.Level.LOW, "fix teh bug", null))
        // once improved, the next tap sends what the box holds (the rewrite, an edit of it, or the text put back by Undo)
        assertFalse(TextAssist.shouldRewrite(TextAssist.Level.FULL, "Fix the bug.", "fix teh bug"))
    }

    @Test fun eachLevelAddsOneLineToOneSharedCore() {
        assertNull(TextAssist.prompt(TextAssist.Level.OFF))
        val prompts = TextAssist.Level.entries.drop(1).map { TextAssist.prompt(it)!! }
        val core = prompts.first().lines().first()
        for (p in prompts) {
            assertEquals(core, p.lines().first())
            assertEquals(2, p.lines().size)
            assertTrue(p.contains("Keep the user's intent"))
        }
        assertEquals(prompts.size, prompts.toSet().size)
        // docs/MODEL-FIRST.md: no shouting orders
        for (p in prompts) for (w in listOf("ALWAYS", "NEVER", "EVERY", "MUST")) assertFalse(w, p.contains(w))
    }

    @Test fun requestCarriesTheMessageAndRecentChat() {
        assertNull(TextAssist.request(TextAssist.Level.OFF, "hi", emptyList()))
        assertNull(TextAssist.request(TextAssist.Level.LOW, " ", emptyList()))
        val r = TextAssist.request(TextAssist.Level.MED, "send it to him", listOf(true to "email Sam the report", false to "Drafted."))!!
        assertEquals(2, r.length())
        assertEquals("system", r.getJSONObject(0).getString("role"))
        val user = r.getJSONObject(1).getString("content")
        assertTrue(user.contains("User: email Sam the report"))
        assertTrue(user.endsWith("Message to improve:\nsend it to him"))
    }

    @Test fun cleanKeepsOnlyAUsableRewrite() {
        assertEquals("Fix the login bug.", TextAssist.clean("<think>x</think>\n\"Fix the login bug.\"", "fix login bug"))
        assertEquals("Fix it.", TextAssist.clean("```\nFix it.\n```", "fix it"))
        assertNull(TextAssist.clean("   ", "fix it"))
        assertNull(TextAssist.clean("fix it", "fix it "))
        assertNotNull(TextAssist.clean("Fix it, please.", "fix it"))
    }
}
