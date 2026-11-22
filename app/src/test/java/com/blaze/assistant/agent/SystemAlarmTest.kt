package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.55.4: a calendar or reminder app's alarm clock is never shown as a Clock alarm (Finn 19:53, "20:00 Alarm", Clock empty). */
class SystemAlarmTest {
    private val t = 1_795_370_400_000L

    @Test fun aClockAppsAlarmStillCounts() {   // parity with 0.55.3: real Clock alarms keep showing
        assertEquals(t, SystemAlarm.decide(t, "com.sec.android.app.clockpackage", emptySet()))
        assertEquals(t, SystemAlarm.decide(t, "com.google.android.deskclock", emptySet()))
        assertEquals(t, SystemAlarm.decide(t, "com.example.myclock", setOf("com.example.myclock")))
    }

    @Test fun anotherAppsAlarmClockIsUnknownNotAnAlarm() {
        assertEquals(SystemAlarm.UNKNOWN, SystemAlarm.decide(t, "com.samsung.android.calendar", setOf("com.sec.android.app.clockpackage")))
        assertEquals(SystemAlarm.UNKNOWN, SystemAlarm.decide(t, null, emptySet()))
        assertEquals(SystemAlarm.UNKNOWN, SystemAlarm.decide(t, "", emptySet()))
    }

    @Test fun noAlarmIsNull() {
        assertNull(SystemAlarm.decide(null, "com.google.android.deskclock", emptySet()))
        assertNull(SystemAlarm.decide(0L, null, emptySet()))
    }

    @Test fun unknownNeverDeletesAnAlarmArgusSet() {
        // Processes skips goneFromClock when the next alarm is UNKNOWN; null (no alarm at all) still drops a phantom.
        val pr = src("agent/Processes.kt")
        assertTrue(pr.contains("sysNext != -1L &&"))
        assertTrue(pr.contains("if (t == SystemAlarm.UNKNOWN) return null"))
        assertTrue(pr.contains("nextSystemAlarm = { SystemAlarm.read(a0) }"))
    }

    @Test fun homeAndContextOnlyReadAlarmsThroughSystemAlarm() {
        val s = src("agent/TurnContextSource.kt")
        assertFalse("raw nextAlarmClock read is back", s.contains("?.nextAlarmClock"))
        assertTrue(s.contains("SystemAlarm.read(it)"))
        assertTrue(s.contains("if (clockNext > 0L &&"))
        val m = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.isFile }.readText()
        assertTrue(m.contains("android.intent.action.SHOW_ALARMS"))
    }

    private fun src(p: String) = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        .walkTopDown().first { it.path.endsWith(p) }.readText()
}
