package com.blaze.assistant.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.55.1: the Home setup topics are saved to memory as setup topics, not as a choice. "Interested in Study" came back as
 * "You chose to study. Roxan, your day is planned around studying" when Finn asked Argus to plan his day (2026-11-22).
 */
class InterestsLineTest {
    @Test fun setupTopicsAreNotWordedAsAChoiceOrAPlan() {
        val line = Interests.memoryLine(setOf("study"), setOf("Exams"), "")
        assertTrue(line, line.startsWith(Interests.HOME_TOPICS))
        assertTrue(line, line.contains("Study: Exams"))
        assertTrue(line, line.endsWith(Interests.NOT_A_PLAN))
        assertFalse(line, line.startsWith("Interested in"))
    }

    @Test fun nothingPickedSavesNothing() = assertEquals("", Interests.memoryLine(emptySet(), emptySet(), " "))
}
