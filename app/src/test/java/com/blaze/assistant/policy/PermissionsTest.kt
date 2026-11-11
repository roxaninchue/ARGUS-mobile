package com.blaze.assistant.policy

import com.blaze.assistant.policy.Policy.Call
import com.blaze.assistant.policy.Policy.CardKind
import com.blaze.assistant.policy.Policy.Ctx
import com.blaze.assistant.policy.Policy.Decision
import com.blaze.assistant.policy.Policy.Step
import com.blaze.assistant.policy.Policy.Subject
import com.blaze.assistant.policy.Policy.Trigger
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.Permissions
import com.blaze.assistant.team.Rule
import com.blaze.assistant.team.SendScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-SF-04: the Permissions page's model and its adapter into Policy.decide (PermissionsModel.kt). Pure JVM.
 * Covers: cap 0 → every spend asks; allowance / per-purchase max; revoke ("Ask again") makes a standing allow ask
 * again; per-bot autonomy reaches Policy.decide; the lowest permission along a chain; send scope; persistence codecs.
 */
class PermissionsTest {
    private val zone = "Africa/Lusaka"
    private fun bot(id: String, autonomy: Autonomy = Autonomy.ACT, cap: Long = 0, scope: SendScope = SendScope.DRAFT_ONLY) =
        Subject(id, Permissions(autonomy = autonomy, actions = Policy.CLASS_DEFAULTS, spendCapMonthlyMinor = cap, currency = "ZMW", sendScope = scope))

    private val money = bot("money")
    private val scout = bot("scout")
    private val pay = Call("run_ussd", mapOf("code" to "*115#"))
    private val email = Call("gmail_send", mapOf("to" to "alex@example.com"))
    private fun zmw(minor: Long, merchant: String? = null) = SpendFacts(minor, "ZMW", merchant)
    private fun settings(vararg p: BotPolicy) = p.fold(PermissionSettings()) { s, b -> s.with(b) }

    private fun decide(who: Subject, call: Call, s: PermissionSettings, ctx: Ctx = Ctx(), spend: SpendFacts? = null, send: SendFacts? = null,
                       spent: Map<String, Long> = emptyMap()) =
        PermissionsModel.decide(who, call, ctx, s, spend, send, { id, _ -> spent[id] ?: 0L }, zone)

    // ---------------------------------------------------------------- SpendGuard → Policy

    @Test fun capZeroEverySpendAsks() {
        val s = PermissionSettings()   // nothing set: the default allowance is 0
        for (amt in listOf(1L, 100L, 5_000L, 1_000_000L)) for (a in Autonomy.values()) {
            val d = decide(bot("money", a), pay, s, spend = zmw(amt))
            assertTrue("$amt $a → card", d is Decision.Card)
            assertEquals(ActionClass.SPEND, d.cls)
            assertEquals(Step.ALWAYS_ASK, d.step)
        }
        // an explicit 0 behaves the same, even with a per-purchase max and an allowed merchant
        val z = settings(BotPolicy("money", monthlyCapMinor = 0, perTxMaxMinor = 10_000, allowedMerchants = setOf("Shoprite")))
        assertTrue(decide(money, pay, z, spend = zmw(500, "Shoprite")) is Decision.Card)
        // no facts given for a SPEND → it asks too
        assertTrue(decide(money, pay, settings(BotPolicy("money", monthlyCapMinor = 50_000))) is Decision.Card)
    }

    @Test fun withinAllowanceRunsOverItAsks() {
        val s = settings(BotPolicy("money", monthlyCapMinor = 50_000))
        assertEquals(Decision.Allow(ActionClass.SPEND, Step.STANDING), decide(money, pay, s, spend = zmw(20_000)))
        assertTrue(decide(money, pay, s, spend = zmw(20_000), spent = mapOf("money" to 40_000)) is Decision.Card)
        assertEquals(Decision.Allow(ActionClass.SPEND, Step.STANDING), decide(money, pay, s, spend = zmw(10_000), spent = mapOf("money" to 40_000)))
        // other currency asks
        assertTrue(decide(money, pay, s, spend = SpendFacts(100, "USD")) is Decision.Card)
        // Policy's own rules still win: proactive and tainted spends always ask
        assertTrue(decide(money, pay, s, Ctx(trigger = Trigger.ROUTINE), spend = zmw(100)) is Decision.Card)
        assertTrue(decide(money, pay, s, Ctx(taint = "a web page"), spend = zmw(100)) is Decision.Card)
        // NEVER for spending refuses before SpendGuard is even asked
        val never = Subject("money", Permissions(actions = Policy.CLASS_DEFAULTS + (ActionClass.SPEND to Rule.NEVER), spendCapMonthlyMinor = 50_000))
        assertTrue(decide(never, pay, s, spend = zmw(100)) is Decision.Refuse)
    }

    @Test fun overPerPurchaseMaxIsRefused() {
        val s = settings(BotPolicy("money", monthlyCapMinor = 100_000, perTxMaxMinor = 5_000))
        val d = decide(money, pay, s, spend = zmw(6_000))
        assertTrue(d is Decision.Refuse)
        assertTrue((d as Decision.Refuse).reason.contains("per-purchase limit"))
        assertEquals(Decision.Allow(ActionClass.SPEND, Step.STANDING), decide(money, pay, s, spend = zmw(5_000)))
    }

    @Test fun allowedMerchantsOnly() {
        val s = settings(BotPolicy("money", monthlyCapMinor = 100_000, allowedMerchants = setOf("Shoprite")))
        assertTrue(decide(money, pay, s, spend = zmw(500, "shoprite")).allowed)
        assertTrue(decide(money, pay, s, spend = zmw(500, "Game")) is Decision.Card)
        assertTrue(decide(money, pay, s, spend = zmw(500)) is Decision.Card)
    }

    @Test fun ctxFlagsComeFromTheAdapter() {
        val s = settings(BotPolicy("money", monthlyCapMinor = 1_000))
        val p = PermissionsModel.prepare(money, Ctx(), s, spend = zmw(500), spent = { _, _ -> 0L }, zone = zone)
        assertTrue(p.ctx.spendPreApproved)
        assertFalse(p.ctx.sendPreApproved)
        assertEquals(SpendOutcome.ALLOW, p.spend!!.outcome)
        assertEquals(1_000L, p.bot.permissions.spendCapMonthlyMinor)
        val q = PermissionsModel.prepare(money, Ctx(), s, spend = zmw(1_500), zone = zone)
        assertFalse(q.ctx.spendPreApproved)
        assertEquals(SpendReason.OVER_CAP, q.spend!!.reason)
    }

    // ---------------------------------------------------------------- standing allows: Ask again

    @Test fun revokeMakesItAskAgain() {
        val store = InMemoryPolicyStore()
        val call = Call("add_calendar_event")   // WRITE_REMOTE: asks on Act by default
        val ctx = Ctx(store = store)
        val s = PermissionSettings()
        assertTrue(decide(scout, call, s, ctx) is Decision.Card)
        store.set("add_calendar_event", "scout", Rule.ALLOW, "user", 1)
        assertEquals(Decision.Allow(ActionClass.WRITE_REMOTE, Step.STANDING), decide(scout, call, s, ctx))
        val rows = PermissionsModel.standing(store, listOf("scout"))
        assertEquals(1, rows.size)
        assertTrue(rows[0].applies)
        PermissionsModel.askAgain(store, rows[0])
        assertNull(store.rule("add_calendar_event", "scout"))
        assertTrue(decide(scout, call, s, ctx) is Decision.Card)
        assertTrue(PermissionsModel.standing(store).isEmpty())
    }

    @Test fun askAgainOnlyTouchesThatRow() {
        val store = InMemoryPolicyStore().apply {
            set("add_calendar_event", "scout", Rule.ALLOW, "user", 1)
            set("add_calendar_event", "money", Rule.ALLOW, "user", 2)
            set("git", PolicyStore.ANY_BOT, Rule.ALLOW, "user", 3)
            set("send_sms", "scout", Rule.ALLOW, "user", 4)   // SEND can't be pre-approved: listed but doesn't apply
        }
        val rows = PermissionsModel.standing(store, listOf("money", "scout"))
        assertEquals(listOf("money", "scout", "scout", "*"), rows.map { it.botId })
        assertFalse(rows.first { it.tool == "send_sms" }.applies)
        PermissionsModel.askAgain(store, rows.first { it.botId == "scout" && it.tool == "add_calendar_event" })
        assertNull(store.rule("add_calendar_event", "scout"))
        assertEquals(Rule.ALLOW, store.rule("add_calendar_event", "money"))
        assertEquals(Rule.ALLOW, store.rule("git", "scout"))   // the * row still covers scout
        assertEquals(1, PermissionsModel.askAgainAll(store, "scout"))
        assertEquals(2, store.all().size)
    }

    // ---------------------------------------------------------------- autonomy per bot

    @Test fun perBotAutonomyHonouredByPolicy() {
        val local = Call("save_file")             // WRITE_LOCAL
        val remote = Call("add_calendar_event")   // WRITE_REMOTE
        // bot record says Act; the page overrides
        assertTrue(decide(scout, local, PermissionSettings()).allowed)
        assertTrue(decide(scout, remote, PermissionSettings()) is Decision.Card)

        val assist = settings(BotPolicy("scout", autonomy = Autonomy.ASSIST))
        assertEquals(Step.ASSIST, decide(scout, local, assist).step)
        assertTrue(decide(scout, local, assist) is Decision.Card)
        assertTrue(decide(scout, Call("web_search"), assist).allowed)   // reading never asks

        val auto = settings(BotPolicy("scout", autonomy = Autonomy.AUTOPILOT))
        assertEquals(Decision.Allow(ActionClass.WRITE_REMOTE, Step.AUTOPILOT), decide(scout, remote, auto))
        assertEquals(Decision.AllowWithUndo(ActionClass.WRITE_REMOTE), decide(scout, remote, auto, Ctx(undoable = true)))
        // the exceptions still ask on Autopilot
        assertTrue(decide(scout, Call("file_delete"), auto) is Decision.Card)
        assertTrue(decide(scout, pay, auto, spend = zmw(100)) is Decision.Card)
        assertEquals(CardKind.DRAFT, (decide(scout, email, auto) as Decision.Card).kind)
        // another bot's setting doesn't leak
        assertTrue(decide(money, remote, auto) is Decision.Card)
        // a bot whose record is Autopilot can be turned down to Act from the page
        val down = settings(BotPolicy("scout", autonomy = Autonomy.ACT))
        assertTrue(decide(bot("scout", Autonomy.AUTOPILOT), remote, down) is Decision.Card)
    }

    // ---------------------------------------------------------------- chains: the lowest permission

    @Test fun chainLowestAutonomyViaAdapter() {
        val remote = Call("add_calendar_event")
        val s = settings(BotPolicy("scout", autonomy = Autonomy.AUTOPILOT), BotPolicy("money", autonomy = Autonomy.ASSIST))
        assertTrue(decide(scout, remote, s).allowed)
        val d = decide(scout, remote, s, Ctx(chain = listOf(money)))
        assertTrue(d is Decision.Card)
        assertEquals(Step.ASSIST, d.step)
    }

    @Test fun chainLowestSpendCapViaAdapter() {
        // Scout may auto-spend 1000; Money (who asked) has no allowance → it asks
        val s = settings(BotPolicy("scout", monthlyCapMinor = 100_000), BotPolicy("money", monthlyCapMinor = 0))
        assertTrue(decide(scout, pay, s, spend = zmw(500)).allowed)
        assertTrue(decide(scout, pay, s, Ctx(chain = listOf(money)), spend = zmw(500)) is Decision.Card)
        // Money allows 50.00 total and already spent 45.00 this month: Scout can't buy 10.00 for it
        val t = settings(BotPolicy("scout", monthlyCapMinor = 100_000), BotPolicy("money", monthlyCapMinor = 5_000))
        assertTrue(decide(scout, pay, t, Ctx(chain = listOf(money)), spend = zmw(1_000)).allowed)
        assertTrue(decide(scout, pay, t, Ctx(chain = listOf(money)), spend = zmw(1_000), spent = mapOf("money" to 4_500)) is Decision.Card)
        // the smallest per-purchase max along the chain refuses
        val u = settings(BotPolicy("scout", monthlyCapMinor = 100_000), BotPolicy("money", monthlyCapMinor = 100_000, perTxMaxMinor = 2_000))
        assertTrue(decide(scout, pay, u, Ctx(chain = listOf(money)), spend = zmw(3_000)) is Decision.Refuse)
        // merchants: allowed by every link only
        val v = settings(BotPolicy("scout", monthlyCapMinor = 100_000, allowedMerchants = setOf("Shoprite", "Game")),
            BotPolicy("money", monthlyCapMinor = 100_000, allowedMerchants = setOf("Game")))
        assertTrue(decide(scout, pay, v, Ctx(chain = listOf(money)), spend = zmw(500, "Game")).allowed)
        assertTrue(decide(scout, pay, v, Ctx(chain = listOf(money)), spend = zmw(500, "Shoprite")) is Decision.Card)
    }

    @Test fun chainLowestSendScopeViaAdapter() {
        val one = SendFacts(recipients = 1, allKnown = true)
        val s = settings(BotPolicy("scout", sendScope = SendScope.KNOWN_CONTACTS), BotPolicy("money", sendScope = SendScope.DRAFT_ONLY))
        assertEquals(Decision.Allow(ActionClass.SEND, Step.STANDING), decide(scout, email, s, send = one))
        assertTrue(decide(scout, email, s, Ctx(chain = listOf(money)), send = one) is Decision.Card)
        // a parent that may not send at all refuses for the whole chain
        val n = settings(BotPolicy("scout", sendScope = SendScope.ANY), BotPolicy("money", sendScope = SendScope.NONE))
        assertTrue(decide(scout, email, n, Ctx(chain = listOf(money)), send = one) is Decision.Refuse)
    }

    // ---------------------------------------------------------------- send scope

    @Test fun sendScopeRules() {
        val known = SendFacts(1, allKnown = true)
        assertFalse(PermissionsModel.sendAllowed(SendScope.DRAFT_ONLY, known))
        assertFalse(PermissionsModel.sendAllowed(SendScope.NONE, known))
        assertTrue(PermissionsModel.sendAllowed(SendScope.KNOWN_CONTACTS, known))
        assertFalse(PermissionsModel.sendAllowed(SendScope.KNOWN_CONTACTS, SendFacts(1, allKnown = false)))
        assertFalse(PermissionsModel.sendAllowed(SendScope.KNOWN_CONTACTS, SendFacts(2, allKnown = true)))
        assertFalse(PermissionsModel.sendAllowed(SendScope.KNOWN_CONTACTS, SendFacts(1, cc = 1, allKnown = true)))
        assertTrue(PermissionsModel.sendAllowed(SendScope.ANY, SendFacts(3)))
        assertFalse(PermissionsModel.sendAllowed(SendScope.ANY, SendFacts(1, public = true)))
        assertFalse(PermissionsModel.sendAllowed(SendScope.ANY, SendFacts(0)))
        // default (drafts only): every SEND is a draft card; NONE refuses
        assertEquals(CardKind.DRAFT, (decide(scout, email, PermissionSettings(), send = known) as Decision.Card).kind)
        assertTrue(decide(scout, email, settings(BotPolicy("scout", sendScope = SendScope.NONE)), send = known) is Decision.Refuse)
        // proactive sends never go without a card, whatever the scope
        val any = settings(BotPolicy("scout", sendScope = SendScope.ANY))
        assertTrue(decide(scout, email, any, send = known).allowed)
        assertTrue(decide(scout, email, any, Ctx(trigger = Trigger.CHECKIN), send = known) is Decision.Card)
    }

    // ---------------------------------------------------------------- Autopilot exceptions

    @Test fun autopilotExceptionsListed() {
        val s = settings(BotPolicy("scout", monthlyCapMinor = 0))
        val b = PermissionsModel.apply(scout, s)
        val lines = PermissionsModel.autopilotExceptions(b, PermissionsModel.spendSettings(b, s, zone))
        assertTrue(lines.any { it.startsWith("Deleting") })
        assertTrue(lines.any { it.contains("every purchase asks") })
        assertTrue(lines.any { it.contains("draft") })
        assertTrue(lines.any { it.contains("password") })
        assertTrue(lines.any { it.contains("didn't write") })
        val t = settings(BotPolicy("scout", monthlyCapMinor = 50_000, perTxMaxMinor = 10_000))
        val c = PermissionsModel.apply(scout, t)
        val l2 = PermissionsModel.autopilotExceptions(c, PermissionsModel.spendSettings(c, t, zone))
        assertTrue(l2.any { it.contains("ZMW 500.00") && it.contains("ZMW 100.00") })
        // every exception really asks on Autopilot
        val auto = settings(BotPolicy("scout", autonomy = Autonomy.AUTOPILOT))
        for (call in listOf(Call("file_delete"), Call("browser_secure_input"), Call("gmail_send"), pay))
            assertTrue(call.tool, decide(scout, call, auto) is Decision.Card)
        assertTrue(decide(scout, Call("add_calendar_event"), auto, Ctx(taint = "an email")) is Decision.Card)
        val pinned = InMemoryPolicyStore().apply { set("add_calendar_event", "scout", Rule.ASK, "user", 1) }
        assertTrue(decide(scout, Call("add_calendar_event"), auto, Ctx(store = pinned)) is Decision.Card)
    }

    // ---------------------------------------------------------------- persistence

    @Test fun settingsCodecRoundTrip() {
        val s = settings(
            BotPolicy("scout", autonomy = Autonomy.AUTOPILOT, monthlyCapMinor = 12_345, perTxMaxMinor = 2_000, currency = "ZMW",
                allowedMerchants = setOf("Shoprite", "Pick n Pay, Lusaka"), sendScope = SendScope.KNOWN_CONTACTS),
            BotPolicy("we\tird%id", autonomy = Autonomy.ASSIST))
        val text = PermissionsCodec.encode(s)
        assertEquals(s, PermissionsCodec.decode(text))
        assertEquals(PermissionSettings(), PermissionsCodec.decode(null))
        assertEquals(PermissionSettings(), PermissionsCodec.decode("garbage"))
        // a broken line / unknown value is skipped, the rest survives
        val broken = text + "\nonly\ttwo" + "\nx\tWAT\t-\t-\t-\t-\t-"
        assertEquals(s, PermissionsCodec.decode(broken))
        // clearing every field drops the bot
        assertTrue(s.with(BotPolicy("scout")).bots.keys == setOf("we\tird%id"))
    }

    @Test fun textPolicyStorePersists() {
        var saved: String? = null
        val a = TextPolicyStore({ saved }, { saved = it })
        a.set("add_calendar_event", "scout", Rule.ALLOW, "user", 5)
        a.set("forget", PolicyStore.ANY_BOT, Rule.NEVER, "user", 6)
        val b = TextPolicyStore({ saved }, { saved = it })
        assertEquals(a.all(), b.all())
        assertEquals(Rule.NEVER, b.rule("forget", "scout"))
        b.clear("add_calendar_event", "scout")
        assertNull(TextPolicyStore({ saved }, { saved = it }).rule("add_calendar_event", "scout"))
        val sql = InMemoryPolicyStore()
        b.migrateTo(sql)
        assertEquals(Rule.NEVER, sql.rule("forget", "x"))
        assertTrue(TextPolicyStore({ "nonsense" }, {}).all().isEmpty())
    }

    @Test fun parseMoney() {
        assertEquals(1_250L, PermissionsModel.parseMoney("12.5"))
        assertEquals(125_000L, PermissionsModel.parseMoney("1,250.00"))
        assertEquals(1_200L, PermissionsModel.parseMoney("ZMW 12"))
        assertEquals(0L, PermissionsModel.parseMoney("0"))
        assertNull(PermissionsModel.parseMoney("-5"))
        assertNull(PermissionsModel.parseMoney("12.345"))
        assertNull(PermissionsModel.parseMoney("abc"))
        assertEquals("12.05", PermissionsModel.moneyField(1_205))
    }
}
