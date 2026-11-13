package com.blaze.assistant.policy

import com.blaze.assistant.agent.CommandPolicy
import com.blaze.assistant.agent.ConfirmGate
import com.blaze.assistant.store.JdbcSql
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.Permissions
import com.blaze.assistant.team.Rule
import com.blaze.assistant.team.TeamSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.currentCoroutineContext

/**
 * P2-SF-01: approvals inside the tool call. The call waits on the approval; 25 minutes with no answer = expired; a new
 * user message = skipped; a pending row left by a dead process = expired at start; Always allow saves a standing policy
 * that the next decision uses; an approved call runs under ConfirmGate.PolicyOk so the tool's own card doesn't ask again.
 * Virtual time (kotlinx-coroutines-test) + team.db on sqlite-jdbc.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalsTest {
    private val sql = JdbcSql().also { s -> s.tx { for (st in TeamSchema.steps(0)) s.exec(st) } }

    private fun req(chat: String = "job-1-1", bot: String = "atlas", tool: String = "gcal_create", cls: ActionClass = ActionClass.WRITE_REMOTE) =
        Approvals.Request(chat, bot, "1", "tc-1", tool, cls, "Atlas wants to change something in your accounts", "gcal create", "{}", false)

    private fun gateReq(ctx: Policy.Ctx = Policy.Ctx(botId = "atlas"), cls: ActionClass = ActionClass.WRITE_REMOTE, chat: String = "job-1-1",
                        enforce: Boolean = true, tool: String = "gcal_create") =
        PolicyGate.Request(chat, "atlas", "Atlas", "1", tool, "{\"title\":\"Dentist\"}", Policy.Call(tool, cls), ctx, enforce)

    @Test fun expiresAfter25Minutes() = runTest {
        val store = ApprovalStore(sql) { testScheduler.currentTime }
        val ap = Approvals({ testScheduler.currentTime }, store)
        val p = ap.open(req())
        assertEquals("pending", store.status(p.id))
        val w = async { ap.await(p) }
        advanceTimeBy(Approvals.EXPIRY_MS - 1); runCurrent()
        assertFalse(w.isCompleted)
        assertTrue(ap.resolve("nope", Approvals.Status.ALLOWED).not())
        advanceTimeBy(2); runCurrent()
        assertEquals(Approvals.Status.EXPIRED, w.await())
        assertEquals("expired", store.status(p.id))
        assertFalse("a late answer changes nothing", ap.resolve(p.id, Approvals.Status.ALLOWED))
        assertEquals("expired", store.status(p.id))
        assertEquals(25 * 60_000L, Approvals.EXPIRY_MS)
    }

    @Test fun answeredInTime() = runTest {
        val store = ApprovalStore(sql) { testScheduler.currentTime }
        val ap = Approvals({ testScheduler.currentTime }, store)
        val p = ap.open(req())
        val w = async { ap.await(p) }
        advanceTimeBy(24 * 60_000L); runCurrent()
        assertTrue(ap.resolve(p.id, Approvals.Status.ALLOWED))
        assertEquals(Approvals.Status.ALLOWED, w.await())
        assertEquals("allowed", store.status(p.id))
        assertTrue(ap.pending().isEmpty())
    }

    @Test fun newUserMessageSkips() = runTest {
        val ap = Approvals({ testScheduler.currentTime })
        val here = ap.open(req(chat = "job-1-1", bot = "atlas"))
        val sameBot = ap.open(req(chat = "team-atlas-g1", bot = "atlas"))
        val other = ap.open(req(chat = "job-2-1", bot = "nova"))
        val w = async { ap.await(here) }
        runCurrent()
        assertEquals(2, ap.userMessage("team-atlas-g1", "atlas"))
        assertEquals(Approvals.Status.SKIPPED, w.await())
        assertEquals(listOf(other.id), ap.pending().map { it.id })
        assertEquals(0, ap.userMessage("main", null))
        assertEquals(1, ap.userMessage("job-2-1"))
        assertTrue(sameBot.id != here.id)
    }

    @Test fun restartExpiresWhatWasPending() {
        val store = ApprovalStore(sql) { 1_000L }
        val before = Approvals({ 500L }, store)
        val a = before.open(req()); val b = before.open(req(tool = "send_sms", cls = ActionClass.SEND))
        before.resolve(b.id, Approvals.Status.DENIED)
        // the process dies; the next start closes the leftover
        assertEquals(1, ApprovalStore(sql) { 2_000L }.expireLeftovers())
        assertEquals("expired", store.status(a.id))
        assertEquals("denied", store.status(b.id))
        assertEquals(0, store.count("pending"))
        assertEquals(0, ApprovalStore(sql).expireLeftovers())
        // the in-memory side: expireAll on the way out
        val ap = Approvals({ 0L }); ap.open(req()); ap.open(req())
        assertEquals(2, ap.expireAll()); assertTrue(ap.pending().isEmpty())
    }

    @Test fun spendAndDeleteAreOnDeviceOnly() {
        val ap = Approvals({ 0L })
        assertTrue(ap.open(req(cls = ActionClass.SPEND)).approval.onDeviceOnly)
        assertTrue(ap.open(req(cls = ActionClass.DELETE)).approval.onDeviceOnly)
        assertFalse(ap.open(req(cls = ActionClass.WRITE_REMOTE)).approval.onDeviceOnly)
    }

    @Test fun gateWaitsInsideTheCallThenRunsUnderPolicyOk() = runTest {
        val ap = Approvals({ testScheduler.currentTime })
        val shown = CompletableDeferred<Approvals.Pending>()
        val gate = PolicyGate(ap, presenter = { p, _ -> shown.complete(p); null })
        var ranWithOk: Boolean? = null
        val out = async { gate.run(gateReq()) { ranWithOk = ConfirmGate.policyOk(currentCoroutineContext()); "created" } }
        val p = shown.await()
        assertEquals(listOf(p.id), ap.pending().map { it.id })
        assertNull("the tool hasn't run yet", ranWithOk)
        ap.resolve(p.id, Approvals.Status.ALLOWED)
        assertEquals("created", out.await())
        assertEquals(true, ranWithOk)
    }

    @Test fun gateDenyExpireSkipDoNotRun() = runTest {
        for (end in listOf(Approvals.Status.DENIED, Approvals.Status.EXPIRED, Approvals.Status.SKIPPED)) {
            val ap = Approvals({ testScheduler.currentTime })
            val gate = PolicyGate(ap, presenter = { _, _ -> if (end == Approvals.Status.DENIED) Approvals.Status.DENIED else null })
            var ran = false
            val out = async { gate.run(gateReq()) { ran = true; "x" } }
            runCurrent()
            when (end) {
                Approvals.Status.EXPIRED -> { advanceTimeBy(Approvals.EXPIRY_MS + 1); runCurrent() }
                Approvals.Status.SKIPPED -> ap.userMessage("job-1-1", "atlas")
                else -> {}
            }
            val r = out.await()
            assertFalse("$end ran the tool", ran)
            assertTrue("$end: $r", r.contains("Don't retry"))
        }
    }

    @Test fun detachedCardIsLeftToCloseItself() = runTest {
        val ap = Approvals({ testScheduler.currentTime })
        var closed = false
        val gate = PolicyGate(ap, presenter = { _, _ -> try { kotlinx.coroutines.delay(120_000); null } finally { closed = true } }, detach = backgroundScope)
        val out = async { gate.run(gateReq()) { "ran" } }
        runCurrent()
        ap.userMessage("job-1-1")
        assertTrue(out.await().contains("skipped"))
        assertFalse("the card wasn't cancelled under the user", closed)
        advanceTimeBy(120_001); runCurrent()
        assertTrue(closed)
    }

    @Test fun refusalAndAllowNeedNoCard() = runTest {
        val ap = Approvals({ testScheduler.currentTime })
        val gate = PolicyGate(ap, presenter = { _, _ -> error("no card expected") })
        assertEquals("ok", gate.run(gateReq(cls = ActionClass.READ)) { "ok" })
        val r = gate.run(gateReq(Policy.Ctx(botId = "atlas", plan = true))) { "ran" }
        assertTrue(r.startsWith("Refused"))
        assertTrue(ap.pending().isEmpty())
        // shadow (the user's own chat): asks are made but the call runs as before, without a card
        assertEquals("ran", gate.run(gateReq(cls = ActionClass.SEND, enforce = false)) { "ran" })
    }

    @Test fun alwaysAllowSavesAStandingPolicyTheNextCallUses() = runTest {
        val ap = Approvals({ testScheduler.currentTime })
        val pol = PolicyStore(sql) { 7L }
        val gate = PolicyGate(ap, presenter = { _, _ -> Approvals.Status.ALWAYS }, saveAlways = { t, b -> pol.set(t, b, Rule.ALLOW) })
        assertEquals("1", gate.run(gateReq()) { "1" })
        assertEquals(Rule.ALLOW, pol.rule("gcal_create", "atlas"))
        // next call: the standing rule allows without a card
        val quiet = PolicyGate(ap, presenter = { _, _ -> error("no card expected") })
        assertEquals("2", quiet.run(gateReq(Policy.Ctx(botId = "atlas", standing = pol.rule("gcal_create", "atlas")))) { "2" })
        // Always on a delete is not remembered (the card doesn't offer it)
        assertEquals("3", gate.run(gateReq(cls = ActionClass.DELETE, tool = "file_delete")) { "3" })
        assertNull(pol.rule("file_delete", "atlas"))
    }

    @Test fun policyStoreBotRowWinsOverStar() {
        val pol = PolicyStore(sql) { 1L }
        pol.set("gcal_create", "*", Rule.ASK)
        assertEquals(Rule.ASK, pol.rule("gcal_create", "atlas"))
        pol.set("gcal_create", "atlas", Rule.ALLOW)
        assertEquals(Rule.ALLOW, pol.rule("gcal_create", "atlas"))
        assertEquals(Rule.ASK, pol.rule("gcal_create", "nova"))
        pol.forget("gcal_create", "atlas")
        assertEquals(Rule.ASK, pol.rule("gcal_create", "atlas"))
        assertNull(pol.rule("other", "atlas"))
        assertEquals(1, pol.all().size)
    }

    @Test fun proactiveSpendThroughTheGateAlwaysShowsACard() = runTest {
        val ap = Approvals({ testScheduler.currentTime })
        var cards = 0
        val gate = PolicyGate(ap, presenter = { _, _ -> cards++; Approvals.Status.DENIED })
        val ctx = Policy.Ctx(botId = "atlas", perms = Permissions(autonomy = Autonomy.AUTOPILOT), trigger = Policy.Trigger.ROUTINE,
            standing = Rule.ALLOW, command = CommandPolicy.Level.SAFE)
        val r = gate.run(gateReq(ctx, cls = ActionClass.SPEND, tool = "run_ussd")) { "spent" }
        assertEquals(1, cards)
        assertTrue(r.contains("did NOT allow"))
    }
}
