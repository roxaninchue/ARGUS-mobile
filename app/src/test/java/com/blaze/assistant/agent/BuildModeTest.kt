package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildModeTest {
    @Test fun makeRequestsForAnArtifactTurnItOn() {
        listOf("make me a landing page for my bakery", "build an app that tracks my study hours", "design a logo for Blaze Foods",
            "create a pitch deck for the investor meeting", "write a python script that renames my photos", "make a snake game",
            "can you build me a portfolio website?", "create a CV for me").forEach { assertTrue(it, BuildMode.wants(it)) }
    }

    @Test fun oneToolActionsAndQuestionsDoNot() {
        listOf("make a call to mum", "create a reminder for 6pm", "set an alarm for 7", "create an event tomorrow at 3", "hi",
            "what is a landing page?", "make sense of this", "add a contact for Sam", "create a playlist of chill songs").forEach { assertFalse(it, BuildMode.wants(it)) }
    }

    @Test fun theBlockAsksForPlanDesignSystemReviewAndOneImprovement() {
        val b = BuildMode.block("make me a landing page for my bakery")
        listOf("PLAN first", "design system", "type scale", "spacing", "colour tokens", "states", "motion", "SELF-REVIEW", "ONE improvement round", "production-grade")
            .forEach { assertTrue(it, b.contains(it, ignoreCase = true)) }
        assertEquals("", BuildMode.block("set an alarm for 7"))
        assertTrue(BuildMode.block("make me a quick simple page").contains("quick or simple"))
    }

    @Test fun buildTurnsGetMoreStepsAndTime() {
        assertEquals(60, BuildMode.steps(30, true)); assertEquals(30, BuildMode.steps(30, false))
        assertEquals(20 * 60_000L, TurnDeadline.turnMs(false, 30, false, build = true))
        assertEquals(TurnDeadline.TURN_MS, TurnDeadline.turnMs(false, 30, false))
        assertEquals(60 * 60_000L, TurnDeadline.turnMs(true, 60, true, build = true))
    }
}
