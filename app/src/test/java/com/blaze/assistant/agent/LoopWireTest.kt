package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.55.1: the cloud loop's wire format (LoopWire): event filter, bodies, the steps recorder for a full resend. */
class LoopWireTest {
    @Test fun theFilterTakesArgusEventsAndPassesTheRestUntouched() {
        val raw = listOf(
            "data: {\"argus\":{\"type\":\"turn\",\"turn_id\":\"T1\"}}", "",
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"}}]}", "",
            "data: {\"argus\":{\"type\":\"done\",\"steps\":1}}", "",
            "data: [DONE]", "").joinToString("\n")
        val seen = ArrayList<String>()
        val out = LoopWire.EventFilter(raw.byteInputStream()) { seen.add(it.getString("type")) }.readBytes().toString(Charsets.UTF_8)
        assertEquals(listOf("turn", "done"), seen)
        assertTrue(out.contains("\"content\":\"Hi\""))
        assertFalse(out.contains("argus"))
        assertTrue(out.contains("data: [DONE]"))
    }

    @Test fun bodies() {
        val s = LoopWire.startBody(JSONArray().put(JSONObject().put("role", "user").put("content", "x")), JSONArray().put(JSONObject().put("type", "function")), null, 900, listOf("set_alarm", "set_alarm", "device_setting"))
        assertEquals("auto", s.get("tool_choice"))
        assertEquals(listOf("set_alarm", "device_setting"), (0 until s.getJSONArray("argus_tools_available").length()).map { s.getJSONArray("argus_tools_available").getString(it) })
        val r = LoopWire.resumeBody("T1", listOf("c1" to "ok"), JSONArray().put(JSONObject().put("type", "function")))
        assertEquals("T1", r.getString("turnId"))
        assertEquals("c1", r.getJSONArray("tool_results").getJSONObject(0).getString("tool_call_id"))
        assertEquals(1, r.getJSONArray("tools").length())
    }

    @Test fun pauseAndSchemas() {
        val p = LoopWire.pause(JSONObject("{\"reason\":\"need_tools\",\"turn_id\":\"T\",\"resumable\":true,\"calls\":[],\"need_tools\":[\"device_setting\"]}"))
        assertEquals(LoopWire.Pause("need_tools", "T", true, emptyList(), listOf("device_setting")), p)
        val all = JSONArray().put(JSONObject("{\"type\":\"function\",\"function\":{\"name\":\"a\"}}")).put(JSONObject("{\"type\":\"function\",\"function\":{\"name\":\"device_setting\"}}"))
        assertEquals("device_setting", LoopWire.schemas(all, listOf("device_setting", "missing")).getJSONObject(0).getJSONObject("function").getString("name"))
    }

    @Test fun theRecorderReplaysWhatAlreadyHappenedSoAFullResendRunsNothingTwice() {
        val r = LoopWire.Recorder()
        r.event(JSONObject("{\"type\":\"model\",\"calls\":2}"))
        r.event(JSONObject("{\"type\":\"tool\",\"id\":\"c1\",\"name\":\"web_search\",\"arguments\":\"{\\\"query\\\":\\\"x\\\"}\",\"text\":\"Checking.\"}"))
        r.event(JSONObject("{\"type\":\"tool_result\",\"id\":\"c1\",\"name\":\"web_search\",\"content\":\"results\",\"ms\":300}"))
        r.batch(PhoneBatch.Batch("T", "J", listOf(PhoneBatch.Step("c2", "set_alarm", "{}", true)), "s"))
        r.results(listOf("c2" to "Alarm set."))
        val base = JSONArray().put(JSONObject().put("role", "user").put("content", "q"))
        val m = r.resend(base)
        assertEquals(listOf("user", "assistant", "tool", "tool"), (0 until m.length()).map { m.getJSONObject(it).getString("role") })
        assertEquals(listOf("c1", "c2"), m.getJSONObject(1).getJSONArray("tool_calls").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } })
        assertEquals("Checking.", m.getJSONObject(1).getString("content"))
        assertEquals(listOf("web_search" to 300L), r.cloud)
        // a step still waiting on a result is left out (it never happened)
        val r2 = LoopWire.Recorder()
        r2.event(JSONObject("{\"type\":\"model\",\"calls\":1}"))
        r2.batch(PhoneBatch.Batch("T", "J", listOf(PhoneBatch.Step("c9", "set_alarm", "{}", true)), "s"))
        assertEquals(0, r2.messages().length())
    }
}
