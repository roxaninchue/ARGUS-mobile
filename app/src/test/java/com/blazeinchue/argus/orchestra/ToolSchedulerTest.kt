package com.blazeinchue.argus.orchestra

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ToolSchedulerTest {
    private fun call(i: Int, name: String, vararg kv: Pair<String, String>) =
        ToolScheduler.Call("c$i", name, JSONObject().apply { kv.forEach { put(it.first, it.second) } })

    @Test fun networkCallsRunTogether() = runTest {
        val calls = (0 until 5).map { call(it, "web_search", "query" to "q$it") }
        val live = AtomicInteger(); var peak = 0
        val t0 = currentTime
        val out = ToolScheduler.run(calls, { c -> peak = maxOf(peak, live.incrementAndGet()); delay(1000); live.decrementAndGet(); "r-${c.id}" },
            locks = KeyedLocks(), clock = { currentTime })
        assertEquals(5, peak)
        assertEquals(1000, currentTime - t0)   // all at once, not 5 s
        assertEquals(listOf("r-c0", "r-c1", "r-c2", "r-c3", "r-c4"), out.map { it.output })   // in the model's order
        assertEquals(listOf("c0", "c1", "c2", "c3", "c4"), out.map { it.id })
    }

    @Test fun writeThenReadSamePathKeepsOrderOtherPathsParallel() = runTest {
        val calls = listOf(call(0, "file_write", "path" to "/root/a.txt"), call(1, "file_read", "path" to "/root/a.txt"), call(2, "file_write", "path" to "/root/b.txt"))
        assertEquals(listOf(emptySet(), setOf(0), emptySet<Int>()), ToolScheduler.plan(calls))
        val order = mutableListOf<String>()
        ToolScheduler.run(calls, { c -> order += "start ${c.id}"; delay(if (c.id == "c0") 500 else 100); order += "end ${c.id}"; "ok" }, locks = KeyedLocks())
        assertTrue(order.indexOf("end c0") < order.indexOf("start c1"))
        assertTrue(order.indexOf("start c2") < order.indexOf("end c0"))   // b.txt didn't wait for a.txt
    }

    @Test fun twoWritesInOneFolderRunTogetherButAListingWaits() {
        val calls = listOf(call(0, "file_write", "path" to "app/x.kt"), call(1, "file_write", "path" to "app/y.kt"), call(2, "file_list", "path" to "app"))
        assertEquals(listOf(emptySet(), emptySet(), setOf(0, 1)), ToolScheduler.plan(calls))
    }

    @Test fun screenActionsOneAtATimeInOrder() = runTest {
        val calls = listOf(call(0, "open_app", "app" to "Clock"), call(1, "web_search", "query" to "x"), call(2, "tap", "text" to "Alarm"), call(3, "type_text", "text" to "7"))
        val live = AtomicInteger(); var peakScreen = 0
        val order = mutableListOf<String>()
        ToolScheduler.run(calls, { c ->
            val screen = c.name != "web_search"
            if (screen) peakScreen = maxOf(peakScreen, live.incrementAndGet())
            order += c.id; delay(200); if (screen) live.decrementAndGet(); "ok"
        }, locks = KeyedLocks())
        assertEquals(1, peakScreen)
        assertEquals(listOf("c0", "c2", "c3"), order.filter { it != "c1" })
        assertEquals(1, order.indexOf("c1"))   // the search started beside the first screen action
    }

    @Test fun terminalCallsAreIsolatedUnlessTheyNameASession() {
        val calls = listOf(call(0, "run_shell", "command" to "ls"), call(1, "run_shell", "command" to "pwd"),
            call(2, "termux_run", "session" to "build", "command" to "make"), call(3, "job_input", "session" to "build", "text" to "y"))
        assertEquals(listOf(emptySet(), emptySet(), emptySet(), setOf(2)), ToolScheduler.plan(calls))
    }

    @Test fun shellCommandsKeepTheirOrderWithFileCalls() {
        val calls = listOf(call(0, "file_write", "path" to "a.py"), call(1, "run_shell", "command" to "python a.py"), call(2, "web_search", "query" to "q"), call(3, "file_read", "path" to "out.txt"))
        assertEquals(listOf(emptySet(), setOf(0), emptySet(), setOf(1)), ToolScheduler.plan(calls))
    }

    @Test fun onlyRealResourcesAreHeldAcrossAgents() {
        val held = ToolScheduler.Resources.lockClaims(ToolScheduler.Resources.claims("send_sms", JSONObject()) + ToolScheduler.Resources.claims("tap", JSONObject()) +
            ToolScheduler.Resources.claims("file_write", JSONObject().put("path", "/x/y.kt")) + ToolScheduler.Resources.claims("new_tool", JSONObject()))
        assertEquals(setOf("screen", "file:/x/y.kt", "dir:/x", "dir:/"), held.map { it.key }.toSet())
        assertTrue(ToolScheduler.Resources.prefetchable("web_search"))
        assertTrue(!ToolScheduler.Resources.prefetchable("tap") && !ToolScheduler.Resources.prefetchable("ask_user") && !ToolScheduler.Resources.prefetchable("delegate"))
    }

    @Test fun unknownToolsTakeTurnsButNotWithNetwork() {
        val calls = listOf(call(0, "brand_new_tool"), call(1, "other_new_tool"), call(2, "web_search", "query" to "q"))
        assertEquals(listOf(emptySet(), setOf(0), emptySet<Int>()), ToolScheduler.plan(calls))
    }

    @Test fun cardsNeverStack() {
        val calls = listOf(call(0, "send_sms", "to" to "1"), call(1, "gmail_send", "to" to "a"), call(2, "ask_user", "q" to "?"))
        assertEquals(listOf(emptySet(), setOf(0), setOf(0, 1)), ToolScheduler.plan(calls))
    }

    @Test fun timeoutsAndErrorsBecomeResultsInOrder() = runTest {
        val calls = listOf(call(0, "web_search"), call(1, "fetch_page"), call(2, "web_search"))
        val out = ToolScheduler.run(calls, { c ->
            when (c.id) { "c0" -> { delay(10_000); "late" }; "c1" -> throw IllegalStateException("boom"); else -> "fine" }
        }, timeoutMs = 1000, locks = KeyedLocks())
        assertTrue(out[0].timedOut && out[0].output.startsWith("Error:"))
        assertTrue(out[1].failed && out[1].output.contains("boom"))
        assertEquals("fine", out[2].output)
    }

    @Test fun locksAreSharedAcrossBatches() = runTest {
        // two agents' batches writing the same file never overlap
        val locks = KeyedLocks()
        val live = ConcurrentHashMap<String, AtomicInteger>(); var peak = 0
        val exec: suspend (ToolScheduler.Call) -> String = { c ->
            val n = live.getOrPut(c.args.optString("path")) { AtomicInteger() }.incrementAndGet(); peak = maxOf(peak, n)
            delay(300); live[c.args.optString("path")]!!.decrementAndGet(); "ok"
        }
        val a = async { ToolScheduler.run(listOf(call(0, "file_edit", "path" to "/p/Main.kt")), exec, locks = locks) }
        val b = async { ToolScheduler.run(listOf(call(0, "file_write", "path" to "/p//Main.kt")), exec, locks = locks) }
        a.await(); b.await()
        assertEquals(1, peak)
        assertTrue(locks.idle("file:/p/Main.kt"))
    }

    @Test fun listenerSeesEveryStartAndEnd() = runTest {
        val seen = mutableListOf<String>()
        ToolScheduler.run(listOf(call(0, "web_search"), call(1, "web_search")), { "ok" }, locks = KeyedLocks(), listener = object : ToolScheduler.Listener {
            override fun started(index: Int, call: ToolScheduler.Call) { seen += "s$index" }
            override fun finished(index: Int, call: ToolScheduler.Call, result: ToolScheduler.Result) { seen += "f$index" }
        })
        assertEquals(setOf("s0", "s1", "f0", "f1"), seen.toSet())
    }

    @Test fun describeNamesTheWaves() {
        val calls = listOf(call(0, "web_search"), call(1, "web_search"), call(2, "file_write", "path" to "a"), call(3, "file_read", "path" to "a"))
        assertEquals("4 call(s) in 2 wave(s): [web_search, web_search, file_write] then [file_read]", ToolScheduler.describe(calls))
    }
}
