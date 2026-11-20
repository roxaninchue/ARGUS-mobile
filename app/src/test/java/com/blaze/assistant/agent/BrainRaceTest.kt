package com.blaze.assistant.agent


import com.blaze.assistant.BrandName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.46.4 hotfix: the router (kinds, order, limits), the Halo side reply, and the wiring that removed the 4-minute wait. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrainRaceTest {
    @org.junit.Before fun drainLeaks() = LeakedMainLaunches.drain()
    private val R = BackupRoute.Step.RESERVE; private val C = BackupRoute.Step.CLOUD
    private fun src(p: String) = listOf(File("src/main/java/com/blaze/assistant/agent/$p"), File("app/src/main/java/com/blaze/assistant/agent/$p")).first { it.exists() }.readText()

    @Test fun kinds() {
        // 0.46.6: no chat fast lane for tool turns: a lite or short message that offers tools is a TASK (one brain at a time)
        assertEquals(BrainRace.Kind.TASK, BrainRace.kind(lite = true, heavy = false, toolCount = 40, coding = false, userChars = 10))
        assertEquals(BrainRace.Kind.CHAT, BrainRace.kind(false, false, 0, false, 900))
        assertEquals(BrainRace.Kind.CHAT, BrainRace.kind(true, false, 0, false, 10))
        assertEquals(BrainRace.Kind.TASK, BrainRace.kind(false, false, 60, false, "I have a cloudflare token I want to give you and a gitlab token as well".length))
        assertEquals(BrainRace.Kind.TASK, BrainRace.kind(false, true, 60, false, 50))
        assertEquals(BrainRace.Kind.TASK, BrainRace.kind(false, false, 60, false, 600))
        assertEquals(BrainRace.Kind.CODING, BrainRace.kind(false, false, 60, true, 30))
        assertEquals(BrainRace.Kind.CHAT, BrainRace.kind(false, true, 60, true, 30, forced = BrainRace.Kind.CHAT))   // Halo's side lane
        assertEquals(11, BrainRace.userChars(JSONArray().put(JSONObject().put("role", "user").put("content", " what's up?? "))))
    }

    @Test fun orderNeverWaitsOnACold() {
        // 0.52.1 (gw061): tool turns ask Argus Cloud before the Nous reserve (Owner 16 Nov 2026: no on-phone brain any more)
        assertEquals(listOf(C, R), BrainRace.order(BrainRace.Kind.TASK, signedIn = true))
        assertEquals(listOf(C, R), BrainRace.order(BrainRace.Kind.CODING, true))
        // 0.46.6: chat keeps the standing order (Nous → gateway); speed never puts the gateway before Nous
        assertEquals(listOf(R, C), BrainRace.order(BrainRace.Kind.CHAT, true))
        assertEquals(listOf(R, C), BrainRace.order(BrainRace.Kind.CHAT, true, ttft = { if (it == C) 900L else if (it == R) 4_000L else null }))
        assertEquals(emptyList<BackupRoute.Step>(), BrainRace.order(BrainRace.Kind.CHAT, signedIn = false))
    }

    @Test fun limits() {
        val c = BrainRace.limits(BrainRace.Kind.CHAT, hot = false); val t = BrainRace.limits(BrainRace.Kind.TASK, hot = false)
        assertEquals(8_000L, c.firstMs); assertEquals(3_000L, c.hedgeMs); assertEquals(30_000L, c.stallMs); assertEquals(60_000L, c.thinkMs); assertEquals(60_000L, c.wholeMs); assertEquals(90_000L, t.wholeMs)
        assertEquals(20_000L, t.firstMs); assertEquals(6_000L, t.hedgeMs); assertEquals(30_000L, t.stallMs)
        assertEquals(0L, BrainRace.limits(BrainRace.Kind.CHAT, hot = true).hedgeMs)
        assertTrue(BrainRace.cloudRunFor(lite = false, longWork = true)); assertFalse(BrainRace.cloudRunFor(false, false)); assertFalse(BrainRace.cloudRunFor(true, true))
    }

    @Test fun ledgerLearnsFirstTokenTimes() {
        val l = BrainLedger { 0L }
        assertEquals(null, l.ttft(R)); l.ttft(R, 5_000); assertEquals(5_000L, l.ttft(R)); l.ttft(R, 1_000); assertEquals(3_400L, l.ttft(R))
        val l2 = BrainLedger(); l2.load(l.toJson()); assertEquals(3_400L, l2.ttft(R))
    }

    @Test fun haloAnswersWithinTwoSecondsWhileBlazeIsBusy() = runTest {
        // Argus's turn is busy (hanging); Halo's side reply has its own call. The brain is slow: the holding line shows at 2 s.
        val shown = mutableListOf<Pair<Long, String>>()
        val blaze = backgroundScope.launch { delay(10 * 60_000) }   // the stuck main turn, untouched by Halo
        val r = BrainRace.haloReply(BrainRace.HALO_HOLD_MS, { BrainRace.haloHolding("check my GitLab tokens", "Reading 2 pages") }, { shown += testScheduler.currentTime to it }) { say ->
            delay(6_000); say("${BrandName.NAME} is still on it - about a minute more."); "${BrandName.NAME} is still on it - about a minute more." }
        assertEquals(2_000L, shown.first().first)
        assertEquals("${BrandName.NAME} is still on \u201ccheck my GitLab tokens\u201d. Right now: Reading 2 pages.", shown.first().second)
        assertEquals("${BrandName.NAME} is still on it - about a minute more.", r)
        assertTrue(blaze.isActive)
        // fast model: no holding line at all
        val shown2 = mutableListOf<String>()
        BrainRace.haloReply(2_000, { "hold" }, { shown2 += it }) { say -> delay(800); say("Hi!"); "Hi!" }
        assertEquals(listOf("Hi!"), shown2)
        // every brain fails after the holding line: the holding line is Halo's reply, not an error
        val r3 = BrainRace.haloReply(2_000, { "${BrandName.NAME} is still on your request." }, { }) { _ -> delay(9_000); throw LlmClient.LlmException(Cloud.BUSY, 503) }
        assertEquals("${BrandName.NAME} is still on your request.", r3)
    }

    @Test fun wiring() {
        val llm = src("LlmClient.kt"); val agent = src("Agent.kt")
        assertTrue(llm.contains("HedgeRace.run(steps, BrainRace.limits(kind), onText != null,"))
        assertTrue(llm.contains("parallel = BrainRace.parallel(tools?.length() ?: 0))"))
        assertTrue(llm.contains("BrainRace.order(kind, true, { BrainLedger.app.ttft(it) }, { BrainLedger.app.reliability(it) })"))
        // 0.46.5: our own deadline is not a brain failure; the calm final line; one retry with the long cap; Cloud fails fast in the race
        assertTrue(llm.contains("if (e !is HedgeRace.TooSlow) BrainLedger.app.fail(step, e.message.orEmpty())"))
        assertTrue(llm.contains("allFailed = { last, fatal -> BrainRace.finalError(steps, fatal, cloudErr, last) }"))
        assertTrue(llm.contains("retryMs = BrainRace.RETRY_MS,"))
        assertTrue(llm.contains("withContext(Cloud.FastFail) { viaGatewayWith(\"skip\", gText, gThink) }"))
        assertTrue(src("Cloud.kt").contains("coroutineContext[FastFailKey] != null"))
        assertFalse("dead first-chunk watcher removed", llm.contains("private suspend fun watchFirstChunk"))
        assertTrue(agent.contains("BrainRace.cloudRunFor(lite, longWork) && CloudRuns.eligible("))
        assertTrue(agent.contains("HedgeRace.watch(BrainRace.TASK_LIMITS.firstMs, BrainRace.TASK_LIMITS.stallMs,"))
        assertTrue(agent.contains("CallState().also { it.raceKind = BrainRace.Kind.CHAT }.element()"))
        assertTrue(agent.contains("BrainRace.haloReply(BrainRace.HALO_HOLD_MS,"))
        // Owner 16 Nov 2026: the on-phone brain is gone from the router and from the Cloud-run check
        assertFalse(llm.contains("BackupBrain") || src("CloudRuns.kt").contains("BackupBrain"))
    }

    // ---- 0.46.5: reliability ledger, final error. 0.46.6: the ledger no longer reorders the brains ----
    // 0.52.1 (gw061): tool (TASK) turns ask the gateway before the Nous reserve; chat keeps Nous → gateway.
    @Test fun orderStaysOnPhoneNousGatewayWhateverTheLedgerSays() {
        val ttft = { s: BackupRoute.Step -> if (s == C) 900L else if (s == R) 15_000L else 25_000L }
        // Nous mostly failing, gateway fast: Nous is still asked before the gateway (0.46.5 put it last, so it was skipped)
        assertEquals(listOf(R, C), BrainRace.order(BrainRace.Kind.CHAT, true, ttft) { if (it == R) 0.1 else 0.95 })
        assertEquals(listOf(C, R), BrainRace.order(BrainRace.Kind.TASK, true, ttft) { if (it == R) 0.1 else 0.95 })
        assertEquals(listOf(R, C), BrainRace.order(BrainRace.Kind.CHAT, true, ttft) { 0.9 })
        assertEquals(listOf(C, R), BrainRace.order(BrainRace.Kind.TASK, true, ttft) { 0.9 })
        // the ledger: successes and real failures move the rate; nothing else does
        val l = BrainLedger { 0L }
        assertEquals(null, l.reliability(C))
        repeat(3) { l.fail(C, "busy") }; repeat(3) { l.ok(R, 20_000) }
        assertTrue(l.reliability(C)!! < 0.5); assertTrue(l.reliability(R)!! >= 0.8)
        assertEquals(listOf(R, C), BrainRace.order(BrainRace.Kind.CHAT, true, { if (it == C) 900L else 15_000L }, { l.reliability(it) }))
        assertEquals(listOf(C, R), BrainRace.order(BrainRace.Kind.TASK, true, { null }, { if (it == R) 0.0 else 1.0 }))
        val l2 = BrainLedger(); l2.load(l.toJson()); assertEquals(l.reliability(R)!!, l2.reliability(R)!!, 1e-9)
        // retry pick: most reliable, ties keep the order
        assertEquals(R, BrainRace.retryPick(listOf(C, R)) { if (it == R) 0.9 else 0.3 })
        assertEquals(C, BrainRace.retryPick(listOf(C, R)) { null })
        // a saved ledger from before the removal (with the on-phone "BACKUP" row) still loads its other rows
        val old = BrainLedger(); old.load("{\"BACKUP\":{\"calls\":3,\"ttft\":6000},\"RESERVE\":{\"calls\":2,\"ttft\":3100}}")
        assertEquals(3_100L, old.ttft(R)); assertEquals(setOf(R), old.rows.keys)
    }

    @Test fun finalErrorIsCalmUnlessCloudWasTheOnlyPath() {
        val busy = LlmClient.LlmException(Cloud.BUSY, 503)
        assertEquals(BrainRace.TOO_LONG, BrainRace.finalError(listOf(R, C), null, busy, busy).message)
        assertEquals(BrainRace.TOO_LONG, BrainRace.finalError(listOf(R, C), null, busy, HedgeRace.TooSlow("x")).message)
        assertEquals(Cloud.BUSY, BrainRace.finalError(listOf(C), null, busy, busy).message)
        val signIn = LlmClient.LlmException(Cloud.EXPIRED, 401)
        assertEquals(Cloud.EXPIRED, BrainRace.finalError(listOf(R, C), signIn, busy, busy).message)
        assertEquals("That took too long. Tap Retry.", BrainRace.TOO_LONG)
    }

    @Test fun cloudInTheRaceFailsAtOnceInsteadOfRetrying() = runTest {
        var tries = 0
        val busy = runCatching { kotlinx.coroutines.withContext(Cloud.FastFail) {
            Cloud.drive(true, call = { _ -> tries++; throw LlmClient.LlmException("${BrandName.NAME} Cloud is busy (503)", 503) }, served = { "" }, sleep = { })
        } }
        assertTrue(busy.isFailure); assertEquals(1, tries)
        // outside the race the old quiet retries still apply
        tries = 0
        runCatching { Cloud.drive(true, call = { _ -> tries++; throw LlmClient.LlmException("${BrandName.NAME} Cloud is busy (503)", 503) }, served = { "" }, sleep = { }) }
        assertTrue(tries > 1)
    }
}
