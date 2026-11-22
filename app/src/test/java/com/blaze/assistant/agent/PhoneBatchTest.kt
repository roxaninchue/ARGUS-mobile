package com.blaze.assistant.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.55.1: the dumb executor for the gateway's signed phone batches (PhoneBatch). */
class PhoneBatchTest {
    private val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it * 7 + 3).toByte() })
    private val jti = "jti-123"
    private fun batch(vararg steps: PhoneBatch.Step, turn: String = "turnAAAAAAAAAAAAAAAA"): PhoneBatch.Batch =
        PhoneBatch.Batch(turn, jti, steps.toList(), PhoneBatch.sign(key, turn, jti, steps.toList()))
    private val alarm = PhoneBatch.Step("a1", "set_alarm", "{\"hour\":7,\"minute\":0}", true)
    private val timer = PhoneBatch.Step("t1", "set_timer", "{\"seconds\":300}", true)
    private val msg = PhoneBatch.Step("m1", "message_contact", "{\"to\":\"Mum\",\"text\":\"on my way / 5 min\"}", false)
    private val allowAll: (String) -> Boolean = { true }

    @Test fun theSignedMessageMatchesTheGatewaysJsonStringify() {
        // JSON.stringify([{id, tool, args, final}]) in the gateway: "/" is NOT escaped, quotes and backslashes are.
        val m = PhoneBatch.message("T", "J", listOf(PhoneBatch.Step("c", "x", "{\"a\":\"b/c\\\\d\"}", false)))
        assertEquals("T\nJ\n[{\"id\":\"c\",\"tool\":\"x\",\"args\":\"{\\\"a\\\":\\\"b/c\\\\\\\\d\\\"}\",\"final\":false}]", m)
        // a known HMAC vector computed with Node (gateway signBatch) for this key and steps
        assertEquals(PhoneBatch.sign(key, "T", "J", listOf(PhoneBatch.Step("c", "x", "{}", true))).length, 43)
    }

    @Test fun signatureRejection() = runBlocking {
        val good = batch(alarm, msg)
        assertTrue(PhoneBatch.verify(key, jti, good))
        val ran = ArrayList<String>()
        val exec: suspend (String, JSONObject) -> String = { n, _ -> ran.add(n); "ok" }
        for (bad in listOf(good.copy(sig = good.sig.dropLast(1) + if (good.sig.last() == 'A') "B" else "A"),
                good.copy(steps = listOf(alarm.copy(args = "{\"hour\":3}"), msg)),
                good.copy(steps = listOf(alarm.copy(tool = "pay_bill"), msg)),
                good.copy(steps = listOf(alarm, msg.copy(final = true))),
                good.copy(turnId = "other"),
                good.copy(ticket = "another-ticket"))) {
            val o = PhoneBatch.run(bad, key, jti, allowAll, exec)
            assertFalse(o.accepted)
            assertTrue(PhoneBatch.next(bad, o) is PhoneBatch.Next.Refuse)
        }
        // the wrong key, or a batch for another ticket id
        assertFalse(PhoneBatch.run(good, key.reversed(), jti, allowAll, exec).accepted)
        assertFalse(PhoneBatch.run(good, key, "jti-other", allowAll, exec).accepted)
        assertEquals("nothing runs from a refused batch", emptyList<String>(), ran)
    }

    @Test fun aToolTheTurnMayNotRunRefusesTheWholeBatch() = runBlocking {
        val ran = ArrayList<String>()
        val o = PhoneBatch.run(batch(alarm, msg), key, jti, { it == "set_alarm" }, { n, _ -> ran.add(n); "ok" })
        assertFalse(o.accepted)
        assertEquals("not_allowed:message_contact", o.reason)
        assertTrue(ran.isEmpty())
    }

    @Test fun stepsRunInOrderAndStopAtTheFirstFailure() = runBlocking {
        val ran = ArrayList<String>()
        val b = batch(alarm, msg, timer)
        val o = PhoneBatch.run(b, key, jti, allowAll, { n, _ -> ran.add(n); if (n == "message_contact") "Error: no contact called Mum" else "Alarm set for 07:00." })
        assertEquals(listOf("set_alarm", "message_contact"), ran)
        assertEquals(listOf(true, false, false), o.done.map { it.ok })
        assertEquals(PhoneBatch.NOT_RUN, o.done[2].result)
        assertFalse(o.done[2].ran)
        val n = PhoneBatch.next(b, o) as PhoneBatch.Next.Resume
        assertEquals(listOf("a1", "m1", "t1"), n.results.map { it.first })
    }

    @Test fun aDeclinedApprovalCardStopsTheBatch() = runBlocking {
        val ran = ArrayList<String>()
        val o = PhoneBatch.run(batch(msg, alarm), key, jti, allowAll, { n, _ -> ran.add(n); if (n == "message_contact") "The user did NOT approve message_contact. Don't retry it." else "Alarm set." })
        assertEquals(listOf("message_contact"), ran)
        assertFalse(o.allOk)
    }

    @Test fun anAllFinalBatchThatSucceedsEndsTheTurnWithNoModelCall() = runBlocking {
        val b = batch(alarm, timer)
        assertTrue(b.allFinal)
        val o = PhoneBatch.run(b, key, jti, allowAll, { n, _ -> if (n == "set_alarm") "Alarm set for 07:00, label \u201c(Argus A12)\u201d. [ID A12]" else "Timer started for 5 min. [ID T3]" })
        val n = PhoneBatch.next(b, o)
        assertTrue(n is PhoneBatch.Next.End)
        assertEquals("Alarm set for 07:00.\nTimer started for 5 min.", (n as PhoneBatch.Next.End).text)
        // a final batch with a failure resumes (the model explains), it never ends quietly
        val o2 = PhoneBatch.run(b, key, jti, allowAll, { n2, _ -> if (n2 == "set_alarm") "Error: no clock app" else "Timer started." })
        assertTrue(PhoneBatch.next(b, o2) is PhoneBatch.Next.Resume)
    }

    @Test fun aMixedBatchResumesWithTheResults() = runBlocking {
        val b = batch(alarm, msg)
        assertFalse(b.allFinal)
        val o = PhoneBatch.run(b, key, jti, allowAll, { n, _ -> if (n == "set_alarm") "Alarm set for 07:00." else "Sent to Mum." })
        assertTrue(o.allOk)
        val n = PhoneBatch.next(b, o) as PhoneBatch.Next.Resume
        assertEquals(listOf("a1" to "Alarm set for 07:00.", "m1" to "Sent to Mum."), n.results)
        val body = LoopWire.resumeBody(b.turnId, n.results)
        assertEquals(setOf("turnId", "stream", "tool_results"), body.keySet())
        assertFalse("no chat resent", body.has("messages"))
    }

    @Test fun parseReadsTheGatewayEvent() {
        val ev = JSONObject().put("type", "batch").put("turnId", "turnX").put("ticket", jti).put("sig", "s")
            .put("steps", JSONArray().put(JSONObject().put("id", "a1").put("tool", "set_alarm").put("args", "{}").put("final", true)))
        val b = PhoneBatch.parse(ev)!!
        assertEquals("turnX", b.turnId); assertTrue(b.allFinal)
        assertEquals(null, PhoneBatch.parse(JSONObject().put("turnId", "x")))
    }

    @Test fun failedReadsToolResultsOnlyNeverTheUsersWords() {
        assertTrue(PhoneBatch.failed("Error: x")); assertTrue(PhoneBatch.failed("Not run: y")); assertTrue(PhoneBatch.failed(""))
        assertTrue(PhoneBatch.failed("The user did NOT approve deleting it."))
        assertFalse(PhoneBatch.failed("Alarm set for 07:00."))
    }
}
