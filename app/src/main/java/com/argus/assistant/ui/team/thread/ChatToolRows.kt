package com.argus.assistant.ui.team.thread

import com.argus.assistant.agent.StepPill
import com.argus.assistant.agent.StepTool
import com.argus.assistant.agent.ToolTrail
import com.argus.assistant.ui.team.thread.ThreadModel.State
import com.argus.assistant.ui.team.thread.ThreadModel.Step
import com.argus.assistant.ui.team.thread.ThreadModel.ThreadItem

/**
 * Milestone 4: the main ARGUS chat's tool calls as the A-team thread's rows (group → kind → call), reusing
 * [ThreadModel]'s [Step], [ThreadItem.Live] and [ThreadItem.Work], so ThreadKit draws them the same way as a bot thread.
 *
 *  - A run of step messages is one turn's work. While the chat works, the run at the end is the live block
 *    ([ThreadItem.Live]: the current line, a count, and its calls grouped by kind once opened).
 *  - Once the turn is over the run folds into a [ThreadItem.Work] chip, drawn above the message that ended it
 *    ([Turns.before], keyed by that message's id); a finished run with nothing after it is [Turns.tail].
 *  - A step message is a call when its meta is [com.argus.assistant.agent.Friendly.exact]'s text (`name(args)` or
 *    `$ cmd` for a shell); other step lines (narration) only feed the live line. A call's result comes from
 *    [ToolTrail] by the same exact text (first unused match, then the same tool's next result).
 *  - An acknowledgement reply ([Msg.ack]) does not end a turn, so "On it" never splits one turn's chip in two.
 *  - Nothing raw on screen: titles from [StepTool.label] or [StepPill.clean]; only a shell call shows its command.
 *
 * Pure: ChatToolRowsTest drives it on the JVM.
 */
object ChatToolRows {
    /** What the model needs of a chat message (the screen maps its ChatMessage list to these). */
    data class Msg(val id: Long, val step: Boolean, val text: String, val meta: String = "", val at: Long = 0, val startAt: Long = 0,
                   /** An acknowledgement reply ("On it"): it does not end the turn. */
                   val ack: Boolean = false)

    data class Turns(
        /** Message id → the finished work chip drawn just above that message. */
        val before: Map<Long, ThreadItem.Work>,
        /** The running turn's live block, or null. */
        val live: ThreadItem.Live?,
        /** A finished run with no message after it (the turn stopped without a reply). */
        val tail: ThreadItem.Work?,
    )

    private val CALL = Regex("^([A-Za-z][A-Za-z0-9_.-]*)\\(")

    /** The tool name an exact text names: `$ cmd` is a shell call; null = a narration line, not a call. */
    fun toolOf(meta: String): String? {
        val m = meta.trim()
        if (m.isEmpty()) return null
        if (m.startsWith("$ ")) return "run_shell"
        return CALL.find(m)?.groupValues?.get(1)
    }

    /** The shell command an exact text carries (`$ ls -la` → `ls -la`), else null. */
    fun commandOf(meta: String): String? = meta.trim().takeIf { it.startsWith("$ ") }?.removePrefix("$ ")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The turns of [msgs] (oldest first). [working] = the chat is running a turn now. [results] = [ToolTrail] results
     * for this chat, oldest first.
     */
    fun turns(msgs: List<Msg>, working: Boolean, results: List<ToolTrail.Result> = emptyList()): Turns {
        val used = BooleanArray(results.size)
        val before = LinkedHashMap<Long, ThreadItem.Work>()
        var run = ArrayList<Msg>()
        var prevAt = 0L
        for (m in msgs) {
            if (m.step) { run += m; continue }
            if (m.ack) continue
            if (run.isNotEmpty()) {
                work(run, results, used, live = false, prevAt = prevAt, endAt = m.at)?.let { before[m.id] = it }
                run = ArrayList()
            }
            prevAt = m.at
        }
        if (run.isEmpty()) return Turns(before, null, null)
        return if (working) Turns(before, live(run, results, used, prevAt), null)
        else Turns(before, null, work(run, results, used, live = false, prevAt = prevAt, endAt = run.last().at))
    }

    /** The calls of a run, with their results and states. In a live run an unanswered call is RUNNING, else DONE. */
    fun steps(run: List<Msg>, results: List<ToolTrail.Result>, used: BooleanArray, live: Boolean): List<Step> =
        run.mapNotNull { stepOf(it, results, used, live) }

    /** One call message as a [Step] (null for a narration / status line). */
    fun stepOf(m: Msg, results: List<ToolTrail.Result>, used: BooleanArray, live: Boolean): Step? {
        val name = toolOf(m.meta) ?: return null
        val l = StepTool.label(name)
        var hit = results.indices.firstOrNull { !used[it] && results[it].exact == m.meta.trim() }
        if (hit == null) hit = results.indices.firstOrNull { !used[it] && toolOf(results[it].exact) == name && results[it].at >= m.at }
        hit?.let { used[it] = true }
        val r = hit?.let { results[it] }
        val state = when {
            r != null -> if (r.failed) State.FAILED else State.DONE
            live -> State.RUNNING
            else -> State.DONE
        }
        val now = StepPill.clean(m.text) ?: l.now
        return Step("m${m.id}", now, l.done, l.kind, l.icon, state,
            command = if (l.kind == StepTool.Kind.COMMAND) commandOf(m.meta) else null,
            output = r?.output?.takeIf { it.isNotBlank() }, undoable = false, at = m.at, detail = detailOf(m.meta), tool = name)
    }

    private val ARG = Regex("""(?:^|, )(query|q|url|path|file|filename|name|title|app|contact|to|text|prompt|task|action)=([^,]+)""")

    /** One line about what a call was for: a shell's command, else its query / url / path / name … argument. */
    fun detailOf(meta: String): String? {
        commandOf(meta)?.let { return it.take(160) }
        val m = meta.trim()
        val open = m.indexOf('('); if (open < 0) return null
        val body = m.substring(open + 1).removeSuffix(")")
        val v = ARG.find(body)?.groupValues?.get(2) ?: body.substringBefore(", ").substringAfter('=', "")
        return v.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").take(160).takeIf { it.isNotBlank() }
    }

    private fun start(run: List<Msg>, prevAt: Long): Long =
        run.firstOrNull { it.startAt > 0 }?.startAt ?: run.first().at.takeIf { it > 0 } ?: prevAt

    private fun work(run: List<Msg>, results: List<ToolTrail.Result>, used: BooleanArray, live: Boolean, prevAt: Long, endAt: Long): ThreadItem.Work? {
        val steps = steps(run, results, used, live)
        if (steps.isEmpty()) return null
        val failed = steps.last().state == State.FAILED
        return ThreadItem.Work("work-${steps.first().key}", steps, (endAt - start(run, prevAt)).coerceAtLeast(0), failed)
    }

    private fun live(run: List<Msg>, results: List<ToolTrail.Result>, used: BooleanArray, prevAt: Long): ThreadItem.Live {
        val steps = steps(run, results, used, live = true)
        val cur = steps.lastOrNull { it.state == State.RUNNING }
        val line = run.asReversed().firstNotNullOfOrNull { StepPill.clean(it.text) } ?: cur?.now ?: steps.lastOrNull()?.now ?: "Working"
        return ThreadItem.Live("live-${run.first().id}", line, steps, start(run, prevAt))
    }

    // ---------------------------------------------------------------- 0.53.3 turn flow (Claude-style)

    /** Consecutive calls of the same tool, drawn as ONE row ("Searched the web · 4"), opened to each call. */
    data class Run(val key: String, val steps: List<Step>) {
        val first: Step get() = steps.first()
        val running: Boolean get() = steps.any { it.state == State.RUNNING || it.state == State.WAITING }
        val failed: Int get() = steps.count { it.state == State.FAILED }
        val allFailed: Boolean get() = failed == steps.size
        /** "Searching the web" while one runs, else "Searched the web" (all failed: "Couldn't search the web"). */
        val title: String get() = when {
            running -> steps.lastOrNull { it.state == State.RUNNING || it.state == State.WAITING }?.let { StepTool.label(it.tool).now } ?: first.now
            else -> first.done
        }
        /** "4 searches", "2 of 3 failed" … the quiet words after the title ("" for a single call). */
        val count: String get() = when {
            steps.size == 1 -> if (failed == 1) "failed" else ""
            failed == 0 -> "${steps.size}"
            else -> "${steps.size} \u00b7 $failed failed"
        }
    }

    /** A turn's flow: what the model said between its calls, and the calls folded into [Run]s, in order. */
    sealed class Seg {
        abstract val key: String
        data class Say(override val key: String, val text: String) : Seg()
        data class Tools(override val key: String, val run: Run) : Seg()
    }

    data class Flow(val key: String, val segs: List<Seg>, val live: Boolean, val ms: Long, val startAt: Long) {
        val steps: Int get() = segs.sumOf { (it as? Seg.Tools)?.run?.steps?.size ?: 0 }
        val runs: List<Run> get() = segs.mapNotNull { (it as? Seg.Tools)?.run }
    }

    data class Flows(val before: Map<Long, Flow>, val live: Flow?, val tail: Flow?)

    /** Narration steps (Agent marks them [com.argus.assistant.agent.Narration.META]). */
    fun isSay(m: Msg): Boolean = com.argus.assistant.agent.Narration.isNarration(m.meta) && m.text.isNotBlank()

    /** [run]'s messages as a flow: narration → [Seg.Say]; calls of one tool in a row → one [Seg.Tools]. Pure. */
    fun flowOf(run: List<Msg>, results: List<ToolTrail.Result>, used: BooleanArray, live: Boolean): List<Seg> {
        val out = ArrayList<Seg>()
        var cur = ArrayList<Step>()
        fun flush() { if (cur.isNotEmpty()) { out += Seg.Tools("run-${cur.first().key}", Run("run-${cur.first().key}", cur.toList())); cur = ArrayList() } }
        for (m in run) {
            if (isSay(m)) { flush(); out += Seg.Say("say-${m.id}", m.text.trim()); continue }
            val st = stepOf(m, results, used, live) ?: continue
            // 0.53.4 (owner): calls that read the same ("Used the browser" for open, read, click) share one row too
            if (cur.isNotEmpty() && !sameRow(cur.last(), st)) flush()
            cur += st
        }
        flush()
        return out
    }

    /** Two calls in a row share a row when they are the same tool or their rows would read the same. */
    fun sameRow(a: Step, b: Step): Boolean = a.tool == b.tool || (a.done.isNotBlank() && a.done == b.done)

    /**
     * 0.53.4 (owner screenshot 09:05, "tools can become clustered, add another foldable to hide all steps"): a turn with
     * more than one row gets one fold over all of them. Live it stays open; a finished turn starts folded.
     */
    fun folds(flow: Flow): Boolean = flow.runs.size >= 2

    /** The fold's words: "Used 3 tools · 6 steps", "Working · 4 steps", "6 steps · 2 failed". */
    fun foldTitle(flow: Flow): String {
        val runs = flow.runs
        val n = flow.steps
        val failed = runs.sumOf { it.failed }
        val head = if (flow.live && runs.any { it.running }) "Working" else "Used ${runs.map { it.first.done }.distinct().size} tools"
        return "$head \u00b7 ${ThreadModel.count(n)}" + if (failed > 0) " \u00b7 $failed failed" else ""
    }

    /** Like [turns], as flows: finished ones keyed by the message that ended them, the running one live. */
    fun flows(msgs: List<Msg>, working: Boolean, results: List<ToolTrail.Result> = emptyList()): Flows {
        val used = BooleanArray(results.size)
        val before = LinkedHashMap<Long, Flow>()
        var run = ArrayList<Msg>()
        var prevAt = 0L
        fun make(r: List<Msg>, live: Boolean, endAt: Long): Flow? {
            val segs = flowOf(r, results, used, live)
            if (segs.isEmpty()) return null
            val s0 = start(r, prevAt)
            return Flow("flow-${r.first().id}", segs, live, (endAt - s0).coerceAtLeast(0), s0)
        }
        for (m in msgs) {
            if (m.step) { run += m; continue }
            if (m.ack) continue
            if (run.isNotEmpty()) { make(run, false, m.at)?.let { before[m.id] = it }; run = ArrayList() }
            prevAt = m.at
        }
        if (run.isEmpty()) return Flows(before, null, null)
        return if (working) Flows(before, make(run, true, run.last().at), null) else Flows(before, null, make(run, false, run.last().at))
    }

    /** The collapsed live block's words: the current line and, with calls, their count ("Reading a page · 3 steps"). */
    fun collapsed(live: ThreadItem.Live): String =
        if (live.steps.isEmpty()) live.line else live.line + " \u00b7 " + ThreadModel.count(live.steps.size)
}
