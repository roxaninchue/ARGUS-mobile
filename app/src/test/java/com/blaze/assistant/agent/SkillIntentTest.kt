package com.blaze.assistant.agent


import com.blaze.assistant.BrandName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.50.0 (owner's screenshot 2026-11-01 22:51: "Skills: android-app-builder, reverse-engine…" under a casual message).
 * SkillIntent now only gates specialists; skills are on the shelf (SkillShelf, 2026-11-20).
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
        }
    }

    /** 2026-11-20 skill shelf: skills are no longer picked by intent; every bundled skill is on the shelf for the model. */
    @Test fun everySkillIsOnTheShelfWhateverTheWords() {
        val shelf = SkillShelf.block(bundled)
        for (s in listOf("study-flashcards", "android-app-builder", "reverse-engineer-anything", "landing-page-builder", "study-planner"))
            assertTrue(s, shelf.contains("- $s: "))
    }

    @Test fun intentRules() {
        listOf("help me plan my week", "how do I write a cover letter", "please summarise this", "translate this to Bemba",
            "I want you to research free AI APIs", "flashcards on photosynthesis", "use the tutor skill").forEach { assertTrue(it, SkillIntent.taskIntent(it)) }
        listOf("I'll try and sleep soon!", "lol", "yes", "I'm tired today").forEach { assertFalse(it, SkillIntent.taskIntent(it)) }
        assertEquals(3, SkillIntent.MAX_NAMES)
    }
}
