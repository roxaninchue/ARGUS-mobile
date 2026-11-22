package com.blaze.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.55.2 (Finn 18:08: Home said "19:30 Alarm" while the Clock app said "No alarms"). */
class ProcessesAlarmTest {
    private val now = 1_795_000_000_000L
    private val h = 3_600_000L

    @Test fun anAlarmTheClockDoesNotHaveIsGone() {
        assertTrue(Processes.goneFromClock(now + h, false, now, null, now - 10 * h))           // no alarm at all on the phone
        assertTrue(Processes.goneFromClock(now + h, false, now, now + 2 * h, now - 10 * h))    // the next one rings later
    }

    @Test fun aRealOneStays() {
        assertFalse(Processes.goneFromClock(now + h, false, now, now + h, now - 10 * h))       // it is the next alarm
        assertFalse(Processes.goneFromClock(now + 2 * h, false, now, now + h, now - 10 * h))   // an earlier one hides it
        assertFalse(Processes.goneFromClock(now + h, true, now, null, now - 10 * h))           // repeating: left alone
        assertFalse(Processes.goneFromClock(now + h, false, now, null, now - 30_000L))         // just set: Clock may not have it yet
        assertFalse(Processes.goneFromClock(now - h, false, now, null, now - 10 * h))          // already rang
    }
}
