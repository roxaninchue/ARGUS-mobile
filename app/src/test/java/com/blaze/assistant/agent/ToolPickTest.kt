package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPickTest {
    private fun tool(name: String, desc: String = "does $name") = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object")))
    private fun arr(names: Collection<String>) = JSONArray().apply { names.forEach { put(tool(it)) } }
    private fun names(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") }

    private val coreish = ToolPick.ESSENTIAL + ToolPick.CUES.flatMap { it.names }
    private val selected = arr(coreish + listOf("set_alarm", "set_timer", "set_volume"))

    @Test fun plainActionDropsTheOptionalCore() {
        val out = names(ToolPick.pick(selected, "open whatsapp", emptySet(), setOf("device")))
        assertTrue(out.containsAll(listOf("open_app", "find_tool", "load_tools", "set_alarm")))
        assertFalse(out.contains("start_agent")); assertFalse(out.contains("search_chat")); assertFalse(out.contains("blaze_docs_search"))
        assertTrue(out.size < selected.length())
    }

    /** 2026-11-16 model-first: cues come back on this chat's use or the model's own load, never on the request's words. */
    @Test fun cuesComeBackOnHistoryOrLoadNotWords() {
        assertFalse(names(ToolPick.pick(selected, "do this in the background while I wait", emptySet(), emptySet())).contains("start_agent"))
        assertFalse(names(ToolPick.pick(selected, "what did you say earlier about rent", emptySet(), emptySet())).contains("search_chat"))
        assertTrue(names(ToolPick.pick(selected, "ok", setOf("check_agent"), emptySet())).contains("list_agents"))
        assertTrue(names(ToolPick.pick(selected, "ok", emptySet(), setOf("history"))).contains("read_turn"))
        assertTrue(names(ToolPick.pick(selected, "ok", emptySet(), setOf("agents"))).contains("start_agent"))
        assertEquals(setOf("self"), ToolPick.cuesIn("Matching tools (now loaded): blaze_docs_search: search docs"))
    }

    @Test fun routinesKeepEverything() {
        assertEquals(selected.length(), ToolPick.pick(selected, "x", emptySet(), emptySet(), keepAll = true).length())
    }

    /** Over the cap, structure decides: core, must, used, then the registry's order; the request's words don't. */
    @Test fun capRanksByStructureNotWords() {
        val many = arr(ToolPick.ESSENTIAL + (1..60).map { "extra_tool_$it" } + listOf("set_volume", "calendar_events_x"))
        val out = names(ToolPick.pick(many, "turn the volume up", setOf("extra_tool_59"), emptySet(), max = 25, must = setOf("calendar_events_x")))
        assertEquals(25, out.size)
        assertTrue(out.containsAll(ToolPick.ESSENTIAL)); assertTrue(out.contains("extra_tool_59")); assertTrue(out.contains("calendar_events_x"))
        assertFalse("the word volume no longer ranks set_volume", out.contains("set_volume"))
        assertTrue(out.contains("extra_tool_1"))
        // Order is kept as given.
        assertTrue(out.indexOf("extra_tool_59") < out.indexOf("calendar_events_x"))
    }

    @Test fun loadToolOffersTheCues() {
        val f = ToolPick.loadTool().getJSONObject("function")
        val e = f.getJSONObject("parameters").getJSONObject("properties").getJSONObject("groups").getJSONObject("items").getJSONArray("enum")
        val ids = (0 until e.length()).map { e.getString(it) }
        assertTrue(ids.containsAll(ToolPick.CUES.map { it.id })); assertTrue(ids.contains("device"))
        assertTrue(f.getString("description").contains("agents ("))
    }

    @Test fun sizeLine() {
        val s = ToolPick.sizeLine(arr(listOf("a_b")), arr(listOf("a_b", "c_d")), arr(listOf("a_b", "c_d")))
        assertTrue(s, s.startsWith("tools 1/2 · schemas "))
    }

    /** 2026-11-14 harness core: web, terminal, files, browser, schedule and messages ride every turn, whatever the words. */
    @Test fun harnessCoreRidesEveryTurn() {
        val many = arr(ToolPick.ESSENTIAL + ToolPick.HARNESS + (1..80).map { "extra_tool_$it" })
        val out = names(ToolPick.pick(many, "who won last night", emptySet(), emptySet()))
        assertTrue(out.size <= ToolPick.MAX_TOOLS)
        assertTrue(out.containsAll(ToolPick.ESSENTIAL + ToolPick.HARNESS))
        assertTrue(ToolPick.core(true).none { it in ToolPick.TERMINAL_CORE })
        assertTrue(ToolPick.core(false).containsAll(ToolPick.TERMINAL_CORE))
    }
}
