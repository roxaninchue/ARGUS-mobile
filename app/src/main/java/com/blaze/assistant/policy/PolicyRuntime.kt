package com.blaze.assistant.policy

import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.ConfirmGate
import com.blaze.assistant.agent.Modes
import com.blaze.assistant.agent.SecretVault
import com.blaze.assistant.agent.TeamHooks
import com.blaze.assistant.connect.Mcp
import com.blaze.assistant.connect.McpConfig
import com.blaze.assistant.store.Sql
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.BotThreads
import com.blaze.assistant.team.JobsRuntime
import com.blaze.assistant.team.RowBodies
import com.blaze.assistant.team.RowKind
import com.blaze.assistant.team.Rule
import com.blaze.assistant.team.TeamStore
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * P2-SF-01 + P2-SF-02, Android glue: the one call `Agent.Session.runTool` makes ([run]). It works out who is acting and
 * why (the chat's TeamHooks binding, or the job behind an unbound job chat; neither = the user's own chat with ARGUS),
 * reads Modes (Plan, Autopilot), this turn's taint (ConfirmGate.Taint), the bot's permissions and the standing policies
 * (team.db), the MCP tool's mode and the command's level, and hands them to [PolicyGate].
 *
 * P2-SF-02: the policy now applies to the user's own ARGUS chats too (the Manager, trigger = user), and every "ask" is a
 * real card from [flow] ([CardFlow], drawn by ui/team/cards ArgusCards wherever the old confirm card is drawn): approval
 * or draft, one card at a time per bot, closed the moment the approval ends (Allow / Always / Don't, a new user message
 * → skipped, 25 minutes → expired). A call the user approved runs under [ConfirmGate.PolicyOk], so the tool's own confirm
 * card doesn't ask again. `request_app_connection` is a connect card in every chat; a team bot's `ask_user` is a question
 * card (the user's own chats keep Agent.askUser's card, with its overlay, voice and Autopilot rules). The switch
 * `argus_cards` (default on) puts the user's own chats back on the P2-SF-01 shadow if it ever has to.
 *
 * When ARGUS isn't on screen a card also posts the confirm notification (Allow / Don't answer the card; a question or
 * connect card posts "Action required"), cleared when the card goes. A pending row left in team.db by a dead process
 * is closed as expired at start ([init]).
 */
object PolicyRuntime {
    @Volatile private var store: ApprovalStore? = null
    @Volatile private var policies: PolicyStore? = null
    @Volatile private var team: TeamStore? = null
    @Volatile private var threads: BotThreads? = null

    val approvals = Approvals(sink = object : Approvals.Sink {
        override fun put(a: com.blaze.assistant.team.Approval) { store?.put(a) }
        override fun resolve(id: String, status: Approvals.Status, at: Long, by: String?) { store?.resolve(id, status, at, by) }
    })

    /** The card the notification shows (its Allow / Don't answer this one). */
    @Volatile private var notified: String? = null

    val center = CardCenter(com.blaze.assistant.orchestra.KeyedLocks.shared, onShow = { c -> notify(c) }, onGone = { c -> unnotify(c) })

    /** Team threads keep a card row for a bot's card: pending, then its final state. */
    private val rowOf = ConcurrentHashMap<String, Pair<String, Long>>()   // card id → (bot, seq)
    private val mirror = object : CardFlow.Mirror {
        override fun opened(card: Card) {
            val t = threads ?: return
            if (card.botId == BotRules.MANAGER_ID) return
            runCatching { t.post(card.botId, RowKind.CARD, rowBody(card, Cards.State.PENDING)) }.getOrNull()?.let { rowOf[card.id] = card.botId to it.seq }
        }
        override fun closed(card: Card, state: Cards.State) {
            val t = threads ?: return
            val (bot, seq) = rowOf.remove(card.id) ?: return
            runCatching { t.update(bot, seq, rowBody(card, state)) }
        }
    }

    val flow = CardFlow(approvals, center, mirror = mirror, isConnected = { app -> connected(app) })

    /** The cards on screen (ArgusCards draws them). */
    val cards get() = center.shown

    fun answer(id: String, a: CardAnswer): Boolean = flow.answer(id, a)

    /** The confirm notification's Allow / Don't: answers the card it shows. False = no card of ours is behind it. */
    fun answerNotified(ok: Boolean): Boolean {
        val id = notified ?: return false
        val c = center.card(id) ?: return false
        val a = when (c.kind) {
            Cards.Kind.DRAFT -> if (ok) CardAnswer.Send else CardAnswer.Discard
            Cards.Kind.APPROVAL -> if (ok) CardAnswer.Allow else CardAnswer.Deny
            else -> return false
        }
        return answer(id, a)
    }

    private val gate = PolicyGate(approvals,
        presenter = flow.presenter,
        saveAlways = { tool, bot -> policies?.set(tool, bot, Rule.ALLOW) },
        pass = { r, d -> flow.pass(r, d) },
        outcome = { id -> flow.outcome(id) })

    /** Opens the stores on team.db (JobsRuntime.init, on IO) and closes what a dead process left pending. */
    fun init(sql: Sql, botThreads: BotThreads? = null) {
        val s = ApprovalStore(sql)
        runCatching { s.expireLeftovers() }
        store = s; policies = PolicyStore(sql); team = TeamStore(sql); threads = botThreads
        TeamHooks.onUserMessage = { chat, bot -> approvals.userMessage(chat, bot?.takeIf { it != BotRules.MANAGER_ID }) }
    }

    private fun cardsOn(): Boolean = try { com.blaze.assistant.agent.Settings.bool("argus_cards", true) } catch (_: Throwable) { true }

    /** One tool call: [block] runs it. [agentPlan] = a background agent started in Plan. */
    suspend fun run(chatId: String, tool: String, args: String, agentPlan: Boolean, block: suspend () -> String): String {
        val binding = TeamHooks.binding(chatId)
        val jobId = binding?.origin?.jobId ?: JobsRuntime.jobOfChat(chatId)
        val botId = binding?.botId ?: BotRules.MANAGER_ID
        if (tool == Cards.CONNECT_TOOL) return flow.connect(chatId, botId, botName(botId), args, jobId)
        if (tool == "ask_user" && binding != null && botId != BotRules.MANAGER_ID) return flow.ask(chatId, botId, botName(botId), args, jobId)
        // 0.53.0 (card fix 1): a screen plan that sends through an app ARGUS can connect (Gmail on the screen because the
        // send tool was missing) asks to connect it first. Connected: the bot is told to use the app's tool; "Not now":
        // the screen route goes on to its Draft card; skipped / expired: nothing runs.
        if (cardsOn()) ScreenSend.of(tool, args)?.takeIf { it.app.isNotEmpty() && connectorFor(it.app) != null && !connected(it.app) && "$chatId|${it.app.lowercase()}" !in declined }?.let { s ->
            val reason = "Send through ${s.app} directly instead of driving the phone's screen. Nothing was sent yet."
            val res = flow.connect(chatId, botId, botName(botId), org.json.JSONObject().put("app", s.app).put("reason", reason).toString(), jobId)
            if (res.startsWith(Cards.connected(s.app))) return "$res. Nothing was sent yet: send it now with the ${s.app} tool, not the screen."
            if (res != Cards.notConnected(s.app)) return res
            declined += "$chatId|${s.app.lowercase()}"
        }
        val own = binding == null && jobId == null
        val r = try { request(chatId, tool, args, agentPlan, binding, jobId, own) } catch (e: Throwable) {
            if (e is CancellationException) throw e
            com.blaze.assistant.device.CrashLog.quiet("PolicyRuntime.request", e)
            // a bound bot fails closed; the user's own chats and jobs keep running as before (their tools' own confirms)
            return if (binding != null && binding.botId != BotRules.MANAGER_ID) "Error: $tool wasn't checked, so it didn't run. Tell the user." else block()
        }
        if (r == null) return block()
        return gate.run(r, block)
    }

    /**
     * 0.53.1 parallel tools: may this call run ahead in the parallel batch? Only when the policy lets it run with no card
     * ([PolicyGate.quiet]); connect / ask cards and screen sends never do. Fails safe: an error keeps it in the in-order loop.
     */
    suspend fun quiet(chatId: String, tool: String, args: String, agentPlan: Boolean): Boolean = try {
        if (tool == Cards.CONNECT_TOOL || tool == "ask_user" || ScreenSend.of(tool, args) != null) false else {
            val binding = TeamHooks.binding(chatId)
            val jobId = binding?.origin?.jobId ?: JobsRuntime.jobOfChat(chatId)
            PolicyGate.quiet(request(chatId, tool, args, agentPlan, binding, jobId, binding == null && jobId == null))
        }
    } catch (e: CancellationException) { throw e } catch (_: Throwable) { false }

    private fun botName(botId: String): String =
        if (botId == BotRules.MANAGER_ID) com.blaze.assistant.BrandName.NAME else team?.get(botId)?.name ?: botId

    private suspend fun request(chatId: String, tool: String, args: String, agentPlan: Boolean, binding: TeamHooks.Binding?, jobId: String?,
                                own: Boolean): PolicyGate.Request? {
        val origin = binding?.origin?.takeIf { it.jobId != null || it.trigger != "user" }
            ?: jobId?.let { id -> JobsRuntime.store()?.get(id)?.let { PolicyCalls.originOf(it) } }
            ?: binding?.origin ?: TeamHooks.Origin()
        val trigger = Policy.Trigger.ofSource(origin.trigger)
        val botId = binding?.botId ?: BotRules.MANAGER_ID
        // P2-SF-02: the user's own chats get cards too; `argus_cards` off = P2-SF-01's shadow (enforced only for other bots / work the user didn't start)
        if (!PolicyCalls.enforced(botId, trigger) && !(cardsOn() && (own || botId == BotRules.MANAGER_ID))) return null
        val ts = team
        val bot = ts?.get(botId)
        val autopilot = Modes.autopilot
        val mcp = if (ToolRegistry.isMcp(tool)) Mcp.find(tool) else null
        val c = PolicyCalls.classify(tool, args, mcp?.let { it.tool.readOnly to it.tool.destructive })
        val chain = origin.chain.mapNotNull { id -> ts?.get(id) }.map { PolicyCalls.permsOf(it, autopilot) }
        val allowed = c.commandKey?.let { k -> k in Agent.cmdGrants.chat(chatId) || k in Agent.cmdGrants.always() } ?: false
        val ctx = Policy.Ctx(
            botId = botId, perms = PolicyCalls.permsOf(bot, autopilot), chain = chain, trigger = trigger, triggerText = origin.text,
            confirmed = origin.confirmed, plan = Modes.plan || agentPlan, tainted = coroutineContext[ConfirmGate.Taint]?.source,
            standing = policies?.rule(tool, botId), grant = PolicyCalls.grantOf(bot, tool),
            mcp = mcp?.let { Policy.Mcp(it.override.orEmpty(), McpConfig.needsApproval(it.override, it.tool.risk, autopilot)) },
            command = c.command, commandAllowed = allowed)
        val scrubbed = SecretVault.scrub(args)
        return PolicyGate.Request(chatId, botId, bot?.name ?: botName(botId), jobId, tool, scrubbed.take(600), c.call, ctx, enforce = true,
            argsFull = scrubbed.take(ARGS_FULL_MAX))
    }

    const val ARGS_FULL_MAX = 20_000

    /** The card row a team thread keeps (ThreadModel reads it back as a decision card). */
    fun rowBody(card: Card, state: Cards.State): String = RowBodies.card(card.kind.code, linkedMapOf(
        "id" to card.id, "title" to card.title, "text" to (card.question?.text ?: card.draft?.body ?: card.detail).take(2000),
        "question" to card.question?.text, "name" to card.connect?.app, "to" to card.draft?.to, "state" to state.code))

    /** Chats where the user said "Not now" to connecting an app for a screen send ("chat|app"); the screen route goes on. */
    private val declined: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** The connector id behind [app] ("Gmail" / "Calendar" / "Drive" are Google), or null when ARGUS has none for it. */
    fun connectorFor(app: String): String? {
        val a = app.trim().lowercase()
        if (a.isEmpty()) return null
        return when {
            a.contains("gmail") || a.contains("calendar") || a.contains("drive") || a.contains("google") || a.contains("classroom") -> "google"
            else -> runCatching { com.blaze.assistant.connect.Connectors.all.firstOrNull { a.contains(it.id) || a.contains(it.name.lowercase()) }?.id }.getOrNull()
        }
    }

    /** Is [app] connected now (Connectors by id or name; "Gmail" / "Calendar" / "Drive" are Google). */
    fun connected(app: String): Boolean {
        val id = connectorFor(app) ?: return false
        return runCatching { com.blaze.assistant.connect.Connectors.connected(id) }.getOrDefault(false)
    }

    private fun notify(c: Card) {
        if (Agent.inForeground) return
        runCatching {
            notified = c.id
            when (c.kind) {
                Cards.Kind.APPROVAL -> com.blaze.assistant.notify.Notifier.confirm(c.title, listOfNotNull(c.note, c.detail).joinToString("\n"), "Allow")
                Cards.Kind.DRAFT -> com.blaze.assistant.notify.Notifier.confirm(c.title, "To ${c.draft?.to}\n\n${c.draft?.body.orEmpty()}", "Send")
                Cards.Kind.QUESTION, Cards.Kind.CONNECT -> com.blaze.assistant.notify.Notifier.actionRequired(c.title, c.botName, c.detail.take(300))
            }
        }
    }

    private fun unnotify(c: Card) {
        if (notified != c.id) return
        notified = null
        runCatching {
            if (c.kind == Cards.Kind.QUESTION || c.kind == Cards.Kind.CONNECT) com.blaze.assistant.notify.Notifier.clearAction()
            else com.blaze.assistant.notify.Notifier.clearConfirm()
        }
    }
}
