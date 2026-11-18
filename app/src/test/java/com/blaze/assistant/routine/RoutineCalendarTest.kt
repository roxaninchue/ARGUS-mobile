package com.blaze.assistant.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 18 Nov 2026 (owner): routines land on the calendar with an alert, and offline runs wait for the network. */
class RoutineCalendarTest {
    private fun r(days: Int = 127, at: Long = 0L, every: Int = 0, command: String = "", enabled: Boolean = true) =
        Routine(1, "Morning brief", "Brief me", 7, 0, days, enabled, command = command, everyMinutes = every, at = at)

    @Test fun repeatRuleFollowsTheDays() {
        assertEquals("FREQ=DAILY", RoutineCalendar.rrule(r(127)))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", RoutineCalendar.rrule(r(31)))
        assertEquals("FREQ=WEEKLY;BYDAY=SA,SU", RoutineCalendar.rrule(r(96)))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", RoutineCalendar.rrule(r(1 or 4 or 16)))
        assertNull("once", RoutineCalendar.rrule(r(0)))
        assertNull("a dated one-off", RoutineCalendar.rrule(r(0, at = 1_800_000_000_000L)))
    }

    @Test fun onlyPromptRoutinesThatAreOnGoOnTheCalendar() {
        assertTrue(RoutineCalendar.wanted(r()))
        assertTrue(RoutineCalendar.wanted(r(0, at = 1_800_000_000_000L)))
        assertFalse("paused", RoutineCalendar.wanted(r(enabled = false)))
        assertFalse("shell job", RoutineCalendar.wanted(r(command = "sh backup.sh")))
        assertFalse("every N minutes", RoutineCalendar.wanted(r(every = 30)))
    }

    @Test fun calendarEventRepeatArgument() {
        assertNull(RoutineCalendar.repeatRule(""))
        assertNull(RoutineCalendar.repeatRule("once"))
        assertEquals("FREQ=DAILY", RoutineCalendar.repeatRule("daily"))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", RoutineCalendar.repeatRule("weekdays"))
        assertEquals("FREQ=WEEKLY;BYDAY=TU,TH", RoutineCalendar.repeatRule("tue,thu"))
    }

    @Test fun googleCalendarFirstElseArgus() {
        val local = RoutineCalendar.Cal(1, "LOCAL", true)
        val gOther = RoutineCalendar.Cal(2, RoutineCalendar.GOOGLE, false)
        val gMain = RoutineCalendar.Cal(3, RoutineCalendar.GOOGLE, true)
        assertEquals(3L, RoutineCalendar.pick(listOf(local, gOther, gMain)))
        assertEquals(2L, RoutineCalendar.pick(listOf(local, gOther)))
        assertNull("no Google account on the phone: the ARGUS calendar", RoutineCalendar.pick(listOf(local)))
        assertNull(RoutineCalendar.pick(emptyList()))
    }

    @Test fun offlineRunsSayTheyAreLate() {
        assertEquals("Morning brief: you're offline, so I'll run it as soon as you're back online.", RoutineLater.offlineNote("Morning brief"))
        val p = RoutineLater.latePrompt("Brief me", 0L, java.util.TimeZone.getTimeZone("UTC"))
        assertTrue(p.startsWith("[This was due Thu 1 Jan 00:00;"))
        assertTrue(p.endsWith("\nBrief me"))
    }

    /** The routine keeps its event id across saves, and the receiver queues offline runs instead of failing them. */
    @Test fun wiring() {
        val o = r().copy(eventId = 42).toJson()
        assertEquals(42L, o.getLong("event_id"))
        val root = listOf(File("."), File("..")).first { File(it, "app").isDirectory }
        val recv = File(root, "app/src/main/java/com/blaze/assistant/routine/RoutineReceiver.kt").readText()
        assertTrue(recv.contains("RoutineLater.queue(ctx, id, System.currentTimeMillis())"))
        val routines = File(root, "app/src/main/java/com/blaze/assistant/routine/Routines.kt").readText()
        assertTrue(routines.contains("save(cur() + r); calendar(r.id); return r"))
        assertTrue(routines.contains("uncalendar(ev)"))
    }
}
