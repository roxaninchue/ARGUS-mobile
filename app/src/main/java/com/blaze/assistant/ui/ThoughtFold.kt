package com.blaze.assistant.ui

/**
 * fix/chat-flat-ui (owner, 14 Nov 2026: "we'll show collapsible and expandable thinking process"): the rules of the
 * folded section above a reply. Pure (ThoughtFoldTest); drawn by [ThoughtSection].
 *
 * While the model is still reasoning and no answer text has arrived, the section is open and says "Thinking". As soon as
 * the answer starts it folds to "Thought for 4s". A tap opens or closes it, and that choice wins from then on.
 */
object ThoughtFold {
    const val LIVE = "Thinking"
    const val DONE = "Thought for"
    /** The header when the time was never measured (older replies). */
    const val DONE_UNTIMED = "Thoughts"

    private val leftoverTags = Regex("</?(?:think|thought|thinking)>", RegexOption.IGNORE_CASE)
    private val blankRuns = Regex("\n{3,}")

    /** Still reasoning: the turn is live and no answer text has arrived yet. */
    fun thinkingNow(live: Boolean, answerStarted: Boolean): Boolean = live && !answerStarted

    /** "Thinking" while reasoning; then "Thought for 4s" / "Thought for 1m 5s"; "Thoughts" when not timed. */
    fun header(live: Boolean, answerStarted: Boolean, ms: Long): String {
        if (thinkingNow(live, answerStarted)) return LIVE
        val d = duration(ms)
        return if (d.isEmpty()) DONE_UNTIMED else "$DONE $d"
    }

    /** "4s", "1m 5s", "2m"; under a second rounds up to "1s"; 0 (or less) = "" (not measured). */
    fun duration(ms: Long): String {
        if (ms <= 0) return ""
        val s = ((ms + 500) / 1000).coerceAtLeast(1)
        return if (s < 60) "${s}s" else "${s / 60}m" + (if (s % 60 == 0L) "" else " ${s % 60}s")
    }

    /** Open or not: the user's own tap wins; otherwise open only while it is still thinking. */
    fun open(choice: Boolean?, live: Boolean, answerStarted: Boolean): Boolean = choice ?: thinkingNow(live, answerStarted)

    /** The text drawn: no stray think tags, no runs of blank lines, trimmed. */
    fun body(raw: String): String = raw.replace(leftoverTags, "").replace("\r\n", "\n").replace(blankRuns, "\n\n").trim()

    /** Whether a section is drawn at all. */
    fun shows(raw: String, setting: Boolean): Boolean = setting && body(raw).isNotEmpty()

    /** The reasoning a row shows: its saved thinking, else (only for the reply streaming right now) the turn's live thinking. */
    fun pick(saved: String, streamingHere: Boolean, liveTurn: String): String = saved.ifBlank { if (streamingHere) liveTurn else "" }

    /**
     * Times a section that was on screen while it streamed: from the first time it was seen thinking to the moment the
     * answer started (or the turn ended). Not a substitute for the saved thinkMs; [ms] is 0 until both ends are known.
     */
    class Timer {
        private var start = 0L
        private var end = 0L
        fun mark(thinkingNow: Boolean, now: Long) {
            if (thinkingNow) { if (start == 0L) start = now; end = 0L }
            else if (start != 0L && end == 0L) end = now
        }
        fun ms(): Long = if (start == 0L || end == 0L) 0L else (end - start).coerceAtLeast(0L)
    }
}
