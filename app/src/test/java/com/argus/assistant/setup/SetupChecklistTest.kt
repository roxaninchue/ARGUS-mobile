package com.argus.assistant.setup

import com.argus.assistant.home.HomeStore
import com.argus.assistant.home.HomeTodo
import com.argus.assistant.home.Widget
import com.argus.assistant.home.WidgetTypes
import com.argus.assistant.store.JdbcSql
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

    /** Owner 2026-11-19, faster intro: English needs no model call; another language gets one reply written from the fixed one. */
    @Test fun theIntroductionIsFixedInEnglishAndOneReplyElsewhere() {
        assertFalse(FirstRunIntro.needsModel("en")); assertFalse(FirstRunIntro.needsModel("en-GB")); assertFalse(FirstRunIntro.needsModel(" EN_us "))
        assertTrue(FirstRunIntro.needsModel("fr")); assertTrue(FirstRunIntro.needsModel("bem")); assertTrue(FirstRunIntro.needsModel("sw"))
        assertFalse("nothing picked: English", FirstRunIntro.needsModel(""))
        val k = FirstRunIntro.kickoff(listOf("Connect your calendar", "Allow notifications"), "French")
        assertTrue(k.startsWith("[A note from the ARGUS app, not a message from the user"))
        assertTrue(k.contains("Connect your calendar; Allow notifications"))
        assertTrue(k.contains("\u201cSet up ARGUS\u201d checklist is already at the top of their Life tab"))
        assertTrue(k.contains("They picked French as the app's language."))
        assertTrue(k.contains("Introduce yourself to the user in French"))
        assertTrue("written from the fixed introduction", k.endsWith(FirstRunIntro.FALLBACK))
        assertFalse("no tool to call: the checklist is already there", k.contains(com.argus.assistant.home.LifeTodo.TOOL))
        // docs/MODEL-FIRST.md: plain facts, no shouting
        for (w in listOf("ALWAYS", "NEVER", "MUST", "EVERY")) assertFalse(w, Regex("\\b$w\\b").containsMatchIn(k))
        val m = FirstRunIntro.messages(emptyList(), "")
        assertEquals(1, m.length()); assertEquals("user", m.getJSONObject(0).getString("role"))
        assertTrue(m.getJSONObject(0).getString("content").contains("They picked English"))
        assertTrue(FirstRunIntro.FALLBACK.contains("Set up ARGUS") && FirstRunIntro.FALLBACK.contains("Life"))
        assertEquals("Hello there", FirstRunIntro.pick("  Hello there ", "fb"))
        assertEquals("fb", FirstRunIntro.pick("   ", "fb"))
        assertEquals("fb", FirstRunIntro.pick(null, "fb"))
        assertTrue(FirstRunIntro.TIMEOUT_MS in 10_000L..60_000L)
    }

    /** Owner 2026-11-19: the checklist and the introduction arrive together; English skips the model, others send no tools. */
    @Test fun theChecklistAndTheIntroductionArriveTogether() {
        fun src(p: String) = listOf(File("src/main/java/com/argus/assistant/$p"), File("app/src/main/java/com/argus/assistant/$p")).first { it.exists() }.readText()
        val fr = src("setup/FirstRun.kt")
        val welcome = fr.substring(fr.indexOf("fun welcome("), fr.indexOf("fun sync("))
        assertTrue("English: no model call", welcome.contains("if (!FirstRunIntro.needsModel(lang.code)) null else"))
        assertTrue("one reply, no tools", welcome.contains("LlmClient.complete(FirstRunIntro.messages(open, lang.english), null, null, null,"))
        assertTrue("the checklist just before the message", welcome.contains("beforePost = {") && welcome.contains("ensureChecklist(app)"))
        assertFalse("no hidden agent turn", welcome.contains("tools = "))
        val agent = src("agent/Agent.kt")
        val intro = agent.substring(agent.indexOf("fun introduce("), agent.indexOf("fun messagesOf("))
        assertFalse("no Session, no tool loop", intro.contains("Session(") || intro.contains(".send("))
        assertTrue(intro.indexOf("write()") < intro.indexOf("beforePost()"))
        assertTrue(intro.indexOf("beforePost()") < intro.indexOf("FirstRunIntro.pick(text, fallback)"))
    }
}
