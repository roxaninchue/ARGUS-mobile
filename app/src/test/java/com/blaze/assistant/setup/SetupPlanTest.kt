package com.blaze.assistant.setup

import com.blaze.assistant.setup.SetupPlan.Phase
import com.blaze.assistant.setup.SetupPlan.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52.3 one-screen setup (setup/SetupPlan.kt): the list, the weighted overall progress, what may run now (order, Wi-Fi,
 * offline, back-off), retry, resume (only settled results are kept), when the screen shows and BlazeApp's later start
 * for people past onboarding. The phone side (SetupRunner) is wired by SpaceWiringTest and run by SpaceOnboardingCheck.
 */
class SetupPlanTest {
    private fun st(vararg p: Pair<String, Phase>) = p.associate { it.first to Status(it.second) }
    private fun all(ph: Phase) = SetupPlan.ITEMS.associate { it.id to Status(ph) }

    @Test fun theListIsEverythingArgusDownloads() {
        assertEquals(listOf("linux", "tools", "skills", "brain", "voice", "quick"), SetupPlan.ITEMS.map { it.id })
        assertEquals(SetupPlan.ITEMS.size, SetupPlan.ITEMS.map { it.title }.toSet().size)
        for (i in SetupPlan.ITEMS) {
            assertTrue(i.id, i.mb > 0)
            assertTrue(i.id, i.title.isNotBlank() && i.sub.isNotBlank())
            i.after?.let { a -> assertTrue("${i.id} after $a", SetupPlan.ITEMS.indexOfFirst { it.id == a } < SetupPlan.ITEMS.indexOf(i)) }
        }
        // the big downloads wait for Wi-Fi; Linux and its tools don't (they're small enough and needed for tasks)
        assertEquals(setOf("brain", "voice", "quick"), SetupPlan.ITEMS.filter { it.wifi }.map { it.id }.toSet())
        assertNull(SetupPlan.item("nope"))
        assertEquals("tools", SetupPlan.item("tools")!!.id)
    }

    @Test fun overallProgressIsWeightedAndIgnoresSkipped() {
        assertEquals(0f, SetupPlan.overall(emptyMap()), 0f)
        assertEquals(1f, SetupPlan.overall(all(Phase.DONE)), 0f)
        assertEquals(1f, SetupPlan.overall(all(Phase.SKIPPED)), 0f)
        val total = SetupPlan.ITEMS.sumOf { it.mb }.toFloat()
        assertEquals(60f / total, SetupPlan.overall(st("tools" to Phase.DONE)), 0.0001f)
        // a running item counts its own progress
        val half = mapOf("brain" to Status(Phase.RUNNING, 50))
        assertEquals(39f / total, SetupPlan.overall(half), 0.0001f)
        assertEquals(0f, SetupPlan.overall(mapOf("brain" to Status(Phase.RUNNING, -1))), 0f)
        // skipped items leave the total
        val skipBrain = st("brain" to Phase.SKIPPED, "tools" to Phase.DONE)
        assertEquals(60f / (total - 78f), SetupPlan.overall(skipBrain), 0.0001f)
        assertEquals((60f / total * 100f).toInt(), SetupPlan.percent(st("tools" to Phase.DONE)))
        // it only goes up as items finish
        var s = emptyMap<String, Status>(); var last = 0f
        for (i in SetupPlan.ITEMS) { s = s + (i.id to Status(Phase.DONE)); val o = SetupPlan.overall(s); assertTrue(o >= last); last = o }
        assertEquals(100, SetupPlan.percent(s))
    }

    @Test fun completeAndLeft() {
        assertFalse(SetupPlan.complete(emptyMap()))
        assertTrue(SetupPlan.complete(all(Phase.DONE)))
        val mixed = all(Phase.DONE) + ("voice" to Status(Phase.SKIPPED)) + ("quick" to Status(Phase.FAILED))
        assertFalse(SetupPlan.complete(mixed))
        assertEquals(listOf("quick"), SetupPlan.left(mixed).map { it.id })
        assertTrue(SetupPlan.settled(Status(Phase.DONE)) && SetupPlan.settled(Status(Phase.SKIPPED)))
        for (p in listOf(Phase.PENDING, Phase.RUNNING, Phase.WAITING_WIFI, Phase.OFFLINE, Phase.FAILED)) assertFalse(SetupPlan.settled(Status(p)))
        assertFalse(SetupPlan.settled(null))
    }

    @Test fun headlinesAndRowLines() {
        assertEquals("ARGUS is ready", SetupPlan.headline(all(Phase.DONE)))
        assertEquals("Setting up ARGUS\u2026", SetupPlan.headline(st("linux" to Phase.RUNNING)))
        assertEquals("Waiting for Wi-Fi to finish", SetupPlan.headline(all(Phase.DONE) + st("brain" to Phase.WAITING_WIFI, "voice" to Phase.WAITING_WIFI)))
        assertEquals("Waiting for a connection", SetupPlan.headline(all(Phase.OFFLINE)))
        val brain = SetupPlan.item("brain")!!
        assertEquals("Waiting", SetupPlan.line(brain, null))
        assertEquals("42%", SetupPlan.line(brain, Status(Phase.RUNNING, 42)))
        assertEquals("Unpacking", SetupPlan.line(brain, Status(Phase.RUNNING, -1, "Unpacking")))
        assertEquals("Working\u2026", SetupPlan.line(brain, Status(Phase.RUNNING)))
        assertEquals("Ready", SetupPlan.line(brain, Status(Phase.DONE)))
        assertEquals("On Wi-Fi \u00b7 78 MB", SetupPlan.line(brain, Status(Phase.WAITING_WIFI)))
        assertEquals("When you're online", SetupPlan.line(brain, Status(Phase.OFFLINE)))
        assertEquals("Retrying soon", SetupPlan.line(brain, Status(Phase.FAILED)))
        assertEquals("Not needed on this phone", SetupPlan.line(brain, Status(Phase.SKIPPED)))
        assertEquals("Turned off", SetupPlan.line(brain, Status(Phase.SKIPPED, note = "Turned off")))
    }

    @Test fun whatMayRunNow() {
        val ids = { s: Map<String, Status>, online: Boolean, wifi: Boolean -> SetupPlan.runnable(s, online, wifi, 1_000L).map { it.id } }
        // first run on Wi-Fi: everything without a prerequisite
        assertEquals(listOf("linux", "skills", "voice", "quick"), ids(emptyMap(), true, true))
        // on mobile data the big ones wait
        assertEquals(listOf("linux", "skills"), ids(emptyMap(), true, false))
        // offline: nothing
        assertEquals(emptyList<String>(), ids(emptyMap(), false, true))
        // tools and the backup brain follow Linux
        assertEquals(listOf("tools", "skills", "brain", "voice", "quick"), ids(st("linux" to Phase.DONE), true, true))
        assertTrue("brain" in ids(st("linux" to Phase.SKIPPED), true, true))
        // running and settled items are not started again
        assertFalse("linux" in ids(st("linux" to Phase.RUNNING), true, true))
        assertFalse("skills" in ids(st("skills" to Phase.DONE), true, true))
    }

    @Test fun failuresBackOffAndStopAfterTheAutomaticTries() {
        assertEquals(listOf(0L, 30_000L, 120_000L, 480_000L, 1_800_000L, 1_800_000L), (0..5).map { SetupPlan.backoffMs(it) })
        val f1 = SetupPlan.failed(null, "x".repeat(300), 1_000L)
        assertEquals(Phase.FAILED, f1.phase); assertEquals(1, f1.tries); assertEquals(31_000L, f1.retryAt); assertEquals(120, f1.note.length)
        val f2 = SetupPlan.failed(f1, "again", 2_000L)
        assertEquals(2, f2.tries); assertEquals(122_000L, f2.retryAt)
        val s = mapOf("skills" to f1)
        assertFalse("skills" in SetupPlan.runnable(s, true, true, 30_999L).map { it.id })
        assertTrue("skills" in SetupPlan.runnable(s, true, true, 31_000L).map { it.id })
        val spent = mapOf("skills" to Status(Phase.FAILED, tries = SetupPlan.MAX_AUTO_TRIES, retryAt = 0L))
        assertFalse("skills" in SetupPlan.runnable(spent, true, true, Long.MAX_VALUE).map { it.id })
        // Retry: failed items start over at once, everything else is kept
        val r = SetupPlan.retryAll(spent + st("linux" to Phase.DONE))
        assertEquals(Status(), r["skills"]); assertEquals(Phase.DONE, r["linux"]!!.phase)
        assertTrue("skills" in SetupPlan.runnable(r, true, true, 0L).map { it.id })
    }

    @Test fun waitingRowsSayWhy() {
        val brain = SetupPlan.item("brain")!!; val linux = SetupPlan.item("linux")!!
        assertEquals(Phase.OFFLINE, SetupPlan.waiting(linux, null, online = false, unmetered = false)!!.phase)
        assertEquals(Phase.WAITING_WIFI, SetupPlan.waiting(brain, null, online = true, unmetered = false)!!.phase)
        assertNull(SetupPlan.waiting(linux, null, online = true, unmetered = false))
        assertNull(SetupPlan.waiting(brain, Status(Phase.DONE), false, false))
        assertNull(SetupPlan.waiting(brain, Status(Phase.RUNNING), false, false))
        assertNull(SetupPlan.waiting(brain, Status(Phase.FAILED, tries = 1), false, false))
    }

    @Test fun onlySettledResultsAreKeptForResume() {
        val s = st("linux" to Phase.DONE, "voice" to Phase.SKIPPED, "brain" to Phase.RUNNING, "quick" to Phase.FAILED)
        val raw = SetupPlan.encode(s)
        assertEquals("linux=DONE,voice=SKIPPED", raw)
        assertEquals(st("linux" to Phase.DONE, "voice" to Phase.SKIPPED), SetupPlan.decode(raw))
        assertEquals(emptyMap<String, Status>(), SetupPlan.decode(""))
        assertEquals(emptyMap<String, Status>(), SetupPlan.decode("nope=DONE,linux=RUNNING,brain=BOGUS,,="))
        assertEquals(st("tools" to Phase.DONE), SetupPlan.decode(" tools = DONE "))
        assertEquals("space_setup_v1", SetupPlan.PREF)
    }

    @Test fun whenTheScreenShowsAndTheLaterStart() {
        assertTrue(SetupPlan.showScreen(onboarded = false, started = true, st = emptyMap()))
        assertFalse(SetupPlan.showScreen(onboarded = true, started = true, st = emptyMap()))
        assertFalse(SetupPlan.showScreen(onboarded = false, started = false, st = emptyMap()))
        assertFalse(SetupPlan.showScreen(onboarded = false, started = true, st = all(Phase.DONE)))
        // BlazeApp's start 30 s after the first frame: past onboarding (or setup begun) and something left
        assertEquals(30_000L, SetupPlan.LATER_MS)
        assertTrue(SetupPlan.startLater(onboarded = true, started = false, st = emptyMap()))
        assertTrue(SetupPlan.startLater(onboarded = false, started = true, st = emptyMap()))
        assertFalse("first run waits for the onboarding's setup screen", SetupPlan.startLater(onboarded = false, started = false, st = emptyMap()))
        assertFalse("nothing left", SetupPlan.startLater(onboarded = true, started = true, st = all(Phase.DONE)))
    }
}
