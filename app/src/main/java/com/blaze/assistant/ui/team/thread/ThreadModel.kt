package com.blaze.assistant.ui.team.thread

import com.blaze.assistant.agent.StepPill
import com.blaze.assistant.agent.StepTool
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.EngineKind
import com.blaze.assistant.team.Row
import com.blaze.assistant.team.RowBodies
import com.blaze.assistant.team.RowKind

/**
 * P1-SH-03: a bot thread's rows (P1-TM-02 RowStore) as what the thread draws (04 §2.8 Thread 14–30), pure so
 * ThreadModelTest checks it on the JVM. ThreadKit.kt draws these.
 *
 *  - Messages: the user's words → [ThreadItem.User] (ink bubble); the bot's → [ThreadItem.Agent] (no bubble).
 *  - Tool calls between two messages are one turn's steps: while any runs (or the thread is running) they are the live
 *    [ThreadItem.Live] (ActivityLine) at the end; once the reply lands they fold into a [ThreadItem.Work] chip above it.
 *  - `step` events only feed the live line; other events and notices are [ThreadItem.System] notes.
 *  - Cards: jobs / ok / file / handoff have their own cards; approval / draft / question / connect (P2-SF-02) are a
 *    [CardModel.Decision] with their state (pending → approved / denied / expired / skipped); anything else is a plain
 *    [CardModel.Other].
 *  - Nothing raw reaches the screen: tools are named by [StepTool.label]; free text from steps goes through
 *    [StepPill.clean].
 */
object ThreadModel {
    enum class State { RUNNING, DONE, FAILED, WAITING }

    /** One step (a tool call): the step list's row and, opened, a ToolCard. */
    data class Step(
        val key: String, val now: String, val done: String, val kind: StepTool.Kind, val icon: String, val state: State,
        /** Shell command text for a command step (`$ cmd`), else null. */
        val command: String? = null,
        val output: String? = null,
        /** The engine marked the change reversible (`undoable: true`), so the card offers Undo. */
        val undoable: Boolean = false,
        val at: Long = 0,
    ) {
        /** The verb line for its state ("Reading a page", "Read a page", "Failed"). */
        val title: String get() = if (state == State.RUNNING || state == State.WAITING) now else done
        val status: String get() = when (state) { State.RUNNING -> "Running"; State.DONE -> "Done"; State.FAILED -> "Failed"; State.WAITING -> "Waiting for your OK" }
    }

    data class Group(val kind: StepTool.Kind, val steps: List<Step>)

    const val OUTPUT_MAX = 4_000
    const val JOBS_SHOWN = 3

    // ---------------------------------------------------------------- cards
    sealed class CardModel {
        data class JobItem(val id: String, val title: String, val due: String?, val done: Boolean)
        data class Jobs(val bot: String, val jobs: List<JobItem>, val open: Int) : CardModel() {
            val header: String get() = "${bot.uppercase()} \u00b7 JOBS"
            val count: String get() = "$open open"
            val shown: List<JobItem> get() = jobs.take(JOBS_SHOWN)
            val more: Int get() = (jobs.size - JOBS_SHOWN).coerceAtLeast(0)
        }
        data class Ok(val jobId: String, val title: String, val due: String?) : CardModel()
        data class File(val name: String, val path: String?, val size: Long?, val mime: String?) : CardModel() {
            val sizeText: String? get() = size?.let { ThreadModel.size(it) }
            val image: Boolean get() = mime?.startsWith("image/") == true
        }
        data class Handoff(val fromId: String, val fromName: String, val toId: String, val toName: String, val text: String,
                           val status: String?, val reply: String?) : CardModel() {
            val line: String get() = "$fromName \u2192 $toName"
        }
        data class Other(val card: String, val title: String, val text: String?) : CardModel()
        /** P2-SF-02: an approval / draft / question / connect card as the thread keeps it; [state] = pending|approved|denied|expired|skipped. */
        data class Decision(val id: String, val kind: String, val title: String, val text: String?, val to: String?, val app: String?,
                            val state: String) : CardModel() {
            val pending: Boolean get() = state == "pending"
            val stateLabel: String get() = when (state) {
                "approved" -> if (kind == "draft") "Sent" else if (kind == "question") "Answered" else if (kind == "connect") "Connected" else "Approved"
                "denied" -> if (kind == "draft") "Not sent" else if (kind == "connect") "Not connected" else if (kind == "question") "Dismissed" else "Not allowed"
                "expired" -> "Expired"; "skipped" -> "Skipped"; else -> "Waiting for you"
            }
        }
    }

    // ---------------------------------------------------------------- items
    sealed class ThreadItem {
        abstract val key: String
        data class Intro(override val key: String, val name: String, val role: String, val engine: String, val about: String?,
                         val color: String?, val manager: Boolean) : ThreadItem()
        data class User(override val key: String, val text: String, val via: String?) : ThreadItem()
        data class Agent(override val key: String, val text: String, val streaming: Boolean, val via: String?) : ThreadItem()
        data class System(override val key: String, val text: String, val error: Boolean) : ThreadItem()
        data class Live(override val key: String, val line: String, val steps: List<Step>, val startedAt: Long) : ThreadItem()
        data class Work(override val key: String, val steps: List<Step>, val ms: Long, val failed: Boolean) : ThreadItem() {
            val groups: List<Group> get() = ThreadModel.groups(steps)
            val chip: String get() = ThreadModel.workChip(steps.size, ms, failed)
            val summary: String get() = ThreadModel.summary(steps)
        }
        data class Card(override val key: String, val card: CardModel) : ThreadItem()
    }

    // ---------------------------------------------------------------- the rules
    /** The engine pill: where the bot thinks, never a model or provider name. */
    fun engineLabel(b: Bot): String = when (b.engine.kind) {
        EngineKind.LOCAL -> "On this phone"
        EngineKind.CLOUD_RUN -> "Cloud"
        EngineKind.HERMES -> "Your server"
    }

    fun intro(b: Bot, about: String? = null): ThreadItem.Intro = ThreadItem.Intro("intro", b.name,
        if (BotRules.isManager(b)) "Manager \u00b7 Lead" else b.label, engineLabel(b), about?.trim()?.takeIf { it.isNotEmpty() },
        b.avatar.color, BotRules.isManager(b))

    private fun Map<String, Any?>.s(k: String): String? = (this[k] as? String)?.takeIf { it.isNotBlank() }
    private fun Map<String, Any?>.n(k: String): Long? = when (val v = this[k]) { is Long -> v; is Double -> v.toLong(); is Int -> v.toLong(); is String -> v.toLongOrNull(); else -> null }
    private fun Map<String, Any?>.b(k: String): Boolean = this[k] == true

    /** BH-17 provenance as a small line ("From Nova", "Routine · Morning brief", "By voice"). */
    fun via(b: Map<String, Any?>): String? {
        @Suppress("UNCHECKED_CAST") val v = b["via"] as? Map<String, Any?> ?: return null
        val name = v.s("name")
        return when (v.s("kind")) {
            "bot" -> name?.let { "From $it" }
            "routine" -> name?.let { "Routine \u00b7 $it" } ?: "Routine"
            "voice" -> "By voice"
            else -> null
        }
    }

    fun step(r: Row): Step {
        val b = RowBodies.read(r.body)
        val name = b.s("name").orEmpty()
        val l = StepTool.label(name)
        val st = when (b.s("state")) {
            "running", null -> State.RUNNING
            "waiting", "needs_approval" -> State.WAITING
            "refused", "failed", "error", "interrupted" -> State.FAILED
            else -> State.DONE
        }
        val cmd = if (l.kind == StepTool.Kind.COMMAND) commandOf(b["args"]) else null
        val out = b.s("output")?.let { if (it.length > OUTPUT_MAX) it.take(OUTPUT_MAX) + "\u2026" else it }
        return Step(b.s("callId") ?: "s${r.seq}", l.now, l.done, l.kind, l.icon, st, cmd, out, b.b("undoable") && st == State.DONE, r.createdAt)
    }

    /** The command a shell step ran, when its args carry one (`command` / `cmd`, a map or its JSON). */
    private fun commandOf(args: Any?): String? {
        @Suppress("UNCHECKED_CAST")
        val m = (args as? Map<String, Any?>) ?: (args as? String)?.let { RowBodies.read(it) } ?: return null
        return (m["command"] as? String ?: m["cmd"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun card(r: Row, botName: String): CardModel {
        val b = RowBodies.read(r.body)
        return when (b.s("card")) {
            "jobs" -> {
                @Suppress("UNCHECKED_CAST")
                val js = (b["jobs"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> }.map {
                    CardModel.JobItem(it.s("id").orEmpty(), it.s("title") ?: "Job", it.s("due"), it.b("done"))
                }
                CardModel.Jobs(b.s("bot") ?: botName, js, b.n("open")?.toInt() ?: js.count { !it.done })
            }
            "ok" -> CardModel.Ok(b.s("jobId").orEmpty(), b.s("title") ?: "Job", b.s("due"))
            "file" -> CardModel.File(b.s("name") ?: b.s("path")?.substringAfterLast('/') ?: "File", b.s("path"), b.n("size"), b.s("mime"))
            "handoff" -> CardModel.Handoff(b.s("from").orEmpty(), b.s("fromName") ?: "A teammate", b.s("to").orEmpty(), b.s("toName") ?: "a teammate",
                b.s("text").orEmpty(), b.s("status"), b.s("reply"))
            "approval", "draft", "question", "connect" -> CardModel.Decision(b.s("id").orEmpty(), b.s("card")!!, b.s("title") ?: "Needs your OK",
                b.s("text") ?: b.s("question"), b.s("to"), b.s("app") ?: b.s("name"), b.s("state") ?: "pending")
            else -> CardModel.Other(b.s("card") ?: "card", b.s("title") ?: RowBodies.preview(RowKind.CARD, r.body) ?: "Card", b.s("text"))
        }
    }

    /**
     * The thread for [rows] (any order; sorted by seq here). [running] = the bot's turn is still going (BotThreads
     * `running`), so the last steps stay live even between tool calls. [intro] goes first when given.
     */
    fun items(rows: List<Row>, botName: String, running: Boolean, intro: ThreadItem.Intro? = null): List<ThreadItem> {
        val out = ArrayList<ThreadItem>()
        intro?.let { out += it }
        val steps = ArrayList<Step>()
        var line: String? = null
        var turnStart = -1L            // when this turn's first step or step line came in (-1 = none yet)
        fun flush(endAt: Long) {
            if (steps.isEmpty()) { turnStart = -1L; line = null; return }
            val failed = steps.any { it.state == State.FAILED } && steps.last().state == State.FAILED
            out += ThreadItem.Work("work-${steps.first().key}", steps.toList(), (endAt - turnStart).coerceAtLeast(0), failed)
            steps.clear(); line = null; turnStart = -1L
        }
        for (r in rows.sortedBy { it.seq }) {
            val b = RowBodies.read(r.body)
            when (r.kind) {
                RowKind.TOOL_CALL -> { if (turnStart < 0) turnStart = r.createdAt; steps += step(r) }
                RowKind.MESSAGE -> {
                    val text = b.s("text") ?: continue
                    if (b["from"] == RowBodies.USER) { flush(r.createdAt); out += ThreadItem.User("m${r.seq}", text, via(b)) }
                    else { flush(r.createdAt); out += ThreadItem.Agent("m${r.seq}", text, b.b("streaming"), via(b)) }
                }
                RowKind.EVENT -> if (b.s("type") == "step") {
                    if (turnStart < 0) turnStart = r.createdAt
                    line = StepPill.clean(b.s("text").orEmpty()) ?: line
                } else b.s("text")?.let { out += ThreadItem.System("e${r.seq}", StepTool.line(it, "Something changed"), false) }
                RowKind.NOTICE -> b.s("text")?.let { out += ThreadItem.System("n${r.seq}", StepTool.notice(it), b.s("level") == "error") }
                RowKind.CARD -> out += ThreadItem.Card("c${r.seq}", card(r, botName))
            }
        }
        val live = running || steps.any { it.state == State.RUNNING || it.state == State.WAITING }
        if (live && (steps.isNotEmpty() || running)) {
            val cur = steps.lastOrNull { it.state == State.RUNNING || it.state == State.WAITING }
            out += ThreadItem.Live("live", line ?: cur?.now ?: steps.lastOrNull()?.now ?: "Working", steps.toList(), turnStart.coerceAtLeast(0))
        } else flush(steps.lastOrNull()?.at ?: 0L)
        return out
    }

    // ---------------------------------------------------------------- text
    fun groups(steps: List<Step>): List<Group> =
        StepTool.Kind.entries.mapNotNull { k -> steps.filter { it.kind == k }.takeIf { it.isNotEmpty() }?.let { Group(k, it) } }

    /** "7 steps", or with more than one kind "7 steps: 3 reads, 2 commands, 1 browser, 1 approval". */
    fun summary(steps: List<Step>): String {
        val n = count(steps.size)
        val g = groups(steps)
        if (g.size <= 1) return n
        return n + ": " + g.joinToString(", ") { "${it.steps.size} ${if (it.steps.size == 1) it.kind.one else it.kind.many}" }
    }

    fun count(n: Int): String = if (n == 1) "1 step" else "$n steps"

    /** "Worked for 2m 10s · 7 steps"; a failed turn "Stopped after 12 steps · Go on". */
    fun workChip(n: Int, ms: Long, failed: Boolean): String =
        if (failed) "Stopped after ${count(n)} \u00b7 Go on" else "Worked for ${duration(ms)} \u00b7 ${count(n)}"

    /** "12s", "2m 10s", "1h 5m". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    /** The live line's clock: "0:07", "1:24", "1:02:05". */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** The live line as TalkBack reads it (polite live region): "Checking hotels, 3 steps, 1 minute 24 seconds". */
    fun liveA11y(line: String, steps: Int, ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val t = buildList {
            if (s >= 60) add(if (s / 60 == 1L) "1 minute" else "${s / 60} minutes")
            if (s % 60 > 0 || s < 60) add(if (s % 60 == 1L) "1 second" else "${s % 60} seconds")
        }.joinToString(" ")
        return listOf(line, if (steps > 0) count(steps) else null, t).filterNotNull().joinToString(", ")
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1048576.0)
        else -> "%.1f GB".format(java.util.Locale.ROOT, bytes / 1073741824.0)
    }
}
