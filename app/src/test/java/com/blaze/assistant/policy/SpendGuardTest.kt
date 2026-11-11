package com.blaze.assistant.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** P2-SF-04 pure core: cap 0 → every spend asks, per-transaction max, month rollover in the user's timezone, chain = lowest. */
class SpendGuardTest {
    private val lusaka = "Africa/Lusaka"
    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int = 0, zone: String = lusaka) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test fun defaultCapZeroAsksForEverySpend() {
        val s = SpendSettings(zone = lusaka)
        assertEquals(0L, s.monthlyCapMinor)
        for (amt in listOf(1L, 100L, 999_999L)) {
            val v = SpendGuard.check(amt, "ZMW", 0, s)
            assertEquals(SpendOutcome.ASK, v.outcome); assertEquals(SpendReason.CAP_ZERO, v.reason)
        }
    }

    @Test fun withinCapAllowsAndExactCapIsAllowed() {
        val s = SpendSettings(monthlyCapMinor = 50_000, zone = lusaka)       // K500
        SpendGuard.check(10_000, "ZMW", 20_000, s).let {
            assertEquals(SpendOutcome.ALLOW, it.outcome); assertEquals(SpendReason.WITHIN_CAP, it.reason); assertEquals(30_000L, it.remainingMinor)
        }
        assertEquals(SpendOutcome.ALLOW, SpendGuard.check(30_000, "zmw", 20_000, s).outcome)  // lands exactly on the cap
        SpendGuard.check(30_001, "ZMW", 20_000, s).let { assertEquals(SpendOutcome.ASK, it.outcome); assertEquals(SpendReason.OVER_CAP, it.reason) }
        SpendGuard.check(1, "ZMW", 60_000, s).let { assertEquals(SpendReason.OVER_CAP, it.reason); assertEquals(0L, it.remainingMinor) }
    }

    @Test fun perTransactionMaxDenies() {
        val s = SpendSettings(monthlyCapMinor = 100_000, perTxMaxMinor = 20_000, zone = lusaka)
        assertEquals(SpendOutcome.ALLOW, SpendGuard.check(20_000, "ZMW", 0, s).outcome)
        SpendGuard.check(20_001, "ZMW", 0, s).let {
            assertEquals(SpendOutcome.DENY, it.outcome); assertEquals(SpendReason.OVER_TX_MAX, it.reason)
            assertTrue(it.message, it.message.contains("ZMW 200.00"))
        }
        // the max applies even with cap 0 (it is a hard limit, the cap only decides ask vs auto)
        assertEquals(SpendReason.OVER_TX_MAX, SpendGuard.check(30_000, "ZMW", 0, s.copy(monthlyCapMinor = 0)).reason)
        assertEquals(SpendReason.CAP_ZERO, SpendGuard.check(10_000, "ZMW", 0, s.copy(monthlyCapMinor = 0)).reason)
    }

    @Test fun badAmountsCurrencyAndMerchants() {
        val s = SpendSettings(monthlyCapMinor = 100_000, zone = lusaka)
        assertEquals(SpendReason.INVALID_AMOUNT, SpendGuard.check(0, "ZMW", 0, s).reason)
        assertEquals(SpendOutcome.DENY, SpendGuard.check(-5, "ZMW", 0, s).outcome)
        SpendGuard.check(100, "USD", 0, s).let { assertEquals(SpendOutcome.ASK, it.outcome); assertEquals(SpendReason.CURRENCY_MISMATCH, it.reason) }
        val m = s.copy(allowedMerchants = setOf("ZESCO", "Airtel"))
        assertEquals(SpendOutcome.ALLOW, SpendGuard.check(100, "ZMW", 0, m, merchant = "zesco").outcome)
        assertEquals(SpendReason.MERCHANT_NOT_ALLOWED, SpendGuard.check(100, "ZMW", 0, m, merchant = "Amazon").reason)
        assertEquals(SpendReason.MERCHANT_NOT_ALLOWED, SpendGuard.check(100, "ZMW", 0, m).reason)
        // no overflow with huge numbers
        assertEquals(SpendOutcome.ASK, SpendGuard.check(Long.MAX_VALUE, "ZMW", Long.MAX_VALUE, s).outcome)
    }

    @Test fun monthRollsOverInTheUsersTimezone() {
        // 31 Oct 23:30 in Lusaka (UTC+2) is 21:30 UTC; 1 Nov 00:30 Lusaka is still 31 Oct 22:30 UTC.
        val lateOct = ms(2026, 10, 31, 23, 30)
        val earlyNov = ms(2026, 11, 1, 0, 30)
        assertEquals("2026-10", SpendMonth.key(lateOct, lusaka))
        assertEquals("2026-11", SpendMonth.key(earlyNov, lusaka))
        assertEquals("2026-10", SpendMonth.key(earlyNov, "UTC"))          // a UTC clock would put it in October
        val (s, e) = SpendMonth.bounds(ms(2026, 11, 11, 22, 42), lusaka)
        assertEquals(ms(2026, 11, 1, 0, 0), s); assertEquals(ms(2026, 12, 1, 0, 0), e)
        // December → January crosses the year
        val (ds, de) = SpendMonth.bounds(ms(2026, 12, 31, 23, 59), lusaka)
        assertEquals(ms(2026, 12, 1, 0), ds); assertEquals(ms(2027, 1, 1, 0), de)
        // DST zone: the March month is one hour shorter in New York
        val ny = "America/New_York"
        val (ns, ne) = SpendMonth.bounds(ms(2027, 3, 15, 12, zone = ny), ny)
        assertEquals(31L * 24 * 3600_000 - 3600_000, ne - ns)
    }

    @Test fun ledgerTotalsPerBotPerMonthPerCurrency() {
        val nov5 = ms(2026, 11, 5, 10); val oct31late = ms(2026, 10, 31, 23, 30); val nov1early = ms(2026, 11, 1, 0, 30)
        val rows = listOf(
            SpendEntry("1", "money", "a1", 10_000, "ZMW", "ZESCO", nov5),
            SpendEntry("2", "money", "a2", 5_000, "ZMW", "Airtel", nov1early),   // November in Lusaka
            SpendEntry("3", "money", "a3", 7_000, "ZMW", "Shoprite", oct31late), // October
            SpendEntry("4", "scout", "a4", 2_000, "ZMW", "Uber", nov5),
            SpendEntry("5", "money", "a5", 900, "USD", "Netflix", nov5))
        val now = ms(2026, 11, 11, 22, 42)
        assertEquals(15_000L, SpendLedger.monthSpent(rows, "money", "ZMW", now, lusaka))
        assertEquals(17_000L, SpendLedger.monthSpent(rows, null, "ZMW", now, lusaka))
        assertEquals(900L, SpendLedger.monthSpent(rows, "money", "usd", now, lusaka))
        // On 1 Dec the month starts over.
        assertEquals(0L, SpendLedger.monthSpent(rows, "money", "ZMW", ms(2026, 12, 1, 0, 1), lusaka))
        // Fed into check: K500 cap, K150 spent → K350 more asks, K350 exactly is fine.
        val s = SpendSettings(monthlyCapMinor = 50_000, zone = lusaka)
        val spent = SpendLedger.monthSpent(rows, "money", "ZMW", now, lusaka)
        assertEquals(SpendOutcome.ALLOW, SpendGuard.check(35_000, "ZMW", spent, s).outcome)
        assertEquals(SpendOutcome.ASK, SpendGuard.check(35_001, "ZMW", spent, s).outcome)
    }

    @Test fun chainInheritsTheLowestPermission() {
        val money = SpendSettings(monthlyCapMinor = 100_000, perTxMaxMinor = 50_000, allowedMerchants = setOf("ZESCO", "Airtel"), zone = lusaka)
        val scout = SpendSettings(monthlyCapMinor = 300_000, perTxMaxMinor = null, allowedMerchants = setOf("airtel", "Uber"), zone = lusaka)
        val l = SpendGuard.lowest(listOf(scout, money))
        assertEquals(100_000L, l.monthlyCapMinor); assertEquals(50_000L, l.perTxMaxMinor); assertEquals(setOf("airtel"), l.allowedMerchants)
        assertEquals(SpendOutcome.ALLOW, SpendGuard.check(1_000, "ZMW", 0, l, "Airtel").outcome)
        assertEquals(SpendOutcome.ASK, SpendGuard.check(1_000, "ZMW", 0, l, "Uber").outcome)
        // A link with cap 0 makes the whole chain ask.
        assertEquals(0L, SpendGuard.lowest(listOf(scout, SpendSettings(zone = lusaka))).monthlyCapMinor)
        // Disjoint merchant lists: nothing auto-pays.
        val d = SpendGuard.lowest(listOf(scout.copy(allowedMerchants = setOf("Uber")), money))
        assertEquals(0L, d.monthlyCapMinor)
    }

    @Test fun moneyFormat() {
        assertEquals("ZMW 12.50", SpendGuard.money(1250, "zmw"))
        assertEquals("USD 0.05", SpendGuard.money(5, "USD"))
        assertEquals("-ZMW 1.00", SpendGuard.money(-100, "ZMW"))
    }
}
