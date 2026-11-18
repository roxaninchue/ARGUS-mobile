package com.blaze.assistant.policy

import com.blaze.assistant.policy.Policy.Decision
import com.blaze.assistant.policy.Policy.Reason
import com.blaze.assistant.policy.SpendGuard.Entry
import com.blaze.assistant.policy.SpendGuard.Verdict
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.Permissions
import com.blaze.assistant.team.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** P2-SF-04: SpendGuard's limits and month window, and its one place in Policy.decide (SPEND still always asks). */
class SpendGuardTest {
    private val zone = ZoneId.of("Africa/Lusaka")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun capZeroAlwaysAsks() {
        for (amount in listOf(null, 0L, 1L, 100L, 1_000_000L)) for (spent in listOf(0L, 50L)) for (tx in listOf(0L, 10L, 1_000_000L))
            assertEquals(Verdict.NO_CAP, SpendGuard.decide(amount, spent, 0, tx))
        assertTrue(Verdict.NO_CAP.asks)
    }

    @Test fun withinBothLimitsOnly() {
        assertEquals(Verdict.WITHIN, SpendGuard.decide(500, 0, 1_000, 0))
        assertEquals(Verdict.WITHIN, SpendGuard.decide(500, 500, 1_000, 500))      // exactly at the cap and the max
        assertEquals(Verdict.OVER_CAP, SpendGuard.decide(501, 500, 1_000, 0))
        assertEquals(Verdict.OVER_PER_TX, SpendGuard.decide(600, 0, 1_000, 500))
        assertEquals(Verdict.UNKNOWN_AMOUNT, SpendGuard.decide(null, 0, 1_000, 500))
        assertEquals(Verdict.UNKNOWN_AMOUNT, SpendGuard.decide(-1, 0, 1_000, 500))
        assertTrue(Verdict.values().filter { it.within } == listOf(Verdict.WITHIN))
    }

    @Test fun monthWindowRollsOver() {
        val rows = listOf(
            Entry("shop", 300, "ZMW", at(2026, 10, 31, 23)),   // last month
            Entry("shop", 200, "ZMW", at(2026, 11, 1, 0)),     // first hour of the month (Lusaka, not UTC)
            Entry("shop", 100, "ZMW", at(2026, 11, 17)),
            Entry("shop", 999, "USD", at(2026, 11, 17)),       // other currency doesn't count
            Entry("travel", 50, "ZMW", at(2026, 11, 17)),
            Entry("shop", 400, "ZMW", at(2026, 12, 1, 0)),     // next month
        )
        val now = at(2026, 11, 17)
        assertEquals(300, SpendGuard.spentThisMonth(rows, now, zone, "ZMW", "shop"))
        assertEquals(350, SpendGuard.spentThisMonth(rows, now, zone, "ZMW"))
        assertEquals(400, SpendGuard.spentThisMonth(rows, at(2026, 12, 2), zone, "ZMW", "shop"))
        assertEquals(at(2026, 11, 1, 0), SpendGuard.monthStart(now, zone))
        // a payment over the cap in November fits again in December
        assertEquals(Verdict.OVER_CAP, SpendGuard.decide(250, SpendGuard.spentThisMonth(rows, now, zone, "ZMW", "shop"), 500, 0))
        assertEquals(Verdict.WITHIN, SpendGuard.decide(50, SpendGuard.spentThisMonth(rows, at(2026, 12, 2), zone, "ZMW", "shop"), 500, 0))
    }

    @Test fun moneyFormat() {
        assertEquals("ZMW 12.05", SpendGuard.money(1205, "ZMW"))
        assertEquals("ZMW 0.00", SpendGuard.money(0, "ZMW"))
    }

    private val budget = Permissions(autonomy = Autonomy.AUTOPILOT, actions = ActionClass.values().associateWith { Rule.ALLOW },
        spendCapMonthlyMinor = 1_000, spendPerTxMaxMinor = 500)

    @Test fun everySpendStillAsksInPolicy() {
        for (p in listOf(Permissions(), budget, budget.copy(spendCapMonthlyMinor = 0)))
            for (check in listOf(null, SpendGuard.Check(100, 0), SpendGuard.Check(900, 0), SpendGuard.Check(400, 900), SpendGuard.Check(null, 0)))
                for (standing in listOf(null, Rule.ALLOW)) {
                    val d = Policy.decide(Policy.Call("run_ussd", ActionClass.SPEND), Policy.Ctx(perms = p, standing = standing, spend = check))
                    assertTrue("$p $check → $d", d is Decision.Ask && d.reason == Reason.SPEND && !d.always)
                }
    }

    @Test fun overLimitLineOnTheCardOnly() {
        fun note(c: SpendGuard.Check?, p: Permissions = budget, tainted: String? = null) =
            (Policy.decide(Policy.Call("run_ussd", ActionClass.SPEND), Policy.Ctx(perms = p, spend = c, tainted = tainted)) as Decision.Ask).note
        assertNull(note(null)); assertNull(note(SpendGuard.Check(100, 0)))
        assertNull(note(SpendGuard.Check(100, 0), budget.copy(spendCapMonthlyMinor = 0)))   // no budget: no extra line, it just asks
        assertEquals("Over this bot's per-payment maximum of ZMW 5.00.", note(SpendGuard.Check(900, 0)))
        assertEquals("Over this bot's monthly spending cap of ZMW 10.00.", note(SpendGuard.Check(400, 900)))
        assertEquals("Over this bot's monthly spending cap of ZMW 10.00. Requested after reading your email.", note(SpendGuard.Check(400, 900), tainted = "your email"))
    }

    @Test fun chainTakesTheSmallestPerPaymentMax() {
        val a = Permissions(spendPerTxMaxMinor = 500); val b = Permissions(spendPerTxMaxMinor = 0); val c = Permissions(spendPerTxMaxMinor = 200)
        assertEquals(200, Policy.effective(a, listOf(b, c)).spendPerTxMaxMinor)
        assertEquals(500, Policy.effective(a, listOf(b)).spendPerTxMaxMinor)
    }
}
