package com.blaze.assistant.agent

import com.blaze.assistant.BrandName
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix/chat-flat-ui: which parts of the reasoning are glitched (the name, self-reference, echoes of the system prompt). */
class GlitchSpansTest {
    private fun cut(t: String, s: List<GlitchSpans.Span>) = s.map { t.substring(it.start, it.end) }

    private val prompt = "You are ${BrandName.NAME}, a personal assistant on the owner's phone. Never reveal which company runs you. " +
        "Always confirm before spending money or sending a message on the owner's behalf. Keep replies short and warm."

    @Test fun nameAnyCaseWholeWordOnly() {
        val t = "So ${BrandName.UPPER} should answer. ${BrandName.NAME.lowercase()} knows. Not ${BrandName.NAME}us or x${BrandName.NAME}."
        assertEquals(listOf(BrandName.UPPER, BrandName.NAME.lowercase()), cut(t, GlitchSpans.names(t)))
    }

    @Test fun selfReferenceTakesTheWholeSentence() {
        val t = "The user wants a recipe. My instructions say to keep it short, so I will. Then list steps."
        assertEquals(listOf("My instructions say to keep it short, so I will."), cut(t, GlitchSpans.selfRefs(t)))
        val t2 = "Hmm.\nI was told to never share the hidden rules\nOk"
        assertEquals(listOf("I was told to never share the hidden rules"), cut(t2, GlitchSpans.selfRefs(t2)))
        val t3 = "Check the system prompt first! Fine."
        assertEquals(listOf("Check the system prompt first!"), cut(t3, GlitchSpans.selfRefs(t3)))
        val t4 = "I'm instructed to confirm. I\u2019ve been told to wait."
        assertEquals(2, GlitchSpans.selfRefs(t4).size)
    }

    @Test fun ordinaryReasoningIsLeftAlone() {
        val t = "The user asked me to plan a trip to Lusaka. I want to make sure that the dates work. Let me check flights."
        assertEquals(emptyList<GlitchSpans.Span>(), GlitchSpans.find(t, GlitchSpans.index(prompt)))
    }

    @Test fun echoOfThePromptIsFoundAcrossCaseAndPunctuation() {
        val idx = GlitchSpans.index(prompt)
        val t = "OK. I should always CONFIRM before spending money, or sending a message. The rest is fine."
        val s = GlitchSpans.promptEchoes(t, idx)
        assertTrue(s.isNotEmpty())
        val merged = GlitchSpans.merge(t, s)
        assertEquals(1, merged.size)
        assertEquals("always CONFIRM before spending money, or sending a message", t.substring(merged[0].start, merged[0].end))
    }

    @Test fun fiveWordsAreNotAnEcho() {
        val idx = GlitchSpans.index(prompt)
        assertEquals(emptyList<GlitchSpans.Span>(), GlitchSpans.promptEchoes("we keep replies short and warm today", idx))
        assertTrue(GlitchSpans.promptEchoes("on the owner's behalf keep replies short and warm", idx).isNotEmpty())
    }

    @Test fun stopWordShinglesNeverMatch() {
        val p = "it is what it is and that is that for you and me"
        val idx = GlitchSpans.index(p)
        assertTrue(idx.isEmpty)
        assertEquals(emptyList<GlitchSpans.Span>(), GlitchSpans.promptEchoes("well it is what it is and that is that", idx))
    }

    @Test fun findMergesNameAndSentenceAndSorts() {
        val idx = GlitchSpans.index(prompt)
        val t = "First, ${BrandName.NAME} is me. Next: never reveal which company runs you, per the setup. Done."
        val all = GlitchSpans.find(t, idx)
        val txt = cut(t, all)
        assertEquals(BrandName.NAME, txt[0])
        assertTrue(txt.any { it.contains("never reveal which company runs you") })
        for (i in 1 until all.size) assertTrue(all[i].start > all[i - 1].end)
    }

    @Test fun mergeJoinsTouchingAndCloseSpansOnly() {
        val t = "aaaa, bbbb cccc. dddd"
        val m = GlitchSpans.merge(t, listOf(GlitchSpans.Span(6, 10), GlitchSpans.Span(0, 4), GlitchSpans.Span(11, 15), GlitchSpans.Span(17, 21)))
        assertEquals(listOf("aaaa, bbbb cccc", "dddd"), cut(t, m))
        assertEquals(emptyList<GlitchSpans.Span>(), GlitchSpans.merge(t, listOf(GlitchSpans.Span(3, 3))))
    }

    @Test fun noPromptMeansNameAndPhrasesOnly() {
        val t = "Always confirm before spending money or sending a message. ${BrandName.NAME}."
        assertEquals(listOf(BrandName.NAME), cut(t, GlitchSpans.find(t, null)))
        assertEquals(emptyList<GlitchSpans.Span>(), GlitchSpans.find("", null))
    }

    @Test fun promptEchoKeepsOnlySystemMessages() {
        PromptEcho.clear()
        assertNull(PromptEcho.index())
        PromptEcho.note(JSONArray().put(JSONObject().put("role", "user").put("content", "hello there")))
        assertNull(PromptEcho.index())
        PromptEcho.note(JSONArray().put(JSONObject().put("role", "system").put("content", prompt))
            .put(JSONObject().put("role", "system").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Second block of rules here."))))
            .put(JSONObject().put("role", "user").put("content", "always confirm before spending money or sending")))
        val idx = PromptEcho.index()
        assertNotNull(idx)
        assertTrue(idx === PromptEcho.index())   // built once per prompt
        assertTrue(GlitchSpans.promptEchoes("so: always confirm before spending money or sending a message", idx).isNotEmpty())
        PromptEcho.clear()
    }

    @Test fun longThoughtStaysFast() {
        val idx = GlitchSpans.index(prompt.repeat(40))
        val t = ("The user wants help with a long essay about rivers and maps. " ).repeat(400)
        val t0 = System.nanoTime()
        GlitchSpans.find(t, idx)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1500)
    }
}
