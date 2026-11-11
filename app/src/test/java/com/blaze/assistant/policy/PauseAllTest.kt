package com.blaze.assistant.policy

import com.blaze.assistant.policy.Kill.BotRun
import com.blaze.assistant.policy.Kill.Budget
import com.blaze.assistant.policy.Kill.Core
import com.blaze.assistant.policy.Kill.Held
import com.blaze.assistant.policy.Kill.Kind
import com.blaze.assistant.policy.Kill.Source
import com.blaze.assistant.policy.Kill.Step
import com.blaze.assistant.policy.Kill.Store
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.BotThreads
import com.blaze.assistant.team.Bots
import com.blaze.assistant.team.JobQueue
import com.blaze.assistant.team.JobRun
import com.blaze.assistant.team.MemoryTeamPort
import com.blaze.assistant.team.Rows
import com.blaze.assistant.team.TeamStore
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-SF-06: Pause all agents holds the job queue and routines until resumed (05 §7.7, NEW-11). */
class PauseAllTest {

    @Test fun pauseAllStopsEveryRunningTurn() {
        val k = Core()
        val a = k.begin("nova")!!; val b = k.begin("atlas", Source.JOB)!!
        var aborts = 0
        a.onStop { aborts++ }; b.onStop { aborts++ }
        assertEquals(2, k.pauseAll())
        assertTrue(a.isStopped); assertTrue(b.isStopped); assertEquals(2, aborts)
        assertTrue(k.state.value.allPaused)
        k.end(a); k.end(b)
        assertEquals(BotRun.PAUSED, k.run("nova")); assertEquals(BotRun.PAUSED, k.run("anyone"))
        assertEquals(0, k.pauseAll())   // again: nothing left to stop
    }

    @Test fun pauseAllHoldsJobsAndRoutinesUntilResumed() {
        val k = Core()
        val released = mutableListOf<Held>()
        k.onRelease { released += it }
        assertTrue(k.admit("nova", Kind.JOB, "j1"))          // not paused: runs
        k.pauseAll()
        assertFalse(k.admit("nova", Kind.JOB, "j2"))
        assertFalse(k.admit("manager", Kind.ROUTINE, "7"))
        assertFalse(k.admit("atlas", Kind.JOB, "j3"))
        assertFalse(k.admit("nova", Kind.JOB, "j2"))         // asked twice: held once
        assertNull(k.begin("nova", Source.ROUTINE))
        assertNull(k.begin("atlas", Source.AGENT))
        assertEquals(3, k.state.value.held.size)
        assertTrue(released.isEmpty())                        // nothing leaks out while paused
        val back = k.resumeAll()
        val want = listOf(Held("nova", Kind.JOB, "j2"), Held("manager", Kind.ROUTINE, "7"), Held("atlas", Kind.JOB, "j3"))
        assertEquals(want, back)                              // arrival order
        assertEquals(want, released)
        assertTrue(k.state.value.held.isEmpty())
        assertFalse(k.state.value.allPaused)
        assertTrue(k.admit("nova", Kind.JOB, "j4"))
        assertNotNull(k.begin("nova", Source.ROUTINE))
    }

    @Test fun usersOwnMessageIsNeverHeld() {
        val k = Core()
        k.pauseAll()
        val t = k.begin("nova", Source.USER)
        assertNotNull(t)
        assertEquals(Step.CONTINUE, t!!.step())
        assertTrue(k.admit("nova", Kind.JOB, "asked-now", Source.USER))
    }

    @Test fun aBotPausedOnItsOwnStaysHeldAfterResumeAll() {
        val k = Core()
        k.pauseBot("atlas")
        k.pauseAll()
        assertFalse(k.admit("atlas", Kind.JOB, "a1"))
        assertFalse(k.admit("nova", Kind.JOB, "n1"))
        assertEquals(listOf(Held("nova", Kind.JOB, "n1")), k.resumeAll())
        assertTrue(k.isPaused("atlas")); assertFalse(k.isPaused("nova"))
        assertEquals(listOf(Held("atlas", Kind.JOB, "a1")), k.resumeBot("atlas"))
        assertFalse(k.isPaused("atlas"))
    }

    @Test fun resumeBotUnderPauseAllKeepsHolding() {
        val k = Core()
        k.pauseBot("atlas"); k.pauseAll()
        assertFalse(k.admit("atlas", Kind.ROUTINE, "r1"))
        assertTrue(k.resumeBot("atlas").isEmpty())            // Pause all still holds it
        assertTrue(k.isPaused("atlas"))
        assertEquals(listOf(Held("atlas", Kind.ROUTINE, "r1")), k.resumeAll())
    }

    @Test fun pauseIsPersisted() {
        val store = Store.Memory()
        val k1 = Core(store)
        k1.pauseAll(); k1.pauseBot("atlas")
        val k2 = Core(store)                               // app restarted
        assertTrue(k2.allPaused); assertTrue(k2.isPaused("nova")); assertTrue(k2.isBotPaused("atlas"))
        assertTrue(k2.state.value.allPaused)
        assertEquals(setOf("atlas"), k2.state.value.pausedBots)
        k2.resumeAll()
        assertFalse(store.allPaused())
        assertTrue(store.pausedBots().contains("atlas"))
    }

    @Test fun notchLongPressToggles() {
        val k = Core()
        val t = k.begin("nova", Source.JOB)!!
        assertTrue(k.toggleAll())
        assertTrue(t.isStopped); assertTrue(k.allPaused)
        assertFalse(k.admit("nova", Kind.JOB, "x"))
        assertFalse(k.toggleAll())
        assertFalse(k.allPaused)
        assertTrue(k.admit("nova", Kind.JOB, "y"))
    }

    @Test fun theSharedSwitchDelegates() {
        Kill.install(Store.Memory())
        assertFalse(Kill.isPaused("nova"))
        Kill.pauseAll()
        assertTrue(Kill.isPaused("nova")); assertTrue(Kill.state.value.allPaused)
        assertFalse(Kill.admit("nova", Kind.JOB, "j"))
        assertEquals(listOf(Held("nova", Kind.JOB, "j")), Kill.resumeAll())
        Kill.install(Store.Memory())
    }

    @Test fun pauseHoldsTheRealJobQueueUntilResumed() {
        val now = 1_762_855_200_000L
        val port = MemoryTeamPort()
        val team = TeamStore(port) { now }.also { it.ensure() }
        val q = JobQueue(port, team, BotThreads(port, Rows(port) { now }, team, null) { now }, { now }, { ZoneId.of("UTC") }, cap = { 4 })
        val k = Core()
        q.held = { k.isPaused(it) }   // the default reads the app-wide Kill; this test uses its own
        fun bot(name: String) = (team.create(com.blaze.assistant.team.Bot(id = "", kind = BotKind.CUSTOM, name = name, label = "",
            avatar = Bots.avatar(0, Bots.COLORS[0]), soulPath = "", toolGroups = setOf("web"), createdAt = 0, u = 0)) as TeamStore.Result.Ok).bot
        val nova = bot("Nova"); val atlas = bot("Atlas")
        val a = q.add("Draft the budget", nova.id).job
        val b = q.add("Book the venue", atlas.id).job
        k.pauseAll()
        assertNull(q.claim())                                   // nothing starts while paused
        assertEquals(JobRun.QUEUED, q.get(a.id)!!.run); assertEquals(JobRun.QUEUED, q.get(b.id)!!.run)
        k.resumeAll()
        k.pauseBot(atlas.id)
        assertEquals(a.id, q.claim()!!.id)                      // Nova's job runs, Atlas (paused on its own) waits
        assertNull(q.claim())
        k.resumeBot(atlas.id)
        assertEquals(b.id, q.claim()!!.id)
    }
}
