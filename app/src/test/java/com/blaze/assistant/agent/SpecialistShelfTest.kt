package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 5 specialist shelf (docs/SKILL-SHELF.md §12): the model sees every specialist, one line each, and decides itself
 * whether to delegate. Nothing in here reads the user's message.
 */
class SpecialistShelfTest {
    private fun mine(id: String, name: String, prompt: String, madeBy: String = Specialists.USER) =
        Specialists.Spec(id, name, prompt, setOf("web"), custom = true, madeBy = madeBy)

    @Test fun everySpecialistOneLineBuiltInsFirst() {
        val own = listOf(mine("zz-farm", "Farming advisor", "Advises smallholder farmers on crops and pests. Returns sources."),
            mine("aa-tax", "Tax helper", "Explains ZRA tax forms step by step.", Specialists.BLAZE))
        val b = SpecialistShelf.block(Specialists.builtIn + own)
        for (s in Specialists.builtIn) assertTrue(s.id, b.contains("- ${s.id} (${s.name}): "))
        assertTrue(b, b.contains("- aa-tax (Tax helper): Explains ZRA tax forms step by step (yours)"))
        assertTrue(b.contains("- zz-farm (Farming advisor): Advises smallholder farmers on crops and pests (yours)"))
        // built-ins in their fixed order, then the user's own by id
        val ids = b.lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ").substringBefore(' ') }
        assertEquals(Specialists.builtIn.map { it.id } + listOf("aa-tax", "zz-farm"), ids)
        assertFalse("built-ins aren't marked as the user's", b.lines().first { it.startsWith("- researcher ") }.contains("(yours)"))
    }

    @Test fun saysWhereDelegateIs() {
        val b = SpecialistShelf.block(Specialists.builtIn)
        assertTrue(b.contains("delegate") && b.contains("load_tools extras") && b.contains("create_specialist"))
        assertEquals("", SpecialistShelf.block(emptyList()))
    }

    @Test fun sameSetSameBytes() {
        val own = listOf(mine("b", "Bee", "Does b things."), mine("a", "Ay", "Does a things."))
        assertEquals(SpecialistShelf.block(Specialists.builtIn + own), SpecialistShelf.block(Specialists.builtIn + own.reversed()))
        assertEquals(SpecialistShelf.promptBlock(Specialists.builtIn + own), SpecialistShelf.block(Specialists.builtIn + own))
        assertEquals(SpecialistShelf.block(Specialists.builtIn + own), SpecialistShelf.block((own + Specialists.builtIn).reversed()))
    }

    @Test fun factsNotOrders() {
        assertFalse(Regex("\\b(always|never|must|only)\\b", RegexOption.IGNORE_CASE).containsMatchIn(SpecialistShelf.HEAD))
        assertFalse(Regex("\\b[A-Z]{4,}\\b").containsMatchIn(SpecialistShelf.HEAD.replace("SPECIALISTS", "")))
    }

    /** The word router is gone for good. */
    @Test fun noMatcherLeft() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        assertFalse(File(root, "com/blaze/assistant/agent/SpecialistMatch.kt").exists())
        assertFalse(File(root, "com/blaze/assistant/agent/SkillIntent.kt").exists())
        val shelf = File(root, "com/blaze/assistant/agent/SpecialistShelf.kt").readText()
        assertFalse(shelf.contains("userText") || shelf.contains("query"))
    }
}
