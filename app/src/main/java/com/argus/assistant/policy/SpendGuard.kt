package com.argus.assistant.policy

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P2-SF-04 SpendGuard (05 §7.1, NEW-09): a bot's monthly spending cap and per-payment maximum, read against this month's
 * spend_ledger rows. Pure Kotlin: rows come in as data ([Entry]), the clock and zone are arguments. SpendGuardTest.
 *
 * The verdict never lets a payment skip its card: SPEND always asks in [Policy.decide] (the harness must: approval for
 * pay stays). What the guard adds is the line on the card when a payment is over a limit, and the Permissions page's
 * "spent this month". A cap of 0 (the default) means no budget at all, so every spend asks ([Verdict.NO_CAP]).
 */
object SpendGuard {
    /** One spend_ledger row (team.db): what was paid, by which bot, when. */
    data class Entry(val botId: String, val amountMinor: Long, val currency: String, val at: Long)

    /** The limits of one bot (Permissions.spendCapMonthlyMinor / spendPerTxMaxMinor / currency). 0 per payment = only the cap. */
    data class Limits(val capMonthlyMinor: Long = 0, val perTxMaxMinor: Long = 0, val currency: String = "ZMW")

    /** One payment the policy is asked about: its amount (null = unknown) and what the bot spent this month already. */
    data class Check(val amountMinor: Long?, val spentThisMonthMinor: Long)

    enum class Verdict(val within: Boolean) {
        /** Inside the cap and the per-payment maximum. */
        WITHIN(true),
        /** Cap 0: no spending budget, every spend asks. */
        NO_CAP(false),
        /** The amount isn't known, so it can't be inside a limit. */
        UNKNOWN_AMOUNT(false),
        /** More than the per-payment maximum. */
        OVER_PER_TX(false),
        /** This payment would take the month over the cap. */
        OVER_CAP(false);
        val asks get() = !within
    }

    /**
     * ASK unless within both limits and cap > 0. [perTxMax] 0 = no separate per-payment limit (the cap alone).
     */
    fun decide(amountMinor: Long?, spentThisMonth: Long, cap: Long, perTxMax: Long): Verdict = when {
        cap <= 0 -> Verdict.NO_CAP
        amountMinor == null || amountMinor < 0 -> Verdict.UNKNOWN_AMOUNT
        perTxMax > 0 && amountMinor > perTxMax -> Verdict.OVER_PER_TX
        spentThisMonth.coerceAtLeast(0) + amountMinor > cap -> Verdict.OVER_CAP
        else -> Verdict.WITHIN
    }

    fun decide(check: Check, limits: Limits): Verdict =
        decide(check.amountMinor, check.spentThisMonthMinor, limits.capMonthlyMinor, limits.perTxMaxMinor)

    /** Start of the calendar month [now] falls in, in [zone] (the user's month, not UTC's). */
    fun monthStart(now: Long, zone: ZoneId): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).withDayOfMonth(1).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** Start of the next month (the window's end, exclusive). */
    fun nextMonthStart(now: Long, zone: ZoneId): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).withDayOfMonth(1).toLocalDate().plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** What [botId] (null = every bot) spent in [currency] this month. Other currencies aren't converted, so they don't count. */
    fun spentThisMonth(rows: List<Entry>, now: Long, zone: ZoneId, currency: String, botId: String? = null): Long {
        val from = monthStart(now, zone); val to = nextMonthStart(now, zone)
        return rows.filter { it.at in from until to && it.currency.equals(currency, true) && (botId == null || it.botId == botId) }
            .sumOf { it.amountMinor.coerceAtLeast(0) }
    }

    /** "ZMW 12.50" (minor units are hundredths). */
    fun money(minor: Long, currency: String): String {
        val sign = if (minor < 0) "-" else ""
        val m = kotlin.math.abs(minor)
        return "$sign$currency ${m / 100}.${(m % 100).toString().padStart(2, '0')}"
    }

    /** The line on the approval card when a payment is over a limit the user set; null otherwise (no new text). */
    fun note(v: Verdict, limits: Limits): String? = when (v) {
        Verdict.OVER_CAP -> "Over this bot's monthly spending cap of ${money(limits.capMonthlyMinor, limits.currency)}."
        Verdict.OVER_PER_TX -> "Over this bot's per-payment maximum of ${money(limits.perTxMaxMinor, limits.currency)}."
        else -> null
    }
}
