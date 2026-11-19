package com.argus.assistant.ui.argus.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2026-11-19 (owner spec): the notch overlay's state machine and backdrop rule, plus where ChatScreen wires them. */
class NotchOverlayTest {
    private val modes = listOf("team", "chat", "life", "desk")

    @Test fun sameModeClosesAndGoesBackToTheNormalPage() {
        for (m in modes) {
            val s = NotchOverlay.tap(m, m)
            assertFalse(s.open); assertEquals(NotchOverlay.Effect.NORMAL, s.effect); assertEquals(m, s.mode)
        }
    }

    @Test fun anotherModeSwitchesAsBefore() {
        for (from in modes) for (to in modes) if (from != to) {
            val s = NotchOverlay.tap(from, to)
            assertFalse(s.open); assertEquals(NotchOverlay.Effect.SWITCH, s.effect); assertEquals(to, s.mode)
        }
    }

    /** Owner, round 2: Back / the edge swipe while open, from ANY mode, closes the overlay and goes to the regular Chat. */
    @Test fun backWhileOpenFromAnyModeGoesToChatAndIsNotOursWhileClosed() {
        for (m in modes) {
            val s = NotchOverlay.back(open = true, current = m)
            assertFalse(s.open); assertEquals(NotchOverlay.Effect.CHAT, s.effect); assertEquals("chat", s.mode)
        }
        for (m in modes) assertEquals(NotchOverlay.Effect.NONE, NotchOverlay.back(open = false, current = m).effect)
        assertEquals("life", NotchOverlay.back(false, "life").mode)
    }

    @Test fun xGoesToChatAndTheBadgeOpensSettings() {
        val x = NotchOverlay.close()
        assertFalse(x.open); assertEquals(NotchOverlay.Effect.CHAT, x.effect); assertEquals("chat", x.mode)
        assertEquals(NotchOverlay.back(true, "desk"), x)   // the X is exactly Back
        for (m in modes) {
            val me = NotchOverlay.me(m)
            assertFalse(me.open); assertEquals(NotchOverlay.Effect.SETTINGS, me.effect); assertEquals(m, me.mode)
        }
    }

    @Test fun outsideTapAndToggle() {
        val o = NotchOverlay.outside("life")
        assertFalse(o.open); assertEquals(NotchOverlay.Effect.NONE, o.effect)
        assertTrue(NotchOverlay.toggle(false, "chat").open)
        assertFalse(NotchOverlay.toggle(true, "chat").open)
    }

    @Test fun backdropBlursOnlyWhenAllowedAndMotionIsOn() {
        assertEquals(NotchOverlay.Backdrop.BLUR, NotchOverlay.backdrop(blurOk = true, reducedMotion = false))
        assertEquals(NotchOverlay.Backdrop.SCRIM, NotchOverlay.backdrop(blurOk = true, reducedMotion = true))
        assertEquals(NotchOverlay.Backdrop.SCRIM, NotchOverlay.backdrop(blurOk = false, reducedMotion = false))
        assertEquals(NotchOverlay.Backdrop.SCRIM, NotchOverlay.backdrop(blurOk = false, reducedMotion = true))
        // light: under the Chrome glass's 24 dp, a slight scrim on the blur, a stronger plain one without it
        assertTrue(NotchOverlay.BLUR_DP in 1 until 24)
        assertTrue(NotchOverlay.scrimAlpha(NotchOverlay.Backdrop.BLUR) <= 0.15f)
        assertTrue(NotchOverlay.scrimAlpha(NotchOverlay.Backdrop.SCRIM) > NotchOverlay.scrimAlpha(NotchOverlay.Backdrop.BLUR))
        assertEquals(0, NotchOverlay.fadeMs(true)); assertTrue(NotchOverlay.fadeMs(false) > 0)
    }

    /** Source scan: ChatScreen runs the pill through these rules, blurs the chat sheet only while open, and keeps Back. */
    @Test fun chatScreenWiresTheOverlay() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        fun src(name: String) = root.walkTopDown().first { it.name == name }.readText()
        val chat = src("ChatScreen.kt")
        assertTrue("the chat sheet is the blur's source only while the pill is open", ".then(if (notchOpen) Modifier.haze(notchHaze) else Modifier)" in chat)
        assertTrue("NotchBackdrop(notchHaze, onClose = " in chat)
        assertTrue("X = Chat", "onExit = { notchStep(NotchOverlay.close()) }" in chat)
        assertTrue("badge = Settings", "onMe = { notchStep(NotchOverlay.me(modeNow)) }" in chat)
        assertTrue("Settings the way the top bar's badge opens it", "NotchOverlay.Effect.SETTINGS -> settingsPage = \"\"" in chat && "onMe = { notchOpen = false; settingsPage = \"\" }" in chat)
        assertTrue("a switch goes through the tab switch", "NotchOverlay.Effect.SWITCH -> { exitView(); composer.closeSpotlight(); go(navNow().switchTab(st.mode)) }" in chat)
        assertTrue("Chat = the plain chat, nothing over it", "NotchOverlay.Effect.CHAT -> { exitView(); composer.closeSpotlight(); botThread = null; appsOpen = false; go(navNow().switchTab(st.mode)) }" in chat)
        assertTrue("Back (the overlay's handler) = Chat", "BackHandler { notchStep(NotchOverlay.back(true, modeNow)) }" in chat)
        // BackRule's window interceptor runs backToChat before any BackHandler: it must take the notch to Chat first
        val btc = chat.substringAfter("fun backToChat() {").substringBefore("val over = ")
        assertTrue("backToChat sends Back with the notch open to Chat", "if (notchOpen && settingsPage == null) { notchStep(NotchOverlay.back(true, navNow().tab)); return }" in btc)
        assertTrue("a mode tap goes through the rules", "onMode = { m -> notchStep(NotchOverlay.tap(modeNow, m)) }" in chat)
        assertFalse("the round-1 Back (normal page) is gone", "BackHandler { notchOpen = false; exitView(); composer.closeSpotlight() }" in chat)
        assertFalse("the old bare outside-tap box is gone", "indication = null) { notchOpen = false })" in chat)
        val glance = src("NotchGlance.kt")
        assertTrue("blur through Haze", "hazeChild(haze, HazeStyle(" in glance && "NotchOverlay.backdrop(rememberArgusBlur(), reduced)" in glance)
        assertTrue("Reduced motion read from the app's setting", "ArgusPrefs.reducedNow()" in glance)
        assertTrue("endless, lazily", "LazyColumn(" in glance && "Glance.needMore(" in glance && "GlanceStore.window(ctx, loaded, Glance.PAGE)" in glance)
        assertTrue("the page's own bar", "GlanceBar(initial, onMe = onMe, onExit = onExit)" in glance)
        val bar = src("GlanceBar.kt")
        assertTrue("badge = the top bar's avatar", "ArgusIconButton(\"user\", KitA11y.Shell.ME, onMe, kind = IconButtonKind.AVATAR" in bar)
        assertTrue("X = the top bar's round glass button with Lucide's x", "ArgusIconButton(\"x\", tr(\"Close\"), onExit)" in bar)
        val top = src("KitShell.kt")
        assertTrue("the top bar's +, search and bell use the same default (glass) button", "ArgusIconButton(\"plus\", KitA11y.Shell.NEW, onPlus)" in top)
    }
}
