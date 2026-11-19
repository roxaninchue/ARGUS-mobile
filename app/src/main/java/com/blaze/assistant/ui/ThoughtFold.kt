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
    /** The Lucide icon in front of the header (owner 2026-11-14: "use a brain icon for thoughts"). */
    const val ICON = "brain"

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

    /**
     * Owner, 15 Nov 2026 ("instead of showing thinking I want the thought to stream in"): while it reasons and the section
     * is open, the words themselves are the indicator. No "Thinking" header then, only the small breathing brain beside
     * the streaming text. The header comes back when folded (a tap) or once the answer starts ("Thought for 4s").
     */
    fun headerShown(open: Boolean, live: Boolean, answerStarted: Boolean): Boolean = !(open && thinkingNow(live, answerStarted))

    /** While the thought streams, the newest [LIVE_TAIL] characters (cut at a word, "…" in front) so it reads as a rolling
     *  window under the question instead of pushing the chat away; the whole thought is there once it folds and opens. */
    fun liveTail(body: String, max: Int = LIVE_TAIL): String {
        if (body.length <= max) return body
        val from = body.length - max
        val cut = body.indexOfAny(charArrayOf(' ', '\n'), from).let { if (it < 0 || it - from > 40) from else it + 1 }
        return "\u2026" + body.substring(cut).trimStart()
    }
    const val LIVE_TAIL = 560

    /**
     * Open or not: the user's own tap on this section wins; otherwise open only while it is still thinking, and then only
     * when live thoughts start open ([liveOpen], Settings.liveThoughtsOpen: folding a live thought once turns it off).
     */
    fun open(choice: Boolean?, live: Boolean, answerStarted: Boolean, liveOpen: Boolean = true): Boolean =
        choice ?: (thinkingNow(live, answerStarted) && liveOpen)

    /**
     * The open / closed taps, per section key, kept outside the composition. Before (20 Nov 2026) they lived only in
     * rememberSaveable, which forgets them whenever the section leaves the screen: the live thought between tool steps,
     * the chat rebuilt after the app comes back. Then the section opened again on its own.
     */
    object Choices {
        private const val MAX = 400
        private val map = object : LinkedHashMap<Long, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Boolean>?) = size > MAX
        }
        @Synchronized fun get(key: Long): Boolean? = map[key]
        @Synchronized fun put(key: Long, open: Boolean) { map[key] = open }
        @Synchronized fun clear() = map.clear()
    }

    /**
     * A tap on a section: remembered for that section, and when the thought is still streaming it also sets how live
     * thoughts start from now on (returned as the new live default, null = unchanged).
     */
    fun tap(key: Long, open: Boolean, live: Boolean, answerStarted: Boolean): Boolean? {
        Choices.put(key, open)
        return if (thinkingNow(live, answerStarted)) open else null
    }

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
