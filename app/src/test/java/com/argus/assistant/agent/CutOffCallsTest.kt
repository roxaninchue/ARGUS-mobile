package com.argus.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.53.6: a tool call cut off by the output cap (finish_reason "length") or with arguments that won't parse is never run
 * with empty arguments: it is reported as cut, answered with a plain error, and the model is told to go shorter / in parts.
 */
class CutOffCallsTest {
    private val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "design")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string")).put("html", JSONObject().put("type", "string"))))))

    private fun chunk(delta: JSONObject?, finish: String? = null): String {
        val ch = JSONObject().put("delta", delta ?: JSONObject())
        if (finish != null) ch.put("finish_reason", finish) else ch.put("finish_reason", JSONObject.NULL)
        return "data: " + JSONObject().put("choices", JSONArray().put(ch)) + "\n\n"
    }

    private fun callDelta(args: String, first: Boolean) = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).apply {
        if (first) put("id", "call_1")
        put("function", JSONObject().apply { if (first) put("name", "design"); put("arguments", args) })
    }))

    @Test fun lengthStopIsRecordedAndTheOpenCallIsNotRun() {
        val cutArgs = "{\"action\":\"create\",\"html\":\"<!doctype html><html><body><h1>Landing"
        val sse = chunk(callDelta(cutArgs.take(20), true)) + chunk(callDelta(cutArgs.drop(20), false)) + chunk(null, "length") + "data: [DONE]\n\n"
        val reply = LlmClient.readSse(sse.byteInputStream(), { }, null)
        assertEquals("length", reply.optString(LlmClient.FINISH))
        val rep = ToolCalls.normalize(reply, tools)
        assertEquals(1, rep.cut.size)
        val c = rep.cut.values.first()
        assertEquals("design", c.name)
        assertEquals(cutArgs.length, c.chars)
        assertTrue(c.lengthStop)
        // the call stays (so its error answer pairs) but with no arguments to run on
        val call = reply.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("{}", call.getJSONObject("function").getString("arguments"))
        assertTrue(ToolCalls.cutNote(c).contains("cut off at ${cutArgs.length} characters"))
        assertTrue(ToolCalls.cutNote(c).startsWith("Error: NOT run"))
    }

    @Test fun braceRepairDoesNotSaveACutCall() {
        // closes cleanly with "}" but the stream was cut: still not run
        val reply = JSONObject().put("role", "assistant").put(LlmClient.FINISH, "length").put("tool_calls", JSONArray().put(JSONObject()
            .put("id", "a").put("type", "function").put("function", JSONObject().put("name", "design").put("arguments", "{\"action\":\"create\",\"html\":\"<p>hi</p>\""))))
        assertEquals(1, ToolCalls.normalize(reply, tools).cut.size)
    }

    @Test fun unreadableArgumentsAreNotRunEvenWithoutLength() {
        val reply = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().put(JSONObject()
            .put("id", "a").put("type", "function").put("function", JSONObject().put("name", "design").put("arguments", "{\"action\":\"create\",\"html\":\"<p>unclosed"))))
        val rep = ToolCalls.normalize(reply, tools)
        assertEquals(1, rep.cut.size)
        assertFalse(rep.cut.values.first().lengthStop)
    }

    @Test fun aCompleteCallStillRuns() {
        val reply = JSONObject().put("role", "assistant").put(LlmClient.FINISH, "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
            .put("id", "a").put("type", "function").put("function", JSONObject().put("name", "design").put("arguments", "{\"action\":\"create\",\"html\":\"<p>hi</p>\"}"))))
        assertTrue(ToolCalls.normalize(reply, tools).cut.isEmpty())
    }

    @Test fun finishKeyNeverReachesTheProvider() {
        val msgs = JSONArray().put(JSONObject().put("role", "assistant").put("content", "hi").put(LlmClient.FINISH, "length"))
        assertFalse(ToolCalls.clean(msgs).getJSONObject(0).has(LlmClient.FINISH))
    }

    @Test fun selfTalkIsHidden() {
        assertTrue(Narration.selfTalk("Let me build a complete landing page and pass it in properly"))
        assertTrue(Narration.selfTalk("I need to pass the actual content"))
        assertFalse(Narration.selfTalk("Got it — a beautiful landing page coming up"))
        assertFalse(Narration.selfTalk(""))
    }
}
