package com.blaze.assistant.team

import com.blaze.assistant.store.JdbcSql
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * P1-TM-06: routing v1 on a real team.db (sqlite-jdbc). Ports the A-team command router cases (app.js structuredParse,
 * cardParse, agentList, ruleParse, toActions, applyActions; chat.js CHAT.home / mention; agentSend CMDISH) and adds the
 * ticket's checks: @mention / name routing, delegate = job + HandoffCard, the fallback order exact → Needle → gateway →
 * rules, offline add-job, the preview rules and `$ cmd`.
 */
class RoutingTest {
    private val sql = JdbcSql()
    private var now = 1_731_400_000_000L
    private var ids = 0
    private val today = LocalDate.of(2026, 11, 12)   // a Thursday
    private val team = TeamStore(sql) { now }
    private val jobs = JobStore(sql, { now }) { "j${++ids}" }
    private val posted = ArrayList<Triple<String, String, String>>()
    private var kicks = 0
    private val router = Router(team, jobs, today = { today }, kick = { kicks++ }, post = { b, k, body -> posted += Triple(b, k, body) })

    init {
        sql.exec("PRAGMA foreign_keys = ON")
        sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
        team.ensure()
        for ((id, name) in listOf("nova" to "Nova", "atlas" to "Atlas Reed", "echo" to "Echo"))
            team.insert(Bot(id = id, kind = BotKind.CUSTOM, name = name, label = "", avatar = Avatar("orb", "#123456"), soulPath = "",
                toolGroups = setOf("web"), createdAt = now, u = now, email = if (id == "nova") "nova@team.ai" else ""))
    }

    @After fun close() = sql.close()

    /** Records which lane ran, in order. */
    private class Lanes(val needleCalls: List<TeamCommands.Call>? = null, val cloud: Boolean = true, val gw: String? = null) : Router.Lanes {
        val order = ArrayList<String>()
        override suspend fun needle(clause: String): Router.Needle? { order += "needle"; return needleCalls?.let { Router.Needle(it, 40) } }
        override fun cloud(): Boolean = cloud
        override suspend fun gateway(system: String, text: String): String? { order += "gateway"; return gw }
    }

    private fun call(name: String, vararg a: Pair<String, String>) = TeamCommands.Call(name, mapOf(*a))
    private fun job(title: String, bot: String?) = (jobs.add(title, "", bot) as JobStore.Added.New).job

    // ---------------- exact parsers ----------------

    @Test fun structuredFormMakesBotAndJob() {
        val c = TeamCommands.structured("Name: Zed, email: zed@x.ai, role: sales, task: call Zamtel, due: tomorrow") { false }!!
        assertEquals(listOf("add_agent", "add_task"), c.map { it.name })
        assertEquals("zed@x.ai", c[0].a("email")); assertEquals("call Zamtel", c[1].a("title")); assertEquals("Zed", c[1].a("agent"))
        assertNull(TeamCommands.structured("Name: only one field") { false })
    }

    @Test fun cardAndListParse() {
        assertEquals("Rhea", TeamCommands.card("Rhea, nova@hitch.io")!![0].a("name"))
        assertEquals("research", TeamCommands.card("Nova nova@hitch.io research")!![0].a("role"))
        assertNull(TeamCommands.card("send the deck to nova@hitch.io"))
        val l = TeamCommands.agentList("add Ann, Bob and Cy")!!
        assertEquals(listOf("Ann", "Bob", "Cy"), l.map { it.a("name") })
        val l2 = TeamCommands.agentList("add Ann (ann@x.ai, sales), Bob Lee as design")!!
        assertEquals("ann@x.ai", l2[0].a("email")); assertEquals("sales", l2[0].a("role")); assertEquals("design", l2[1].a("role"))
        assertNull(TeamCommands.agentList("add a job for Ann and Bob"))
    }

    @Test fun rulesAndCleanTitle() {
        assertEquals("add_agent", TeamCommands.rules("add agent Vega, finance vega@x.ai") { false }[0].name)
        assertEquals("add_task", TeamCommands.rules("Echo: book the venue") { it == "Echo" }[0].name)
        assertEquals("complete_task", TeamCommands.rules("done send the deck") { false }[0].name)
        assertEquals("Send the deck", TeamCommands.cleanTitle("send the deck by Friday"))
        assertEquals(listOf("add Ann", "give Bob the deck"), TeamCommands.clauses("add Ann and then give Bob the deck"))
    }

    @Test fun dueWords() {
        assertEquals("2026-11-13", TeamCommands.dueIn("send the deck tomorrow", today)!!.value)
        assertEquals("2026-11-13", TeamCommands.dueIn("send the deck Friday", today)!!.value)
        assertEquals("2026-11-15", TeamCommands.dueIn("in 3 days", today)!!.value)
        assertNull(TeamCommands.dueIn("send the deck", today))
        assertEquals("2026-11-13", TeamCommands.dueWord("this_week", today))
        assertEquals("2026-11-16", TeamCommands.dueWord("next_week", today))
        assertNull(TeamCommands.dueWord("none", today))
    }

    @Test fun gatewayReplyParsesEveryShape() {
        assertEquals("add_task", TeamCommands.parseCalls("<think>hm</think>```json\n{\"calls\":[{\"name\":\"add_task\",\"arguments\":{\"title\":\"x\"}}]}\n```")[0].name)
        assertEquals("x", TeamCommands.parseCalls("[{\"function\":{\"name\":\"add_task\",\"arguments\":\"{\\\"title\\\":\\\"x\\\"}\"}}]")[0].a("title"))
        assertTrue(TeamCommands.parseCalls("no json here").isEmpty())
    }

    // ---------------- routing ----------------

    @Test fun mentionAndNameRouting() {
        val team0 = Router.Where.Team
        assertEquals("atlas", (router.route("@Atlas find flights LUN to CPT", team0) as Router.Route.Delegate).bot.id)
        assertEquals("find flights", (router.route("@atlas find flights", team0) as Router.Route.Delegate).task)
        assertEquals("", (router.route("@Nova", team0) as Router.Route.Delegate).task)
        assertEquals("nova", (router.route("Nova, draft the brief", team0) as Router.Route.Delegate).bot.id)
        assertEquals("echo", (router.route("ask Echo to book the venue", team0) as Router.Route.Delegate).bot.id)
        // @Argus is the Manager: the rest is plain chat
        assertEquals(Router.Route.Chat("what's on today?"), router.route("@Argus what's on today?", team0))
        // not a bot: conversation; a typo never routes by name
        assertTrue(router.route("Novaa, draft the brief", team0) is Router.Route.Chat)
        assertTrue(router.route("@Zed hello", team0) is Router.Route.Chat)
        // a contact card is a command, not a hand-off
        assertTrue(router.route("Nova, nova@new.ai", team0) is Router.Route.Apply)
        // in Atlas's own thread, @Atlas is just Atlas
        assertTrue(router.route("Atlas, how is it going?", Router.Where.Thread("atlas")) is Router.Route.Chat)
    }

    @Test fun delegateIsJobPlusHandoffCard() = runBlocking {
        val out = router.handle("@Atlas find flights LUN to CPT for Jan 5", Router.Where.Team, Lanes(), instant = false) as Router.Outcome.Delegated
        assertEquals("Atlas Reed is on it.", out.handed.line)
        val j = jobs.forBot("atlas").single()
        assertEquals("Find flights LUN to CPT for Jan 5", j.title); assertEquals("user", j.source); assertEquals(JobRun.QUEUED, j.run)
        val (bot, kind, body) = posted.single()
        assertEquals("atlas", bot); assertEquals(RowKind.CARD, kind)
        val b = RowBodies.read(body)
        assertEquals("handoff", b["card"]); assertEquals(BotRules.MANAGER_ID, b["from"]); assertEquals("Atlas Reed", b["toName"]); assertEquals(j.id, b["jobId"])
        assertEquals(1, kicks)
        // the same ask again: no second job, no second card
        val again = router.handle("@Atlas find flights LUN to CPT for Jan 5", Router.Where.Team, Lanes(), instant = false) as Router.Outcome.Delegated
        assertTrue(again.handed.duplicate); assertEquals(1, jobs.forBot("atlas").size); assertEquals(1, posted.size)
    }

    @Test fun teamAddJobAppliesAtOnce() = runBlocking {
        val out = router.handle("add job send the deck Friday for Echo", Router.Where.Team, Lanes(), instant = false) as Router.Outcome.Done
        val j = jobs.forBot("echo").single()
        assertEquals("Send the deck", j.title); assertEquals("2026-11-13", j.due)
        assertEquals(1, out.applied.count)
        // unassigned when no bot is named
        router.handle("job: water the plants", Router.Where.Team, Lanes(), instant = false)
        assertNull(jobs.all().first { it.title == "Water the plants" }.botId)
    }

    @Test fun offlineAddJobWorks() = runBlocking {
        // no model, no network: "add job …" in ARGUS's chat is still a job (for ARGUS), with no lane asked
        val lanes = Lanes(needleCalls = null, cloud = false)
        val out = router.handle("add job send the deck Friday", Router.Where.Thread(BotRules.MANAGER_ID), lanes, instant = false)
        assertTrue(out is Router.Outcome.Done)
        val j = jobs.all().single()
        assertEquals(BotRules.MANAGER_ID, j.botId); assertEquals("2026-11-13", j.due)
        assertTrue(lanes.order.isEmpty())
        // offline, a command-ish line in a thread goes through the rules and lands on that thread's bot
        val out2 = router.handle("give Echo the venue booking tomorrow", Router.Where.Thread("nova"),
            Lanes(needleCalls = listOf(call("add_task", "agent" to "Echo", "title" to "venue booking", "due" to "tomorrow")), cloud = false), instant = false)
        assertTrue(out2 is Router.Outcome.Done)
        assertEquals("2026-11-13", jobs.forBot("echo").single().due)
    }

    @Test fun threadCommandsGoToTheBotWhenOnline() = runBlocking {
        val lanes = Lanes(cloud = true)
        assertEquals(Router.Outcome.Chat("mark the deck done"), router.handle("mark the deck done", Router.Where.Thread("nova"), lanes, instant = false))
        assertTrue(lanes.order.isEmpty())
    }

    // ---------------- interpret: the fallback order ----------------

    @Test fun fallbackOrderExactNeedleGatewayRules() = runBlocking {
        // 1. exact parse: no lane runs
        val l1 = Lanes(needleCalls = listOf(call("add_task", "title" to "x")))
        assertEquals(Router.VIA_EXACT, router.interpret("Name: Zed, task: call Zamtel", l1).via)
        assertTrue(l1.order.isEmpty())
        // 2. Needle finds it: the gateway is never asked
        val l2 = Lanes(needleCalls = listOf(call("add_task", "agent" to "Nova", "title" to "draft the brief")), gw = "{\"calls\":[]}")
        val p2 = router.interpret("give Nova the brief to draft", l2)
        assertEquals(listOf("needle"), l2.order)
        assertTrue(p2.via.startsWith(Router.VIA_PHONE))
        // 3. Needle finds nothing → the gateway
        val l3 = Lanes(needleCalls = emptyList(), gw = "{\"calls\":[{\"name\":\"add_task\",\"arguments\":{\"agent\":\"Echo\",\"title\":\"book venue\"}}]}")
        val p3 = router.interpret("Echo should sort the venue", l3)
        assertEquals(listOf("needle", "gateway"), l3.order); assertEquals(Router.VIA_CLOUD, p3.via)
        // 3b. offline or signed out: the gateway is skipped
        val l3b = Lanes(needleCalls = emptyList(), cloud = false, gw = "{\"calls\":[{\"name\":\"add_task\",\"arguments\":{\"title\":\"x\"}}]}")
        router.interpret("Echo should sort the venue", l3b)
        assertEquals(listOf("needle"), l3b.order)
        // 4. nothing from either → quick rules
        job("send the deck", "nova")
        val l4 = Lanes(needleCalls = null, gw = "nonsense")
        val p4 = router.interpret("done send the deck", l4)
        assertEquals(listOf("needle", "gateway"), l4.order)
        assertEquals(Router.VIA_RULES, p4.via); assertTrue(p4.actions.single() is Router.Act.Complete)
        // a lane that throws counts as finding nothing
        val boom = object : Router.Lanes {
            override suspend fun needle(clause: String): Router.Needle? = throw IllegalStateException("runner died")
            override fun cloud() = false
            override suspend fun gateway(system: String, text: String): String? = null
        }
        assertEquals(Router.VIA_RULES, router.interpret("done send the deck", boom).via)
    }

    @Test fun needleRunsClauseByClause() = runBlocking {
        val seen = ArrayList<String>()
        val l = object : Router.Lanes {
            override suspend fun needle(clause: String): Router.Needle? { seen += clause; return Router.Needle(listOf(call("add_task", "title" to clause)), 10) }
            override fun cloud() = false
            override suspend fun gateway(system: String, text: String): String? = null
        }
        val p = router.interpret("give Nova the brief; give Echo the venue", l)
        assertEquals(listOf("give Nova the brief", "give Echo the venue"), seen)
        assertEquals(2, p.actions.size); assertEquals("${Router.VIA_PHONE} \u00b7 20 ms", p.via)
    }

    // ---------------- actions and apply ----------------

    @Test fun actionsReadTheTeam() {
        val deck = job("send the deck", "nova")
        val acts = router.actions(listOf(
            call("add_agent", "name" to "Nova", "email" to "nova@new.ai"),
            call("add_agent", "name" to "Vega", "role" to "finance"),
            call("add_task", "agent" to "Vega", "title" to "check the budget", "due" to "tomorrow"),
            call("add_task", "agent" to "Zed", "title" to "call Zamtel"),
            call("complete_task", "title" to "the deck"),
            call("reschedule_task", "title" to "deck", "due" to "next_week", "agent" to "Echo"),
            call("remove", "kind" to "task", "name" to "nothing like it"),
        ), "add things")
        assertTrue(acts[0] is Router.Act.UpdateBot)
        assertTrue(acts[1] is Router.Act.AddBot)
        val vega = acts[2] as Router.Act.AddJob
        assertNull(vega.botId); assertFalse(vega.createBot)                 // Vega is being made in the same command
        assertTrue((acts[3] as Router.Act.AddJob).createBot)                // Zed isn't on the team: made with the job
        assertEquals(deck.id, (acts[4] as Router.Act.Complete).jobId)
        val mv = acts[5] as Router.Act.Move
        assertEquals("2026-11-16", mv.due); assertEquals("echo", mv.botId)
        assertTrue(acts[6] is Router.Act.Miss)
    }

    @Test fun applyMakesTheChanges() {
        val deck = job("send the deck", "nova")
        val a = router.apply(router.actions(listOf(
            call("add_agent", "name" to "Vega", "email" to "vega@x.ai", "role" to "finance"),
            call("add_task", "agent" to "Vega", "title" to "check the budget"),
            call("complete_task", "title" to "deck")), "x"))
        assertEquals(3, a.count)
        val vega = team.roster().first { it.name == "Vega" }
        assertEquals("vega@x.ai", vega.email); assertEquals("finance", vega.label)
        assertEquals("Check the budget", jobs.forBot(vega.id).single().title)
        assertEquals(JobRun.DONE, jobs.get(deck.id)!!.run)
        // a bad email is refused, counted as nothing
        assertEquals(0, router.apply(listOf(Router.Act.AddBot("Kim", "not-an-email", "", ""))).count)
        // the Manager is never removed
        assertTrue(router.actions(listOf(call("remove", "kind" to "agent", "name" to "Argus")), "remove agent Argus").single() is Router.Act.Miss)
    }

    // ---------------- the preview sheet ----------------

    @Test fun previewUnlessInstantAndRemovalsAlwaysAsk() = runBlocking {
        job("send the deck", "nova")
        val lanes = Lanes(needleCalls = listOf(call("reschedule_task", "title" to "deck", "due" to "tomorrow")))
        val p = router.handle("move the deck to tomorrow", Router.Where.Team, lanes, instant = false)
        assertTrue(p is Router.Outcome.Preview)
        assertNull(jobs.all().single().due)                                   // nothing applied before Apply
        val d = router.handle("move the deck to tomorrow", Router.Where.Team, lanes, instant = true)
        assertTrue(d is Router.Outcome.Done)
        assertEquals("2026-11-13", jobs.all().single().due)
        val rm = router.handle("delete the deck job", Router.Where.Team, Lanes(needleCalls = listOf(call("remove", "kind" to "task", "name" to "deck"))), instant = true)
        assertTrue(rm is Router.Outcome.Preview)
        assertEquals(1, jobs.all().size)
        // a reading that misses its target goes to ARGUS instead (A-team)
        val miss = router.handle("mark the budget done", Router.Where.Team, Lanes(needleCalls = listOf(call("complete_task", "title" to "budget"))), instant = true)
        assertEquals(Router.Outcome.Chat("mark the budget done"), miss)
    }

    @Test fun shellAndChat() = runBlocking {
        assertEquals(Router.Outcome.Shell("ls -la ~"), router.handle("$ ls -la ~", Router.Where.Thread(BotRules.MANAGER_ID), Lanes(), instant = false))
        assertEquals(Router.Outcome.Shell("uname -a"), router.handle("\$uname -a", Router.Where.Team, Lanes(), instant = false))
        assertEquals(Router.Outcome.Chat("what's the weather?"), router.handle("what's the weather?", Router.Where.Team, Lanes(), instant = false))
        assertNotNull(TeamCommands.shell("$ x")); assertNull(TeamCommands.shell("costs $5"))
    }

    @Test fun needleToolsAreTheFiveCommands() {
        @Suppress("UNCHECKED_CAST")
        val tools = TeamJson.read(TeamCommands.NEEDLE_TOOLS) as List<Map<String, Any?>>
        assertEquals(TeamCommands.NAMES, tools.map { it["name"] }.toSet())
        assertTrue(TeamCommands.gatewaySystem(listOf("Nova"), emptyList(), today).contains("Existing agents: Nova."))
    }

    @Test fun chatOnlyDetoursForTeamWords() {
        for (t in listOf("$ ls", "@Nova hi", "add job x", "mark the deck done", "ask Echo to book it", "Atlas, find flights"))
            assertTrue(t, TeamCommands.maybeTeam(t))
        for (t in listOf("what's the weather in Lusaka?", "how are you", "", "tell"))
            assertFalse(t, TeamCommands.maybeTeam(t))
    }

    @Test fun needleReplyAndShellNote() {
        val r = TeamCommands.needleReply("{\"type\":\"call\",\"function_calls\":[{\"name\":\"add_task\",\"arguments\":{\"title\":\"x\",\"agent\":\"Nova\"}},{\"name\":\"open_app\",\"arguments\":{}}]}")
        assertEquals(listOf("add_task"), r.map { it.name }); assertEquals("Nova", r[0].a("agent"))
        assertEquals("remove", TeamCommands.needleReply("{\"function_calls\":[],\"suppressed_calls\":[{\"name\":\"remove\",\"arguments\":{\"kind\":\"task\",\"name\":\"x\"}}]}").single().name)
        assertTrue(TeamCommands.needleReply("garbage").isEmpty())
        assertEquals("$ uname\n```\nLinux\n```", ShellNote.of("uname", "Linux\n"))
        assertTrue(ShellNote.of("x", "").contains("(no output)"))
        assertTrue(ShellNote.of("x", "a".repeat(5000)).length < 3100)
    }

    @Test fun planSheetModel() {
        job("send the deck", "nova")
        val p = Router.Plan("move the deck to tomorrow and remove it", router.actions(listOf(
            call("reschedule_task", "title" to "deck", "due" to "tomorrow"), call("complete_task", "title" to "budget")), "zzz"), "${Router.VIA_PHONE} \u00b7 120 ms")
        val m = com.blaze.assistant.ui.team.PlanSheetModel
        assertEquals("Apply", m.button(p)); assertTrue(m.enabled(p)); assertTrue(m.showSwitch(p))
        assertEquals(listOf(false, true), m.lines(p).map { it.miss })
        assertEquals("\u201cmove the deck to tomorrow and remove it\u201d \u00b7 On this phone \u00b7 120 ms", m.sub(p))
        assertFalse(m.enabled(Router.Plan("x", listOf(Router.Act.Miss("x")), Router.VIA_RULES)))
        assertFalse(m.showSwitch(Router.Plan("x", listOf(Router.Act.DeleteJob("j1", "x")), Router.VIA_RULES)))
        assertEquals("Done \u00b7 2 changes", m.done(Router.Applied(2, listOf("a", "b"))))
        assertEquals("Apply 2", m.button(Router.Plan("x", listOf(Router.Act.DeleteJob("a", "a"), Router.Act.DeleteJob("b", "b")), "")))
    }
}
