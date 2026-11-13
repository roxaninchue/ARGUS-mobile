package com.blaze.assistant.policy

import com.blaze.assistant.agent.CommandPolicy
import com.blaze.assistant.agent.ConfirmGate
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.ActionClass.CREDENTIAL
import com.blaze.assistant.team.ActionClass.DELETE
import com.blaze.assistant.team.ActionClass.READ
import com.blaze.assistant.team.ActionClass.SEND
import com.blaze.assistant.team.ActionClass.SPEND
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.DEFAULT_RULES
import com.blaze.assistant.team.Permissions
import com.blaze.assistant.team.Rule
import com.blaze.assistant.team.SendScope

/**
 * P2-SF-01: the one policy engine (05 §7.1). `Policy.decide(call, ctx)` merges, in this order:
 *
 *  1. refusals: NEVER (the bot's rule for the class, a tool grant, a standing policy), an MCP tool set to Off, SEND with
 *     send scope NONE;
 *  2. Plan mode (Modes) and a bot on Assist: anything but a read is refused at dispatch;
 *  3. a tool that is itself a card (ask_user, request_secret) runs: its card is the approval;
 *  4. SPEND, DELETE, CREDENTIAL and COMPUTE_PAID always show an approval card; SEND shows a draft card unless the user's standing
 *     rule covers it (user-started, untainted, send scope KNOWN_CONTACTS or ANY);
 *  5. A-team's proactive rule: started by a routine, suggestion or another bot, not confirmed, and the task text matches
 *     A-team's RISKY regex (money / send / delete) → card for anything beyond reads and local writes;
 *  6. ConfirmGate's taint: a turn that read untrusted content asks for anything beyond reads and local writes, with
 *     "Requested after reading …" on the card;
 *  7. CommandPolicy: a RISKY command asks (no allowance covers it);
 *  8. Mcp.kt Ask / Always / Off (Always runs, Ask asks, the default follows McpConfig.needsApproval);
 *  9. standing policy (policies table: tool × bot, "Always allow"), a bot's per-tool grant, a command allowance → allow;
 * 10. the bot's rule for the class (DEFAULT_RULES: reads, local writes and phone taps allowed) → allow;
 * 11. autonomy AUTOPILOT → allow (with Undo when the tool can be undone);
 * 12. else an approval card.
 *
 * A request that came through other bots (bot → bot) is decided on the LOWEST permission of every bot in the chain
 * ([effective]); standing allows only cover direct requests. Pure Kotlin; PolicyTest has the decision table.
 */
object Policy {
    /** Who started the work this call belongs to. */
    enum class Trigger { USER, ROUTINE, SUGGESTION, BOT, REMOTE, SYSTEM;
        val proactive get() = this != USER
        companion object {
            /** From a job's `source` (user | manager | routine | bot:<id> | mcp | suggestion | a-team …). */
            fun ofSource(source: String?): Trigger = when (source?.substringBefore(':')?.trim()?.lowercase()) {
                null, "", "user", "a-team" -> USER
                "routine", "checkin", "check-in" -> ROUTINE
                "suggestion", "proactive" -> SUGGESTION
                "bot", "manager" -> BOT
                "mcp", "remote", "api" -> REMOTE
                else -> SYSTEM
            }
        }
    }

    /** One tool call: its name, class, and whether it can be undone (the Autopilot Undo bar). */
    data class Call(val tool: String, val cls: ActionClass, val selfCard: Boolean = false, val undoable: Boolean = false)

    /** The MCP side: the tool's Ask / Always / Off override ("" = default) and whether McpConfig says it needs a tap. */
    data class Mcp(val mode: String, val needsApproval: Boolean)

    /**
     * Everything the decision reads. [perms] = the acting bot's permissions; [chain] = the permissions of the bots the
     * request came through (requester first), empty for a direct request. [standing] = the policies-table rule for
     * (tool, bot) or (tool, *); [grant] = the bot's per-tool grant (Bot.toolGrants / BotAppGrant). [tainted] = the
     * untrusted source this turn read (ConfirmGate.Taint), null when clean. [command] = CommandPolicy level for shell /
     * git, with [commandAllowed] when an allowance ("Allow for this chat" / "Always allow this command") covers it.
     */
    data class Ctx(
        val botId: String = "manager",
        val perms: Permissions = Permissions(),
        val chain: List<Permissions> = emptyList(),
        val trigger: Trigger = Trigger.USER,
        val triggerText: String = "",
        val confirmed: Boolean = true,
        val plan: Boolean = false,
        val tainted: String? = null,
        val standing: Rule? = null,
        val grant: Rule? = null,
        val mcp: Mcp? = null,
        val command: CommandPolicy.Level? = null,
        val commandAllowed: Boolean = false,
    )

    enum class Reason {
        NEVER, MCP_OFF, SEND_SCOPE, PLAN, ASSIST, SELF_CARD, SPEND, DELETE, CREDENTIAL, COMPUTE_PAID, SEND, PROACTIVE, TAINTED,
        RISKY_COMMAND, MCP_ASK, MCP_ALWAYS, STANDING, GRANT, COMMAND_ALLOWED, RULE, AUTOPILOT, ASK
    }

    enum class Card { APPROVAL, DRAFT }

    sealed class Decision {
        abstract val reason: Reason
        data class Allow(override val reason: Reason, val undo: Boolean = false) : Decision()
        /** [always] = the card may offer "Always allow"; [note] = the line on top ("Requested after reading …"). */
        data class Ask(override val reason: Reason, val card: Card = Card.APPROVAL, val always: Boolean = false, val note: String? = null) : Decision()
        data class Refuse(override val reason: Reason, val message: String) : Decision()
    }

    // ---------------- A-team RISKY (manager.js v2.18, verbatim) ----------------

    val RISKY: Map<String, Regex> = linkedMapOf(
        "money" to Regex("""\b(buy|purchase|pay|payment|order|checkout|subscribe|subscription|donate|transfer|send money|book (a|the)? ?(flight|hotel|ticket|table|room)|top ?up|withdraw|invest|bet)\b""", RegexOption.IGNORE_CASE),
        "send" to Regex("""\b(send|email|e-mail|text|sms|whatsapp|message|dm|tweet|post|reply to|forward|publish|invite)\b""", RegexOption.IGNORE_CASE),
        "delete" to Regex("""\b(delete|remove|erase|wipe|drop|cancel|unsubscribe|uninstall|rm -rf|destroy)\b""", RegexOption.IGNORE_CASE),
    )

    /** A-team `risky(text)`: which of money / send / delete the free text of a task or routine asks for. */
    fun risky(text: String): List<String> = RISKY.filter { it.value.containsMatchIn(text) }.keys.toList()

    // ---------------- lowest permission in a chain ----------------

    private fun strict(a: Rule, b: Rule): Rule = if (a.ordinal >= b.ordinal) a else b   // ALLOW < ASK < NEVER

    /** The permissions a call runs under: the strictest of the acting bot's and every bot it came through. */
    fun effective(own: Permissions, chain: List<Permissions>): Permissions {
        if (chain.isEmpty()) return own
        val all = listOf(own) + chain
        val rules = ActionClass.values().associateWith { c -> all.map { it.actions[c] ?: DEFAULT_RULES[c] ?: Rule.ASK }.reduce(::strict) }
        return own.copy(
            autonomy = all.minByOrNull { it.autonomy.ordinal }!!.autonomy,
            actions = rules,
            spendCapMonthlyMinor = all.minOf { it.spendCapMonthlyMinor },
            sendScope = all.minByOrNull { it.sendScope.ordinal }!!.sendScope,
            maxHops = all.minOf { it.maxHops },
        )
    }

    private fun Permissions.rule(c: ActionClass): Rule = actions[c] ?: DEFAULT_RULES[c] ?: Rule.ASK

    // ---------------- the decision ----------------

    const val DONT_RETRY = "Don't retry it."

    fun decide(call: Call, x: Ctx): Decision {
        val c = call.cls
        val eff = effective(x.perms, x.chain)
        val name = call.tool
        val direct = x.chain.isEmpty()

        // 1. refusals
        if (x.mcp?.mode == MCP_OFF) return Decision.Refuse(Reason.MCP_OFF, "Refused: $name is turned off for this app. $DONT_RETRY")
        if (x.standing == Rule.NEVER || x.grant == Rule.NEVER || eff.rule(c) == Rule.NEVER)
            return Decision.Refuse(Reason.NEVER, "Refused: this bot isn't allowed to ${verb(c)} ($name). $DONT_RETRY Tell the user what you would have done.")
        if (c == SEND && eff.sendScope == SendScope.NONE)
            return Decision.Refuse(Reason.SEND_SCOPE, "Refused: this bot may not send anything in the user's name ($name). $DONT_RETRY Give the user the text instead.")

        // 2. Plan mode, Assist
        if (c != READ && x.plan) return Decision.Refuse(Reason.PLAN, "Refused: PLAN mode is read and look only, and $name can change things. $DONT_RETRY Present the plan instead.")
        if (c != READ && eff.autonomy == Autonomy.ASSIST) return Decision.Refuse(Reason.ASSIST, "Refused: this bot is on Assist (read only), and $name can change things. $DONT_RETRY Suggest the step to the user.")

        // 3. the tool is its own card
        if (call.selfCard) return Decision.Allow(Reason.SELF_CARD)

        val note = x.tainted?.let { "Requested after reading $it." }

        // 4. always-card classes
        when (c) {
            SPEND -> return Decision.Ask(Reason.SPEND, note = note)
            DELETE -> return Decision.Ask(Reason.DELETE, note = note)
            CREDENTIAL -> return Decision.Ask(Reason.CREDENTIAL, note = note)
            ActionClass.COMPUTE_PAID -> return Decision.Ask(Reason.COMPUTE_PAID, note = note)   // asks each session (P7 adds the session)
            SEND -> return if (direct && !x.trigger.proactive && x.tainted == null && x.standing == Rule.ALLOW &&
                eff.sendScope.ordinal >= SendScope.KNOWN_CONTACTS.ordinal) Decision.Allow(Reason.STANDING)
                else Decision.Ask(Reason.SEND, Card.DRAFT, note = note)
            else -> {}
        }

        // 5. proactive work the user hasn't confirmed, whose task reads as money / send / delete
        if (x.trigger.proactive && !x.confirmed && risky(x.triggerText).isNotEmpty() && !ActionClasses.quiet(c))
            return Decision.Ask(Reason.PROACTIVE, note = "Started by ${who(x.trigger)}, not by you" + (note?.let { ". $it" } ?: "."))

        // 6. taint
        if (x.tainted != null && ConfirmGate.taintAsks(c)) return Decision.Ask(Reason.TAINTED, note = note)

        // 7. risky commands
        if (x.command == CommandPolicy.Level.RISKY) return Decision.Ask(Reason.RISKY_COMMAND, note = note)

        val alwaysOk = direct && !x.trigger.proactive && ActionClasses.preApprovable(c)

        // 8. MCP Ask / Always
        x.mcp?.let { m ->
            if (m.mode == MCP_ALWAYS) return Decision.Allow(Reason.MCP_ALWAYS)
            if (m.mode == MCP_ASK || m.needsApproval) return Decision.Ask(Reason.MCP_ASK, always = alwaysOk)
        }

        // 9. standing allows (direct requests only)
        if (direct && x.standing == Rule.ALLOW && ActionClasses.preApprovable(c)) return Decision.Allow(Reason.STANDING)
        if (direct && x.grant == Rule.ALLOW && ActionClasses.preApprovable(c)) return Decision.Allow(Reason.GRANT)
        if (x.command == CommandPolicy.Level.ASK && x.commandAllowed && x.tainted == null) return Decision.Allow(Reason.COMMAND_ALLOWED)   // CommandPolicy.decide: no allowances after outside content

        // 10. the class's rule (a command that needs a look is not covered by "local writes are fine")
        if (eff.rule(c) == Rule.ALLOW && x.command != CommandPolicy.Level.ASK) return Decision.Allow(Reason.RULE)

        // 11. Autopilot
        if (eff.autonomy == Autonomy.AUTOPILOT) return Decision.Allow(Reason.AUTOPILOT, undo = call.undoable)

        // 12.
        return Decision.Ask(Reason.ASK, always = alwaysOk)
    }

    const val MCP_ASK = "ask"
    const val MCP_ALWAYS = "always"
    const val MCP_OFF = "off"

    private fun who(t: Trigger) = when (t) {
        Trigger.ROUTINE -> "a routine"; Trigger.SUGGESTION -> "a suggestion"; Trigger.BOT -> "another bot"
        Trigger.REMOTE -> "another app"; Trigger.SYSTEM -> "ARGUS itself"; Trigger.USER -> "you"
    }

    /** A plain verb for a refusal line. */
    fun verb(c: ActionClass): String = when (c) {
        ActionClass.READ -> "read this"; ActionClass.WRITE_LOCAL -> "save or change things on the phone"
        ActionClass.WRITE_REMOTE -> "change things in the user's accounts"; ActionClass.SEND -> "send messages in the user's name"
        ActionClass.SPEND -> "spend money"; ActionClass.DELETE -> "delete things"; ActionClass.SETTINGS -> "change settings or install apps"
        ActionClass.CREDENTIAL -> "use sign-ins or secrets"; ActionClass.COMPUTE_PAID -> "start paid computers"
        ActionClass.PHYSICAL -> "use the phone's screen"
    }
}
