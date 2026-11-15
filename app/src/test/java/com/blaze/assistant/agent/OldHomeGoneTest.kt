package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-15: "I don't ever want this screen to show again, it happens after sign in or onboarding." The 0.52.3
 * Home (greeting card, "Finishing setup", "Try asking", "+ New project") showed inside the ARGUS shell because finishing
 * onboarding did `switchTab(ChatNav.HOME)` and ChatScreen draws `homePane()` for that tab. ChatNav now never yields the
 * Home tab, so every route lands on Chat instead.
 */
class OldHomeGoneTest {
    private val all = listOf(ChatNav.CHAT, ChatNav.PROJECTS, ChatNav.HOME, ChatNav.TEAM, ChatNav.LIFE, ChatNav.DESK, "", "nope", null)

    @Test fun finishingOnboardingOrSignInLandsOnChat() {
        // ChatScreen's onboarding onNext (and Settings' onTab) call switchTab with the old Home's id
        for (from in listOf(ChatNav(), ChatNav(ChatNav.TEAM), ChatNav(ChatNav.LIFE), ChatNav().openProject("p1"), ChatNav().openChat("p1", "c1")))
            assertEquals("from ${from.tab}", ChatNav.CHAT, from.switchTab(ChatNav.HOME).tab)
    }

    @Test fun noTabResolvesToTheOldHome() {
        for (t in all) {
            assertFalse("tabOf($t)", ChatNav.tabOf(t) == ChatNav.HOME)
            assertFalse("switchTab($t)", ChatNav().switchTab(t ?: "").tab == ChatNav.HOME)
        }
        for (argus in listOf(true, false)) assertFalse("rootTab($argus)", ChatNav.rootTab(argus) == ChatNav.HOME)
        assertEquals(ChatNav.TEAM, ChatNav.rootTab(true))
        assertEquals(ChatNav.CHAT, ChatNav.rootTab(false))
        for (t in all) assertEquals("startTab($t)", ChatNav.CHAT, ChatNav.startTab(t))
    }

    @Test fun processDeathRestoreAndOldSavedStateLandOnChat() {
        // rememberSaveable keeps ChatNav.encode(); a state saved while the Home tab was up (before this fix) comes back as Chat
        val saved = listOf(ChatNav.HOME, "", "").joinToString("\u001f")
        assertEquals(ChatNav.CHAT, ChatNav.decode(saved).tab)
        assertEquals(ChatNav.CHAT, ChatNav.decode(ChatNav(ChatNav.HOME).encode()).tab)
        // every other tab still round-trips
        for (t in listOf(ChatNav.CHAT, ChatNav.TEAM, ChatNav.LIFE, ChatNav.DESK))
            assertEquals(t, ChatNav.decode(ChatNav(t).encode()).tab)
        assertEquals(ChatNav().openChat("p1", "c1"), ChatNav.decode(ChatNav().openChat("p1", "c1").encode()))
    }

    @Test fun theOtherRulesAreUnchanged() {
        // the ARGUS shell's /home and open_view home still open Life (P1-SH-01 decision), modes and Projects keep their tabs
        assertEquals(ChatNav.LIFE, ChatNav.argusTab(ChatNav.HOME))
        for (m in ChatNav.MODES) assertEquals(m, ChatNav().switchTab(m).tab)
        assertEquals(ChatNav.PROJECTS, ChatNav().switchTab(ChatNav.PROJECTS).tab)
        assertTrue(ChatNav.isMode(ChatNav.CHAT))
    }

    @Test fun chatScreenOnlyReachesTheHomePaneThroughTheHomeTab() {
        // ChatScreen draws the old Home (homePane / SpaceHomeLayer) only for pane "home", i.e. navShown.tab == HOME,
        // and builds its nav only from rootTab / decode / switchTab. No ChatNav(HOME) or copy(tab = HOME) anywhere in main.
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val chat = File(root, "com/blaze/assistant/ui/ChatScreen.kt").readText()
        assertTrue("navShown.tab == ChatNav.HOME -> \"home\"" in chat)
        assertTrue("ChatNav(tab = ChatNav.rootTab(argus))" in chat)
        val direct = Regex("""ChatNav\(\s*(tab\s*=\s*)?(ChatNav\.)?HOME\b|copy\(\s*tab\s*=\s*(ChatNav\.)?HOME\b|ChatNav\(\s*"home"""")
        val hits = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { direct.containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals("nothing builds a ChatNav on the old Home tab directly", emptyList<String>(), hits)
    }
}
