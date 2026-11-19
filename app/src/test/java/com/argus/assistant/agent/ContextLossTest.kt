package com.argus.assistant.agent

import com.argus.assistant.agent.TaskMemory.Rec
import com.argus.assistant.agent.TaskMemory.State
import com.argus.assistant.memory.ChatTier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-19 context-loss fixes (phone screenshots: "save Finn … flashcard fix" answered as the NET103 flashcards task;
 * "Now session 2" lost the Downloads notes read the turn before).
 */
class ContextLossTest {
    private val now = 1_800_000_000_000L
    private fun min(m: Int) = now - m * 60_000L

    private val flashcards = Rec("t1", "c", "Make flashcards from my NET103 session 1 notes and save them to Learn", State.DONE,
        updated = min(30), findings = listOf("NET103 session 1 is in Learn with 14 cards"))
    private val gavin = Rec("t2", "c", "Save Gavin's email damcax75557@afuyo.com and the gateway tests task to memory", State.DONE, updated = min(5))

    @Test fun aLongNewRequestIsNotBoundToAnOldTaskByGenericWords() {
        val text = "Now please save this to memory: Name: Finn, Email: funder@afuyo.com, Task: flashcard fix. Store it so I can ask about Finn and this flashcard fix later."
        val r = TaskMemory.resolve(text, listOf(flashcards, gavin), now)
        assertEquals(TaskMemory.Kind.NEW, r.kind)
        assertNull(r.rec)
        assertEquals("", TaskMemory.block(r))
    }

    @Test fun aShortPointerStillNamesItsTask() {
        val r = TaskMemory.resolve("open the NET103 flashcards", listOf(flashcards, gavin), now)
        assertNotNull(r.rec)
        assertEquals("t1", r.rec?.id)
    }

    @Test fun aNewMatchIsWordedAsMaybeNotContinue() {
        val b = TaskMemory.block(TaskMemory.Resolution(TaskMemory.Kind.NEW, flashcards, score = 3))
        assertFalse(b.contains("continue THIS one"))
        assertTrue(b.contains("may relate"))
    }

    private fun m(role: String, content: Any) = JSONObject().put("role", role).put("content", content)

    @Test fun aBigFileReadDoesNotPushThePreviousTurnOutOfTheTail() {
        val h = JSONArray()
        h.put(m("user", "Give me a summary of NET session 1 please"))
        h.put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("id", "c1").put("type", "function")
            .put("function", JSONObject().put("name", "read_file").put("arguments", "{\"path\":\"/sdcard/Download/NET103_Session1.pdf\"}")))))
        h.put(JSONObject().put("role", "tool").put("tool_call_id", "c1").put("name", "read_file").put("content", "x".repeat(60_000)))
        h.put(m("assistant", "Here's a summary of NET103 Session 1 …"))
        h.put(m("user", "Now session 2 please"))
        // The stored read is ~15k tokens; as sent it is ~250, so the session 1 turn (and the file path) stays word for word.
        assertEquals(0, ChatTier.tailStart(h, 12, 4_200))
        assertTrue(ChatTier.sentTokens(h) < LongChat.tokens(h))
    }

    @Test fun droppedStepsKeepWhatTheUserAsked() {
        val msgs = JSONArray().put(m("system", "sys"))
        msgs.put(m("user", "Summarise NET103 session 1 from my Downloads folder"))
        for (k in 0 until 6) {
            msgs.put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("id", "c$k").put("type", "function")
                .put("function", JSONObject().put("name", "read_file").put("arguments", "{}")))))
            msgs.put(JSONObject().put("role", "tool").put("tool_call_id", "c$k").put("name", "read_file").put("content", "page $k\n" + "y".repeat(3_000)))
        }
        msgs.put(m("user", "Now session 2 please"))
        val out = RequestBudget.summariseOld(msgs, null, 8_000).toString()
        assertTrue(out.contains("Earlier steps were summarised"))
        assertTrue(out.contains("Summarise NET103 session 1 from my Downloads folder"))
        assertTrue(out.contains("Now session 2 please"))
    }
}
