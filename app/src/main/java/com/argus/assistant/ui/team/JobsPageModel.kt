package com.blaze.assistant.ui.team

import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.DueWords
import com.blaze.assistant.team.Job
import com.blaze.assistant.team.JobQueue
import com.blaze.assistant.team.JobRun
import com.blaze.assistant.team.JobStep
import com.blaze.assistant.team.JobStore
import com.blaze.assistant.ui.argus.ArgusTypeScale
import com.blaze.assistant.ui.argus.TypeSpec
import com.blaze.assistant.ui.argus.kit.ChipModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * P1-TM-07: Desk → Jobs (A-team jobs page C1 + Bot HQ tasks per bot), pure so JobsPageModelTest runs it on the JVM.
 *
 * Filters, each over every job in team.db ("open" = not done and not cancelled; a failed job is still open):
 *  - Needs you: open and overdue, or waiting for the user's answer, or failed with no retry left, or proactive and not
 *    confirmed yet (A-team: overdue / approval jobs);
 *  - Today / Tomorrow: open, due that day;  Upcoming: open, due after tomorrow;  Overdue: open, due day passed;
 *  - Done: done (newest first);  All: every job but cancelled ones;  By bot: open jobs of the bot picked in the bot row;
 *  - Unassigned: open with no bot.
 * Search matches every word (any order, any case) against the title, brief, notes, result and the bot's name, on top of
 * the filter. Counts on the chips ignore the search. Order: due day (no due last), then priority, then oldest; Done by
 * when it closed.
 */
object JobsFilter {
    const val NEEDS_YOU = "needs"
    const val TODAY = "today"
    const val TOMORROW = "tomorrow"
    const val UPCOMING = "upcoming"
    const val OVERDUE = "overdue"
    const val DONE = "done"
    const val ALL = "all"
    const val BY_BOT = "bybot"
    const val UNASSIGNED = "unassigned"

    val ORDER = listOf(NEEDS_YOU, TODAY, TOMORROW, UPCOMING, OVERDUE, DONE, ALL, BY_BOT, UNASSIGNED)
    val LABELS = mapOf(NEEDS_YOU to "Needs you", TODAY to "Today", TOMORROW to "Tomorrow", UPCOMING to "Upcoming", OVERDUE to "Overdue",
        DONE to "Done", ALL to "All", BY_BOT to "By bot", UNASSIGNED to "Unassigned")
    /** Chips that show a count (Done, All and By bot would only be noise). */
    private val COUNTED = setOf(NEEDS_YOU, TODAY, TOMORROW, UPCOMING, OVERDUE, UNASSIGNED)
    private val PRI = mapOf("high" to 0, "normal" to 1, "low" to 2)

    fun of(key: String?): String? = key?.trim()?.lowercase(Locale.ROOT)?.takeIf { it in ORDER }

    fun open(j: Job): Boolean = j.run != JobRun.DONE && j.run != JobRun.CANCELLED

    fun overdue(j: Job, today: LocalDate): Boolean = open(j) && DueWords.overdue(j.due, today)

    fun needsYou(j: Job, today: LocalDate): Boolean = open(j) &&
        (DueWords.overdue(j.due, today) || j.run == JobRun.WAITING || (j.run == JobRun.FAILED && j.retryAt == null) || !j.confirmed)

    fun matches(key: String, j: Job, today: LocalDate, bot: String? = null): Boolean {
        val d = DueWords.date(j.due)
        return when (key) {
            NEEDS_YOU -> needsYou(j, today)
            TODAY -> open(j) && d == today
            TOMORROW -> open(j) && d == today.plusDays(1)
            UPCOMING -> open(j) && d != null && d.isAfter(today.plusDays(1))
            OVERDUE -> overdue(j, today)
            DONE -> j.run == JobRun.DONE
            BY_BOT -> open(j) && (bot == null || j.botId == bot)
            UNASSIGNED -> open(j) && j.botId == null
            else -> j.run != JobRun.CANCELLED
        }
    }

    /** Every word of [query] is in the job's text or its bot's name. Blank matches everything. */
    fun search(j: Job, query: String, botName: String?): Boolean {
        val words = JobStore.key(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = JobStore.key(listOf(j.title, j.brief, j.notes, j.result.orEmpty(), botName.orEmpty()).joinToString(" "))
        return words.all { hay.contains(it) }
    }

    fun counts(jobs: List<Job>, today: LocalDate): Map<String, Int> = ORDER.associateWith { k -> jobs.count { matches(k, it, today) } }

    /** Opening Jobs: Needs you when something does, else Today when something is due, else All. */
    fun default(counts: Map<String, Int>): String = when {
        (counts[NEEDS_YOU] ?: 0) > 0 -> NEEDS_YOU
        (counts[TODAY] ?: 0) > 0 -> TODAY
        else -> ALL
    }

    fun chips(counts: Map<String, Int>): List<ChipModel> =
        ORDER.map { k -> ChipModel(k, LABELS.getValue(k), if (k in COUNTED) counts[k]?.takeIf { it > 0 } else null) }

    fun sort(key: String, jobs: List<Job>): List<Job> =
        if (key == DONE) jobs.sortedWith(compareByDescending<Job> { it.doneAt ?: it.u }.thenBy { it.id })
        else jobs.sortedWith(compareBy<Job>({ DueWords.date(it.due) == null }, { it.due ?: "" }, { PRI[it.priority] ?: 1 }, { it.createdAt }, { it.id }))
}

/** One job row (04 §2.8 `TaskRow`): check · title · line (bot · tags) · due (red when overdue) · 4 dp tick when high. */
data class JobRowModel(
    val id: String,
    val title: String,
    val botId: String?,
    val botName: String,
    val line: String,
    val due: String?,
    val overdue: Boolean,
    val done: Boolean,
    val high: Boolean,
    val tags: List<String>,
    val a11y: String,
)

data class JobsState(
    val filter: String,
    val chips: List<ChipModel>,
    val rows: List<JobRowModel>,
    /** The bot row for By bot (open jobs per bot); empty for other filters. */
    val botChips: List<ChipModel>,
    val bot: String?,
    val empty: String?,
    val needsYou: Int,
)

object JobsPageModel {
    /** Due labels, counts in the sheet and log times use the meta type, which has tabular figures (columns line up). */
    val NUMBERS: TypeSpec = ArgusTypeScale.meta

    const val UNASSIGNED_NAME = "Unassigned"

    fun botName(j: Job, bots: Map<String, Bot>): String = j.botId?.let { bots[it]?.name ?: it } ?: UNASSIGNED_NAME

    /** The small words on a row: waiting, running, blocked, failed, needs your OK, high. */
    fun tags(j: Job): List<String> = buildList {
        when {
            j.run == JobRun.RUNNING -> add("running")
            j.run == JobRun.WAITING -> add("waiting")
            j.run == JobRun.FAILED -> add(if (j.retryAt == null) "failed" else "retrying")
            j.run == JobRun.QUEUED && j.status == "blocked" -> add("blocked")
        }
        if (JobsFilter.open(j) && !j.confirmed) add("needs your OK")
        if (j.priority == "high" && JobsFilter.open(j)) add("high")
    }

    fun row(j: Job, bots: Map<String, Bot>, today: LocalDate): JobRowModel {
        val who = botName(j, bots)
        val tags = tags(j)
        val done = j.run == JobRun.DONE
        val due = DueWords.label(j.due, today)
        val overdue = JobsFilter.overdue(j, today)
        val a11y = listOfNotNull(j.title, who, tags.joinToString(", ").ifEmpty { null },
            due?.let { if (overdue) it else "due $it" }, if (done) "done" else null).joinToString(", ")
        return JobRowModel(j.id, j.title, j.botId, who, (listOf(who) + tags).joinToString(" \u00b7 "), due, overdue, done,
            j.priority == "high" && !done, tags, a11y)
    }

    /**
     * The page: chips with counts, the rows of [filter] (null = [JobsFilter.default]) and [query], the bot row for By bot
     * ([bot] null = the first bot with open jobs), and the empty line.
     */
    fun state(jobs: List<Job>, bots: Map<String, Bot>, filter: String?, query: String, bot: String?, today: LocalDate): JobsState {
        val counts = JobsFilter.counts(jobs, today)
        val f = JobsFilter.of(filter) ?: JobsFilter.default(counts)
        val botChips = if (f != JobsFilter.BY_BOT) emptyList() else botChips(jobs, bots)
        val b = if (f != JobsFilter.BY_BOT) null else bot?.takeIf { id -> botChips.any { it.key == id } } ?: botChips.firstOrNull()?.key
        val list = jobs.filter { JobsFilter.matches(f, it, today, b) && JobsFilter.search(it, query, it.botId?.let { id -> bots[id]?.name }) }
        val rows = JobsFilter.sort(f, list).map { row(it, bots, today) }
        return JobsState(f, JobsFilter.chips(counts), rows, botChips, b, if (rows.isEmpty()) empty(f, query, jobs.isEmpty()) else null,
            counts[JobsFilter.NEEDS_YOU] ?: 0)
    }

    /** By bot: one chip per bot with open jobs, the Manager first, then by name; the count is its open jobs. */
    fun botChips(jobs: List<Job>, bots: Map<String, Bot>): List<ChipModel> = jobs.filter { JobsFilter.open(it) && it.botId != null }
        .groupBy { it.botId!! }
        .map { (id, js) -> ChipModel(id, bots[id]?.name ?: id, js.size) }
        .sortedWith(compareBy<ChipModel>({ it.key != BotRules.MANAGER_ID }, { it.label.lowercase(Locale.ROOT) }))

    fun empty(filter: String, query: String, none: Boolean): String = when {
        none -> "No jobs yet. Add one with +, or ask a bot to do something."
        query.isNotBlank() -> "No jobs match \u201c${query.trim()}\u201d."
        else -> when (filter) {
            JobsFilter.NEEDS_YOU -> "Nothing needs you."
            JobsFilter.TODAY -> "Nothing due today."
            JobsFilter.TOMORROW -> "Nothing due tomorrow."
            JobsFilter.UPCOMING -> "Nothing due later."
            JobsFilter.OVERDUE -> "Nothing overdue."
            JobsFilter.DONE -> "No finished jobs yet."
            JobsFilter.BY_BOT -> "No bot has open jobs."
            JobsFilter.UNASSIGNED -> "Every job has a bot."
            else -> "No jobs."
        }
    }
}

/**
 * P1-TM-07: the job sheet (tap a row): who, due, a status sentence, the brief, the result, the log (the job's steps,
 * oldest first, each with its time) and the buttons: Retry (failed, waiting, cancelled, or Start for a proactive job not
 * confirmed yet), Done (any open job) and Cancel job (queued or failed).
 */
object JobSheetModel {
    data class LogLine(val seq: Int, val icon: String, val label: String, val time: String, val bad: Boolean)

    data class Sheet(
        val id: String,
        val title: String,
        val who: String,
        val due: String?,
        val overdue: Boolean,
        val status: String,
        val brief: String?,
        val result: String?,
        val log: List<LogLine>,
        /** "Retry", "Start" or null. */
        val retry: String?,
        val canDone: Boolean,
        val canCancel: Boolean,
    )

    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val DM = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)

    /** "10:42" today, "11 Nov 10:42" another day. */
    fun time(at: Long, today: LocalDate, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(at).atZone(zone)
        return if (t.toLocalDate() == today) t.format(HM) else t.format(DM)
    }

    fun icon(state: String): String = when (state) {
        "done", "task_done" -> "circle-check"
        "failed", "task_failed" -> "circle-alert"
        "run" -> "play"
        "waiting" -> "message-circle"
        "task" -> "list-checks"
        else -> "clock"
    }

    /** A step as a person reads it: a run's agent id is "Started", a task step keeps its words. */
    fun label(s: JobStep): String = when (s.state) {
        "run" -> "Started"
        "waiting" -> "Asked: " + s.label
        "task" -> "Task: " + s.label
        "task_done" -> "Task done: " + s.label
        "task_failed" -> "Task failed: " + s.label
        "failed" -> "Failed: " + s.label
        else -> s.label
    }

    fun status(j: Job, zone: ZoneId, today: LocalDate): String = when (j.run) {
        JobRun.QUEUED -> when {
            !j.confirmed -> "Waits for your OK before it starts"
            j.status == "blocked" -> "Waits on another job"
            j.botId == null -> "Not assigned to a bot"
            else -> "In the queue"
        }
        JobRun.RUNNING -> "Running now"
        JobRun.WAITING -> "Waits for your answer"
        JobRun.DONE -> "Done" + (j.doneAt?.let { " \u00b7 " + time(it, today, zone) } ?: "")
        JobRun.FAILED -> j.retryAt?.let { "Failed \u00b7 tries again at " + time(it, today, zone) } ?: "Failed \u00b7 out of tries"
        JobRun.CANCELLED -> "Cancelled"
    }

    fun of(j: Job, steps: List<JobStep>, bots: Map<String, Bot>, today: LocalDate, zone: ZoneId): Sheet {
        val open = JobsFilter.open(j)
        val retry = when {
            j.run == JobRun.FAILED || j.run == JobRun.WAITING || j.run == JobRun.CANCELLED -> "Retry"
            j.run == JobRun.QUEUED && !j.confirmed -> "Start"
            else -> null
        }
        return Sheet(
            id = j.id, title = j.title, who = JobsPageModel.botName(j, bots), due = DueWords.label(j.due, today),
            overdue = JobsFilter.overdue(j, today), status = status(j, zone, today),
            brief = j.brief.trim().takeIf { it.isNotEmpty() && JobStore.key(it) != JobStore.key(j.title) },
            result = j.result?.trim()?.takeIf { it.isNotEmpty() },
            log = steps.sortedBy { it.seq }.map { LogLine(it.seq, icon(it.state), label(it), time(it.at, today, zone), it.state == "failed" || it.state == "task_failed") },
            retry = retry, canDone = open, canCancel = j.run == JobRun.QUEUED || j.run == JobRun.FAILED,
        )
    }
}

/** P1-TM-07: the FAB's New job sheet. Pure: [problems] says what stops Add; [dueOf] reads the due words. */
object NewJob {
    data class Draft(val title: String = "", val botId: String? = null, val due: String = "", val priority: String = "normal")

    const val TITLE_MAX = 120
    val PRIORITIES = listOf("low", "normal", "high")

    fun dueOf(d: Draft, today: LocalDate): String? = DueWords.parse(d.due, today)

    fun problems(d: Draft, today: LocalDate): List<String> = buildList {
        if (d.title.isBlank()) add("Give the job a title")
        if (d.title.trim().length > TITLE_MAX) add("Keep the title under $TITLE_MAX characters")
        if (d.due.isNotBlank() && dueOf(d, today) == null) add("Couldn't read \u201c${d.due.trim()}\u201d as a date. Try Fri, tomorrow 5pm or 5 Jan")
    }

    /** The due words read back under the field ("Fri 17:00"), or null. */
    fun dueHint(d: Draft, today: LocalDate): String? = dueOf(d, today)?.let { DueWords.label(it, today) }

    /** The bot chips: Unassigned, then the roster (the Manager first, as the roster has it). */
    fun botChips(roster: List<Bot>): List<ChipModel> = listOf(ChipModel("", JobsPageModel.UNASSIGNED_NAME)) + roster.map { ChipModel(it.id, it.name) }
}

/**
 * P1-TM-07: what the Jobs page does to a job. Through [queue] when the jobs runtime is up (so Done and Retry report to the
 * bot's thread and the queue ticks), else straight on the [store] with the same effect on the row and its log. [kick]
 * ticks the queue after a change.
 */
class JobsActions(private val store: JobStore, private val queue: () -> JobQueue?, private val kick: () -> Unit = {}) {
    fun done(id: String): Job? {
        val q = queue()
        val j = if (q != null) q.done(id, null)
        else store.get(id)?.let { if (it.run == JobRun.DONE) it else store.setRun(id, JobRun.DONE)?.also { store.step(id, "Done", "done") } }
        kick(); return j
    }

    fun retry(id: String): Job? {
        val q = queue()
        val j = if (q != null) q.retry(id)
        else store.get(id)?.let { cur ->
            if (cur.run == JobRun.RUNNING || cur.run == JobRun.DONE) cur
            else store.update(id) { it.copy(run = JobRun.QUEUED, status = "todo", retryAt = null, confirmed = true) }?.also { store.step(id, "Retry", "step") }
        }
        kick(); return j
    }

    fun cancel(id: String): Job? {
        val j = queue()?.cancel(id) ?: store.get(id)?.takeIf { it.run != JobRun.DONE }?.let { store.setRun(id, JobRun.CANCELLED) }
        j?.let { store.step(id, "Cancelled", "step") }
        kick(); return j
    }

    /** Adds the draft (source "user"); null when it has problems. A duplicate returns the job already there. */
    fun add(d: NewJob.Draft, today: LocalDate): JobStore.Added? {
        if (NewJob.problems(d, today).isNotEmpty()) return null
        val a = store.add(d.title.trim(), "", d.botId?.ifBlank { null }, NewJob.dueOf(d, today), d.priority, source = "user")
        kick(); return a
    }
}
