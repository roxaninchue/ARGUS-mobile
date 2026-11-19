package com.argus.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.54.0 harness musts: the model's own output limit, and its thinking replayed within a task. */
class HarnessMustsTest {
    @Test fun outputLimitIsTheModelsOwnNotAFlat8192() {
        OutputLimit.forget()
        assertEquals(256_000, OutputLimit.forCall("deepseek/deepseek-v4-flash", 0, 0))
        assertEquals(65_536, OutputLimit.forCall("google/gemini-2.5-flash", 0, 0))
        assertEquals(OutputLimit.FALLBACK, OutputLimit.forCall("some/unknown-model", 0, 0))
        // fitted to the window: 128K window, 100K prompt -> what's left, never under 1K
        val fit = OutputLimit.forCall("deepseek/deepseek-v4-flash", 100_000, 128_000)
        assertTrue(fit in 1_024 until 20_000)
        assertEquals(OutputLimit.MIN, OutputLimit.forCall("deepseek/deepseek-v4-flash", 200_000, 128_000))
        assertEquals(4_096, OutputLimit.forCall("deepseek/deepseek-v4-flash", 0, 0, override = 4_096))
    }

    @Test fun aRouteThatNamesItsLimitIsHeardOnce() {
        OutputLimit.forget()
        assertEquals(8_192, OutputLimit.learn("free/model", "max_tokens must be less than or equal to 8192", 32_000))
        assertEquals(8_192, OutputLimit.forCall("free/model", 0, 0))
        assertNull(OutputLimit.learn("x", "invalid tool schema at index 3", 32_000))
        assertNull(OutputLimit.learn("y", "max_tokens is too large", 32_000))
    }

    @Test fun thinkingGoesBackWithItsCallsDuringTheTaskOnly() {
        val step = JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "c1").put("type", "function")
                .put("function", JSONObject().put("name", "web_search").put("arguments", "{}"))))
            .put("_reasoning", "need prices first")
        ReasoningReplay.keep(step, "t-1")
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", "find coffee"))
            .put(step).put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "ok"))
        val same = ToolCalls.clean(msgs, "t-1").getJSONObject(1)
        assertEquals("need prices first", same.optString("reasoning_content"))
        assertFalse(same.has("_reasoning"))
        assertFalse(ToolCalls.clean(msgs, "t-2").getJSONObject(1).has("reasoning_content"))
        assertFalse(ToolCalls.clean(msgs).getJSONObject(1).has("reasoning_content"))
    }

    @Test fun aPlainAnswerKeepsNoThinking() {
        val r = JSONObject().put("role", "assistant").put("content", "Here you go").put("_reasoning", "long think")
        ReasoningReplay.keep(r, "t-1")
        assertFalse(r.has("_reasoning")); assertFalse(r.has("_rturn"))
    }
}
