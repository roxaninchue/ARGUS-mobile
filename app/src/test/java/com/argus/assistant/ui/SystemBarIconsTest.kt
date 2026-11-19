package com.argus.assistant.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner 2026-11-18: the status bar (clock, Wi-Fi, signal, battery) was white on ARGUS's light pages. The icons follow
 * ARGUS's own light / dark, not the phone's, and stay light over a screen that is black in both themes.
 */
class SystemBarIconsTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/argus/assistant/$path").readText()

    @Test fun iconsFollowTheAppTheme() {
        assertTrue("light ARGUS: dark icons", SystemBarIcons.darkIcons(appDark = false, darkScreenShowing = false))
        assertFalse("dark ARGUS: light icons", SystemBarIcons.darkIcons(appDark = true, darkScreenShowing = false))
        assertFalse("a black screen over light ARGUS: light icons", SystemBarIcons.darkIcons(appDark = false, darkScreenShowing = true))
    }

    @Test fun mainActivitySyncsTheIcons() {
        assertTrue(src("MainActivity.kt").contains("SyncSystemBarIcons("))
    }

    @Test fun theModePagerJumpsStraightToFarModes() {
        // Team to Life must not scroll through Chat (an old chat flashed past)
        assertTrue(src("ui/ArgusModes.kt").contains("pager.scrollToPage(want)"))
    }

    @Test fun theChatPageKeepsChatModeWhileLeaving() {
        // the pager's Chat page stays Chat mode (the current chat) while the tab is already another one
        val s = src("ui/ChatScreen.kt")
        assertTrue(s.contains("chat = { chatPane(true) }"))
        assertTrue(s.contains("(modePage || nav.tab == ChatNav.CHAT)"))
    }
}
