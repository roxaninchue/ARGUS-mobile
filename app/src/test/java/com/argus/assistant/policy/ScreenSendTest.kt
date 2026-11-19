package com.blaze.assistant.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.53.0 card fixes: screen sends as drafts, Connect first, readable approval text (ScreenSend, PolicyGate.detail). */
class ScreenSendTest {
    private val gmail = """{"steps":[{"do":"open_app","app":"Gmail"},{"do":"tap","target":"Compose"},
        {"do":"type","target":"To","text":"dana@example.com"},{"do":"type","target":"Subject","text":"Friday"},
        {"do":"type","target":"Compose email","text":"See you at 7."},{"do":"tap","target":"Send"}]}"""

    @Test fun aGmailComposePlanIsADraft() {
        val s = ScreenSend.of("run_plan", gmail)
        assertNotNull(s)
        assertEquals("Gmail", s!!.app)
        assertEquals(listOf("dana@example.com"), s.draft.recipients)
        assertEquals("Friday", s.draft.subject)
        assertEquals("See you at 7.", s.draft.body)
        assertEquals("run_plan", s.draft.tool)
    }

    @Test fun cardsTreatTheScreenSendAsADraftAndKeepRealSendTools() {
        assertEquals("See you at 7.", Cards.draftFor("run_plan", gmail, sendClass = false)?.body)
        assertEquals("hi", Cards.draftFor("send_email", """{"to":"a@b.c","body":"hi"}""", sendClass = true)?.body)
        assertNull(Cards.draftFor("open_app", """{"app":"Gmail"}""", sendClass = false))
    }

    @Test fun plansWithoutTypingThenSendAreNotSends() {
        assertNull(ScreenSend.of("run_plan", """{"steps":[{"do":"open_app","app":"Settings"},{"do":"tap","target":"Wi-Fi","want":"on"}]}"""))
        assertNull(ScreenSend.of("run_plan", """{"steps":[{"do":"tap","target":"Send"}]}"""))
        assertNull(ScreenSend.of("run_plan", """{"steps":[{"do":"tap","target":"Send"},{"do":"type","target":"Search","text":"x"}]}"""))
        assertNull(ScreenSend.of("tap", """{"target":"Send"}"""))
        assertNull(ScreenSend.of("run_plan", "not json"))
    }

    @Test fun anEditedScreenDraftPassMatchesTheRetriedPlan() {
        val edited = Cards.Draft("run_plan", listOf("dana@example.com"), "Friday", "See you at 8.")
        val retry = gmail.replace("See you at 7.", "See you at 8.")
        assertEquals(edited.key, Cards.draftFor("run_plan", retry, false)!!.key)
        assertTrue(Cards.edited(edited).contains("same steps"))
    }

    @Test fun approvalTextIsWordsNotJson() {
        val d = PolicyGate.detail("run_plan", """{"steps":[{"do":"open_app","app":"Settings"},{"do":"tap","target":"Wi-Fi","want":"on"}]}""", "Requested after reading the screen.")
        assertEquals("run plan\n1. Open Settings\n2. Tap Wi-Fi", d)
        assertFalse(d.contains("{"))
        assertFalse("note is drawn once, on its own line", d.contains("Requested after reading"))
        assertEquals("set volume\nlevel: 5\nstream: music", PolicyGate.detail("set_volume", """{"level":5,"stream":"music"}""", null))
        assertEquals("wifi: on: true", PolicyGate.detail("wifi", """{"on":true}""", null))
        assertEquals("open settings", PolicyGate.detail("open_settings", "{}", null))
    }
}
