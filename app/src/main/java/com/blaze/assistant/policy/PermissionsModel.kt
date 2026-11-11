package com.blaze.assistant.policy

import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.Rule
import com.blaze.assistant.team.SendScope
import java.time.ZoneId

/**
 * # P2-SF-04: the Permissions page's model and the adapter into [Policy.decide] (05 §7.1–§7.4)
 *
 * Pure Kotlin (no android.*, no org.json); PermissionsTest runs it on the JVM. The Android glue is
 * `PermissionsPrefs.kt` (SharedPreferences "argus_permissions"), the page is `ui/argus/settings/PermissionsPage.kt`.
 *
 * What the user sets on Settings › Approvals & permissions, per bot ([BotPolicy]):
 *  - autonomy (Assist / Act / Autopilot),
 *  - SpendGuard: monthly allowance (default 0 → every spend asks), per-purchase max, currency, allowed merchants,
 *  - send scope ([SendScope]: NONE = may not send at all, DRAFT_ONLY = every message is a draft card (default),
 *    KNOWN_CONTACTS = one informal message to someone the user has messaged before, no CC, may go without a card,
 *    ANY = a private message to anyone may go without a card). Public posts always ask.
 * Standing per-tool rules ("Always allow" / "Never") live in [PolicyStore]; "Ask again" clears one ([askAgain]).
 *
 * A [BotPolicy] field left null keeps the bot's own `Permissions` value (team.db `bots.permissions` once P0-TM-01
 * lands), so the page only ever overrides what the user touched here.
 *
 * The adapter ([prepare] / [decide]): applies each bot's settings to its [Policy.Subject] (and to every bot in the
 * chain), runs [SpendGuard.check] for a SPEND and the send-scope rule for a SEND, and hands Policy the result as
 * [Policy.Ctx.spendPreApproved] / [Policy.Ctx.sendPreApproved]. Chains get the LOWEST permission (05 §7.2): every
 * bot's own allowance must hold, the merged [SpendGuard.lowest] settings must hold, and the lowest send scope applies.
 * Policy itself still refuses proactive or tainted SPEND/SEND without a card. A spend over a per-purchase max
 * ([SpendOutcome.DENY]) becomes a [Policy.Decision.Refuse] (the bot may not make it; the user raises the max here).
 */
data class BotPolicy(
    val botId: String,
    val autonomy: Autonomy? = null,
    /** Minor units; null = the bot's `Permissions.spendCapMonthlyMinor` (0 by default). */
    val monthlyCapMinor: Long? = null,
    /** Minor units; null = no per-purchase max. */
    val perTxMaxMinor: Long? = null,
    /** ISO 4217; null = the bot's `Permissions.currency`. */
    val currency: String? = null,
    val allowedMerchants: Set<String> = emptySet(),
    val sendScope: SendScope? = null,
) {
    val isEmpty: Boolean get() = autonomy == null && monthlyCapMinor == null && perTxMaxMinor == null && currency == null &&
        allowedMerchants.isEmpty() && sendScope == null
}

/** Everything the page stores besides the [PolicyStore] rows. */
data class PermissionSettings(val bots: Map<String, BotPolicy> = emptyMap()) {
    fun of(botId: String): BotPolicy = bots[botId] ?: BotPolicy(botId)
    fun with(p: BotPolicy): PermissionSettings = if (p.isEmpty) copy(bots = bots - p.botId) else copy(bots = bots + (p.botId to p))
}

/** What a SPEND call is about (from the tool's arguments / checkout page). */
data class SpendFacts(val amountMinor: Long, val currency: String, val merchant: String? = null)

/** What a SEND call is about. [allKnown] = the user has messaged every recipient before; [public] = a post anyone can see. */
data class SendFacts(val recipients: Int, val cc: Int = 0, val public: Boolean = false, val allKnown: Boolean = false)

/** One standing per-tool rule as the page lists it. [applies] = Policy honours it (NEVER / ASK always; ALLOW only for pre-approvable classes). */
data class StandingRow(val tool: String, val botId: String, val rule: Rule, val cls: ActionClass, val applies: Boolean,
                       val setBy: String, val at: Long) {
    val anyBot: Boolean get() = botId == PolicyStore.ANY_BOT
}

object PermissionsModel {

    /** Autonomy choices in page order, with the line under each. */
    val AUTONOMY: List<Pair<Autonomy, String>> = listOf(
        Autonomy.ASSIST to "Asks before doing anything beyond reading.",
        Autonomy.ACT to "Does safe, local work; asks before changing things elsewhere.",
        Autonomy.AUTOPILOT to "Gets on with it, with Undo where it can. The exceptions below still ask.",
    )

    val SEND_SCOPES: List<Pair<SendScope, String>> = listOf(
        SendScope.NONE to "Never sends anything.",
        SendScope.DRAFT_ONLY to "Shows every message as a draft for you to send.",
        SendScope.KNOWN_CONTACTS to "Informal replies to people you've messaged before, one recipient, no CC, go without a card.",
        SendScope.ANY to "Private messages to anyone go without a card. Public posts still ask.",
    )

    fun autonomyName(a: Autonomy): String = when (a) { Autonomy.ASSIST -> "Assist"; Autonomy.ACT -> "Act"; Autonomy.AUTOPILOT -> "Autopilot" }

    fun sendScopeName(s: SendScope): String = when (s) {
        SendScope.NONE -> "Never"; SendScope.DRAFT_ONLY -> "Drafts only"; SendScope.KNOWN_CONTACTS -> "People I know"; SendScope.ANY -> "Anyone"
    }

    // ---------------------------------------------------------------- applying settings to a bot

    /** [s] with the user's settings for it: autonomy, allowance, currency, send scope (NONE → SEND is NEVER). */
    fun apply(s: Policy.Subject, settings: PermissionSettings): Policy.Subject {
        val p = settings.of(s.id)
        if (p.isEmpty) return s
        val perm = s.permissions
        val scope = p.sendScope ?: perm.sendScope
        val actions = if (scope == SendScope.NONE) perm.actions + (ActionClass.SEND to Rule.NEVER) else perm.actions
        return s.copy(permissions = perm.copy(
            autonomy = p.autonomy ?: perm.autonomy,
            spendCapMonthlyMinor = (p.monthlyCapMinor ?: perm.spendCapMonthlyMinor).coerceAtLeast(0),
            currency = p.currency ?: perm.currency,
            sendScope = scope,
            actions = actions))
    }

    /** SpendGuard's settings for an (applied) bot. */
    fun spendSettings(s: Policy.Subject, settings: PermissionSettings, zone: String = ZoneId.systemDefault().id): SpendSettings {
        val p = settings.of(s.id)
        return SpendSettings(monthlyCapMinor = s.permissions.spendCapMonthlyMinor.coerceAtLeast(0), perTxMaxMinor = p.perTxMaxMinor,
            currency = s.permissions.currency, allowedMerchants = p.allowedMerchants, zone = zone)
    }

    /** The lowest send scope along a chain (NONE < DRAFT_ONLY < KNOWN_CONTACTS < ANY). */
    fun chainSendScope(chain: List<Policy.Subject>): SendScope = chain.minByOrNull { it.permissions.sendScope.ordinal }!!.permissions.sendScope

    /** 05 §7.4: may this message go without a draft card under [scope]? Never for public posts. */
    fun sendAllowed(scope: SendScope, f: SendFacts): Boolean = when {
        f.public || f.recipients < 1 -> false
        scope == SendScope.KNOWN_CONTACTS -> f.recipients == 1 && f.cc == 0 && f.allKnown
        scope == SendScope.ANY -> true
        else -> false
    }

    /**
     * SpendGuard for a chain (actor first). Each bot's own allowance with its own month's spending, then the merged
     * lowest settings with the actor's spending; the first verdict that isn't ALLOW is returned (DENY before ASK).
     * [spent] = what a bot spent this month in a currency (AuditDao.monthSpent / SpendLedger.monthSpent).
     */
    fun spendVerdict(chain: List<Policy.Subject>, f: SpendFacts, settings: PermissionSettings, spent: (botId: String, currency: String) -> Long,
                     zone: String = ZoneId.systemDefault().id): SpendVerdict {
        require(chain.isNotEmpty()) { "empty chain" }
        val each = chain.map { s ->
            val ss = spendSettings(s, settings, zone)
            SpendGuard.check(f.amountMinor, f.currency, spent(s.id, ss.currency), ss, f.merchant)
        }
        val lowest = SpendGuard.lowest(chain.map { spendSettings(it, settings, zone) })
        val merged = SpendGuard.check(f.amountMinor, f.currency, spent(chain.first().id, lowest.currency), lowest, f.merchant)
        val all = each + merged
        return all.firstOrNull { it.outcome == SpendOutcome.DENY } ?: all.firstOrNull { it.outcome == SpendOutcome.ASK } ?: merged
    }

    /** The bot, its chain and the Ctx flags as Policy should see them. */
    data class Prepared(val bot: Policy.Subject, val ctx: Policy.Ctx, val spend: SpendVerdict? = null, val sendScope: SendScope)

    fun prepare(bot: Policy.Subject, ctx: Policy.Ctx, settings: PermissionSettings, spend: SpendFacts? = null, send: SendFacts? = null,
                spent: (botId: String, currency: String) -> Long = { _, _ -> 0L }, zone: String = ZoneId.systemDefault().id): Prepared {
        val b = apply(bot, settings)
        val parents = ctx.chain.map { apply(it, settings) }
        val chain = listOf(b) + parents
        val verdict = spend?.let { spendVerdict(chain, it, settings, spent, zone) }
        val scope = chainSendScope(chain)
        return Prepared(b, ctx.copy(chain = parents,
            spendPreApproved = verdict?.outcome == SpendOutcome.ALLOW,
            sendPreApproved = send != null && sendAllowed(scope, send)), verdict, scope)
    }

    /** [Policy.decide] with the page's settings applied (the one call SF-02's cards / ToolRouter should use). */
    fun decide(bot: Policy.Subject, call: Policy.Call, ctx: Policy.Ctx, settings: PermissionSettings, spend: SpendFacts? = null,
               send: SendFacts? = null, spent: (botId: String, currency: String) -> Long = { _, _ -> 0L },
               zone: String = ZoneId.systemDefault().id): Policy.Decision {
        val p = prepare(bot, ctx, settings, spend, send, spent, zone)
        val d = Policy.decide(p.bot, call, p.ctx)
        val v = p.spend
        if (d.cls == ActionClass.SPEND && v != null && v.outcome == SpendOutcome.DENY && d !is Policy.Decision.Refuse)
            return Policy.Decision.Refuse("Refused: ${v.message} Don't retry it; tell the user.", d.cls, Policy.Step.NEVER)
        return d
    }

    // ---------------------------------------------------------------- standing rules

    /** Every standing rule, bots in [order] first (then the rest, `*` last), tools alphabetically. */
    fun standing(store: PolicyStore, order: List<String> = emptyList()): List<StandingRow> = store.all().map { e ->
        val cls = ActionClasses.base(e.tool)
        StandingRow(e.tool, e.botId, e.rule, cls, applies = e.rule != Rule.ALLOW || cls in Policy.PRE_APPROVABLE, setBy = e.setBy, at = e.at)
    }.sortedWith(compareBy<StandingRow>({ r -> if (r.anyBot) Int.MAX_VALUE else order.indexOf(r.botId).let { if (it < 0) order.size else it } },
        { it.botId }, { it.tool }))

    /** "Ask again": the bot (or `*`) loses its standing rule for the tool; the next call shows a card. */
    fun askAgain(store: PolicyStore, row: StandingRow) = store.clear(row.tool, row.botId)

    /** "Ask again" for every rule one bot has (the page's "Clear all"). Returns how many went. */
    fun askAgainAll(store: PolicyStore, botId: String): Int {
        val rows = store.all().filter { it.botId == botId }
        rows.forEach { store.clear(it.tool, it.botId) }
        return rows.size
    }

    fun ruleLabel(r: Rule): String = when (r) { Rule.ALLOW -> "Always allow"; Rule.ASK -> "Always ask"; Rule.NEVER -> "Never" }

    // ---------------------------------------------------------------- Autopilot exceptions

    /** What still always asks for [bot] even on Autopilot (05 §7.1/§7.2), in plain words, with its allowance and scope. */
    fun autopilotExceptions(bot: Policy.Subject, spend: SpendSettings): List<String> {
        val cur = spend.currency
        val money = when {
            spend.monthlyCapMinor <= 0 -> "Spending money: every purchase asks (no monthly allowance set)."
            else -> "Spending money above what's left of ${SpendGuard.money(spend.monthlyCapMinor, cur)} a month" +
                (spend.perTxMaxMinor?.let { "; anything over ${SpendGuard.money(it, cur)} at once is refused" } ?: "") +
                (if (spend.allowedMerchants.isNotEmpty()) "; other shops than ${spend.allowedMerchants.sorted().joinToString(", ")} ask" else "") + "."
        }
        val send = when (bot.permissions.sendScope) {
            SendScope.NONE -> "Sending messages: never allowed for this bot."
            SendScope.DRAFT_ONLY -> "Sending messages: every message is a draft you send."
            SendScope.KNOWN_CONTACTS -> "Sending messages to more than one person, with CC, to someone new, or in public."
            SendScope.ANY -> "Public posts, and any message when you didn't start the work."
        }
        return listOf(
            "Deleting anything: files, mail, events, bots, bookings.",
            money,
            send,
            "Signing in or using a saved password, card or key.",
            "Paid computers and paid model lanes (asks once per session).",
            "Anything that changes things outside the phone after reading a web page, email or message you didn't write.",
            "Spending, sending or deleting when a routine, check-in, suggestion or another bot started it.",
            "Tools you set to Always ask.",
        )
    }

    // ---------------------------------------------------------------- money input

    /** "12", "12.5", "1,250.00", "ZMW 12.50" → minor units; null for anything else (or negative). */
    fun parseMoney(text: String): Long? {
        val t = text.trim().replace(",", "").replace(Regex("^[A-Za-z]{3}\\s*"), "").trim()
        if (t.isEmpty()) return null
        val m = Regex("^(\\d{1,12})(?:\\.(\\d{1,2}))?$").matchEntire(t) ?: return null
        val whole = m.groupValues[1].toLong()
        val frac = m.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
        return whole * 100 + frac
    }

    /** Minor units → "12.50" for an input field (no currency). */
    fun moneyField(minor: Long): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"
}

/**
 * Text form of [PermissionSettings] for one SharedPreferences string (pure, tested). Line 1 `v1`, then one line per
 * bot: botId, autonomy, cap, per-tx max, currency, merchants (`,`-joined), send scope — tab-separated, `-` = null,
 * every field %-escaped. Unknown enum values and broken lines are skipped (never crash on a bad pref).
 */
object PermissionsCodec {
    private const val V = "v1"

    fun encode(s: PermissionSettings): String = buildString {
        append(V)
        for (p in s.bots.values.sortedBy { it.botId }) {
            if (p.isEmpty) continue
            append('\n')
            append(listOf(esc(p.botId), p.autonomy?.name ?: "-", p.monthlyCapMinor?.toString() ?: "-", p.perTxMaxMinor?.toString() ?: "-",
                p.currency?.let(::esc) ?: "-", p.allowedMerchants.sorted().joinToString(",") { esc(it) }.ifEmpty { "-" },
                p.sendScope?.name ?: "-").joinToString("\t"))
        }
    }

    fun decode(text: String?): PermissionSettings {
        if (text.isNullOrBlank()) return PermissionSettings()
        val lines = text.split('\n')
        if (lines.first().trim() != V) return PermissionSettings()
        val bots = LinkedHashMap<String, BotPolicy>()
        for (line in lines.drop(1)) {
            val f = line.split('\t')
            if (f.size != 7) continue
            val id = unesc(f[0]).trim()
            if (id.isEmpty()) continue
            val p = runCatching {
                BotPolicy(id,
                    autonomy = f[1].takeIf { it != "-" }?.let { a -> Autonomy.values().firstOrNull { it.name == a } },
                    monthlyCapMinor = f[2].takeIf { it != "-" }?.toLong()?.coerceAtLeast(0),
                    perTxMaxMinor = f[3].takeIf { it != "-" }?.toLong()?.coerceAtLeast(0),
                    currency = f[4].takeIf { it != "-" }?.let(::unesc),
                    allowedMerchants = if (f[5] == "-") emptySet() else f[5].split(',').map(::unesc).filter { it.isNotBlank() }.toSet(),
                    sendScope = f[6].takeIf { it != "-" }?.let { s -> SendScope.values().firstOrNull { it.name == s } })
            }.getOrNull() ?: continue
            if (!p.isEmpty) bots[id] = p
        }
        return PermissionSettings(bots)
    }

    internal fun esc(s: String): String = buildString {
        for (ch in s) when (ch) { '%', '\t', '\n', '\r', ',' -> append('%').append(ch.code.toString(16).padStart(2, '0')); else -> append(ch) }
    }
    internal fun unesc(s: String): String = Regex("%([0-9a-fA-F]{2})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
}

/**
 * A [PolicyStore] kept as one text blob ([load] / [save]): SharedPreferences on the phone until P0's team.db exists
 * (then [SqlPolicyStore]; [migrateTo] copies the rows across once). Line format: `v1`, then tool, botId, rule, setBy,
 * at (tab-separated, %-escaped). Thread-safe; loads lazily; every change saves the whole (small) table.
 */
class TextPolicyStore(private val load: () -> String?, private val save: (String) -> Unit) : PolicyStore {
    /** Read on first use (not at construction), so installing the store at app start does no disk work. */
    private val rows: LinkedHashMap<Pair<String, String>, PolicyStore.Entry> by lazy {
        LinkedHashMap<Pair<String, String>, PolicyStore.Entry>().also { m ->
            decode(runCatching { load() }.getOrNull()).forEach { m[it.tool to it.botId] = it }
        }
    }

    @Synchronized override fun rule(tool: String, botId: String): Rule? = (rows[tool to botId] ?: rows[tool to PolicyStore.ANY_BOT])?.rule
    @Synchronized override fun set(tool: String, botId: String, rule: Rule, setBy: String, at: Long) {
        rows[tool to botId] = PolicyStore.Entry(tool, botId, rule, setBy, at); flush()
    }
    @Synchronized override fun clear(tool: String, botId: String) { if (rows.remove(tool to botId) != null) flush() }
    @Synchronized override fun all(): List<PolicyStore.Entry> = rows.values.toList()

    /** Copies every row into [other] (team.db once it lands). */
    fun migrateTo(other: PolicyStore) = all().forEach { other.set(it.tool, it.botId, it.rule, it.setBy, it.at) }

    private fun flush() = save(encode(rows.values))

    companion object {
        fun encode(rows: Collection<PolicyStore.Entry>): String = buildString {
            append("v1")
            rows.forEach { e ->
                append('\n').append(listOf(PermissionsCodec.esc(e.tool), PermissionsCodec.esc(e.botId), e.rule.name,
                    PermissionsCodec.esc(e.setBy), e.at.toString()).joinToString("\t"))
            }
        }

        fun decode(text: String?): List<PolicyStore.Entry> {
            if (text.isNullOrBlank()) return emptyList()
            val lines = text.split('\n')
            if (lines.first().trim() != "v1") return emptyList()
            return lines.drop(1).mapNotNull { line ->
                val f = line.split('\t')
                if (f.size != 5) return@mapNotNull null
                val rule = Rule.values().firstOrNull { it.name == f[2] } ?: return@mapNotNull null
                val tool = PermissionsCodec.unesc(f[0]).ifBlank { return@mapNotNull null }
                PolicyStore.Entry(tool, PermissionsCodec.unesc(f[1]).ifBlank { PolicyStore.ANY_BOT }, rule, PermissionsCodec.unesc(f[3]),
                    f[4].toLongOrNull() ?: 0L)
            }
        }
    }
}
