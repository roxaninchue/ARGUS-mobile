package com.blaze.assistant.ui.team.thread

import com.blaze.assistant.agent.StepTool
import com.blaze.assistant.team.Avatar
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.EngineKind
import com.blaze.assistant.team.EngineSpec
import com.blaze.assistant.team.Row
import com.blaze.assistant.team.RowBodies
import com.blaze.assistant.team.RowKind
import com.blaze.assistant.team.TeamJson
import com.blaze.assistant.ui.team.thread.ThreadModel.CardModel
import com.blaze.assistant.ui.team.thread.ThreadModel.State
import com.blaze.assistant.ui.team.thread.ThreadModel.ThreadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-SH-03: rows → what a bot thread draws (04 §2.8 Thread), on the JVM. */
class ThreadModelTest {
    private var seq = 0L
    private fun row(kind: String, body: String, at: Long) = Row("atlas", 0, ++seq, seq, kind, body, at)
    private fun msg(from: String, text: String, at: Long, streaming: Boolean = false, via: RowBodies.Via? = null) = row(RowKind.MESSAGE, RowBodies.message(from, text, streaming, via), at)
    private fun tool(id: String, name: String, state: String, at: Long, args: Any? = null, output: String? = null, undoable: Boolean = false) =
        row(RowKind.TOOL_CALL, TeamJson.write(linkedMapOf("callId" to id, "name" to name, "args" to args, "state" to state, "output" to output, "undoable" to (if (undoable) true else null))), at)

    @Test fun aFinishedTurnFoldsIntoAWorkChipAboveTheReply() {
        val rows = listOf(
            msg("user", "Find flights for Jan 5", 0),
            tool("c1", "web_search", "done", 1_000),
            tool("c2", "fetch_page", "done", 20_000),
            tool("c3", "terminal", "done", 60_000, args = mapOf("command" to "ls -la"), output = "total 0"),
            tool("c4", "save_file", "done", 100_000, undoable = true),
            msg("atlas", "Found 3 options.", 131_000),
        )
        val items = ThreadModel.items(rows, "Atlas", running = false)
        assertEquals(listOf("User", "Work", "Agent"), items.map { it::class.simpleName })
        val w = items[1] as ThreadItem.Work
        assertEquals("Worked for 2m 10s \u00b7 4 steps", w.chip)
        assertEquals("4 steps: 1 edit, 1 command, 2 browser", w.summary)
        assertEquals(listOf(StepTool.Kind.EDIT, StepTool.Kind.COMMAND, StepTool.Kind.BROWSER), w.groups.map { it.kind })
        val cmd = w.steps.first { it.key == "c3" }
        assertEquals("ls -la", cmd.command); assertEquals("total 0", cmd.output); assertEquals("Ran a command", cmd.title)
        assertTrue(w.steps.first { it.key == "c4" }.undoable)
        assertFalse(w.steps.first { it.key == "c1" }.undoable)
        // nothing on screen carries a raw tool name
        val shown = w.steps.flatMap { listOf(it.title, it.now, it.done, it.status) } + w.chip + w.summary
        for (n in listOf("web_search", "fetch_page", "terminal", "save_file")) assertTrue(shown.none { it.contains(n) })
    }

    @Test fun aRunningTurnIsTheLiveActivityLineAtTheEnd() {
        val rows = listOf(
            msg("user", "Check hotels", 0),
            row(RowKind.EVENT, RowBodies.event("step", "Working on bank_balance"), 500),
            tool("c1", "web_search", "done", 1_000),
            tool("c2", "fetch_page", "running", 5_000),
        )
        val live = ThreadModel.items(rows, "Atlas", running = true).last() as ThreadItem.Live
        assertEquals("a step line with a tool name falls back to the running step", "Reading a page", live.line)
        assertEquals(2, live.steps.size); assertEquals(State.RUNNING, live.steps[1].state); assertEquals(500L, live.startedAt)
        val clean = ThreadModel.items(rows.dropLast(2) + row(RowKind.EVENT, RowBodies.event("step", "Comparing prices"), 6_000), "Atlas", running = true).last() as ThreadItem.Live
        assertEquals("Comparing prices", clean.line)
        // the turn is over but its last step was cut off: a red "Stopped after" chip
        val stopped = ThreadModel.items(listOf(msg("user", "go", 0), tool("c1", "web_search", "done", 0), tool("c2", "terminal", "interrupted", 3_000)), "Atlas", running = false)
        val w = stopped.last() as ThreadItem.Work
        assertTrue(w.failed); assertEquals("Stopped after 2 steps \u00b7 Go on", w.chip)
        assertEquals("Failed", w.steps[1].status)
    }

    @Test fun messagesNotesAndProvenance() {
        val rows = listOf(
            msg("user", "Brief", 0, via = RowBodies.Via("routine", "r1", "Morning brief")),
            msg("atlas", "Here it is", 1, streaming = true, via = RowBodies.Via("bot", "nova", "Nova")),
            row(RowKind.NOTICE, RowBodies.notice("Groq rate limit, retrying on another model", "error"), 2),
            row(RowKind.EVENT, RowBodies.event("job", "2 changes \u2713 Job Send deck \u00b7 Fri"), 3),
            row(RowKind.MESSAGE, RowBodies.message("atlas", ""), 4),
        )
        val items = ThreadModel.items(rows, "Atlas", running = false)
        assertEquals(4, items.size)
        assertEquals("Routine \u00b7 Morning brief", (items[0] as ThreadItem.User).via)
        val a = items[1] as ThreadItem.Agent
        assertTrue(a.streaming); assertEquals("From Nova", a.via)
        val n = items[2] as ThreadItem.System
        assertTrue(n.error); assertEquals("Something went wrong on this turn", n.text)
        assertEquals("2 changes \u2713 Job Send deck \u00b7 Fri", (items[3] as ThreadItem.System).text)
    }

    @Test fun cards() {
        val jobs = row(RowKind.CARD, RowBodies.card("jobs", mapOf("jobs" to listOf(
            mapOf("id" to "j1", "title" to "Find flights", "due" to "Fri"), mapOf("id" to "j2", "title" to "Hotel"),
            mapOf("id" to "j3", "title" to "Visa", "done" to true), mapOf("id" to "j4", "title" to "Car")))), 0)
        val ok = row(RowKind.CARD, RowBodies.card("ok", mapOf("jobId" to "j9", "title" to "Send deck", "due" to "2 days late")), 1)
        val file = row(RowKind.CARD, RowBodies.card("file", mapOf("path" to "/files/bots/atlas/plan.pdf", "size" to 1_468_006L, "mime" to "application/pdf")), 2)
        val hand = row(RowKind.CARD, RowBodies.card("handoff", mapOf("from" to "atlas", "fromName" to "Atlas", "to" to "ledger", "toName" to "Ledger",
            "text" to "Is $356 within the travel budget?", "reply" to "Yes, $500 left")), 3)
        val appr = row(RowKind.CARD, RowBodies.card("approval", mapOf("title" to "Book PF701")), 4)
        val cs = ThreadModel.items(listOf(jobs, ok, file, hand, appr), "Atlas", running = false).map { (it as ThreadItem.Card).card }
        val j = cs[0] as CardModel.Jobs
        assertEquals("ATLAS \u00b7 JOBS", j.header); assertEquals("3 open", j.count); assertEquals(3, j.shown.size); assertEquals(1, j.more)
        assertEquals(CardModel.Ok("j9", "Send deck", "2 days late"), cs[1])
        val f = cs[2] as CardModel.File
        assertEquals("plan.pdf", f.name); assertEquals("1.4 MB", f.sizeText); assertFalse(f.image)
        val h = cs[3] as CardModel.Handoff
        assertEquals("Atlas \u2192 Ledger", h.line); assertEquals("Yes, $500 left", h.reply)
        assertEquals(CardModel.Other("approval", "Book PF701", null), cs[4])
    }

    @Test fun introAndText() {
        val m = BotRules.manager(0)
        val i = ThreadModel.intro(m)
        assertEquals("Manager \u00b7 Lead", i.role); assertTrue(i.manager)
        val b = Bot(id = "atlas", kind = BotKind.CUSTOM, name = "Atlas", label = "Travel", avatar = Avatar("orb", "#f59e0b"), soulPath = "",
            toolGroups = emptySet(), engine = EngineSpec(kind = EngineKind.HERMES), createdAt = 0, u = 0)
        val bi = ThreadModel.intro(b, "  Plans trips.  ")
        assertEquals("Your server", bi.engine); assertEquals("Plans trips.", bi.about); assertEquals("Travel", bi.role)
        assertNull(ThreadModel.intro(b, " ").about)
        assertEquals("On this phone", ThreadModel.engineLabel(m))
        assertEquals(listOf("12s", "2m 10s", "1h 5m"), listOf(12_000L, 130_000L, 3_900_000L).map { ThreadModel.duration(it) })
        assertEquals(listOf("0:07", "1:24", "1:02:05"), listOf(7_000L, 84_000L, 3_725_000L).map { ThreadModel.clock(it) })
        assertEquals("Checking hotels, 3 steps, 1 minute 24 seconds", ThreadModel.liveA11y("Checking hotels", 3, 84_000))
        assertEquals("Working, 0 seconds", ThreadModel.liveA11y("Working", 0, 0))
        assertEquals(listOf("900 B", "12 KB", "1.4 MB"), listOf(900L, 12_000L, 1_468_006L).map { ThreadModel.size(it) })
    }
}
