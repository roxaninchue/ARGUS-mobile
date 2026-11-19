package com.blaze.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.53.6: big models get the full tool descriptions; only models named small are slimmed. */
class ToolSlimTest {
    @Test fun onlySmallModelsAreSlimmed() {
        for (big in listOf("google/gemini-2.5-pro", "minimax/minimax-m2:free", "qwen/qwen3-235b-a22b:free", "moonshotai/kimi-k2", "deepseek-v3.1:free",
            "meta-llama/llama-3.3-70b-instruct", "openai/gpt-oss-120b", "glm-4.6"))
            assertFalse(big, ToolCalls.smallModel("gateway", big))
        for (small in listOf("gpt-4o-mini", "llama-3.1-8b-instant", "gemini-2.0-flash-lite", "mistral-small", "qwen2.5-7b-instruct:free", "phi-3-mini", "gemini-1.5-flash-8b"))
            assertTrue(small, ToolCalls.smallModel("gateway", small))
    }
}
