package com.blaze.assistant.team

import com.blaze.assistant.store.JdbcSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * P1-TM-05: the jobs queue on a real SQLite (sqlite-jdbc, team.db's own schema) with a fake runner and clock. Ports the
 * A-team jobs.js cases listed for this ticket in specs.md (a new job is queued and starts; A runs, B waits on it, C
 * waits for its bot; one job at a time per bot; a job the bot didn't close waits for you and its dependant stays
 * queued; marking the blocker done by hand starts the dependant; a gateway failure marks the job failed; add_job of a
 * failed job reopens it; no duplicate job from add_job; agent_status lists a bot's recent jobs), plus the queue rules
 * of 05 §2.3: runaway guard 12 / 10 min, stale runs failed on start, user messages first, the phone's cap, priority,
 * proactive jobs never act unconfirmed, backoff 1 / 5 / 15 min.
 */
class JobsQueueTest {
    private val sql = JdbcSql()
    private var now = 1_731_400_000_000L
    private var ids = 0
    private val team = TeamStore(sql) { now }
    private val store = JobStore(sql, { now }) { "j${++ids}" }
    private val today = LocalDate.of(2026, 11, 12)

    private class Fake : JobQueue.Runner {
        val started = ArrayList<Pair<String, String>>()   // job id → brief
        val reports = ArrayList<Pair<String, JobQueue.Report>>()
        val dead = HashSet<String>()
        var refuse = false
        override fun start(job: Job, bot: Bot, brief: String): String? { if (refuse) return null; started += job.id to brief; return "agent-${job.id}-${started.size}" }
        override fun alive(runId: String) = runId !in dead
        override fun report(job: Job, bot: Bot?, event: JobQueue.Report) { reports += job.id to event }
        fun ids() = started.map { it.first }
    }

    private val run = Fake()
    private var userTurn = HashSet<String>()
    private var cap = 3
    private val queue = JobQueue(store, team, run, { now }, { cap }, { it in userTurn }, { today })
    private val tools = JobTools(store, queue, team, current = { JobTools.jobOfChat(it) }, today = { today })

    init {
        sql.exec("PRAGMA foreign_keys = ON")
        sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
        team.ensure()
        for ((id, name) in listOf("nova" to "Nova", "atlas" to "Atlas", "echo" to "Echo"))
            team.insert(Bot(id = id, kind = BotKind.CUSTOM, name = name, label = "", avatar = Avatar("orb", "#123456"), soulPath = "",
                toolGroups = setOf("web"), createdAt = now, u = now))
    }

    @After fun close() = sql.close()

    private fun add(title: String, bot: String?, deps: List<String> = emptyList(), priority: String = "normal", confirmed: Boolean = true, due: String? = null) =
        (store.add(title, "", bot, due, priority, deps, confirmed = confirmed) as JobStore.Added.New).job.id
    private fun runOf(id: String) = store.get(id)!!.run
    private fun tool(name: String, args: String, chat: String = "main", bot: String? = null) = tools.run(chat, bot, name, args)

    @Test fun aNewJobIsQueuedAndStartsWithTheATeamBrief() {
        val out = tool("add_job", """{"title":"Find flights LUN-CPT","brief":"Window seat, under $400","agent":"Atlas","due":"Mon","priority":"high"}""")
        assertEquals("Added #j1 \u00abFind flights LUN-CPT\u00bb for Atlas, due Mon.", out)
        val j = store.get("j1")!!
        assertEquals(JobRun.QUEUED, j.run); assertEquals("high", j.priority); assertEquals("2026-11-16", j.due); assertEquals("manager", j.source)
        assertEquals(listOf("j1"), queue.tick().map { it.id })
        assertEquals(JobRun.RUNNING, runOf("j1")); assertEquals("doing", store.get("j1")!!.status)
        val brief = run.started.single().second
        assertTrue(brief, brief.startsWith("You are assigned job #j1: Find flights LUN-CPT. Due: Mon.\nDo it now."))
        assertTrue(brief.contains("Window seat, under $400")); assertTrue(brief.contains("call done_job with id j1"))
        assertEquals("agent-j1-1", store.runOf("j1")); assertEquals("j1", store.jobOfRun("agent-j1-1"))
        assertEquals(JobQueue.Kind.STARTED, run.reports.single().second.kind)
    }

    @Test fun aRunsBWaitsOnItCWaitsForItsBotThenAllRunInOrder() {
        val a = add("A", "nova"); val b = add("B", "atlas", deps = listOf(a)); val c = add("C", "nova")
        assertEquals(listOf(a), queue.tick().map { it.id })
        assertEquals("blocked", store.get(b)!!.status); assertEquals("todo", store.get(c)!!.status)
        assertTrue(queue.tick().isEmpty())
        queue.done(a, "A done")
        assertEquals(listOf(b, c), queue.tick().map { it.id })        // B after A; C after A (one job at a time per Nova)
        assertEquals("todo".let { "doing" }, store.get(b)!!.status)
        queue.done(b, "ok"); queue.done(c, "ok")
        assertEquals(listOf(a, b, c), run.ids()); assertTrue(listOf(a, b, c).all { runOf(it) == JobRun.DONE })
    }

    @Test fun aJobTheBotDidNotCloseWaitsForYouAndItsDependantStaysQueued() {
        val a = add("Book hotel", "atlas"); val b = add("Send itinerary", "nova", deps = listOf(a))
        queue.tick()
        queue.finished(a, ok = true, text = "Which area do you prefer, V&A or Sea Point?")
        assertEquals(JobRun.WAITING, runOf(a)); assertEquals("waiting", store.get(a)!!.status)
        assertEquals("Which area do you prefer, V&A or Sea Point?", store.steps(a).last { it.state == "waiting" }.label)
        assertEquals(JobQueue.Report(JobQueue.Kind.WAITING, "Which area do you prefer, V&A or Sea Point?"), run.reports.last().second)
        assertTrue(queue.tick().isEmpty()); assertEquals(JobRun.QUEUED, runOf(b))
        // marking the blocker done by hand starts the dependant
        queue.done(a, "V&A, booked")
        assertEquals(listOf(b), queue.tick().map { it.id })
    }

    @Test fun aGatewayFailureMarksTheJobFailedWithBackoffThenGivesUp() {
        val a = add("Scan prices", "nova")
        queue.tick()
        queue.finished(a, ok = false, text = "java.net.UnknownHostException: Unable to resolve host \"gateway.example\"")
        var j = store.get(a)!!
        assertEquals(JobRun.FAILED, j.run); assertEquals("failed", j.status); assertEquals(now + 60_000, j.retryAt)
        assertEquals("Couldn't reach the internet", store.failures(a).single().label)
        assertTrue(queue.tick().isEmpty())
        now += 60_000
        assertEquals(listOf(a), queue.tick().map { it.id })
        assertTrue(run.started.last().second.contains("The last try failed: Couldn't reach the internet."))
        queue.finished(a, ok = false, text = "HTTP 503 Service Unavailable"); assertEquals(now + 5 * 60_000, store.get(a)!!.retryAt)
        now += 5 * 60_000; queue.tick(); queue.finished(a, ok = false, text = "read timed out"); assertEquals(now + 15 * 60_000, store.get(a)!!.retryAt)
        now += 15 * 60_000; queue.tick(); queue.finished(a, ok = false, text = "429 Too Many Requests")
        j = store.get(a)!!
        assertNull(j.retryAt); assertEquals(JobRun.FAILED, j.run); assertEquals(JobQueue.Kind.GAVE_UP, run.reports.last().second.kind)
        assertEquals(listOf("Couldn't reach the internet", "The service had a problem", "The connection timed out", "The service is busy right now"),
            store.failures(a).map { it.label })
        now += 3_600_000; assertTrue(queue.tick().isEmpty())
        // retry_job puts it back
        assertEquals("#$a \u00abScan prices\u00bb is back in the queue.", tool("retry_job", """{"id":"$a"}"""))
        assertEquals(listOf(a), queue.tick().map { it.id })
    }

    @Test fun addJobOfAFailedJobReopensItAndNeverAddsADuplicate() {
        assertTrue(tool("add_job", """{"title":"Greet user","agent":"Echo"}""").startsWith("Added #j1"))
        assertEquals("Already in the queue: #j1 \u00abGreet user\u00bb for Echo (todo). No new job added.", tool("add_job", """{"title":"greet  user!","agent":"echo"}"""))
        assertEquals(1, store.all().size)
        queue.tick(); queue.finished("j1", ok = false, text = "boom")
        assertEquals("Reopened #j1 \u00abGreet user\u00bb for Echo; it's back in the queue.", tool("add_job", """{"title":"Greet user","agent":"Echo"}"""))
        assertEquals(JobRun.QUEUED, runOf("j1")); assertNull(store.get("j1")!!.retryAt); assertEquals(1, store.all().size)
        // the same title for another bot is another job
        assertTrue(tool("add_job", """{"title":"Greet user","agent":"Nova"}""").startsWith("Added #j2"))
        assertTrue(tool("add_job", """{"title":"x","agent":"Zed"}""").startsWith("Error: there's no bot called \"Zed\""))
    }

    @Test fun runawayGuardTwelveStartsInTenMinutes() {
        cap = 100
        repeat(15) { i ->
            team.insert(Bot(id = "b$i", kind = BotKind.CUSTOM, name = "B$i", label = "", avatar = Avatar("orb", "#123456"), soulPath = "", toolGroups = emptySet(), createdAt = now, u = now))
            add("job $i", "b$i")
        }
        assertEquals(12, queue.tick().size); assertTrue(queue.guarded())
        now += 5 * 60_000; assertTrue(queue.tick().isEmpty())
        now += 5 * 60_000 + 1; assertEquals(3, queue.tick().size)
    }

    @Test fun staleRunsFailOnStartButAResumedRunKeepsGoing() {
        val a = add("A", "nova"); val b = add("B", "atlas")
        queue.tick()
        run.dead += store.runOf(a)!!
        val failed = queue.recover()
        assertEquals(listOf(a), failed.map { it.id })
        assertEquals("The app closed while this ran", store.failures(a).single().label)
        assertEquals(JobRun.RUNNING, runOf(b))
    }

    @Test fun userMessagesFirstCapAndPriorityAndUnconfirmed() {
        cap = 2
        userTurn += "nova"
        val low = add("low", "atlas", priority = "low"); val high = add("high", "echo", priority = "high")
        val n = add("nova job", "nova"); val norm = add("normal", "manager")
        val proactive = add("suggested", "atlas", confirmed = false)
        assertEquals(listOf(high, norm), queue.tick().map { it.id })     // cap 2: high first, Nova busy with you
        assertTrue(queue.tick().isEmpty())
        queue.done(high, "ok"); userTurn.clear()
        assertEquals(listOf(n), queue.tick().map { it.id })             // normal before low
        queue.done(norm, "ok")
        assertEquals(listOf(low), queue.tick().map { it.id })
        queue.done(low, "ok"); queue.done(n, "ok")
        assertTrue(queue.tick().isEmpty()); assertEquals(JobRun.QUEUED, runOf(proactive))
        queue.retry(proactive)                                            // confirming it lets it run
        assertEquals(listOf(proactive), queue.tick().map { it.id })
        // an unassigned job never starts, and a refused start is a failure
        add("nobody", null); assertTrue(queue.tick().isEmpty())
        run.refuse = true; val r = add("refused", "echo"); queue.tick()
        assertEquals("It couldn't be started", store.failures(r).single().label)
    }

    @Test fun earlierDueFirstAtTheSamePriority() {
        val later = add("later", "nova", due = "2026-11-20"); val sooner = add("sooner", "nova", due = "2026-11-13"); add("none", "nova")
        assertEquals(listOf(sooner), queue.tick().map { it.id }); queue.done(sooner, "ok")
        assertEquals(listOf(later), queue.tick().map { it.id })
    }

    @Test fun jobToolsInsideARunAndAgentStatus() {
        tool("agent_delegate", """{"agent":"Nova","task":"Compare three CRM tools\nprices and seats"}""", bot = "atlas")
        val j = store.get("j1")!!
        assertEquals("Compare three CRM tools", j.title); assertEquals("bot:atlas", j.source); assertEquals("atlas", JobStore.requester(j.source))
        queue.tick()
        val chat = "job-j1-1700000000000"
        // step 1 is the run itself; the bot's own steps follow
        assertEquals("Step 2 added.", tool("create_task", """{"title":"Read pricing pages"}""", chat, "nova"))
        assertEquals("Step 2: done.", tool("update_task", """{"step":"2","status":"done"}""", chat, "nova"))
        assertEquals("Error: no step 1.", tool("update_task", """{"step":"1","status":"done"}""", chat, "nova"))
        assertEquals("task_done", store.steps("j1").first { it.seq == 2 }.state)
        assertTrue(store.failures("j1").isEmpty())
        assertEquals("Error: say what you did in result.", tool("done_job", "{}", chat, "nova"))
        assertEquals("Done: #j1 \u00abCompare three CRM tools\u00bb.", tool("done_job", """{"result":"HubSpot is cheapest at 3 seats"}""", chat, "nova"))
        assertEquals(JobRun.DONE, runOf("j1")); assertEquals("HubSpot is cheapest at 3 seats", store.get("j1")!!.result)
        // the run then ends; done_job already closed it
        assertEquals(JobRun.DONE, queue.finished("j1", ok = true, text = "Done.")!!.run)
        assertEquals(JobQueue.Kind.DONE, run.reports.last { it.first == "j1" }.second.kind)
        assertEquals("Error: create_task works inside a job; you aren't running one.", tool("create_task", """{"title":"x"}"""))
        tool("add_job", """{"title":"Draft memo","agent":"Nova","due":"someday soon"}""")
        val status = tool("agent_status", """{"agent":"nova"}""")
        assertTrue(status, status.startsWith("Nova's recent jobs:\n#j2 \u00abDraft memo\u00bb: todo"))
        assertTrue(status.contains("#j1 \u00abCompare three CRM tools\u00bb: done, result: HubSpot is cheapest at 3 seats"))
        assertTrue(tool("update_job", """{"id":"j2","due":"Jan 5","priority":"urgent","agent":"Echo"}""").startsWith("Updated #j2 \u00abDraft memo\u00bb: Echo, high, due 5 Jan"))
        assertEquals("Cancelled #j2 \u00abDraft memo\u00bb.", tool("update_job", """{"id":"j2","status":"cancelled"}"""))
        assertEquals(JobRun.CANCELLED, runOf("j2"))
    }

    @Test fun plainWordsAndBackoff() {
        assertEquals(listOf(60_000L, 300_000L, 900_000L, null), (1..4).map { JobFailure.retryAt(it, 0) })
        assertEquals("The connection dropped", JobFailure.plain("java.net.SocketException: Connection reset"))
        assertEquals("A sign-in or key stopped working", JobFailure.plain("HTTP 401 Unauthorized"))
        assertEquals("It stopped without saying why", JobFailure.plain(" "))
        assertEquals("The tool said no", JobFailure.plain("The tool said no\nstack…"))
        assertFalse(JobFailure.plain("x".repeat(400)).length > 140)
    }
}
