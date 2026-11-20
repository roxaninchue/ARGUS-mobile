package com.blaze.assistant.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 20 Nov report (Galaxy A12, 0.54.0): "CRASH (previous run, crash)" with nothing else. */
class CrashMemoryTest {
    private fun src() = listOf(File("src/main/java/com/blaze/assistant/device/CrashLog.kt"), File("app/src/main/java/com/blaze/assistant/device/CrashLog.kt")).first { it.exists() }.readText()

    @Test fun exitLineSaysMemoryAndWhetherOnScreen() {
        val line = CrashLog.exitLine(5, null, null, null, emptyList(), pssKb = 412 * 1024, rssKb = 530 * 1024, background = true)!!
        assertTrue(line.startsWith("CRASH (previous run, crash)"))
        assertTrue(line.contains("memory at exit: pss 412 MB, rss 530 MB"))
        assertTrue(line.contains("in the background"))
        val bare = CrashLog.exitLine(5, null, null, null, emptyList())!!
        assertFalse(bare.contains("memory at exit")); assertFalse(bare.contains("on screen"))
    }

    @Test fun crashHandlerFreesTheReserveFirst() {
        val s = src()
        assertTrue(s.contains("reserve = ByteArray(256 * 1024)"))
        val handler = s.substringAfter("Thread.setDefaultUncaughtExceptionHandler { t, e ->").substringBefore("writeNow(")
        assertTrue(handler.contains("freeReserve()"))
        assertTrue(s.contains("e is OutOfMemoryError) { freeReserve(); writeNow("))
        assertTrue(s.contains("x.pss, x.rss"))
    }
}
