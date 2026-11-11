package com.blaze.assistant.policy

import com.blaze.assistant.agent.ConfirmGate
import com.blaze.assistant.connect.McpConfig
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.ActionClass.COMPUTE_PAID
import com.blaze.assistant.team.ActionClass.CREDENTIAL
import com.blaze.assistant.team.ActionClass.DELETE
import com.blaze.assistant.team.ActionClass.PHYSICAL
import com.blaze.assistant.team.ActionClass.READ
import com.blaze.assistant.team.ActionClass.SEND
import com.blaze.assistant.team.ActionClass.SETTINGS
import com.blaze.assistant.team.ActionClass.SPEND
import com.blaze.assistant.team.ActionClass.WRITE_LOCAL
import com.blaze.assistant.team.ActionClass.WRITE_REMOTE
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotAppGrant
import com.blaze.assistant.team.Permissions
import com.blaze.assistant.team.Rule

/**
 * # Policy — the one place that decides whether a tool call runs (P2-SF-01, 05 §7)
 *
 * Public API (SF-02 cards, SF-04 Permissions page, SF-05 audit and SF-06 build on exactly this):
 *
 *  - [decide] `(bot, call, ctx) → Decision`. Pure: no android.*, no I/O except reading [Ctx.store].
 *  - [Decision]: [Decision.Allow] · [Decision.AllowWithUndo] (Autopilot, undoable → 10 s Undo bar) ·
 *    [Decision.Card] (`kind` = [CardKind.APPROVAL] or [CardKind.DRAFT] for every SEND; `reason`; `canAlwaysAllow`) ·
 *    [Decision.Refuse] (`reason`, sent back to the model as the tool result). Every decision carries the call's
 *    [ActionClass] and the [Step] that fired, for the audit log.
 *  - [Call] / [Ctx] / [Subject] / [Trigger]: what is being called, by whom, in which situation.
 *  - [Approvals] (Approvals.kt): the card's state machine pending → allowed / always / denied / expired / skipped,
 *    25-minute expiry, a new user message skips, an app restart expires.
 *  - [PolicyStore] (PolicyStore.kt): standing per-tool rules (team.db `policies`); [remember] writes "Always allow".
 *  - [ActionClasses] (ActionClass.kt): the tool → class declaration.
 *
 * Decision order (05 §7.1, first match wins):
 *  1. NEVER anywhere in the chain (bot class rule, bot tool grant, app grant, standing policy) or MCP mode Off → Refuse.
 *  2. Plan mode and a non-read call → Refuse.
 *  (2a. the user already approved this exact call (batch card) → Allow.)
 *  (2b. A-team jobAllowed: a proactive job the user hasn't confirmed, non-read → Card "needs your OK (money…)".)
 *  3. destructive (DELETE) / SPEND / SEND → Card (SEND = DRAFT). SpendGuard / a send rule (SF-04, [Ctx.spendPreApproved],
 *     [Ctx.sendPreApproved]) may pass it only with the user present and an untainted turn: proactive SPEND/SEND/DELETE
 *     never run unconfirmed (05 §7.2).
 *  4. tainted turn and class ≥ WRITE_REMOTE → Card "Requested after reading …" (ConfirmGate).
 *  5. standing policy → Allow: READ always; class rule ALLOW (READ/WRITE_LOCAL/PHYSICAL by default), tool grant, app
 *     grant, store ALLOW, MCP Always, a command / session grant, a WRITE_REMOTE that only affects the user. Only for
 *     classes that can be pre-approved; CREDENTIAL never, COMPUTE_PAID per session only. Autonomy ASSIST asks for all
 *     non-read work.
 *  6. autonomy AUTOPILOT and non-destructive → Allow, or AllowWithUndo when [Ctx.undoable]. (A tool the user pinned to
 *     Ask — tool grant, app grant, policies row, or MCP mode Ask — keeps asking, like McpConfig.needsApproval.)
 *  7. else → Card.
 *
 * Bot chains (05 §7.2): [Ctx.chain] lists the bots above the caller; the LOWEST permission along the chain applies
 * (any NEVER refuses, the lowest autonomy wins, a standing allow must hold for every bot in it).
 */
object Policy {

    enum class CardKind { APPROVAL, DRAFT }

    /** Which rule decided (audit / tests). */
    enum class Step { NEVER, PLAN, APPROVED, UNCONFIRMED_JOB, ALWAYS_ASK, TAINT, READ, ASSIST, STANDING, AUTOPILOT, DEFAULT }

    sealed class Decision {
        abstract val cls: ActionClass
        abstract val step: Step
        data class Allow(override val cls: ActionClass, override val step: Step) : Decision()
        data class AllowWithUndo(override val cls: ActionClass, override val step: Step = Step.AUTOPILOT) : Decision()
        data class Card(val kind: CardKind, val reason: String, override val cls: ActionClass, override val step: Step,
                        /** "Always allow" may be offered (the class can be pre-approved and the turn isn't tainted). */
                        val canAlwaysAllow: Boolean = false,
                        /** ConfirmGate's taint source, when the card says "Requested after reading …". */
                        val taintSource: String? = null) : Decision()
        data class Refuse(val reason: String, override val cls: ActionClass, override val step: Step) : Decision()
        val allowed: Boolean get() = this is Allow || this is AllowWithUndo
    }

    /** Who started the work. Everything but [USER] is proactive / bot-initiated (05 §7.2). */
    enum class Trigger { USER, ROUTINE, CHECKIN, SUGGESTION, BOT, MCP;
        val proactive: Boolean get() = this != USER }

    /**
     * One tool call. [args] flattened to strings (ToolRouter.argsOf for a JSONObject). [cls]: the class the tool declares
     * itself (tools added after this table, e.g. P7's cloud desk); for a tool already in the table it can only raise it.
     */
    data class Call(val tool: String, val args: Map<String, String> = emptyMap(), val id: String = "",
                    val mcp: ActionClasses.McpInfo? = null, val cls: ActionClass? = null)

    /** The permission side of a bot (a [Bot], or the legacy single agent). */
    data class Subject(val id: String, val permissions: Permissions = Permissions(actions = CLASS_DEFAULTS),
                       val toolGrants: Map<String, Rule> = emptyMap(), val apps: List<BotAppGrant> = emptyList()) {
        companion object { fun of(bot: Bot) = Subject(bot.id, bot.permissions, bot.toolGrants, bot.apps) }
    }

    data class Ctx(
        val trigger: Trigger = Trigger.USER,
        /** Plan mode (Modes.plan, or a background agent started in Plan). */
        val plan: Boolean = false,
        /** ConfirmGate.Taint.source: what untrusted content this turn read (null = clean). */
        val taint: String? = null,
        /** Bots above the caller (parent first); their lowest permission applies. */
        val chain: List<Subject> = emptyList(),
        val store: PolicyStore? = null,
        /** UndoCenter can undo this call (Autopilot then gets the Undo bar). */
        val undoable: Boolean = false,
        /** A WRITE_REMOTE that affects only the user (a calendar event with no guests…). */
        val onlyUserAffected: Boolean = false,
        /** Tool names and CommandPolicy keys allowed for this chat / always (CommandPolicy.Grants, per-session COMPUTE_PAID). */
        val grants: Set<String> = emptySet(),
        /** The user already approved this exact call (e.g. on the step's batch card). */
        val approved: Boolean = false,
        /** The job this call belongs to was confirmed by the user (A-team `t.confirmed`); proactive jobs start false. */
        val jobConfirmed: Boolean = true,
        /** The job / routine / suggestion text (A-team `jobAllowed` checks it with RISKY). */
        val task: String = "",
        /** SF-04 SpendGuard: within the bot's cap and per-transaction max, allowed merchant. Default cap 0 → false. */
        val spendPreApproved: Boolean = false,
        /** SF-04: a standing "this recipient, this kind" send rule matches (never for public posts). */
        val sendPreApproved: Boolean = false,
    )

    /** 05 §7.1 defaults, used for any class a bot's permission map leaves out. */
    val CLASS_DEFAULTS: Map<ActionClass, Rule> = ActionClass.values().associateWith {
        if (it == READ || it == WRITE_LOCAL || it == PHYSICAL) Rule.ALLOW else Rule.ASK
    }

    /** Classes a standing rule / "Always allow" may cover (05 §7.1 "Can be pre-approved?"). */
    val PRE_APPROVABLE: Set<ActionClass> = setOf(READ, WRITE_LOCAL, WRITE_REMOTE, SETTINGS, PHYSICAL)

    /** The single local agent before teams exist (always Autopilot since 0.52, see Modes / ApprovalDefault). */
    val LEGACY = Subject("manager", Permissions(autonomy = Autonomy.AUTOPILOT, actions = CLASS_DEFAULTS))

    fun decide(bot: Bot, call: Call, ctx: Ctx = Ctx()): Decision = decide(Subject.of(bot), call, ctx)

    fun decide(bot: Subject, call: Call, ctx: Ctx = Ctx()): Decision {
        val chain = listOf(bot) + ctx.chain
        val c = ActionClasses.classify(call.tool, call.args, call.mcp)
        // A declared class (a new tool not in the table yet) replaces UNKNOWN; for a tool in the table it can only raise it.
        val cls = when { call.cls == null -> c.cls; ActionClasses.declared(call.tool) -> ActionClasses.max(call.cls, c.cls); else -> call.cls }
        val tool = call.tool
        val what = label(tool)

        // 1. NEVER anywhere along the chain, or the MCP tool is Off.
        if (call.mcp?.mode == McpConfig.OFF) return Decision.Refuse("$what is turned off in this server's MCP settings. Don't retry it.", cls, Step.NEVER)
        chain.firstOrNull { s -> never(s, tool, cls, ctx.store) }?.let { s ->
            val who = if (s === bot) "this bot" else "${s.id} (who asked for this)"
            return Decision.Refuse("Refused: $who isn't allowed to ${verb(cls)} ($what). Don't retry it; tell the user what you would have done.", cls, Step.NEVER)
        }
        // 2. Plan refuses anything that isn't a read.
        if (ctx.plan && cls != READ) return Decision.Refuse(
            "Refused: PLAN mode (read and look only) and $what can change things. Don't retry it. Finish investigating with read-only tools, then present the plan.",
            cls, Step.PLAN)
        if (ctx.approved) return Decision.Allow(cls, Step.APPROVED)
        val proactive = ctx.trigger.proactive
        // 2b. A-team jobAllowed: an unconfirmed proactive job acts only after the user's OK.
        if (proactive && !ctx.jobConfirmed && cls != READ) {
            val r = ActionClasses.risky(ctx.task)
            return Decision.Card(CardKind.APPROVAL, if (r.isNotEmpty()) "Needs your OK (${r.joinToString(", ")})." else "Not confirmed by you yet.",
                cls, Step.UNCONFIRMED_JOB)
        }
        val clean = ctx.taint == null
        // 3. Destructive, money and messages always show a card (SpendGuard / send rules only with the user present).
        when (cls) {
            DELETE -> return card(CardKind.APPROVAL, "Can't be undone" + why(c), cls, Step.ALWAYS_ASK, ctx)
            SPEND -> return if (ctx.spendPreApproved && !proactive && clean) Decision.Allow(cls, Step.STANDING)
                else card(CardKind.APPROVAL, (if (proactive) "Started without you: money always needs your OK" else "Spends money") + why(c), cls, Step.ALWAYS_ASK, ctx)
            SEND -> return if (ctx.sendPreApproved && !proactive && clean) Decision.Allow(cls, Step.STANDING)
                else card(if (c.base == SEND || call.cls == SEND) CardKind.DRAFT else CardKind.APPROVAL,
                    (if (proactive) "Started without you: messages always need your OK" else "Speaks for you") + why(c), cls, Step.ALWAYS_ASK, ctx)
            else -> {}
        }
        // 4. Prompt injection: after untrusted content, anything ≥ WRITE_REMOTE asks (ConfirmGate).
        if (!clean && cls.ordinal >= WRITE_REMOTE.ordinal)
            return card(CardKind.APPROVAL, ConfirmGate.detail(why(c).removePrefix(": "), ctx.taint), cls, Step.TAINT, ctx)
        if (cls == READ) return Decision.Allow(cls, Step.READ)
        val autonomy = chain.minOf { it.permissions.autonomy.ordinal }.let { Autonomy.values()[it] }
        if (autonomy == Autonomy.ASSIST) return card(CardKind.APPROVAL, "Assist: asks before acting" + why(c), cls, Step.ASSIST, ctx)
        // 5. Standing policies.
        when (cls) {
            CREDENTIAL -> return card(CardKind.APPROVAL, "Uses a sign-in or secret" + why(c), cls, Step.ALWAYS_ASK, ctx)
            COMPUTE_PAID -> return if (tool in ctx.grants) Decision.Allow(cls, Step.STANDING)
                else card(CardKind.APPROVAL, "Costs money while it runs (asks once per session)" + why(c), cls, Step.ALWAYS_ASK, ctx)
            else -> {}
        }
        if (tool in ctx.grants || (c.commandKey != null && c.commandKey in ctx.grants)) return Decision.Allow(cls, Step.STANDING)
        if (cls in PRE_APPROVABLE && chain.all { s -> allows(s, tool, cls, ctx.store, call.mcp, s === bot) }) return Decision.Allow(cls, Step.STANDING)
        if (cls == WRITE_REMOTE && (ctx.onlyUserAffected || tool in ActionClasses.SELF_ONLY)) return Decision.Allow(cls, Step.STANDING)
        // 6. Autopilot: non-destructive work runs (with Undo when it can be undone).
        val pinnedAsk = call.mcp?.mode == McpConfig.ASK || chain.any { explicit(it, tool, ctx.store) == Rule.ASK }
        if (autonomy == Autonomy.AUTOPILOT && !pinnedAsk)
            return if (ctx.undoable) Decision.AllowWithUndo(cls) else Decision.Allow(cls, Step.AUTOPILOT)
        // 7. Ask.
        return card(CardKind.APPROVAL, "Asks first" + why(c), cls, Step.DEFAULT, ctx)
    }

    /** "Always allow" on an approved card → a standing ALLOW for (tool, bot), only for classes that can be pre-approved. */
    fun remember(store: PolicyStore, approval: com.blaze.assistant.team.Approval, now: Long): Boolean {
        if (approval.status != ApprovalState.ALWAYS.wire || approval.actionClass !in PRE_APPROVABLE) return false
        store.set(approval.tool, approval.botId, Rule.ALLOW, approval.resolvedBy ?: "user", now)
        return true
    }

    /** The lowest autonomy along a chain (05 §7.2). */
    fun chainAutonomy(chain: List<Subject>): Autonomy = Autonomy.values()[chain.minOf { it.permissions.autonomy.ordinal }]
    /** The lowest monthly spend cap along a chain (Money can't ask Scout to buy beyond Money's cap). */
    fun chainSpendCap(chain: List<Subject>): Long = chain.minOf { it.permissions.spendCapMonthlyMinor }

    // ---------------- internals ----------------

    private fun classRule(s: Subject, cls: ActionClass): Rule = s.permissions.actions[cls] ?: CLASS_DEFAULTS.getValue(cls)

    private fun appRule(s: Subject, tool: String): Rule? = s.apps.firstNotNullOfOrNull { it.tools[tool] }

    private fun never(s: Subject, tool: String, cls: ActionClass, store: PolicyStore?): Boolean =
        classRule(s, cls) == Rule.NEVER || s.toolGrants[tool] == Rule.NEVER || appRule(s, tool) == Rule.NEVER ||
            store?.rule(tool, s.id) == Rule.NEVER

    /** The rule the user set for this tool (bot grant, app grant, then the standing policies table). */
    private fun explicit(s: Subject, tool: String, store: PolicyStore?): Rule? = s.toolGrants[tool] ?: appRule(s, tool) ?: store?.rule(tool, s.id)

    /** A standing allow for [s]: its own ASK grants / store rules win over the class default. */
    private fun allows(s: Subject, tool: String, cls: ActionClass, store: PolicyStore?, mcp: ActionClasses.McpInfo?, caller: Boolean): Boolean {
        val explicit = explicit(s, tool, store)
        if (explicit == Rule.ASK) return false
        if (explicit == Rule.ALLOW) return true
        if (caller && mcp?.mode == McpConfig.ALWAYS) return true
        if (caller && mcp?.mode == McpConfig.ASK) return false
        return classRule(s, cls) == Rule.ALLOW
    }

    private fun card(kind: CardKind, reason: String, cls: ActionClass, step: Step, ctx: Ctx) =
        Decision.Card(kind, reason, cls, step, canAlwaysAllow = cls in PRE_APPROVABLE && ctx.taint == null && !ctx.trigger.proactive,
            taintSource = if (step == Step.TAINT) ctx.taint else null)

    private fun why(c: ActionClasses.Classified) = if (c.why.isBlank()) "" else ": ${c.why}"

    private fun label(tool: String) = tool.replace('_', ' ')

    private fun verb(cls: ActionClass) = when (cls) {
        READ -> "read this"; WRITE_LOCAL -> "change things on the phone"; WRITE_REMOTE -> "change things in other apps"
        SEND -> "send messages"; SPEND -> "spend money"; DELETE -> "delete things"; SETTINGS -> "change settings"
        CREDENTIAL -> "use sign-ins or secrets"; COMPUTE_PAID -> "start paid computers"; PHYSICAL -> "tap on the screen"
    }
}
