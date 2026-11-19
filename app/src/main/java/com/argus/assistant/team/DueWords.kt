package com.blaze.assistant.team

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * P1-TM-05: a job's due words ("Fri", "tomorrow 5pm", "in 3 days", "Jan 5", "2026-01-05") → the `jobs.due` text, an ISO
 * date ("2026-01-05") or date and time ("2026-01-05T17:00"), and back to a short label for cards ("Today", "Tomorrow",
 * "Fri", "5 Jan", "2 days late"). Pure; [today] is passed in so DueWordsTest controls it.
 *
 * Rules: a weekday ("Fri", "next Fri", "on Friday") is the next one after today ("Fri" on a Friday = next Friday); "next week" =
 * next Monday; "this weekend" = Saturday (today when it is Saturday or Sunday); "end of week" = Friday; "end of month"
 * = its last day; "tonight" = today 20:00; "eod" / "end of day" = today 17:00; a day-month with no year that has passed
 * this year is next year; numeric dates are day first (05/01 = 5 January), as written in Zambia. A time ("5pm", "17:30",
 * "at 9") may follow any of these. Words it can't read give null and the job keeps no due date.
 */
object DueWords {
    private val days = mapOf("mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY)
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val timeRe = Regex("""(?:\bat\s+)?\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""")
    private val iso = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[t ](\d{2}):(\d{2}))?$""")

    fun parse(text: String?, today: LocalDate): String? {
        var t = text?.trim()?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ")?.removePrefix("by ")?.removePrefix("due ")?.trim() ?: return null
        if (t.isEmpty()) return null
        iso.matchEntire(t)?.let { m ->
            val d = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() ?: return null
            if (m.groupValues[4].isEmpty()) return d.toString()
            val tm = runCatching { LocalTime.of(m.groupValues[4].toInt(), m.groupValues[5].toInt()) }.getOrNull() ?: return null
            return fmt(d, tm)
        }
        var fixed: LocalTime? = null
        when {
            t == "tonight" || t.startsWith("tonight ") -> { fixed = LocalTime.of(20, 0); t = "today" + t.removePrefix("tonight") }
            t == "eod" || t == "end of day" || t == "end of today" -> return fmt(today, LocalTime.of(17, 0))
        }
        // the day part, then whatever is left is the time
        val (day, rest) = dayPart(t, today) ?: return null
        val time = fixed ?: rest.trim().takeIf { it.isNotEmpty() }?.let { time(it) ?: return null }
        return if (time == null) day.toString() else fmt(day, time)
    }

    private fun fmt(d: LocalDate, t: LocalTime) = d.toString() + "T" + t.format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun time(s: String): LocalTime? {
        val m = timeRe.matchEntire(s.trim()) ?: return null
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        when (m.groupValues[3]) { "pm" -> if (h < 12) h += 12; "am" -> if (h == 12) h = 0 }
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }

    private fun dayPart(t: String, today: LocalDate): Pair<LocalDate, String>? {
        fun word(w: String) = t == w || t.startsWith("$w ")
        fun after(w: String) = t.removePrefix(w)
        listOf("day after tomorrow" to today.plusDays(2), "today" to today, "tomorrow" to today.plusDays(1), "tmrw" to today.plusDays(1),
            "next week" to today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
            "end of week" to today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)),
            "end of the week" to today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)),
            "this weekend" to weekend(today), "weekend" to weekend(today),
            "end of month" to today.with(TemporalAdjusters.lastDayOfMonth()), "end of the month" to today.with(TemporalAdjusters.lastDayOfMonth()),
        ).forEach { (w, d) -> if (word(w)) return d to after(w) }
        Regex("""^in (\d{1,3}|a|an|one|two|three) (day|days|week|weeks)\b""").find(t)?.let { m ->
            val n = when (val v = m.groupValues[1]) { "a", "an", "one" -> 1; "two" -> 2; "three" -> 3; else -> v.toInt() }
            val d = if (m.groupValues[2].startsWith("week")) today.plusWeeks(n.toLong()) else today.plusDays(n.toLong())
            return d to t.substring(m.range.last + 1)
        }
        Regex("""^(?:next |this |on )?(mon|tue|wed|thu|fri|sat|sun)[a-z]*\b""").find(t)?.let { m ->
            val dow = days.getValue(m.groupValues[1])
            return today.with(TemporalAdjusters.next(dow)) to t.substring(m.range.last + 1)
        }
        // "jan 5", "5 jan", "january 5th 2027", "5 january"
        Regex("""^(?:(\d{1,2})(?:st|nd|rd|th)? ([a-z]{3})[a-z]*|([a-z]{3})[a-z]* (\d{1,2})(?:st|nd|rd|th)?)(?:,? (\d{4}))?\b""").find(t)?.let { m ->
            val mon = months.indexOf(m.groupValues[2].ifEmpty { m.groupValues[3] }).takeIf { it >= 0 } ?: return@let
            val dd = m.groupValues[1].ifEmpty { m.groupValues[4] }.toInt()
            return dated(today, m.groupValues[5].toIntOrNull(), mon + 1, dd)?.let { it to t.substring(m.range.last + 1) }
        }
        Regex("""^(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""").find(t)?.let { m ->
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            return dated(today, y, m.groupValues[2].toInt(), m.groupValues[1].toInt())?.let { it to t.substring(m.range.last + 1) }
        }
        // a bare time ("5pm") means today
        return time(t)?.let { today to t }
    }

    private fun weekend(today: LocalDate): LocalDate =
        if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) today else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))

    private fun dated(today: LocalDate, year: Int?, month: Int, day: Int): LocalDate? {
        val d = runCatching { LocalDate.of(year ?: today.year, month, day) }.getOrNull() ?: return null
        return if (year == null && d.isBefore(today)) runCatching { d.plusYears(1) }.getOrNull() else d
    }

    /** The day of a stored due text, or null. */
    fun date(due: String?): LocalDate? = due?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** True when the due day has passed (a due time on today is not overdue until tomorrow; the day is what counts). */
    fun overdue(due: String?, today: LocalDate): Boolean = date(due)?.isBefore(today) == true

    /** A card's due label: "Today", "Tomorrow", "Fri" (this week), "5 Jan", "5 Jan 2027", "1 day late", "3 days late"; time appended. */
    fun label(due: String?, today: LocalDate): String? {
        val d = date(due) ?: return null
        val time = due!!.substringAfter('T', "").takeIf { it.length == 5 }?.let { " $it" }.orEmpty()
        val n = ChronoUnit.DAYS.between(today, d)
        val day = when {
            n < 0 -> return if (n == -1L) "1 day late" else "${-n} days late"
            n == 0L -> "Today"
            n == 1L -> "Tomorrow"
            n < 7 -> d.dayOfWeek.name.take(1) + d.dayOfWeek.name.substring(1, 3).lowercase(Locale.ROOT)
            d.year == today.year -> "${d.dayOfMonth} ${months[d.monthValue - 1].replaceFirstChar { it.uppercase() }}"
            else -> "${d.dayOfMonth} ${months[d.monthValue - 1].replaceFirstChar { it.uppercase() }} ${d.year}"
        }
        return day + time
    }
}
