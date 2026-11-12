package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-SH-03 sanitiser: raw tool, model and provider names never reach a thread's step list or notes. */
class StepToolTest {
    private val raw = listOf("bank_balance", "memory_save", "web_search", "fetch_page", "gmail_send_email", "composio_execute",
        "run_plan", "phone_task", "codespace_exec", "send_to_bot", "add_job", "zzz_unknown_tool", "x", "", "GOOGLECALENDAR_LIST_EVENTS")

    @Test fun aToolIsNamedByWhatItDoesNeverByItsName() {
        for (n in raw) {
            val l = StepTool.label(n)
            for (t in listOf(l.now, l.done)) {
                assertTrue("$n → $t", t.isNotBlank())
                assertFalse("$n leaks into '$t'", n.isNotBlank() && t.contains(n, ignoreCase = true))
                assertFalse("snake_case in '$t'", Regex("[a-z]+_[a-z]").containsMatchIn(t))
                assertEquals("'$t' passes the pill's own check", t, StepPill.clean(t))
            }
        }
    }

    @Test fun knownToolsAndWordRules() {
        assertEquals("Searching the web", StepTool.label("web_search").now)
        assertEquals("Read a page", StepTool.label("fetch_page").done)
        assertEquals(StepTool.Kind.MEMORY, StepTool.label("memory_save").kind)
        assertEquals(StepTool.Kind.READ, StepTool.label("bank_balance").kind)
        assertEquals(StepTool.Kind.EDIT, StepTool.label("gmail_send_email").kind)
        assertEquals(StepTool.Kind.CLOUD, StepTool.label("codespace_exec").kind)
        assertEquals(StepTool.Kind.TEAM, StepTool.label("send_to_bot").kind)
        assertEquals(StepTool.Kind.JOBS, StepTool.label("add_job").kind)
        assertEquals(StepTool.Kind.READ, StepTool.label("GOOGLECALENDAR_LIST_EVENTS").kind)
        assertEquals("Working", StepTool.label("zzz_unknown_tool").now)
        assertEquals("specialties stay general", StepPill.SPECIALIST_IN, StepTool.label("use_skill").now)
    }

    @Test fun freeTextAndNoticesAreChecked() {
        assertEquals("Working", StepTool.line("Working on bank_balance", "Working"))
        assertEquals("Working", StepTool.line("Asking claude-3 via openrouter", "Working"))
        assertEquals("Reading the thread", StepTool.line("Reading the thread.", "Working"))
        assertEquals("Something went wrong on this turn", StepTool.notice("Groq rate limit reached, switching model"))
        assertEquals("The page didn't load", StepTool.notice(" The page didn't load "))
        assertTrue(StepPill.mentionsRoute("via OpenRouter")); assertFalse(StepPill.mentionsRoute("Booked for Friday"))
    }
}
