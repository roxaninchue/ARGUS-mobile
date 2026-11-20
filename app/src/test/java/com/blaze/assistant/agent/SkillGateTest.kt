package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v0.30.4 fast actions: the test phone trace's first step was "Skills: android-app-builder, wf-find-a-person,
 * wf-do-it-on-screen, blaze-workflow-builder, git-pr-workflow" (22 s) for "stop GitHub opening links in the GitHub
 * app": picked by word overlap ("github", "app", "open") and put in the prompt before the first call.
 */
class SkillGateTest {
    private val root = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }
    private val bundled = root.listFiles()!!.filter { it.isDirectory }.mapNotNull { SkillMatch.fromDir(it, "bundled", true) }
    private val ask = "stop GitHub opening links in the GitHub app"

    @Test fun theOldMatchLoadedUnrelatedSkills() {
        val old = SkillMatch.match(ask, bundled)
        val chars = SkillMatch.block(old).length
        println("Before: ${old.names} in full ${old.full.map { it.name }}, $chars chars in the prompt")
        assertTrue(old.full.isNotEmpty())
    }

    /** 2026-11-20 skill shelf: no per-turn pick for a device action; the user's recorded fix is on the shelf to replay. */
    @Test fun aPlainDeviceActionSeesTheLearnedFixOnTheShelf() {
        assertTrue(FastActions.plainDevice(ask))
        val learned = SkillMatch.Cand("github-links-off", "Turn off GitHub opening links in its app", listOf("github", "links", "open by default"),
            "1. open_settings open_by_default GitHub 2. tap Open supported links\n(run_skill github-links-off replays the recorded phone steps)", "learned", true)
        val shelf = SkillShelf.block(bundled + learned)
        assertTrue(shelf, shelf.contains("- github-links-off: Turn off GitHub opening links in its app [replay]"))
        assertTrue("the user's own skill comes first", shelf.indexOf("github-links-off") < shelf.indexOf("android-app-builder"))
        assertFalse("no skill bodies are pasted in", shelf.contains("open_settings open_by_default"))
    }

    @Test fun perTurnBodiesNeedAStrongMatch() {
        // Real requests still get their skill in full.
        val f = SkillMatch.match("make me flashcards on photosynthesis and quiz me", bundled, fullMin = SkillMatch.FULL_MIN, fullWords = SkillMatch.FULL_WORDS)
        assertEquals("study-flashcards", f.full.first().name)
        val c = SkillMatch.match("fix this kotlin build error in my repo and open a pull request", bundled, fullMin = SkillMatch.FULL_MIN, fullWords = SkillMatch.FULL_WORDS)
        assertTrue(c.full.map { it.name }.toString(), c.full.any { it.name in setOf("blaze-coding-workflow", "debugging", "git-pr-workflow") })
        // One shared word is not enough for a body in full.
        val weak = SkillMatch.match("is github down?", bundled, fullMin = SkillMatch.FULL_MIN, fullWords = SkillMatch.FULL_WORDS)
        assertFalse(weak.full.map { it.name }.toString(), weak.full.any { it.name == "android-app-builder" })
    }
}
