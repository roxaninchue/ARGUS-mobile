package com.blaze.assistant.team

/**
 * P1-TM-05: the rules of a Job (05 §2.3 / §2.7; port of A-team jobs.js QUEUE + manager.js jobAllowed / risky). Pure.
 *
 * Job.status stays todo/done (the API's view); Job.run is the run state. A-team's extra state "blocked" (the bot
 * refused to close it) is FAILED with a blocked step, which is never retried by itself. The failure log, attempts and
 * Bot HQ create_task/update_task progress are [JobStep]s: progress steps have states todo/doing/done, failures
 * [STEP_ERROR] / [STEP_BLOCKED].
 */
object Jobs {
    const val TODO = "todo"; const val DONE = "done"
    const val STEP_ERROR = "error"; const val STEP_BLOCKED = "blocked"
    val TASK_STATES = setOf("todo", "doing", "done")
    val SOURCES = setOf("user", "manager", "routine", "bot", "mcp", "suggestion")
    /** Sources that start without the user (A-team t.pro): they wait for an explicit yes before anything runs. */
    val PROACTIVE = setOf("routine", "suggestion")

    /** Runaway guard: at most 12 automatic starts per 10 minutes (A-team QUEUE.LIMIT / WINDOW). */
    const val LIMIT = 12
    const val WINDOW_MS = 10 * 60_000L
    /** Network / gateway failures retry by themselves after 1, 5, then 15 minutes (A-team v2.16d). */
    val BACKOFF_MIN = listOf(1, 5, 15)
    val TRANSIENT = Regex("network|timed? ?out|timeout|cert|trust anchor|ssl|tls|handshake|failed to fetch|fetch failed|gateway|isn't answering|unreachable|offline|ECONN|EAI_AGAIN|socket|connection|\\b5\\d\\d\\b|429|rate limit|interrupted|internet|secure connection|portal|unable to resolve host", RegexOption.IGNORE_CASE)

    /** Things that need the user's explicit yes, whoever starts them (A-team manager.js RISKY). */
    private val RISKY = linkedMapOf(
        "money" to Regex("\\b(buy|purchase|pay|payment|order|checkout|subscribe|subscription|donate|transfer|send money|book (a|the)? ?(flight|hotel|ticket|table|room)|top ?up|withdraw|invest|bet)\\b", RegexOption.IGNORE_CASE),
        "send" to Regex("\\b(send|email|e-mail|text|sms|whatsapp|message|dm|tweet|post|reply to|forward|publish|invite)\\b", RegexOption.IGNORE_CASE),
        "delete" to Regex("\\b(delete|remove|erase|wipe|drop|cancel|unsubscribe|uninstall|rm -rf|destroy)\\b", RegexOption.IGNORE_CASE),
    )
    fun risky(text: String): List<String> = RISKY.filterValues { it.containsMatchIn(text) }.keys.toList()

    fun isOpen(j: Job) = j.status != DONE && j.run != JobRun.DONE && j.run != JobRun.CANCELLED
    fun isProactive(j: Job) = j.source in PROACTIVE

    fun priorityRank(p: String) = when (p.lowercase()) { "high", "urgent" -> 0; "low" -> 2; else -> 1 }
    fun priority(p: String?) = when { p == null -> "normal"; Regex("high|urgent|asap", RegexOption.IGNORE_CASE).containsMatchIn(p) -> "high"; Regex("low", RegexOption.IGNORE_CASE).containsMatchIn(p) -> "low"; else -> "normal" }

    fun normTitle(t: String) = t.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    /** The open jobs this one waits on. */
    fun blockers(j: Job, all: List<Job>): List<Job> = j.deps.mapNotNull { d -> all.firstOrNull { it.id == d } }.filter { isOpen(it) }

    data class Gate(val ok: Boolean, val why: String = "")

    /** Proactive jobs never act unconfirmed (A-team MANAGER.jobAllowed, 05 §2.3). */
    fun allowed(j: Job): Gate {
        if (j.confirmed) return Gate(true)
        val r = risky(j.title + " " + j.brief + " " + j.notes)
        return Gate(false, if (r.isNotEmpty()) "needs your OK (${r.joinToString(", ")})" else "not confirmed by you yet")
    }

    /** Can this job start now (its bot aside)? */
    fun ready(j: Job, all: List<Job>, botIds: Set<String>, now: Long): Boolean =
        j.run == JobRun.QUEUED && isOpen(j) && j.botId != null && j.botId in botIds && blockers(j, all).isEmpty() &&
            allowed(j).ok && (j.retryAt == null || j.retryAt <= now)

    /**
     * The next job to start (A-team QUEUE.next): ready, its bot not already running a job and not busy with a user
     * message ([busyBots]: user messages go first), high priority first, then the longest queued (u = queued-at).
     */
    fun next(all: List<Job>, botIds: Set<String>, busyBots: Set<String>, now: Long): Job? {
        val running = all.filter { it.run == JobRun.RUNNING }.mapNotNull { it.botId }.toSet()
        return all.filter { ready(it, all, botIds, now) && it.botId !in running && it.botId !in busyBots }
            .minWithOrNull(compareBy<Job>({ priorityRank(it.priority) }, { it.u }, { it.createdAt }))
    }

    fun attempts(steps: List<JobStep>) = steps.count { it.state == STEP_ERROR }
    fun lastError(steps: List<JobStep>) = steps.lastOrNull { it.state == STEP_ERROR || it.state == STEP_BLOCKED }?.label
    fun blocked(j: Job, steps: List<JobStep>) = j.run == JobRun.FAILED && steps.lastOrNull { it.state == STEP_ERROR || it.state == STEP_BLOCKED }?.state == STEP_BLOCKED

    /** Minutes to wait before the automatic retry after [attempts] failures, or null (not transient / too many). */
    fun backoff(err: String, attempts: Int, stopped: Boolean): Int? =
        if (!stopped && TRANSIENT.containsMatchIn(err) && attempts in 1..BACKOFF_MIN.size) BACKOFF_MIN[attempts - 1] else null

    /** A failure in plain words (never a stack trace, a URL or a provider name). */
    fun plain(err0: String): String {
        val err = err0.trim().ifEmpty { "Unknown error" }
        val low = err.lowercase()
        return when {
            low.startsWith("interrupted") -> "Interrupted: the app closed while it ran"
            low == "stopped" -> "Stopped"
            Regex("unable to resolve host|failed to connect|network|offline|internet|eai_again|econn|unreachable").containsMatchIn(low) -> "No internet connection"
            Regex("timed? ?out|timeout").containsMatchIn(low) -> "It took too long to answer"
            Regex("cert|trust anchor|ssl|tls|handshake|secure connection|portal").containsMatchIn(low) -> "The secure connection failed (a Wi-Fi sign-in page?)"
            Regex("429|rate limit|quota").containsMatchIn(low) -> "Too busy right now"
            Regex("\\b5\\d\\d\\b|gateway|isn't answering|overloaded|unavailable").containsMatchIn(low) -> "The ARGUS service isn't answering"
            err.length > 120 || err.contains('{') || err.contains("Exception") || err.contains("http", true) -> "Something went wrong"
            else -> err
        }
    }

    // ------------------------------------------------------------ texts

    /** The job as a task message in the bot's own chat (A-team QUEUE.brief, v2.7 wording so it is never re-queued). */
    fun brief(j: Job, all: List<Job>, me: String, today: java.time.LocalDate): String {
        val l = ArrayList<String>()
        l += "You are assigned job #${ref(j)}: ${j.title}"
        if (j.due != null) l += "Due: " + DueWords.label(j.due, today)
        if (j.priority == "high") l += "Priority: high"
        val details = listOf(j.brief, j.notes).map { it.trim() }.filter { it.isNotEmpty() && it != j.title }.distinct().joinToString("\n")
        if (details.isNotEmpty()) l += "Details: $details"
        val done = j.deps.mapNotNull { d -> all.firstOrNull { it.id == d } }
        if (done.isNotEmpty()) l += "It waited on: " + done.joinToString("; ") { it.title + (it.result?.let { r -> " (${clip(r, 120)})" } ?: "") }
        val who = me.ifBlank { "the user" }
        l += ""
        l += "Do it now and deliver the result to $who in this chat (your reply is what they read in the notification). This job already exists: " +
            "do not create a job for it (no add_job for this). When it is finished, call done_job with the title \"${j.title}\". If you truly need something from $who first, ask and stop."
        return l.joinToString("\n")
    }

    fun ref(j: Job) = j.id.replace(Regex("[^A-Za-z0-9]"), "").takeLast(6).ifEmpty { "1" }

    /** The one status line a job shows (A-team t.upd, derived here instead of stored). */
    fun line(j: Job, steps: List<JobStep>, all: List<Job>, now: Long): String = when {
        !isOpen(j) && j.run == JobRun.CANCELLED -> "Cancelled"
        !isOpen(j) -> j.result?.let { clip(it, 90) } ?: "Done"
        j.run == JobRun.RUNNING -> steps.lastOrNull { it.state == "doing" }?.label?.let { "Working: $it" } ?: "Working on it"
        j.run == JobRun.WAITING -> j.result?.let { clip(it, 90) } ?: "Waiting for you"
        j.run == JobRun.FAILED && blocked(j, steps) -> "Blocked: " + (lastError(steps) ?: "not finished")
        j.run == JobRun.FAILED -> {
            val n = attempts(steps)
            val err = clip(lastError(steps) ?: "failed", 70)
            if (j.retryAt != null && j.retryAt > now) "$err · retrying in ${((j.retryAt - now + 59_999) / 60_000)} min (try ${n + 1})" else "$err · failed $n\u00d7"
        }
        !allowed(j).ok -> "Waiting for your OK: " + allowed(j).why
        blockers(j, all).isNotEmpty() -> "Waits on " + blockers(j, all).joinToString(", ") { it.title }
        j.botId == null -> "Unassigned"
        else -> "Queued"
    }

    /** Short words for a list chip: running · queued · waiting · blocked · failed · done · to do. */
    fun state(j: Job, steps: List<JobStep>): String = when {
        j.status == DONE || j.run == JobRun.DONE -> "done"
        j.run == JobRun.CANCELLED -> "cancelled"
        j.run == JobRun.RUNNING -> "running"
        j.run == JobRun.FAILED -> if (blocked(j, steps)) "blocked" else "failed"
        j.run == JobRun.WAITING -> "waiting"
        j.run == JobRun.QUEUED -> "queued"
        else -> "to do"
    }

    fun clip(s: String, n: Int = 160): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        if (t.length <= n) return t
        return t.take(n - 1).replace(Regex("\\s+\\S*$"), "") + "\u2026"
    }

    private val META = Regex("^(?:let(?:'s|\u2019s| me| us)\\b|i(?:'ll|\u2019ll| will| am going to|'m going to|\u2019m going to)\\s+(?:now\\s+)?(?:add|create|run|check|mark|use|call|start|look|do|make|set|update|save|attach|greet)\\b|(?:now|next|first),?\\s+(?:i|let)\\b|(?:ok(?:ay)?|sure|done|got it|all set|alright|noted|great)[.!]*$|marked\\b.*\\bdone[.!]*$|job (?:added|created|done|complete)[.!]*$|(?:i(?:'ve|\u2019ve| have)\\s+)?(?:added|created|marked|completed) (?:the|this|that) job\\b)", RegexOption.IGNORE_CASE)

    /**
     * One or two sentences of what the bot said (A-team NOTIF.summary): markdown, code, links and meta-talk
     * ("Let's add the job.", "Done.") stripped; never the full reply; ≤ 160 characters.
     */
    fun summary(text: String): String {
        val t = text.replace(Regex("```[\\s\\S]*?(```|$)"), " ").replace(Regex("`([^`]*)`"), "$1").replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^\\s*(#{1,6}|>|[-*+]|\\d+[.)])\\s+"), "").replace(Regex("[*_~]{1,2}"), "").replace(Regex("\\s+"), " ").trim()
        val sents = Regex("[^.!?]+[.!?]+(\\s|$)|[^.!?]+$").findAll(t).map { it.value.trim() }.filter { it.isNotEmpty() && !META.containsMatchIn(it) }.toList()
        if (sents.isEmpty()) return ""
        var out = sents[0]
        if (sents.size > 1 && out.length < 70) out += " " + sents[1]
        return clip(out)
    }
}
