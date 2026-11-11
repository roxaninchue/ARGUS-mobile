package com.blaze.assistant.team

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * P1-TM-05: due dates in words (port of A-team app.js dueFromWord / dueFromText / dueLabel / bucket, and the
 * argus-agents-api due parser). Dates are local YYYY-MM-DD strings, like Job.due. Pure: [today] is passed in.
 */
object DueWords {
    private val DAYS = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")
    private val ISO = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** Sunday = 0 … Saturday = 6 (JS getDay). */
    private fun dow(d: LocalDate) = d.dayOfWeek.value % 7

    /** A single due word ("today", "tomorrow", "this_week", "next week", "later", "fri", an ISO date). null = no date. */
    fun fromWord(w0: String?, today: LocalDate): String? {
        val w = w0?.lowercase()?.trim().orEmpty()
        if (w.isEmpty()) return null
        if (ISO.matches(w)) return w
        return when (w) {
            "none", "no date", "someday" -> null
            "today", "tonight", "now" -> today.toString()
            "tomorrow", "tmrw" -> today.plusDays(1).toString()
            "yesterday" -> today.minusDays(1).toString()
            "this_week", "this week" -> { val d = dow(today); today.plusDays(if (d == 5 || d == 6) 1 else (5 - d).toLong()).toString() }
            "next_week", "next week" -> { val d = dow(today); today.plusDays((((8 - d) % 7).takeIf { it != 0 } ?: 7).toLong()).toString() }
            "later" -> today.plusDays(14).toString()
            else -> {
                val bare = w.replace(Regex("^(next|this|on|by)\\s+"), "")
                val di = DAYS.indexOfFirst { bare.startsWith(it.take(3)) }
                if (di < 0) null else { var k = (di - dow(today) + 7) % 7; if (k == 0) k = 7; today.plusDays(k.toLong()).toString() }
            }
        }
    }

    /**
     * A date said anywhere in a sentence ("by Friday", "in 3 days", "12/11", "2026-11-20", "tomorrow").
     * Returns [NONE] when the sentence names no date (A-team returns undefined), else the date or null ("no date").
     */
    fun fromText(t: String, today: LocalDate): String? {
        val s = t.lowercase()
        Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b").find(s)?.let { return it.groupValues[1] }
        Regex("\\b(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?\\b").find(s)?.let { m ->
            val y = m.groupValues[3].let { if (it.isEmpty()) today.year.toString() else if (it.length == 2) "20$it" else it }
            val mo = m.groupValues[2].toInt(); val d = m.groupValues[1].toInt()
            return if (mo in 1..12 && d in 1..31) "$y-${"%02d".format(mo)}-${"%02d".format(d)}" else NONE
        }
        Regex("\\bin (\\d+) days?\\b").find(s)?.let { return today.plusDays(it.groupValues[1].toLong()).toString() }
        if (Regex("\\btoday\\b|\\btonight\\b").containsMatchIn(s)) return today.toString()
        if (Regex("\\btomorrow\\b|\\btmrw\\b").containsMatchIn(s)) return today.plusDays(1).toString()
        if (Regex("\\bnext week\\b").containsMatchIn(s)) return fromWord("next_week", today)
        if (Regex("\\bthis week\\b").containsMatchIn(s)) return fromWord("this_week", today)
        Regex("\\b(?:on |by |next |this )?(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b").find(s)?.let { return fromWord(it.groupValues[1], today) }
        return NONE
    }

    /** Sentinel for "the text names no date" (keep the job's own due). */
    const val NONE = "\u0000none"

    /** A due given to a tool: a sentence date first, else a word; a word nobody can read returns [NONE]. */
    fun parse(v: String?, today: LocalDate): String? {
        if (v.isNullOrBlank()) return null
        val t = fromText(v, today)
        if (t != NONE) return t
        val w = v.lowercase().trim().replace(Regex("\\s+"), "_")
        if (w in setOf("none", "no_date", "someday")) return null
        return fromWord(w, today) ?: fromWord(v, today) ?: NONE
    }

    /** "Today", "Tomorrow", "3d overdue", "Friday", "20 Nov", "No date". */
    fun label(d: String?, today: LocalDate): String {
        if (d.isNullOrBlank()) return "No date"
        val dt = runCatching { LocalDate.parse(d) }.getOrNull() ?: return d
        val diff = dt.toEpochDay() - today.toEpochDay()
        return when {
            diff == 0L -> "Today"
            diff == 1L -> "Tomorrow"
            diff < 0 -> "${-diff}d overdue"
            diff < 7 -> dt.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            else -> "${dt.dayOfMonth} ${dt.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}"
        }
    }

    /** A-team bucket(): done · todo (no date) · overdue · today · tomorrow · upcoming. */
    fun bucket(status: String, due: String?, today: LocalDate): String = when {
        status == "done" -> "done"
        due.isNullOrBlank() -> "todo"
        due < today.toString() -> "overdue"
        due == today.toString() -> "today"
        due == today.plusDays(1).toString() -> "tomorrow"
        else -> "upcoming"
    }

}
