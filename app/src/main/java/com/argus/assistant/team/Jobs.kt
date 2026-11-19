package com.blaze.assistant.team

import com.blaze.assistant.store.Sql
import java.util.Locale

/**
 * P1-TM-05: the jobs table (05 §2.3, A-team `jobs.js` model) over the same [Sql] seam as [TeamStore], so JobsQueueTest
 * runs it on the JVM (sqlite-jdbc) and the phone on team.db ([TeamSql]). No schema change (team.db stays v2).
 *
 * Columns as the schema has them; how this file uses the free-text ones:
 *  - `run`: QUEUED → RUNNING → DONE | WAITING (the bot ended without `done_job`; it asked something) | FAILED (with
 *    `retry_at` while a retry is due) | CANCELLED.
 *  - `status`: the word a list shows: todo, doing, blocked (waits on a dep), waiting, done, failed, cancelled.
 *  - `source`: who asked, "user", "manager", "routine", "mcp", "suggestion" or "bot:<id>" (the requester a summary goes
 *    back to; A-team has no requester column and the schema stays as is).
 *  - `job_steps`: the job's log. States: "step" (a line the app logs), "task" / "task_done" / "task_failed" (BH
 *    create_task / update_task), "done" (the job closed), "failed"
 *    (a failure, its label in plain words), "run" (a start, label = the background agent id that runs it), "waiting"
 *    (what the bot asked when it stopped).
 *
 * Every call does disk I/O: run it off the main thread.
 */
class JobStore(private val sql: Sql, private val clock: () -> Long = { System.currentTimeMillis() },
               private val newId: () -> String = { "j" + java.util.UUID.randomUUID().toString().replace("-", "").take(8) }) {

    sealed class Added {
        abstract val job: Job
        data class New(override val job: Job) : Added()
        /** The same open job for the same bot already exists (A-team: duplicate add_job dropped). */
        data class Duplicate(override val job: Job) : Added()
        /** A failed or cancelled job with the same title for the same bot was put back in the queue. */
        data class Reopened(override val job: Job) : Added()
    }

    companion object {
        const val COLS = "id, title, IFNULL(bot_id,''), IFNULL(due,''), priority, status, run, deps, IFNULL(parent_job,''), source, brief, " +
            "IFNULL(result,''), notes, confirmed, IFNULL(retry_at,-1), created_at, IFNULL(done_at,-1), u"
        val PRIORITIES = listOf("high", "normal", "low")
        val OPEN = setOf(JobRun.QUEUED, JobRun.RUNNING, JobRun.WAITING)

        /** "Greet the user!" and "greet  the user" are the same job. */
        fun key(title: String): String = title.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        fun priority(p: String?): String = p?.trim()?.lowercase(Locale.ROOT)?.let { v ->
            when (v) { "urgent", "high", "asap", "1" -> "high"; "low", "someday", "3" -> "low"; else -> "normal" }
        } ?: "normal"

        fun requester(source: String): String? = source.takeIf { it.startsWith("bot:") }?.removePrefix("bot:")?.takeIf { it.isNotBlank() }
        fun sourceKind(source: String): String = source.substringBefore(':')

        private fun deps(s: String): List<String> = try {
            @Suppress("UNCHECKED_CAST") (TeamJson.read(s) as? List<Any?>).orEmpty().mapNotNull { it as? String }
        } catch (_: Exception) { emptyList() }
    }

    private fun toJob(r: Sql.Row) = Job(
        id = r.text(0), title = r.text(1), botId = r.text(2).ifEmpty { null }, due = r.text(3).ifEmpty { null }, priority = r.text(4),
        status = r.text(5), run = runCatching { JobRun.valueOf(r.text(6)) }.getOrDefault(JobRun.QUEUED), deps = deps(r.text(7)),
        parentJob = r.text(8).ifEmpty { null }, source = r.text(9), brief = r.text(10), result = r.text(11).ifEmpty { null }, notes = r.text(12),
        confirmed = r.long(13) != 0L, retryAt = r.long(14).takeIf { it >= 0 }, createdAt = r.long(15), doneAt = r.long(16).takeIf { it >= 0 }, u = r.long(17))

    fun get(id: String): Job? = sql.query("SELECT $COLS FROM jobs WHERE id=?", id.trim()) { toJob(it) }.firstOrNull()

    fun all(): List<Job> = sql.query("SELECT $COLS FROM jobs ORDER BY created_at, id") { toJob(it) }

    fun forBot(botId: String, limit: Int = 50): List<Job> =
        sql.query("SELECT $COLS FROM jobs WHERE bot_id=? ORDER BY created_at DESC, id DESC LIMIT ?", botId, limit) { toJob(it) }

    fun open(): List<Job> = all().filter { it.run in OPEN }

    // UPDATE or INSERT, never INSERT OR REPLACE: a replace deletes the row first and ON DELETE CASCADE would take its steps.
    /** Writes [j] as it is, without a transaction of its own (P1-MG-01 imports inside one [Sql.tx]). */
    internal fun write(j: Job) {
        val v = arrayOf<Any?>(j.title, j.botId, j.due, j.priority, j.status, j.run.name, TeamJson.write(j.deps), j.parentJob, j.source, j.brief, j.result,
            j.notes, if (j.confirmed) 1 else 0, j.retryAt, j.createdAt, j.doneAt, j.u)
        val there = sql.query("SELECT 1 FROM jobs WHERE id=?", j.id) { 1 }.isNotEmpty()
        if (there) sql.exec("UPDATE jobs SET title=?, bot_id=?, due=?, priority=?, status=?, run=?, deps=?, parent_job=?, source=?, brief=?, result=?, " +
            "notes=?, confirmed=?, retry_at=?, created_at=?, done_at=?, u=? WHERE id=?", *v, j.id)
        else sql.exec("INSERT INTO jobs(title, bot_id, due, priority, status, run, deps, parent_job, source, brief, result, notes, confirmed, " +
            "retry_at, created_at, done_at, u, id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", *v, j.id)
    }

    /** Writes [j] as it is (tests and imports). */
    fun put(j: Job) = sql.tx { write(j) }

    /**
     * Adds a job, or returns the open one already there for the same bot and title, or reopens a failed / cancelled
     * one with that title. Unknown deps are dropped. [confirmed] false = proactive: it never starts until confirmed.
     */
    fun add(title: String, brief: String, botId: String?, due: String? = null, priority: String = "normal", deps: List<String> = emptyList(),
            parent: String? = null, source: String = "user", confirmed: Boolean = true): Added = sql.tx {
        val t = title.trim().ifEmpty { brief.trim().lineSequence().first().take(60) }.ifEmpty { "Job" }.take(120)
        val k = key(t)
        val same = all().filter { it.botId == botId && key(it.title) == k }
        same.firstOrNull { it.run in OPEN }?.let { return@tx Added.Duplicate(it) }
        val now = clock()
        same.lastOrNull { it.run == JobRun.FAILED || it.run == JobRun.CANCELLED }?.let { old ->
            val j = old.copy(run = JobRun.QUEUED, status = "todo", retryAt = null, doneAt = null, result = null,
                brief = brief.ifBlank { old.brief }, due = due ?: old.due, u = now)
            write(j); step(j.id, "Reopened", "step")
            return@tx Added.Reopened(j)
        }
        val known = deps.map { it.trim() }.filter { it.isNotEmpty() && get(it) != null }.distinct()
        val j = Job(newId(), t, botId, due, priority(priority), "todo", JobRun.QUEUED, known, parent?.takeIf { it.isNotBlank() }, source,
            brief.trim().ifEmpty { t }, confirmed = confirmed, createdAt = now, u = now)
        write(j)
        Added.New(j)
    }

    /** Applies [f] and stamps `u`; null when the job is gone. */
    fun update(id: String, f: (Job) -> Job): Job? = sql.tx {
        val j = get(id) ?: return@tx null
        f(j).copy(id = j.id, u = clock()).also { write(it) }
    }

    fun setRun(id: String, run: JobRun, status: String = statusOf(run)): Job? = update(id) {
        it.copy(run = run, status = status, doneAt = if (run == JobRun.DONE || run == JobRun.CANCELLED) clock() else it.doneAt,
            retryAt = if (run == JobRun.FAILED) it.retryAt else null)
    }

    fun statusOf(run: JobRun): String = when (run) {
        JobRun.QUEUED -> "todo"; JobRun.RUNNING -> "doing"; JobRun.WAITING -> "waiting"
        JobRun.DONE -> "done"; JobRun.FAILED -> "failed"; JobRun.CANCELLED -> "cancelled"
    }

    // ---------------- steps (the job's log) ----------------

    fun step(jobId: String, label: String, state: String = "step"): JobStep {
        val seq = (sql.query("SELECT IFNULL(MAX(seq),0) FROM job_steps WHERE job_id=?", jobId) { it.long(0) }.firstOrNull() ?: 0L).toInt() + 1
        val s = JobStep(jobId, seq, label.trim().take(500), state, clock())
        sql.exec("INSERT INTO job_steps(job_id, seq, label, state, at) VALUES(?,?,?,?,?)", s.jobId, s.seq, s.label, s.state, s.at)
        return s
    }

    fun steps(jobId: String): List<JobStep> =
        sql.query("SELECT job_id, seq, label, state, at FROM job_steps WHERE job_id=? ORDER BY seq", jobId) { JobStep(it.text(0), it.long(1).toInt(), it.text(2), it.text(3), it.long(4)) }

    fun setStepState(jobId: String, seq: Int, state: String) = sql.exec("UPDATE job_steps SET state=? WHERE job_id=? AND seq=?", state, jobId, seq)

    /** Failures so far (each `failed` step), the backoff counter. */
    fun failures(jobId: String): List<JobStep> = steps(jobId).filter { it.state == "failed" }

    /** The job a background agent run belongs to (its `run` step), or null. */
    fun jobOfRun(agentId: String): String? =
        sql.query("SELECT job_id FROM job_steps WHERE state='run' AND label=? ORDER BY at DESC LIMIT 1", agentId) { it.text(0) }.firstOrNull()

    /** The newest run's agent id of a job, or null. */
    fun runOf(jobId: String): String? = steps(jobId).lastOrNull { it.state == "run" }?.label

    /** Automatic starts since [since] (the runaway guard counts these). */
    fun startsSince(since: Long): Int = sql.query("SELECT count(*) FROM job_steps WHERE state='run' AND at>=?", since) { it.long(0).toInt() }.firstOrNull() ?: 0

    fun delete(id: String) = sql.tx { sql.exec("DELETE FROM job_steps WHERE job_id=?", id); sql.exec("DELETE FROM jobs WHERE id=?", id) }
}

/** P1-TM-05: what a failure looks like to a person, and when it is tried again. Pure. */
object JobFailure {
    /** Backoff after the 1st, 2nd and 3rd failure; after that the job stays failed until someone retries it. */
    val BACKOFF_MIN = listOf(1L, 5L, 15L)

    /** When to try again after failure number [n] (1-based) at [now], or null when it's out of tries. */
    fun retryAt(n: Int, now: Long): Long? = BACKOFF_MIN.getOrNull(n - 1)?.let { now + it * 60_000 }

    /** A raw error in plain words. Network and service errors get a fixed sentence; others their first line, clipped. */
    fun plain(error: String?): String {
        val e = error?.trim().orEmpty()
        val l = e.lowercase(Locale.ROOT)
        return when {
            e.isEmpty() -> "It stopped without saying why"
            Regex("unknownhost|unable to resolve|no address associated|network is unreachable|no internet|offline|enetunreach").containsMatchIn(l) ->
                "Couldn't reach the internet"
            Regex("timed? ?out|timeout|sockettimeout").containsMatchIn(l) -> "The connection timed out"
            Regex("connection (refused|reset)|econnreset|econnrefused|broken pipe|\\beof\\b").containsMatchIn(l) -> "The connection dropped"
            Regex("\\b429\\b|rate.?limit|too many requests|quota").containsMatchIn(l) -> "The service is busy right now"
            Regex("\\b50[0234]\\b|bad gateway|service unavailable|gateway time").containsMatchIn(l) -> "The service had a problem"
            Regex("\\b40[13]\\b|unauthori[sz]ed|forbidden|invalid api key|expired token").containsMatchIn(l) -> "A sign-in or key stopped working"
            Regex("app closed|process (died|was killed)|restarted").containsMatchIn(l) -> "The app closed while this ran"
            else -> e.lineSequence().first().let { if (it.length > 140) it.take(139).trimEnd() + "\u2026" else it }
        }
    }
}
