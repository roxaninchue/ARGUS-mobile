package com.blaze.assistant.team

import com.blaze.assistant.agent.ChatMeta
import com.blaze.assistant.agent.engines.BotEngine
import com.blaze.assistant.agent.engines.EngineCaps
import com.blaze.assistant.agent.engines.LocalEngine
import com.blaze.assistant.agent.engines.LocalEngineTest.FakeLlm
import com.blaze.assistant.agent.engines.LocalEngineTest.FakeLoop
import com.blaze.assistant.agent.engines.RowSink
import com.blaze.assistant.agent.engines.TurnEvent
import com.blaze.assistant.agent.engines.TurnInput
import com.blaze.assistant.store.JdbcSql
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-TM-02: bot threads end to end on the JVM: real [LocalEngine] (driven by LocalEngineTest's [FakeLoop], which calls
 * the TeamHooks seam exactly where Agent.kt does) → [BotThreads.put] → team.db rows on sqlite-jdbc. Ports Bot HQ
 * BH-06 (unread / preview / last activity, `/read`), BH-16 (`/fresh`), BH-12 (trigger envelopes) and the per-bot
 * serial queue of loop.ts; the ARGUS thread reads the user's chats without storing a copy.
 */
class BotThreadsTest {
    private val sql = JdbcSql()
    private var now = 1_731_400_000_000L
    private val team = TeamStore(sql) { now }
    private val chats = mutableMapOf<String, String>()
    private var current: String? = null
    private val argus = ArgusChats.of({ chats.keys.map { ChatMeta(it, "Chat", now, false, "argus said hi") } }, { chats[it] }, { current })
    private val engines = mutableMapOf<String, BotEngine>()
    private val threads = BotThreads(sql, team, argus, { engines[it.id] }) { now }

    init {
        sql.exec("PRAGMA foreign_keys = ON")
        sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
        team.ensure()
    }

    @After fun close() = sql.close()

    private fun local(vararg replies: FakeLlm.Reply) = LocalEngine(FakeLoop(FakeLlm(*replies), listOf("web_search")), soul = { null }, clock = { now })
    private fun say(t: String) = FakeLlm.Reply.Say(t)
    private fun turn(bot: String, text: String) = runBlocking { threads.send(bot, Trigger.User(text)).toList() }
    private fun texts(bot: String) = threads.rows(bot).map { RowBodies.read(it.body)["text"] }

    @Test fun aBotTurnStreamsThroughLocalEngineIntoItsThread() {
        engines["tempo"] = local(FakeLlm.Reply.Call("web_search"), say("You're free Friday after 3."))
        val ev = turn("tempo", "When am I free?")
        assertTrue(ev.first() is TurnEvent.Started); assertEquals("You're free Friday after 3.", (ev.last() as TurnEvent.Done).reply)
        val rs = threads.rows("tempo")
        assertEquals(listOf("message", "event", "tool-call", "message"), rs.map { it.kind })
        assertEquals("When am I free?", RowBodies.read(rs[0].body)["text"]); assertEquals("user", RowBodies.read(rs[0].body)["from"])
        assertEquals("done", RowBodies.read(rs[2].body)["state"])
        assertTrue("the tool call was rewritten in place", rs[2].updatedSeq > rs[2].seq)
        // what the flow carried for the open thread is what was stored
        val streamed = ev.filterIsInstance<TurnEvent.RowOut>().map { it.row }.groupBy { it.seq }.mapValues { (_, v) -> v.maxBy { it.updatedSeq } }
        assertEquals(rs.associateBy { it.seq }, streamed)
        val t = threads.thread("tempo")!!
        assertEquals(1, t.unread); assertEquals("You're free Friday after 3.", t.preview); assertEquals(now, t.lastActivityAt)
        assertEquals("team-tempo-g0", t.chatId); assertFalse(t.running)
    }

    @Test fun secondTurnContinuesNumberingAndUserTextNeverCountsUnread() {
        engines["tempo"] = local(say("One."), say("Two."))
        turn("tempo", "a"); turn("tempo", "b")
        val seqs = threads.rows("tempo").map { it.seq }
        assertEquals(seqs.distinct(), seqs); assertEquals(seqs.sorted(), seqs)
        assertEquals(listOf("a", "One.", "b", "Two."), texts("tempo"))
        assertEquals(2, threads.thread("tempo")!!.unread)
        threads.markRead("tempo")
        assertEquals(0, threads.thread("tempo")!!.unread)
    }

    @Test fun openThreadDoesNotCountAndOpeningMarksRead() {
        engines["inbox"] = local(say("3 new mails."), say("Archived."))
        turn("inbox", "check mail")
        assertEquals(1, threads.thread("inbox")!!.unread)
        threads.open("inbox")
        assertEquals(0, threads.thread("inbox")!!.unread)
        turn("inbox", "archive them")
        assertEquals("replies while the thread is on screen don't count", 0, threads.thread("inbox")!!.unread)
        threads.open(null)
    }

    @Test fun approvalCardCountsAndPreviews() {
        val r = threads.post("tempo", RowKind.CARD, RowBodies.card("approval", mapOf("title" to "Move the dentist to Tuesday")))!!
        assertEquals(1L, r.seq)
        val t = threads.thread("tempo")!!
        assertEquals(1, t.unread); assertEquals("Waiting for you: Move the dentist to Tuesday", t.preview)
        threads.post("tempo", RowKind.NOTICE, RowBodies.notice("Calendar offline", "warn"))
        assertEquals("a notice is not the preview", "Waiting for you: Move the dentist to Tuesday", threads.thread("tempo")!!.preview)
    }

    @Test fun streamingReplyCountsOnceWhenItCloses() {
        val g = 0
        threads.put(Row("tempo", g, 1, 1, RowKind.MESSAGE, RowBodies.message("tempo", "Wor", streaming = true), now))
        assertEquals(0, threads.thread("tempo")!!.unread)
        threads.put(Row("tempo", g, 1, 2, RowKind.MESSAGE, RowBodies.message("tempo", "Working on it", streaming = true), now))
        threads.put(Row("tempo", g, 1, 3, RowKind.MESSAGE, RowBodies.message("tempo", "Working on it. Done."), now))
        assertEquals(1, threads.thread("tempo")!!.unread)
        threads.put(Row("tempo", g, 1, 4, RowKind.MESSAGE, RowBodies.message("tempo", "Working on it. Done!"), now))
        threads.put(Row("tempo", g, 1, 3, RowKind.MESSAGE, RowBodies.message("tempo", "Working on it. Done."), now))   // late repeat
        assertEquals("an edit or a repeat of a closed reply doesn't count again", 1, threads.thread("tempo")!!.unread)
        assertEquals("Working on it. Done!", threads.thread("tempo")!!.preview)
    }

    @Test fun freshStartsANewGenerationAndChat() {
        engines["tempo"] = local(say("Old answer."), say("New answer."))
        turn("tempo", "old question")
        val f = threads.fresh("tempo") as BotThreads.Fresh.Ok
        assertEquals(1, f.generation); assertEquals("team-tempo-g1", f.chatId)
        assertTrue(threads.rows("tempo").isEmpty())
        val t0 = threads.thread("tempo")!!
        assertEquals(0, t0.unread); assertNull(t0.preview)
        turn("tempo", "new question")
        assertEquals(listOf("new question", "New answer."), texts("tempo"))
        assertEquals(1L, threads.rows("tempo").first().seq)
        assertTrue(threads.fresh("ghost") is BotThreads.Fresh.Refused)
    }

    @Test fun oneTurnAtATimePerBotAndFreshRefusedWhileRunning() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val loop = FakeLoop(FakeLlm(say("first"), say("second")), emptyList()).also { it.gate = gate }
        engines["tempo"] = LocalEngine(loop, soul = { null }, clock = { now })
        val a = async { threads.send("tempo", Trigger.User("one")).toList() }
        while (!threads.isRunning("tempo")) yield()
        val b = async { threads.send("tempo", Trigger.User("two")).toList() }
        repeat(20) { yield() }
        assertTrue(threads.thread("tempo")!!.running)
        assertTrue(threads.fresh("tempo") is BotThreads.Fresh.Refused)
        assertEquals("the second turn waits for the first", listOf("one"), texts("tempo"))
        gate.complete(Unit)
        a.await(); b.await()
        assertEquals(listOf("one", "first", "two", "second"), texts("tempo"))
        assertFalse(threads.isRunning("tempo"))
    }

    @Test fun triggersReachTheLoopInTheirEnvelope() {
        val seen = mutableListOf<TurnInput>()
        engines["tempo"] = object : BotEngine {
            override val kind = EngineKind.LOCAL
            override fun turn(bot: Bot, input: TurnInput, sink: RowSink): Flow<TurnEvent> = flow { seen += input; emit(TurnEvent.Done(input.turnId, "", "DONE")) }
            override suspend fun cancel(turnId: String) {}
            override suspend fun steer(turnId: String, text: String) {}
            override fun capabilities() = EngineCaps(EngineKind.LOCAL, true, 1000, false)
        }
        runBlocking {
            threads.send("tempo", Trigger.Routine("r1", "Morning brief", "Summarise today")).toList()
            threads.send("tempo", Trigger.Bot("ledger", "Ledger", "Is Friday free?")).toList()
            threads.send("tempo", Trigger.User("hey", byVoice = true)).toList()
        }
        assertTrue(seen[0].text.startsWith("[Routine \"Morning brief\" is running now.")); assertNull(seen[0].untrusted)
        assertEquals("bot:ledger", seen[1].untrusted)
        assertTrue(seen[2].byVoice); assertEquals("hey", seen[2].text)
        assertTrue(seen.all { it.threadId == "team-tempo-g0" && it.generation == 0 && it.firstSeq == 1L })
    }

    @Test fun unknownOffOrEngineLessBotsFailPlainly() {
        assertTrue((turn("ghost", "x").single() as TurnEvent.Failed).error.contains("No bot"))
        assertTrue((turn("tempo", "x").single() as TurnEvent.Failed).error.contains("no engine"))
        team.delete("inbox")   // a domain bot is switched off, not removed
        engines["inbox"] = local(say("x"))
        assertTrue((turn("inbox", "x").single() as TurnEvent.Failed).error.contains("switched off"))
        assertTrue(threads.rows("tempo").isEmpty())
    }

    @Test fun argusThreadReadsTheChatAndStoresNoCopy() {
        current = "c1"
        chats["c1"] = TeamJson.write(mapOf("id" to "c1", "updated" to 77L, "messages" to listOf(
            mapOf("role" to "USER", "text" to "hello", "at" to 10L), mapOf("role" to "BLAZE", "text" to "Hi Finn."))))
        assertEquals(listOf("hello", "Hi Finn."), texts("manager"))
        engines["manager"] = local(say("Sure."))
        val ev = turn("manager", "plan Friday")
        val out = ev.filterIsInstance<TurnEvent.RowOut>().map { it.row }
        assertEquals("live rows number on from the chat", 3L, out.first().seq)
        assertEquals(0L, sql.query("SELECT count(*) FROM rows") { it.long(0) }.first())
        val t = threads.thread("manager")!!
        assertEquals("c1", t.chatId); assertEquals(1, t.unread); assertEquals("argus said hi", t.preview)
        assertEquals("quiet bots by name after ARGUS", listOf("manager", "inbox", "tempo"), threads.threads().map { it.botId })
    }

    @Test fun listOrderIsArgusThenPinnedThenRecent() {
        engines["inbox"] = local(say("mail")); engines["tempo"] = local(say("cal"))
        turn("inbox", "a"); now += 1000; turn("tempo", "b")
        assertEquals(listOf("manager", "tempo", "inbox"), threads.threads().map { it.botId })
        team.update(team.get("inbox")!!.copy(pinned = true))
        assertEquals(listOf("manager", "inbox", "tempo"), threads.threads().map { it.botId })
    }

    @Test fun changesFlowCarriesRowsClearedReadLive() = runBlocking {
        val got = mutableListOf<BotThreads.Change>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { threads.changes.collect { got += it } }
        engines["tempo"] = local(say("ok"))
        turn("tempo", "x"); threads.markRead("tempo"); threads.fresh("tempo")
        job.cancel()
        assertEquals(BotThreads.Change.Live("tempo", true), got.first())
        assertTrue(got.any { it is BotThreads.Change.Rows })
        assertTrue(got.contains(BotThreads.Change.Live("tempo", false)))
        assertTrue(got.contains(BotThreads.Change.Read("tempo")))
        assertEquals(BotThreads.Change.Cleared("tempo", 1), got.last())
    }

    @Test fun teamChatsAreNeverTheUsersChats() {
        val all = listOf(ChatMeta("abc123", "Mine", 2, false, ""), ChatMeta("team-tempo-g0", "Tempo", 3, false, ""), ChatMeta("team-inbox-g4", "Inbox", 1, false, ""))
        assertEquals(listOf("abc123"), BotThreads.userChats(all).map { it.id })
        assertEquals("team-ledger-g2", BotThreads.teamChatId("ledger", 2))
        assertTrue(BotThreads.isTeamChat("team-ledger-g2")); assertFalse(BotThreads.isTeamChat("teamwork")); assertFalse(BotThreads.isTeamChat(null))
        val a = ArgusChats.of({ all }, { "{}" }, { "team-tempo-g0" })
        assertEquals(listOf("abc123"), a.list().map { it.id })
        assertNull("bot chats and private chats are never served", a.read("team-tempo-g0")); assertNull(a.read("private-1"))
        assertNull(a.current())
    }
}
