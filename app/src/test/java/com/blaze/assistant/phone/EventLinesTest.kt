package com.blaze.assistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * 18 Nov 2026 (phone, Wed 18 Nov 10:51 CAT, GPT 6 Luna): "how's my week?" was answered "Today — Thu 19 Nov: 14:00–17:00 NET103
 * final". calendar_events had no "today" in it and Wednesday had nothing on, so the first listed day read as today. The
 * result now opens with today's date and every event says how its day relates to today.
 */
class EventLinesTest {
    private val lusaka = ZoneId.of("Africa/Lusaka")
    private val now = ZonedDateTime.of(2026, 11, 18, 10, 51, 0, 0, lusaka)
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, lusaka).toInstant().toEpochMilli()
    private fun utcDay(d: Int) = LocalDate.of(2026, 11, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test fun theOwnersWeekSaysTomorrowNotToday() {
        val evs = listOf(
            EventLines.Event("NET103 final", at(19, 14), at(19, 17), location = "Hall 2"),
            EventLines.Event("Trip", utcDay(21), utcDay(23), allDay = true),
            EventLines.Event("Go home", utcDay(25), utcDay(26), allDay = true),
        )
        val lines = EventLines.lines(evs, now)
        assertEquals("Thu 19 Nov (tomorrow) 14:00\u201317:00: NET103 final @ Hall 2", lines[0])
        assertEquals("Sat 21 Nov (in 3 days) \u2013 Sun 22 Nov all day: Trip", lines[1])
        assertEquals("Wed 25 Nov (in 7 days) all day: Go home", lines[2])
        val out = com.blaze.assistant.phone.Skills.eventsResult(lines, now)
        assertTrue(out, out.startsWith("Today is Wed 18 Nov 2026, 10:51 (Africa/Lusaka).\n"))
        assertTrue(!out.contains("(today)"))
    }

    @Test fun relativeDays() {
        val today = now.toLocalDate()
        assertEquals("today", EventLines.relative(today, today))
        assertEquals("tomorrow", EventLines.relative(today.plusDays(1), today))
        assertEquals("yesterday", EventLines.relative(today.minusDays(1), today))
        assertEquals("in 6 days", EventLines.relative(today.plusDays(6), today))
    }

    @Test fun localDayNotUtcDay() {
        // 00:30 CAT on Thu 19 is still Wed 18 in UTC: the line uses the phone's zone.
        val l = EventLines.line(EventLines.Event("Night bus", at(19, 0, 30), at(19, 2)), now.toLocalDate(), lusaka)
        assertEquals("Thu 19 Nov (tomorrow) 00:30\u201302:00: Night bus", l)
        // An event later today says today.
        val t = EventLines.line(EventLines.Event("Call", at(18, 15), at(18, 16)), now.toLocalDate(), lusaka)
        assertEquals("Wed 18 Nov (today) 15:00\u201316:00: Call", t)
        // Across midnight: the end carries its own day.
        val x = EventLines.line(EventLines.Event("Party", at(20, 22), at(21, 1)), now.toLocalDate(), lusaka)
        assertEquals("Fri 20 Nov (in 2 days) 22:00\u2013Sat 21 Nov 01:00: Party", x)
    }

    @Test fun emptyRangeStillSaysToday() {
        val out = Skills.eventsResult(emptyList(), now)
        assertTrue(out.startsWith("Today is Wed 18 Nov 2026"))
        assertTrue(out.endsWith(Skills.EVENTS_NONE))
        assertEquals(Skills.EVENTS_NONE, Skills.eventsResult(emptyList()))
    }
}
