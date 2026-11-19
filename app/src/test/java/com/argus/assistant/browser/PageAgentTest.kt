package com.argus.assistant.browser

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2026-11-18 page tools for "Ask ARGUS about this page" (PageAgent). */
class PageAgentTest {
    private fun a(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

    @Test fun onlyPageToolsAreSent() {
        val t = PageAgent.tools()
        val names = (0 until t.length()).map { t.getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(PageAgent.NAMES, names)
        assertTrue(names.all { it.startsWith("page_") })
        // Every schema lets the model mark a step consequential.
        for (k in 0 until t.length()) assertTrue(t.getJSONObject(k).getJSONObject("function").getJSONObject("parameters").getJSONObject("properties").has("consequential"))
    }

    @Test fun anythingElseIsRefused() {
        for (n in listOf("message_contact", "open_app", "browser_open", "run_shell", "memory_save", "page_delete_everything"))
            assertFalse(n, PageAgent.allowed(n))
        assertTrue(PageAgent.refusal("open_app").contains("main chat"))
    }

    @Test fun commitsWaitForAllow() {
        assertTrue(PageAgent.needsAllow("page_click", a("n" to 4, "consequential" to true)))
        assertTrue(PageAgent.needsAllow("page_click", a("text" to "Place order")))
        assertTrue(PageAgent.needsAllow("page_click", a("label" to "Pay K250")))
        assertTrue(PageAgent.needsAllow("page_type", a("text" to "hello", "submit" to true)))
        assertTrue(PageAgent.needsAllow("page_key", a("key" to "Enter", "label" to "send the message")))
        assertFalse(PageAgent.needsAllow("page_click", a("text" to "Next")))
        assertFalse(PageAgent.needsAllow("page_type", a("text" to "Lusaka", "label" to "City")))
        assertFalse(PageAgent.needsAllow("page_scroll", a("direction" to "down")))
        assertFalse(PageAgent.needsAllow("page_read", JSONObject()))
        // "Booking" words inside other words don't trip it.
        assertFalse(PageAgent.needsAllow("page_click", a("text" to "Notebook")))
    }

    @Test fun stepLinesArePlainWords() {
        assertEquals("Tapping \u201cNext\u201d", PageAgent.stepLine("page_click", a("text" to "Next")))
        assertEquals("Typing \u201cLusaka\u201d into \u201cCity\u201d", PageAgent.stepLine("page_type", a("text" to "Lusaka", "label" to "City")))
        assertEquals("Scrolling up", PageAgent.stepLine("page_scroll", a("direction" to "up")))
        assertEquals("Tapping \u201cPlace order\u201d?", PageAgent.allowQuestion("page_click", a("text" to "Place order")))
    }

    @Test fun parsesCallsAndCapsSteps() {
        val reply = JSONObject().put("tool_calls", JSONArray()
            .put(JSONObject().put("id", "c1").put("function", JSONObject().put("name", "page_click").put("arguments", "{\"n\":3}")))
            .put(JSONObject().put("function", JSONObject().put("name", "page_read").put("arguments", "not json"))))
        val cs = PageAgent.calls(reply)
        assertEquals(2, cs.size)
        assertEquals("c1", cs[0].id); assertEquals(3, cs[0].args.getInt("n"))
        assertEquals("p1", cs[1].id); assertEquals(0, cs[1].args.length())
        assertEquals(12, PageAgent.MAX_STEPS)
        assertTrue(PageAgent.RULE.contains("At most 12 steps"))
        assertEquals("Try page_wait, then page_read.", PageAgent.result("Try browser_wait, then browser_read."))
    }

    @Test fun ruleGoesBeforeThePage() {
        val m = PageAgent.messages(PageAsk.Capture("T", "https://example.com/", "Hello world"), listOf(true to "fill it in"), null, "English")
        val sys = m.getJSONObject(0).getString("content")
        assertTrue(sys.indexOf("ACTING ON THIS PAGE") in 1 until sys.indexOf("PAGE ON THE USER'S SCREEN:"))
    }

    /** The sheet asks Allow / Not now itself and answers only its own pending step (never Agent.confirm). */
    @Test fun sheetAsksItself() {
        val root = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        val src = File(root, "app/src/main/java/com/argus/assistant/browser/PageAskSheet.kt").readText()
        assertTrue(src.contains("PageAgent.Runner("))
        assertTrue(src.contains("Chip(\"Allow\", active = true) { d.complete(true) }"))
        assertTrue(src.contains("Chip(\"Not now\") { d.complete(false) }"))
        assertFalse(src.contains("Agent.confirm"))
    }
}
