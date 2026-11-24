package com.blaze.assistant.phone

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
    data class Event(val title: String, val begin: Long, val end: Long, val allDay: Boolean = false, val location: String? = null,
                     /** 0.55.14: the provider's event id (0 = unknown). Listed so delete_event / move_event act on one event, not a title match. */
                     val id: Long = 0)

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
        return "$time: ${e.title}$loc" + (if (e.id > 0) " [id ${e.id}]" else "")
    }

    /**
     * 0.55.14 (phone 24 Nov 02:20, "Deleted “Go home” (Wed 25 Nov 02:00)"): when an event is, for a tool's reply. An all-day
     * event is a UTC-midnight date, so it is read in UTC and says "all day", never a local clock time.
     */
    fun whenText(e: Event, zone: ZoneId): String = if (e.allDay) {
        val s = Instant.ofEpochMilli(e.begin).atZone(ZoneOffset.UTC).toLocalDate()
        val last = Instant.ofEpochMilli(maxOf(e.begin, e.end - 1)).atZone(ZoneOffset.UTC).toLocalDate()
        if (last.isAfter(s)) "${s.format(dayFmt)} \u2013 ${last.format(dayFmt)}, all day" else "${s.format(dayFmt)}, all day"
    } else Instant.ofEpochMilli(e.begin).atZone(zone).let { "${it.format(dayFmt)} ${it.format(hmFmt)}" }

    /** What a delete / move acts on, from the events that match. */
    sealed class Pick {
        data class One(val event: Event) : Pick()
        /** Several different events match and none was named by id: list them and ask, act on none. */
        data class Many(val events: List<Event>) : Pick()
        object None : Pick()
    }

    /**
     * 0.55.14: [id] > 0 picks exactly that event (or None). Without an id, one match is acted on; several DIFFERENT events
     * (different ids) are never guessed between: the reply lists them with their ids. The same id seen twice (a recurring
     * instance or a second read) is one event.
     */
    fun pick(matches: List<Event>, id: Long): Pick {
        if (id > 0) return matches.firstOrNull { it.id == id }?.let { Pick.One(it) } ?: Pick.None
        val distinct = matches.distinctBy { if (it.id > 0) "id:${it.id}" else "t:${it.title}|${it.begin}" }
        return when (distinct.size) { 0 -> Pick.None; 1 -> Pick.One(distinct[0]); else -> Pick.Many(distinct) }
    }

    /** The reply when several events match: each with its time and id, and what to do next. */
    fun manyText(verb: String, title: String, events: List<Event>, zone: ZoneId): String =
        "${events.size} different events match \u201c$title\u201d, so nothing was ${verb.removeSuffix("_event")}d. They are separate events (different ids), not one event listed twice:\n" +
            events.take(10).joinToString("\n") { "- ${whenText(it, zone)}: ${it.title} [id ${it.id}]" } +
            "\nAsk the user which one, then call $verb again with its event_id."

    /** 0.55.14: with ids listed, what they mean (one line per event, so two reads of the list are never "a duplicate"); "" without ids. */
    fun idNote(lines: List<String>): String = if (lines.none { it.contains(" [id ") }) "" else
        "${lines.size} event${if (lines.size == 1) "" else "s"}, one line each; [id N] is the event's id (two lines are two events only when " +
            "their ids differ; pass event_id to move_event / delete_event).\n"

    fun lines(events: List<Event>, now: ZonedDateTime): List<String> = events.map { line(it, now.toLocalDate(), now.zone) }
}
