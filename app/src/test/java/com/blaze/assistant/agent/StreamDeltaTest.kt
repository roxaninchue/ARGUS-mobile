package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** 0.54.2 (owner, 15 Nov 2026: "I want the thought to stream in"): reasoning in every shape providers stream it. */
class StreamDeltaTest {
    private fun d(json: String) = StreamDelta.of(JSONObject(json))

    @Test fun reasoningContentAndReasoningKeys() {
        assertEquals(StreamDelta.Part("step 1", ""), d("""{"reasoning_content":"step 1"}"""))
        assertEquals(StreamDelta.Part("step 2", ""), d("""{"reasoning":"step 2","content":null}"""))
        assertEquals(StreamDelta.Part("", "Hi"), d("""{"content":"Hi","reasoning_content":null}"""))
        assertEquals(StreamDelta.Part("", ""), d("""{"role":"assistant"}"""))
    }

    @Test fun mistralThinkingChunksStreamAsReasoningNotAsText() {
        // thinking phase: content is a list holding a thinking chunk
        assertEquals(StreamDelta.Part("Let me trace", ""),
            d("""{"content":[{"type":"thinking","thinking":[{"type":"text","text":"Let me trace"}]}]}"""))
        // transition: the closing thinking chunk and the first text chunk in one list
        assertEquals(StreamDelta.Part(" it.", "So j"),
            d("""{"content":[{"type":"thinking","thinking":[{"type":"text","text":" it."}]},{"type":"text","text":"So j"}]}"""))
        // answer phase: a plain string again
        assertEquals(StreamDelta.Part("", " ends"), d("""{"content":" ends"}"""))
    }

    @Test fun mistralWholeMessageIsFlattened() {
        val m = JSONObject("""{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"why"}]},{"type":"text","text":"Because."}]}""")
        assertEquals("why", StreamDelta.flatten(m))
        assertEquals("Because.", m.getString("content"))
        val plain = JSONObject("""{"role":"assistant","content":"Hi"}""")
        assertEquals("", StreamDelta.flatten(plain)); assertEquals("Hi", plain.getString("content"))
    }

    @Test fun thinkTagsStayInTheTextForReplyParts() {
        assertEquals(StreamDelta.Part("", "<think>hmm"), d("""{"content":"<think>hmm"}"""))
    }
}
