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

    /** Round 3: the tools a turn gets don't depend on the message's words; the model loads the rest. */
    @Test fun noWordGuessedToolGroups() {
        val src = agentSrc()
        for (gone in listOf("ToolRouter.guess(", "LearnedRoutes.predict(", "ToolRouter.fallback(", "TurnContext.scheduleAsk(userNow)",
                "PromptProfile.simpleGroupOk(", "ExecRoute.route(lu"))
            assertFalse(gone, src.contains(gone))
        assertTrue(src.contains("addAll(ToolPick.ALWAYS_GROUPS); addAll(ToolRouter.usedIn(history))"))
    }

    @Test fun noShoutingRulesInTheChatPrompt() {
        val shouts = listOf("Answer EVERY user message", "never leave one out", "answer every one of them")
        val files = listOf("RapidMessages.kt", "ChatBurst.kt", "ChatLane.kt").map { File(root(), "app/src/main/java/com/blaze/assistant/agent/$it").readText() }
        for (f in files) for (s in shouts) assertFalse(s, f.lines().any { s in it && !it.trimStart().startsWith("*") && !it.trimStart().startsWith("//") })
    }

    /**
     * Agent-first persona (owner, 16 Nov 2026: "I've completed my CIT exam" got only a congratulation). The fix is what the
     * model is given, not a detector: a short core that says ARGUS is an agent and how it works, the [context] block with
     * today and what's next, and calendar / reminder tools that say what they find, change and delete.
     */
    @Test fun agentFirstPersonaIsShortCalmAndShared() {
        val core = AgentPersona.CORE
        assertTrue("persona core stays small: ${core.length}", core.length <= 2_000)
        assertTrue(core.contains("not a chatbot") && core.contains("Check before you assume") && core.contains("act or offer"))
        val absolutes = Regex("\\b(always|never|must|every)\\b", RegexOption.IGNORE_CASE)
        for (p in listOf(core, AgentPersona.PICKED.substringBefore(Duo.IDENTITY_RULES)))
            assertFalse("no absolutes or shouting in the persona: " + absolutes.find(p)?.value, absolutes.containsMatchIn(p))
        assertFalse("no capitals-only orders", Regex("\\b[A-Z]{4,}\\b").containsMatchIn(core.replace("ARGUS", "")))
        // The default personality's first system message is this core, byte for byte, and new enough to win over a cached remote copy.
        val asset = org.json.JSONObject(File(root(), "app/src/main/assets/persona-v1.json").readText())
        assertTrue(asset.getString("system") == core.replace(com.blaze.assistant.BrandName.NAME, "Argus"))
        assertTrue(SharedPersona.VersionCmp.compare(asset.getString("version"), "1.0.7") > 0)
        // A picked personality gets the same agent role and way of working.
        assertTrue(agentSrc().contains("s(\"assistant\", AgentPersona.PICKED)"))
        assertTrue(AgentPersona.PICKED.contains(AgentPersona.HOW) && AgentPersona.PICKED.contains(Duo.IDENTITY_RULES))
        // Gone: the lines that read news as small talk and asked for stock offers instead of a look at the user's plans.
        for (gone in listOf("small talk need no tools", "offer 1 to 3 concrete next steps", "NOT A CHAT BUDDY"))
            assertFalse(gone, (HarnessPrompt.CORE + TurnContext.RULE + Duo.ASSISTANT_RULES + core).contains(gone))
        // Nothing detects the words: no regex or keyword list in the persona.
        val src = File(root(), "app/src/main/java/com/blaze/assistant/agent/AgentPersona.kt").readText()
        assertFalse(src.contains("Regex(") || src.contains("containsMatchIn") || src.contains(".contains("))
    }

    @Test fun theModelSeesTodayAndNextEveryTurn() {
        val zone = java.time.ZoneId.of("Africa/Lusaka")
        val now = java.time.ZonedDateTime.of(2026, 11, 16, 22, 59, 0, 0, zone)
        val exam = ScheduleDay.Entry("CIT exam", "event", ScheduleDay.Rule.Span(now.withHour(9).withMinute(0).toInstant(), now.withHour(11).withMinute(0).toInstant(), false))
        val next = ScheduleDay.Entry("NET class", "reminder", ScheduleDay.Rule.Once(now.plusDays(2).withHour(8).withMinute(0).toInstant()))
        val b = TurnContext.block(TurnContext.Inputs(now, schedule = listOf(exam, next), dates = ScheduleDay.datesAsked("I've completed my CIT exam", now.toLocalDate())))
        assertTrue(b, b.contains("now: Mon 16 Nov 2026 22:59") && b.contains("CIT exam") && b.contains("(passed)") && b.contains(TurnContext.NEXT) && b.contains("NET class"))
        // It rides on every main-chat request, whatever the message says.
        assertTrue(agentSrc().contains("\"\\n\" + TurnContextSource.block(lastUserText())"))
        // The tools say what they can do with a finished or stale entry.
        fun main(f: String) = File(root(), "app/src/main/java/com/blaze/assistant/$f").readText()
        assertTrue(main("phone/Skills.kt").contains("before move_event reschedules it or delete_event removes it"))
        assertTrue(main("routine/Routines.kt").contains("Delete a reminder or routine by name, for example one whose event has happened"))
        assertTrue(main("routine/Routines.kt").contains("before delete_routine removes it or process (by its ID) pauses or reschedules it"))
        assertTrue(main("phone/PimEdit.kt").contains("Delete a phone calendar event, for example one that is cancelled"))
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

    /**
     * 18 Nov 2026 text assist: the user's words are never rewritten unseen. The improved text goes into the chat box
     * (composerFill) and the send that follows reads the box; nothing rewrites the text between the tap and the model.
     */
    @Test fun textAssistNeverSendsUnseenText() {
        val screen = File(root(), "app/src/main/java/com/blaze/assistant/ui/ChatScreen.kt").readText()
        val block = screen.substring(screen.indexOf("TextAssist.rewrite(t, lines)")).let { it.substring(0, it.indexOf("return@send")) }
        assertTrue(block.contains("input = com.blaze.assistant.ui.argus.kit.composerFill(better)"))
        assertFalse("assist never sends by itself", block.contains("send("))
        assertFalse("Agent.kt never calls text assist", agentSrc().contains("TextAssist."))
    }
}
