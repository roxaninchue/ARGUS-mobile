package com.argus.assistant.phone

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 18 Nov 2026 (phone, Wed 18 Nov 10:51 CAT, GPT 6 Luna): "how's my week?" got "Here's your week… Today — Thu 19 Nov: 14:00–17:00
 * NET103 final". calendar_events listed events from an hour ago onwards with no "today" in it, so the first listed day
 * (Thursday, nothing on Wednesday) read as today. Now the result opens with today's date and zone, and every event says
 * how its day relates to today ("today", "tomorrow"), so the model never has to work out which day is today itself.
 *
 * Pure (java.time only): EventLinesTest. English, fixed formats (this is for the model, not shown to the user).
 */
object EventLines {
    /** One calendar instance as the provider returns it: [begin]/[end] epoch ms; all-day ones are UTC midnights (end exclusive). */
    data class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean = false, val location: String? = null)

    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val fullFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    private val hmFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** "today" / "tomorrow" / "yesterday" / "in 5 days" / "3 days ago" for [d] seen from [today]. */
    fun relative(d: LocalDate, today: LocalDate): String {
        val n = ChronoUnit.DAYS.between(today, d)
        return when {
            n == 0L -> "today"
            n == 1L -> "tomorrow"
            n == -1L -> "yesterday"
            n > 1 -> "in $n days"
            else -> "${-n} days ago"
        }
    }

    /** "Today is Wed 18 Nov 2026, 10:51 (Africa/Lusaka)." */
    fun header(now: ZonedDateTime): String = "Today is ${now.format(fullFmt)}, ${now.format(hmFmt)} (${now.zone.id})."

    /**
     * "Thu 19 Nov (tomorrow) 14:00–17:00: NET103 final @ Hall 2"; "Wed 25 Nov (in 7 days) all day: Go home";
     * an all-day span over several days: "Sat 21 Nov (in 3 days) – Sun 22 Nov all day: Trip".
     */
    fun line(e: Event, today: LocalDate, zone: ZoneId): String {
        val loc = e.location?.takeIf { it.isNotBlank() }?.let { " @ $it" } ?: ""
        val time: String = if (e.allDay) {
            // Android stores all-day instances as UTC midnights; the end is the exclusive next midnight.
            val s = Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC).toLocalDate()
            val last = Instant.ofEpochMilli(maxOf(e.begin, e.end - 1)).atZone(ZoneOffset.UTC).toLocalDate()
            val head = "${s.format(dayFmt)} (${relative(s, today)})"
            if (last.isAfter(s)) "$head \u2013 ${last.format(dayFmt)} all day" else "$head all day"
        } else {
            val s = Instant.ofEpochMilli(e.begin).atZone(zone)
            val en = Instant.ofEpochMilli(e.end).atZone(zone)
            val sd = s.toLocalDate()
            val endPart = if (en.toLocalDate() != sd && e.end > e.begin) en.format(dayFmt) + " " + en.format(hmFmt) else en.format(hmFmt)
            "${sd.format(dayFmt)} (${relative(sd, today)}) ${s.format(hmFmt)}\u2013$endPart"
        }
        return "$time: ${e.title}$loc"
    }

    fun lines(events: List<Event>, now: ZonedDateTime): List<String> = events.map { line(it, now.toLocalDate(), now.zone) }
}
