package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.55.0 lean harness (LeanTurn): which turns go lean, the turn's state on its CallState, core + top 4 + catalog tools,
 * find_tools marking what it found (sent from the next step through ToolPick), the short stable prompt, and the "· lean"
 * label. Pure: own CallState objects (never GLOBAL), no Settings, no Context.
 */
class LeanTurnTest {
    @After fun tearDown() { CallState.GLOBAL.lean = null }

    private fun tool(name: String, desc: String = "Does $name.") = JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", desc).put("parameters", JSONObject().put("type", "object")))

    private fun tools(vararg names: String) = JSONArray().apply { names.forEach { put(tool(it)) } }
    private fun names(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") }

    private val all = JSONArray().apply {
        (LeanTurn.CORE + LeanTurn.META).forEach { put(tool(it)) }
        put(tool("device_setting", "Turn a phone setting on or off: Wi-Fi, Bluetooth, flashlight, Do Not Disturb, airplane mode."))
        put(tool("look_at_screen", "Look at the screen now as a picture."))
        put(tool("github_create_issue", "Open an issue in one of the user's GitHub repos."))
        put(tool("run_shell", "Run a command in Argus's Linux terminal."))
        put(tool("music_ops", "Play, pause or skip Argus Music."))
        put(tool("flashcards", "Make and review flashcards."))
    }

    @Test fun onlyTheMainChatsOwnTurnsGoLean() {
        assertTrue(LeanTurn.eligible(true, null, specialist = false, routine = false))
        assertFalse(LeanTurn.eligible(false, null, false, false))
        assertFalse(LeanTurn.eligible(true, "B1", false, false))
        assertFalse(LeanTurn.eligible(true, null, specialist = true, routine = false))
        assertFalse(LeanTurn.eligible(true, null, specialist = false, routine = true))
    }

    @Test fun coreKeepsAlarmsTimersAndMessagesSoTheyNeverWaitOnALookup() {
        for (n in listOf("set_alarm", "set_timer", "message_contact")) assertTrue(n, n in LeanTurn.CORE)
        assertEquals(16, LeanTurn.CORE.size)
        assertEquals(listOf("find_tools", "load_skill"), LeanTurn.META)
    }

    @Test fun stateLivesOnTheTurnsCallStateForEveryStepAndForks() {
        val cs = CallState()
        val a = LeanTurn.begin(cs, 100L, true) { listOf("music_ops") }!!
        assertSame(a, LeanTurn.begin(cs, 100L, true) { error("top is read once per turn") })
        assertSame(a, cs.fork().lean)
        val b = LeanTurn.begin(cs, 200L, true) { emptyList() }!!
        assertTrue(a !== b)
        assertNull(LeanTurn.begin(cs, 300L, false) { emptyList() })
        assertNull(cs.lean)
    }

    @Test fun topFourAreTheMostUsedOtherToolsThisPhoneHas() {
        val counts = mapOf("set_alarm" to 99, "music_ops" to 7, "look_at_screen" to 12, "run_shell" to 3, "gone_tool" to 50,
            "github_create_issue" to 7, "flashcards" to 1, "find_tool" to 40, "device_setting" to 0)
        val have = names(all).toSet()
        assertEquals(listOf("look_at_screen", "github_create_issue", "music_ops", "run_shell"), LeanTurn.top(counts, have))
    }

    @Test fun aLeanStepSendsCoreTopCatalogToolsFoundAndUsedOnly() {
        val st = LeanTurn.State(1L, listOf("music_ops"))
        val sent = names(LeanTurn.filter(all, st, used = setOf("run_shell")))
        assertEquals(LeanTurn.CORE + LeanTurn.META + listOf("run_shell", "music_ops"), sent)
        assertEquals(20, sent.size)
        assertFalse("device_setting" in sent)
        st.found += "device_setting"
        assertTrue("device_setting" in names(LeanTurn.filter(all, st, emptySet())))
        // nothing outside the turn's own (already filtered) list can ride along
        st.found += "pay_everyone"
        assertFalse("pay_everyone" in names(LeanTurn.filter(all, st, emptySet())))
    }

    @Test fun findToolsFindsBluetoothAndMarksItForTheNextStep() {
        val st = LeanTurn.State(1L, emptyList())
        val out = LeanTurn.find("turn on bluetooth", all, null, st)
        assertTrue(out, out.contains("device_setting"))
        assertTrue("device_setting" in st.found)
        assertTrue(out.contains("next step"))
        assertFalse(LeanTurn.FIND in st.found)
    }

    @Test fun findToolsUsesTheCatalogForSkillsAndRanking() {
        val st = LeanTurn.State(1L, emptyList())
        val cat = { _: String, _: Int -> listOf(LeanCatalog.Entry(LeanCatalog.KIND_SKILL, "study-flashcards", "", "Make spaced-repetition flashcards."),
            LeanCatalog.Entry(LeanCatalog.KIND_TOOL, "flashcards", "study", "Make flashcards."), LeanCatalog.Entry(LeanCatalog.KIND_TOOL, "not_on_this_phone", "", "x")) }
        val out = LeanTurn.find("make flashcards on photosynthesis", all, cat, st)
        assertTrue(out, out.contains("study-flashcards"))
        assertTrue(out.contains("load_skill"))
        assertTrue("flashcards" in st.found)
        assertFalse("a catalog row the phone can't send is never offered", "not_on_this_phone" in st.found)
    }

    @Test fun findToolsAnswersPlainlyWhenNothingMatchesOrTheQueryIsEmpty() {
        assertTrue(LeanTurn.find("", all, null, null).contains("Say what you need"))
        val st = LeanTurn.State(1L, emptyList())
        assertTrue(LeanTurn.find("zzqx vvkp", all, null, st).startsWith("No tool or skill matched"))
        assertTrue(st.found.isEmpty())
        assertTrue(LeanTurn.find("bluetooth", all, { _, _ -> throw IllegalStateException("db closed") }, st).contains("device_setting"))
    }

    @Test fun thePromptIsShortAndByteStable() {
        assertTrue("prompt ${LeanTurn.PROMPT.length} chars", LeanTurn.PROMPT.length < LeanTurn.PROMPT_MAX)
        assertTrue(LeanTurn.PROMPT.contains("find_tools")); assertTrue(LeanTurn.PROMPT.contains("load_skill"))
        assertFalse("no clock or date in the cached part", Regex("\\d{1,2}:\\d{2}|20\\d\\d").containsMatchIn(LeanTurn.PROMPT))
        val a = LeanTurn.system("", "RULES", "Rhea", "Reply in English.", "memory one")
        val b = LeanTurn.system("", "RULES", "Nova", "Reply in English.", "memory two")
        val prefix = LeanTurn.PROMPT + "\nRULES"
        assertTrue(a.startsWith(prefix)); assertTrue(b.startsWith(prefix))
        assertTrue(a.endsWith("memory one"))
        assertTrue(LeanTurn.system("", "", null, "", "x".repeat(5_000)).length < LeanTurn.PROMPT.length + LeanTurn.MEMORY_MAX + 10)
    }

    @Test fun catalogToolSchemasAreValidAndNamedForTheGateway() {
        val f = LeanTurn.findDef().getJSONObject("function"); val l = LeanTurn.loadSkillDef().getJSONObject("function")
        assertEquals("find_tools", f.getString("name")); assertEquals("load_skill", l.getString("name"))
        assertEquals("query", f.getJSONObject("parameters").getJSONArray("required").getString(0))
        assertEquals("name", l.getJSONObject("parameters").getJSONArray("required").getString(0))
        for (n in LeanTurn.CORE + LeanTurn.META) assertTrue(Regex("^[A-Za-z0-9_.:-]{1,128}$").matches(n))
    }

    @Test fun toolPickSendsTheLeanSetOnALeanTurnAndTheOldSetOtherwise() {
        val diet = JSONArray().apply { for (i in 0 until all.length()) put(all.get(i)); put(tool("calendar_add")); put(tool("flashcards_review")) }
        val old = names(ToolPick.pick(diet, "hi", emptySet(), emptySet()))
        assertTrue(old.size > LeanTurn.CORE.size + 2)
        val cs = CallState()
        LeanTurn.begin(cs, 7L, true) { emptyList() }
        val prev = CallState.GLOBAL.lean
        CallState.GLOBAL.lean = cs.lean   // the test thread reads GLOBAL (no turn scope here)
        try {
            val lean = names(ToolPick.pick(diet, "hi", emptySet(), emptySet()))
            assertEquals((LeanTurn.CORE + LeanTurn.META).toSet(), lean.toSet())
            assertTrue(ToolPick.core(false, true).containsAll(LeanTurn.CORE))
            assertEquals(LeanTurn.TAG, LeanTurn.tag())
            assertTrue(names(ToolPick.pick(diet, "hi", emptySet(), emptySet(), keepAll = true)).size == diet.length())
        } finally { CallState.GLOBAL.lean = prev }
        assertEquals("", LeanTurn.tag())
    }

    @Test fun scoreWeighsNameWordsOverDescriptionWords() {
        assertEquals(0, LeanTurn.score(emptyList(), "device_setting", "x"))
        assertTrue(LeanTurn.score(listOf("setting"), "device_setting", "") > LeanTurn.score(listOf("setting"), "music_ops", "a setting"))
        assertEquals(4, LeanTurn.score(listOf("github"), "github_pr", "Open a GitHub pull request."))
    }

    @Test fun foundToolsStayForTheRestOfTheTurnAndANewTurnStartsClean() {
        val cs = CallState()
        LeanTurn.begin(cs, 1L, true) { emptyList() }!!.found += "device_setting"
        assertTrue("device_setting" in LeanTurn.begin(cs, 1L, true) { emptyList() }!!.names())
        assertFalse("device_setting" in LeanTurn.begin(cs, 2L, true) { emptyList() }!!.names())
    }

    @Test fun aPickedPersonaGoesFirstAndTheSharedPersonaNeedsNoCopy() {
        assertTrue(LeanTurn.system("You are Argus, but pirate.", "", null, "", "").startsWith("You are Argus, but pirate.\n" + LeanTurn.PROMPT))
        assertTrue(LeanTurn.system("", "", null, "", "").startsWith(LeanTurn.PROMPT))
    }

    @Test fun topBreaksTiesByNameAndSkipsCatalogTools() {
        assertEquals(listOf("a_tool", "b_tool"), LeanTurn.top(mapOf("b_tool" to 3, "a_tool" to 3, "find_tools" to 9, "use_skill" to 9), setOf("a_tool", "b_tool", "find_tools", "use_skill")))
        assertTrue(LeanTurn.top(emptyMap(), setOf("x")).isEmpty())
    }

    @Test fun agentKeepsLeanOutOfRunLoop() {
        val src = listOf("src/main/java/com/blaze/assistant/agent/Agent.kt", "app/src/main/java/com/blaze/assistant/agent/Agent.kt").map(::File).first { it.exists() }.readText()
        val start = src.indexOf("suspend fun runLoop()")
        val end = src.indexOf("suspend fun runTool(name: String", start)
        assertTrue(start > 0 && end > start)
        val loop = src.substring(start, end)
        for (n in listOf("LeanTurn", "LeanCatalog", "leanCall", "find_tools", "load_skill")) assertFalse("$n inside runLoop", loop.contains(n))
        assertTrue(src.contains("LeanTurn.LOAD -> {"))
        assertTrue(src.contains("private fun turnSystem(): String"))
    }

    /** 0.55.1 (Finn: "Hi" got only "Hi"): the lean prompt keeps the voice and greeting guidance; the [context] block rides in the live tail. */
    @Test fun greetingsGetAWarmUsefulReply() {
        assertTrue(LeanTurn.PROMPT.contains("GREETINGS:"))
        assertTrue(LeanTurn.PROMPT.contains("Never answer a greeting with a bare \"Hi\""))
        assertTrue(LeanTurn.PROMPT.contains("VOICE: a sharp, warm friend"))
        assertTrue(LeanTurn.PROMPT.contains("You are "))
        assertTrue(LeanTurn.PROMPT.length < LeanTurn.PROMPT_MAX)
        val s = LeanTurn.system("", "RULES", "Rhea", "", "", now = "CURRENT LOCAL TIME: x")
        assertTrue(s.startsWith(LeanTurn.PROMPT + "\nRULES"))
        assertTrue(s.contains("CURRENT LOCAL TIME: x"))
    }

    /** 0.55.1 (Finn, 11:05: "optimal time to study?" got 9:00-12:00): the lean prompt always carries "now", after the cached prefix. */
    @Test fun theLeanPromptCarriesTheCurrentTime() {
        val now = java.time.ZonedDateTime.of(2026, 11, 22, 11, 5, 0, 0, java.time.ZoneId.of("Africa/Lusaka"))
        val line = LeanTurn.nowLine(now, java.util.Locale.forLanguageTag("en-ZM"))
        assertEquals("CURRENT LOCAL TIME: Sunday 22 November 2026, 11:05, Africa/Lusaka (UTC+02:00); locale en-ZM. " +
            "This is exact: answer any time, date or day question from it and plan the rest of today from it.", line)
        val a = LeanTurn.system("", "RULES", "Finn", "Reply in English.", "memory", now = line)
        assertTrue(a.contains("11:05, Africa/Lusaka (UTC+02:00)"))
        assertTrue("the time is after the cached prefix", a.indexOf("CURRENT LOCAL TIME:") > a.indexOf(LeanTurn.PROMPT) + LeanTurn.PROMPT.length)
        val b = LeanTurn.system("", "RULES", "Finn", "Reply in English.", "memory", now = LeanTurn.nowLine(now.plusMinutes(1), java.util.Locale.forLanguageTag("en-ZM")))
        assertEquals("the prefix stays byte-identical", a.substringBefore("CURRENT LOCAL TIME:"), b.substringBefore("CURRENT LOCAL TIME:"))
        assertTrue(LeanTurn.PROMPT.contains("WHO SAID WHAT:"))
        // Finn 11:07: "It's 11:07" got a bare "It's 11:07 now." instead of a corrected study plan
        assertTrue(LeanTurn.PROMPT.contains("your last plan was wrong") && LeanTurn.PROMPT.contains("never just repeat the time"))
        assertTrue(LeanTurn.PROMPT.length <= LeanTurn.PROMPT_MAX)
        // Agent wires it into every lean turn
        val agent = listOf("src/main/java/com/blaze/assistant/agent/Agent.kt", "app/src/main/java/com/blaze/assistant/agent/Agent.kt").map { java.io.File(it) }.first { it.exists() }.readText()
        assertTrue(agent.contains("now = LeanTurn.nowLine(java.time.ZonedDateTime.now())"))
    }

    /** 0.55.2 (Finn 18:00, weather web-searched and failed; "I don't want to fix every small error that used to work"). */
    @Test fun leanNeverDropsAnAlwaysSentToolWithoutADecision() {
        val old = ToolPick.ESSENTIAL + ToolPick.HARNESS
        val missing = old.filter { it !in LeanTurn.CORE && it !in LeanTurn.BEHIND_FIND }
        assertTrue("always-sent before, neither in LeanTurn.CORE nor LeanTurn.BEHIND_FIND: $missing", missing.isEmpty())
        assertTrue(LeanTurn.BEHIND_FIND.none { it in LeanTurn.CORE })
        // The everyday asks that used to work answer from CORE, never from a web search or a lookup first.
        for (n in listOf("get_weather", "get_location", "set_alarm", "set_timer", "message_contact", "calendar_events", "create_routine", "device_status", "open_app"))
            assertTrue("$n must ride every lean turn", n in LeanTurn.CORE)
        assertTrue(LeanTurn.PROMPT.contains("Weather: call get_weather (never web search for weather)"))
        assertTrue(LeanTurn.PROMPT.contains("\"(listed twice)\" is a duplicate"))
        assertTrue(LeanTurn.PROMPT.length <= LeanTurn.PROMPT_MAX)
    }
}
