package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.55.10 (Finn, 23 Nov): Stop mid-reply closed the app. Stop's clean-up never throws, and the turn is cancelled first. */
class StopSafeTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    @Test fun aFailingStepIsDroppedAndTheRestRuns() {
        val ran = mutableListOf<String>()
        assertFalse(StopSafe.run("a") { ran += "a"; throw IllegalStateException("boom") })
        assertTrue(StopSafe.run("b") { ran += "b" })
        assertEquals(listOf("a", "b"), ran)
    }

    @Test fun cancellationIsPassedOn() {
        var passed = false
        try { StopSafe.run("c") { throw kotlinx.coroutines.CancellationException("stop") } }
        catch (_: kotlinx.coroutines.CancellationException) { passed = true }
        assertTrue(passed)
    }

    @Test fun stopCancelsFirstAndGuardsEveryStep() {
        val a = src("agent/Agent.kt")
        val body = a.substringAfter("        fun stop() {").substringBefore("\n        }")
        assertTrue(body.indexOf("job?.cancel()") in 0 until body.indexOf("settleInbox()"))
        assertFalse(body.lines().any { it.trim().let { l -> l.isNotEmpty() && !l.startsWith("//") && !l.startsWith("StopSafe.run(") && l != "mood = Mood.IDLE" } })
        assertTrue(a.contains("StopSafe.run(\"stop tidy\")"))
        assertTrue(a.contains("if (!stopped) StopSafe.run(\"learn\") { learnAfterTurn() }"))
    }
}
