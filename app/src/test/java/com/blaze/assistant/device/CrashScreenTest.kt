package com.blaze.assistant.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashScreenTest {
    @Test fun onlyAQuickCrashOnRecordOpensTheScreen() {
        assertTrue(CrashLog.safeScreen(lastLaunchUnfinished = true, pending = true))
        assertFalse(CrashLog.safeScreen(lastLaunchUnfinished = true, pending = false))   // swiped away early, no crash
        assertFalse(CrashLog.safeScreen(lastLaunchUnfinished = false, pending = true))   // first crash: the normal dialog
    }

    @Test fun showsTheNewestCrashFromItsLine() {
        val log = "2026-11-24 10:00:00  QUIET x: y\n2026-11-24 10:01:00  CRASH on main: old\n2026-11-24 10:02:00  SLOW 9000 ms: z\n" +
            "2026-11-24 10:03:00  CRASH on main: java.lang.IllegalStateException: boom\n\tat a.b.C(C.kt:1)\n"
        val out = CrashLog.lastCrash(log)
        assertTrue(out.startsWith("2026-11-24 10:03:00  CRASH on main: java.lang.IllegalStateException: boom"))
        assertTrue(out.contains("at a.b.C(C.kt:1)"))
        assertFalse(out.contains("old"))
    }

    @Test fun noCrashLineGivesTheTail() {
        assertEquals("abc", CrashLog.lastCrash("xyzabc", max = 3))
        assertEquals("", CrashLog.lastCrash(""))
    }
}
