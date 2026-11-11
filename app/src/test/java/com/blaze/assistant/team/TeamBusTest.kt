package com.blaze.assistant.team

import com.blaze.assistant.memory.MemScopes
import com.blaze.assistant.team.BotMessaging.Handoff
import com.blaze.assistant.team.BotMessaging.HandoffStatus
import com.blaze.assistant.team.BotMessaging.Provenance
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-TM-01: TeamBus (05 §2.4, §7.2, §10.2). Hop cap, thread budget, scope redaction of payloads, stay_silent,
 * expects_reply, the HandoffCard in both threads with the collapsed "A ↔ B · n messages" line, provenance badges,
 * no side doors, and LoopGuard reused against ping-pong.
 */
class TeamBusTest {
    private var now = 1_700_000_000_000L
    private val port = MemoryTeamPort()
    private val team = TeamStore(port) { now }.also { it.ensure() }
    private val rows = Rows(port) { now }
    private val threads = BotThreads(port, rows, team) { now }
    private val busPort = MemoryBotThreadPort()
    private var ids = 0

    init {
        port.putBot(Bots.domain("atlas", "Atlas", "Travel", "travel", 0, Bots.COLORS[0], setOf("web", "browser"), listOf("flight"), now))
        port.putBot(Bots.domain("ledger", "Ledger", "Money", "money", 1, Bots.COLORS[1], setOf("money"), listOf("budget"), now))
        port.putBot(Bots.domain("pulse", "Pulse", "Health", "health", 2, Bots.COLORS[2], setOf("health"), listOf("run"), now))
        port.putBot(Bots.domain("circle", "Circle", "Social", "social", 3, Bots.COLORS[3], setOf("comms"), listOf("friend"), now))
    }

    /** What each receiving turn got, and a scripted engine. */
    private val prompts = ArrayList<Pair<String, String>>()
    private val budgets = ArrayList<ThreadBudget>()
    private var script: (String, String, ThreadBudget) -> BotReply = { _, _, _ -> BotReply("ok") }
    private var mem: Map<String, List<MemScopes.Line>> = emptyMap()
    private val queue = ArrayList<() -> Unit>()
    private var inline = true

    private val bus: TeamBus = TeamBus(team, threads, busPort,
        runner = BotRunner { id, prompt, b -> prompts += id to prompt; budgets += b; script(id, prompt, b) },
        memory = MemoryView { mem[it] ?: emptyList() },
        later = { if (inline) it() else queue += it },
        clock = { now }, newId = { "t${++ids}" })

    private fun card(botId: String, threadId: String): Handoff? =
        rows.all(botId).mapNotNull { Handoff.of(it) }.lastOrNull { it.thread == threadId }

    // ------------------------------------------------------------------------------------------------ basics

    @Test fun askAndReplyShowsOneHandoffCardInBothThreadsWithTheTalkLine() {
        script = { _, _, _ -> BotReply("ZMW 18,000 left for December.", calls = 2) }
        val r = bus.send("atlas", "Ledger", "Budget left for the Dec trip?") as TeamBus.Sent.Replied
        assertEquals("ZMW 18,000 left for December.", r.reply)
        assertTrue(r.text, r.text.contains("Ledger replied (thread t1, 18 model calls left)"))
        val t = bus.thread("t1")!!
        assertEquals(BusState.ANSWERED, t.state); assertEquals(1, t.depth); assertEquals(2, t.messages); assertEquals(18, t.budgetLeft)
        assertEquals("atlas", t.a); assertEquals("ledger", t.b)

        for (side in listOf("atlas", "ledger")) {
            val cards = rows.all(side).mapNotNull { Handoff.of(it) }
            assertEquals("one card per exchange in $side's thread", 1, cards.size)
            val c = cards.single()
            assertEquals(HandoffStatus.REPLIED, c.status); assertFalse(c.status.live)
            assertEquals("Atlas \u2194 Ledger \u00b7 2 messages", c.talk)
            assertEquals("Atlas \u2192 Ledger: Budget left for the Dec trip?", c.title)
            assertEquals("ZMW 18,000 left for December.", c.reply)
        }
        assertEquals(Handoff.SIDE_ASKER, card("atlas", "t1")!!.side); assertEquals(Handoff.SIDE_ANSWERER, card("ledger", "t1")!!.side)
        // the chat list preview is the card title, and the receiver gets an unread
        assertEquals("Atlas \u2192 Ledger: Budget left for the Dec trip?", threads.state("ledger").lastPreview)
        assertTrue(threads.state("ledger").unread >= 1)
        // transcript: what each side actually received, with bot provenance
        val tr = bus.transcript("t1")
        assertEquals(listOf("bot:atlas", "bot:ledger"), tr.map { RowBody.field(it, "from") })
        assertEquals(listOf("from Atlas", "from Ledger"), tr.map { BotMessaging.badge(it, mapOf("atlas" to "Atlas", "ledger" to "Ledger")) })
        // the receiver's turn starts with the BH-12 bot envelope
        val (who, prompt) = prompts.single()
        assertEquals("ledger", who)
        assertTrue(prompt, prompt.startsWith("[Message from Atlas \u00b7 Travel, another bot on your team \u00b7 thread t1 \u00b7 hop 1 of 3 \u00b7 20 model calls left in this thread]\nBudget left for the Dec trip?"))
        assertTrue(prompt.contains("can't use Atlas's tools"))
    }

    @Test fun followUpInTheSameThreadUpdatesTheSameCards() {
        script = { _, p, _ -> BotReply(if (p.contains("flights")) "Yes, within budget." else "ZMW 18,000.") }
        bus.send("atlas", "ledger", "Budget left?")
        val r = bus.exec("send_to_bot", JSONObject().put("bot", "ledger").put("message", "Are \$356 flights OK?").put("thread", "t1"), team.get("atlas")!!)
        assertTrue(r.text, r.ok); assertEquals("t1", r.threadId)
        assertEquals(1, rows.all("atlas").count { Handoff.of(it) != null })
        val c = card("atlas", "t1")!!
        assertEquals(4, c.messages); assertEquals("Atlas \u2194 Ledger \u00b7 4 messages", c.talk); assertEquals("Yes, within budget.", c.reply)
        assertEquals(4, bus.transcript("t1").size)
        // a thread is only continued by its two bots, with each other
        assertTrue(bus.send("circle", "ledger", "hi", thread = "t1").text.contains("isn't yours"))
        assertTrue(bus.send("atlas", "pulse", "hi", thread = "t1").text.contains("is with Ledger"))
        assertTrue(bus.send("atlas", "ledger", "hi", thread = "nope").text.contains("No thread nope"))
    }

    // ------------------------------------------------------------------------------------------------ hop cap

    @Test fun hopCapStopsTheFourthHop() {
        val results = HashMap<String, TeamBus.Sent>()
        script = { id, _, b ->
            when (id) {
                "ledger" -> { results["ledger"] = bus.send("ledger", "Tempo", "Free 14-20 Dec?", via = b); BotReply("ledger done") }
                "tempo" -> { results["tempo"] = bus.send("tempo", "Inbox", "Any invites 14-20 Dec?", via = b); BotReply("tempo done") }
                "inbox" -> {
                    assertFalse("at the cap the engine drops send_to_bot", b.canSend)
                    assertEquals(listOf("stay_silent"), BotMessaging.schemas(b.canSend).map { it.getJSONObject("function").getString("name") })
                    assertTrue(prompts.last().second.contains("You can't message other bots from here"))
                    results["inbox"] = bus.send("inbox", "circle", "Tell Thandi", via = b); BotReply("inbox done")
                }
                else -> BotReply("?")
            }
        }
        val top = bus.send("atlas", "ledger", "Can we do Dec 14-20?")
        assertTrue(top.ok)
        assertEquals(listOf(1, 2, 3), listOf("t1", "t2", "t3").map { bus.thread(it)!!.depth })
        assertTrue(results["ledger"]!!.ok); assertTrue(results["tempo"]!!.ok)
        val capped = results["inbox"] as TeamBus.Sent.Refused
        assertEquals(TeamBus.Why.HOP_CAP, capped.why)
        assertTrue(capped.text, capped.text.contains("hop 3 of 3") && capped.text.contains("Answer the user directly"))
        assertFalse(prompts.any { it.first == "circle" })
        // child threads share the root
        assertEquals(listOf("t1", "t1", "t1"), listOf("t1", "t2", "t3").map { bus.thread(it)!!.root })
        assertEquals("t2", bus.thread("t3")!!.parent)
        // chain for Policy (lowest permission along it): nearest sender first
        assertEquals(listOf("atlas"), budgets[0].chain); assertEquals(listOf("ledger", "atlas"), budgets[1].chain)
        assertEquals(listOf("tempo", "ledger", "atlas"), budgets[2].chain)
    }

    @Test fun aBotsOwnMaxHopsLowersTheCap() {
        val ledger = team.get("ledger")!!
        port.putBot(ledger.copy(permissions = ledger.permissions.copy(maxHops = 1)))
        var nested: TeamBus.Sent? = null
        script = { id, _, b -> if (id == "ledger") nested = bus.send("ledger", "tempo", "x", via = b); BotReply("ok") }
        bus.send("atlas", "ledger", "hi")
        assertEquals(TeamBus.Why.HOP_CAP, (nested as TeamBus.Sent.Refused).why)
        assertEquals(1, BotMessaging.maxHops(team.get("ledger"))); assertEquals(3, BotMessaging.maxHops(team.get("atlas")))
    }

    // ------------------------------------------------------------------------------------------------ budget

    @Test fun threadBudgetIsTwentyCallsAndEndsTheThread() {
        script = { _, _, _ -> BotReply("answer", calls = 8) }
        assertTrue(bus.send("atlas", "ledger", "q1").ok)
        assertEquals(12, bus.thread("t1")!!.budgetLeft)
        assertTrue(bus.send("atlas", "ledger", "q2", thread = "t1").ok)
        assertEquals(4, bus.budgetLeft("t1"))
        val last = bus.send("atlas", "ledger", "q3", thread = "t1") as TeamBus.Sent.Replied
        assertTrue(last.text, last.text.contains("thread budget used up"))
        val t = bus.thread("t1")!!
        assertEquals(0, t.budgetLeft); assertEquals(BusState.OUT_OF_BUDGET, t.state); assertTrue(t.state.ended)
        assertEquals(HandoffStatus.OUT_OF_BUDGET, card("atlas", "t1")!!.status)
        assertEquals("Stopped \u00b7 thread budget used", card("ledger", "t1")!!.statusText)
        val again = bus.send("atlas", "ledger", "q4", thread = "t1") as TeamBus.Sent.Refused
        assertEquals(TeamBus.Why.THREAD, again.why)
        assertEquals(3, prompts.size)
    }

    @Test fun hopsShareTheRootBudgetAndAnEmptyPoolRefuses() {
        var nested: TeamBus.Sent? = null
        var charge: Boolean? = null
        script = { id, _, b ->
            if (id == "ledger") {
                assertTrue(b.charge(19))           // the engine charges as it goes
                charge = b.charge()                // the 20th call: allowed, but nothing is left after it
                nested = bus.send("ledger", "tempo", "x", via = b)
                BotReply("done", calls = 20)       // engine reports 20 in total: nothing more is charged
            } else BotReply("never")
        }
        bus.send("atlas", "ledger", "big question")
        assertEquals(false, charge)
        assertEquals(TeamBus.Why.BUDGET, (nested as TeamBus.Sent.Refused).why)
        assertEquals(0, bus.budgetLeft("t1"))
        assertFalse(prompts.any { it.first == "tempo" })
        assertEquals(BusState.OUT_OF_BUDGET, bus.thread("t1")!!.state)
        assertFalse(budgets[0].charge())
    }

    @Test fun aNestedHopSpendsFromTheSamePool() {
        script = { id, _, b -> if (id == "ledger") { bus.send("ledger", "tempo", "x", via = b); BotReply("ok", calls = 3) } else BotReply("tempo", calls = 5) }
        bus.send("atlas", "ledger", "q")
        assertEquals(20 - 5 - 3, bus.budgetLeft("t1"))
        assertEquals(12, bus.thread("t2")!!.budgetLeft)  // child mirrors the shared pool
    }

    // ------------------------------------------------------------------------------------------------ redaction

    private fun line(id: Long, text: String, scope: String, private: Boolean = false, share: Set<String> = emptySet()) =
        MemScopes.Line(id = id, text = text, scope = scope, sensitivity = if (private) MemScopes.SENSITIVE_PRIVATE else 0, shareTags = share)

    @Test fun askIsRedactedToWhatTheReceiverMayRead() {
        mem = mapOf("pulse" to listOf(
            line(11, "Finn has a 7am run Tuesday", "health", share = setOf("share:social")),
            line(12, "resting heart rate averaged 48 bpm this week", "health", private = true),
            line(13, "Finn's name is Finn Banda", MemScopes.USER_CORE),
        ))
        script = { _, _, _ -> BotReply("Noted, I'll plan around it.") }
        val ask = "FYI Finn has a 7am run Tuesday. Also his resting heart rate averaged 48 bpm   this week (#12)."
        val r = bus.send("pulse", "circle", ask) as TeamBus.Sent.Replied
        val got = prompts.single().second
        assertTrue(got, got.contains("Finn has a 7am run Tuesday"))
        assertFalse(got, got.contains("48 bpm")); assertFalse(got, got.contains("#12"))
        assertTrue(got.contains(BotMessaging.Redact.WITHHELD))
        // the receiver's card and the transcript hold the redacted ask; the asker's card keeps its own words
        assertFalse(card("circle", "t1")!!.ask.contains("48"))
        assertEquals(1, card("circle", "t1")!!.withheld)
        assertTrue(card("pulse", "t1")!!.ask.contains("48 bpm"))
        assertFalse(RowBody.text(bus.transcript("t1").first()).contains("48"))
        assertEquals("1", RowBody.field(bus.transcript("t1").first(), "withheld"))
        assertEquals(0, r.withheld)
    }

    @Test fun replyIsRedactedForTheAskerButSharedLinesPass() {
        mem = mapOf("ledger" to listOf(
            line(21, "Trip budget for Cape Town: ZMW 18,000", "money", share = setOf("share:travel")),
            line(22, "Checking account balance is ZMW 3,200", "money", private = true),
        ))
        script = { _, _, _ -> BotReply("Trip budget for Cape Town: ZMW 18,000. For context, checking account balance is ZMW 3,200.\n" +
            "- #31 [money] Netflix renews on the 3rd\n- #40 [team] Finn travels 14-20 Dec") }
        val r = bus.send("atlas", "ledger", "Budget left?") as TeamBus.Sent.Replied
        assertTrue(r.reply, r.reply.contains("ZMW 18,000"))
        assertFalse(r.reply, r.reply.contains("3,200"))
        assertFalse("block line from a scope Atlas can't read", r.reply.contains("Netflix"))
        assertTrue("team lines pass", r.reply.contains("Finn travels 14-20 Dec"))
        assertEquals(2, r.withheld)
        assertTrue(r.text.contains("2 note(s) you can't see were withheld"))
        assertFalse(card("atlas", "t1")!!.reply.contains("3,200"))
        assertTrue("the answerer's card keeps its own reply", card("ledger", "t1")!!.reply.contains("3,200"))
    }

    @Test fun redactPassRules() {
        val social = MemScopes.Viewer("circle", "social")
        val lines = listOf(line(1, "resting heart rate 48", "health", private = true), line(2, "short", "health", private = true),
            line(3, "Atlas working note about seat maps", "bot:atlas"))
        val o = BotMessaging.Redact.pass("Resting  HEART rate 48 and short. - see #2.\n- #9 [your notes] my seat map trick\n- #8 [from Atlas] x", lines, "pulse", social,
            names = mapOf("atlas" to "Atlas"))
        assertFalse(o.text, o.text.contains("48"))
        assertTrue("under MIN_MATCH text is not matched", o.text.contains("short"))
        assertFalse("#2 ref replaced", o.text.contains("#2"))
        assertFalse("your notes = the writer's bot scope", o.text.contains("seat map trick"))
        assertFalse("from Atlas = bot:atlas", o.text.contains("- #8"))
        assertEquals(setOf(1L, 2L, 9L, 8L), o.withheld.toSet())
        assertEquals("bot:pulse", BotMessaging.Redact.labelScope("your notes", "pulse"))
        assertEquals("user:people", BotMessaging.Redact.labelScope("about the user: people", "x"))
        assertEquals("money", BotMessaging.Redact.labelScope("money", "x"))
        assertFalse(BotMessaging.Redact.pass("nothing here", lines, "pulse", social).changed)
        // the Manager reads non-private domains through its reports but never private ones
        val mgr = bus.viewer(team.get("manager")!!)
        assertFalse(MemScopes.visible(mgr, lines[0]))
    }

    // ------------------------------------------------------------------------------------------------ ends

    @Test fun staySilentEndsTheThread() {
        script = { id, _, b ->
            val out = bus.exec("stay_silent", JSONObject().put("reason", "nothing to add"), team.get(id)!!, via = b)
            assertTrue(out.silent); assertTrue(out.text, out.text.contains("thread t1 closes"))
            BotReply("", calls = 1)
        }
        val r = bus.send("atlas", "circle", "Anyone free Saturday?") as TeamBus.Sent.Silent
        assertTrue(r.text, r.text.contains("chose not to reply"))
        assertEquals(BusState.SILENT, bus.thread("t1")!!.state)
        assertEquals(HandoffStatus.SILENT, card("atlas", "t1")!!.status); assertEquals("No reply needed", card("circle", "t1")!!.statusText)
        assertEquals(1, bus.transcript("t1").size)
        assertEquals(TeamBus.Why.THREAD, (bus.send("atlas", "circle", "hello?", thread = "t1") as TeamBus.Sent.Refused).why)
        // outside a bot turn it just ends the turn
        assertEquals("[stay_silent] Ending this turn without a message.", bus.staySilent("tempo", null))
        // a BotReply.silent() and a blank answer are silence too
        script = { _, _, _ -> BotReply.silent() }
        assertTrue(bus.send("atlas", "circle", "q2") is TeamBus.Sent.Silent)
    }

    @Test fun expectsReplyFalseReturnsAtOnceAndTheCardGoesLive() {
        inline = false
        script = { id, _, b -> bus.staySilent(id, b); BotReply("") }
        val r = bus.exec("send_to_bot", JSONObject().put("bot", "Tempo").put("message", "Block 14-20 Dec").put("expects_reply", false), team.get("atlas")!!)
        assertTrue(r.text, r.ok && r.text.contains("no reply expected"))
        assertTrue(prompts.isEmpty())
        val live = card("atlas", "t1")!!
        assertEquals(HandoffStatus.ASKING, live.status); assertTrue(live.status.live); assertFalse(live.expectsReply)
        assertTrue(prompts.isEmpty())
        queue.removeAt(0).invoke()
        assertTrue(prompts.single().second.contains("doesn't need an answer"))
        assertEquals(BusState.DONE, bus.thread("t1")!!.state)
        assertEquals(HandoffStatus.DELIVERED, card("atlas", "t1")!!.status); assertEquals(HandoffStatus.DELIVERED, card("tempo", "t1")!!.status)
    }

    @Test fun cardIsWorkingWhileTheReceiverRuns() {
        script = { _, _, _ ->
            assertEquals(HandoffStatus.WORKING, card("atlas", "t1")!!.status)
            assertEquals("Ledger is on it", card("atlas", "t1")!!.statusText)
            BotReply("done")
        }
        bus.send("atlas", "ledger", "q")
        assertEquals(HandoffStatus.REPLIED, card("atlas", "t1")!!.status)
    }

    @Test fun aFailedTurnFailsTheThread() {
        script = { _, _, _ -> throw IllegalStateException("model down") }
        val r = bus.send("atlas", "ledger", "q") as TeamBus.Sent.Failed
        assertTrue(r.text, r.text.contains("Couldn't reach Ledger: model down"))
        assertEquals(BusState.FAILED, bus.thread("t1")!!.state); assertEquals(HandoffStatus.FAILED, card("ledger", "t1")!!.status)
        assertFalse(r.ok)
    }

    // ------------------------------------------------------------------------------------------------ loops + who

    @Test fun loopGuardStopsTheSameAskWithTheSameAnswer() {
        script = { _, _, _ -> BotReply("I don't know.") }
        assertTrue(bus.send("atlas", "ledger", "What's my balance?").ok)
        assertTrue(bus.send("atlas", "ledger", "what's my  balance?").ok)
        val third = bus.send("atlas", "ledger", "What's my balance?") as TeamBus.Sent.Refused
        assertEquals(TeamBus.Why.LOOP, third.why)
        assertTrue(third.text, third.text.contains("already ran twice"))
        assertEquals(2, prompts.size)
        assertTrue("a different ask goes through", bus.send("atlas", "ledger", "Budget for December?").ok)
        bus.endTurn("atlas")
        assertTrue("a new user turn starts clean", bus.send("atlas", "ledger", "What's my balance?").ok)
    }

    @Test fun aBotCantMessageTheBotWaitingOnItOrAnswerThroughSend() {
        var back: TeamBus.Sent? = null; var through: TeamBus.Sent? = null
        script = { id, _, b ->
            if (id == "ledger") { back = bus.send("ledger", "atlas", "what dates?", via = b); through = bus.send("ledger", "", "x", thread = b.threadId, via = b) }
            BotReply("ok")
        }
        bus.send("atlas", "ledger", "q")
        assertEquals(TeamBus.Why.WAITING_ON_YOU, (back as TeamBus.Sent.Refused).why)
        assertEquals(TeamBus.Why.WAITING_ON_YOU, (through as TeamBus.Sent.Refused).why)
        assertEquals(1, prompts.size)
    }

    @Test fun whoCanBeMessaged() {
        fun why(from: String, to: String, msg: String = "hi") = (bus.send(from, to, msg) as? TeamBus.Sent.Refused)?.why
        assertEquals(TeamBus.Why.SELF, why("atlas", "Atlas"))
        assertEquals(TeamBus.Why.UNKNOWN_BOT, why("atlas", "Nobody"))
        assertTrue(bus.send("atlas", "Nobody", "hi").text.contains("Teammates: ARGUS"))
        assertEquals(TeamBus.Why.EMPTY, why("atlas", "ledger", "  "))
        port.putBot(Bots.domain("weekend", "Weekend", "Room", "room", 0, Bots.COLORS[4], emptySet(), emptyList(), now).copy(kind = BotKind.ROOM))
        assertEquals(TeamBus.Why.ROOM, why("atlas", "weekend"))
        port.putBot(team.get("circle")!!.copy(enabled = false))
        assertEquals(TeamBus.Why.DISABLED, why("atlas", "circle"))
        // a hidden subagent talks to its parent only, and only its parent reaches it
        port.putBot(Bots.worker("booker", "Booker", setOf("browser"), emptyList(), now).copy(id = "sub1", kind = BotKind.SUBAGENT, parentId = "atlas", reportsTo = "atlas"))
        assertTrue(bus.send("sub1", "atlas", "3 options found").ok)
        assertEquals(TeamBus.Why.HIDDEN, why("sub1", "ledger"))
        assertEquals(TeamBus.Why.HIDDEN, why("ledger", "sub1"))
        assertTrue(bus.send("atlas", "sub1", "pick the cheapest").ok)
        assertEquals(TeamBus.Why.UNKNOWN_BOT, why("ghost", "atlas"))
        // the Manager is a teammate like any other, by name
        assertTrue(bus.send("ledger", "ARGUS", "FYI rent is due").ok)
    }

    // ------------------------------------------------------------------------------------------------ no side doors

    @Test fun noSideDoors() {
        val call = """{"tool":"money_transfer","arguments":{"amount":500}}"""
        val r = bus.send("atlas", "ledger", call) as TeamBus.Sent.Refused
        assertEquals(TeamBus.Why.TOOL_CALL, r.why)
        assertTrue(BotMessaging.looksLikeToolCall("```json\n{\"name\":\"book\",\"arguments\":{}}\n```"))
        assertFalse(BotMessaging.looksLikeToolCall("Can you check {the budget}?"))
        assertFalse(BotMessaging.looksLikeToolCall("{\"note\":\"just data\"}"))
        assertTrue(prompts.isEmpty())

        val roster = team.all(hidden = true)
        val atlas = team.get("atlas")!!
        val note = BotMessaging.sideDoor(atlas, "money_balance", "money", roster)!!
        assertTrue(note, note.contains("belongs to Ledger") && note.contains("send_to_bot"))
        assertNull(BotMessaging.sideDoor(atlas, "web_search", "web", roster))
        assertNull(BotMessaging.sideDoor(atlas, "send_to_bot", "team", roster))
        assertNull(BotMessaging.sideDoor(atlas, "memory_save", "memory", roster))
        assertNull(BotMessaging.sideDoor(atlas.copy(toolGrants = mapOf("money_balance" to Rule.ASK)), "money_balance", "money", roster))
        assertEquals("Not run: lab_tool isn't one of your tools.", BotMessaging.sideDoor(atlas, "lab_tool", "lab", roster))
        // the receiver runs as itself: the runner is asked for ledger's turn, never atlas's tools
        script = { id, _, b -> assertEquals("ledger", id); assertEquals("ledger", b.botId); BotReply("ok") }
        bus.send("atlas", "ledger", "Please check the budget")
    }

    // ------------------------------------------------------------------------------------------------ provenance + reads

    @Test fun provenanceBadgesAndTriggers() {
        val names = mapOf("juno" to "Juno", "r1" to "Morning brief")
        assertEquals("from Juno", BotMessaging.badge(Provenance.parse("bot:juno"), "mira", names))
        assertEquals("routine Morning brief", BotMessaging.badge(Provenance.parse("routine:r1"), null, names))
        assertEquals("said on a call", BotMessaging.badge(Provenance.parse("voice"), null))
        assertNull(BotMessaging.badge(Provenance.parse("user"), null))
        assertNull("own replies carry no badge", BotMessaging.badge(Provenance.parse("mira", "mira"), "mira"))
        assertEquals("from Juno", BotMessaging.badge(Provenance.parse("juno", "mira"), "mira", names))   // P1 plain-id rows
        assertNull(Provenance.parse(""))
        assertEquals("bot:juno", Provenance.bot("juno").from); assertEquals("routine:r1", Provenance.routine("r1").from)
        assertEquals("voice", Provenance.VOICE.from)
        assertEquals("[Routine Morning brief is running now]", BotMessaging.trigger(Provenance.routine("r1"), names))
        assertEquals("[Message from Juno, another bot on your team]", BotMessaging.trigger(Provenance.bot("juno"), names))
        assertEquals("[System notice]", BotMessaging.trigger(Provenance.SYSTEM))
        // on a stored row
        val r = threads.post("atlas", RowBody.ASSISTANT, "hi", from = Provenance.routine("r1").from)
        assertEquals("routine Morning brief", BotMessaging.badge(r, names))
        assertNull(BotMessaging.badge(threads.post("atlas", RowBody.ASSISTANT, "mine")))
    }

    @Test fun talkLineAndCardRoundTrip() {
        assertEquals("Money \u2194 Travel \u00b7 1 message", BotMessaging.talkLine("Money", "Travel", 1))
        assertEquals("Money \u2194 Travel \u00b7 3 messages", BotMessaging.talkLine("Money", "Travel", 3))
        val h = Handoff("t9", "t1", "atlas", "ledger", "Atlas", "Ledger", "Budget?", HandoffStatus.WORKING, messages = 3, depth = 2, budgetLeft = 7, withheld = 1)
        val back = Handoff.of(h.body())!!
        assertEquals(h, back)
        assertEquals("Atlas asked Ledger: Budget? Ledger is on it. Atlas \u2194 Ledger \u00b7 3 messages. 1 private note withheld.", h.spoken)
        assertEquals(true, JSONObject(h.body()).getBoolean("live"))
        assertNull(Handoff.of(RowBody.card("job", mapOf("title" to "x"))))
        assertNull(Handoff.of("not json"))
        assertEquals(listOf(true, true, false, false, false, false, false), HandoffStatus.values().map { it.live })
        assertEquals(HandoffStatus.OUT_OF_BUDGET, HandoffStatus.of("out_of_budget"))
        assertEquals(BotMessaging.PREVIEW, BotMessaging.preview("x".repeat(500)).length)
        assertTrue(BotMessaging.isTranscriptKey(BotMessaging.transcriptKey("t1")))
    }

    @Test fun talkStatsForPeopleAndTheTeamList() {
        script = { _, _, _ -> BotReply("ok") }
        bus.send("atlas", "ledger", "a"); now += 1000
        bus.send("ledger", "atlas", "b"); now += 1000
        bus.send("atlas", "tempo", "c")
        val t = bus.talk("atlas")
        assertEquals(listOf("tempo", "ledger"), t.map { it.other })
        val l = t.first { it.other == "ledger" }
        assertEquals(2, l.threads); assertEquals(4, l.messages); assertEquals(1, l.asked); assertEquals(1, l.answered)
        assertEquals(2, bus.pairs()[("atlas" to "ledger")]); assertEquals(1, bus.pairs(now)[("atlas" to "tempo")])
        assertEquals(listOf("t3", "t2", "t1"), bus.threadsOf("atlas").map { it.id })
        // transcript rows never show up as roster threads
        assertTrue(threads.list().none { BotMessaging.isTranscriptKey(it.bot.id) })
    }

    @Test fun schemas() {
        val all = BotMessaging.schemas().map { it.getJSONObject("function") }
        assertEquals(listOf("send_to_bot", "stay_silent"), all.map { it.getString("name") })
        assertEquals("[\"bot\",\"message\"]", all[0].getJSONObject("parameters").getJSONArray("required").toString())
        assertNotNull(all[0].getJSONObject("parameters").getJSONObject("properties").opt("expects_reply"))
        assertEquals("[unknown] unknown tool", bus.exec("unknown", JSONObject(), team.get("atlas")!!).text)
    }
}
