package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.54.1: a reply that ended on its output limit or a closed stream is shown as cut, with a Continue chip. */
class CutReplyTest {
    private fun chunk(text: String, finish: String? = null): String {
        val ch = JSONObject().put("delta", JSONObject().put("content", text))
        ch.put("finish_reason", finish ?: JSONObject.NULL)
        return "data: " + JSONObject().put("choices", JSONArray().put(ch)) + "\n\n"
    }

    @Test fun reasons() {
        assertEquals("length", CutReply.reason("length"))
        assertEquals(CutReply.DROPPED, CutReply.reason("dropped"))
        assertNull(CutReply.reason("stop"))
        assertNull(CutReply.reason("tool_calls"))
        assertNull(CutReply.reason(""))
        assertNull(CutReply.reason(null))
    }

    @Test fun cutReplyIsMarkedAndGetsContinueFirst() {
        val text = "Pizza in Lusaka:\n\n1. Debonairs Pizza \u2013"
        val shown = CutReply.mark(text, "length")
        assertTrue(shown.startsWith(text))
        assertTrue(shown.endsWith(CutReply.LENGTH_LINE))
        assertTrue(CutReply.mark(text, CutReply.DROPPED).endsWith(CutReply.DROPPED_LINE))
        assertEquals(listOf("Continue", "Map them", "Cheapest?"), CutReply.chips(listOf("Map them", "continue", "Cheapest?", "Hours"), "length"))
    }

    @Test fun finishedReplyIsUntouched() {
        assertEquals("Done.", CutReply.mark("Done.", null))
        assertEquals(listOf("a", "b"), CutReply.chips(listOf("a", "b"), null))
    }

    @Test fun streamThatClosesWithNoFinishOrDoneIsDropped() {
        val reply = LlmClient.readSse((chunk("1. Debonairs") + chunk(" Pizza \u2013")).byteInputStream(), { }, null)
        assertEquals(CutReply.DROPPED, reply.optString(LlmClient.FINISH))
        assertEquals(CutReply.DROPPED, CallState.current().finish)
    }

    @Test fun streamThatEndsProperlyIsNotCut() {
        val withDone = LlmClient.readSse((chunk("Hi") + "data: [DONE]\n\n").byteInputStream(), { }, null)
        assertFalse(withDone.has(LlmClient.FINISH))
        val withStop = LlmClient.readSse(chunk("Hi", "stop").byteInputStream(), { }, null)
        assertEquals("stop", withStop.optString(LlmClient.FINISH))
        assertNull(CutReply.reason(CallState.current().finish))
    }

    @Test fun lengthStopIsRecordedForTheTurn() {
        LlmClient.readSse((chunk("1. Debonairs", "length") + "data: [DONE]\n\n").byteInputStream(), { }, null)
        assertEquals("length", CutReply.reason(CallState.current().finish))
    }
}
