package com.blaze.assistant.agent


import com.blaze.assistant.BrandName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gateway's two brains (Argus Cloud, the Nous reserve) and when to move on. Owner 16 Nov 2026: no on-device brain. */
class BackupRouteTest {
    @Test fun stepsAndReserveHeaders() {
        assertEquals(listOf(BackupRoute.Step.CLOUD, BackupRoute.Step.RESERVE), BackupRoute.Step.values().toList())
        assertEquals("skip", BackupRoute.reserveHeader(BackupRoute.Step.CLOUD))
        assertEquals("only", BackupRoute.reserveHeader(BackupRoute.Step.RESERVE))
    }

    @Test fun tooSlowOnlyBeforeAnySignOfLife() {
        assertFalse(BackupRoute.tooSlow(0, 14_999, false, 15_000))
        assertTrue(BackupRoute.tooSlow(0, 15_000, false, 15_000))
        assertFalse(BackupRoute.tooSlow(0, 60_000, true, 15_000))
        assertFalse(BackupRoute.tooSlow(0, 60_000, false, 0))
        assertTrue(BackupRoute.alive(100, 150, false, 0))            // a chunk after the start
        assertFalse(BackupRoute.alive(100, 50, false, 0))            // an older call's chunk doesn't count
        assertTrue(BackupRoute.alive(100, 0, true, 120))             // a non-streamed answer arrived
        assertFalse(BackupRoute.alive(100, 0, true, 90))             // an older call's flag
    }

    @Test fun movesOnForOutagesNotForAccountProblems() {
        assertTrue(BackupRoute.moveOn(503, Cloud.BUSY))
        assertTrue(BackupRoute.moveOn(0, Cloud.UNREACHABLE))
        assertTrue(BackupRoute.moveOn(429, "rate limited"))
        assertTrue(BackupRoute.moveOn(EmptyReply.CODE, EmptyReply.FAILED))
        assertFalse(BackupRoute.moveOn(401, Cloud.EXPIRED))
        assertFalse(BackupRoute.moveOn(403, Cloud.WAITLISTED))
        assertFalse(BackupRoute.moveOn(401, Cloud.SIGNED_OUT))
        assertFalse(BackupRoute.moveOn(429, "You've used today's free messages. They come back tomorrow."))
        assertFalse(BackupRoute.moveOn(429, "quota_exceeded"))
    }

    // v0.45.28: Rocky on 4G saw "Blaze Cloud is busy" and the Nous reserve was never asked.
    @Test fun busyStillGoesToTheReserve() {
        val R = listOf(BackupRoute.Step.RESERVE)
        assertEquals(R, BackupRoute.fallbacks(503, Cloud.BUSY, false))
        assertEquals(R, BackupRoute.fallbacks(429, Cloud.BUSY, false))                 // busy, not the allowance
        assertEquals(R, BackupRoute.fallbacks(0, Cloud.UNREACHABLE, false))
        assertEquals(R, BackupRoute.fallbacks(0, "${BrandName.NAME} Cloud too slow (no first chunk in 15s)", false))
        assertEquals(R, BackupRoute.fallbacks(502, "${BrandName.NAME} · blaze-auto said (502): upstream", false))
        // never for what no other brain can fix, nor a request the services refuse as such
        assertTrue(BackupRoute.fallbacks(401, Cloud.SIGNED_OUT, false).isEmpty())
        assertTrue(BackupRoute.fallbacks(403, Cloud.WAITLISTED, false).isEmpty())
        assertTrue(BackupRoute.fallbacks(429, "You've used today's free messages. They come back tomorrow.", false).isEmpty())
        assertTrue(BackupRoute.fallbacks(400, "bad tool shape", true).isEmpty())
    }

    @Test fun partialStreamFailureFallsBack() {
        val broke = "${BrandName.NAME} · blaze-auto stopped sending (timed out mid-reply)."
        assertTrue(BackupRoute.brokeMidReply(broke))
        assertTrue(BackupRoute.brokeMidReply("unexpected end of stream on https://gateway"))
        assertFalse(BackupRoute.brokeMidReply(Cloud.SIGNED_OUT))
        assertEquals(listOf(BackupRoute.Step.RESERVE), BackupRoute.fallbacks(0, broke, false))
        // the reserve call is the gateway call with X-Blaze-Reserve: only
        assertEquals("only", BackupRoute.reserveHeader(BackupRoute.Step.RESERVE))
    }

    @Test fun llmClientRacesTheGatewayBrains() {
        val src = HideModels19Test.srcFile("java/com/blaze/assistant/agent/LlmClient.kt").readText()
        assertFalse(src.contains("if (e is TooSlow && !backupReady) throw"))
        // 0.46.4: every call of the loop runs through the hedged race (BrainRace order: Nous / Argus Cloud)
        assertTrue(src.contains("HedgeRace.run("))
        assertTrue(src.contains("BrainRace.order(kind, true, "))
        assertFalse(src.contains("BackupBrain") || src.contains("OfflineBrain"))
    }
}
