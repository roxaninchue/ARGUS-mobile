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
import com.blaze.assistant.team.JobsRuntime
import com.blaze.assistant.team.Rule
import com.blaze.assistant.team.TeamStore
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * P2-SF-01, Android glue: the one call `Agent.Session.runTool` makes ([run]). It works out who is acting and why (the
 * chat's TeamHooks binding, or the job behind an unbound job chat), reads Modes (Plan, Autopilot), this turn's taint
 * (ConfirmGate.Taint), the bot's permissions and the standing policies (team.db), the MCP tool's mode and the command's
 * level, and hands them to [PolicyGate].
 *
 * Where it applies: a bot other than the Manager, or work the user didn't start (a routine, a suggestion, another bot,
 * an app). The user's own turns with ARGUS run exactly as before (their existing confirm cards), without even being
 * classified, until P2-SF-02 moves them onto the new cards.
 *
 * Until P2-SF-02 the approval shows on today's confirm card (Agent.confirm, always shown): Allow → allowed, Don't or
 * its 2-minute timeout → denied. The approval itself still expires after 25 minutes, is skipped by a new user message,
 * and a pending row left in team.db by a dead process is closed as expired at start ([init]).
 */
object PolicyRuntime {
    @Volatile private var store: ApprovalStore? = null
    @Volatile private var policies: PolicyStore? = null
    @Volatile private var team: TeamStore? = null

    val approvals = Approvals(sink = object : Approvals.Sink {
        override fun put(a: com.blaze.assistant.team.Approval) { store?.put(a) }
        override fun resolve(id: String, status: Approvals.Status, at: Long, by: String?) { store?.resolve(id, status, at, by) }
    })

    private val gate = PolicyGate(approvals,
        presenter = { p, _ -> if (Agent.confirm(p.approval.title, p.approval.detail, "Allow", always = true)) Approvals.Status.ALLOWED else Approvals.Status.DENIED },
        saveAlways = { tool, bot -> policies?.set(tool, bot, Rule.ALLOW) },
        detach = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default))

    /** Opens the stores on team.db (JobsRuntime.init, on IO) and closes what a dead process left pending. */
    fun init(sql: Sql) {
        val s = ApprovalStore(sql)
        runCatching { s.expireLeftovers() }
        store = s; policies = PolicyStore(sql); team = TeamStore(sql)
        TeamHooks.onUserMessage = { chat, bot -> approvals.userMessage(chat, bot?.takeIf { it != BotRules.MANAGER_ID }) }
    }

    /** One tool call: [block] runs it. [agentPlan] = a background agent started in Plan. */
    suspend fun run(chatId: String, tool: String, args: String, agentPlan: Boolean, block: suspend () -> String): String {
        val binding = TeamHooks.binding(chatId)
        val jobId = binding?.origin?.jobId ?: JobsRuntime.jobOfChat(chatId)
        if (binding == null && jobId == null) return block()   // the user's own chats: unchanged
        val r = try { request(chatId, tool, args, agentPlan, binding, jobId) } catch (e: Throwable) {
            if (e is CancellationException) throw e
            com.blaze.assistant.device.CrashLog.quiet("PolicyRuntime.request", e)
            // a bound bot fails closed; an unbound job of the user's keeps running as before
            return if (binding != null && binding.botId != BotRules.MANAGER_ID) "Error: $tool wasn't checked, so it didn't run. Tell the user." else block()
        }
        if (r == null) return block()
        return gate.run(r, block)
    }

    private suspend fun request(chatId: String, tool: String, args: String, agentPlan: Boolean, binding: TeamHooks.Binding?, jobId: String?): PolicyGate.Request? {
        val origin = binding?.origin?.takeIf { it.jobId != null || it.trigger != "user" }
            ?: jobId?.let { id -> JobsRuntime.store()?.get(id)?.let { PolicyCalls.originOf(it) } }
            ?: binding?.origin ?: TeamHooks.Origin()
        val trigger = Policy.Trigger.ofSource(origin.trigger)
        val botId = binding?.botId ?: BotRules.MANAGER_ID
        if (!PolicyCalls.enforced(botId, trigger)) return null
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
        return PolicyGate.Request(chatId, botId, bot?.name ?: botId, jobId, tool, SecretVault.scrub(args).take(600), c.call, ctx, enforce = true)
    }
}
