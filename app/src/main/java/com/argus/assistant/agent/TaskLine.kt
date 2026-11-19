package com.argus.assistant.agent

/**
 * 0.52.1 (owner, repeated): ONE pill per task, a single short line of natural text that changes as the work moves on
 * ("Searching sources…", "Reading three pages…", "Merging the report…"). No step count, no timer, no list, no command
 * line, never expands, gone when the task is done. This turns the raw step texts (Friendly lines, agent steps, blueprint
 * node events) into that one line. Pure Kotlin; TaskLineTest.
 */
object TaskLine {
    const val IDLE_LINE = "Thinking\u2026"
    const val MAX = 44

    private val words = listOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private fun n(k: Int) = if (k in 1..9) words[k] else k.toString()

    private enum class Kind { SEARCH, READ, SCREEN, BROWSER, SHELL, FILE_WRITE, FILE_READ, MEMORY, MERGE, OTHER }

    private fun kind(t: String): Kind {
        val l = t.lowercase()
        return when {
            l.contains("stitch") || l.contains("merg") || l.contains("final report") || l.contains("putting it together") || l.startsWith("writing it up") -> Kind.MERGE
            (l.startsWith("searching") && !l.startsWith("searching your")) || l.startsWith("search ") -> Kind.SEARCH
            l.startsWith("reading ") && !l.startsWith("reading the screen") && !l.startsWith("reading your") -> Kind.READ
            l.startsWith("looking at your screen") || l.startsWith("tapping") || l.startsWith("scrolling") || l.startsWith("typing") ||
                l.startsWith("reading the screen") || l.startsWith("waiting for the screen") || l.startsWith("pressing") -> Kind.SCREEN
            l.startsWith("using the browser") || l.startsWith("opening ") && l.contains('.') -> Kind.BROWSER
            l.startsWith("running") || l.startsWith("installing packages") || l.startsWith("checking a background job") -> Kind.SHELL
            l.startsWith("writing ") || l.startsWith("editing ") -> Kind.FILE_WRITE
            l.startsWith("checking memory") || l.startsWith("looking through what i remember") -> Kind.MEMORY
            else -> Kind.OTHER
        }
    }

    /** Lines that must never reach the pill: counters, timers, commands, raw status words. */
    private val noise = Regex("""(?i)^(stopped\.?|done\.?|worked\b.*|working\b.*|\d+ steps?\b.*|waiting for your task.*|sent\. waiting.*|not trying .*|not repeating .*)$""")

    /**
     * The pill's line for a task whose newest step texts are [steps] (oldest first). Repeats of one kind fold into a
     * count in words ("Reading three pages…"). Returns [IDLE_LINE] when no step has a showable line yet.
     */
    fun of(steps: List<String>): String {
        val clean = steps.mapNotNull { s -> s.trim().takeIf { it.isNotEmpty() && !noise.matches(it) } }
        val last = clean.lastOrNull() ?: return IDLE_LINE
        val k = kind(last)
        var run = 0
        for (i in clean.indices.reversed()) { if (kind(clean[i]) == k) run++ else break }
        val line = when (k) {
            Kind.SEARCH -> if (run >= 2) "Searching sources" else "Searching the web"
            Kind.READ -> if (run >= 2) "Reading ${n(run)} pages" else "Reading a page"
            Kind.SCREEN -> "Working on your screen"
            Kind.BROWSER -> "Working in the browser"
            Kind.SHELL -> "Working in the terminal"
            Kind.FILE_WRITE -> if (run >= 2) "Writing ${n(run)} files" else "Writing a file"
            Kind.FILE_READ -> "Reading files"
            Kind.MEMORY -> "Remembering"
            Kind.MERGE -> "Merging the report"
            Kind.OTHER -> StepPill.clean(last) ?: return IDLE_LINE.takeIf { clean.size == 1 } ?: of(clean.dropLast(1))
        }
        return finish(line)
    }

    private fun finish(s: String): String {
        var t = s.trim().trimEnd('.', '\u2026', ' ')
        if (t.length > MAX) t = t.take(MAX - 1).substringBeforeLast(' ').ifEmpty { t.take(MAX - 1) }
        return "$t\u2026"
    }

    /**
     * The one pill the chat shows: the running turn's line when a turn runs ([turnSteps] non-null), else the newest live
     * background agent's line ([agents]: each live agent's step log, newest agent last), else null (no pill).
     */
    fun pick(turnSteps: List<String>?, agents: List<List<String>>): String? = when {
        turnSteps != null -> of(turnSteps)
        agents.isNotEmpty() -> of(agents.last())
        else -> null
    }
}
