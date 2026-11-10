package com.blaze.assistant.proactive

import com.blaze.assistant.agent.ReplyChips
import com.blaze.assistant.proactive.ProactiveDial.Level
import com.blaze.assistant.proactive.ProactiveDial.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Unreleased after 0.52.3: the proactivity dial and the "why you got this" line on every heads-up. */
class ProactiveDialTest {
    @Test fun levelsReadBackAndDefaultToNormal() {
        Level.values().forEach { assertEquals(it, Level.of(it.id)) }
        assertEquals(Level.NORMAL, Level.of(""))
        assertEquals(Level.NORMAL, Level.of(null))
        assertEquals(Level.HIGH, Level.of(" HIGH "))
        assertEquals(listOf("Off", "Low", "Normal", "High"), Level.values().map { it.label })
    }

    @Test fun offKeepsOnlyUserReminders() {
        assertTrue(ProactiveDial.allows(Level.OFF, Source.REMINDER, false))
        Source.values().filter { it != Source.REMINDER }.forEach {
            assertFalse(it.name, ProactiveDial.allows(Level.OFF, it, false))
            assertFalse(it.name, ProactiveDial.allows(Level.OFF, it, true))
        }
        assertFalse(ProactiveDial.runsChecks(Level.OFF))
        assertTrue(ProactiveDial.runsChecks(Level.LOW))
    }

    @Test fun lowKeepsUrgentAndAskedFollowUps() {
        assertTrue(ProactiveDial.allows(Level.LOW, Source.FOLLOW_UP_ASKED, false))
        assertTrue(ProactiveDial.allows(Level.LOW, Source.DEVICE, true))
        assertTrue(ProactiveDial.allows(Level.LOW, Source.CHECK, true))
        assertFalse(ProactiveDial.allows(Level.LOW, Source.FOLLOW_UP, false))
        assertFalse(ProactiveDial.allows(Level.LOW, Source.HABIT, false))
        assertFalse(ProactiveDial.allows(Level.LOW, Source.BRIEF, false))
        assertFalse(ProactiveDial.allows(Level.LOW, Source.CHECK_IN, false))
    }

    @Test fun normalAndHighKeepEverything() {
        for (l in listOf(Level.NORMAL, Level.HIGH)) Source.values().forEach { assertTrue(ProactiveDial.allows(l, it, false)) }
        assertEquals(4, ProactiveDial.habitDays(Level.NORMAL))
        assertEquals(4, ProactiveDial.habitDays(Level.LOW))
        assertEquals(3, ProactiveDial.habitDays(Level.HIGH))
        Level.values().forEach { assertTrue(ProactiveDial.note(it).isNotBlank()) }
    }

    @Test fun reasonsAreShortAndSpecific() {
        assertEquals("You usually open WhatsApp around 8:00", ProactiveDial.Why.habit("WhatsApp", 8))
        assertEquals("New notification from Slack", ProactiveDial.Why.notification("Slack"))
        assertEquals("Jack is waiting on a reply on WhatsApp", ProactiveDial.Why.followUp("Jack Banda", "WhatsApp"))
        assertTrue(ProactiveDial.Why.followUpAsked(0L, Locale.US).startsWith("Follow-up you asked for on "))
        assertTrue(ProactiveDial.Why.habit("A".repeat(200), 9).length <= ProactiveDial.MAX)
    }

    @Test fun metaCarriesTheReasonOutsideTheChip() {
        assertEquals("Heads-up \u00b7 Tue 09:10", ProactiveDial.meta("Tue 09:10", ""))
        val m = ProactiveDial.meta("Tue 09:10", "New notification from Slack")
        assertEquals("New notification from Slack", ProactiveDial.whyOf(m))
        assertEquals("Heads-up \u00b7 Tue 09:10", ProactiveDial.chipOf(m))
        assertEquals("Heads-up \u00b7 Tue 09:10", ReplyChips.visible(m, false))
        assertEquals("", ProactiveDial.whyOf("Heads-up \u00b7 Tue 09:10"))
        // a chip added later stays in the chip
        assertEquals("Heads-up \u00b7 Tue 09:10 \u00b7 Memory updated", ProactiveDial.chipOf("$m \u00b7 Memory updated"))
        assertEquals("New notification from Slack", ProactiveDial.whyOf("$m \u00b7 Memory updated"))
    }
}
