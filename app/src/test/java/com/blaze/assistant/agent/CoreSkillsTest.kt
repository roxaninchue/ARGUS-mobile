package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** v0.24.3 core skills + specialists: always present and pinned, per-turn matching, disabled exclusion, Learn routing, workflow save, specialist matching. */
class CoreSkillsTest {
    private val root = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }
    private fun md(n: String) = File(root, "$n/SKILL.md").readText()
    private fun cand(n: String, on: Boolean = true) = SkillMatch.fromDir(File(root, n), "bundled", on)!!
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.replace("\r\n", "\n").toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------------- core skills always present ----------------

    @Test fun everyCoreSkillShipsAndIsIndexed() {
        assertEquals(6, CoreSkills.NAMES.size)
        val idx = JSONObject(File(root, "index.json").readText()).getJSONArray("skills")
        val byName = (0 until idx.length()).map { idx.getJSONObject(it) }.associateBy { it.getString("name") }
        for (n in CoreSkills.NAMES) {
            val text = md(n)
            assertTrue("$n front matter", text.contains("name: $n") && text.contains("core: true"))
            assertTrue("$n pinned", CoreSkills.pinned(text).size >= 4)
            assertEquals("$n sha in index.json", sha(text), byName[n]?.getString("sha256"))
            assertTrue(CoreSkills.isCore(n)); assertTrue(CoreSkills.isCore(n.uppercase()))
        }
        assertFalse(CoreSkills.isCore("deep-research"))
    }

    @Test fun pinnedBlockHasAllSixWithinBudget() {
        val mds = CoreSkills.NAMES.associateWith { md(it) }
        val b = CoreSkills.block(mds)
        assertTrue(b.length <= CoreSkills.BUDGET_CHARS)
        for (n in CoreSkills.NAMES) assertTrue("$n in block", b.contains("[$n]"))
        assertTrue(b.contains("CORE SKILLS") && b.contains("Answer") || b.contains("answer"))
        assertFalse("not trimmed at the real budget", b.contains("trimmed"))
        // Order follows NAMES.
        val pos = CoreSkills.NAMES.map { b.indexOf("[$it]") }
        assertEquals(pos.sorted(), pos)
        // The loader path gives the same text, and a tiny budget still keeps the first rule of each that fits.
        CoreSkills.reset(); CoreSkills.loader = { mds }
        assertEquals(b, CoreSkills.promptBlock()); CoreSkills.reset(); CoreSkills.loader = null
        val small = CoreSkills.block(mds, budget = 1_500)
        assertTrue(small.length <= 1_500 + 80); assertTrue(small.contains("trimmed")); assertTrue(small.contains("[blaze-voice]"))
        assertEquals("", CoreSkills.block(emptyMap()))
    }

    @Test fun pinnedParsing() {
        val text = "---\nname: x\n---\n# X\n\n## Pinned\n- one\n- two\n  continued\n\n## Other\n- not this\n"
        assertEquals(listOf("one", "two continued"), CoreSkills.pinned(text))
        assertEquals(emptyList<String>(), CoreSkills.pinned("# no pinned\n- a"))
    }

    @Test fun voiceAndTaskRulesSayWhatLukeAsked() {
        val v = md("blaze-voice").lowercase(); val t = md("blaze-task-workflow").lowercase(); val vw = md("blaze-views")
        assertTrue(v.contains("answer") && v.contains("contractions") && v.contains("take a side") && v.contains("ai"))
        assertTrue(t.contains("verify") || t.contains("check it really happened")); assertTrue(t.contains("two failed loops")); assertTrue(t.contains("confirm before spending"))
        for (view in listOf("Learn", "Design", "Schedule", "Memory", "Gallery", "Projects", "Skills", "Browser", "Archive")) assertTrue(view, vw.contains("| $view |"))
        assertTrue(vw.contains("flashcards") && vw.contains("open_view view=learn") && vw.contains("save_workflow"))
    }

    // ---------------- on the shelf (round 5: no per-turn word match) ----------------

    @Test fun coreSkillsAreOnTheShelfAndDisabledOnesArent() {
        val all = (CoreSkills.NAMES + listOf("study-flashcards", "tutor", "debugging", "deep-research", "email-drafting")).map { cand(it) }
        val shelf = SkillShelf.block(all)
        for (n in listOf("blaze-coding-workflow", "blaze-workflow-builder", "blaze-specialists")) assertTrue(n, shelf.contains("- $n \u2014 "))
        val off = SkillShelf.block(listOf(cand("study-flashcards", on = false), cand("tutor")))
        assertFalse(off.contains("study-flashcards")); assertTrue(off.contains("- tutor \u2014 "))
        // a specialist's packs leave switched-off skills out too
        val tutor = Specialists.builtIn.first { it.id == "tutor" }
        val brief = Specialists.brief(tutor, listOf(cand("study-flashcards", on = false), cand("tutor")))
        assertFalse(brief.contains("### study-flashcards")); assertTrue(brief.contains("### tutor"))
    }

    // ---------------- views: flashcards go to Learn ----------------

    @Test fun flashcardsRouteToLearn() {
        assertEquals("learn", ViewRouter.route("make flashcards on cell biology")?.id)
        assertEquals("learn", ViewRouter.route("quiz me on the OSI model")?.id)
        val textCards = "Q: What is a cell?\nA: The basic unit of life\nQ: What holds DNA?\nA: The nucleus\nQ: Powerhouse?\nA: Mitochondria"
        assertEquals(ViewRouter.Fix.CONVERT, ViewRouter.shouldConvert("make flashcards on cell biology", textCards, false, false))
        assertEquals(3, ViewRouter.cardsFrom(textCards).size)
        // The skills view takes workflows too, and save_workflow backs a Skills claim.
        assertEquals("skills", ViewRouter.route("save this as a workflow")?.id)
        assertTrue("save_workflow" in ViewRouter.byId("skills")!!.tools)
        assertTrue(ClaimGuard.backs(ViewRouter.byId("skills")!!, ClaimGuard.Call("save_workflow")))
    }

    // ---------------- workflow builder ----------------

    @Test fun workflowSavesAsAShelfSkill() {
        val text = SkillPacks.workflowMd("Airtime Top Up", "top up airtime, buy airtime",
            "1. Open the MTN app with open_app\n2. Tap Airtime\n- Enter the amount the user gave", "Balance went up\nSMS confirmation received",
            listOf("airtime", "mtn"), tested = false)
        assertTrue(text.startsWith("---\nname: airtime-top-up\n"))
        assertTrue(text.contains("workflow: true") && text.contains("tested: false"))
        assertTrue(text.contains("## Steps\n1. Open the MTN app with open_app\n2. Tap Airtime\n3. Enter the amount the user gave"))
        assertTrue(text.contains("## Checks\n- Balance went up\n- SMS confirmation received"))
        assertTrue(text.contains("Not tested yet"))
        val dir = Files.createTempDirectory("wf").toFile().resolve("airtime-top-up").apply { mkdirs() }
        File(dir, "SKILL.md").writeText(text)
        val c = SkillMatch.fromDir(dir, "user", true)!!
        assertEquals("airtime-top-up", c.name)
        assertTrue(c.keywords.toString(), "airtime" in c.keywords && "mtn" in c.keywords)
        assertTrue(SkillShelf.block(listOf(c, cand("deep-research"))).contains("- airtime-top-up \u2014 "))
        assertTrue(SkillPacks.workflowMd("x", "y", "1. a", "", emptyList(), tested = true).let { it.contains("tested: true") && !it.contains("Not tested") })
        assertTrue("save_workflow" in SkillPacks.names)
        assertTrue(SkillPacks.tools().any { it.getJSONObject("function").getString("name") == "save_workflow" })
    }

    // ---------------- specialists ----------------

    @Test fun specialistPacksShipAndBrief() {
        // Round 5: nothing picks a specialist from the words (SpecialistMatch is gone); each brings its packs to its own runs.
        for (s in Specialists.builtIn) for (k in Specialists.skillsFor(s)) assertTrue("${s.id} → $k ships", File(root, "$k/SKILL.md").isFile)
        val tutor = Specialists.builtIn.first { it.id == "tutor" }
        val b = Specialists.brief(tutor, listOf(cand("study-flashcards"), cand("tutor")))
        assertTrue(b.contains("### study-flashcards") && b.contains("### tutor"))
        assertFalse("no order wording", b.contains("follow them"))
        // Delegated specialists only get tools from their groups (browser and study are real groups now).
        assertEquals("browser", Specialists.group("browser_open")); assertEquals("study", Specialists.group("flashcards"))
    }
}
