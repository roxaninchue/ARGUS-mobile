package com.blaze.assistant.agent

import com.blaze.assistant.ui.Views
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** v0.23.0 views + skills: the views exist, the deck tool, skill matching and disabled-skill exclusion. */
class ViewsSkillsTest {

    // ---------------- views (2026-11-21: the view shelf replaced view routing; ViewShelfTest) ----------------

    @Test fun everyShelfViewExists() {
        for (s in ViewShelf.specs) {
            // Archive is search only since v0.24.0 (no chat list screen); the Stage is the chat itself (0.46.0).
            if (s.id == "archive" || s.id == "stage") continue
            assertTrue("no view ${s.id}", Views.find(s.id) != null)
        }
        assertEquals("projects", Views.find("project")?.id)
    }

    @Test fun theDeckToolMakesALearnDeck() {
        Flashcards.fileOverride = File(Files.createTempDirectory("learn").toFile(), "cards.json"); Flashcards.reset()
        assertTrue(Flashcards.run(JSONObject("""{"action":"add","deck":"Sums","cards":[{"front":"4+4?","back":"8"}]}""")).startsWith("Added 1 card"))
        assertEquals("Sums", Flashcards.decks().single().name)
        Flashcards.fileOverride = null; Flashcards.reset()
    }

    // ---------------- skill matching ----------------

    private fun cand(name: String, desc: String, tags: List<String> = emptyList(), body: String = "# $name\n1. step\n2. step", source: String = "bundled", on: Boolean = true) =
        SkillMatch.Cand(name, desc, tags, body, source, on)

    private val lib = listOf(
        cand("study-flashcards", "Make and study flashcards in the Learn view. Use for quiz me, test me, revise, exam prep.",
            listOf("flashcards", "quiz", "quiz me", "study", "revise", "exam")),
        cand("tutor", "Teach a topic step by step, then quiz the user; turn notes into flashcards.", listOf("student", "tutoring", "flashcards", "quiz")),
        cand("landing-page-builder", "Build a one-page landing page with a hero and call to action.", listOf("web", "landing", "html")),
        cand("email-drafting", "Draft clear emails and replies.", listOf("email", "gmail")),
        cand("airtime-topup", "Top up MTN airtime with USSD", emptyList(), "1. dial *303#", source = "learned"),
    )

    /** Round 5: no word score picks a skill; the shelf lists every one that's on and the model reads the one it wants. */
    @Test fun theShelfListsEverySkillThatIsOn() {
        val shelf = SkillShelf.block(lib)
        for (c in lib) assertTrue(c.name, shelf.contains("- ${c.name} \u2014 use when "))
        val off = SkillShelf.block(lib.map { if (it.name == "study-flashcards") it.copy(on = false) else it })
        assertFalse(off.contains("study-flashcards")); assertTrue(off.contains("- tutor \u2014 "))
        assertEquals("", SkillShelf.block(lib.map { it.copy(on = false) }))
    }

    @Test fun readsSkillFoldersWithTagsAndTheShippedSkill() {
        val dir = File(Files.createTempDirectory("sk").toFile(), "my-skill").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: my-skill\ndescription: Do the thing\ntags: [alpha, beta gamma]\n---\n\n# Body\n1. go\n")
        val c = SkillMatch.fromDir(dir, "user", on = false)!!
        assertEquals("my-skill", c.name); assertEquals(listOf("alpha", "beta gamma"), c.keywords)
        assertEquals("# Body\n1. go", c.body); assertFalse(c.on)
        // The bundled study-flashcards skill points at Learn; both it and tutor are on the shelf.
        val root = listOf(File("src/main/assets/skills"), File("app/src/main/assets/skills")).first { it.isDirectory }
        val shipped = SkillMatch.fromDir(File(root, "study-flashcards"), "bundled", true)!!
        assertTrue(shipped.body.contains("flashcards") && shipped.body.contains("open_view") && shipped.body.contains("view=learn"))
        val tutor = SkillMatch.fromDir(File(root, "tutor"), "bundled", true)!!
        assertTrue(SkillShelf.block(listOf(tutor, shipped)).let { it.contains("- study-flashcards \u2014 ") && it.contains("- tutor \u2014 ") })
    }
}
