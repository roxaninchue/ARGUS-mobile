package com.blaze.assistant.ui.shots

import com.blaze.assistant.agent.ChatMeta
import com.blaze.assistant.agent.ManagerChats
import com.blaze.assistant.agent.ScheduleDay
import com.blaze.assistant.store.JdbcSql
import com.blaze.assistant.team.ATeamImport
import com.blaze.assistant.team.ATeamImporter
import com.blaze.assistant.team.Avatar
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.BotThreads
import com.blaze.assistant.team.Job
import com.blaze.assistant.team.ImportPrefs
import com.blaze.assistant.team.JobRun
import com.blaze.assistant.team.JobStore
import com.blaze.assistant.team.JobStep
import com.blaze.assistant.team.Router
import com.blaze.assistant.team.Row
import com.blaze.assistant.team.RowBodies
import com.blaze.assistant.team.RowKind
import com.blaze.assistant.team.TeamJson
import com.blaze.assistant.team.TeamSchema
import com.blaze.assistant.team.TeamStore
import com.blaze.assistant.ui.argus.kit.ChipModel
import com.blaze.assistant.ui.space.SpaceAnswer
import com.blaze.assistant.ui.team.BotEditorModel
import com.blaze.assistant.ui.team.ChatDrawerModel
import com.blaze.assistant.ui.team.DeskModel
import com.blaze.assistant.ui.team.EditorState
import com.blaze.assistant.ui.team.JobCounts
import com.blaze.assistant.ui.team.JobSheetModel
import com.blaze.assistant.ui.team.JobsFilter
import com.blaze.assistant.ui.team.JobsPageModel
import com.blaze.assistant.ui.team.JobsState
import com.blaze.assistant.ui.team.LifeModel
import com.blaze.assistant.ui.team.TeamModel
import com.blaze.assistant.ui.team.TeamState
import com.blaze.assistant.ui.team.thread.ThreadModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P1-QA-01 fixture data for every P1 screen (Bot HQ's `?fixtures` idea, BH-22: one fixed, believable team that every
 * picture shows). Pure Kotlin on the real pure models, so it compiles and is checked on the JVM (P1FixturesTest), and
 * the shots (P1ShotsTest) and the preview composables (P1Previews) draw exactly this. Fixed clock: Thursday
 * 12 Nov 2026, 11:00 in Lusaka, so dates, "today" and overdue never move between runs.
 */
object P1Fixtures {
    val zone: ZoneId = ZoneId.of("Africa/Lusaka")
    val today: LocalDate = LocalDate.of(2026, 11, 12)          // a Thursday
    val nowTime: LocalTime = LocalTime.of(11, 0)
    val now: Long = ZonedDateTime.of(today, nowTime, zone).toInstant().toEpochMilli()
    const val TEAM_NAME = "Finn's team"
    const val USER_NAME = "Finn"
    const val USER_EMAIL = "finn@example.com"

    private const val MIN = 60_000L
    private const val HOUR = 3_600_000L
    private fun at(day: Int, h: Int, m: Int = 0): Long = ZonedDateTime.of(today.withDayOfMonth(day), LocalTime.of(h, m), zone).toInstant().toEpochMilli()

    // ---------------------------------------------------------------- team (P1-TM-01 / TM-03)
    fun bot(id: String, name: String, label: String, color: String, pinned: Boolean = false, kind: BotKind = BotKind.CUSTOM,
            face: String = "orb", reportsTo: String? = BotRules.MANAGER_ID, portfolio: String = "") =
        Bot(id = id, kind = kind, name = name, label = label, avatar = Avatar(face, color), soulPath = "", toolGroups = setOf("web"),
            reportsTo = reportsTo, pinned = pinned, createdAt = 0, u = 0).let { if (portfolio.isEmpty()) it else it.copy(portfolio = portfolio) }

    val manager: Bot = BotRules.manager(0)
    val nova = bot("nova", "Nova", "Research lead", "#22c55e", pinned = true, portfolio = "Market research, competitor pricing, weekly briefs")
    val atlas = bot("atlas", "Atlas", "Travel & sales ops", "#f59e0b", pinned = true, reportsTo = "nova")
    val echo = bot("echo", "Echo", "Support", "#ef4444", reportsTo = "nova")
    val ledger = bot("ledger", "Ledger", "Money", "#0ea5e9")
    val tempo = bot("tempo", "Tempo", "Time & calendar", "#7C5CFF", kind = BotKind.DOMAIN)

    val roster: List<Bot> = listOf(manager, nova, atlas, echo, ledger, tempo)
    val bots: Map<String, Bot> = roster.associateBy { it.id }

    val threads: List<BotThreads.Thread> = listOf(
        BotThreads.Thread(BotRules.MANAGER_ID, manager.name, 0, null, 1, "Morning. Three things need you today.", now - 5 * MIN, true, false, 0),
        BotThreads.Thread("nova", "Nova", 0, null, 2, "Competitor pricing scan is ready", now - MIN, true, true, 0),
        BotThreads.Thread("atlas", "Atlas", 0, null, 0, "Found 3 flights to Cape Town", now - 2 * HOUR, true, false, 1),
        BotThreads.Thread("ledger", "Ledger", 0, null, 0, "November budget: K 2,140 left", at(10, 18), false, false, 0),
        BotThreads.Thread("echo", "Echo", 0, null, 0, "All clear", now - 26 * HOUR, false, false, 0),
    )
    val jobCounts = JobCounts(today = 4, overdue = 1, unassigned = 2, open = 9, overdueByBot = mapOf("atlas" to (1 to "Send deck to Alex")))

    fun teamState(): TeamState = TeamModel.state(TEAM_NAME, roster, threads, jobCounts, now, zone)
    fun emptyTeamState(): TeamState = TeamModel.state(TEAM_NAME, listOf(manager), threads.take(1), JobCounts(), now, zone)

    // ---------------------------------------------------------------- thread (P1-SH-03)
    private fun row(seq: Long, kind: String, body: String, at: Long) = Row("atlas", 0, seq, seq, kind, body, at)
    private fun tool(seq: Long, id: String, name: String, state: String, at: Long, undoable: Boolean = false) =
        row(seq, RowKind.TOOL_CALL, TeamJson.write(linkedMapOf("callId" to id, "name" to name, "state" to state, "undoable" to (if (undoable) true else null))), at)

    /** Atlas's thread: a jobs card, a finished turn (3 steps, a reply), a handoff to Ledger, then a live turn. */
    fun threadRows(live: Boolean = true): List<Row> {
        val t = now - 20 * MIN
        val done = listOf(
            row(1, RowKind.CARD, RowBodies.card("jobs", mapOf("jobs" to listOf(mapOf("id" to "j2", "title" to "Find flights LUN\u2192CPT", "due" to "Fri"),
                mapOf("id" to "j3", "title" to "Hotel near the V&A", "due" to "Fri")))), t),
            row(2, RowKind.MESSAGE, RowBodies.message("user", "Find flights for Jan 5, window seat, under \$400"), t + 1_000),
            tool(3, "c1", "web_search", "done", t + 2_000), tool(4, "c2", "fetch_page", "done", t + 30_000),
            tool(5, "c3", "save_file", "done", t + 60_000, undoable = true),
            row(6, RowKind.MESSAGE, RowBodies.message("atlas", "Found 3 options. The best is Proflight 07:10, \$356, window 12A."), t + 131_000),
            row(7, RowKind.CARD, RowBodies.card("handoff", mapOf("from" to "atlas", "fromName" to "Atlas", "to" to "ledger", "toName" to "Ledger",
                "text" to "Is \$356 within the travel budget?", "reply" to "Yes, \$500 left")), t + 132_000),
        )
        return if (!live) done else done + listOf(
            row(8, RowKind.MESSAGE, RowBodies.message("user", "And a hotel near the waterfront?"), now - 2 * MIN),
            tool(9, "c4", "web_search", "done", now - 90_000), tool(10, "c5", "fetch_page", "running", now - 30_000),
        )
    }

    val threadIntro = ThreadModel.ThreadItem.Intro("intro", "Atlas", "Travel & sales ops", "On this phone",
        "Books trips and keeps the sales pipeline moving.", "#f59e0b", manager = false)

    fun threadItems(live: Boolean = true): List<ThreadModel.ThreadItem> = ThreadModel.items(threadRows(live), "Atlas", running = live, intro = threadIntro)

    // ---------------------------------------------------------------- chat mode drawer (P1-SH-04)
    /** Four chats of the Manager (8 Nov, 11 Nov late, today 10:00, today 10:40 = newest) + one project chat. */
    fun managerChats(): ManagerChats {
        val m = ManagerChats.fresh()
        for (s in listOf(at(8, 9), at(11, 23), at(12, 10), at(12, 10, 40))) m.newChat(s, liveBlank = false)
        m.pin(1, now - HOUR)
        return m
    }

    val chatMessages: List<ChatDrawerModel.Msg> = listOf(
        ChatDrawerModel.Msg(true, "Old plans for October", at(1, 9)),
        ChatDrawerModel.Msg(true, "Budget for the **week**", at(8, 9) + 1), ChatDrawerModel.Msg(false, "Here's a budget with rent and food.", at(8, 9) + 2),
        ChatDrawerModel.Msg(true, "Late question about the ZESCO bill", at(11, 23) + 1),
        ChatDrawerModel.Msg(true, "Book the dentist", at(12, 10) + 1), ChatDrawerModel.Msg(false, "Done for Friday at 9.", at(12, 10) + 2),
        ChatDrawerModel.Msg(true, "What's on my plate today?", at(12, 10, 40) + 1),
    )
    val projectChats: List<ChatDrawerModel.ProjectChat> = listOf(
        ChatDrawerModel.ProjectChat(ChatMeta("p-cape", "Cape Town itinerary", at(12, 9), false, "Day 2: Table Mountain"), "Cape Town trip"),
    )

    /** The drawer as it reads while the newest chat is replying. */
    fun drawerState(query: String = ""): ChatDrawerModel.State {
        val m = managerChats()
        return ChatDrawerModel.state(m, chatMessages, projectChats, now, zone, query, replying = m.live)
    }

    // ---------------------------------------------------------------- life (P1-SH-05)
    val dayItems: List<ScheduleDay.Item> = listOf(
        ScheduleDay.Item("Mum's birthday", "event", null),
        ScheduleDay.Item("Gym", "reminder", LocalTime.of(7, 0)),
        ScheduleDay.Item("Call Mum", "event", LocalTime.of(13, 30), LocalTime.of(14, 0)),
        ScheduleDay.Item("Pay ZESCO bill", "reminder", LocalTime.of(18, 0)),
    )

    fun lifeState(): LifeModel.LifeState = LifeModel.state(today, nowTime, dayItems,
        projects = listOf("p1" to "Cape Town trip", "p2" to "ARGUS overhaul"),
        weather = LifeModel.Weather("24\u00b0C", "Clear \u00b7 Lusaka"),
        answer = SpaceAnswer.Card("What's on today?", "Three things: the gym at 7, a call with Mum at 13:30 and the ZESCO bill at 18:00.", false))

    // ---------------------------------------------------------------- settings (P1-SH-06)
    const val PLAN_LINE = "ARGUS Cloud"
    const val VERSION = "0.53.0 (fixture)"

    // ---------------------------------------------------------------- bot editor (P1-TM-04)
    fun editorState(): EditorState = BotEditorModel.open(roster, nova, soul = "You lead research for Finn. Keep briefs short, cite sources, flag price changes over 5 %.")

    // ---------------------------------------------------------------- routing (P1-TM-06)
    fun plan(): Router.Plan = Router.Plan(
        text = "Atlas: book the hotel by Friday, Ledger check the travel budget, done with water plants, move send deck to Monday",
        actions = listOf(
            Router.Act.AddJob("Book the hotel", "atlas", "Atlas", createBot = false, due = "2026-11-13", priority = "normal", dueText = "Fri"),
            Router.Act.AddJob("Check the travel budget", "ledger", "Ledger", createBot = false, due = null, priority = "normal", dueText = "No date"),
            Router.Act.Move("j2", "Send deck to Alex", "2026-11-16", dueSet = true, dueText = "Mon", botId = null, botName = null),
            Router.Act.Miss("water plants"),
        ),
        via = Router.VIA_PHONE + " \u00b7 180 ms",
    )

    // ---------------------------------------------------------------- jobs + desk (P1-TM-05 / TM-07)
    private val t0 = ZonedDateTime.of(today, LocalTime.of(9, 0), zone).toInstant().toEpochMilli()
    private fun job(n: Int, title: String, bot: String? = "nova", due: String? = null, run: JobRun = JobRun.QUEUED, priority: String = "normal",
                    confirmed: Boolean = true, retryAt: Long? = null, status: String = "todo", brief: String = "", result: String? = null,
                    doneAt: Long? = null) =
        Job("j$n", title, bot, due, priority, status, run, source = "user", brief = brief, result = result, confirmed = confirmed,
            retryAt = retryAt, createdAt = t0 + n, doneAt = doneAt, u = t0 + n)

    val jobs: List<Job> = listOf(
        job(1, "Pay ZESCO bill", "ledger", due = "2026-11-11"),                                          // overdue
        job(2, "Send deck to Alex", "atlas", due = "2026-11-12", priority = "high"),                     // today, high
        job(3, "Book hotel near the V&A", "atlas", due = "2026-11-13", run = JobRun.WAITING, status = "waiting"),   // tomorrow, waiting
        job(4, "Plan the Cape Town days", null, due = "2026-11-20"),                                      // upcoming, unassigned
        job(5, "Water plants", run = JobRun.DONE, status = "done", doneAt = t0 + 500),                    // done
        job(6, "Check competitor prices", confirmed = false),                                             // proactive, needs an OK
        job(7, "Scrape the supplier site", "nova", run = JobRun.FAILED, status = "failed",
            brief = "Pull this week's prices from the supplier's catalogue into prices.csv."),            // failed, out of tries
        job(8, "Draft the weekly email", BotRules.MANAGER_ID, run = JobRun.FAILED, status = "failed", retryAt = now + MIN),   // retrying
        job(9, "Read the Q3 report", null),                                                               // unassigned, no date
        job(10, "Summarise support tickets", "echo", run = JobRun.RUNNING, status = "running"),            // running
    )

    fun jobsState(filter: String? = JobsFilter.NEEDS_YOU, query: String = ""): JobsState =
        JobsPageModel.state(jobs, bots, filter, query, null, today)

    val failedSteps: List<JobStep> = listOf(
        JobStep("j7", 1, "nova", "run", t0 + 10 * MIN),
        JobStep("j7", 2, "Open the supplier catalogue", "task_done", t0 + 11 * MIN),
        JobStep("j7", 3, "The site didn't answer (timed out)", "failed", t0 + 12 * MIN),
        JobStep("j7", 4, "Still no answer after 15 min", "failed", t0 + 28 * MIN),
    )

    fun jobSheet(): JobSheetModel.Sheet = JobSheetModel.of(jobs.first { it.id == "j7" }, failedSteps, bots, today, zone)

    /** Desk's chips: 2 waiting in Chat, Needs you from the jobs above, 3 projects. */
    fun deskChips(): List<ChipModel> = DeskModel.chips(INBOX_WAITING, JobsFilter.counts(jobs, today)[JobsFilter.NEEDS_YOU] ?: 0, PROJECTS)
    const val INBOX_WAITING = 2
    const val PROJECTS = 3

    // ---------------------------------------------------------------- A-team import (P1-MG-01)
    /** A-team's Demo team after Copy backup (P0-MG-01's fixture, `app/src/test/resources/ateam`). */
    fun backupText(): String = (P1Fixtures::class.java.getResource("/ateam/demo-team.backup.json")
        ?: throw IllegalStateException("ateam/demo-team.backup.json is not on the test classpath")).readText()

    fun backupFile(): ATeamImport.File = when (val r = ATeamImport.read(backupText())) {
        is ATeamImport.Read.Ok -> r.file
        is ATeamImport.Read.Bad -> throw IllegalStateException("demo backup no longer reads: ${r.problems}")
    }

    /** What Import would do with [backupFile] on a new install (fresh team.db in memory, no name yet): the real preview. */
    fun backupPreview(): ATeamImport.Report {
        val sql = JdbcSql()
        try {
            sql.exec("PRAGMA foreign_keys = ON")
            sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
            val store = TeamStore(sql) { now }
            store.ensure()
            val prefs = object : ImportPrefs { override var userName = ""; override var themeMode = "system" }
            return ATeamImporter(sql, store, JobStore(sql, { now }), prefs).preview(backupFile())
        } finally { sql.close() }
    }
}
