package com.blaze.assistant.ui.argus.kit

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

    @Test fun backWhileOpenClosesToTheNormalPageAndIsNotOursWhileClosed() {
        val open = NotchOverlay.back(open = true, current = "chat")
        assertFalse(open.open); assertEquals(NotchOverlay.Effect.NORMAL, open.effect); assertEquals("chat", open.mode)
        assertEquals(NotchOverlay.Effect.NONE, NotchOverlay.back(open = false, current = "chat").effect)
        // Back never switches mode
        for (m in modes) assertEquals(m, NotchOverlay.back(true, m).mode)
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
        assertTrue("Back = the normal page", "BackHandler { notchOpen = false; exitView(); composer.closeSpotlight() }" in chat)
        assertTrue("only another mode switches", "if (st.effect == NotchOverlay.Effect.SWITCH) go(navNow().switchTab(st.mode))" in chat)
        assertFalse("the old bare outside-tap box is gone", "indication = null) { notchOpen = false })" in chat)
        val glance = src("NotchGlance.kt")
        assertTrue("blur through Haze", "hazeChild(haze, HazeStyle(" in glance && "NotchOverlay.backdrop(rememberArgusBlur(), reduced)" in glance)
        assertTrue("Reduced motion read from the app's setting", "ArgusPrefs.reducedNow()" in glance)
    }
}
