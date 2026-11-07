package com.blazeinchue.argus.agent


import com.blazeinchue.argus.BrandName
import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Background agents (v1.14, ported from desktop internal/agents + bgagents.go).
 *
 * The main chat hands long work to an agent and keeps chatting. Each agent runs in its own
 * hidden chat (a normal Session, so it gets the same tools, modes and repairs), takes a slot
 * (1 at a time, 2 on phones with 6 GB+ RAM), and waits in a queue otherwise. Its card in the
 * chat shows live progress with Steer and Stop. When it finishes, its result wakes the chat that
 * started it: the main Argus reads it and tells the user. Agents are saved in filesDir and pick up
 * where they were after Argus restarts.
 */
object BgAgents {
    data class Bg(
        val id: String, val title: String, val task: String, val parent: String, val chat: String,
        val plan: Boolean = false, val status: String = QUEUED,
        val created: Long = System.currentTimeMillis(), val started: Long = 0, val ended: Long = 0,
        val steps: Int = 0, val last: String = "", val result: String = "", val waiting: Boolean = false,
        val resume: Boolean = false, val followUp: String = "",
        /** v0.26.2: the specialist this agent runs as ("" = a general background agent). */
        val specialist: String = "",
        /** 0.52.0: its newest steps (up to [LOG_MAX]), for its work pill's expanded list. */
        val log: List<String> = emptyList(),
    ) {
        val live get() = status == QUEUED || status == RUNNING
        val seconds get() = (((if (ended > 0) ended else System.currentTimeMillis()) - (if (started > 0) started else created)) / 1000).coerceAtLeast(0)
    }

    const val QUEUED = "queued"; const val RUNNING = "running"; const val DONE = "done"
    const val FAILED = "failed"; const val CANCELLED = "cancelled"
    const val CARD = "agent:"
    const val LOG_MAX = 40
    const val RESULT_PREFIX = "[Background agent result"

    /** Marks a coroutine as belonging to an agent, so its confirm and question cards say which. */
    class Tag(val id: String) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Tag> }

    val toolNames = setOf("start_agent", "spawn_agent", "list_agents", "check_agent", "message_agent", "cancel_agent", "agent_progress", "post_update")
    /** Agents don't start or steer other agents. */
    val leadOnly = setOf("start_agent", "spawn_agent", "message_agent", "cancel_agent", "delegate", "create_specialist", "orchestrate", "post_update")

    private lateinit var app: Context
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.blazeinchue.argus.device.CrashLog.guard)
    // 0.52.1 crash audit: touched from Main (runOne/pump) and from tool threads (cancel via the lead's tool call).
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    /** 0.52.1: the file is written off the main thread, one write at a time, newest snapshot wins. */
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val pending = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val _all = MutableStateFlow<List<Bg>>(emptyList())
    val all: StateFlow<List<Bg>> = _all

    fun init(ctx: Context) {
        app = ctx.applicationContext
        file = File(app.filesDir, "bg_agents.json")
        // 0.52.1 crash audit: the file can be ~1 MB (40 results); read it off the main thread (init runs in Application.onCreate).
        scope.launch {
            val list = withContext(io) { load() }
            // Argus was closed mid-run: those go back in the queue and continue where they were.
            synchronized(this@BgAgents) {
                val restored = list.map { if (it.status == RUNNING) it.copy(status = QUEUED, resume = true) else it }
                val fresh = _all.value.filter { n -> restored.none { it.id == n.id } }   // started before the read finished
                _all.value = (restored + fresh).sortedBy { it.created }.takeLast(40)
            }
            save()
            delay(4000); pump()
        }
    }

    private fun load(): List<Bg> = try {
        if (!file.exists()) emptyList() else {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Bg(it.getString("id"), it.optString("title"), it.optString("task"), it.optString("parent"), it.optString("chat"),
                    it.optBoolean("plan"), it.optString("status", DONE), it.optLong("created"), it.optLong("started"), it.optLong("ended"),
                    it.optInt("steps"), it.optString("last"), it.optString("result"), false, it.optBoolean("resume"), it.optString("follow_up"),
                    it.optString("specialist"), it.optJSONArray("log")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } } ?: emptyList())
            }
        }
    } catch (e: Exception) { android.util.Log.w("BgAgents", "load: ${e.message}"); emptyList() }

    private fun ok() = ::file.isInitialized

    @Synchronized private fun save() {
        if (!ok()) return
        val text = try {
            JSONArray(_all.value.map {
                JSONObject().put("id", it.id).put("title", it.title).put("task", it.task).put("parent", it.parent).put("chat", it.chat)
                    .put("plan", it.plan).put("status", it.status).put("created", it.created).put("started", it.started).put("ended", it.ended)
                    .put("steps", it.steps).put("last", it.last).put("result", it.result.take(20000)).put("resume", it.resume).put("follow_up", it.followUp)
                    .apply { if (it.specialist.isNotBlank()) put("specialist", it.specialist) }
                    .put("log", JSONArray(it.log.takeLast(LOG_MAX)))
            }).toString()
        } catch (e: Exception) { android.util.Log.w("BgAgents", "save: ${e.message}"); return }
        // 0.52.1 crash audit: never write on the caller's thread (runOne runs on Main). Only the newest snapshot is written.
        if (pending.getAndSet(text) == null) scope.launch(io) {
            val t = pending.getAndSet(null) ?: return@launch
            try {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(t)
                if (!tmp.renameTo(file)) { file.writeText(t); tmp.delete() }
            } catch (e: Exception) { android.util.Log.w("BgAgents", "write: ${e.message}") }
        }
    }

    @Synchronized private fun update(id: String, persist: Boolean = true, f: (Bg) -> Bg) {
        _all.value = _all.value.map { if (it.id == id) f(it) else it }
        if (persist) save()
    }

    /** Screenshot tests (ShotsTest) only: show these agents without saving anything. */
    fun preview(list: List<Bg>) { _all.value = list }

    fun get(id: String): Bg? = _all.value.firstOrNull { it.id.equals(id.trim(), true) }

    /**
     * How many agents may run at once. 0.52.0: 4 on 6 GB+ phones, 3 on 4 GB+, 2 below, 1 on phones Android flags as
     * low-RAM (orchestra/AgentBudget); was 1, or 2 on 6 GB+.
     */
    fun cap(): Int {
        val mi = ActivityManager.MemoryInfo()
        return try {
            val am = app.getSystemService(ActivityManager::class.java)
            am.getMemoryInfo(mi)
            // totalMem reports a little under the marketing size (a "6 GB" phone shows ~5.5 GB). Under memory pressure, one fewer.
            val c = com.blazeinchue.argus.orchestra.AgentBudget.cap(mi.totalMem, am.isLowRamDevice)
            if (mi.lowMemory) (c - 1).coerceAtLeast(1) else c
        } catch (_: Exception) { 1 }
    }

    /** The specialist an agent runs as (null = a general agent). */
    fun spec(bg: Bg): Specialists.Spec? = bg.specialist.takeIf { it.isNotBlank() }?.let { try { Specialists.byId(it) } catch (_: Throwable) { null } }

    /** v0.26.2: what kind of slot it needs (Dispatch): network specialists run up to 3 at once, phone ones take turns. */
    fun kind(bg: Bg): Dispatch.Kind = if (bg.specialist.isBlank()) Dispatch.Kind.GENERAL else Dispatch.kind(spec(bg)?.groups ?: setOf("web"))

    /** The card's role label: "AGENT", or the specialist's name ("SHOPPER"). */
    fun cardRole(bg: Bg): String = if (bg.specialist.isBlank()) "AGENT" else (spec(bg)?.name ?: bg.specialist).uppercase()

    /** Queue a new agent. Returns it; it starts as soon as a slot is free. [specialist] = run it as that specialist. */
    fun start(task: String, title: String, parent: String, specialist: String = ""): Bg {
        val id = Processes.nextId("agent")
        val bg = Bg(id, title.ifBlank { task.lineSequence().first().take(40) }.take(60), task, parent,
            "bg-" + java.util.UUID.randomUUID().toString().take(10), plan = Modes.plan, specialist = specialist)
        synchronized(this) { _all.value = (_all.value + bg).takeLast(40) }
        save()
        scope.launch { pump() }
        return bg
    }

    @Synchronized private fun claimNext(): Bg? {
        val running = _all.value.filter { it.status == RUNNING }.map { kind(it) }
        val queued = _all.value.filter { it.status == QUEUED }
        val i = Dispatch.pick(queued.map { kind(it) }, running, cap())
        if (i < 0) return null
        val next = queued[i]
        _all.value = _all.value.map { if (it.id == next.id) it.copy(status = RUNNING, started = if (it.started > 0) it.started else System.currentTimeMillis()) else it }
        save()
        return get(next.id)
    }

    private fun pump() {
        while (true) {
            val bg = claimNext() ?: return
            jobs[bg.id] = scope.launch { runOne(bg) }
        }
    }

    private fun prompt(bg: Bg) = if (bg.specialist.isNotBlank()) "[Brief from the main ${BrandName.NAME} for you, ${spec(bg)?.name ?: bg.specialist} ${bg.id}]\n${bg.task}"
        else "[Background agent ${bg.id} \u00ab${bg.title}\u00bb. You work on your own in the background while the user keeps chatting with the main ${BrandName.NAME}; " +
        "nobody watches this chat. Don't ask questions unless you're truly blocked (ask_user shows the user a card). Use background tools; touch the phone screen only if the task can't be done any other way. " +
        "When finished, reply with the result only: what you did or found, short but complete, with any links or file names.]\n${bg.task}"

    private suspend fun runOne(start: Bg) {
        if (start.specialist == com.blazeinchue.argus.orchestra.OrchestraRuns.SPECIALIST) { runBlueprint(start); return }
        var bg = start
        val s = Agent.agentSession(bg)
        val text = when {
            bg.followUp.isNotBlank() -> "[Follow-up from the user]\n${bg.followUp}"
            bg.resume -> "[${BrandName.NAME} restarted while you were working. Continue the task from where you left off; check what's already done first, don't redo it.]"
            else -> prompt(bg)
        }
        update(bg.id) { it.copy(resume = false, waiting = false, followUp = "") }
        // v0.26.2: a custom specialist without a playbook gets one written (validated) before its first run.
        spec(bg)?.let { sp -> if (sp.custom && sp.playbook.isBlank()) { update(bg.id, persist = false) { it.copy(last = "Writing its field playbook") }; Agent.ensurePlaybook(sp) } }
        // 0.52.1 (ProgressGate): milestones this agent has already reported to the main Argus.
        var fired = ProgressGate.reached(bg.steps)
        suspend fun runAndWait(t: String) {
            withContext(Dispatchers.Main) { s.send(t) }
            while (s.job?.isActive == true) {
                val stepMsgs = s.msgs.filter { it.role == Role.STEP }
                val steps = stepMsgs.size
                val last = stepMsgs.lastOrNull()?.text.orEmpty()
                update(bg.id, persist = false) { it.copy(steps = steps, last = last.take(120), log = stepMsgs.takeLast(LOG_MAX).map { m -> m.text.take(160) }) }
                get(bg.id)?.let { now -> if (now.status == RUNNING && ProgressGate.due(steps, now.seconds, fired)) { fired = ProgressGate.reached(steps); Agent.agentMilestone(now) } }
                delay(1000)
            }
        }
        runAndWait(text)
        // v0.26.2 self-check: one review of the draft against the specialist's playbook; redo once if it fails.
        spec(bg)?.let { sp ->
            val draft = s.msgs.lastOrNull { it.role == Role.ARGUS || it.role == Role.ERROR }
            if (get(bg.id)?.status == RUNNING && draft != null && draft.role == Role.ARGUS) {
                val pb = Playbooks.forSpec(Specialists.byId(sp.id) ?: sp)
                if (pb.isNotBlank()) {
                    update(bg.id, persist = false) { it.copy(last = "Checking its work against the ${sp.name} playbook") }
                    val r = Agent.reviewDraft(sp, pb, bg.task, draft.text)
                    // v0.45.2: a review that didn't finish is said plainly on the card, not counted as a pass.
                    if (!r.checked) update(bg.id, persist = false) { it.copy(last = VerifyPolicy.NOT_REVIEWED) }
                    if (r.checked && !r.pass && get(bg.id)?.status == RUNNING) {
                        update(bg.id, persist = false) { it.copy(last = "Fixing: ${r.fixes.firstOrNull().orEmpty().take(80)}") }
                        runAndWait(Playbooks.redoPrompt(r.fixes))
                    }
                }
            }
        }
        Dispatch.browser.release(bg.id); Dispatch.phone.release(bg.id)
        bg = get(bg.id) ?: return
        jobs.remove(bg.id)
        if (bg.status == CANCELLED) { save(); pump(); return }
        val lastMsg = s.msgs.lastOrNull { it.role == Role.ARGUS || it.role == Role.ERROR }
        val failed = lastMsg == null || lastMsg.role == Role.ERROR
        val result = lastMsg?.text.orEmpty().ifBlank { "(no result)" }
        update(bg.id) { it.copy(status = if (failed) FAILED else DONE, ended = System.currentTimeMillis(), result = result,
            steps = s.msgs.count { m -> m.role == Role.STEP }, waiting = false) }
        get(bg.id)?.let { Agent.agentFinished(it) }
        pump()
    }

    /** 0.52.0: a blueprint run (OrchestraRuns): every node event is a step on its pill; the result is its reply. */
    private suspend fun runBlueprint(start: Bg) {
        update(start.id) { it.copy(resume = false, waiting = false, followUp = "") }
        val (ok, result) = try {
            com.blazeinchue.argus.orchestra.OrchestraRuns.run(start) { line, n, log -> update(start.id, persist = false) { it.copy(steps = n, last = line.take(120), log = log) } }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
            false to ("The blueprint run stopped: " + (e.message ?: e.javaClass.simpleName))
        }
        val bg = get(start.id) ?: return
        jobs.remove(bg.id)
        if (bg.status == CANCELLED) { save(); pump(); return }
        update(bg.id) { it.copy(status = if (ok) DONE else FAILED, ended = System.currentTimeMillis(), result = result, waiting = false) }
        com.blazeinchue.argus.orchestra.OrchestraRuns.forget(bg.id)
        get(bg.id)?.let { Agent.agentFinished(it) }
        pump()
    }

    /** Add an instruction while it works, or a follow-up once it's done (it runs again). */
    fun steer(id: String, text: String): String {
        val bg = get(id) ?: return "No agent $id. Call list_agents."
        if (text.isBlank()) return "Say what to tell it."
        return when (bg.status) {
            RUNNING -> {
                if (bg.specialist == com.blazeinchue.argus.orchestra.OrchestraRuns.SPECIALIST && com.blazeinchue.argus.orchestra.OrchestraRuns.steer(bg.id, text))
                    "Told blueprint ${bg.id}: every part that starts from now on follows it."
                else { Agent.agentSession(bg).steer.add(text.trim()); "Told ${bg.id}: it picks this up at its next step." }
            }
            QUEUED -> { update(id) { it.copy(task = it.task + "\n\nAlso: " + text.trim()) }; "${bg.id} hasn't started yet; added that to its task." }
            else -> {
                // Follow-up: the agent's chat continues with the new instruction when a slot is free.
                update(id) { it.copy(status = QUEUED, ended = 0, result = "", resume = false, followUp = text.trim()) }
                scope.launch { pump() }
                "${bg.id} is on that follow-up."
            }
        }
    }

    fun cancel(id: String): String {
        val bg = get(id) ?: return "No agent $id."
        if (!bg.live) return "${bg.id} isn't running."
        update(id) { it.copy(status = CANCELLED, ended = System.currentTimeMillis(), waiting = false) }
        Dispatch.browser.release(bg.id); Dispatch.phone.release(bg.id)
        if (bg.status == RUNNING) Agent.stopAgent(bg)
        jobs.remove(id)?.cancel()
        scope.launch { pump() }
        return "Stopped ${bg.id}."
    }

    /** Forget a finished agent (its chat stays in the chat list). */
    @Synchronized fun discard(id: String) { _all.value = _all.value.filterNot { it.id == id && !it.live }; save() }

    fun setWaiting(id: String, w: Boolean) = update(id, persist = false) { it.copy(waiting = w) }

    /** For the main chat's prompt: when to hand work off, and what's running. */
    fun leadLine(): String {
        if (!Settings.bool("bg_agents", true)) return ""
        val live = _all.value.filter { it.live }
        return "\nYOU ARE THE CONVERSATION: the user must always be able to keep talking to you. Answer quick things yourself in one turn. " +
            "Anything that needs more than about two tool steps or a minute goes to a background agent and you reply at once in one short line: " +
            "spawn_agent (one task, optional specialist type), delegate (several specialists in parallel) or orchestrate (a big multi-part job: an app / APK, " +
            "deep research with a fact-checked document, curated media lists with artwork, long documents). Results come back to you on their own; " +
            "never wait or poll. While agents work, keep chatting; if the user asks how one is going, answer from list_agents / check_agent; " +
            "for what one has found so far agent_progress; to change what one does use message_agent, to stop it cancel_agent. " +
            "While they work you may post_update the user once per real milestone, in your own words from their actual results; never stock progress lines. When you call several tools in one reply, independent ones run at the same time." +
            (if (live.isNotEmpty()) " Working now: " + live.joinToString("; ") { "${it.id} ${it.title} (${it.status})" } + "." else "")
    }

    fun runningCount() = _all.value.count { it.status == RUNNING }

    /** What the main Argus reads when an agent finishes (desktop ResultMessage). */
    fun resultMessage(bg: Bg): String = if (bg.specialist.isNotBlank())
        Dispatch.resultMessage(bg.id, spec(bg)?.name ?: bg.specialist, bg.title, bg.status, bg.steps, bg.seconds, bg.result,
            _all.value.count { it.live && it.parent == bg.parent && it.id != bg.id })
    else buildString {
        append("$RESULT_PREFIX · ${bg.id} · ${bg.status} · ${bg.steps} step(s) · ${bg.seconds}s]\n")
        append("This is a platform message, not the user. Your background agent finished. Tell the user what it found or did, in your own words and briefly; don't paste it whole.\n")
        append("<agent_result id=\"${bg.id}\" title=\"${bg.title}\" status=\"${bg.status}\">\n")
        append(if (bg.status == DONE) bg.result.take(8000) else "The agent could not finish: " + bg.result.take(3000))
        append("\n</agent_result>")
        val others = _all.value.count { it.live && it.parent == bg.parent }
        if (others > 0) append("\n$others other agent(s) from this chat are still working. End your turn with at most one short progress line; summarise when the last one is in.")
    }

    fun describe(bg: Bg): String = "${bg.id} \u00ab${bg.title}\u00bb: ${bg.status}${if (bg.waiting) " (waiting for the user's tap)" else ""}, ${bg.steps} steps, ${bg.seconds}s" +
        (if (bg.last.isNotBlank() && bg.live) ". Now: ${bg.last}" else "")

    // ---------- tools ----------

    fun tools(): List<JSONObject> {
        fun fn(name: String, d: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", name).put("description", d)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))
        fun s(d: String) = JSONObject().put("type", "string").put("description", d)
        return listOf(
            fn("start_agent", "Hand long or slow work (research, a multi-step job, anything over ~1 minute) to a background agent and keep chatting with the user. " +
                "Give a complete, self-contained task: it doesn't see this chat. Its result comes back to you automatically when it's done; don't wait for it or poll. " +
                "One or two run at a time; more wait in a queue.",
                JSONObject().put("task", s("Everything the agent needs to know and do")).put("title", s("2-5 word name for its card")), listOf("task")),
            fn("spawn_agent", "Delegate a task to a background agent of a given specialist type and keep talking with the user (you stay responsive). " +
                "specialist: researcher, coder, writer, planner, shopper, booker, operator, inbox, designer, tutor (or empty for a general agent). " +
                "Give a self-contained task and any inputs; the result comes back to you as soon as it lands. Several can run at once.",
                JSONObject().put("task", s("Everything the agent needs to know and do")).put("specialist", s("Specialist type, optional"))
                    .put("inputs", s("Data or details it needs, optional")).put("title", s("2-5 word pill title")), listOf("task")),
            fn("list_agents", "List background agents and what each is doing.", JSONObject(), emptyList()),
            fn("check_agent", "Status, current step and (when done) the full result of one background agent.", JSONObject().put("id", s("e.g. B2")), listOf("id")),
            fn("message_agent", "Steer a background agent: add an instruction while it works, or give a follow-up after it finished.", JSONObject().put("id", s("e.g. B2")).put("text", s("What to tell it")), listOf("id", "text")),
            fn("cancel_agent", "Stop a background agent.", JSONObject().put("id", s("e.g. B2")), listOf("id")),
            fn("agent_progress", "What a running background agent has done and found so far: its newest steps and its latest real tool results. " +
                "Use it to answer \"how is it going\" or before steering it.", JSONObject().put("id", s("e.g. B2")), listOf("id")),
            fn("post_update", "Post one short interim message to the user while your background agents work, written by you from what they really found " +
                "(e.g. what turned up so far and what is left). Only for real progress: refused when nothing is new. Not for the final answer.",
                JSONObject().put("text", s("One or two short sentences in your own words")), listOf("text")),
        )
    }

    fun run(name: String, a: JSONObject, parent: String): String = when (name) {
        "list_agents" -> _all.value.filter { it.live || it.ended > System.currentTimeMillis() - 6 * 3600_000 }.takeLast(12)
            .joinToString("\n") { describe(it) }.ifBlank { "No background agents." }
        "check_agent" -> get(a.optString("id"))?.let { describe(it) + (if (it.result.isNotBlank()) "\nResult:\n" + it.result.take(8000) else "") } ?: "No agent ${a.optString("id")}."
        "message_agent" -> steer(a.optString("id"), a.optString("text"))
        "cancel_agent" -> cancel(a.optString("id"))
        "agent_progress" -> Agent.agentProgress(a.optString("id"))
        "post_update" -> Agent.postUpdate(parent, a.optString("text"))
        else -> "Unknown agent tool."
    }
}
