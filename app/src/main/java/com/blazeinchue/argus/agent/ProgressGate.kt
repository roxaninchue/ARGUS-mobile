package com.blazeinchue.argus.agent

/**
 * 0.52.1 (owner: "real progress replies, no templates"). While background agents work, the main Argus may post an
 * interim message it writes itself from the agents' real results ("I found some genuinely good stuff, getting a bit
 * more, then merging the final report"). This decides WHEN:
 *  - an agent reaching a milestone ([MILESTONES] steps, after [MIN_ELAPSED_S]) wakes the main Argus once per milestone
 *    with its progress; the model either calls post_update or replies [SKIP] (nothing is shown);
 *  - post_update itself is refused unless there is something new since the last update (≥ [MIN_NEW_STEPS] more agent
 *    steps or an agent finished), at most one per [MIN_GAP_MS], and never a near-repeat of the last line.
 * Pure Kotlin; ProgressGateTest.
 */
object ProgressGate {
    val MILESTONES = listOf(4, 12, 24, 40)
    const val MIN_ELAPSED_S = 40L
    const val MIN_GAP_MS = 30_000L
    const val MIN_NEW_STEPS = 5
    const val MAX_CHARS = 280
    const val SKIP = "SKIP"

    /** How many milestones [steps] has reached. */
    fun reached(steps: Int): Int = MILESTONES.count { steps >= it }

    /** A new milestone to report: more reached than [fired] already reported, and the agent has run long enough. */
    fun due(steps: Int, seconds: Long, fired: Int): Boolean = seconds >= MIN_ELAPSED_S && reached(steps) > fired

    data class Posted(val at: Long, val steps: Int, val finished: Int, val text: String)

    /**
     * Null = post it; otherwise why not (returned to the model as the tool's answer). [steps] = steps of this chat's
     * agents now (live and finished), [finished] = how many of them have finished, [live] = how many still work.
     */
    fun refuse(last: Posted?, now: Long, steps: Int, finished: Int, live: Int, text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return "Not posted: write the update first."
        if (live == 0) return "Not posted: no agent is working now. Reply normally instead."
        if (t.length > MAX_CHARS) return "Not posted: keep it to one or two short sentences (under $MAX_CHARS characters)."
        if (last == null) return if (steps >= 3) null else "Not posted: the agents have barely started; wait until they have found something."
        if (now - last.at < MIN_GAP_MS) return "Not posted: you posted an update ${(now - last.at) / 1000}s ago. Wait for real progress."
        if (finished <= last.finished && steps - last.steps < MIN_NEW_STEPS) return "Not posted: nothing new since your last update."
        if (similar(t, last.text)) return "Not posted: that says the same as your last update."
        return null
    }

    /** The model answered a milestone wake with nothing to tell. */
    fun skip(reply: String): Boolean = reply.trim().trim('.', '!', '"', '`', '*').uppercase().let { it == SKIP || it == "NO_UPDATE" || it == "NO UPDATE" }

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

    fun similar(a: String, b: String): Boolean {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return a.trim().equals(b.trim(), true)
        return (x intersect y).size.toDouble() / (x union y).size >= 0.7
    }

    /** The platform message that wakes the main Argus at a milestone (its findings are real tool results). */
    fun wakeText(id: String, title: String, steps: Int, seconds: Long, log: List<String>, findings: List<String>): String = buildString {
        append("[Agent progress · $id · $steps steps · ${seconds}s] This is a platform message, not the user. Your background agent \u00ab$title\u00bb is still working.\n")
        if (log.isNotEmpty()) append("Its newest steps: ").append(log.takeLast(8).joinToString("; ").take(900)).append('\n')
        if (findings.isNotEmpty()) {
            append("What it has found so far (real tool results):\n")
            findings.takeLast(4).forEach { append("- ").append(it.replace(Regex("\\s+"), " ").take(600)).append('\n') }
        }
        append("If there is something real and new worth telling the user now, call post_update with one or two short sentences in your own words, ")
        append("written from these results (what was found, what's left). Don't use stock phrases and don't promise times. ")
        append("If not, reply with exactly $SKIP. Don't do any other work in this turn.")
    }
}
