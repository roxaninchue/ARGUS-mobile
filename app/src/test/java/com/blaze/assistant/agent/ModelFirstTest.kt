package com.blaze.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-16 owner rule (docs/MODEL-FIRST.md): build the system around the model; never pre-decide what the user meant with
 * keyword rules, and never bend the model with orders that fight each other. Seen on the owner's phone: "Let's login to
 * Instagram" / "Oh wait" / "Google first" — "Google first" matched a "google <topic>" regex, the lookup lane searched
 * "first" + "Instagram" and posted a GitHub repo; a prompt line ("Answer EVERY user message… never leave one out") made the
 * model re-answer the cancelled message. These scans keep those pre-routers from coming back in the main chat.
 */
class ModelFirstTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "docs").isDirectory && File(it, "app").isDirectory }
    private fun agentSrc(): String = File(root(), "app/src/main/java/com/blaze/assistant/agent/Agent.kt").readText()

    @Test fun busyMessagesGoToTheModelNotAKeywordRouter() {
        val src = agentSrc()
        val send = src.substring(src.indexOf("        fun send(text: String, byVoice: Boolean = false")).let { it.substring(0, it.indexOf("\n        }\n")) }
        assertFalse("send() must not classify a mid-turn message by keywords", send.contains("BusyRoute.route("))
        assertTrue(send.contains("ChatBurstLive.follow(this, shown, byVoice, untrusted)"))
    }

    @Test fun noKeywordTriggeredLookupLaneInTheChat() {
        val src = agentSrc()
        assertFalse("the model decides when to search", src.contains("LookupLane.isLookup(shown)"))
        assertFalse(src.contains("runLookup(SearchRoute.query("))
    }

    /** Round 2 (owner: "yes remove them all"): the other pre-routers in front of the model are gone too. */
    @Test fun noOtherPreRoutersInTheTurn() {
        val src = agentSrc()
        for (gone in listOf("Media.directPrompt(", "MemoryIntent.isListRequest(", "SearchRoute.webNote(", "searches.check(",
                "ChatLane.fits(", "ToolTruth.toolShaped(", "Duo.kindFor(", "private suspend fun runLookup("))
            assertFalse(gone, src.contains(gone))
        assertTrue("fast-lane rules only when no model is reachable", src.contains("if (offline && single && attachments.isEmpty() && !routine"))
        assertTrue(src.contains("var lite = false"))
    }

    @Test fun noShoutingRulesInTheChatPrompt() {
        val shouts = listOf("Answer EVERY user message", "never leave one out", "answer every one of them")
        val files = listOf("RapidMessages.kt", "ChatBurst.kt", "ChatLane.kt").map { File(root(), "app/src/main/java/com/blaze/assistant/agent/$it").readText() }
        for (f in files) for (s in shouts) assertFalse(s, f.lines().any { s in it && !it.trimStart().startsWith("*") && !it.trimStart().startsWith("//") })
    }

    @Test fun theRuleIsWrittenDownForOtherAgents() {
        val doc = File(root(), "docs/MODEL-FIRST.md")
        assertTrue(doc.isFile)
        val t = doc.readText()
        assertTrue(t.contains("build the system around the model", ignoreCase = true))
        assertTrue(File(root(), "AGENTS.md").readText().contains("docs/MODEL-FIRST.md"))
        assertTrue(File(root(), "overhaul.md").readText().contains("docs/MODEL-FIRST.md"))
        assertTrue(File(root(), "docs/AGENTS.md").readText().contains("MODEL-FIRST.md"))
    }
}
