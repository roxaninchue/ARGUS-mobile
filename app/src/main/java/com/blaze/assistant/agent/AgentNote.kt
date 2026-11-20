package com.blaze.assistant.agent

import com.blaze.assistant.orchestra.OrchestraRuns
import com.blaze.assistant.orchestra.Pipelines

/**
 * 0.54.1: the words on the background-run notification (AgentKeeper). Pure, so it is tested without Android.
 *
 * Never vague: it names the run, says how far along it is in real numbers (a blueprint's parts finished of the parts in
 * its plan; a plain agent's steps taken, with no made-up total), and what it is doing right now, or that it waits on you.
 */
object AgentNote {
    data class Text(val title: String, val body: String, val done: Int, val total: Int, val lines: List<String> = emptyList())

    /** What kind of run it is, in plain words ("Deep research", "Building an app", "Researcher"). */
    fun kind(bg: BgAgents.Bg): String = when {
        bg.specialist == OrchestraRuns.SPECIALIST -> when (try { OrchestraRuns.decode(bg.task).shape } catch (_: Throwable) { Pipelines.Shape.GENERIC }) {
            Pipelines.Shape.RESEARCH -> "Deep research"
            Pipelines.Shape.APK -> "Building an app"
            Pipelines.Shape.MEDIA -> "Curating a list"
            Pipelines.Shape.DOCUMENT -> "Writing a document"
            else -> "Blueprint run"
        }
        bg.specialist.isNotBlank() -> bg.specialist.replaceFirstChar { it.uppercase() }
        else -> "Background agent"
    }

    /** "Part 6 of 14", or "Step 23" for an agent without a plan, or "" before its first step. */
    fun progress(bg: BgAgents.Bg): String = when {
        bg.total > 0 -> "Part ${bg.done.coerceAtMost(bg.total)} of ${bg.total}"
        bg.steps > 0 -> "Step ${bg.steps}"
        else -> ""
    }

    /** What it is doing this moment. */
    fun now(bg: BgAgents.Bg, running: Int): String = when {
        bg.waiting -> "Waiting for you: open ${com.blaze.assistant.BrandName.NAME} and answer its card"
        bg.status == BgAgents.QUEUED && bg.resume -> "Picking up where it stopped"
        bg.status == BgAgents.QUEUED -> "Queued: starts when a slot frees ($running running)"
        bg.last.isNotBlank() -> bg.last
        else -> "Starting"
    }

    fun one(bg: BgAgents.Bg, running: Int): String = listOf(progress(bg), now(bg, running)).filter { it.isNotBlank() }.joinToString(" \u00b7 ")

    /** The notification for every live run ([live] non-empty). */
    fun build(live: List<BgAgents.Bg>): Text {
        val running = live.count { it.status == BgAgents.RUNNING }
        if (live.size == 1) {
            val bg = live[0]
            return Text("${kind(bg)}: ${bg.title}".take(80), one(bg, running), bg.done, bg.total)
        }
        val queued = live.size - running
        val waiting = live.count { it.waiting }
        val head = buildString {
            append("${live.size} runs: $running working")
            if (queued > 0) append(", $queued queued")
            if (waiting > 0) append(", $waiting waiting for you")
        }
        val lines = live.map { "${kind(it)}: ${it.title.take(40)} \u00b7 ${one(it, running)}".take(120) }
        return Text(head, lines.first(), 0, 0, lines)
    }
}
