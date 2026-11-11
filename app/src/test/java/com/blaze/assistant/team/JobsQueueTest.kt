package com.blaze.assistant.team

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * P1-TM-05: ports A-team's jobs cases (app/tests/jobs.test.js, v216d failure log / retry, manager.js jobAllowed,
 * NOTIF.summary) and the due-word parser (app.js dueFromWord / dueFromText / bucket). Pure JVM, mocked clock.
 */
class JobsQueueTest {
    private var now = 1_762_855_200_000L   // 2025-11-11 10:00 UTC, a Tuesday
    private val port = MemoryTeamPort()
    private val team = TeamStore(port) { now }.also { it.ensure() }
    private val threads = BotThreads(port, Rows(port) { now }, team, null) { now }
    private val notes = ArrayList<Pair<String, String>>()
    private val q = JobQueue(port, team, threads, { now }, { ZoneId.of("UTC") }, cap = { 4 }, me = { "Finn" }).also { it.notify = { b, t -> notes += b.name to t } }

    private fun bot(name: String) = (team.create(Bot(id = "", kind = BotKind.CUSTOM, name = name, label = "", avatar = Bots.avatar(0, Bots.COLORS[0]),
        soulPath = "", toolGroups = setOf("web"), createdAt = 0, u = 0)) as TeamStore.Result.Ok).bot

    @Test fun aNewJobIsQueuedStartsWithItsBriefAndFinishesDone() {
        val nova = bot("Nova")
        val j = q.add("Build the launch slides", nova.id, due = "tomorrow").job
        assertEquals(JobRun.QUEUED, j.run); assertEquals("2025-11-12", j.due); assertTrue(j.confirmed)
        val c = q.claim()!!
        assertEquals(j.id, c.id); assertEquals(JobRun.RUNNING, c.run)
        val brief = q.brief(c)
        assertTrue(brief, Regex("You are assigned job #\\w+: Build the launch slides").containsMatchIn(brief))
        assertTrue(brief.contains("Due: Tomorrow")); assertTrue(brief.contains("deliver the result to Finn")); assertTrue(brief.contains("no add_job for this"))
        q.done(j.id)   // the bot called done_job during its run
        val reply = "## Result\nThe slides are finished. More detail. More detail. More detail. More detail. More detail. More detail. More detail. More detail. More detail. More detail. More detail."
        val f = q.finish(j.id, JobQueue.RunResult(reply))!!
        assertEquals(JobRun.DONE, f.run); assertEquals(Jobs.DONE, f.status); assertNotNull(f.doneAt)
        val n = notes.single()
        assertEquals("Nova", n.first); assertTrue(n.second.length <= 160); assertTrue(n.second.startsWith("Result The slides are finished."))
    }

    @Test fun depsWaitAndOneJobAtATimePerBot() {
        val atlas = bot("Atlas"); val nova = bot("Nova")
        val a = q.add("Collect the numbers", atlas.id).job
        val b = q.add("Write the report", nova.id, deps = listOf(a.id)).job
        val c = q.add("Book the room", atlas.id).job
        assertEquals(a.id, q.claim()!!.id)
        assertNull("B waits on A, C waits for its bot", q.claim())
        assertEquals(listOf("Collect the numbers"), Jobs.blockers(q.get(b.id)!!, q.all()).map { it.title })
        assertEquals("Waits on Collect the numbers", q.line(q.get(b.id)!!))
        q.done(a.id); q.finish(a.id, JobQueue.RunResult("Numbers collected."))
        val next = listOf(q.claim()!!, q.claim()!!).map { it.id }.toSet()
        assertEquals(setOf(b.id, c.id), next)
    }

    @Test fun aJobTheBotDidNotCloseWaitsForYouAndTheTickFreesItsDependant() {
        val atlas = bot("Atlas")
        val a = q.add("Draft budget", atlas.id).job
        val b = q.add("Send budget", atlas.id, deps = listOf(a.id)).job
        q.claim()
        val w = q.finish(a.id, JobQueue.RunResult("I need the budget figure first. What is it?"))!!
        assertEquals(JobRun.WAITING, w.run); assertEquals(JobRun.QUEUED, q.get(b.id)!!.run)
        assertTrue(notes.any { it.first == "Atlas" && it.second.contains("budget figure first") })
        assertNull(q.claim())
        q.done(a.id)
        assertEquals(b.id, q.claim()!!.id)
    }

    @Test fun gatewayFailuresAreLoggedInPlainWordsAndRetryAfter1_5_15Minutes() {
        val nova = bot("Nova")
        val j = q.add("Ship the build", nova.id).job
        q.claim(); q.finish(j.id, JobQueue.RunResult(error = "java.net.UnknownHostException: Unable to resolve host \"ai.blaze-app.workers.dev\""))
        var f = q.get(j.id)!!
        assertEquals(JobRun.FAILED, f.run); assertEquals(now + 60_000, f.retryAt)
        assertEquals("No internet connection · retrying in 1 min (try 2)", q.line(f))
        assertTrue(notes.last().second.startsWith("Couldn't finish Ship the build: No internet connection (I'll retry)"))
        assertNull("not before its time", q.claim())
        now += 60_000
        assertEquals(j.id, q.claim()!!.id); q.finish(j.id, JobQueue.RunResult(error = "HTTP 503 gateway"))
        assertEquals(now + 5 * 60_000, q.get(j.id)!!.retryAt)
        now += 5 * 60_000; q.claim(); q.finish(j.id, JobQueue.RunResult(error = "timeout"))
        assertEquals(now + 15 * 60_000, q.get(j.id)!!.retryAt)
        now += 15 * 60_000; q.claim(); q.finish(j.id, JobQueue.RunResult(error = "timeout"))
        f = q.get(j.id)!!
        assertNull("after three automatic retries it waits for a bot or the user", f.retryAt)
        assertEquals("It took too long to answer · failed 4\u00d7", q.line(f))
        assertTrue(q.failedBlock(nova.id, false).contains("\"Ship the build\": It took too long to answer (tries: 4)"))
        assertTrue(q.retry(j.id, "user")); assertEquals(JobRun.QUEUED, q.get(j.id)!!.run)
    }

    @Test fun aNonTransientErrorIsNotRetriedByItselfAndStoppedIsPlain() {
        val nova = bot("Nova")
        val j = q.add("Parse the file", nova.id).job
        q.claim(); q.finish(j.id, JobQueue.RunResult(error = "File not found: notes.md"))
        assertNull(q.get(j.id)!!.retryAt)
        q.retry(j.id); q.claim(); q.finish(j.id, JobQueue.RunResult(stopped = true))
        assertNull(q.get(j.id)!!.retryAt); assertEquals("Stopped · failed 2\u00d7", q.line(q.get(j.id)!!))
    }

    @Test fun aRefusedDoneJobIsBlockedAndNeverRetriedByItself() {
        val nova = bot("Nova")
        val j = q.add("Deploy", nova.id).job
        q.claim(); q.finish(j.id, JobQueue.RunResult("Tests fail.", rejected = "2 tests still fail"))
        val b = q.get(j.id)!!
        assertEquals(JobRun.FAILED, b.run); assertNull(b.retryAt)
        assertEquals("blocked", Jobs.state(b, q.steps(j.id))); assertEquals("Blocked: 2 tests still fail", q.line(b))
    }

    @Test fun staleRunsAreFailedOnStartAndRetryByThemselves() {
        val nova = bot("Nova")
        val j = q.add("Long research", nova.id).job
        q.claim()
        // the app died; a new process starts
        val q2 = JobQueue(port, team, threads, { now }, { ZoneId.of("UTC") })
        q2.recoverOnStart()
        val f = q2.get(j.id)!!
        assertEquals(JobRun.FAILED, f.run); assertNotNull(f.retryAt)
        assertTrue(q2.line(f).startsWith("Interrupted: the app closed while it ran"))
    }

    @Test fun duplicateAddJobIsDroppedAndAFailedOneIsReopened() {
        val nova = bot("Nova")
        val a = q.add("Call Zamtel", nova.id).job
        val dup = q.add("call zamtel!", nova.id)
        assertTrue(dup.duplicate); assertEquals(a.id, dup.job.id); assertEquals(1, q.all().size)
        q.claim(); q.finish(a.id, JobQueue.RunResult(error = "File not found"))
        val again = q.add("Call Zamtel", nova.id)
        assertTrue(again.duplicate); assertEquals("reopened", again.note); assertEquals(JobRun.QUEUED, q.get(a.id)!!.run)
        // the same title for another bot is a different job
        assertFalse(q.add("Call Zamtel", bot("Atlas").id).duplicate)
    }

    @Test fun proactiveJobsNeverActUnconfirmed() {
        val nova = bot("Nova")
        val j = q.add("Order the NET103 textbook online", nova.id, source = "suggestion").job
        assertFalse(j.confirmed)
        assertNull(q.claim())
        assertEquals("Waiting for your OK: needs your OK (money)", q.line(j))
        q.update(j.id, confirmed = true)
        assertEquals(j.id, q.claim()!!.id)
        assertTrue(Jobs.allowed(j.copy(title = "Check prices", brief = "")).why.contains("not confirmed"))
    }

    @Test fun userMessagesGoFirstAndTheCapHolds() {
        val a = bot("A1"); val b = bot("B1"); val c = bot("C1")
        q.cap = { 2 }
        val ja = q.add("ja", a.id).job; q.add("jb", b.id); q.add("jc", c.id)
        assertNull("A1 is talking with the user", q.claim(busyBots = setOf(a.id, b.id, c.id)))
        assertEquals(ja.id, q.claim(busyBots = emptySet())!!.id)
        assertNotNull(q.claim())
        assertNull("cap of 2 running", q.claim())
    }

    @Test fun highPriorityFirstThenLongestQueued() {
        val a = bot("A1"); val b = bot("B1")
        q.add("old normal", a.id); now += 1000
        val hi = q.add("urgent thing", b.id, priority = "high").job
        assertEquals(hi.id, q.claim()!!.id)
    }

    @Test fun runawayGuardPausesAfter12StartsIn10Minutes() {
        val bots = (1..13).map { bot("Bot$it") }
        bots.forEach { q.add("job for ${it.name}", it.id) }
        q.cap = { 99 }
        repeat(12) { assertNotNull(q.claim()) }
        assertNull(q.claim()); assertTrue(q.paused)
        now += 10 * 60_000
        assertNotNull(q.claim()); assertFalse(q.paused)
    }

    @Test fun completionPostsASummaryCardToTheRequester() {
        val atlas = bot("Atlas")
        val tools = JobTools(q, team, threads)
        val out = tools.exec("agent_delegate", JSONObject().put("agent", "atlas").put("task", "Find three venues in Lusaka for 40 people").put("due", "friday"), JobTools.Caller(team.manager()))
        assertTrue(out.text, out.ok)
        val j = q.get(out.jobId!!)!!
        assertEquals("manager", j.source); assertEquals("2025-11-14", j.due); assertEquals(atlas.id, j.botId)
        val handoff = threads.timeline("manager").single()
        assertEquals("handoff", RowBody.type(handoff)); assertEquals("Atlas is on it", RowBody.field(handoff, "title"))
        q.claim(); q.done(j.id); q.finish(j.id, JobQueue.RunResult("Three venues found: A, B and C. Details below.", posted = false))
        val card = threads.timeline("manager").last()
        assertEquals("job", RowBody.type(card)); assertEquals("done", RowBody.field(card, "state")); assertEquals("Three venues found: A, B and C. Details below.", RowBody.field(card, "text"))
        assertEquals("Three venues found: A, B and C. Details below.", RowBody.text(threads.timeline(atlas.id).single()))   // not posted by the engine: the queue posts it
    }

    @Test fun deletingABotUnassignsItsJobs() {
        val nova = bot("Nova")
        val j = q.add("Water plants", nova.id).job
        team.onDeleted { q.unassign(it.id) }
        team.delete(nova.id)
        val u = q.get(j.id)!!
        assertNull(u.botId); assertEquals("Unassigned", q.line(u)); assertNull(q.claim())
    }

    @Test fun summaryStripsMarkdownCodeAndMetaTalk() {
        assertEquals("Result All good. The deploy ran.", Jobs.summary("## Result\n**All good.** The `deploy` ran.\n```sh\nls\n```\nExtra text that goes on for a while."))
        assertEquals("Here is the plan for next week. First we do the research, then the draft.", Jobs.summary("Let's add the job. Here is the plan for next week. First we do the research, then the draft."))
        assertEquals("", Jobs.summary("Done."))
    }

    @Test fun toolsAddDoneRetryAndSteps() {
        val nova = bot("Nova")
        val tools = JobTools(q, team, threads)
        val me = JobTools.Caller(nova)
        assertTrue(tools.exec("add_job", JSONObject().put("title", "Tidy notes").put("due", "in 3 days"), me).text.contains("due 2025-11-14"))
        assertTrue(tools.exec("add_job", JSONObject().put("title", "Tidy notes"), me).text.contains("no second job"))
        assertFalse(tools.exec("agent_delegate", JSONObject().put("agent", "manager").put("task", "x"), me).ok)
        val j = q.claim()!!
        val inJob = JobTools.Caller(nova, j.id)
        assertFalse(tools.exec("add_job", JSONObject().put("title", "Tidy notes"), inJob).ok)
        assertTrue(tools.exec("create_task", JSONObject().put("title", "Read the notes"), inJob).ok)
        assertTrue(tools.exec("update_task", JSONObject().put("id", "1").put("state", "in_progress"), inJob).ok)
        assertEquals("Working: Read the notes", q.line(q.get(j.id)!!))
        assertFalse(tools.exec("retry_job", JSONObject().put("job", "Tidy notes"), me).ok)   // running
        assertTrue(tools.exec("done_job", JSONObject().put("result", "Notes tidied"), inJob).ok)
        q.finish(j.id, JobQueue.RunResult("Notes tidied."))
        assertEquals("Notes tidied", q.get(j.id)!!.result)
        assertFalse(tools.exec("frobnicate", JSONObject(), me).ok)
        assertEquals(6, JobTools.schemas(false).size); assertEquals(7, JobTools.schemas(true).size)
    }

    // ---- due words (A-team app.js dueFromWord / dueFromText / dueLabel / bucket)

    private val tue = LocalDate.of(2025, 11, 11)

    @Test fun dueWords() {
        assertEquals("2025-11-11", DueWords.fromWord("today", tue)); assertEquals("2025-11-12", DueWords.fromWord("tomorrow", tue))
        assertEquals("2025-11-14", DueWords.fromWord("this_week", tue)); assertEquals("2025-11-17", DueWords.fromWord("next week", tue))
        assertEquals("2025-11-25", DueWords.fromWord("later", tue)); assertEquals("2025-11-14", DueWords.fromWord("fri", tue))
        assertEquals("2025-11-18", DueWords.fromWord("tuesday", tue)); assertNull(DueWords.fromWord("someday", tue))
        assertEquals("2025-11-15", DueWords.fromWord("this_week", LocalDate.of(2025, 11, 14)))
        assertEquals("2025-11-14", DueWords.fromText("send it by Friday please", tue)); assertEquals("2025-11-16", DueWords.fromText("in 5 days", tue))
        assertEquals("2025-12-03", DueWords.fromText("on 3/12", tue)); assertEquals("2026-01-02", DueWords.fromText("2026-01-02", tue))
        assertEquals(DueWords.NONE, DueWords.fromText("call mum", tue))
        assertEquals("2025-11-12", DueWords.parse("tomorrow", tue)); assertEquals(DueWords.NONE, DueWords.parse("whenever-ish", tue)); assertNull(DueWords.parse("none", tue))
        assertEquals("Today", DueWords.label("2025-11-11", tue)); assertEquals("Tomorrow", DueWords.label("2025-11-12", tue))
        assertEquals("2d overdue", DueWords.label("2025-11-09", tue)); assertEquals("Friday", DueWords.label("2025-11-14", tue)); assertEquals("20 Nov", DueWords.label("2025-11-20", tue))
        assertEquals("overdue", DueWords.bucket("todo", "2025-11-10", tue)); assertEquals("today", DueWords.bucket("todo", "2025-11-11", tue))
        assertEquals("tomorrow", DueWords.bucket("todo", "2025-11-12", tue)); assertEquals("upcoming", DueWords.bucket("todo", "2025-11-30", tue))
        assertEquals("todo", DueWords.bucket("todo", null, tue)); assertEquals("done", DueWords.bucket("done", "2025-11-10", tue))
    }
}
