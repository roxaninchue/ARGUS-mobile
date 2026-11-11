package com.blaze.assistant.policy

import com.blaze.assistant.policy.Kill.BotRun
import com.blaze.assistant.policy.Kill.Budget
import com.blaze.assistant.policy.Kill.Core
import com.blaze.assistant.policy.Kill.Held
import com.blaze.assistant.policy.Kill.Kind
import com.blaze.assistant.policy.Kill.Source
import com.blaze.assistant.policy.Kill.Step
import com.blaze.assistant.policy.Kill.Store
import com.blaze.assistant.agent.GoalPin
import com.blaze.assistant.agent.StopResume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-SF-06: instant Stop (A-team stopP semantics), Pause bot, the step budget of 40 with "Go on", and resume. */
class StopTest {

    @Test fun stopAbortsTheStreamAndCancelsCommandsOnce() {
        val k = Core()
        val t = k.begin("nova")!!
        var aborted = 0; var cancelled = 0; val hooked = mutableListOf<String>()
        t.onStop { aborted++ }          // abort the model stream
        t.onStop { cancelled++ }        // cancel running commands
        k.onStopHooks += { hooked += it } // P7: tmux wait-for -S / pkill for the bot's window
        assertEquals(BotRun.RUNNING, k.run("nova"))
        assertTrue(k.stop("nova"))
        assertEquals(1, aborted); assertEquals(1, cancelled); assertEquals(listOf("nova"), hooked)
        assertTrue(t.isStopped); assertTrue(t.signal.isCompleted)
        assertEquals(BotRun.STOPPING, k.run("nova"))
        assertEquals(Step.STOP, t.step())
        k.end(t)
        assertEquals(BotRun.IDLE, k.run("nova"))
    }

    @Test fun stopIsIdempotent() {
        val k = Core()
        val t = k.begin("nova")!!
        var n = 0
        t.onStop { n++ }
        assertTrue(t.stop())
        assertFalse(t.stop())
        assertFalse(k.stop("nova"))
        assertEquals(1, n)
        k.end(t)
        assertFalse(k.stop("nova"))          // nothing running: still a quiet no-op
        assertFalse(k.stop("nobody"))
    }

    @Test fun aCancelRegisteredAfterStopRunsAtOnce() {
        val k = Core(); val t = k.begin("nova")!!
        t.stop()
        var ran = false
        t.onStop { ran = true }
        assertTrue(ran)
    }

    @Test fun aThrowingCancelDoesNotBlockTheOthers() {
        val k = Core(); val t = k.begin("nova")!!
        var second = false
        t.onStop { error("socket already closed") }
        t.onStop { second = true }
        assertTrue(t.stop()); assertTrue(second); assertTrue(t.signal.isCompleted)
    }

    @Test fun stopWinsAHungAwait() = runBlocking {
        val k = Core(); val t = k.begin("nova")!!
        val never = CompletableDeferred<String>()     // a hung cloud call
        launch { delay(50); k.stop("nova") }
        val got = withTimeout(2_000) { t.race { never.await() } }
        assertNull(got)
        assertNull(t.race { "after stop" })           // later awaits lose at once
    }

    @Test fun raceReturnsTheValueWhenNotStopped() = runBlocking {
        val k = Core(); val t = k.begin("nova")!!
        assertEquals("ok", t.race { delay(10); "ok" })
        assertFalse(t.isStopped)
    }

    @Test fun stopOnlyTouchesThatBot() {
        val k = Core()
        val a = k.begin("nova")!!; val b = k.begin("atlas")!!
        k.stop("nova")
        assertTrue(a.isStopped); assertFalse(b.isStopped)
        assertEquals(Step.CONTINUE, b.step())
    }

    @Test fun pauseBotFinishesTheStepThenStops() {
        val k = Core()
        val t = k.begin("nova", Source.JOB)!!
        assertEquals(Step.CONTINUE, t.step())       // step 1 runs
        k.pauseBot("nova")                           // tapped mid-step: nothing is cancelled
        assertFalse(t.isStopped)
        assertEquals(Step.PAUSE, t.step())           // the next step does not start
        k.end(t)
        assertEquals(BotRun.PAUSED, k.run("nova"))
        assertNull(k.begin("nova", Source.JOB))      // automatic work waits
        val mine = k.begin("nova", Source.USER)      // the user's own message still runs
        assertNotNull(mine); assertEquals(Step.CONTINUE, mine!!.step())
        k.end(mine)
        assertTrue(k.resumeBot("nova").isEmpty())
        assertEquals(BotRun.IDLE, k.run("nova"))
        assertNotNull(k.begin("nova", Source.JOB))
    }

    @Test fun budgetOf40TriggersGoOn() {
        val k = Core()
        val t = k.begin("nova")!!
        assertEquals(Kill.STEP_BUDGET, 40)
        repeat(40) { assertEquals("step ${it + 1}", Step.CONTINUE, t.step()) }
        assertEquals(Step.BUDGET, t.step())          // the 41st model call does not run
        assertEquals(Step.BUDGET, t.endedBy)
        assertEquals("Stopped after 40 steps \u00b7 Go on", t.budget.chip())
        assertEquals(t.budget.chip(), StopResume.budgetLine(40))   // WorkChip and StopResume say the same thing
        assertTrue(StopResume.isResume(StopResume.GO_ON))           // the chip's text resumes
        assertTrue(StopResume.isResume("go on"))
        t.budget.goOn()                                             // Go on: another 40
        repeat(40) { assertEquals(Step.CONTINUE, t.step()) }
        assertEquals(Step.BUDGET, t.step())
        assertEquals("Stopped after 80 steps \u00b7 Go on", t.budget.chip())
    }

    @Test fun stopBeatsPauseBeatsBudget() {
        val k = Core()
        val t = k.begin("nova", Source.JOB, Budget(1))!!
        assertEquals(Step.CONTINUE, t.step())
        k.pauseBot("nova")
        assertEquals(Step.PAUSE, t.step())
        t.stop()
        assertEquals(Step.STOP, t.step())
        assertEquals("Stopped after 1 step \u00b7 Go on", Budget.chip(1))
    }

    @Test fun resumeContinuesWhereItStopped() {
        // Stop: the task is kept and "Continue where you stopped" picks it up with what was done.
        val goal = GoalPin.Goal("Book the 07:00 bus to Kitwe", listOf("window seat"))
        val stopped = StopResume.pending(goal, listOf("Opened the bus app: done and checked"), listOf("pay"), at = 5)!!
        assertEquals(StopResume.WHY_STOPPED, stopped.why)
        assertTrue(StopResume.isResume(StopResume.CHIP))
        val n1 = StopResume.note(stopped)
        assertTrue(n1.startsWith("[Resuming the task the user stopped"))
        assertTrue(n1.contains("Already done (don't redo)")); assertTrue(n1.contains("pay"))
        // Budget: "Go on" carries on with the same goal and receipts.
        val budget = StopResume.budgetPending(goal, listOf("Searched buses: done and checked"), 40, at = 6)!!
        val back = StopResume.fromJson(JSONObject(budget.toJson().toString()))!!
        assertEquals(budget, back)
        val n2 = StopResume.note(back)
        assertTrue(n2.contains("paused after 40 steps")); assertTrue(n2.contains("Book the 07:00 bus to Kitwe"))
        assertTrue(n2.contains("window seat")); assertTrue(n2.endsWith("Continue from where it stopped.]"))
        assertNull(StopResume.budgetPending(null, emptyList(), 40))
        // An old saved Pending (no "why") still reads as a Stop.
        val old = JSONObject().put("request", "x").put("at", 1L)
        assertEquals(StopResume.WHY_STOPPED, StopResume.fromJson(old)!!.why)
        assertFalse(stopped.toJson().has("why"))
    }
}
