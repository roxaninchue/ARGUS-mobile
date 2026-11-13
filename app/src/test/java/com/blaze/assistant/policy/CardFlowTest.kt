package com.blaze.assistant.policy

import com.blaze.assistant.agent.ConfirmGate
import com.blaze.assistant.orchestra.KeyedLocks
import com.blaze.assistant.orchestra.ToolScheduler
import com.blaze.assistant.store.JdbcSql
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.TeamSchema
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-SF-02: the card flow on virtual time. Don't allow → a refusal telling the bot not to retry; a new user message →
 * skipped and the card is gone at once (the P2-SF-01 gap); 25 minutes → expired; one card at a time per bot without
 * stalling another bot; drafts show every recipient and word, Send approves that call only, Edit hands back the text
 * with a one-time pass for exactly it; ask_user with ≤6 options or a free reply; request_app_connection resumes with
 * "[System] connected X".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardFlowTest {
    private fun TestScope.flow(sql: JdbcSql? = null, connected: (String) -> Boolean = { false }): CardFlow {
        val ap = Approvals({ testScheduler.currentTime }, sql?.let { ApprovalStore(it) { testScheduler.currentTime } })
        return CardFlow(ap, CardCenter(KeyedLocks()), { testScheduler.currentTime }, isConnected = connected)
    }

    private fun gate(f: CardFlow, saved: MutableList<String> = mutableListOf()) = PolicyGate(f.approvals, presenter = f.presenter,
        saveAlways = { t, b -> saved += "$t@$b" }, pass = { r, d -> f.pass(r, d) }, outcome = { f.outcome(it) })

    private fun req(bot: String = "atlas", tool: String = "gcal_create", cls: ActionClass = ActionClass.WRITE_REMOTE, args: String = "{\"title\":\"Dentist\"}",
                    chat: String = "job-1-1") =
        PolicyGate.Request(chat, bot, bot.replaceFirstChar { it.uppercase() }, "1", tool, args.take(600), Policy.Call(tool, cls), Policy.Ctx(botId = bot), true,
            argsFull = args)

    private fun mail(to: String = "nova@hitch.io", text: String = "Running 10 min late, sorry!") =
        JSONObject().put("to", to).put("cc", "ops@hitch.io").put("subject", "Today").put("body", text).toString()

    @Test fun denyTellsTheBotNotToRetry() = runTest {
        val f = flow(); val g = gate(f)
        var ran = false
        val out = async { g.run(req()) { ran = true; "created" } }
        runCurrent()
        val card = f.center.shown.value.single()
        assertEquals(Cards.Kind.APPROVAL, card.kind)
        assertTrue("WRITE_REMOTE on a direct request may offer Always allow", card.always)
        assertTrue(f.answer(card.id, CardAnswer.Deny))
        val r = out.await()
        assertFalse(ran)
        assertTrue(r, r.contains("Don't retry"))
        assertTrue(f.center.shown.value.isEmpty())
        assertEquals(Cards.State.DENIED, f.center.recent.value.last().state)
    }

    @Test fun allowRunsUnderPolicyOkAndAlwaysSavesOnlyWhereOffered() = runTest {
        val f = flow(); val saved = mutableListOf<String>(); val g = gate(f, saved)
        var ok: Boolean? = null
        val out = async { g.run(req()) { ok = ConfirmGate.policyOk(currentCoroutineContext()); "created" } }
        runCurrent()
        f.answer(f.center.shown.value.single().id, CardAnswer.Always)
        assertEquals("created", out.await())
        assertEquals(true, ok)
        assertEquals(listOf("gcal_create@atlas"), saved)
        assertEquals(Cards.State.APPROVED, f.center.recent.value.last().state)
        // SPEND never offers it, and an "Always" tap there is a plain Allow
        val spend = async { g.run(req(tool = "buy", cls = ActionClass.SPEND)) { "paid" } }
        runCurrent()
        val c = f.center.shown.value.single()
        assertFalse(c.always)
        f.answer(c.id, CardAnswer.Always)
        assertEquals("paid", spend.await())
        assertEquals(1, saved.size)
    }

    @Test fun newUserMessageSkipsAndTakesTheCardDownAtOnce() = runTest {
        val f = flow(); val g = gate(f)
        var ran = false
        val out = async { g.run(req()) { ran = true; "x" } }
        runCurrent()
        assertEquals(1, f.center.shown.value.size)
        assertEquals(1, f.approvals.userMessage("job-1-1", "atlas"))
        runCurrent()
        assertTrue("the card is gone with the approval, no 2-minute wait", f.center.shown.value.isEmpty())
        val r = out.await()
        assertFalse(ran)
        assertTrue(r, r.contains("skipped") && r.contains("Don't retry"))
        assertEquals(Cards.State.SKIPPED, f.center.recent.value.last().state)
    }

    @Test fun expiresAfter25Minutes() = runTest {
        val sql = JdbcSql().also { s -> s.tx { for (st in TeamSchema.steps(0)) s.exec(st) } }
        val f = flow(sql); val g = gate(f)
        val out = async { g.run(req()) { "x" } }
        runCurrent()
        val id = f.center.shown.value.single().id
        advanceTimeBy(Approvals.EXPIRY_MS - 1); runCurrent()
        assertEquals(1, f.center.shown.value.size)
        advanceTimeBy(2); runCurrent()
        assertTrue(f.center.shown.value.isEmpty())
        assertTrue(out.await().contains("25 minutes"))
        assertEquals(Cards.State.EXPIRED, f.center.recent.value.last().state)
        assertEquals("expired", ApprovalStore(sql).status(id))
        assertFalse("a late tap does nothing", f.answer(id, CardAnswer.Allow))
    }

    @Test fun oneCardAtATimePerBotWithoutStallingOthers() = runTest {
        val f = flow(); val g = gate(f)
        val a1 = async { g.run(req(chat = "c1")) { "a1" } }
        runCurrent()
        val a2 = async { g.run(req(chat = "c2", tool = "gcal_update")) { "a2" } }
        val n1 = async { g.run(req(bot = "nova", chat = "c3")) { "n1" } }
        runCurrent()
        val shown = f.center.shown.value
        assertEquals(listOf("atlas", "nova"), shown.map { it.botId })
        assertEquals("gcal_create", shown.first().tool)
        assertEquals(1, f.center.queued("atlas"))
        assertEquals(0, f.center.queued("nova"))
        // Nova's card is answered while Atlas's first still waits
        f.answer(shown.last().id, CardAnswer.Allow)
        assertEquals("n1", n1.await())
        f.answer(shown.first().id, CardAnswer.Allow)
        assertEquals("a1", a1.await())
        runCurrent()
        val next = f.center.shown.value.single()
        assertEquals("gcal_update", next.tool)
        f.answer(next.id, CardAnswer.Allow)
        assertEquals("a2", a2.await())
        // the lane is per bot, held only by cards
        assertEquals(KeyedLocks.Claim("user:atlas", true), ToolScheduler.Resources.userLane("atlas"))
        assertTrue(ToolScheduler.Resources.lockClaims(ToolScheduler.Resources.claims("request_app_connection", JSONObject())).isEmpty())
    }

    @Test fun draftShowsEveryRecipientAndWordAndSendApprovesThatCall() = runTest {
        val f = flow(); val g = gate(f)
        val long = "Hi Nova, " + "x".repeat(900)
        var sent: String? = null
        val out = async { g.run(req(tool = "gmail_send", cls = ActionClass.SEND, args = mail(text = long))) { sent = "ok"; "Sent to nova@hitch.io" } }
        runCurrent()
        val c = f.center.shown.value.single()
        assertEquals(Cards.Kind.DRAFT, c.kind)
        assertEquals(listOf("nova@hitch.io", "cc: ops@hitch.io"), c.draft!!.recipients)
        assertEquals("Today", c.draft!!.subject)
        assertEquals("the whole draft, not the 600-char redaction", long, c.draft!!.body)
        f.answer(c.id, CardAnswer.Send)
        assertEquals("Sent to nova@hitch.io", out.await())
        assertEquals("ok", sent)
        assertEquals(Cards.State.APPROVED, f.center.recent.value.last().state)
    }

    @Test fun editHandsBackTheTextWithAOneTimePassForExactlyIt() = runTest {
        val f = flow(); val g = gate(f)
        var runs = 0
        val first = async { g.run(req(tool = "gmail_send", cls = ActionClass.SEND, args = mail())) { runs++; "sent" } }
        runCurrent()
        f.answer(f.center.shown.value.single().id, CardAnswer.Edit("Running 15 min late."))
        val r = first.await()
        assertEquals(0, runs)
        assertTrue(r, r.contains("Running 15 min late.") && r.contains("nova@hitch.io"))
        // the same recipients + the edited text: no card
        assertEquals("sent", g.run(req(tool = "gmail_send", cls = ActionClass.SEND, args = mail(text = "Running 15 min late."))) { runs++; "sent" })
        assertEquals(1, runs)
        // the pass was used up: the same draft again asks
        val again = async { g.run(req(tool = "gmail_send", cls = ActionClass.SEND, args = mail(text = "Running 15 min late."))) { runs++; "sent" } }
        runCurrent()
        assertEquals(1, f.center.shown.value.size)
        f.answer(f.center.shown.value.single().id, CardAnswer.Discard)
        val d = again.await()
        assertTrue(d, d.contains("discarded") && d.contains("Don't retry"))
        assertEquals(1, runs)
    }

    @Test fun editPassDoesNotCoverAnotherRecipient() = runTest {
        val f = flow(); val g = gate(f)
        val first = async { g.run(req(tool = "gmail_send", cls = ActionClass.SEND, args = mail())) { "sent" } }
        runCurrent()
        f.answer(f.center.shown.value.single().id, CardAnswer.Edit("New text"))
        first.await()
        val other = async { g.run(req(tool = "gmail_send", cls = ActionClass.SEND, args = mail(to = "boss@hitch.io", text = "New text"))) { "sent" } }
        runCurrent()
        assertEquals("another recipient needs its own card", 1, f.center.shown.value.size)
        f.approvals.userMessage("job-1-1")
        assertTrue(other.await().contains("skipped"))
    }

    @Test fun questionHasAtMostSixOptionsOrAFreeReply() = runTest {
        val f = flow()
        val opts = JSONArray(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun", "mon", " "))
        val out = async { f.ask("team-atlas-g0", "atlas", "Atlas", JSONObject().put("question", "Which day?").put("options", opts).toString()) }
        runCurrent()
        val c = f.center.shown.value.single()
        assertEquals(Cards.Kind.QUESTION, c.kind)
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat"), c.question!!.options)
        f.answer(c.id, CardAnswer.Reply("Thursday after 3"))
        assertEquals("The user answered: Thursday after 3", out.await())
        // skipped by a new message; expired after 25 minutes
        val s = async { f.ask("team-atlas-g0", "atlas", "Atlas", "{\"question\":\"Which hotel?\"}") }
        runCurrent(); f.approvals.userMessage("team-atlas-g0"); runCurrent()
        assertEquals(Cards.QUESTION_SKIPPED, s.await())
        assertTrue(f.center.shown.value.isEmpty())
        val e = async { f.ask("team-atlas-g0", "atlas", "Atlas", "{\"question\":\"Which hotel?\"}") }
        runCurrent(); advanceTimeBy(Approvals.EXPIRY_MS + 1); runCurrent()
        assertEquals(Cards.QUESTION_EXPIRED, e.await())
        assertTrue(f.ask("c", "atlas", "Atlas", "{}").startsWith("Error"))
    }

    @Test fun connectResumesWithSystemConnected() = runTest {
        var on = false
        val f = flow(connected = { on && it == "Gmail" })
        val tap = async { f.connect("team-atlas-g0", "atlas", "Atlas", "{\"app\":\"Gmail\",\"reason\":\"to read the booking mail\"}") }
        runCurrent()
        val c = f.center.shown.value.single()
        assertEquals(Cards.Kind.CONNECT, c.kind); assertEquals("Gmail", c.connect!!.app); assertEquals("Atlas needs Gmail", c.title)
        f.answer(c.id, CardAnswer.Connected)
        assertEquals("[System] connected Gmail", tap.await())
        // noticed by itself once the account is connected
        val auto = async { f.connect("team-atlas-g0", "atlas", "Atlas", "{\"app\":\"Gmail\"}") }
        runCurrent(); on = true; advanceTimeBy(Cards.CONNECT_POLL_MS + 1); runCurrent()
        assertEquals("[System] connected Gmail", auto.await())
        assertTrue(f.center.shown.value.isEmpty())
        // already connected: no card
        assertTrue(f.connect("c", "atlas", "Atlas", "{\"app\":\"Gmail\"}").startsWith("[System] connected Gmail"))
        on = false
        val no = async { f.connect("c", "atlas", "Atlas", "{\"app\":\"Gmail\"}") }
        runCurrent(); f.answer(f.center.shown.value.single().id, CardAnswer.NotNow)
        assertTrue(no.await().contains("Don't retry"))
    }

    @Test fun pureHelpers() {
        val d = Cards.draftOf("message_contact", "{\"contact\":\"Mary; Joe\",\"text\":\"Hi\"}")
        assertEquals(listOf("Mary", "Joe"), d.recipients); assertEquals("Hi", d.body); assertNull(d.subject)
        assertEquals(Cards.fingerprint("t", listOf("A", "b"), null, "x"), Cards.fingerprint("t", listOf("B", "a"), null, " x "))
        assertTrue(Cards.fingerprint("t", listOf("a"), null, "x") != Cards.fingerprint("t", listOf("a"), null, "y"))
        assertEquals("Bye", JSONObject(Cards.withBody("m", "{\"text\":\"Hi\"}", "Bye")).getString("text"))
        assertEquals(Cards.State.SKIPPED, Cards.stateOf(Approvals.Status.SKIPPED))
        assertEquals(Cards.State.APPROVED, Cards.stateOf(Approvals.Status.ALWAYS))
        assertTrue(Cards.connectToolDef().contains(Cards.CONNECT_TOOL))
        assertEquals(ActionClass.READ, ToolRegistry.classOf(Cards.CONNECT_TOOL) { "" })
        assertTrue(ToolRegistry.selfCard(Cards.CONNECT_TOOL))
        val p = DraftPasses({ 0L }, 10)
        p.grant("k", "ap-1"); assertEquals("ap-1", p.take("k")); assertNull(p.take("k"))
        var now = 0L
        val q = DraftPasses({ now }, 10); q.grant("k", "ap-2"); now = 11; assertNull(q.take("k"))
    }
}
