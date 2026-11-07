package com.blazeinchue.argus.composer

import com.blazeinchue.argus.agent.ChatMessage
import com.blazeinchue.argus.agent.Role

/**
 * 0.47 chat over Home (pure; ChatOverlayLogicTest). Which messages the overlay shows, when Argus's typing dots show,
 * how fast a fresh reply types in, and the centred timestamp.
 */
object ChatOverlayLogic {
    /** Messages shown when the chat is opened without sending (a little context). */
    const val CONTEXT = 4

    /** The overlay's start: the id before the last [CONTEXT] user / Argus messages (0 = from the start). */
    fun since(all: List<ChatMessage>): Long {
        val talk = all.filter { it.role == Role.USER || it.role == Role.ARGUS }
        return if (talk.size <= CONTEXT) 0L else talk[talk.size - CONTEXT - 1].id
    }

    /** User and Argus lines (and errors) after [sinceId]; tool steps stay in the full chat. */
    fun visible(all: List<ChatMessage>, sinceId: Long): List<ChatMessage> =
        all.filter { it.id > sinceId && (it.role == Role.USER || it.role == Role.ARGUS || it.role == Role.ERROR) && (it.text.isNotBlank() || it.files.isNotEmpty() || it.media.isNotEmpty()) }

    /**
     * 0.49.0 (RC5 a): what the chat over Home lists. Tool steps are no longer hidden: the steps between a user line and
     * Argus's reply become one compact [Row.Steps] chip ("3 steps · Opening accessibility settings"), expandable.
     */
    sealed class Row {
        abstract val key: String
        data class Msg(val m: ChatMessage) : Row() { override val key get() = "m${m.id}" }
        /** 0.52.0: [items] = the step messages and [startAt] / [endAt] the run's times, for the work pill ("Worked · 5 steps · 1m 2s ›"). */
        data class Steps(val firstId: Long, val lines: List<String>, val live: Boolean, val items: List<ChatMessage> = emptyList(),
                         val startAt: Long = 0L, val endAt: Long = 0L) : Row() { override val key get() = "s$firstId" }
    }

    fun rows(all: List<ChatMessage>, sinceId: Long, busy: Boolean): List<Row> {
        val out = ArrayList<Row>()
        var steps = ArrayList<ChatMessage>()
        var prevAt = 0L
        fun flush(live: Boolean, next: ChatMessage?) {
            if (steps.isNotEmpty()) {
                val start = com.blazeinchue.argus.ui.WorkTime.start(steps, prevAt)
                val end = com.blazeinchue.argus.ui.WorkTime.end(steps, live, next?.at ?: 0L, next?.role == Role.ARGUS, System.currentTimeMillis())
                out += Row.Steps(steps.first().id, steps.map { it.text.trim() }.filter { it.isNotEmpty() }, live, steps.toList(), start, end)
            }
            steps = ArrayList()
        }
        for (m in all) {
            if (m.id <= sinceId) continue
            if (m.role == Role.STEP) { if (m.text.isNotBlank()) steps += m; continue }
            if (m.role == Role.USER || m.role == Role.ARGUS || m.role == Role.ERROR) {
                if (m.text.isBlank() && m.files.isEmpty() && m.media.isEmpty()) continue
                flush(false, m); out += Row.Msg(m); prevAt = m.at
            }
        }
        flush(busy, null)
        return out.filter { it !is Row.Steps || it.lines.isNotEmpty() }
    }

    /** The steps chip's label: "Opening accessibility settings" or "3 steps · Searching the web". */
    fun stepsLabel(r: Row.Steps): String {
        val raw = r.lines.lastOrNull().orEmpty()
        // StepPill.clean hides urls, code and model names (never shown on a pill); such a step reads "Working on it".
        val last = com.blazeinchue.argus.agent.StepPill.clean(raw)?.takeIf { it.isNotBlank() } ?: "Working on it"
        return if (r.lines.size <= 1) last else "${r.lines.size} steps \u00b7 $last"
    }

    /**
     * The live work line beside the typing dots (same order as the old chat's WaitRow): the step pill for this chat,
     * else an acknowledged task's chip, else the status, else the newest live step. Null = nothing to say.
     */
    fun workLine(pill: String?, chip: String, status: String, lastStep: String?): String? {
        pill?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        chip.trim().takeIf { it.isNotEmpty() }?.let { return it }
        com.blazeinchue.argus.agent.StepPill.clean(status)?.takeIf { it.isNotBlank() }?.let { return it }
        return lastStep?.let { com.blazeinchue.argus.agent.StepPill.clean(it) }?.takeIf { it.isNotBlank() }
    }

    /** Follow-up chips show under Argus's newest reply only, and only when it is idle. */
    fun followupsFor(rows: List<Row>, busy: Boolean): Long? {
        if (busy) return null
        val last = (rows.lastOrNull() as? Row.Msg)?.m ?: return null
        return if (last.role == Role.ARGUS && last.followups.isNotEmpty()) last.id else null
    }

    /** Typing dots: Argus is working and hasn't started its answer to the newest user line. */
    fun typing(shown: List<ChatMessage>, busy: Boolean): Boolean = busy && shown.lastOrNull()?.role == Role.USER

    /** Characters a fresh reply reveals per ~16 ms frame: steady for short replies, never slower than ~1.5 s overall. */
    fun step(remaining: Int): Int = maxOf(2, remaining / 90)

    /** "Today 09:41" / "Mon 09:41" / "12 Oct 09:41" for the centred timestamp. */
    fun stamp(at: Long, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
        if (at <= 0L) return ""
        val c = java.util.Calendar.getInstance(zone).apply { timeInMillis = at }
        val n = java.util.Calendar.getInstance(zone).apply { timeInMillis = now }
        val hm = String.format(java.util.Locale.US, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        val sameYear = c.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR)
        val dayDiff = n.get(java.util.Calendar.DAY_OF_YEAR) - c.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dayDiff == 0 -> "Today $hm"
            sameYear && dayDiff == 1 -> "Yesterday $hm"
            sameYear && dayDiff in 2..6 -> java.text.SimpleDateFormat("EEE", java.util.Locale.US).apply { timeZone = zone }.format(java.util.Date(at)) + " " + hm
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.US).apply { timeZone = zone }.format(java.util.Date(at)) + " " + hm
        }
    }

    /** The first letter or digit of [name], upper case; null when there is none (no made-up letter). */
    fun initial(name: String?): String? = name?.trim()?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString()

    /**
     * 0.48.0 RC4: what the user's avatar shows (the chat bar, their bubbles), the same person as the profile button:
     * the account's photo, else the initial of the account's name, else of its email; signed out = a neutral icon
     * (never the old static "Y").
     */
    sealed class Avatar {
        data class Photo(val url: String, val letter: String?) : Avatar()
        data class Letter(val letter: String) : Avatar()
        object Neutral : Avatar()
    }

    fun avatar(signedIn: Boolean, name: String?, email: String?, photoUrl: String?): Avatar {
        if (!signedIn) return Avatar.Neutral
        val letter = initial(name?.takeIf { it.isNotBlank() } ?: email)
        val url = photoUrl?.trim().orEmpty()
        if (url.startsWith("https://")) return Avatar.Photo(url, letter)
        return if (letter != null) Avatar.Letter(letter) else Avatar.Neutral
    }
}
