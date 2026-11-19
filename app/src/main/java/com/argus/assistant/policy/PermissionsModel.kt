package com.blaze.assistant.policy

import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.Permissions
import com.blaze.assistant.team.Rule
import com.blaze.assistant.team.SendScope

/**
 * P2-SF-04 (BH-25, NEW-09): what Settings › Privacy › Approvals & permissions shows, pure so PermissionsTest runs it on
 * the JVM. The page ([com.blaze.assistant.ui.argus.settings.PermissionsPage]) only draws it; the glue reads the policies
 * table (PolicyStore.all) and the bots (TeamStore) and writes back through PolicyStore.forget / TeamStore.update.
 *
 *  - standing allows ("Always allow" on a card): one row per tool × bot from the policies table, grouped by bot
 *    ("*" = every bot first, then the Manager, then by name), each with "Ask again" ([revoke] = delete the row);
 *  - per bot: autonomy (Assist / Act / Autopilot), send scope, SpendGuard monthly cap + per-payment maximum;
 *  - what still asks on Autopilot, read from [Policy.AUTOPILOT_ALWAYS_ASK] (decide's own answer), so it can't drift.
 */
object PermissionsModel {
    const val EVERY_BOT = "*"

    /** One policies-table row. */
    data class Standing(val tool: String, val botId: String, val rule: Rule)

    data class BotAllows(val botId: String, val botName: String, val rows: List<Standing>)

    /** Rows of [all] (PolicyStore.all) grouped by bot then tool. Only ALLOW rows are standing allows; NEVER rows stay out. */
    fun standing(all: List<Triple<String, String, Rule>>, nameOf: (String) -> String, managerId: String = "manager"): List<BotAllows> =
        all.map { Standing(it.first, it.second, it.third) }.filter { it.rule == Rule.ALLOW }
            .groupBy { it.botId }
            .map { (bot, rows) -> BotAllows(bot, if (bot == EVERY_BOT) "Every bot" else nameOf(bot), rows.sortedBy { it.tool }) }
            .sortedWith(compareBy<BotAllows>({ it.botId != EVERY_BOT }, { it.botId != managerId }, { it.botName.lowercase() }, { it.botId }))

    /** "Ask again": the row is gone, so the next call shows its card again (PolicyStore.forget does the same to team.db). */
    fun revoke(all: List<Triple<String, String, Rule>>, tool: String, botId: String): List<Triple<String, String, Rule>> =
        all.filterNot { it.first == tool && it.second == botId }

    /** A tool name as a row label: "gmail_send" → "Gmail send"; MCP "mcp__server__tool" → "server: tool". */
    fun toolLabel(tool: String): String {
        val parts = tool.removePrefix("mcp__").removePrefix("mcp_").split("__")
        val t = if (ToolRegistry.isMcp(tool) && parts.size >= 2) "${parts[0]}: ${parts.drop(1).joinToString(" ")}" else tool
        return t.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
    }

    // ---------------- per bot ----------------

    data class Choice<T>(val value: T, val label: String, val sub: String)

    val AUTONOMY = listOf(
        Choice(Autonomy.ASSIST, "Assist", "Reads and suggests; changes nothing"),
        Choice(Autonomy.ACT, "Act", "Does the safe steps, asks for the rest"),
        Choice(Autonomy.AUTOPILOT, "Autopilot", "Does everything except what always asks"),
    )

    val SEND_SCOPE = listOf(
        Choice(SendScope.NONE, "Never", "Sends nothing in your name"),
        Choice(SendScope.DRAFT_ONLY, "Drafts", "Writes it; you send it"),
        Choice(SendScope.KNOWN_CONTACTS, "Contacts", "May send to people you know, when you've allowed it"),
        Choice(SendScope.ANY, "Anyone", "May send to anyone, when you've allowed it"),
    )

    data class BotPerms(val botId: String, val botName: String, val autonomy: Autonomy, val sendScope: SendScope,
                        val capMonthlyMinor: Long, val perTxMaxMinor: Long, val currency: String, val spentThisMonthMinor: Long = 0)

    fun of(botId: String, name: String, p: Permissions, spent: Long = 0) =
        BotPerms(botId, name, p.autonomy, p.sendScope, p.spendCapMonthlyMinor, p.spendPerTxMaxMinor, p.currency, spent)

    /** Writes the page's choices back onto the bot's permissions (everything else unchanged). Negative amounts → 0. */
    fun apply(p: Permissions, b: BotPerms): Permissions = p.copy(autonomy = b.autonomy, sendScope = b.sendScope,
        spendCapMonthlyMinor = b.capMonthlyMinor.coerceAtLeast(0), spendPerTxMaxMinor = b.perTxMaxMinor.coerceAtLeast(0))

    /** "12.50" → 1250 minor units; blank → 0; nonsense → null (the field keeps its value). */
    fun parseMinor(text: String): Long? {
        val t = text.trim().replace(",", "")
        if (t.isEmpty()) return 0
        val m = Regex("""^(\d{1,12})(?:\.(\d{1,2}))?$""").matchEntire(t) ?: return null
        return m.groupValues[1].toLong() * 100 + m.groupValues[2].padEnd(2, '0').toLong()
    }

    /** The cap line: "No budget: every payment asks" for 0. */
    fun capLine(b: BotPerms): String = if (b.capMonthlyMinor <= 0) "No budget: every payment asks"
        else "${SpendGuard.money(b.spentThisMonthMinor, b.currency)} of ${SpendGuard.money(b.capMonthlyMinor, b.currency)} this month"

    // ---------------- what still asks on Autopilot ----------------

    data class AlwaysAsks(val cls: ActionClass?, val label: String)

    private fun classLabel(c: ActionClass): String = when (c) {
        ActionClass.SPEND -> "Paying or spending money (every payment, inside the cap or not)"
        ActionClass.DELETE -> "Deleting things"
        ActionClass.CREDENTIAL -> "Using sign-ins, passwords or secrets"
        ActionClass.COMPUTE_PAID -> "Starting paid computers"
        ActionClass.SEND -> "Sending in your name (shown as a draft unless you allowed it)"
        else -> "Anything that would " + Policy.verb(c)
    }

    /** What still asks even on Autopilot: the classes decide() never auto-allows, then the situations that always ask. */
    fun autopilotExceptions(): List<AlwaysAsks> = Policy.AUTOPILOT_ALWAYS_ASK.map { AlwaysAsks(it, classLabel(it)) } + listOf(
        AlwaysAsks(null, "Anything asked for after reading a web page, an email or another outside source"),
        AlwaysAsks(null, "Money, messages or deletions a routine, suggestion or another bot started"),
        AlwaysAsks(null, "Risky phone or computer commands"),
    )
}
