package com.blaze.assistant.ui.team.thread

import com.blaze.assistant.agent.StepTool
import com.blaze.assistant.agent.ToolTrail
import com.blaze.assistant.ui.team.thread.ChatToolRows.Msg
import com.blaze.assistant.ui.team.thread.ThreadModel.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 4: the main chat's step messages as live, grouped tool rows, folding into a work chip when the turn ends. */
class ChatToolRowsTest {
    private fun user(id: Long, at: Long) = Msg(id, false, "Fix the build", at = at)
    private fun step(id: Long, text: String, exact: String, at: Long, startAt: Long = 0) = Msg(id, true, text, exact, at, startAt)
    private fun note(id: Long, text: String, at: Long) = Msg(id, true, text, "", at)
    private fun reply(id: Long, at: Long, ack: Boolean = false) = Msg(id, false, if (ack) "On it" else "Done, it builds.", at = at, ack = ack)
    private fun res(exact: String, out: String, at: Long) = ToolTrail.Result(exact, out, ToolTrail.failed(out), at)

    private val turn = listOf(
        user(1, 0),
        step(2, "Reading build.gradle.kts", "file_read(path=app/build.gradle.kts)", 1_000, startAt = 500),
        note(3, "Looking at what failed", 2_000),
        step(4, "Running \u201c./gradlew test\u201d", "$ ./gradlew test", 3_000),
        step(5, "Editing Agent.kt", "file_edit(path=Agent.kt, find=a, replace=b)", 9_000),
        step(6, "Sending a text to Sam", "send_sms(to=Sam, body=done)", 10_000),
    )

    @Test fun aRunningTurnIsOneLiveBlockGroupedByKind() {
        val results = listOf(res("file_read(path=app/build.gradle.kts)", "plugins { }", 1_500), res("$ ./gradlew test", "BUILD SUCCESSFUL", 8_000))
        val t = ChatToolRows.turns(turn, working = true, results = results)
        assertTrue(t.before.isEmpty()); assertNull(t.tail)
        val live = t.live!!
        assertEquals("narration is not a call", 4, live.steps.size)
        assertEquals("Sending a text to Sam", live.line)
        assertEquals("Sending a text to Sam \u00b7 4 steps", ChatToolRows.collapsed(live))
        assertEquals(500L, live.startedAt)
        assertEquals(listOf(State.DONE, State.DONE, State.RUNNING, State.RUNNING), live.steps.map { it.state })
        // expanded: EDITS, READS, COMMANDS, MESSAGES, in the kit's order
        val g = ThreadModel.groups(live.steps)
        assertEquals(listOf(StepTool.Kind.EDIT, StepTool.Kind.READ, StepTool.Kind.COMMAND, StepTool.Kind.MESSAGES), g.map { it.kind })
        assertEquals(listOf("EDITS", "READS", "COMMANDS", "MESSAGES"), g.map { it.kind.group.uppercase() })
        // each call opens to its command and output; only the shell call shows a command
        val cmd = live.steps.first { it.kind == StepTool.Kind.COMMAND }
        assertEquals("./gradlew test", cmd.command); assertEquals("BUILD SUCCESSFUL", cmd.output); assertEquals("Ran a command", cmd.title)
        val read = live.steps.first { it.kind == StepTool.Kind.READ }
        assertNull(read.command); assertEquals("plugins { }", read.output)
        assertEquals("Reading build.gradle.kts", live.steps.first { it.kind == StepTool.Kind.READ }.now)
        // the send is a message, never an edit
        assertEquals(StepTool.Kind.MESSAGES, live.steps.last().kind)
        // nothing raw on screen
        val shown = live.steps.flatMap { listOf(it.title, it.now, it.done, it.status) } + live.line
        for (n in listOf("file_read", "file_edit", "send_sms", "run_shell")) assertTrue(n, shown.none { it.contains(n) })
    }

    @Test fun whenTheTurnEndsItBecomesTheWorkChipAboveTheReply() {
        val results = listOf(res("file_read(path=app/build.gradle.kts)", "ok", 1_500), res("$ ./gradlew test", "ok", 8_000),
            res("file_edit(path=Agent.kt, find=a, replace=b)", "ok", 9_500), res("send_sms(to=Sam, body=done)", "sent", 10_500))
        val msgs = turn + reply(7, 130_500)
        val t = ChatToolRows.turns(msgs, working = false, results = results)
        assertNull(t.live); assertNull(t.tail)
        val w = t.before[7L]!!
        assertEquals("Worked for 2m 10s \u00b7 4 steps", w.chip)
        assertEquals("4 steps: 1 edit, 1 read, 1 command, 1 message", w.summary)
        assertTrue(w.steps.all { it.state == State.DONE }); assertFalse(w.failed)
        // an "On it" ack in the middle does not split the turn
        val withAck = listOf(user(1, 0), reply(2, 100, ack = true)) + turn.drop(1).map { it.copy(id = it.id + 10) } + reply(30, 130_500)
        val t2 = ChatToolRows.turns(withAck, working = false, results = results)
        assertEquals(listOf(30L), t2.before.keys.toList()); assertEquals(4, t2.before[30L]!!.steps.size)
    }

    @Test fun failuresParallelPairingAndTheTail() {
        // two parallel searches finish out of order: each pairs with its own exact call
        val msgs = listOf(user(1, 0), step(2, "Searching the web for \u201ca\u201d", "web_search(query=a)", 10),
            step(3, "Searching the web for \u201cb\u201d", "web_search(query=b)", 11), step(4, "Running \u201cmake\u201d", "$ make", 20))
        val results = listOf(res("web_search(query=b)", "B", 30), res("web_search(query=a)", "A", 31), res("$ make", "Error: make failed", 40))
        val t = ChatToolRows.turns(msgs, working = false, results = results)
        val w = t.tail!!
        assertEquals(listOf("A", "B", "Error: make failed"), w.steps.map { it.output })
        assertEquals(State.FAILED, w.steps.last().state); assertTrue(w.failed)
        assertEquals("Stopped after 3 steps \u00b7 Go on", w.chip)
        // no results at all (an app restart): finished calls are done, with no output
        val bare = ChatToolRows.turns(msgs + reply(5, 50), working = false).before[5L]!!
        assertTrue(bare.steps.all { it.state == State.DONE && it.output == null })
        // a turn of narration only has no rows
        val talk = ChatToolRows.turns(listOf(user(1, 0), note(2, "Thinking it over", 5), reply(3, 9)), working = false)
        assertTrue(talk.before.isEmpty())
        assertTrue(ChatToolRows.turns(listOf(user(1, 0), note(2, "Thinking it over", 5)), working = true).live!!.steps.isEmpty())
    }

    @Test fun exactTextParsing() {
        assertEquals("run_shell", ChatToolRows.toolOf("$ ls -la")); assertEquals("ls -la", ChatToolRows.commandOf("$ ls -la"))
        assertEquals("file_edit", ChatToolRows.toolOf("file_edit(path=a)")); assertNull(ChatToolRows.commandOf("file_edit(path=a)"))
        assertNull(ChatToolRows.toolOf("")); assertNull(ChatToolRows.toolOf("Looking at it"))
        // a step line that names a tool or a model never shows: the label's words do
        val t = ChatToolRows.turns(listOf(user(1, 0), step(2, "Calling bank_balance via openrouter", "bank_balance()", 5)), working = true).live!!
        assertEquals("Looking things up", t.steps[0].now)
    }
}
