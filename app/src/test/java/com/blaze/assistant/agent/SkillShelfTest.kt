package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-20 skill shelf (docs/SKILL-SHELF.md): the model sees every skill that's on, one line each, and picks itself.
 * Nothing in here reads the user's message.
 */
class SkillShelfTest {
    private fun c(n: String, d: String, src: String = "bundled", on: Boolean = true, body: String = "1. …") = SkillMatch.Cand(n, d, emptyList(), body, src, on)

    private val root = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }
    private val bundled = root.listFiles()!!.filter { it.isDirectory }.mapNotNull { SkillMatch.fromDir(it, "bundled", true) }

    @Test fun summaryIsTheFirstThingTheDescriptionSays() {
        assertEquals("Call a person or number, with the same lookup rules as messaging",
            SkillShelf.summary("Workflow. Use when: Call a person or number, with the same lookup rules as messaging. Use for \"call my brother\"."))
        // a short title sentence gives way to the sentence after it
        assertTrue(SkillShelf.summary("Price Scout. Find what something costs right now near the user (groceries, phones).").startsWith("Find what something costs"))
        assertEquals("Research a question on the web with sources", SkillShelf.summary("Research a question on the web with sources. Use when the answer needs more."))
        val long = SkillShelf.summary("Make a presentation - pitch deck, class talk, project update or report-out - as a single self-contained HTML deck with arrows")
        assertTrue(long, long.length <= SkillShelf.LINE_CHARS + 1 && long.endsWith("\u2026") && !long.endsWith(" \u2026"))
        assertEquals("Top up airtime", SkillShelf.summary("  Top up\n airtime  "))
    }

    /** Shelf cost 4/4: a line is `name — use when …`, about 12 words, no examples; the cap is enforced in code. */
    @Test fun useWhenIsShortWithNoExamples() {
        assertEquals("use when asked to call a person or number",
            SkillShelf.useWhen("Workflow. Use when: Call a person or number, with the same lookup rules as messaging. Use for \"call my brother\"."))
        assertEquals("use when asked to make a presentation",
            SkillShelf.useWhen("Make a presentation - pitch deck, class talk, project update or report-out - as a single self-contained HTML deck with arrows"))
        assertEquals("use when Argus writes and changes code in the terminal",
            SkillShelf.useWhen("How Argus writes and changes code in the terminal, in repos and in apps, and how it builds a working thing."))
        assertEquals("use when asked to explain tax forms step by step", SkillShelf.useWhen("Explains tax forms step by step."))
        assertEquals("a cut never splits \"step by step\"", "use when asked to explain ZRA tax forms", SkillShelf.useWhen("Explains ZRA tax forms step by step."))
        assertEquals("use when asked for my tutor", SkillShelf.useWhen("My tutor"))
        assertEquals("use when turning a goal into a plan", SkillShelf.useWhen("Turning a goal into a plan."))
        // examples go: parentheses, quotes, e.g. / such as lists
        val ex = SkillShelf.useWhen("Send invoices to clients (PDF, DOCX) e.g. monthly retainers, such as \"Acme\"; then chase late payers")
        assertEquals("use when asked to send invoices to clients", ex)
        // a long one is cut at a phrase boundary, never on a joining word
        val long = SkillShelf.useWhen("Advises smallholder farmers on crops, planting windows, fertiliser and pests for their district, with the extension source.")
        assertEquals("use when asked to advise smallholder farmers on crops", long)
        for (d in listOf("A bundled helper skill with a fairly long description number 4 for testing the budget and more words",
                "x", "(just an example)", "Checks https://example.com/" + "a/".repeat(80), "word ".repeat(100))) {
            val u = SkillShelf.useWhen(d)
            assertTrue(u, u.split(' ').size <= SkillShelf.LINE_WORDS && u.length <= SkillShelf.LINE_CHARS && u.startsWith("use when "))
            assertFalse(u, Regex("\\b(and|or|the|a|to|of|for|with)$").containsMatchIn(u))
        }
        assertEquals("", SkillShelf.useWhen(""))
        // the same text, the same bytes
        assertEquals(SkillShelf.useWhen("Draft emails and messages - replies, follow-ups - in the user's voice."),
            SkillShelf.useWhen("Draft emails and messages - replies, follow-ups - in the user's voice."))
    }

    @Test fun ownSkillsFirstThenTheLibraryAlphabetical() {
        val e = SkillShelf.entries(listOf(c("zeta", "Z"), c("alpha", "A"), c("my-report", "Mine", "user"), c("airtime-topup", "Top up", "learned",
            body = "steps\n(run_skill airtime-topup replays the recorded phone steps)"), c("off-one", "Off", on = false), c("Alpha", "dup")))
        assertEquals(listOf("airtime-topup", "my-report", "alpha", "zeta"), e.map { it.name })
        assertTrue(e[0].replay); assertFalse(e[1].replay)
        assertFalse("switched-off skills aren't on the shelf", e.any { it.name == "off-one" })
    }

    @Test fun theBlockSaysWhatIsThereNotWhatToThink() {
        val b = SkillShelf.block(listOf(c("tutor", "Teach a topic step by step, then quiz the user"), c("airtime-topup", "Top up airtime", "learned", body = "x run_skill y")))
        assertTrue(b, b.contains("- airtime-topup \u2014 use when asked to top up airtime [replay]\n- tutor \u2014 use when asked to teach a topic step by step"))
        assertTrue(b.contains("use_skill") && b.contains("run_skill") && b.contains("find_skill"))
        // MODEL-FIRST: no absolutes, no shouting, no refusal wording
        assertFalse(Regex("\\b(always|never|must|only)\\b", RegexOption.IGNORE_CASE).containsMatchIn(SkillShelf.HEAD))
        assertFalse(Regex("\\b[A-Z]{4,}\\b").containsMatchIn(SkillShelf.HEAD.replace("SKILLS", "")))
        assertFalse(b.contains("refused") || b.contains("unrelated"))
        assertEquals("", SkillShelf.block(emptyList()))
        assertEquals("", SkillShelf.block(listOf(c("x", "y", on = false))))
    }

    @Test fun sameSkillsSameBytes() {
        val a = listOf(c("b", "B"), c("a", "A"), c("m", "M", "user"))
        assertEquals(SkillShelf.block(a), SkillShelf.block(a.reversed()))
        assertEquals(SkillShelf.promptBlock(a), SkillShelf.promptBlock(a.reversed().reversed()))
        // round 5 step 1 (cache): a name clash resolves the same way whatever order the skills were read in
        val clash = listOf(c("tutor", "Bundled tutor"), c("tutor", "My tutor", "user"))
        assertEquals(SkillShelf.block(clash), SkillShelf.block(clash.reversed()))
        assertTrue(SkillShelf.block(clash).contains("- tutor \u2014 use when asked for my tutor"))
        assertEquals(SkillShelf.promptBlock(clash), SkillShelf.promptBlock(clash.reversed()))
    }

    @Test fun overBudgetKeepsTheUsersOwnAndSaysHowManyMore() {
        val lib = (1..200).map { c("lib-%03d".format(it), "A bundled helper skill with a fairly long description number $it for testing the budget") }
        val mine = (1..5).map { c("mine-$it", "My own skill $it", "user") }
        val b = SkillShelf.block(lib + mine, budget = 2_000)
        mine.forEach { assertTrue(it.name, b.contains("- ${it.name} \u2014 use when ")) }
        val shown = b.lines().count { it.startsWith("- lib-") }
        assertTrue(b, b.trimEnd().endsWith("+${200 - shown} more bundled skills: find_skill lists them."))
        assertTrue(b.length <= 2_000)
    }

    /** Round 5 review: the user's own skills get the room first, but can't push the shelf past its budget; the cut is counted. */
    @Test fun aBigPersonalLibraryStaysInsideTheBudget() {
        val mine = (1..300).map { c("mine-%03d".format(it), "My own skill number $it with a description long enough to matter", "user") }
        val lib = (1..20).map { c("lib-$it", "Bundled $it") }
        val b = SkillShelf.block(mine + lib, budget = 3_000)
        assertTrue("${b.length}", b.length <= 3_000)
        val ownShown = b.lines().count { it.startsWith("- mine-") }
        val libShown = b.lines().count { it.startsWith("- lib-") }
        assertTrue(ownShown > 0)
        assertTrue(b, b.trimEnd().endsWith("+${300 - ownShown + 20 - libShown} more skills (${300 - ownShown} yours, ${20 - libShown} bundled): find_skill lists them."))
        // the real library plus a large personal one still fits
        assertTrue(SkillShelf.block(bundled + mine).length <= SkillShelf.BUDGET_CHARS)
        // shelf cost 4/4: the user's own lines get the same cap as the library's, so the count above stays honest
        val wordy = (1..40).map { c("own-%02d".format(it), "My own very wordy skill number $it (with examples, e.g. this one) that goes on and on about " +
            "everything it does for the weekly store report, the monthly accounts and the yearly tax return", "user") }
        SkillShelf.block(wordy + lib).lines().filter { it.startsWith("- ") }.forEach { l ->
            assertTrue(l, l.substringAfter(" \u2014 ").removeSuffix(" [replay]").split(' ').size <= SkillShelf.LINE_WORDS)
            assertFalse(l, l.contains("(") || l.contains("e.g"))
        }
    }

    @Test fun theRealLibraryFitsWithEverySkillListed() {
        val b = SkillShelf.block(bundled)
        println("Shelf: ${bundled.size} skills, ${b.length} chars")
        assertTrue("${b.length}", b.length <= SkillShelf.BUDGET_CHARS)
        assertFalse(b.contains("more bundled skills"))
        bundled.forEach { assertTrue(it.name, b.contains("- ${it.name} \u2014 use when ")) }
        // every line is `name — use when …`, about 12 words, and says something (no leftover "Workflow." / "Use when:", no examples)
        b.lines().filter { it.startsWith("- ") }.forEach { l ->
            val s = l.substringAfter(" \u2014 ")
            assertTrue(l, s.length >= 12 && s.startsWith("use when ") && !s.contains("Workflow") && !s.contains("Use when"))
            assertTrue(l, s.split(' ').size <= SkillShelf.LINE_WORDS && l.removePrefix("- ").replace(" \u2014 ", " ").split(' ').size <= 12)
            assertFalse(l, l.contains("(") || l.contains("\"") || l.contains("e.g"))
        }
    }

    /** The shelf lists learned skills, so use_skill reads them; a read that found nothing isn't counted as read. */
    @Test fun learnedSkillsReadWithUseSkill() {
        val s = Learning.Skill("airtime-topup", "Top up Airtel airtime", "user asks for airtime", "1. Dial *778#\n2. Pick 1",
            org.json.JSONArray().put(org.json.JSONObject()), 3, 0L)
        val t = SkillPacks.learnedText(s)
        assertTrue(t, t.startsWith("# airtime-topup (learned)") && t.contains("When: user asks for airtime") && t.contains("1. Dial *778#"))
        assertTrue(t.endsWith("run_skill airtime-topup replays the recorded phone steps."))
        assertFalse(SkillPacks.learnedText(s.copy(replay = null)).contains("run_skill"))
        // Round 5 (What Argus is holding): a read counts only for a skill that really exists and is on, by its shelf name.
        val cands = listOf(c("tutor", "Teach"), c("airtime-topup", "Top up", "learned"), c("off-one", "Off", on = false))
        assertEquals("tutor", SkillShelf.known(" Tutor ", cands))
        assertEquals("airtime-topup", SkillShelf.known("airtime-topup", cands))
        assertEquals(null, SkillShelf.known("made-up", cands))
        assertEquals(null, SkillShelf.known("off-one", cands))
        assertEquals(null, SkillShelf.known("", cands))
        // the tool descriptions state what the tools do; nothing refuses
        val desc = SkillPacks.tools().joinToString(" ") { it.getJSONObject("function").getString("description") }
        assertFalse(desc, desc.contains("refused") || desc.contains("looked up automatically"))
    }
}
