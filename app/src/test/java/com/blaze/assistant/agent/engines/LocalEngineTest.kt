package com.blaze.assistant.agent.engines

import com.blaze.assistant.agent.TeamHooks
import com.blaze.assistant.team.Avatar
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.EngineKind
import com.blaze.assistant.team.Row
import com.blaze.assistant.team.Rule
import com.blaze.assistant.team.TeamJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0-AG-01: LocalEngine runs a bot's turn through the TeamHooks seam and emits team Rows. [FakeLoop] stands in for
 * Agent.Session: it calls the hooks at the same points Agent.kt does (the user bubble, the system prompt, the tool
 * list, runTool's refuse / call / result, the reply bubble, endTurn) and asks a scripted fake LLM client for each step.
 */
class LocalEngineTest {

    /** The fake LLM client: one scripted reply per step; it records what each request carried. */
    class FakeLlm(vararg replies: Reply) {
        sealed class Reply { data class Say(val text: String) : Reply(); data class Call(val tool: String, val args: String = "{}") : Reply() }
        private val script = replies.toMutableList()
        val systems = mutableListOf<List<String>>()
        val toolLists = mutableListOf<List<String>>()
        fun complete(system: List<String>, tools: List<String>): Reply { systems += system; toolLists += tools; return script.removeAt(0) }
    }

    /** Agent.Session's hook points, in order, around a fake model. */
    class FakeLoop(private val llm: FakeLlm, private val allTools: List<String>, private val toolImpl: (String, String) -> String = { n, _ -> "$n ok" }) : LocalEngine.Driver {
        val ran = mutableListOf<String>()
        val steered = mutableListOf<String>()
        var cancelled = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun run(threadId: String, text: String, byVoice: Boolean, untrusted: String?) {
            TeamHooks.message(threadId, "USER", text)                                         // Session.add(USER)
            gate?.await()
            var reason = "DONE"
            try {
                for (step in 0 until 8) {
                    val system = listOfNotNull("You are Blaze.", TeamHooks.identityBlock(threadId))   // requestMessages()
                    val tools = allTools.filter { it == "load_tools" || TeamHooks.allows(threadId, it) }   // TeamTools.keep
                    when (val r = llm.complete(system, tools)) {
                        is FakeLlm.Reply.Say -> { TeamHooks.message(threadId, "BLAZE", r.text); break }
                        is FakeLlm.Reply.Call -> {
                            TeamHooks.message(threadId, "STEP", "Working on ${r.tool}")
                            val out = TeamHooks.refuse(threadId, r.tool) ?: run {             // runTool
                                TeamHooks.call(threadId, r.tool, r.args)
                                toolImpl(r.tool, r.args).also { ran += r.tool; TeamHooks.result(threadId, r.tool, it) }
                            }
                            if (out.startsWith("Error:")) TeamHooks.message(threadId, "STEP", "Skipped ${r.tool}")
                        }
                    }
                }
            } catch (e: IllegalStateException) { reason = "FAILED"; TeamHooks.message(threadId, "ERROR", e.message ?: "error") }
            TeamHooks.end(threadId, reason)                                                    // endTurn
        }
        override fun cancel(threadId: String) { cancelled++ }
        override fun steer(threadId: String, text: String) { steered += text }
    }

    private fun bot(id: String = "ledger", kind: BotKind = BotKind.DOMAIN, groups: Set<String> = setOf("money"),
                    grants: Map<String, Rule> = emptyMap(), soul: String = "/nope/SOUL.md") =
        Bot(id = id, kind = kind, name = if (kind == BotKind.MANAGER) "ARGUS" else "Ledger", label = "Money", domain = "money",
            avatar = Avatar("orbit", "#2F6BFF"), soulPath = soul, toolGroups = groups, toolGrants = grants, createdAt = 1L, u = 1L)

    private val groups = mapOf("bank_balance" to "money", "run_shell" to "terminal", "browser_open" to "browser")
    private val all = listOf("load_tools", "web_search", "bank_balance", "run_shell", "browser_open")
    private fun engine(loop: LocalEngine.Driver, soul: String? = "Careful with money. Never moves it without asking.") =
        LocalEngine(loop, groupOf = { groups[it] }, soul = { soul }, clock = { 42L })

    private fun body(r: Row) = TeamJson.read(r.body) as Map<*, *>

    @Test fun aTurnEmitsRowsFromTheFakeModel() = runBlocking {
        val llm = FakeLlm(FakeLlm.Reply.Call("bank_balance", "{\"acct\":\"main\"}"), FakeLlm.Reply.Say("You have K1,200."))
        val loop = FakeLoop(llm, all)
        val stored = mutableListOf<Row>()
        val events = engine(loop).turn(bot(), TurnInput("t1", "bot:ledger", "what's my balance?", generation = 2, firstSeq = 10)) { stored += it }.toList()

        assertTrue(events.first() is TurnEvent.Started)
        val done = events.last() as TurnEvent.Done
        assertEquals("You have K1,200.", done.reply); assertEquals("DONE", done.reason)

        val rows = events.filterIsInstance<TurnEvent.RowOut>().map { it.row }
        assertEquals(stored, rows)                                     // the sink gets exactly what the flow carries
        assertEquals(listOf("message", "event", "tool-call", "tool-call", "message"), rows.map { it.kind })
        assertTrue(rows.all { it.botId == "ledger" && it.generation == 2 && it.createdAt == 42L })
        assertEquals(listOf(10L, 11L, 12L, 12L, 14L), rows.map { it.seq })
        assertEquals(listOf(10L, 11L, 12L, 13L, 14L), rows.map { it.updatedSeq })   // the tool row changed in place
        assertEquals("user", body(rows[0])["from"]); assertEquals("what's my balance?", body(rows[0])["text"])
        assertEquals("running", body(rows[2])["state"]); assertEquals("{\"acct\":\"main\"}", body(rows[2])["args"])
        assertEquals("done", body(rows[3])["state"]); assertEquals("bank_balance ok", body(rows[3])["output"])
        assertEquals(body(rows[2])["callId"], body(rows[3])["callId"])
        assertEquals("ledger", body(rows[4])["from"])
        assertTrue(events.filterIsInstance<TurnEvent.Activity>().map { it.text }.containsAll(listOf("Working on bank_balance", "Using bank_balance")))
        assertFalse(TeamHooks.isBound("bot:ledger"))                   // released when the turn ends
    }

    @Test fun theModelSeesTheBotIdentityAndOnlyItsTools() = runBlocking {
        val llm = FakeLlm(FakeLlm.Reply.Say("Hi."))
        engine(FakeLoop(llm, all)).turn(bot(), TurnInput("t2", "bot:ledger", "hi")) {}.toList()
        val sys = llm.systems.single()
        assertEquals("You are Blaze.", sys[0])
        assertTrue(sys[1].contains("Name: Ledger (Money)") && sys[1].contains("Life area: money") && sys[1].contains("Careful with money."))
        assertEquals(listOf("load_tools", "web_search", "bank_balance"), llm.toolLists.single())   // core + its group
    }

    @Test fun aToolOutsideTheSubsetIsRefusedAsARow() = runBlocking {
        val llm = FakeLlm(FakeLlm.Reply.Call("run_shell", "{\"cmd\":\"rm -rf /\"}"), FakeLlm.Reply.Say("I can't run commands; the Manager can."))
        val loop = FakeLoop(llm, all)
        val rows = engine(loop).turn(bot(), TurnInput("t3", "bot:ledger", "clean my disk")) {}.toList()
            .filterIsInstance<TurnEvent.RowOut>().map { it.row }
        assertTrue(loop.ran.isEmpty())
        val refused = rows.single { it.kind == "tool-call" }
        assertEquals("refused", body(refused)["state"]); assertEquals("run_shell", body(refused)["name"])
        assertTrue((body(refused)["output"] as String).startsWith("Error:"))
    }

    @Test fun grantsOverrideGroupsAndTheManagerKeepsEverything() {
        val b = bot(grants = mapOf("browser_open" to Rule.ASK, "bank_balance" to Rule.NEVER))
        val allow = ToolSubset.of(b) { groups[it] }!!
        assertTrue(allow("browser_open")); assertFalse(allow("bank_balance")); assertTrue(allow("web_search")); assertFalse(allow("run_shell"))
        assertNull(ToolSubset.of(bot(kind = BotKind.MANAGER), { groups[it] }))
        assertNull(ToolSubset.of(bot(groups = setOf(ToolSubset.ALL)), { groups[it] }))
    }

    @Test fun managerIdentityIsOnlyItsSoul() = runBlocking {
        assertEquals("", LocalEngine.identity(bot(kind = BotKind.MANAGER), null))
        assertEquals("Be ARGUS.", LocalEngine.identity(bot(kind = BotKind.MANAGER), "  Be ARGUS.  "))
        val long = LocalEngine.identity(bot(), "s".repeat(LocalEngine.SOUL_MAX + 100))
        assertTrue(long.length < LocalEngine.SOUL_MAX + 200)
        val llm = FakeLlm(FakeLlm.Reply.Say("Hello, I'm ARGUS."))
        engine(FakeLoop(llm, all), soul = null).turn(bot(kind = BotKind.MANAGER), TurnInput("t4", "main", "hi")) {}.toList()
        assertEquals(listOf("You are Blaze."), llm.systems.single())             // no identity block: today's chat, unchanged
        assertEquals(all, llm.toolLists.single())
    }

    @Test fun readSoulReadsTheFileOrNull() {
        val f = File.createTempFile("SOUL", ".md").apply { writeText("I am Ledger."); deleteOnExit() }
        assertEquals("I am Ledger.", LocalEngine.readSoul(bot(soul = f.path)))
        assertNull(LocalEngine.readSoul(bot()))
    }

    @Test fun anInterruptedTurnClosesItsOpenToolCalls() {
        val m = RowMapper("ledger", 0, 1) { 7L }
        m.map(TeamHooks.Event.ToolCall("tc-1", "web_search", "{}"))
        val closed = m.map(TeamHooks.Event.End("STOPPED"))
        assertEquals("interrupted", body(closed.single())["state"])
        assertEquals(1L, closed.single().seq); assertEquals(2L, closed.single().updatedSeq)
        assertEquals("STOPPED", m.endReason)
        assertEquals("notice", m.map(TeamHooks.Event.Message("ERROR", "x")).single().kind)
    }

    @Test fun aFailingLoopEndsTheTurnWithFailed() = runBlocking {
        val loop = object : LocalEngine.Driver {
            override suspend fun run(threadId: String, text: String, byVoice: Boolean, untrusted: String?) { throw java.io.IOException("socket closed") }
            override fun cancel(threadId: String) {}
            override fun steer(threadId: String, text: String) {}
        }
        val ev = engine(loop).turn(bot(), TurnInput("t5", "bot:ledger", "hi")) {}.toList()
        assertEquals("socket closed", (ev.last() as TurnEvent.Failed).error)
        assertFalse(TeamHooks.isBound("bot:ledger"))
    }

    @Test fun steerAndCancelReachTheRunningTurnsThread() = runBlocking {
        val loop = FakeLoop(FakeLlm(FakeLlm.Reply.Say("Done.")), all).apply { gate = CompletableDeferred() }
        val e = engine(loop)
        val run = async { e.turn(bot(), TurnInput("t6", "bot:ledger", "plan my budget")) {}.toList() }
        while (!e.isRunning("t6")) yield()
        e.steer("t6", "only food and rent"); e.steer("t6", "  "); e.cancel("t6")
        e.steer("unknown", "ignored")
        loop.gate!!.complete(Unit)
        run.await()
        assertEquals(listOf("only food and rent"), loop.steered)
        assertEquals(1, loop.cancelled)
        assertFalse(e.isRunning("t6"))
        val caps = e.capabilities()
        assertEquals(EngineKind.LOCAL, caps.kind); assertTrue(caps.localTools); assertEquals(EngineKind.LOCAL, e.kind)
    }

    /** Owner rule: Agent.kt grows by at most 20 lines for the seam (5,807 lines before P0-AG-01). */
    @Test fun agentKtSeamStaysSmall() {
        val f = listOf(File("app/src/main/java"), File("src/main/java")).first { it.isDirectory }.resolve("com/blaze/assistant/agent/Agent.kt")
        val lines = f.readLines()
        assertTrue("Agent.kt is ${lines.size} lines", lines.size <= 5807 + 20)
        val seam = lines.count { "TeamHooks" in it || "TeamTools" in it || "P0-AG-01" in it }
        assertTrue("seam lines: $seam", seam in 1..20)
    }
}
