package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.26.2 Desktop's bm25 ranking for find_skill / find_tool. */
class SkillIndexTest {
    private fun cand(n: String, d: String, kw: List<String> = emptyList(), on: Boolean = true) = SkillMatch.Cand(n, d, kw, "body", "user", on)
    private val cands = listOf(
        cand("airtime-topup", "Top up airtime and data bundles with USSD", listOf("airtime", "bundle")),
        cand("deep-research", "Research a question on the web with sources"),
        cand("landing-page-builder", "Build a landing page for a store or product", listOf("website")),
        cand("study-flashcards", "Make flashcards from notes and quiz the user", listOf("quiz", "revise")),
        cand("email-drafting", "Draft emails in the user's voice"),
        cand("secret-off", "Top up airtime secretly", on = false),
    ) + (1..40).map { cand("filler-skill-$it", "Unrelated helper number $it for gardening $it") }

    @Test fun nameOutranksDescription() {
        val ix = SkillMatch.indexOf(cands)
        val r = ix.search("airtime", 5)
        assertEquals("airtime-topup", r.first().first.key)
        assertFalse("disabled skills aren't indexed", r.any { it.first.key == "secret-off" })
        assertEquals("study-flashcards", ix.search("quiz me with flashcards on cells", 3).first().first.key)
        assertTrue(ix.search("hello there", 3).isEmpty())
    }

    /** find_skill still searches by bm25 (the per-turn block it used to feed is SkillShelf now). */
    @Test fun findSkillSearches() {
        val ix = SkillMatch.indexOf(cands)
        assertTrue(SkillIndex.findLine(ix, "build a landing page website for my store").contains("landing-page-builder"))
        assertTrue(SkillIndex.findLine(ix, "top up airtime").contains("airtime-topup"))
        assertTrue(SkillIndex.findLine(ix, "zzzz").startsWith("No skill"))
        assertTrue(SkillPacks.names.contains("find_skill"))
    }

    /** Round 5: the per-turn word score (match / score / block) is gone; find_skill's bm25 is the only search, run by the model. */
    @Test fun noWordScoreLeft() {
        val gone = SkillMatch::class.java.declaredMethods.map { it.name }.filter { it in setOf("match", "score", "block", "cardLine", "matchedWords", "words", "stem") }
        assertTrue(gone.toString(), gone.isEmpty())
    }
}
