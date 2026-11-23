package com.blaze.assistant.ui

import com.blaze.assistant.agent.*
import com.blaze.assistant.mediaapps.*
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.55.9 (Finn, 2026-11-23): reasoning leaking into the chat, swipes the chat ignored, the model picker's X on the
 * battery icon, a dark status bar in Music / Video / Browser, the landscape rail clipped by a side nav bar, and auto fit.
 */
class Fixes0559Test {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blaze/assistant/$path").readText()

    // ---- reasoning stays in Thoughts
    @Test fun aShortStatusLineStillShows() {
        assertTrue(Narration.shows("Sure — I'll check the downloads and start one."))
        assertTrue(Narration.shows("Found 8 songs in your library. Starting a shuffle."))
        assertTrue(Narration.shows("I found your music files in /storage/emulated/0/Music — mostly m4a, opus, and a few mp3. Next I'll build the player."))
    }

    @Test fun reasoningGoesToThoughts() {
        val long = "The argus_now output didn't show the song library. Let me use music_ops to see what's playing/available. " +
            "The player said \"8 songs\" are local. Let me just try to play one of the known track titles more precisely."
        assertFalse(Narration.shows(long))
        assertFalse(Narration.shows("Try the play_list route."))
        assertFalse(Narration.shows("Let me ask the user how they want it."))
        assertFalse(Narration.shows("But autopilot says decide myself."))
        assertFalse(Narration.shows("Checked it.\n\nNow the mixes."))
        val g = Narration.gate(ReplyParts.Parts(long, "earlier", emptyList()))
        assertEquals("", g.text)
        assertTrue(g.thinking.startsWith("earlier") && g.thinking.contains("argus_now"))
        val keep = ReplyParts.Parts("Starting a shuffle.", "", emptyList())
        assertEquals(keep, Narration.gate(keep))
    }

    @Test fun runLoopUsesTheGateAndGrowsNothing() {
        val a = src("agent/Agent.kt")
        assertTrue(a.contains(".let(Narration::gate)"))
        assertFalse(a.contains("if (Narration.selfTalk(it.text)) it.copy(text = \"\") else it"))
    }

    // ---- swipes
    @Test fun onlyAClearRightwardDragQuotes() {
        val slop = 18f
        assertTrue(QuoteSwipe.claims(30f, 5f, slop))
        assertFalse("diagonal: the list's", QuoteSwipe.claims(25f, 20f, slop))
        assertFalse("leftward", QuoteSwipe.claims(-30f, 0f, slop))
        assertTrue("vertical past slop: the list's", QuoteSwipe.notOurs(5f, 25f, slop))
        assertTrue("leftward past slop", QuoteSwipe.notOurs(-25f, 2f, slop))
        assertFalse("still deciding", QuoteSwipe.notOurs(8f, 6f, slop))
        assertFalse(src("ui/SwipeToQuote.kt").contains("detectHorizontalDragGestures"))
    }

    @Test fun yourDragLetsGoOfTheEnd() {
        val s = src("ui/ChatScreen.kt")
        assertTrue(s.contains("LaunchedEffect(dragging) { if (dragging) follow = false else if (atBottom) follow = true }"))
        assertFalse(s.contains("if (dragging) follow = atBottom"))
    }

    // ---- status bar and sheets
    @Test fun everyAppWindowSyncsTheStatusBarIcons() {
        for (f in listOf("mediaapps/MusicActivity.kt", "mediaapps/VideoActivity.kt", "browser/BrowserActivity.kt", "browser/phone/PhoneBrowserActivity.kt"))
            assertTrue(f, src(f).contains("Theme(statusBars = true)"))
        assertTrue(src("ui/Theme.kt").contains("if (statusBars) SyncSystemBarIcons(dark)"))
    }

    @Test fun sheetsMeasureTheRealWindow() {
        val s = src("ui/common/WindowLayoutUi.kt")
        assertTrue(s.contains("view.rootView.height"))
    }

    // ---- landscape and auto fit
    @Test fun theRailKeepsSideInsetsOutsideItsWidth() {
        val s = src("ui/living/LivingRail.kt")
        assertTrue(s.contains("windowInsetsPadding(railSides())"))
        assertFalse(s.contains(".navigationBarsPadding()) {"))
        assertTrue(s.contains("WindowInsets.displayCutout.union(WindowInsets.navigationBars)"))
    }

    @Test fun autoFitOnlyShrinksNarrowPhones() {
        assertEquals(1f, LookLogic.fitScale(411f), 0.001f)
        assertEquals(1f, LookLogic.fitScale(360f), 0.001f)
        assertEquals(320f / 360f, LookLogic.fitScale(320f), 0.001f)
        assertEquals(LookLogic.FIT_FLOOR, LookLogic.fitScale(240f), 0.001f)
        assertEquals(1f, LookLogic.fitScale(0f), 0.001f)
        assertTrue(src("ui/Theme.kt").contains("LookLogic.fitScale(sw)"))
    }
}

/** 0.55.9 views redesign (Finn's mockups, 23 Nov 2026): the shared header, Music, Movies and Studying. */
class ViewRedesignTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/" + "bla" + "ze/assistant/$path").readText()
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L

    @Test fun bellNoticesAreRealOrEmpty() {
        assertEquals(listOf("Song \u00b7 42%", "Film"), ViewNotices.downloads(listOf("Song" to "42%", "Film" to "")))
        assertEquals(listOf("3 cards are waiting"), ViewNotices.cards(3, "2 h"))
        assertEquals(listOf("1 card is waiting"), ViewNotices.cards(1, null))
        assertEquals(listOf("Next cards come back in 2 h"), ViewNotices.cards(0, "2 h"))
        assertTrue(ViewNotices.cards(0, null).isEmpty())
    }

    @Test fun everyViewUsesTheSharedHeader() {
        assertTrue("ViewHeader(" in src("mediaapps/MusicShell.kt"))
        assertTrue("MediaTopBar(\"Music\"" in src("mediaapps/MusicActivity.kt"))
        assertTrue("MediaTopBar(\"Movies\"" in src("mediaapps/VideoActivity.kt"))
        assertTrue("ViewHeader(\"Studying\"" in src("ui/LearnPage.kt"))
        assertTrue("page != \"learn\"" in src("ui/SettingsSheet.kt"))
    }

    @Test fun studyingStreakAndRing() {
        val now = 10 * day + 3_600_000L
        val times = listOf(now - 60_000L, now - day, now - 2 * day, now - 4 * day, 0L)
        assertEquals(3, LearnHome.streak(times, now, utc))
        assertEquals(2, LearnHome.streak(listOf(now - day, now - 2 * day), now, utc))   // not yet today: still counts
        assertEquals(0, LearnHome.streak(listOf(now - 3 * day), now, utc))
        val w = LearnHome.week(times, now, utc)
        assertEquals(7, w.size)
        assertEquals(listOf(false, false, true, false, true, true, true), w.map { it.second })
        assertEquals(50, LearnHome.todayPct(5, 5)); assertEquals(100, LearnHome.todayPct(4, 0)); assertEquals(0, LearnHome.todayPct(0, 0))
        assertEquals("Good morning,", LearnHome.greeting(8)); assertEquals("Good evening,", LearnHome.greeting(21))
    }

    @Test fun mediaLines() {
        assertEquals("Mix \u00b7 50 songs", MusicHome.mixLine(50)); assertEquals("Mix \u00b7 1 song", MusicHome.mixLine(1))
        assertEquals("1h 12m left", VideoHome.left(0, 72 * 60_000L)); assertEquals("54m left", VideoHome.left(6 * 60_000L, 60 * 60_000L))
        assertEquals("", VideoHome.left(0, 0)); assertEquals("37 items", VideoHome.items(37))
    }
}
