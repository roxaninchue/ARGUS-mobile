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

    /** Owner 2026-11-19: one switch pauses every unprompted heads-up at every level; switching it back restores the dial. */
    @Test fun pausedStopsEveryUnpromptedHeadsUp() {
        val unprompted = listOf(Source.CHECK, Source.BRIEF, Source.NEWS, Source.FOLLOW_UP, Source.CHECK_IN, Source.GLANCE,
            Source.ROUTINE_IDEA, Source.DEVICE, Source.HABIT)
        for (l in Level.values()) {
            for (s in unprompted) for (u in listOf(false, true)) assertFalse("$l $s urgent=$u", ProactiveDial.allows(l, s, u, paused = true))
            assertTrue("reminders still come", ProactiveDial.allows(l, Source.REMINDER, false, paused = true))
            assertTrue("a follow-up the user said yes to is theirs", ProactiveDial.allows(l, Source.FOLLOW_UP_ASKED, false, paused = true))
            assertFalse("no background check while paused", ProactiveDial.runsChecks(l, paused = true))
            // not paused: exactly the dial as before
            for (s in Source.values()) for (u in listOf(false, true))
                assertEquals("$l $s $u", ProactiveDial.allows(l, s, u), ProactiveDial.allows(l, s, u, paused = false))
            assertEquals(ProactiveDial.runsChecks(l), ProactiveDial.runsChecks(l, paused = false))
        }
        assertEquals("headsups_paused", ProactiveDial.PAUSED_KEY)
        assertTrue(ProactiveDial.pausedNote(true).contains("reminders") && ProactiveDial.pausedNote(true).contains("finished tasks"))
        assertTrue(ProactiveDial.pausedNote(false).isNotBlank())
    }

    /** The pause is read everywhere ARGUS starts a message on its own, before any model call where there is one. */
    @Test fun everyUnpromptedSourceHonoursThePause() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        val base = ProactiveDial::class.java.`package`!!.name.substringBeforeLast('.').replace('.', '/')
        fun src(p: String) = java.io.File(root, "$base/$p").readText()
        val pro = src("proactive/Proactive.kt")
        assertTrue(pro.contains("if (!ProactiveDial.allows(level, source, urgent, paused)) return"))
        assertTrue(pro.contains("ProactiveDial.runsChecks(level, paused)"))
        assertTrue(pro.contains("Settings.bool(ProactiveDial.PAUSED_KEY, false)"))
        assertTrue("Jarvis: questions, glances, routine ideas, call offers", src("proactive/Jarvis.kt").contains("if (Proactive.paused) return@withLock"))
        assertTrue(src("proactive/Jarvis.kt").contains("ProactiveDial.Source.CHECK_IN, false, Proactive.paused)"))
        val fu = src("proactive/FollowUps.kt")
        assertTrue(fu.contains("if (Proactive.paused) return   // owner 2026-11-19") && fu.contains("ProactiveDial.Source.FOLLOW_UP, false, Proactive.paused)"))
        assertTrue(src("proactive/Habits.kt").contains("if (!Proactive.paused && recent.size >="))
        assertTrue(src("home/HomeNews.kt").contains("if (Proactive.paused) return"))
        assertTrue("one switch in Settings", src("ui/SettingsV18.kt").contains("ToggleRow(\"Pause heads-ups\""))
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
