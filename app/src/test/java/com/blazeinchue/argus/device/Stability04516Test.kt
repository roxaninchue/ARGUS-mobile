package com.blazeinchue.argus.device

import com.blazeinchue.argus.agent.StreamGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.45.16 reliability: crash guard, ANR/stall log, safe start, memory trim, smoother streaming, key-change notice. */
class Stability04516Test {
    private fun src(p: String) = listOf(File("src/main/java/com/blazeinchue/argus/$p"), File("app/src/main/java/com/blazeinchue/argus/$p")).first { it.exists() }

    @Test fun anrVerdicts() {
        assertEquals(MainWatch.Verdict.OK, MainWatch.verdict(0))
        assertEquals(MainWatch.Verdict.OK, MainWatch.verdict(699))
        assertEquals(MainWatch.Verdict.STALL, MainWatch.verdict(700))
        assertEquals(MainWatch.Verdict.STALL, MainWatch.verdict(4_999))
        assertEquals(MainWatch.Verdict.ANR, MainWatch.verdict(5_000))
    }

    @Test fun safeStartAfterTwoEarlyCrashes() {
        assertFalse(StartGuard.shouldSafeStart(0)); assertFalse(StartGuard.shouldSafeStart(1))
        assertTrue(StartGuard.shouldSafeStart(2)); assertTrue(StartGuard.shouldSafeStart(7))
    }

    @Test fun failingStartStepIsSkippedNotFatal() {
        val before = StartGuard.failedSteps
        var after = false
        StartGuard.step("test-throws") { throw IllegalStateException("boom") }
        StartGuard.step("test-ok") { after = true }
        assertTrue(after)
        assertEquals(before + 1, StartGuard.failedSteps)
    }

    @Test fun memoryTrimLevels() {
        assertFalse(MemoryTrim.shouldDropCaches(5))    // RUNNING_MODERATE
        assertTrue(MemoryTrim.shouldDropCaches(10))    // RUNNING_LOW
        assertTrue(MemoryTrim.shouldDropCaches(15))    // RUNNING_CRITICAL
        assertFalse(MemoryTrim.shouldDropCaches(20))   // UI_HIDDEN: resume stays smooth
        assertTrue(MemoryTrim.shouldDropCaches(40)); assertTrue(MemoryTrim.shouldDropCaches(80))
    }

    @Test fun streamUpdatesAreSpaced() {
        assertEquals(0L, StreamGate.gapLeft(0L, 1_000L))
        assertEquals(50L, StreamGate.gapLeft(1_000L, 1_000L))
        assertEquals(20L, StreamGate.gapLeft(1_000L, 1_030L))
        assertEquals(0L, StreamGate.gapLeft(1_000L, 1_200L))
    }

    @Test fun everyStartupCallIsGuarded() {
        val body = src("ArgusApp.kt").readText().substringAfter("override fun onCreate()").substringBefore("override fun onTrimMemory")
        // Top-level statements of onCreate (8-space indent) that call into Argus must be guarded or be the crash log itself.
        val bare = body.lines().filter { it.startsWith("        ") && !it.startsWith("         ") }.map { it.trim() }
            .filter { (it.startsWith("com.blazeinchue.argus.") || it.startsWith("Agent.") || it.startsWith("Settings.")) &&
                !it.startsWith("com.blazeinchue.argus.device.") }
        assertTrue("unguarded: $bare", bare.isEmpty())
        assertTrue(body.contains("StartGuard.begin(this)") && body.contains("MainWatch.start()"))
        assertTrue(body.contains("safeStart"))
    }

    @Test fun crashLogNeverWritesOnTheCaller() {
        val s = src("device/CrashLog.kt").readText()
        assertTrue(s.contains("writer.execute"))
        assertTrue(s.contains("writeNow(\"CRASH on"))
    }

    @Test fun keyChangeNoticeNamesTheFix() {
        val k = com.blazeinchue.argus.update.Updater.KEY_CHANGED
        assertTrue(k.contains("Uninstall ${com.blazeinchue.argus.BrandName.NAME} once") && k.contains("blazeinchue.pages.dev"))
        val u = src("update/Updater.kt").readText()
        assertTrue(u.contains("State.Failed(KEY_CHANGED)"))
    }
}
