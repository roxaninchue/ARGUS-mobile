package com.blaze.assistant.policy

import com.blaze.assistant.policy.ApprovalMachine.Event
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Approval
import com.blaze.assistant.team.Rule
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-SF-01: the approval card's state machine (pending → allowed / always / denied / expired / skipped). */
class ApprovalsTest {
    private var now = 1_000_000L
    private fun approvals(initial: List<Approval> = emptyList()) = Approvals(clock = { now }, initial = initial)
    private val call = Policy.Call("gcal_create", mapOf("title" to "Exam"), id = "call-1")
    private val card = Policy.Decision.Card(Policy.CardKind.APPROVAL, "Asks first", ActionClass.WRITE_REMOTE, Policy.Step.DEFAULT, true)
    private fun open(a: Approvals, bot: String = "nova") = a.open(bot, call, card, "Add “Exam” to your calendar?", "Mon 16 Nov 09:00", "{\"title\":\"Exam\"}")

    @Test fun transitionTable() {
        val want = mapOf(Event.Allow to ApprovalState.ALLOWED, Event.AlwaysAllow to ApprovalState.ALWAYS, Event.Deny to ApprovalState.DENIED,
            Event.Timeout to ApprovalState.EXPIRED, Event.UserMessage to ApprovalState.SKIPPED, Event.Restart to ApprovalState.EXPIRED)
        for ((e, s) in want) assertEquals(e.toString(), s, ApprovalMachine.next(ApprovalState.PENDING, e))
        // Final states never move.
        for (f in ApprovalState.values().filter { it.final }) for (e in want.keys) assertEquals("$f + $e", f, ApprovalMachine.next(f, e))
        assertEquals(listOf("pending", "allowed", "always", "denied", "expired", "skipped"), ApprovalState.values().map { it.wire })
    }

    @Test fun openIsPendingWithTheCallsDetails() {
        val a = approvals(); val ap = open(a)
        assertEquals("pending", ap.status); assertEquals("call-1", ap.callId); assertEquals(ActionClass.WRITE_REMOTE, ap.actionClass)
        assertEquals(1, a.pending("nova").size); assertFalse(ap.onDeviceOnly)
        val spend = a.open("money", Policy.Call("run_ussd"), card.copy(cls = ActionClass.SPEND), "Buy bundle?", "K10", "{}")
        assertTrue("money / delete approvals stay on the device", spend.onDeviceOnly)
    }

    @Test fun allowAlwaysDeny() {
        val a = approvals()
        val x = open(a); val y = open(a); val z = open(a)
        assertEquals("allowed", a.answer(x.id, Event.Allow)!!.status)
        assertEquals("always", a.answer(y.id, Event.AlwaysAllow)!!.status)
        val d = a.answer(z.id, Event.Deny, by = "phone")!!
        assertEquals("denied", d.status); assertEquals("phone", d.resolvedBy); assertEquals(now, d.resolvedAt)
        // A late second tap changes nothing.
        assertEquals("denied", a.answer(z.id, Event.Allow)!!.status)
        assertTrue(a.pending().isEmpty())
    }

    @Test fun expiresAfter25Minutes() {
        val a = approvals(); val ap = open(a)
        now += 24 * 60_000L
        assertTrue(a.sweep().isEmpty())
        now += 60_000L
        val gone = a.sweep()
        assertEquals(listOf(ap.id), gone.map { it.id }); assertEquals("expired", gone[0].status)
        assertEquals(25 * 60_000L, Approvals.TTL_MS)
    }

    @Test fun answerAfterExpiryIsExpired() {
        val a = approvals(); val ap = open(a)
        now += Approvals.TTL_MS + 1
        assertEquals("expired", a.answer(ap.id, Event.Allow)!!.status)
    }

    @Test fun newUserMessageSkipsPendingCards() {
        val a = approvals(); val mine = open(a, "nova"); val other = open(a, "scout")
        val skipped = a.onUserMessage("nova")
        assertEquals(listOf(mine.id), skipped.map { it.id }); assertEquals("skipped", skipped[0].status)
        assertEquals("pending", a.get(other.id)!!.status)  // one bot's thread never closes another's card
        assertEquals("skipped", a.onUserMessage(null).single().status)
    }

    @Test fun restartExpiresPending() {
        val before = approvals(); val p = open(before); val done = open(before); before.answer(done.id, Event.Allow)
        // The app restarts: rows come back from team.db.
        val after = approvals(listOf(before.get(p.id)!!, before.get(done.id)!!))
        val expired = after.onAppStart()
        assertEquals(listOf(p.id), expired.map { it.id })
        assertEquals("expired", after.get(p.id)!!.status); assertEquals("restart", after.get(p.id)!!.resolvedBy)
        assertEquals("allowed", after.get(done.id)!!.status)
    }

    @Test fun listenersSeeOpenAndClose() {
        val a = approvals(); val seen = mutableListOf<String>()
        a.listen { seen += it.status }
        val ap = open(a); a.answer(ap.id, Event.Deny)
        assertEquals(listOf("pending", "denied"), seen)
    }

    @Test fun awaitInsideTheToolCall() = runBlocking {
        val a = approvals(); val ap = open(a)
        val waiting = async { a.await(ap.id) }
        yield()
        a.answer(ap.id, Event.Allow)
        assertEquals(ApprovalState.ALLOWED, waiting.await())
        assertNull(Approvals.refusal(ApprovalState.ALLOWED, "gcal_create"))
        // Already closed: returns at once.
        assertEquals(ApprovalState.ALLOWED, a.await(ap.id))
    }

    @Test fun awaitSkippedByNewMessage() = runBlocking {
        val a = approvals(); val ap = open(a)
        val waiting = async { a.await(ap.id) }
        yield()
        a.onUserMessage("nova")
        assertEquals(ApprovalState.SKIPPED, waiting.await())
    }

    @Test fun awaitTimesOutAsExpired() = runBlocking {
        val a = Approvals(clock = { now }, ttlMs = 30)
        val ap = open(a)
        assertEquals(ApprovalState.EXPIRED, a.await(ap.id))
        assertEquals("expired", a.get(ap.id)!!.status)
    }

    @Test fun refusalTexts() {
        assertTrue(Approvals.refusal(ApprovalState.DENIED, "send_sms")!!.contains("Do not retry"))
        assertTrue(Approvals.refusal(ApprovalState.EXPIRED, "send_sms")!!.contains("expired"))
        assertTrue(Approvals.refusal(ApprovalState.SKIPPED, "send_sms")!!.contains("new message"))
        assertNull(Approvals.refusal(ApprovalState.ALWAYS, "send_sms"))
    }

    @Test fun alwaysAllowBecomesAStandingRule() {
        val a = approvals(); val s = InMemoryPolicyStore(); val ap = open(a)
        val done = a.answer(ap.id, Event.AlwaysAllow)!!
        assertTrue(Policy.remember(s, done, now))
        assertEquals(Rule.ALLOW, s.rule("gcal_create", "nova"))
    }

    @Test fun sqlPolicyStoreStatements() {
        val log = mutableListOf<Pair<String, List<Any?>>>()
        val rows = mutableListOf<Map<String, Any?>>()
        var changed = 0; var txs = 0
        val st = SqlPolicyStore(object : SqlPolicyStore.Sql {
            override fun exec(statement: String, args: List<Any?>) { log += statement to args }
            override fun query(statement: String, args: List<Any?>) = rows.toList()
            override fun update(statement: String, args: List<Any?>): Int { log += statement to args; return changed }
            override fun <T> transaction(block: () -> T): T { txs++; return block() }
        })
        // No row yet: UPDATE changes nothing, so the INSERT follows, both in one transaction.
        st.set("gcal_create", "nova", Rule.ALLOW, "user", 7)
        assertEquals(listOf(SqlPolicyStore.UPDATE to listOf<Any?>("ALLOW", "user", 7L, "gcal_create", "nova"),
            SqlPolicyStore.INSERT to listOf<Any?>("gcal_create", "nova", "ALLOW", "user", 7L)), log)
        assertEquals(1, txs)
        // The row exists: the UPDATE hits it and no INSERT is sent.
        log.clear(); changed = 1
        st.set("gcal_create", "nova", Rule.NEVER, "user", 8)
        assertEquals(listOf(SqlPolicyStore.UPDATE to listOf<Any?>("NEVER", "user", 8L, "gcal_create", "nova")), log)
        assertEquals(2, txs)
        assertTrue(log.none { it.first.contains("ON CONFLICT", ignoreCase = true) || it.first.contains("OR REPLACE", ignoreCase = true) })
        st.clear("gcal_create", "nova")
        assertEquals(SqlPolicyStore.DELETE, log.last().first)
        assertNull(st.rule("gcal_create", "nova"))
        rows += mapOf("tool" to "gcal_create", "bot_id" to "*", "rule" to "NEVER", "set_by" to "user", "at" to 3L)
        assertEquals(Rule.NEVER, st.rule("gcal_create", "nova"))
        assertEquals(1, st.all().size)
    }
}
