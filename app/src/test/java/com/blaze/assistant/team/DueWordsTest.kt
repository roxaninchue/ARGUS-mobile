package com.blaze.assistant.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** P1-TM-05: due words → jobs.due and back (today = Thursday 12 Nov 2026). */
class DueWordsTest {
    private val today = LocalDate.of(2026, 11, 12)
    private fun p(s: String) = DueWords.parse(s, today)

    @Test fun words() {
        assertEquals("2026-11-12", p("today")); assertEquals("2026-11-13", p("tomorrow")); assertEquals("2026-11-14", p("day after tomorrow"))
        assertEquals("2026-11-13", p("Fri")); assertEquals("2026-11-13", p("by Friday")); assertEquals("2026-11-19", p("thu"))
        assertEquals("2026-11-16", p("next week")); assertEquals("2026-11-14", p("this weekend")); assertEquals("2026-11-13", p("end of week"))
        assertEquals("2026-11-30", p("end of month")); assertEquals("2026-11-15", p("in 3 days")); assertEquals("2026-11-26", p("in two weeks"))
        assertEquals("2026-11-12T20:00", p("tonight")); assertEquals("2026-11-12T17:00", p("eod"))
        assertEquals("2026-11-13T17:00", p("tomorrow 5pm")); assertEquals("2026-11-13T09:30", p("Fri at 9:30")); assertEquals("2026-11-12T17:00", p("5pm"))
        assertEquals("2027-01-05", p("Jan 5")); assertEquals("2027-01-05", p("5 January")); assertEquals("2026-12-25", p("Dec 25th"))
        assertEquals("2027-03-01", p("1 Mar 2027")); assertEquals("2027-01-05", p("05/01")); assertEquals("2026-11-30", p("30/11/26"))
        assertEquals("2026-01-05", p("2026-01-05")); assertEquals("2026-01-05T08:15", p("2026-01-05T08:15"))
        listOf("", "someday", "soon", "31/02", "2026-13-01", "tomorrow at 25", "Fri blah").forEach { assertNull(it, p(it)) }
        assertNull(DueWords.parse(null, today))
    }

    @Test fun labels() {
        fun l(s: String) = DueWords.label(s, today)
        assertEquals("Today", l("2026-11-12")); assertEquals("Tomorrow 17:00", l("2026-11-13T17:00")); assertEquals("Mon", l("2026-11-16"))
        assertEquals("19 Nov", l("2026-11-19")); assertEquals("5 Jan 2027", l("2027-01-05"))
        assertEquals("1 day late", l("2026-11-11")); assertEquals("3 days late", l("2026-11-09"))
        assertNull(DueWords.label("garbage", today))
        assertTrue(DueWords.overdue("2026-11-11", today)); assertFalse(DueWords.overdue("2026-11-12T08:00", today)); assertFalse(DueWords.overdue(null, today))
    }
}
