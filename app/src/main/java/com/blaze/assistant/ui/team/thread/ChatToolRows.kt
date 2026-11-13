package com.blaze.assistant.ui.team.thread

import com.blaze.assistant.agent.StepPill
import com.blaze.assistant.agent.StepTool
import com.blaze.assistant.agent.ToolTrail
import com.blaze.assistant.ui.team.thread.ThreadModel.State
import com.blaze.assistant.ui.team.thread.ThreadModel.Step
import com.blaze.assistant.ui.team.thread.ThreadModel.ThreadItem

/**
 * Milestone 4: the main ARGUS chat's tool calls as the A-team thread's rows (group → kind → call), reusing
 * [ThreadModel]'s [Step], [ThreadItem.Live] and [ThreadItem.Work], so ThreadKit draws them the same way as a bot thread.
 *
 *  - A run of step messages is one turn's work. While the chat works, the run at the end is the live block
 *    ([ThreadItem.Live]: the current line, a count, and its calls grouped by kind once opened).
 *  - Once the turn is over the run folds into a [ThreadItem.Work] chip, drawn above the message that ended it
 *    ([Turns.before], keyed by that message's id); a finished run with nothing after it is [Turns.tail].
 *  - A step message is a call when its meta is [com.blaze.assistant.agent.Friendly.exact]'s text (`name(args)` or
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
    fun steps(run: List<Msg>, results: List<ToolTrail.Result>, used: BooleanArray, live: Boolean): List<Step> {
        val out = ArrayList<Step>()
        for (m in run) {
            val name = toolOf(m.meta) ?: continue
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
            out += Step("m${m.id}", now, l.done, l.kind, l.icon, state,
                command = if (l.kind == StepTool.Kind.COMMAND) commandOf(m.meta) else null,
                output = r?.output?.takeIf { it.isNotBlank() }, undoable = false, at = m.at)
        }
        return out
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

    /** The collapsed live block's words: the current line and, with calls, their count ("Reading a page · 3 steps"). */
    fun collapsed(live: ThreadItem.Live): String =
        if (live.steps.isEmpty()) live.line else live.line + " \u00b7 " + ThreadModel.count(live.steps.size)
}
