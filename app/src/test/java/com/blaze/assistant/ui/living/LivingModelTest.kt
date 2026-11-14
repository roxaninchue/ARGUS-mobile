package com.blaze.assistant.ui.living

import com.blaze.assistant.agent.ToolTrail
import com.blaze.assistant.ui.living.LivingModel.Kind
import com.blaze.assistant.ui.living.LivingModel.Msg
import com.blaze.assistant.ui.living.LivingModel.Role
import com.blaze.assistant.ui.living.LivingModel.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Living UI: chat messages → canvas cards (LivingModel). */
class LivingModelTest {
    private fun u(id: Long, t: String) = Msg(id, Role.USER, t, at = id * 10)
    private fun a(id: Long, t: String, ack: Boolean = false) = Msg(id, Role.BLAZE, t, at = id * 10, ack = ack)
    private fun s(id: Long, meta: String, text: String = "Working") = Msg(id, Role.STEP, text, meta, at = id * 10)
    private fun r(exact: String, out: String, at: Long) = ToolTrail.Result(exact, out, ToolTrail.failed(out), at)

    @Test fun argsParseFriendlyExact() {
        assertEquals(mapOf("query" to "gatus vs uptime kuma", "n" to "5"), LivingModel.args("web_search(query=gatus vs uptime kuma, n=5)"))
        assertEquals(mapOf("url" to "https://a.b/c?x=1&y=2", "max" to "3"), LivingModel.args("fetch_page(url=https://a.b/c?x=1&y=2, max=3)"))
        assertEquals(emptyMap<String, String>(), LivingModel.args("$ ls -la"))
        assertEquals("run_shell", LivingModel.toolOf("$ docker compose up -d"))
        assertNull(LivingModel.toolOf(""))
    }

    @Test fun oneTurnBecomesPromptBrowserFileLogAnswer() {
        val msgs = listOf(
            u(1, "Set up a status page for my app"),
            a(2, "On it", ack = true),
            s(3, "web_search(query=gatus)"),
            s(4, "fetch_page(url=https://github.com/TwiN/gatus)"),
            s(5, "file_write(path=/home/gatus/config.yaml, content=endpoints:\\n  - name: web)"),
            s(6, "$ docker compose up -d"),
            s(7, "$ curl -sI https://example.app"),
            s(8, "", "Thinking about it"),   // narration: no card
            a(9, "Gatus is up, watching your app."),
        )
        val res = listOf(
            r("web_search(query=gatus)", "1. Gatus - automated status page", 31),
            r("fetch_page(url=https://github.com/TwiN/gatus)", "Gatus\nAutomated developer-oriented status page", 41),
            r("$ docker compose up -d", "Container gatus started", 61),
            r("$ curl -sI https://example.app", "Error: timed out", 71),
        )
        val b = LivingModel.board(msgs, working = false, results = res)
        assertEquals(1, b.turns.size)
        val kinds = b.cards.map { it.kind }
        assertEquals(listOf(Kind.PROMPT, Kind.BROWSER, Kind.BROWSER, Kind.FILE, Kind.LOG, Kind.ANSWER), kinds)
        val search = b.cards[1]
        assertTrue(search.flag); assertEquals("gatus", search.url)
        val page = b.cards[2]
        assertEquals("https://github.com/TwiN/gatus", page.url); assertEquals("Gatus", page.title)
        assertEquals("Automated developer-oriented status page", page.text)
        val file = b.cards[3]
        assertEquals("config.yaml", file.title); assertTrue(file.flag); assertEquals(listOf("endpoints:", "  - name: web"), file.lines)
        val log = b.cards[4]
        assertEquals(2, log.rows.size)
        assertEquals("$ docker compose up -d", log.rows[0].text); assertEquals(State.DONE, log.rows[0].state)
        assertEquals(State.FAILED, log.rows[1].state); assertEquals(State.FAILED, log.state)
        assertEquals(5, b.turns[0].steps)
        assertEquals("Done \u00b7 5 steps", b.status)
        assertEquals(b.cards.last().key, b.active)
        assertFalse(b.cards.any { it.text.contains("On it") })
    }

    @Test fun liveTurnMarksTheRunningCardActive() {
        val msgs = listOf(u(1, "Which phone has the best camera?"), s(2, "web_search(query=best camera phone)"), s(3, "fetch_page(url=https://gsmarena.com/x)"))
        val b = LivingModel.board(msgs, working = true, results = listOf(r("web_search(query=best camera phone)", "results", 21)))
        assertEquals(State.DONE, b.cards[1].state)
        assertEquals(State.RUNNING, b.cards[2].state)
        assertEquals(b.cards[2].key, b.active)
        assertEquals("Reading a page \u00b7 2 steps", b.status)
    }

    @Test fun plainToolsUseTheLabel() {
        val msgs = listOf(u(1, "remind me"), s(2, "set_alarm(time=7:00)"))
        val b = LivingModel.board(msgs, working = true, label = { n, run -> if (run) "Setting an alarm" else "Set an alarm" })
        assertEquals("Setting an alarm", b.cards[1].rows[0].text)
        assertFalse(b.cards[1].rows[0].command)
    }

    @Test fun onlyTheNewestTurnsAreKept() {
        val msgs = (1..5L).flatMap { i -> listOf(u(i * 10, "ask $i"), a(i * 10 + 1, "answer $i")) }
        val b = LivingModel.board(msgs, working = false)
        assertEquals(LivingSpec.TURNS_SHOWN, b.turns.size)
        assertEquals("ask 3", b.turns.first().cards.first().text)
    }

    @Test fun cardCapKeepsThePromptAndTheNewestCards() {
        val steps = (0 until 80L).map { s(100 + it, "web_search(query=q$it)") }
        val b = LivingModel.board(listOf(u(1, "go")) + steps, working = false)
        assertEquals(LivingSpec.MAX_CARDS, b.cards.size)
        assertEquals(Kind.PROMPT, b.cards.first().kind)
        assertEquals("q79", b.cards.last().url)
    }

    @Test fun stepsBeforeAnyPromptAndEmptyChatsDrawNothing() {
        val b = LivingModel.board(listOf(s(1, "$ ls")), working = false)
        assertTrue(b.turns.isEmpty()); assertNull(b.active)
        assertEquals("Nothing here yet", b.status)
    }

    @Test fun answerLosesMarkdownMarks() {
        assertEquals("Title\nbold and code", LivingModel.plain("## Title\n**bold** and `code`"))
    }
}
