package com.blaze.assistant.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.54.6: per-turn timing marks (lane, fallback, ticket, first token, calls, tool round trips, size) for the phone's diagnostics. */
class TurnTimingTest {
    private fun direct(ms: Long, first: Long, ticket: Long = -1) = TurnTiming.Call(TurnTiming.DIRECT, ms, first, 8_000, 12, ticket)

    @Test fun aDirectTurnReadsAsDirectWithTicketAndFirstToken() {
        val t = TurnTiming()
        t.call(direct(1_200, 610, ticket = 140))
        t.tool("set_alarm", 42)
        t.call(direct(900, 480))
        val s = t.snapshot(2_400)
        assertEquals("direct", s.lane())
        assertEquals(610L, s.firstMs())
        assertEquals(140L, s.ticketMs())
        assertEquals(1, s.ticketCount())
        val rows = s.rows().toMap()
        assertEquals("140 ms (1 fetched)", rows["Ticket"])
        assertEquals("610 ms, 480 ms", rows["Request \u2192 first token"])
        assertTrue(rows["Model calls"]!!.startsWith("2: d 1.20 s, d 900 ms"))
        assertEquals("1: set_alarm 42 ms", rows["Tool round trips"])
        assertTrue(rows["Sent"]!!.startsWith("8000 chars (~2000 tokens), 12 tools"))
        assertEquals("2.40 s", rows["Whole turn"])
        assertTrue(s.line().startsWith("lane direct \u00b7 ticket 140 ms \u00b7 first 610 ms"))
    }

    @Test fun aFallbackShowsItsReasonAndWhatTheDirectTryCost() {
        val t = TurnTiming()
        t.call(TurnTiming.Call(TurnTiming.NORMAL, 2_900, 2_300, 9_000, 30, ticketMs = 120, directMs = 1_520, fallback = "no first token in 1500 ms"))
        t.call(TurnTiming.Call(TurnTiming.NORMAL, 300, 200, 500, 0, kind = "background"))
        val s = t.snapshot(3_500)
        assertEquals("normal", s.lane())
        assertEquals(listOf("no first token in 1500 ms"), s.fallbacks())
        val rows = s.rows().toMap()
        assertEquals("no first token in 1500 ms", rows["Direct fell back"])
        assertTrue(rows["Model calls"]!!.startsWith("1 (+1 background): 2.90 s (+1.52 s direct try)"))
        assertTrue(s.line().contains("fell back: no first token in 1500 ms"))
    }

    @Test fun mixedLanesAndEmptyTurns() {
        val t = TurnTiming()
        assertEquals("", t.snapshot(10).line())
        assertTrue(t.snapshot(10).rows().isEmpty())
        t.call(direct(1_000, 500))
        t.call(TurnTiming.Call(TurnTiming.NORMAL, 1_000, 700, 100, 1))
        assertEquals("direct 1 / normal 1", t.snapshot(2_000).lane())
        t.clear()
        assertEquals("", t.snapshot(0).lane())
    }

    @Test fun boundedAndRoundTripsThroughJson() {
        val t = TurnTiming()
        repeat(TurnTiming.MAX + 10) { t.tool("x", it.toLong()) }
        t.call(direct(1_000, 500, 90))
        val s = t.snapshot(5_000)
        assertEquals(TurnTiming.MAX, s.tools.size)
        val back = TurnTiming.Snap.fromJson(s.toJson())
        assertEquals(s, back)
        // inside the What happened trace too
        val tr = TurnTrace.Trace(route = "Argus Cloud \u00b7 direct", timing = s)
        val again = TurnTrace.fromJson(TurnTrace.toJson(tr))
        assertNotNull(again!!.timing)
        assertEquals(s, again.timing)
        assertTrue(TurnTrace.rows(again).any { it.first == "Lane" && it.second == "direct" })
        assertFalse(TurnTrace.rows(again).any { it.first == "Whole turn" })   // the sheet keeps its own "Whole reply"
        assertTrue(TurnTrace.copyText(again).contains("Ticket: 90 ms (1 fetched)"))
        // an older trace without timing still reads
        assertEquals(null, TurnTrace.fromJson("""{"model":"m","route":"r"}""")!!.timing)
    }

    @Test fun aTurnAndItsForksShareOneTimingOtherTurnsDont() = runBlocking {
        val a = CallState(); val b = CallState()
        val child = a.fork()
        assertTrue(child.timing === a.timing)
        async(Dispatchers.IO + child.element()) { CallState.current().timing.tool("web_search", 300) }.await()
        async(Dispatchers.IO + b.element()) { CallState.current().timing.tool("other", 1) }.await()
        assertEquals(listOf("web_search"), a.timing.snapshot(0).tools.map { it.name })
        assertEquals(listOf("other"), b.timing.snapshot(0).tools.map { it.name })
    }

    @Test fun speedStatsShowTheLaneAndTheNewestTurns() {
        val d = TurnTiming().apply { call(direct(1_000, 600, 100)) }.snapshot(1_200).line()
        val n = TurnTiming().apply { call(TurnTiming.Call(TurnTiming.NORMAL, 2_000, 900, 100, 0)) }.snapshot(2_100).line()
        val l = listOf(SpeedLog.Entry(1L, 600, 1_200, 1, 0, 0, "r", timing = d), SpeedLog.Entry(2L, 900, 2_100, 1, 0, 0, "r", timing = n),
            SpeedLog.Entry(3L, 1, 1, 1, 0, 0, "r"))
        val text = SpeedLog.timingLines(l)
        assertTrue(text, text.contains("Lane (last 2): direct 1, fell back 0; first token direct 600 ms; normal 900 ms (median)"))
        assertTrue(text.contains(d) && text.contains(n))
        assertEquals("", SpeedLog.timingLines(listOf(SpeedLog.Entry(3L, 1, 1, 1, 0, 0, "r"))))
    }

    /** Source check: the client books every call and every tool run, and "speed stats" reads the timing. */
    @Test fun wiring() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main").isDirectory || File(it, "src/main").isDirectory }
        val dir = File(root, if (File(root, "app/src/main").isDirectory) "app/src/main/java" else "src/main/java").walk().first { it.name == "TurnTiming.kt" }.parentFile
        val llm = File(dir, "LlmClient.kt").readText()
        assertTrue(llm.contains("timing.call(TurnTiming.Call(TurnTiming.DIRECT"))
        assertTrue(llm.contains("timing.call(TurnTiming.Call(TurnTiming.NORMAL"))
        val agent = File(dir, "Agent.kt").readText()
        assertTrue(agent.contains("CallState.current().timing.tool(name"))
        assertTrue(agent.contains("timing = timingSnap.line()"))
        assertTrue(File(dir, "FastLane.kt").readText().contains("SpeedLog.timingLines()"))
        assertTrue(File(dir, "SelfControl.kt").readText().contains("SpeedLog.timingLines()"))
    }
}
