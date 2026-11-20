package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 5 specialist shelf (docs/SKILL-SHELF.md §12): the model sees every specialist, one line each, and decides itself
 * whether to delegate. Step 2: the list lives in delegate's description (ids as an enum), delegate rides every turn, and
 * the prompt keeps a one-line pointer. Nothing in here reads the user's message.
 */
class SpecialistShelfTest {
    private fun mine(id: String, name: String, prompt: String, madeBy: String = Specialists.USER) =
        Specialists.Spec(id, name, prompt, setOf("web"), custom = true, madeBy = madeBy)
    private val own = listOf(mine("zz-farm", "Farming advisor", "Advises smallholder farmers on crops and pests. Returns sources."),
        mine("aa-tax", "Tax helper", "Explains ZRA tax forms step by step.", Specialists.BLAZE))

    private fun fn(specs: List<Specialists.Spec>) = Specialists.tool(specs).getJSONObject("function")
    private fun enumOf(specs: List<Specialists.Spec>) = fn(specs).getJSONObject("parameters").getJSONObject("properties").getJSONObject("tasks")
        .getJSONObject("items").getJSONObject("properties").getJSONObject("specialist").getJSONArray("enum").let { e -> (0 until e.length()).map { e.getString(it) } }

    @Test fun everySpecialistOneLineInDelegateBuiltInsFirst() {
        val d = fn(Specialists.builtIn + own).getString("description")
        for (s in Specialists.builtIn) assertTrue(s.id, d.contains("- ${s.id} \u2014 use when "))
        assertTrue(d, d.contains("- aa-tax \u2014 use when asked to explain ZRA tax forms (yours)"))
        assertTrue(d, d.contains("- zz-farm \u2014 use when asked to advise smallholder farmers on crops (yours)"))
        // shelf cost 4/4: about 12 words a line, built-ins and the user's own alike, no examples
        d.lines().filter { it.startsWith("- ") }.forEach { l ->
            assertTrue(l, l.substringAfter(" \u2014 ").removeSuffix(" (yours)").split(' ').size <= SkillShelf.LINE_WORDS)
            assertFalse(l, l.contains("e.g") || l.contains("\""))
        }
        // built-ins in their fixed order, then the user's own by id; the enum says the same
        val ids = d.lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ").substringBefore(' ') }
        assertEquals(Specialists.builtIn.map { it.id } + listOf("aa-tax", "zz-farm"), ids)
        assertEquals(ids, enumOf(Specialists.builtIn + own))
        assertFalse("built-ins aren't marked as the user's", d.lines().first { it.startsWith("- researcher ") }.contains("(yours)"))
        assertFalse("no shouting", Regex("\\b(NOT|NEVER|ALWAYS|MUST)\\b").containsMatchIn(d))
    }

    @Test fun delegateRidesEveryTurn() {
        assertTrue("delegate" in ToolPick.ESSENTIAL)
        assertTrue("delegate" in ToolPick.core(simple = false, lean = true) && "delegate" in ToolPick.core(simple = true))
    }

    @Test fun thePromptKeepsOneLine() {
        val p = SpecialistShelf.pointer(Specialists.builtIn + own)
        assertEquals(1, p.trim().lines().size)
        assertTrue(p, p.contains("SPECIALISTS: 12 (10 built in, 2 yours)") && p.contains("delegate") && p.contains("create_specialist"))
        assertFalse("no list in the prompt", p.contains("- researcher"))
        assertEquals("", SpecialistShelf.pointer(emptyList()))
        assertEquals(p, SpecialistShelf.promptBlock(Specialists.builtIn + own))
    }

    @Test fun sameSetSameBytes() {
        val a = Specialists.tool(Specialists.builtIn + own).toString()
        assertEquals(a, Specialists.tool((own + Specialists.builtIn).reversed()).toString())
        assertEquals(SpecialistShelf.pointer(Specialists.builtIn + own), SpecialistShelf.pointer(own.reversed() + Specialists.builtIn))
    }

    @Test fun factsNotOrders() {
        val p = SpecialistShelf.pointer(Specialists.builtIn)
        assertFalse(Regex("\\b(always|never|must|only)\\b", RegexOption.IGNORE_CASE).containsMatchIn(p + SpecialistShelf.LIST_HEAD))
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
