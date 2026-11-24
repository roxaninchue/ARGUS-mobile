package com.blaze.assistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * 0.55.14 calendar "duplicate" delete (phone 24 Nov 02:20): two reads of one "Go home" looked like two copies, delete_event
 * removed the first title match, and its reply said "Wed 25 Nov 02:00" for an all-day event.
 */
class CalendarDeleteFixTest {
    private val lusaka = ZoneId.of("Africa/Lusaka")
    private fun utcDay(d: Int) = LocalDate.of(2026, 11, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val goHome = EventLines.Event("Go home", utcDay(25), utcDay(26), allDay = true, id = 41)

    @Test fun eventLinesCarryTheId() {
        val now = ZonedDateTime.of(2026, 11, 24, 2, 18, 0, 0, lusaka)
        assertEquals("Wed 25 Nov (tomorrow) all day: Go home [id 41]", EventLines.line(goHome, now.toLocalDate(), lusaka))
        assertEquals("Wed 25 Nov (tomorrow) all day: Go home", EventLines.line(goHome.copy(id = 0), now.toLocalDate(), lusaka))
        val r = EventLines.idNote(EventLines.lines(listOf(goHome), now))
        assertTrue(r.contains("1 event, one line each")); assertTrue(r.contains("event_id"))
        assertEquals("", EventLines.idNote(listOf("Wed 21 Oct 15:00\u201316:00: Lab")))
    }

    @Test fun allDayDeleteTextNeverShowsAClockTime() {
        assertEquals("Wed 25 Nov, all day", EventLines.whenText(goHome, lusaka))
        assertEquals("Sat 21 Nov \u2013 Sun 22 Nov, all day", EventLines.whenText(EventLines.Event("Trip", utcDay(21), utcDay(23), true, id = 2), lusaka))
        val timed = EventLines.Event("Lab", ZonedDateTime.of(2026, 11, 25, 14, 0, 0, 0, lusaka).toInstant().toEpochMilli(), 0, id = 3)
        assertEquals("Wed 25 Nov 14:00", EventLines.whenText(timed, lusaka))
    }

    @Test fun oneEventReadTwiceIsOneEvent_twoDifferentEventsAreNeverGuessed() {
        assertEquals(EventLines.Pick.One(goHome), EventLines.pick(listOf(goHome, goHome), 0))
        val other = goHome.copy(id = 42)
        val many = EventLines.pick(listOf(goHome, other), 0)
        assertTrue(many is EventLines.Pick.Many)
        assertEquals(EventLines.Pick.One(other), EventLines.pick(listOf(goHome, other), 42))
        assertEquals(EventLines.Pick.None, EventLines.pick(listOf(goHome), 99))
        assertEquals(EventLines.Pick.None, EventLines.pick(emptyList(), 0))
        val t = EventLines.manyText("delete_event", "Go home", (many as EventLines.Pick.Many).events, lusaka)
        assertTrue(t.contains("nothing was deleted")); assertTrue(t.contains("[id 41]")); assertTrue(t.contains("[id 42]")); assertTrue(t.contains("event_id"))
    }
}
