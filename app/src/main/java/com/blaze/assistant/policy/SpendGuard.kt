package com.blaze.assistant.policy

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P2-SF-04 pure core (05-agent-ecosystem.md §7.3): SpendGuard, pure Kotlin (no android.*), SpendGuardTest on the JVM.
 *
 * Per bot: a monthly cap (default 0 → every spend asks), an optional per-transaction max, the cap's currency and
 * optional allowed merchants. [SpendGuard.check] is the one function P2-SF-01's `Policy.decide` calls for a SPEND call;
 * it does not know about Policy, cards or the database. The month's total comes from `spend_ledger`
 * ([SpendLedger.monthSpent] in memory, `AuditDao.monthSpent` on the phone), with the month rolling over at midnight on
 * the 1st in the user's timezone ([SpendMonth]).
 *
 * Verdicts: ALLOW = the money side lets this through without a card (Policy still applies every other rule, e.g. §7.2
 * "proactive SPEND is never unconfirmed"); ASK = show the SPEND card; DENY = the bot may not make this spend at all
 * (bad amount, or over the per-transaction max the user set; raising the max is the user's call in Permissions).
 */
data class SpendSettings(
    /** Auto-spend allowance per calendar month, minor units. 0 (default) = every spend asks. */
    val monthlyCapMinor: Long = 0,
    /** Largest single spend the bot may make at all, minor units; null = no per-transaction max. */
    val perTxMaxMinor: Long? = null,
    /** ISO 4217 code the cap and max are in (05 §9.1 Permissions.currency default). */
    val currency: String = "ZMW",
    /** Empty = any merchant may be auto-paid within the cap; otherwise the rest ask. Compared case-insensitively. */
    val allowedMerchants: Set<String> = emptySet(),
    /** IANA zone the month rolls over in (the user's timezone). */
    val zone: String = ZoneId.systemDefault().id,
)

enum class SpendOutcome { ALLOW, ASK, DENY }

enum class SpendReason { WITHIN_CAP, CAP_ZERO, OVER_CAP, OVER_TX_MAX, CURRENCY_MISMATCH, MERCHANT_NOT_ALLOWED, INVALID_AMOUNT }

/** [remainingMinor] = what is left of this month's cap before this spend (never negative). */
data class SpendVerdict(val outcome: SpendOutcome, val reason: SpendReason, val remainingMinor: Long, val message: String)

object SpendGuard {

    /** Minor units → "ZMW 12.50" (2 decimals; enough for the card line and the reason text). */
    fun money(minor: Long, currency: String): String {
        val neg = minor < 0
        val a = Math.abs(minor)
        return (if (neg) "-" else "") + currency.uppercase() + " " + (a / 100) + "." + (a % 100).toString().padStart(2, '0')
    }

    /**
     * Is a spend of [amountMinor] [currency] allowed through without a card, given [monthSpentMinor] already spent this
     * month (in [settings].currency)? [merchant] is optional and only matters when [SpendSettings.allowedMerchants] is set.
     */
    fun check(amountMinor: Long, currency: String, monthSpentMinor: Long, settings: SpendSettings, merchant: String? = null): SpendVerdict {
        val cap = settings.monthlyCapMinor.coerceAtLeast(0)
        val spent = monthSpentMinor.coerceAtLeast(0)
        val left = (cap - spent).coerceAtLeast(0)
        val cur = settings.currency
        if (amountMinor <= 0) return SpendVerdict(SpendOutcome.DENY, SpendReason.INVALID_AMOUNT, left, "Not a spend: the amount must be above zero.")
        if (!currency.trim().equals(cur.trim(), ignoreCase = true))
            return SpendVerdict(SpendOutcome.ASK, SpendReason.CURRENCY_MISMATCH, left,
                "Asks: ${currency.uppercase()} isn't the cap's currency (${cur.uppercase()}).")
        val max = settings.perTxMaxMinor
        if (max != null && amountMinor > max)
            return SpendVerdict(SpendOutcome.DENY, SpendReason.OVER_TX_MAX, left,
                "Over the per-purchase limit of ${money(max, cur)}. Raise it in Approvals & permissions to allow this.")
        if (settings.allowedMerchants.isNotEmpty()) {
            val m = merchant?.trim().orEmpty()
            if (settings.allowedMerchants.none { it.trim().equals(m, ignoreCase = true) })
                return SpendVerdict(SpendOutcome.ASK, SpendReason.MERCHANT_NOT_ALLOWED, left,
                    "Asks: ${if (m.isEmpty()) "this merchant" else m} isn't on the allowed list.")
        }
        if (cap == 0L) return SpendVerdict(SpendOutcome.ASK, SpendReason.CAP_ZERO, 0, "Asks: no monthly allowance is set, so every spend asks.")
        if (amountMinor > left)   // spent + amount > cap, without overflow
            return SpendVerdict(SpendOutcome.ASK, SpendReason.OVER_CAP, left,
                "Asks: ${money(amountMinor, cur)} is more than the ${money(left, cur)} left of this month's ${money(cap, cur)}.")
        return SpendVerdict(SpendOutcome.ALLOW, SpendReason.WITHIN_CAP, left,
            "Within this month's allowance (${money(left - amountMinor, cur)} left after this).")
    }

    /**
     * 05 §7.2: a bot acting for another inherits the LOWEST permission in the chain. Smallest cap, smallest
     * per-transaction max, merchants allowed by every link; currency and zone from the first (the bot that acts).
     */
    fun lowest(chain: List<SpendSettings>): SpendSettings {
        require(chain.isNotEmpty()) { "empty chain" }
        val first = chain.first()
        val maxes = chain.mapNotNull { it.perTxMaxMinor }
        val lists = chain.map { s -> s.allowedMerchants.map { it.trim().lowercase() }.toSet() }.filter { it.isNotEmpty() }
        var merchants = lists.firstOrNull() ?: emptySet()
        for (l in lists.drop(1)) merchants = merchants intersect l
        // Every link restricts merchants and none overlap: nothing may auto-pay (cap 0 keeps it "ask").
        val noOverlap = lists.isNotEmpty() && merchants.isEmpty()
        val sameCurrency = chain.all { it.currency.equals(first.currency, ignoreCase = true) }
        return first.copy(
            monthlyCapMinor = if (noOverlap || !sameCurrency) 0 else chain.minOf { it.monthlyCapMinor.coerceAtLeast(0) },
            perTxMaxMinor = if (sameCurrency) maxes.minOrNull() else first.perTxMaxMinor,
            allowedMerchants = merchants)
    }
}

/** One `spend_ledger` row (05 §9.2). */
data class SpendEntry(val id: String, val botId: String, val approvalId: String, val amountMinor: Long, val currency: String,
                      val merchant: String, val at: Long)

/** Calendar months in the user's timezone. */
object SpendMonth {
    /** [startMs, endMs) of the month that contains [nowMs] in [zone]. */
    fun bounds(nowMs: Long, zone: String): Pair<Long, Long> {
        val z = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC"))
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), z)
        val start = now.toLocalDate().withDayOfMonth(1).atStartOfDay(z)
        return start.toInstant().toEpochMilli() to start.plusMonths(1).toInstant().toEpochMilli()
    }

    /** "2026-11" for [nowMs] in [zone] (the Activity page's month label and a cache key). */
    fun key(nowMs: Long, zone: String): String {
        val z = runCatching { ZoneId.of(zone) }.getOrDefault(ZoneId.of("UTC"))
        val d = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), z)
        return "%04d-%02d".format(d.year, d.monthValue)
    }
}

object SpendLedger {
    /** What [botId] spent in [currency] in the month containing [nowMs] ([zone]); null [botId] = the whole team. */
    fun monthSpent(entries: List<SpendEntry>, botId: String?, currency: String, nowMs: Long, zone: String): Long {
        val (s, e) = SpendMonth.bounds(nowMs, zone)
        return entries.asSequence()
            .filter { (botId == null || it.botId == botId) && it.currency.equals(currency, ignoreCase = true) && it.at >= s && it.at < e }
            .sumOf { it.amountMinor }
    }
}
