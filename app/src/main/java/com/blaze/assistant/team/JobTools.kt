package com.blaze.assistant.team

import org.json.JSONArray
import org.json.JSONObject

/**
 * P1-TM-05: the "jobs" tool group (A-team add_job / done_job / retry_job, the Manager's agent_delegate, plus Bot HQ
 * create_task / update_task as job steps). Schemas in the repo's OpenAI-function JSON shape (like Specialists.tool());
 * [exec] is pure over [JobQueue] / [TeamStore] and returns the text the model reads.
 */
class JobTools(private val jobs: JobQueue, private val team: TeamStore, private val threads: BotThreads? = null) {

    data class Out(val ok: Boolean, val text: String, val jobId: String? = null)

    /** Who is calling: the bot, and the job it is running right now (null in a normal chat turn). */
    data class Caller(val bot: Bot, val jobId: String? = null)

    fun exec(name: String, a: JSONObject, c: Caller): Out = when (name) {
        "add_job" -> addJob(a, c)
        "done_job" -> doneJob(a, c)
        "update_job" -> updateJob(a, c)
        "retry_job" -> retryJob(a, c)
        "agent_delegate" -> delegate(a, c)
        "create_task" -> createTask(a, c)
        "update_task" -> updateTask(a, c)
        else -> Out(false, "[$name] unknown tool")
    }

    private fun s(a: JSONObject, vararg k: String) = k.asSequence().map { a.optString(it).trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun addJob(a: JSONObject, c: Caller): Out {
        val title = s(a, "title", "task", "text")
        if (title.isEmpty()) return Out(false, "[add_job] a title is required")
        val who = s(a, "agent", "bot")
        val bot = if (who.isEmpty()) c.bot else team.find(who) ?: return Out(false, "[add_job] no bot matches \"$who\" (agents_list shows the roster)")
        if (c.jobId != null && jobs.get(c.jobId)?.let { Jobs.normTitle(it.title) == Jobs.normTitle(title) } == true)
            return Out(false, "[add_job] that is the job you are working on: do it now, then call done_job")
        val deps = (a.optJSONArray("waits_on") ?: a.optJSONArray("deps"))?.let { arr -> (0 until arr.length()).mapNotNull { jobs.find(arr.optString(it))?.id } } ?: emptyList()
        val r = jobs.add(title, bot.id, s(a, "due").ifEmpty { null }, s(a, "priority").ifEmpty { null }, deps, brief = title, notes = s(a, "notes", "details"),
            source = if (Bots.isManager(c.bot)) "manager" else if (bot.id == c.bot.id) "user" else "bot", parentJob = c.jobId)
        val j = r.job
        return if (r.duplicate) Out(true, "[add_job] \"${j.title}\" is ${r.note} (job #${Jobs.ref(j)}); no second job made", j.id)
        else Out(true, "[add_job] queued #${Jobs.ref(j)} \"${j.title}\" for ${bot.name}${j.due?.let { " · due $it" } ?: ""}", j.id)
    }

    private fun doneJob(a: JSONObject, c: Caller): Out {
        val q = s(a, "job", "title", "id")
        val j = (if (q.isEmpty()) c.jobId?.let { jobs.get(it) } else null) ?: jobs.find(q, c.bot.id.takeUnless { Bots.isManager(c.bot) })
            ?: return Out(false, "[done_job] no open job matches \"$q\"")
        val result = s(a, "result", "summary")
        jobs.done(j.id, result.ifEmpty { null })
        return Out(true, "[done_job] marked \"${j.title}\" done", j.id)
    }

    private fun updateJob(a: JSONObject, c: Caller): Out {
        val q = s(a, "job", "title", "id")
        val j = jobs.find(q, c.bot.id.takeUnless { Bots.isManager(c.bot) }) ?: return Out(false, "[update_job] no open job matches \"$q\"")
        val who = s(a, "agent", "bot")
        val to = if (who.isEmpty()) null else team.find(who) ?: return Out(false, "[update_job] no bot matches \"$who\"")
        val out = jobs.update(j.id, title = s(a, "new_title").ifEmpty { null }, due = s(a, "due").ifEmpty { null }, priority = s(a, "priority").ifEmpty { null },
            notes = s(a, "notes").ifEmpty { null }, botId = to?.id)!!
        return Out(true, "[update_job] #${Jobs.ref(out)} \"${out.title}\" · ${out.due ?: "no date"} · ${out.priority}${to?.let { " → ${it.name}" } ?: ""}", out.id)
    }

    private fun retryJob(a: JSONObject, c: Caller): Out {
        val q = s(a, "job", "title", "id")
        val j = jobs.find(q, c.bot.id.takeUnless { Bots.isManager(c.bot) }) ?: return Out(false, "[retry_job] no open job matches \"$q\"" + (if (Bots.isManager(c.bot)) "" else " among your jobs"))
        if (j.run == JobRun.RUNNING) return Out(false, "[retry_job] \"${j.title}\" is running right now")
        if (j.id == c.jobId) return Out(false, "[retry_job] \"${j.title}\" is the job you are working on: keep going in this reply")
        jobs.retry(j.id, c.bot.name)
        val n = Jobs.attempts(jobs.steps(j.id))
        return Out(true, "[retry_job] \"${j.title}\" is queued again (try ${n + 1}) for ${j.botId?.let { team.get(it)?.name } ?: "nobody yet"}.", j.id)
    }

    /** Manager only in P1 (bot-to-bot hand-offs are P5 send_to_bot). Posts a HandoffCard ("Atlas is on it") in ARGUS's thread. */
    private fun delegate(a: JSONObject, c: Caller): Out {
        if (!Bots.isManager(c.bot)) return Out(false, "[agent_delegate] only the Manager can use this tool")
        val who = s(a, "agent", "bot")
        val bot = team.find(who) ?: return Out(false, "[agent_delegate] no bot matches \"$who\" (agents_list shows the roster)")
        if (Bots.isManager(bot)) return Out(false, "[agent_delegate] that's you: do it yourself")
        val task = s(a, "task", "text")
        if (task.isEmpty()) return Out(false, "[agent_delegate] the task is empty")
        val parent = c.jobId?.let { jobs.get(it) }
        val r = jobs.add(task, bot.id, s(a, "due").ifEmpty { null }, s(a, "priority").ifEmpty { null }, brief = task,
            notes = "Delegated by ${c.bot.name} (the Manager).", source = "manager", parentJob = c.jobId,
            confirmed = parent?.confirmed ?: true)
        val j = r.job
        threads?.card(Bots.MANAGER_ID, "handoff", mapOf("title" to "${bot.name} is on it", "to" to bot.id, "job" to j.id, "text" to j.title), viewing = true)
        return Out(true, "[agent_delegate] sent to ${bot.name} as job #${Jobs.ref(j)} (\"${j.title}\")${if (r.duplicate) ", " + r.note else ""}; it starts in their chat. Check it with agent_status.", j.id)
    }

    private fun createTask(a: JSONObject, c: Caller): Out {
        val jid = c.jobId ?: return Out(false, "[create_task] steps belong to a job: you are not running one")
        val label = s(a, "title", "task", "text")
        if (label.isEmpty()) return Out(false, "[create_task] a title is required")
        val st = jobs.addStep(jid, label, s(a, "state").takeIf { it in Jobs.TASK_STATES } ?: "todo")
        return Out(true, "[create_task] step ${st.seq}: ${st.label}", jid)
    }

    private fun updateTask(a: JSONObject, c: Caller): Out {
        val jid = c.jobId ?: return Out(false, "[update_task] steps belong to a job: you are not running one")
        val q = s(a, "id", "step", "title")
        val steps = jobs.steps(jid).filter { it.state in Jobs.TASK_STATES }
        val st = q.toIntOrNull()?.let { n -> steps.firstOrNull { it.seq == n } } ?: steps.firstOrNull { Jobs.normTitle(it.label) == Jobs.normTitle(q) }
            ?: steps.firstOrNull { q.isNotEmpty() && Jobs.normTitle(it.label).contains(Jobs.normTitle(q)) }
            ?: return Out(false, "[update_task] no step matches \"$q\"")
        val state = s(a, "state", "status").lowercase().let { if (it == "in_progress" || it == "running") "doing" else it }
        if (state !in Jobs.TASK_STATES) return Out(false, "[update_task] state must be todo, doing or done")
        jobs.setStep(jid, st.seq, state)
        return Out(true, "[update_task] step ${st.seq} is $state", jid)
    }

    companion object {
        val NAMES = listOf("add_job", "done_job", "update_job", "retry_job", "agent_delegate", "create_task", "update_task")
        /** agent_delegate is the Manager's (05 §2.1); the rest go to every bot. */
        val MANAGER_ONLY = setOf("agent_delegate")

        private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
        private fun fn(name: String, d: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
            JSONObject().put("name", name).put("description", d).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
        private val pri = JSONObject().put("type", "string").put("enum", JSONArray(listOf("low", "normal", "high")))

        fun schemas(manager: Boolean): List<JSONObject> = listOfNotNull(
            fn("add_job", "Put a job on the board for a bot (yourself by default). Same title as an open job = no second job (a failed one is retried).",
                JSONObject().put("title", str("What to do, short")).put("agent", str("Bot name (default: you)")).put("due", str("today, tomorrow, friday, in 3 days, YYYY-MM-DD…"))
                    .put("priority", pri).put("notes", str("Details it needs")).put("waits_on", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Job #s or titles it waits for")),
                listOf("title")),
            fn("done_job", "Mark a job done (the one you are working on by default) with a one-line result.",
                JSONObject().put("job", str("Job # or title")).put("result", str("One line: what was done")), emptyList()),
            fn("update_job", "Change a job: due date, priority, notes, title or the bot it belongs to.",
                JSONObject().put("job", str("Job # or title")).put("due", str("New due")).put("priority", pri).put("notes", str("New notes")).put("new_title", str("New title")).put("agent", str("Reassign to this bot")),
                listOf("job")),
            fn("retry_job", "Pick a failed or blocked job up again: puts it back in the queue (the Manager can retry any bot's job, other bots their own).",
                JSONObject().put("job", str("The job # or title")), listOf("job")),
            if (manager) fn("agent_delegate", "Send a task to a bot. It starts as a job in that bot's chat; returns the job id (check it with agent_status).",
                JSONObject().put("agent", str("Bot name or id")).put("task", str("What to do, with the details it needs")).put("due", str("Optional due date, e.g. friday")).put("priority", pri),
                listOf("agent", "task")) else null,
            fn("create_task", "Add a progress step to the job you are running (shown on its card).", JSONObject().put("title", str("The step")), listOf("title")),
            fn("update_task", "Move a step of your job to todo, doing or done.", JSONObject().put("id", str("Step number or title")).put("state", JSONObject().put("type", "string").put("enum", JSONArray(listOf("todo", "doing", "done")))), listOf("id", "state")),
        )
    }
}
