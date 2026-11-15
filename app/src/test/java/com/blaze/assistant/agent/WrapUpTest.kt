package com.blaze.assistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WrapUpTest {
    @Test fun stopAndAnswerIsHeard() {
        for (t in listOf("I think that's plenty, just give what you found", "that's enough, tell me what you have", "Just give me what you've got",
            "stop searching and answer", "ok wrap it up", "whatever you have so far is fine"))
            assertTrue(t, WrapUp.asks(t))
        for (t in listOf("Find restaurants in Ndola", "Yea try Google maps", "give me directions to Pizza Inn", "is that enough water for a day?"))
            assertFalse(t, WrapUp.asks(t))
        val agent = listOf(java.io.File("src/main/java/com/blaze/assistant/agent/Agent.kt"), java.io.File("app/src/main/java/com/blaze/assistant/agent/Agent.kt")).first { it.exists() }.readText()
        assertTrue(agent.contains("WrapUp.HINT"))
        assertTrue(agent.contains("if (WrapUp.asks(lastUserText())) 0 else RetryGuard.MAX"))
    }
}
