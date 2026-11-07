package com.blazeinchue.argus.agent


import com.blazeinchue.argus.BrandName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.50.0 (owner's screenshot 2026-11-01 22:51: "Skills: android-app-builder, reverse-engine…" under a casual message).
 * Skills load only for a clear task, only on strong matches, and never more than [SkillIntent.MAX_NAMES].
 */
class SkillIntentTest {
    @org.junit.Before fun drainLeaks() = LeakedMainLaunches.drain()

    private val root = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }
    private val bundled = root.listFiles()!!.filter { it.isDirectory }.mapNotNull { SkillMatch.fromDir(it, "bundled", true) }

    private val casual = listOf(
        "need to make improvements to ${BrandName.NAME} agent(an Android ai agent I'm making). I'll try and sleep soon!",
        "can't sleep honestly", "hi!", "thanks, that's great", "I'm working on my android app tonight", "ok",
        "my essay was due today lol", "I've been reading about reverse engineering, it's fascinating")

    @Test fun casualMessagesLoadNoSkills() {
        casual.forEach { m ->
            assertFalse(m, SkillIntent.taskIntent(m))
            assertTrue(m, SkillMatch.selectForTurn(m, bundled).isEmpty)
        }
    }

    @Test fun clearTasksStillGetTheirSkill() {
        val tasks = mapOf(
            "make flashcards on photosynthesis for my biology test" to "study-flashcards",
            "build me an android app that tracks my water intake" to "android-app-builder",
            "can you reverse engineer this APK and tell me what it does?" to "reverse-engineer-anything",
            "write a landing page for my bakery" to "landing-page-builder")
        tasks.forEach { (m, skill) ->
            assertTrue(m, SkillIntent.taskIntent(m))
            val r = SkillMatch.selectForTurn(m, bundled)
            assertTrue("$m -> ${r.names}", skill in r.names)
            assertTrue("$m -> ${r.names}", r.names.size <= SkillIntent.MAX_NAMES)
        }
    }

    @Test fun neverMoreThanThreeSkills() {
        val m = "please build a web design landing page with slides, diagrams, data analysis charts, pixel art and a study plan"
        val r = SkillMatch.selectForTurn(m, bundled)
        assertTrue(r.names.toString(), r.names.size <= SkillIntent.MAX_NAMES)
    }

    @Test fun intentRules() {
        listOf("help me plan my week", "how do I write a cover letter", "please summarise this", "translate this to Bemba",
            "I want you to research free AI APIs", "flashcards on photosynthesis", "use the tutor skill").forEach { assertTrue(it, SkillIntent.taskIntent(it)) }
        listOf("I'll try and sleep soon!", "lol", "yes", "I'm tired today").forEach { assertFalse(it, SkillIntent.taskIntent(it)) }
        assertEquals(3, SkillIntent.MAX_NAMES)
    }
}
