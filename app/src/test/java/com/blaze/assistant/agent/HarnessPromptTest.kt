package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessPromptTest {
    @Test fun workFirstAndNoLengthCap() {
        val c = HarnessPrompt.CORE
        assertTrue(c.contains("WORK FIRST, THEN ANSWER"))
        assertTrue(c.contains("load_tools"))
        // the stacked "one or two sentences" caps that made replies skip the work are gone
        assertFalse(c.contains("One or two sentences", ignoreCase = true))
        assertFalse(c.contains("only when the answer needs fresh"))
    }

    @Test fun noPhoneControlInThePrompt() {
        // 0.55.18: phone control left the app; the block names no screen tool and says the screen can't be used.
        val p = HarnessPrompt.phone(false)
        for (t in listOf("run_plan", "read_screen", "look_at_screen", "type_text", "do_actions", "press_button")) assertFalse(t, p.contains(t))
        assertTrue(p.contains("can't see, read or tap"))
        assertTrue(p.contains("run_shell"))
        assertFalse(HarnessPrompt.phone(true).contains("run_shell"))
    }

    private val names = listOf("blaze-voice", "blaze-task-workflow", "blaze-views", "blaze-specialists", "blaze-workflow-builder", "blaze-coding-workflow")   // CoreSkills.NAMES

    @Test fun onlyTheCodingSkillStaysPinned() {
        assertEquals(listOf("blaze-coding-workflow"), HarnessPrompt.pinned(names))
        assertEquals(emptyList<String>(), HarnessPrompt.pinned(names - "blaze-coding-workflow"))
    }

    @Test fun leanerThanTheStackItReplaces() {
        // CORE + phone block replaced ~16k chars of stable rules (talk, self, remember, again, ask, control, pim, load_tools,
        // weather, phone_save, leak, verify, ids, know, search, calls, autonomy, yes) plus ~6k of pinned core skills.
        assertTrue(HarnessPrompt.CORE.length + HarnessPrompt.phone(false).length < 7_500)
    }
}
