package com.blaze.assistant.memory

import com.blaze.assistant.agent.MemoryGate
import com.blaze.assistant.memory.AutoLearn.Decision
import com.blaze.assistant.memory.AutoLearn.Learned
import com.blaze.assistant.memory.AutoLearn.Proposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-AG-05: A-team manager.js autoLearn cases (v2.16c) + Argus placement, proposals and the Saved card. */
class LearnDetectTest {
    private fun kinds(t: String) = AutoLearn.detect(t).items.map { it.kind + ":" + it.text }

    @Test fun ateamV216cCase() {
        // app/tests/v216c.test.js: "rule and preference saved by the app"
        assertEquals(listOf("rule:Always reply in English", "preference:I prefer short answers"), kinds("Always reply in English. I prefer short answers. Fix the build."))
    }

    @Test fun detectorsLikeManagerJs() {
        assertEquals(listOf("rule:Never suggest crypto"), kinds("never suggest crypto!"))
        assertEquals(listOf("rule:From now on send me drafts first"), kinds("From now on send me drafts first."))
        assertEquals(listOf("rule:Please don't call after 9pm"), kinds("Please don't call after 9pm"))
        assertEquals(listOf("rule:I want you to always ask before booking"), kinds("I want you to always ask before booking"))   // RULE_ANY
        assertEquals(listOf("note:My passport expires in 2028"), kinds("Remember that my passport expires in 2028"))
        assertEquals(listOf("rule:Always use metric units"), kinds("remember to always use metric units"))
        assertEquals(listOf("name:Call me Finn"), kinds("call me Finn"))
        assertEquals(listOf("name:My name is Finn Banda"), kinds("My name is Finn Banda."))
    }

    @Test fun sisterIsNotInTheAteamList() {
        assertTrue(kinds("My sister is Gloria").isEmpty())
        assertEquals(listOf("person:My wife is Mutale"), kinds("My wife is Mutale"))
        assertEquals(listOf("preference:I live in Lusaka"), kinds("I live in Lusaka"))
        assertEquals(listOf("preference:I'm vegetarian"), kinds("I'm vegetarian."))
    }

    @Test fun skipsQuestionsNeverMindAndSecrets() {
        assertTrue(kinds("Do you always reply in English?").isEmpty())
        assertTrue(kinds("never mind").isEmpty())
        assertTrue(kinds("Fix the build and run the tests").isEmpty())
        assertTrue(kinds("ok").isEmpty())
        val d = AutoLearn.detect("Remember my password: hunter2222. Always reply in English.")
        assertEquals(listOf("Always reply in English"), d.items.map { it.text }); assertEquals(1, d.secrets)
        assertEquals(1, AutoLearn.detect("remember my card 4111 1111 1111 1111").secrets)
        assertEquals(4, AutoLearn.detect("Always a b. Never c d. Always e f. Never g h. Always i j.").items.size)
    }

    @Test fun placement() {
        fun at(k: String, t: String) = AutoLearn.place(Learned(k, t))
        assertEquals("user:core", at("rule", "Never book red-eyes").scope); assertTrue(at("rule", "Never book red-eyes").soulRule)
        val allergy = at("preference", "I'm allergic to peanuts")
        assertEquals("user:core", allergy.scope); assertTrue(allergy.requirement); assertEquals(setOf("share:food"), allergy.shareTags)
        assertTrue(at("rule", "Never order me anything with pork, I don't eat pork").requirement)
        assertEquals("user:core", at("name", "Call me Finn").scope)
        assertEquals("user:people", at("person", "My wife is Mutale").scope)
        assertEquals("user:places", at("preference", "I live in Lusaka").scope)
        assertEquals("user:preferences", at("preference", "I prefer short answers").scope)
        assertEquals("user:notes", at("note", "My passport expires in 2028").scope)
    }

    @Test fun proposalsAcceptRules() {
        assertTrue(AutoLearn.decide(Proposal("Allergic to peanuts", "user:core", "pantry", stated = false)) is Decision.Accept)  // requirement: auto even inferred
        assertEquals(Decision.Accept("user:people", "stated by the user"), AutoLearn.decide(Proposal("Gloria is his sister", "user:people", "circle", true)))
        assertTrue(AutoLearn.decide(Proposal("Prefers aisle seats", "user:preferences", "atlas", false)) is Decision.Ask)
        assertTrue(AutoLearn.decide(Proposal("Netflix renews on the 3rd", "money", "ledger", true)) is Decision.Reject)
        assertTrue(AutoLearn.decide(Proposal("token = abcdef123456", "user:core", "inbox", true)) is Decision.Reject)
        assertTrue(AutoLearn.decide(Proposal("Is tired right now", "user:core", "pulse", true)) is Decision.Reject)
        assertTrue(AutoLearn.decide(Proposal("The user asked me to fix CI", "user:notes", "forge", true)) is Decision.Reject)
        assertNull(AutoLearn.acceptable("Never book flights today"))
    }

    private class FakeSink : AutoLearn.Sink {
        val lines = LinkedHashMap<String, Pair<Long, String>>(); val rules = ArrayList<Pair<String, String>>(); var next = 1L
        override fun save(text: String, p: AutoLearn.Placement, stated: Boolean, byBot: String): Long =
            if (lines.containsKey(text.lowercase())) 0 else (next++).also { lines[text.lowercase()] = it to p.scope }
        override fun addRule(botId: String, rule: String): Boolean = if (rules.any { it.second.equals(rule, true) }) false else rules.add(botId to rule)
    }

    @Test fun theFlowSavesOnceShowsACardAndAddsSoulRules() {
        val sink = FakeSink()
        val o = AutoLearn.run("Always reply in English. I prefer short answers. Fix the build.", "atlas", isManager = false, ruleBot = "atlas", sink = sink)
        assertEquals(listOf("Always reply in English", "I prefer short answers"), o.saved.map { it.text })
        assertEquals(listOf("atlas"), o.rulesTo); assertEquals(listOf("atlas" to "Always reply in English"), sink.rules)
        assertEquals("Saved to memory: Always reply in English (+1)", o.card!!.title)
        assertEquals(listOf(1L, 2L), o.card!!.undo)
        // "a repeat is not saved twice"
        val again = AutoLearn.run("Always reply in English.", "atlas", false, "atlas", sink)
        assertTrue(again.saved.isEmpty()); assertNull(again.card); assertEquals(2, sink.lines.size); assertTrue(again.rulesTo.isEmpty())
        // a secret: dropped, one notice
        val s = AutoLearn.run("Remember my pin: 48213. Never text after 10pm.", "manager", true, "manager", sink)
        assertEquals(listOf(AutoLearn.SECRET_NOTICE), s.notices); assertEquals(listOf("Never text after 10pm"), s.saved.map { it.text })
        val allergy = AutoLearn.run("I'm allergic to shellfish.", "pantry", false, "pantry", sink)
        assertEquals("user:core", allergy.saved.single().scope); assertTrue(allergy.card!!.lines.single().endsWith("always applied"))
    }

    @Test fun gateKeepsSmallTalkOut() {
        assertFalse(MemoryGate.learnable("ok cool thanks"))
        assertFalse(MemoryGate.learnable("what's on today?"))
        assertTrue(MemoryGate.learnable("Always reply in English"))
        assertTrue(MemoryGate.learnable("thanks! I'm vegetarian by the way"))
        assertTrue(AutoLearn.NARROW_RULE.contains("never turn \"usually\" into \"always\"", ignoreCase = true))
    }
}
