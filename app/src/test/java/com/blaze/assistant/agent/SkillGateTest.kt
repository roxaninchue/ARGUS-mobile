package com.blaze.assistant.agent

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

    /** 2026-11-20 skill shelf: no per-turn pick for a device action; the user's recorded fix is on the shelf to replay. */
    @Test fun aPlainDeviceActionSeesTheLearnedFixOnTheShelf() {
        val learned = SkillMatch.Cand("github-links-off", "Turn off GitHub opening links in its app", listOf("github", "links", "open by default"),
            "1. open_settings open_by_default GitHub 2. tap Open supported links\n(run_skill github-links-off replays the recorded phone steps)", "learned", true)
        val shelf = SkillShelf.block(bundled + learned)
        assertTrue(shelf, shelf.contains("- github-links-off: Turn off GitHub opening links in its app [replay]"))
        assertTrue("the user's own skill comes first", shelf.indexOf("github-links-off") < shelf.indexOf("android-app-builder"))
        assertFalse("no skill bodies are pasted in", shelf.contains("open_settings open_by_default"))
    }

    /** Round 5: nothing pastes skill bodies by word overlap any more; the shelf is the same text whatever the request. */
    @Test fun noBodiesAndNoWordPick() {
        val shelf = SkillShelf.block(bundled)
        assertFalse("android-app-builder's body isn't pasted", shelf.contains(bundled.first { it.name == "android-app-builder" }.body.take(200)))
        assertTrue(SkillMatch::class.java.declaredMethods.none { it.name == "match" })
        println("Shelf for \"$ask\": ${shelf.length} chars, the same for every request")
    }
}
