package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.53.1: the full tool set reaches every pick; a refusal hands the turn on (ToolWire, ToolTruth.refusal, ModelPick.refused). */
class ToolWireTest {
    private fun user(t: String) = JSONArray().put(JSONObject().put("role", "system").put("content", "sys")).put(JSONObject().put("role", "user").put("content", t))

    @Test fun actionTurnsKeepTheirTools() {
        listOf("what time is it in Tokyo?", "wake me at 6", "who won the game last night", "is it going to rain later",
            "how much is a PS5 in Lusaka", "add dentist friday 3pm").forEach { assertFalse(it, ToolWire.mayTrim(user(it), "auto", false)) }
    }

    @Test fun smallTalkMayBeTrimmedOnAutoOnly() {
        assertTrue(ToolWire.mayTrim(user("hey argus!"), "auto", false))
        assertTrue(ToolWire.mayTrim(user("thanks"), "auto", false))
        assertTrue(ToolWire.mayTrim(user("hi there"), "auto", false))
        assertFalse(ToolWire.mayTrim(user("hi there, what's the weather"), "auto", false))
        assertFalse("a hand pick always gets its tools", ToolWire.mayTrim(user("hey argus!"), "auto", true))
        assertFalse("a named tool is the app's decision", ToolWire.mayTrim(user("thanks"), "required", false))
    }

    @Test fun notTheUsersTurnNoTrim() {
        val m = user("hi").put(JSONObject().put("role", "tool").put("content", "ok"))
        assertFalse(ToolWire.mayTrim(m, "auto", false))
        assertFalse(ToolWire.mayTrim(user("[Check: call the tool now]"), "auto", false))
        assertFalse(ToolWire.mayTrim(JSONArray(), "auto", false))
        assertFalse("a named-tool object is the app's decision", ToolWire.mayTrim(user("thanks"), org.json.JSONObject().put("type", "function"), false))
    }

    @Test fun partsContentIsRead() {
        val m = JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "thanks"))))
        assertEquals("thanks", ToolWire.lastUser(m))
        assertTrue(ToolWire.mayTrim(m, "auto", false))
    }

    @Test fun clockAppAndNoLiveDataAreRefusals() {
        listOf("I can't set alarms, but you can use your clock app.", "Please open your Clock app and add an alarm for 6:00.",
            "I don't have access to real-time information, so check a news site.", "I can't tell the current time.",
            "I'm unable to search the web right now.", "I don't have internet access.", "I can't set an alarm for you.").forEach { assertTrue(it, ToolTruth.refusal(it)) }
        listOf("Done: alarm set for 6:00 tomorrow in your Clock app.", "I can't find that contact; which one?", "I can't send it until you approve the card.",
            "It's 21:40 in Lusaka.", "I couldn't schedule it for a past date; did you mean next Friday?").forEach { assertFalse(it, ToolTruth.refusal(it)) }
    }

    @Test fun refusedRouteIsUnpinnedAndAvoided() {
        val tm = TurnModel("t1")
        tm.served("apinex/qwen3-32b")
        assertTrue(ModelPick.refused(true, tm))
        assertEquals("", tm.pin)
        assertEquals(listOf("apinex/qwen3-32b"), tm.shun)
        tm.served("groq/kimi-k2")
        assertEquals("groq/kimi-k2", tm.pin)
        assertFalse(ModelPick.refused(false, tm))
        assertEquals(listOf("apinex/qwen3-32b"), tm.shun)
    }

    @Test fun refusedHandPickGoesDown() {
        ModelPick.clearDown()
        ModelPick.set("apinex/qwen3-32b")
        try {
            ModelPick.refused(true, null, now = 1_000L)
            assertTrue(ModelPick.isDown("apinex/qwen3-32b", listAt = 0L, now = 2_000L))
        } finally { ModelPick.auto(); ModelPick.clearDown() }
    }
}
