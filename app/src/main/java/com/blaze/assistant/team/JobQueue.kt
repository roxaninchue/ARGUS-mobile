package com.blaze.assistant.team

import java.time.LocalDate
import java.time.ZoneId

/**
 * P1-TM-05: the job queue (05 §2.3; A-team jobs.js QUEUE). Decides what runs; the phone glue ([JobRunner] on
 * BgAgents / TaskWorker, TeamAndroid.kt) does the running and calls [finish]. Pure over [TeamPort]:
 * - a job waits on its deps; one running job per bot; user messages first (a bot busy with the user is skipped);
 *   high priority first, then the longest queued; parallel across bots up to [cap] (AgentBudget.cap());
 * - runaway guard: 12 automatic starts per 10 minutes, then paused until the window frees up;
 * - a run that was in flight when the app died is failed on start ("Interrupted…", which retries by itself);
 * - add_job with the title of an open job for the same bot is dropped (and a failed one is retried instead);
 * - failures are logged as steps; network/gateway errors retry after 1, 5, 15 minutes, in plain words;
 * - completion: the reply stays in the bot's thread, a summary card goes to whoever asked (ARGUS or the
 *   delegating bot), and [notify] gets one short line for the notification.
 */
class JobQueue(
    private val port: TeamPort,
    private val team: TeamStore,
    private val threads: BotThreads? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    var cap: () -> Int = { 2 },
    var me: () -> String = { "" },
) {
    /** One short line per finished / failed / waiting job, for the Android notification (bot, text). */
    var notify: (Bot, String) -> Unit = { _, _ -> }
    /** Told whenever a job changes (Desk / Team badges / graph). */
    var changed: () -> Unit = {}
    /** P2-SF-06: a paused bot (Pause bot / Pause all agents) starts no job; its jobs stay queued until resumed. */
    var held: (String) -> Boolean = { com.blaze.assistant.policy.Kill.isPaused(it) }

    private val starts = ArrayList<Long>()
    var paused = false; private set
    private var lastU = 0L
    private var idN = 0

    fun today(): LocalDate = java.time.Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate()
    @Synchronized private fun stamp(): Long { lastU = maxOf(clock(), lastU + 1); return lastU }
    @Synchronized private fun newId(): String = "j" + clock().toString(36) + (idN++).toString(36)

    fun all(): List<Job> = port.jobs()
    fun get(id: String): Job? = port.job(id)
    fun steps(id: String): List<JobStep> = port.steps(id)
    private fun put(j: Job): Job { port.putJob(j); changed(); return j }

    data class Added(val job: Job, val duplicate: Boolean, val note: String)

    /**
     * Creates and queues a job. [due] is a date or due words. [source] user|manager|routine|bot|mcp|suggestion;
     * proactive sources (routine, suggestion) start unconfirmed unless [confirmed].
     */
    @Synchronized fun add(title0: String, botId: String?, due: String? = null, priority: String? = null, deps: List<String> = emptyList(),
                          brief: String = "", notes: String = "", source: String = "user", parentJob: String? = null, confirmed: Boolean? = null): Added {
        val title = title0.lines().firstOrNull { it.isNotBlank() }?.trim()?.take(90).orEmpty()
        require(title.isNotEmpty()) { "a job needs a title" }
        val bot = botId?.let { team.get(it) }
        val open = port.jobs().firstOrNull { Jobs.isOpen(it) && it.botId == bot?.id && Jobs.normTitle(it.title) == Jobs.normTitle(title) }
        if (open != null) {
            if (open.run == JobRun.FAILED || open.run == JobRun.WAITING) { retry(open.id, "add_job"); return Added(port.job(open.id)!!, true, "reopened") }
            return Added(open, true, "already on the board")
        }
        val now = stamp()
        val d = DueWords.parse(due, today()).takeIf { it != DueWords.NONE }
        val src = source.takeIf { it in Jobs.SOURCES } ?: "user"
        val j = Job(id = newId(), title = title, botId = bot?.id, due = d, priority = Jobs.priority(priority), status = Jobs.TODO,
            run = JobRun.QUEUED, deps = deps.filter { id -> port.job(id) != null }.distinct(), parentJob = parentJob, source = src,
            brief = brief.ifBlank { title0.trim() }, notes = notes, confirmed = confirmed ?: (src !in Jobs.PROACTIVE),
            retryAt = null, createdAt = now, doneAt = null, u = now)
        put(j)
        return Added(j, false, "queued")
    }

    /** Back in the queue (A-team QUEUE.enqueue): u = queued-at. */
    @Synchronized fun enqueue(id: String): Job? {
        val j = port.job(id) ?: return null
        if (!Jobs.isOpen(j)) return j
        return put(j.copy(run = JobRun.QUEUED, u = stamp()))
    }

    /** At app start: a run the app was killed in the middle of can't resume; say so instead of leaving it "running". */
    @Synchronized fun recoverOnStart() {
        port.jobs().filter { it.run == JobRun.RUNNING }.forEach { fail(it.id, "Interrupted: the app closed while it ran") }
    }

    /** Failed jobs whose automatic retry time has come go back in the queue. */
    @Synchronized fun retryDue() {
        val now = clock()
        port.jobs().filter { Jobs.isOpen(it) && it.run == JobRun.FAILED && it.retryAt != null && it.retryAt <= now }.forEach { retry(it.id, "auto") }
    }

    /**
     * The next job to start now, or null (A-team QUEUE.kick): due retries first, then the cap, the runaway guard and
     * [Jobs.next]. Marks it RUNNING and counts the start. [busyBots] = bots in a turn with the user.
     */
    @Synchronized fun claim(busyBots: Set<String> = emptySet()): Job? {
        retryDue()
        val all = port.jobs()
        if (all.count { it.run == JobRun.RUNNING } >= cap().coerceAtLeast(1)) return null
        val now = clock()
        val bots = team.all().map { it.id }.filterNot(held).toSet()
        val j = Jobs.next(all, bots, busyBots, now) ?: return null
        starts.removeAll { now - it >= Jobs.WINDOW_MS }
        if (starts.size >= Jobs.LIMIT) { paused = true; return null }
        paused = false; starts += now
        return put(j.copy(run = JobRun.RUNNING, retryAt = null, u = stamp()))
    }

    /** The task message for a claimed job (what the bot reads in its thread). */
    fun brief(j: Job): String = Jobs.brief(j, port.jobs(), me(), today())

    /** What a run ended with. [reply] = the bot's final text; [posted] = the engine already wrote it in the bot's thread. */
    data class RunResult(val reply: String = "", val error: String? = null, val stopped: Boolean = false, val rejected: String? = null, val posted: Boolean = true)

    /**
     * After a run (A-team QUEUE.after): closed by done_job → DONE; done_job refused → blocked; error/stop → failed
     * (with backoff); otherwise the bot is waiting for the user.
     */
    @Synchronized fun finish(id: String, r: RunResult): Job? {
        var j = port.job(id) ?: return null
        val bot = j.botId?.let { team.get(it) }
        val sum = Jobs.summary(r.reply)
        if (bot != null && !r.posted && r.reply.isNotBlank()) threads?.post(bot.id, RowBody.ASSISTANT, r.reply, jobId = j.id)
        when {
            j.status == Jobs.DONE -> {
                j = put(j.copy(run = JobRun.DONE, result = j.result ?: sum.ifEmpty { "Done" }, doneAt = j.doneAt ?: clock(), u = stamp()))
                report(j, bot, sum.ifEmpty { "Done: ${j.title}" }, "done")
            }
            r.rejected != null -> {
                addStep(j.id, Jobs.clip(r.rejected, 200), Jobs.STEP_BLOCKED)
                j = put(j.copy(run = JobRun.FAILED, retryAt = null, u = stamp()))
                report(j, bot, "${j.title}: Blocked: ${Jobs.clip(r.rejected, 90)}", "blocked")
            }
            r.error != null || r.stopped -> j = fail(j.id, if (r.stopped) "Stopped" else r.error!!, r.stopped)!!
            else -> {
                j = put(j.copy(run = JobRun.WAITING, result = sum.ifEmpty { null }, u = stamp()))
                report(j, bot, sum.ifEmpty { "${j.title}: waiting for you" }, "waiting")
            }
        }
        return j
    }

    /** Logs a failure; transient ones get an automatic retry time (A-team QUEUE.fail). */
    @Synchronized fun fail(id: String, err0: String, stopped: Boolean = false): Job? {
        val j = port.job(id) ?: return null
        val err = Jobs.plain(err0)
        addStep(id, Jobs.clip(err, 300), Jobs.STEP_ERROR)
        val n = Jobs.attempts(port.steps(id))
        val wait = Jobs.backoff(err0, n, stopped)
        val out = put(j.copy(run = JobRun.FAILED, retryAt = wait?.let { clock() + it * 60_000L }, u = stamp()))
        val bot = j.botId?.let { team.get(it) }
        report(out, bot, if (stopped) "Stopped: ${j.title}" else "Couldn't finish ${j.title}: ${Jobs.clip(err, 90)}${if (wait != null) " (I'll retry)" else ""}", "failed")
        return out
    }

    /** Puts a failed / blocked / waiting job back in the queue. [by] auto · user · a bot's name. False when it's done. */
    @Synchronized fun retry(id: String, @Suppress("UNUSED_PARAMETER") by: String = "user"): Boolean {
        val j = port.job(id) ?: return false
        if (!Jobs.isOpen(j) || j.run == JobRun.RUNNING) return false
        put(j.copy(run = JobRun.QUEUED, retryAt = null, u = stamp()))
        return true
    }

    /** done_job / the tick on a job: closes it (frees the jobs that wait on it). */
    @Synchronized fun done(id: String, result: String? = null): Job? {
        val j = port.job(id) ?: return null
        if (j.status == Jobs.DONE) return j
        val running = j.run == JobRun.RUNNING   // the run's finish() reports it
        val out = put(j.copy(status = Jobs.DONE, run = if (running) JobRun.RUNNING else JobRun.DONE, result = result?.takeIf { it.isNotBlank() } ?: j.result,
            doneAt = clock(), u = stamp()))
        return out
    }

    /** Un-ticks a done job (A-team doneChanged(false)). */
    @Synchronized fun reopen(id: String): Job? {
        val j = port.job(id) ?: return null
        return put(j.copy(status = Jobs.TODO, run = JobRun.QUEUED, doneAt = null, u = stamp()))
    }

    @Synchronized fun cancel(id: String): Job? {
        val j = port.job(id) ?: return null
        return put(j.copy(run = JobRun.CANCELLED, retryAt = null, u = stamp()))
    }

    /** update_job / the job sheet: change title, due, priority, notes, assignee or confirmation. */
    @Synchronized fun update(id: String, title: String? = null, due: String? = null, priority: String? = null, notes: String? = null,
                             botId: String? = null, unassign: Boolean = false, confirmed: Boolean? = null): Job? {
        val j = port.job(id) ?: return null
        val d = if (due == null) j.due else DueWords.parse(due, today()).let { if (it == DueWords.NONE) j.due else it }
        val b = when { unassign -> null; botId != null -> team.get(botId)?.id ?: j.botId; else -> j.botId }
        return put(j.copy(title = title?.trim()?.take(90)?.ifEmpty { null } ?: j.title, due = d, priority = priority?.let { Jobs.priority(it) } ?: j.priority,
            notes = notes ?: j.notes, botId = b, confirmed = confirmed ?: j.confirmed, u = stamp()))
    }

    /** A deleted bot's jobs become unassigned (A-team removeAgent); a running one stops. */
    @Synchronized fun unassign(botId: String) {
        port.jobs().filter { it.botId == botId }.forEach { put(it.copy(botId = null, run = if (it.run == JobRun.RUNNING) JobRun.QUEUED else it.run, u = stamp())) }
    }

    /** Bot HQ create_task / update_task: a progress step on a job. Returns the step. */
    @Synchronized fun addStep(jobId: String, label: String, state: String = "todo"): JobStep {
        val seq = (port.steps(jobId).maxOfOrNull { it.seq } ?: 0) + 1
        return JobStep(jobId, seq, label.trim().take(300), state, clock()).also { port.putStep(it) }
    }

    @Synchronized fun setStep(jobId: String, seq: Int, state: String, label: String? = null): JobStep? {
        val s = port.steps(jobId).firstOrNull { it.seq == seq } ?: return null
        return s.copy(state = state, label = label?.trim()?.take(300) ?: s.label, at = clock()).also { port.putStep(it) }
    }

    // ------------------------------------------------------------ views

    /** working · waiting · idle (A-team QUEUE.status). */
    fun status(botId: String): String {
        val mine = port.jobs().filter { it.botId == botId }
        return when {
            mine.any { it.run == JobRun.RUNNING } -> "working"
            mine.any { Jobs.isOpen(it) && it.run in setOf(JobRun.QUEUED, JobRun.WAITING, JobRun.FAILED) } -> "waiting"
            else -> "idle"
        }
    }

    /** The job a bot is on: running, else waiting / failed, else queued. */
    fun current(botId: String): Job? {
        val mine = port.jobs().filter { it.botId == botId && Jobs.isOpen(it) }
        return mine.firstOrNull { it.run == JobRun.RUNNING } ?: mine.firstOrNull { it.run == JobRun.WAITING || it.run == JobRun.FAILED }
            ?: mine.firstOrNull { it.run == JobRun.QUEUED }
    }

    fun line(j: Job) = Jobs.line(j, port.steps(j.id), port.jobs(), clock())

    fun failed(botId: String? = null) = port.jobs().filter { Jobs.isOpen(it) && it.run == JobRun.FAILED && (botId == null || it.botId == botId) }

    /** The FAILED JOBS block bots read in their prompt (A-team QUEUE.failedBlock); "" when none. */
    fun failedBlock(botId: String, all: Boolean): String {
        val l = failed(if (all) null else botId).takeLast(8)
        if (l.isEmpty()) return ""
        return "\n\nFAILED JOBS (logged by the app; pick them up again when you can):\n" + l.joinToString("\n") { j ->
            val st = port.steps(j.id)
            val who = j.botId?.let { team.get(it)?.name }
            "- #${Jobs.ref(j)} \"${j.title}\"${if (all && who != null) " for $who" else ""}: ${Jobs.lastError(st) ?: "failed"} (tries: ${Jobs.attempts(st).coerceAtLeast(1)}${if (j.retryAt != null) ", auto-retry soon" else ""})"
        } + "\nTo retry one, call retry_job with its # or title (or add_job with the same title, which reopens it). Fix the cause first when you can; never create a second job for the same thing."
    }

    /** Finds an open job by #ref, id or title (A-team retry_job lookup). [botId] limits it to one bot's jobs. */
    fun find(q0: String, botId: String? = null, includeDone: Boolean = false): Job? {
        val q = q0.removePrefix("#").trim()
        if (q.isEmpty()) return null
        val pool = port.jobs().filter { (includeDone || Jobs.isOpen(it)) && (botId == null || it.botId == botId) }
        val nq = Jobs.normTitle(q)
        return pool.firstOrNull { it.id == q } ?: pool.firstOrNull { Jobs.ref(it).equals(q.replace(Regex("[^A-Za-z0-9]"), ""), true) }
            ?: pool.firstOrNull { Jobs.normTitle(it.title) == nq } ?: pool.firstOrNull { nq.isNotEmpty() && Jobs.normTitle(it.title).contains(nq) }
    }

    /** Who asked for this job: ARGUS for manager-made jobs, the parent job's bot for bot-delegated ones, else nobody (the user). */
    fun requester(j: Job): String? = when {
        j.parentJob != null -> port.job(j.parentJob)?.botId?.takeIf { it != j.botId }
        j.source == "manager" -> Bots.MANAGER_ID.takeIf { it != j.botId }
        else -> null
    }

    private fun report(j: Job, bot: Bot?, line: String, state: String) {
        val who = bot ?: return
        requester(j)?.let { req -> threads?.card(req, "job", mapOf("job" to j.id, "title" to j.title, "state" to state, "from" to who.id, "text" to Jobs.clip(line))) }
        notify(who, Jobs.clip(line))
    }
}
