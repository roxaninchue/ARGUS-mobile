package com.blaze.assistant.setup

import com.blaze.assistant.home.HomeStore
import com.blaze.assistant.home.HomeTodo
import com.blaze.assistant.home.Widget
import com.blaze.assistant.home.WidgetTypes
import com.blaze.assistant.store.JdbcSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 2026-11-18 first run: the "Set up ARGUS" checklist in Life. Seeded once at the top, ticked from what the phone reports,
 * never unticked or re-added, every step opening a real screen; and the introduction's kickoff and fallback.
 */
class SetupChecklistTest {
    private lateinit var dir: File
    private val open = ArrayList<JdbcSql>()

    @Before fun setUp() { dir = Files.createTempDirectory("checklist").toFile() }
    @After fun tearDown() { open.forEach { try { it.close() } catch (_: Exception) { } }; dir.deleteRecursively() }

    private fun store(): HomeStore = HomeStore(JdbcSql("jdbc:sqlite:" + File(dir, "blaze.db").path).also { open += it })

    @Test fun theStepsAreWhatTheAppCanCheckAndOpen() {
        assertEquals(listOf("calendar", "google", "notifications", "wake_word", "assistant", "desktop", "accessibility"), SetupChecklist.STEPS.map { it.key })
        assertEquals("Set up ARGUS", SetupChecklist.TITLE)
        assertTrue(SetupChecklist.STEPS.any { it.text == "Turn on \u201cHey Argus\u201d" })
        assertTrue(SetupChecklist.STEPS.any { it.text == "Pair with Argus Desktop" })
        // every target is one SelfSetup.open knows: a system screen or one of ARGUS's own settings pages
        val system = setOf("notifications", "assistant", "accessibility")
        for (s in SetupChecklist.STEPS) assertTrue(s.open, s.open in system || s.open.startsWith("settings "))
        assertEquals(SetupChecklist.STEPS.size, SetupChecklist.STEPS.map { it.key }.toSet().size)
        assertEquals(SetupChecklist.STEPS[0], SetupChecklist.step("calendar")); assertNull(SetupChecklist.step("nope"))
    }

    @Test fun detectedFollowsThePhone() {
        val none = SetupChecklist.detected(SetupChecklist.Probe())
        assertTrue(none.values.none { it })
        assertEquals(SetupChecklist.STEPS.map { it.key }.toSet(), none.keys)
        val d = SetupChecklist.detected(SetupChecklist.Probe(calendarPermission = true, notifications = true, phoneControl = true))
        assertTrue(d["calendar"]!!); assertTrue(d["notifications"]!!); assertTrue(d["accessibility"]!!)
        assertFalse(d["google"]!!); assertFalse(d["assistant"]!!)
        assertTrue("Google counts for the calendar too", SetupChecklist.detected(SetupChecklist.Probe(googleConnected = true))["calendar"]!!)
    }

    @Test fun freshAndSyncOnlyTickAndAreIdempotent() {
        val fresh = SetupChecklist.fresh(mapOf("notifications" to true))
        assertEquals(SetupChecklist.STEPS.map { it.text }, fresh.map { it.text })
        assertEquals(listOf("notifications"), fresh.filter { it.done }.map { it.key })
        assertTrue(fresh.all { it.key.isNotBlank() && it.open.isNotBlank() })
        // the user ticked one by hand, added their own item and removed another
        val edited = fresh.map { if (it.key == "desktop") it.copy(done = true) else it }.filter { it.key != "google" } + HomeTodo.Item("Buy a case", false)
        val synced = SetupChecklist.sync(edited, mapOf("assistant" to true, "desktop" to false, "google" to true))
        assertTrue(synced.first { it.key == "assistant" }.done)
        assertTrue("a hand tick stays even when the phone can't tell", synced.first { it.key == "desktop" }.done)
        assertTrue("never re-added", synced.none { it.key == "google" })
        assertEquals("the user's own item stays", "Buy a case", synced.last().text)
        assertEquals(synced, SetupChecklist.sync(synced, mapOf("assistant" to true, "desktop" to false, "google" to true)))
        assertEquals(listOf("Connect your calendar"), SetupChecklist.open(fresh).take(1))
        assertEquals(fresh.count { !it.done }, SetupChecklist.open(fresh).size)
    }

    @Test fun planAddsOnceAndNeverPutsARemovedListBack() {
        assertEquals(SetupChecklist.Plan.ADD, SetupChecklist.plan(hasList = false, seededBefore = false))
        assertEquals(SetupChecklist.Plan.SYNC, SetupChecklist.plan(hasList = true, seededBefore = true))
        assertEquals(SetupChecklist.Plan.SYNC, SetupChecklist.plan(hasList = true, seededBefore = false))
        assertEquals(SetupChecklist.Plan.NONE, SetupChecklist.plan(hasList = false, seededBefore = true))
    }

    @Test fun applyToSeedsAtTheTopOnceThenTicks() {
        val s = store()
        s.add(Widget("", WidgetTypes.NEWS, "Tech", "News"), now = 1)
        val r1 = SetupChecklist.applyTo(s, mapOf("notifications" to true), seededBefore = false, now = 2)
        assertTrue(r1.added)
        val top = s.all().first()
        assertEquals(WidgetTypes.TODO, top.type); assertEquals(SetupChecklist.TITLE, top.title); assertEquals(SetupChecklist.CATEGORY, top.category)
        assertTrue(SetupChecklist.isChecklist(top.cfg()))
        assertEquals(r1.id, top.id)
        // seeding again (a second call, or after the pref was saved) adds nothing
        val r2 = SetupChecklist.applyTo(s, mapOf("notifications" to true, "assistant" to true), seededBefore = true, now = 3)
        assertFalse(r2.added)
        assertEquals(1, s.all().count { it.type == WidgetTypes.TODO })
        assertTrue(r2.items.first { it.key == "assistant" }.done)
        assertTrue(HomeTodo.items(s.get(top.id)!!.cfg()).first { it.key == "assistant" }.done)
        val r3 = SetupChecklist.applyTo(s, mapOf("notifications" to true, "assistant" to true), seededBefore = true, now = 4)
        assertEquals(r2.items, r3.items)
        assertEquals("nothing changed: nothing written", 3L, s.get(top.id)!!.updated)
        // removed by the user: never back
        s.remove(top.id)
        val r4 = SetupChecklist.applyTo(s, emptyMap(), seededBefore = true, now = 5)
        assertFalse(r4.added); assertTrue(r4.items.isEmpty()); assertNull(r4.id)
        assertEquals(0, s.all().count { it.type == WidgetTypes.TODO })
    }

    @Test fun theIntroductionIsTheModelsWithAFixedFallback() {
        val k = FirstRunIntro.kickoff(listOf("Connect your calendar", "Allow notifications"), "French")
        assertTrue(k.startsWith("[A note from the ARGUS app, not a message from the user"))
        assertTrue(k.contains("Connect your calendar; Allow notifications"))
        assertTrue(k.contains("\u201cSet up ARGUS\u201d checklist") && k.contains("Life tab"))
        assertTrue(k.contains("They picked French as the app's language."))
        assertTrue(k.contains("Introduce yourself"))
        // docs/MODEL-FIRST.md: plain facts, no shouting
        for (w in listOf("ALWAYS", "NEVER", "MUST", "EVERY")) assertFalse(w, Regex("\\b$w\\b").containsMatchIn(k))
        // ARGUS makes the checklist itself, with its tool, in the same turn as the introduction
        assertTrue(k.contains("life_todo tool (action setup)"))
        assertEquals(setOf(com.blaze.assistant.home.LifeTodo.TOOL), FirstRunIntro.TOOLS)
        assertTrue(FirstRunIntro.kickoff(emptyList(), "").contains("They picked English"))
        assertTrue(FirstRunIntro.FALLBACK.contains("Set up ARGUS") && FirstRunIntro.FALLBACK.contains("Life"))
        assertEquals("Hello there", FirstRunIntro.pick("  Hello there ", "fb"))
        assertEquals("fb", FirstRunIntro.pick("   ", "fb"))
        assertEquals("fb", FirstRunIntro.pick(null, "fb"))
        assertTrue(FirstRunIntro.TIMEOUT_MS in 10_000L..120_000L)
    }

    /** Owner's spec: after sign-in ARGUS introduces itself and makes the checklist with life_todo; the app's list is only the fallback. */
    @Test fun argusMakesTheChecklistInTheIntroTurn() {
        fun src(p: String) = listOf(File("src/main/java/com/blaze/assistant/$p"), File("app/src/main/java/com/blaze/assistant/$p")).first { it.exists() }.readText()
        val fr = src("setup/FirstRun.kt")
        val welcome = fr.substring(fr.indexOf("fun welcome("), fr.indexOf("fun sync("))
        assertTrue("the hidden turn carries life_todo", welcome.contains("tools = FirstRunIntro.TOOLS"))
        assertTrue("the app's checklist only after the turn, before the message", welcome.contains("beforePost = {") && welcome.contains("ensureChecklist(app)"))
        assertFalse("no checklist before ARGUS's turn", welcome.substring(0, welcome.indexOf("Agent.introduce")).contains("HomeBoard.checklist"))
        val agent = src("agent/Agent.kt")
        assertTrue(agent.contains("val joinNames = widenNames + pinTools +"))
        assertTrue(agent.contains("it.pinTools = tools"))
        assertTrue(agent.indexOf("beforePost()") < agent.indexOf("FirstRunIntro.pick(text, fallback)"))
    }
}
