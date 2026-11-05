package com.argus.assistant.agent


import com.argus.assistant.BrandName
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52.1 (gw061-too-long.md): every request the app sends fits the ~60 KB budget the gateway survives on the Workers
 * Free plan (bodies of 133–141 KB were killed at 10 ms CPU → Cloudflare 1102 → "That took too long").
 */
class RequestBudgetTest {
    private fun tool(i: Int) = JSONObject().put("type", "function").put("function", JSONObject().put("name", "tool_$i")
        .put("description", "Does thing number $i. " + "It has a long description that explains every option in detail. ".repeat(12))
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("description", "What to look for, in the user's words"))
            .put("limit", JSONObject().put("type", "integer").put("description", "How many results")))))

    private fun bigRequest(steps: Int, resultChars: Int): Pair<JSONArray, JSONArray> {
        val m = JSONArray()
        m.put(JSONObject().put("role", "system").put("content", "You are ${BrandName.NAME}. " + "Rules and context. ".repeat(900)))   // ~17 KB
        m.put(JSONObject().put("role", "user").put("content", "Find the three best budget phones and compare them"))
        for (s in 0 until steps) {
            val id = "call_$s"
            m.put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", if (s % 2 == 0) "web_search" else "fetch_page").put("arguments", "{\"query\":\"phones $s\"}")))))
            m.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("name", if (s % 2 == 0) "web_search" else "fetch_page")
                .put("content", "Result $s: " + "x".repeat(resultChars)))
        }
        val t = JSONArray(); for (i in 0 until 128) t.put(tool(i))
        t.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "web_search").put("description", "Search the web")))
        return m to t
    }

    private fun consistent(m: JSONArray): Boolean {
        val called = HashSet<String>()
        for (i in 0 until m.length()) {
            val o = m.getJSONObject(i)
            o.optJSONArray("tool_calls")?.let { c -> for (j in 0 until c.length()) called += c.getJSONObject(j).getString("id") }
            if (o.optString("role") == "tool" && o.optString("tool_call_id") !in called) return false
        }
        return true
    }

    @Test fun theOwnersRequestShapeFitsTheBudget() {
        val (m, t) = bigRequest(steps = 15, resultChars = 6_000)
        val before = RequestBudget.size(m, t)
        assertTrue("fixture must be as big as the killed requests: $before", before > 133_000)
        val f = RequestBudget.fit(m, t)
        assertTrue("after ${f.bytesAfter} B", f.bytesAfter <= RequestBudget.MAX_BYTES)
        assertTrue("tools ${f.toolsAfter}", f.toolsAfter in 1..RequestBudget.MAX_TOOLS)
        assertEquals("system", f.messages.getJSONObject(0).getString("role"))
        assertTrue("the request is kept", f.messages.toString().contains("three best budget phones"))
        val ft = f.tools!!
        assertTrue("a called tool is never dropped", (0 until ft.length()).any { ft.getJSONObject(it).getJSONObject("function").getString("name") == "web_search" })
        assertTrue("every tool result still answers a call", consistent(f.messages))
    }

    @Test fun aSmallRequestGoesOutUntouched() {
        val m = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
        val t = JSONArray().put(tool(1))
        val f = RequestBudget.fit(m, t)
        assertTrue(f.messages === m && f.tools === t)
    }

    @Test fun manyToolsAreCappedEvenWhenSmall() {
        val m = JSONArray().put(JSONObject().put("role", "user").put("content", "open settings"))
        val t = JSONArray(); for (i in 0 until 60) t.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "t$i")))
        assertEquals(RequestBudget.MAX_TOOLS, RequestBudget.fit(m, t).toolsAfter)
    }

    @Test fun longStepsAreSummarised() {
        val (m, t) = bigRequest(steps = 40, resultChars = 2_000)
        val f = RequestBudget.fit(m, t)
        assertTrue("after ${f.bytesAfter}", f.bytesAfter <= RequestBudget.MAX_BYTES)
        assertTrue(f.messages.toString().contains("Earlier steps were summarised"))
        assertTrue(consistent(f.messages))
    }

    @Test fun recentResultsKeepMoreThanOldOnes() {
        val (m, _) = bigRequest(steps = 6, resultChars = 9_000)
        RequestBudget.trimResults(m)
        val tools = (0 until m.length()).map { m.getJSONObject(it) }.filter { it.optString("role") == "tool" }.map { it.getString("content").length }
        assertTrue(tools.last() > tools.first())
        assertTrue(tools.first() < RequestBudget.OLD_RESULT_CHARS + 100)
    }

    @Test fun wiredIntoEveryModelCall() {
        val src = java.io.File("src/main/java/com/argus/assistant/agent/LlmClient.kt").let { if (it.exists()) it else java.io.File("app/src/main/java/com/argus/assistant/agent/LlmClient.kt") }.readText()
        assertTrue(src.contains("RequestBudget.fit(clean0, tools)"))
    }
}
