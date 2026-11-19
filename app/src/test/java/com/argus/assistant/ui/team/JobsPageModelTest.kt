package com.blaze.assistant.ui.team

import com.blaze.assistant.store.JdbcSql
import com.blaze.assistant.team.Avatar
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.Job
import com.blaze.assistant.team.JobQueue
import com.blaze.assistant.team.JobRun
import com.blaze.assistant.team.JobStep
import com.blaze.assistant.team.JobStore
import com.blaze.assistant.team.TeamSchema
import com.blaze.assistant.team.TeamStore
import com.blaze.assistant.ui.argus.ArgusTypeScale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * P1-TM-07: Desk → Jobs (filters Needs you / Today / Tomorrow / Upcoming / Overdue / Done / All / By bot / Unassigned,
 * search, row model, tabular numbers, the job sheet with its log and Retry, the New job sheet) and [JobsActions] on a
 * real SQLite (team.db's schema), with and without the jobs runtime's queue.
 */
class JobsPageModelTest {
    private val today = LocalDate.of(2026, 11, 12)   // a Thursday
    private val zone: ZoneId = ZoneOffset.ofHours(2)
    private val t0 = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    private fun bot(id: String, name: String) = Bot(id = id, kind = BotKind.CUSTOM, name = name, label = "", avatar = Avatar("orb", "#123456"),
        soulPath = "", toolGroups = setOf("web"), createdAt = 0, u = 0)
    private val bots = mapOf("nova" to bot("nova", "Nova"), "atlas" to bot("atlas", "Atlas"), BotRules.MANAGER_ID to bot(BotRules.MANAGER_ID, "ARGUS"))

    private var n = 0
    private fun job(title: String, bot: String? = "nova", due: String? = null, run: JobRun = JobRun.QUEUED, priority: String = "normal",
                    confirmed: Boolean = true, retryAt: Long? = null, status: String = "todo", brief: String = "", doneAt: Long? = null) =
        Job("j${++n}", title, bot, due, priority, status, run, source = "user", brief = brief, confirmed = confirmed, retryAt = retryAt,
            createdAt = t0 + n, doneAt = doneAt, u = t0 + n)

    private val jobs = listOf(
        job("Pay ZESCO bill", due = "2026-11-11"),                         // j1 overdue
        job("Send deck to Alex", "atlas", due = "2026-11-12"),             // j2 today
        job("Book hotel", "atlas", due = "2026-11-13", run = JobRun.WAITING, status = "waiting"),   // j3 tomorrow, waiting
        job("Plan Cape Town", null, due = "2026-11-20", priority = "high"), // j4 upcoming, unassigned
        job("Water plants", run = JobRun.DONE, status = "done", doneAt = t0 + 500),                // j5 done
        job("Old thing", run = JobRun.CANCELLED, status = "cancelled"),    // j6 cancelled
        job("Check prices", confirmed = false),                            // j7 proactive, unconfirmed
        job("Scrape site", run = JobRun.FAILED, status = "failed"),        // j8 failed, out of tries
        job("Draft email", BotRules.MANAGER_ID, run = JobRun.FAILED, status = "failed", retryAt = t0 + 60_000), // j9 retrying
        job("Read report", null),                                          // j10 unassigned, no due
    )

    private fun ids(filter: String, bot: String? = null, q: String = "") = JobsPageModel.state(jobs, bots, filter, q, bot, today).rows.map { it.id }

    @Test fun eachFilter() {
        assertEquals(listOf("j1", "j3", "j7", "j8"), ids(JobsFilter.NEEDS_YOU).sorted())
        assertEquals(listOf("j2"), ids(JobsFilter.TODAY))
        assertEquals(listOf("j3"), ids(JobsFilter.TOMORROW))
        assertEquals(listOf("j4"), ids(JobsFilter.UPCOMING))
        assertEquals(listOf("j1"), ids(JobsFilter.OVERDUE))
        assertEquals(listOf("j5"), ids(JobsFilter.DONE))
        assertEquals(9, ids(JobsFilter.ALL).size)
        assertFalse("j6" in ids(JobsFilter.ALL))
        assertEquals(listOf("j4", "j10"), ids(JobsFilter.UNASSIGNED))
    }

    @Test fun countsIgnoreSearchAndShowOnlyWhereTheyHelp() {
        val s = JobsPageModel.state(jobs, bots, JobsFilter.ALL, "zzz", null, today)
        val c = s.chips.associate { it.key to it.count }
        assertEquals(4, c[JobsFilter.NEEDS_YOU]); assertEquals(1, c[JobsFilter.TODAY]); assertEquals(2, c[JobsFilter.UNASSIGNED])
        assertNull(c[JobsFilter.DONE]); assertNull(c[JobsFilter.ALL]); assertNull(c[JobsFilter.BY_BOT])
        assertEquals(JobsFilter.ORDER, s.chips.map { it.key })
        assertEquals(listOf("Needs you", "Today", "Tomorrow", "Upcoming", "Overdue", "Done", "All", "By bot", "Unassigned"), s.chips.map { it.label })
        assertTrue(s.rows.isEmpty())
        assertEquals("No jobs match \u201czzz\u201d.", s.empty)
        assertEquals(4, s.needsYou)
    }

    @Test fun defaultFilter() {
        assertEquals(JobsFilter.NEEDS_YOU, JobsPageModel.state(jobs, bots, null, "", null, today).filter)
        val calm = listOf(job("A", due = "2026-11-12"), job("B"))
        assertEquals(JobsFilter.TODAY, JobsPageModel.state(calm, bots, null, "", null, today).filter)
        assertEquals(JobsFilter.ALL, JobsPageModel.state(listOf(job("C")), bots, "nonsense", "", null, today).filter)
        assertEquals("No jobs yet. Add one with +, or ask a bot to do something.", JobsPageModel.state(emptyList(), bots, null, "", null, today).empty)
    }

    @Test fun searchEveryWordAnyOrderAndBotName() {
        assertEquals(listOf("j2"), ids(JobsFilter.ALL, q = "alex DECK"))
        assertEquals(listOf("j2", "j3").sorted(), ids(JobsFilter.ALL, q = "atlas").sorted())
        assertEquals(listOf("j1"), ids(JobsFilter.ALL, q = "zesco"))
        assertTrue(ids(JobsFilter.ALL, q = "deck hotel").isEmpty())
        assertTrue(JobsFilter.search(jobs[0], "  ", null))
    }

    @Test fun byBot() {
        val s = JobsPageModel.state(jobs, bots, JobsFilter.BY_BOT, "", null, today)
        assertEquals(listOf(BotRules.MANAGER_ID, "atlas", "nova"), s.botChips.map { it.key })   // Manager first, then by name
        assertEquals(listOf(1, 2, 3), s.botChips.map { it.count })
        assertEquals(BotRules.MANAGER_ID, s.bot)
        assertEquals(listOf("j3", "j2").sorted(), ids(JobsFilter.BY_BOT, "atlas").sorted())
        assertEquals("nova", JobsPageModel.state(jobs, bots, JobsFilter.BY_BOT, "", "nova", today).bot)
        assertTrue(JobsPageModel.state(jobs, bots, JobsFilter.TODAY, "", "nova", today).botChips.isEmpty())
    }

    @Test fun orderDueThenPriorityThenAge() {
        val list = listOf(job("late low", due = "2026-11-20", priority = "low"), job("none"), job("late high", due = "2026-11-20", priority = "high"),
            job("soon", due = "2026-11-14"))
        assertEquals(listOf("soon", "late high", "late low", "none"), JobsFilter.sort(JobsFilter.ALL, list).map { it.title })
        val done = listOf(job("a", run = JobRun.DONE, doneAt = 1), job("b", run = JobRun.DONE, doneAt = 5))
        assertEquals(listOf("b", "a"), JobsFilter.sort(JobsFilter.DONE, done).map { it.title })
    }

    @Test fun rows() {
        val byId = JobsPageModel.state(jobs, bots, JobsFilter.ALL, "", null, today).rows.associateBy { it.id }
        val r1 = byId.getValue("j1")
        assertEquals("1 day late", r1.due); assertTrue(r1.overdue); assertEquals("Nova", r1.line)
        val r3 = byId.getValue("j3")
        assertEquals("Atlas \u00b7 waiting", r3.line); assertEquals("Tomorrow", r3.due); assertFalse(r3.overdue)
        val r4 = byId.getValue("j4")
        assertTrue(r4.high); assertEquals("Unassigned \u00b7 high", r4.line); assertEquals("20 Nov", r4.due)
        assertEquals("needs your OK", byId.getValue("j7").tags.single())
        assertEquals(listOf("failed"), byId.getValue("j8").tags)
        assertEquals(listOf("retrying"), byId.getValue("j9").tags)
        val r5 = byId.getValue("j5")
        assertTrue(r5.done); assertFalse(r5.high); assertTrue(r5.a11y.endsWith("done"))
        assertTrue(r1.a11y.contains("1 day late"))
    }

    @Test fun tabularNumbers() {
        // due labels, sheet times and counts use the meta type: tabular figures (tnum) so columns of times line up
        assertTrue(JobsPageModel.NUMBERS.tabular)
        assertTrue(JobsPageModel.NUMBERS === ArgusTypeScale.meta)
    }

    @Test fun sheetWithLogAndRetry() {
        val j = jobs[7]   // failed, out of tries
        val steps = listOf(JobStep(j.id, 2, "Couldn't reach the internet", "failed", t0 + 120_000),
            JobStep(j.id, 1, "agent-123", "run", t0 + 60_000),
            JobStep(j.id, 3, "Checked the page", "step", t0 - 86_400_000))
        val s = JobSheetModel.of(j, steps, bots, today, zone)
        assertEquals("Retry", s.retry); assertTrue(s.canDone); assertTrue(s.canCancel)
        assertEquals("Failed \u00b7 out of tries", s.status)
        assertEquals(listOf("Started", "Failed: Couldn't reach the internet", "Checked the page"), s.log.map { it.label })
        assertEquals(listOf("09:01", "09:02", "11 Nov 09:00"), s.log.map { it.time })
        assertEquals(listOf(false, true, false), s.log.map { it.bad })
        assertEquals(listOf("play", "circle-alert", "clock"), s.log.map { it.icon })
        assertEquals("Nova", s.who)
    }

    @Test fun sheetStates() {
        fun st(j: Job) = JobSheetModel.of(j, emptyList(), bots, today, zone)
        assertEquals("Failed \u00b7 tries again at 09:01", st(jobs[8]).status)
        assertEquals("Start", st(jobs[6]).retry); assertEquals("Waits for your OK before it starts", st(jobs[6]).status)
        assertEquals("Waits for your answer", st(jobs[2]).status); assertEquals("Retry", st(jobs[2]).retry); assertFalse(st(jobs[2]).canCancel)
        val done = st(jobs[4]); assertNull(done.retry); assertFalse(done.canDone); assertFalse(done.canCancel); assertTrue(done.status.startsWith("Done"))
        assertEquals("Not assigned to a bot", st(jobs[9]).status)
        assertEquals("Waits on another job", st(job("x", status = "blocked")).status)
        assertEquals("Running now", st(job("y", run = JobRun.RUNNING)).status)
        assertNull(st(job("Same", brief = "same")).brief)
        assertEquals("Longer brief", st(job("T", brief = "Longer brief")).brief)
        assertNull(st(jobs[0]).retry)
    }

    @Test fun newJobDraft() {
        assertEquals(listOf("Give the job a title"), NewJob.problems(NewJob.Draft(), today))
        assertTrue(NewJob.problems(NewJob.Draft("Pay rent", due = "fri 5pm"), today).isEmpty())
        assertEquals("2026-11-13T17:00", NewJob.dueOf(NewJob.Draft("x", due = "fri 5pm"), today))
        assertEquals("Tomorrow 17:00", NewJob.dueHint(NewJob.Draft("x", due = "fri 5pm"), today))
        assertTrue(NewJob.problems(NewJob.Draft("x", due = "someday maybe"), today).single().startsWith("Couldn't read"))
        assertTrue(NewJob.problems(NewJob.Draft("x".repeat(121)), today).isNotEmpty())
        assertEquals(listOf("", "nova"), NewJob.botChips(listOf(bots.getValue("nova"))).map { it.key })
    }

    // ---- JobsActions on a real SQLite ----

    private val sql = JdbcSql()
    private var now = t0
    private var ids = 0
    private val team = TeamStore(sql) { now }
    private val store = JobStore(sql, { now }) { "k${++ids}" }

    init {
        sql.exec("PRAGMA foreign_keys = ON")
        sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
        team.ensure()
        team.insert(bot("nova", "Nova").copy(createdAt = now, u = now))
    }

    @After fun close() = sql.close()

    private class Fake : JobQueue.Runner {
        val reports = ArrayList<JobQueue.Kind>()
        override fun start(job: Job, bot: Bot, brief: String): String? = "run-${job.id}"
        override fun alive(runId: String) = true
        override fun report(job: Job, bot: Bot?, event: JobQueue.Report) { reports += event.kind }
    }

    @Test fun actionsWithoutTheRuntime() {
        var kicks = 0
        val a = JobsActions(store, { null }) { kicks++ }
        val added = a.add(NewJob.Draft("Pay rent", "nova", "tomorrow", "high"), today)
        assertTrue(added is JobStore.Added.New)
        val id = added!!.job.id
        assertEquals("2026-11-13", store.get(id)!!.due); assertEquals("high", store.get(id)!!.priority); assertEquals("user", store.get(id)!!.source)
        assertNull(a.add(NewJob.Draft(""), today))
        assertTrue(a.add(NewJob.Draft("pay  RENT!", "nova"), today) is JobStore.Added.Duplicate)
        store.setRun(id, JobRun.FAILED)
        assertEquals(JobRun.QUEUED, a.retry(id)!!.run)
        assertEquals("Retry", store.steps(id).last().label)
        assertEquals(JobRun.DONE, a.done(id)!!.run)
        assertEquals("done", store.steps(id).last().state)
        assertEquals(JobRun.DONE, a.retry(id)!!.run)        // a done job is not retried
        val other = (store.add("Other", "", "nova") as JobStore.Added.New).job.id
        assertEquals(JobRun.CANCELLED, a.cancel(other)!!.run)
        assertEquals("Cancelled", store.steps(other).last().label)
        assertNull(a.cancel(id))                              // done stays done
        assertTrue(kicks >= 6)
    }

    @Test fun actionsThroughTheQueueReport() {
        val fake = Fake()
        val queue = JobQueue(store, team, fake, { now }, { 2 }, { false }, { today })
        val a = JobsActions(store, { queue })
        val id = (store.add("Proactive", "", "nova", confirmed = false) as JobStore.Added.New).job.id
        assertTrue(queue.ready(now).isEmpty())
        assertEquals(JobRun.QUEUED, a.retry(id)!!.run)        // Start confirms it
        assertTrue(store.get(id)!!.confirmed)
        assertEquals(listOf(id), queue.ready(now).map { it.id })
        assertEquals(JobRun.DONE, a.done(id)!!.run)
        assertEquals(listOf(JobQueue.Kind.DONE), fake.reports)
        assertNotNull(JobSheetModel.of(store.get(id)!!, store.steps(id), mapOf("nova" to bot("nova", "Nova")), today, zone).log.lastOrNull { it.icon == "circle-check" })
    }
}
