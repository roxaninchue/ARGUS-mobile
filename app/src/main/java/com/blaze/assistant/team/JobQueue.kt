package com.blaze.assistant.team

import java.time.LocalDate

/**
 * P1-TM-05: the jobs queue (05 §2.3, A-team `jobs.js` QUEUE). Pure apart from the [JobStore] it reads and the [Runner]
 * it starts work on, so JobsQueueTest drives it on the JVM with a fake runner and a fake clock. JobsRuntime (Android)
 * wires the runner to BgAgents and calls [tick] when something changes.
 *
 * Rules, in the order [tick] checks them:
 *  - only jobs that are QUEUED (or FAILED with a due `retry_at`), confirmed (proactive jobs never act unconfirmed),
 *    and assigned to an enabled bot start;
 *  - a job waits on its deps: any dep not DONE keeps it queued with status "blocked" (a dep the user marks done by hand
 *    lets it go on the next tick);
 *  - one running job per bot: a bot with a RUNNING or WAITING job starts nothing else;
 *  - user messages first: a bot whose own thread has a turn going starts no job until it ends;
 *  - at most [cap] jobs run at once across bots (AgentBudget's cap for the phone);
 *  - runaway guard: at most [RUNAWAY_STARTS] automatic starts in [RUNAWAY_WINDOW_MS]; past that nothing starts until
 *    the window clears;
 *  - order: priority high, normal, low; then the earliest due; then the oldest.
 *
 * A run ends through [finished]: with `done_job` called it is DONE (already set by the tool); a run that ended with an
 * answer but no `done_job` is WAITING (the bot asked something; its words are logged); an error is a failure with
 * backoff 1, 5, 15 minutes ([JobFailure]); a cancelled run puts the job back in the queue only if the user retries.
 * On start ([recover]) a RUNNING job whose run is not alive any more is a failure ("The app closed while this ran").
 */
class JobQueue(
    private val store: JobStore,
    private val team: TeamStore,
    private val runner: Runner,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cap: () -> Int = { 2 },
    private val busy: (botId: String) -> Boolean = { false },
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    /** Where jobs run. [start] returns the run's id (a BgAgents id on the phone) or null when it could not start. */
    interface Runner {
        fun start(job: Job, bot: Bot, brief: String): String?
        fun alive(runId: String): Boolean
        /** A job finished, failed or is waiting: post it in the bot's thread and summarise it to the requester. */
        fun report(job: Job, bot: Bot?, event: Report)
    }

    enum class Kind { STARTED, DONE, WAITING, FAILED, GAVE_UP }
    data class Report(val kind: Kind, val text: String)

    companion object {
        const val RUNAWAY_STARTS = 12
        const val RUNAWAY_WINDOW_MS = 10 * 60_000L
        private val PRI = mapOf("high" to 0, "normal" to 1, "low" to 2)
    }

    /** The A-team brief (QUEUE.brief) for [job], plus its due day and the error a retry should know about. */
    fun brief(job: Job): String = buildString {
        append("You are assigned job #").append(job.id).append(": ").append(job.title).append('.')
        job.due?.let { d -> DueWords.label(d, today())?.let { append(" Due: ").append(it).append('.') } }
        append("\nDo it now.")
        if (job.brief.isNotBlank() && JobStore.key(job.brief) != JobStore.key(job.title)) append("\n\n").append(job.brief.trim())
        store.failures(job.id).lastOrNull()?.let { append("\n\nThe last try failed: ").append(it.label).append(". Check what is already done first; don't redo it.") }
        append("\n\nWhen finished, call done_job with id ").append(job.id).append(" and the result in a sentence or two.")
        append(" If you truly need something from the person, ask it in one line and stop; the job waits for their answer.")
    }

    private fun Job.depsDone(): Boolean = deps.all { d -> store.get(d)?.run == JobRun.DONE }

    private fun retryDue(j: Job, now: Long) = j.run == JobRun.FAILED && j.retryAt != null && j.retryAt <= now

    /** Jobs that would start now, in order (no side effects). */
    fun ready(now: Long = clock()): List<Job> {
        val all = store.all()
        val busyBots = all.filter { it.run == JobRun.RUNNING || it.run == JobRun.WAITING }.mapNotNull { it.botId }.toMutableSet()
        var slots = cap() - all.count { it.run == JobRun.RUNNING }
        var guard = RUNAWAY_STARTS - store.startsSince(now - RUNAWAY_WINDOW_MS)
        val bots = team.roster().associateBy { it.id }
        val out = ArrayList<Job>()
        val candidates = all.filter { (it.run == JobRun.QUEUED || retryDue(it, now)) && it.confirmed && it.botId != null }
            .sortedWith(compareBy<Job>({ PRI[it.priority] ?: 1 }, { DueWords.date(it.due) ?: LocalDate.MAX }, { it.createdAt }, { it.id }))
        for (j in candidates) {
            if (slots <= 0 || guard <= 0) break
            val b = j.botId!!
            if (b !in bots || b in busyBots || busy(b) || !j.depsDone()) continue
            out += j; busyBots += b; slots--; guard--
        }
        return out
    }

    /** True when the runaway guard is holding starts back. */
    fun guarded(now: Long = clock()): Boolean = store.startsSince(now - RUNAWAY_WINDOW_MS) >= RUNAWAY_STARTS

    /** Updates the "blocked" words, then starts what may start. Returns the jobs started. */
    @Synchronized fun tick(now: Long = clock()): List<Job> {
        for (j in store.all()) if (j.run == JobRun.QUEUED) {
            val want = if (j.depsDone()) "todo" else "blocked"
            if (j.status != want) store.update(j.id) { it.copy(status = want) }
        }
        val started = ArrayList<Job>()
        for (j in ready(now)) {
            val bot = team.get(j.botId!!) ?: continue
            val brief = brief(j)
            val running = store.setRun(j.id, JobRun.RUNNING) ?: continue
            val run = try { runner.start(running, bot, brief) } catch (e: Exception) { null }
            if (run == null) { fail(running.id, "It couldn't be started", now); continue }
            store.step(j.id, run, "run")
            started += running
            runner.report(running, bot, Report(Kind.STARTED, brief))
        }
        return started
    }

    /** Marks RUNNING jobs whose run is gone as failed (the app closed while they ran). Call once when the app starts. */
    @Synchronized fun recover(now: Long = clock()): List<Job> = store.all().filter { it.run == JobRun.RUNNING }.filter { j ->
        val run = store.runOf(j.id)
        run == null || !runner.alive(run)
    }.mapNotNull { fail(it.id, "The app closed while this ran", now) }

    /**
     * A run ended. [ok] = it ended without an error; [text] = its last reply or the error. A job that `done_job` already
     * closed is left as it is.
     */
    @Synchronized fun finished(jobId: String, ok: Boolean, text: String?, cancelled: Boolean = false, now: Long = clock()): Job? {
        val j = store.get(jobId) ?: return null
        if (j.run != JobRun.RUNNING) return j
        val bot = j.botId?.let { team.get(it) }
        return when {
            cancelled -> store.setRun(j.id, JobRun.CANCELLED)?.also { store.step(j.id, "Stopped", "step") }
            !ok -> fail(j.id, text, now)
            else -> {
                val asked = text?.trim().orEmpty().ifEmpty { "It stopped without finishing" }
                store.step(j.id, asked.take(500), "waiting")
                store.setRun(j.id, JobRun.WAITING)?.also { runner.report(it, bot, Report(Kind.WAITING, asked)) }
            }
        }
    }

    private fun fail(id: String, error: String?, now: Long): Job? {
        val words = JobFailure.plain(error)
        store.step(id, words, "failed")
        val n = store.failures(id).size
        val at = JobFailure.retryAt(n, now)
        val j = store.update(id) { it.copy(run = JobRun.FAILED, status = "failed", retryAt = at) } ?: return null
        runner.report(j, j.botId?.let { team.get(it) }, Report(if (at == null) Kind.GAVE_UP else Kind.FAILED, words))
        return j
    }

    // ---------------- by hand (the job sheet, done_job, retry_job) ----------------

    /** Closes a job with its result (done_job, or the user ticking it). Its dependants may start on the next tick. */
    @Synchronized fun done(id: String, result: String?): Job? {
        val j = store.get(id) ?: return null
        if (j.run == JobRun.DONE) return j
        val r = result?.trim()?.takeIf { it.isNotEmpty() }
        val d = store.update(id) { it.copy(run = JobRun.DONE, status = "done", result = r ?: it.result, doneAt = clock(), retryAt = null) } ?: return null
        store.step(id, "Done", "done")
        runner.report(d, d.botId?.let { team.get(it) }, Report(Kind.DONE, r ?: "Done"))
        return d
    }

    /** Puts a failed, waiting or cancelled job back in the queue now (retry_job, the sheet's Retry, a person's answer). */
    @Synchronized fun retry(id: String): Job? {
        val j = store.get(id) ?: return null
        if (j.run == JobRun.RUNNING || j.run == JobRun.DONE) return j
        return store.update(id) { it.copy(run = JobRun.QUEUED, status = "todo", retryAt = null, confirmed = true) }?.also { store.step(id, "Retry", "step") }
    }

    /** Next day for an overdue job (the OkCard's Tomorrow). */
    fun tomorrow(id: String): Job? = store.update(id) { it.copy(due = today().plusDays(1).toString()) }

    fun cancel(id: String): Job? = store.get(id)?.takeIf { it.run != JobRun.DONE }?.let { store.setRun(id, JobRun.CANCELLED) }
}

/**
 * P1-TM-05: the job tools every chat gets through TeamHooks (05 §8 `jobs` group): A-team add_job, done_job, update_job,
 * agent_delegate, retry_job, agent_status; Bot HQ create_task / update_task, which become steps of the job the bot is
 * running. [current] = the job a chat is running (JobsRuntime knows which run belongs to which chat). Pure: arguments
 * and results are JSON / plain text, so JobsQueueTest calls it directly.
 */
class JobTools(private val store: JobStore, private val queue: JobQueue, private val team: TeamStore,
               private val current: (chatId: String) -> String? = { null }, private val today: () -> LocalDate = { LocalDate.now() },
               private val changed: () -> Unit = {}) {

    companion object {
        val NAMES = setOf("add_job", "done_job", "update_job", "agent_delegate", "retry_job", "agent_status", "create_task", "update_task")
        const val CHAT_PREFIX = "job-"

        /** The job a job chat is running ("job-<id>-<n>", JobsRuntime names them so), or null. */
        fun jobOfChat(chatId: String): String? = chatId.takeIf { it.startsWith(CHAT_PREFIX) }?.removePrefix(CHAT_PREFIX)?.substringBefore('-')?.ifEmpty { null }
    }

    private fun fn(name: String, about: String, props: Map<String, Map<String, Any?>>, required: List<String>) = TeamJson.write(linkedMapOf(
        "type" to "function", "function" to linkedMapOf("name" to name, "description" to about,
            "parameters" to linkedMapOf("type" to "object", "properties" to props, "required" to required))))
    private fun str(d: String) = linkedMapOf<String, Any?>("type" to "string", "description" to d)

    fun defs(): List<String> = listOf(
        fn("add_job", "Add a job to the team's queue, for a bot (by name) or unassigned. It runs in the background when its bot is free and the jobs it waits on are done. The same open job is never added twice.",
            linkedMapOf("title" to str("Short title"), "brief" to str("What to do, with every detail the bot needs"), "agent" to str("Bot name, or empty for unassigned"),
                "due" to str("When it's due in words: today, Fri, tomorrow 5pm, Jan 5"), "priority" to str("high, normal or low"),
                "deps" to linkedMapOf("type" to "array", "items" to str("job id"), "description" to "Job ids this one waits on")), listOf("title")),
        fn("agent_delegate", "Hand a task to another bot as a job and keep going; its result comes back to you when it's done.",
            linkedMapOf("agent" to str("Bot name"), "task" to str("What to do, complete and unambiguous"), "due" to str("Optional due words")), listOf("agent", "task")),
        fn("done_job", "Close your job with its result. Call it once the work is really done.",
            linkedMapOf("id" to str("Job id (default: the job you are running)"), "result" to str("What you did or found, in a sentence or two")), listOf("result")),
        fn("update_job", "Change a job: title, due, priority, notes, who does it, or cancel it (status cancelled).",
            linkedMapOf("id" to str("Job id"), "title" to str(""), "due" to str("Due words"), "priority" to str("high, normal or low"), "notes" to str(""),
                "agent" to str("Bot name"), "status" to str("cancelled to cancel")), listOf("id")),
        fn("retry_job", "Put a failed or waiting job back in the queue now.", linkedMapOf("id" to str("Job id")), listOf("id")),
        fn("agent_status", "A bot's recent jobs and how each went.", linkedMapOf("agent" to str("Bot name")), listOf("agent")),
        fn("create_task", "Add a step to the job you are running (shows in its progress).", linkedMapOf("title" to str("The step")), listOf("title")),
        fn("update_task", "Mark a step of your job done or failed.", linkedMapOf("step" to str("Step number"), "status" to str("done, failed or in_progress")), listOf("step", "status")),
    )

    /** A bot by id or name (case-insensitive), enabled ones only. */
    fun bot(nameOrId: String?): Bot? {
        val n = nameOrId?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() } ?: return null
        val all = team.roster()
        return all.firstOrNull { it.id.equals(n, true) } ?: all.firstOrNull { it.name.equals(n, true) } ?: all.firstOrNull { it.label.equals(n, true) }
    }

    private fun label(j: Job): String = "#${j.id} \u00ab${j.title}\u00bb"
    private fun who(j: Job): String = j.botId?.let { team.get(it)?.name } ?: "unassigned"

    fun run(chatId: String, botId: String?, name: String, args: String): String {
        @Suppress("UNCHECKED_CAST")
        val a = (try { TeamJson.read(args.ifBlank { "{}" }) } catch (_: Exception) { null } as? Map<String, Any?>) ?: emptyMap()
        fun s(k: String) = (a[k] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: (a[k] as? Number)?.toString()
        val caller = botId ?: BotRules.MANAGER_ID
        val source = if (caller == BotRules.MANAGER_ID) "manager" else "bot:$caller"
        val out = when (name) {
            "add_job", "agent_delegate" -> {
                val agentWord = s("agent")
                val target = agentWord?.let { bot(it) ?: return "Error: there's no bot called \"$it\" on the team." }
                val brief = s("brief") ?: s("task") ?: ""
                val title = s("title") ?: brief.lineSequence().first().take(60)
                if (title.isBlank()) return "Error: give the job a title."
                if (name == "agent_delegate" && target == null) return "Error: say which bot should do it."
                val dueWords = s("due")
                val due = dueWords?.let { DueWords.parse(it, today()) }
                @Suppress("UNCHECKED_CAST") val deps = (a["deps"] as? List<Any?>).orEmpty().mapNotNull { (it as? String)?.removePrefix("#") }
                when (val r = store.add(title, brief, target?.id, due, s("priority") ?: "normal", deps, current(chatId), source)) {
                    is JobStore.Added.Duplicate -> "Already in the queue: ${label(r.job)} for ${who(r.job)} (${r.job.status}). No new job added."
                    is JobStore.Added.Reopened -> "Reopened ${label(r.job)} for ${who(r.job)}; it's back in the queue."
                    is JobStore.Added.New -> "Added ${label(r.job)} for ${who(r.job)}" +
                        (r.job.due?.let { ", due ${DueWords.label(it, today())}" } ?: if (dueWords != null) " (couldn't read the due date \"$dueWords\"; none set)" else "") +
                        (if (r.job.deps.isNotEmpty()) ", after ${r.job.deps.joinToString { "#$it" }}" else "") + "."
                }
            }
            "done_job" -> {
                val id = s("id")?.removePrefix("#") ?: current(chatId) ?: return "Error: which job? Give its id."
                val result = s("result") ?: return "Error: say what you did in result."
                val j = store.get(id) ?: return "Error: no job #$id."
                if (j.run == JobRun.DONE) return "Job ${label(j)} was already done."
                queue.done(id, result)?.let { "Done: ${label(it)}." } ?: "Error: no job #$id."
            }
            "update_job" -> {
                val id = s("id")?.removePrefix("#") ?: return "Error: which job? Give its id."
                if (store.get(id) == null) return "Error: no job #$id."
                if (s("status")?.lowercase() in setOf("cancelled", "canceled", "cancel")) return queue.cancel(id)?.let { "Cancelled ${label(it)}." }.also { changed() } ?: "Job #$id is already done."
                val target = s("agent")?.let { bot(it) ?: return "Error: there's no bot called \"$it\" on the team." }
                val due = s("due")?.let { DueWords.parse(it, today()) ?: return "Error: couldn't read the due date \"${s("due")}\"." }
                val j = store.update(id) { j0 -> j0.copy(title = s("title") ?: j0.title, due = due ?: j0.due,
                    priority = s("priority")?.let { JobStore.priority(it) } ?: j0.priority, notes = s("notes") ?: j0.notes, botId = target?.id ?: j0.botId) }!!
                "Updated ${label(j)}: ${who(j)}, ${j.priority}" + (j.due?.let { ", due ${DueWords.label(it, today())}" } ?: "") + "."
            }
            "retry_job" -> {
                val id = s("id")?.removePrefix("#") ?: return "Error: which job? Give its id."
                val j = queue.retry(id) ?: return "Error: no job #$id."
                when (j.run) { JobRun.RUNNING -> "${label(j)} is running now."; JobRun.DONE -> "${label(j)} is already done."; else -> "${label(j)} is back in the queue." }
            }
            "agent_status" -> {
                val b = bot(s("agent")) ?: return "Error: there's no bot called \"${s("agent")}\" on the team."
                val js = store.forBot(b.id, 8)
                if (js.isEmpty()) "${b.name} has no jobs." else "${b.name}'s recent jobs:\n" + js.joinToString("\n") { j ->
                    "${label(j)}: ${j.status}" + (j.due?.let { ", due ${DueWords.label(it, today())}" } ?: "") +
                        (if (j.run == JobRun.FAILED) ", last failure: " + (store.failures(j.id).lastOrNull()?.label ?: "unknown") else "") +
                        (j.result?.let { ", result: ${it.take(160)}" } ?: "")
                }
            }
            "create_task" -> {
                val id = current(chatId) ?: return "Error: create_task works inside a job; you aren't running one."
                val st = store.step(id, s("title") ?: return "Error: give the step a title.", "task")
                "Step ${st.seq} added."
            }
            "update_task" -> {
                val id = current(chatId) ?: return "Error: update_task works inside a job; you aren't running one."
                val n = s("step")?.toIntOrNull() ?: return "Error: give the step number."
                val st = when (s("status")?.lowercase()) { "done", "completed", "complete" -> "task_done"; "failed", "error" -> "task_failed"; else -> "task" }
                if (store.steps(id).none { it.seq == n && it.state.startsWith("task") }) return "Error: no step $n."
                store.setStepState(id, n, st); "Step $n: ${st.removePrefix("task_").replace("task", "in progress")}."
            }
            else -> return "Error: unknown job tool $name."
        }
        if (name != "agent_status") changed()
        return out
    }
}
