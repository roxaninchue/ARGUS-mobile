package com.blaze.assistant.policy

import com.blaze.assistant.agent.CommandPolicy
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.Job
import com.blaze.assistant.team.Permissions
import com.blaze.assistant.team.Rule
import org.json.JSONObject

/**
 * P2-SF-01: the pure half of the dispatch glue: one tool call (name + JSON args) → [Policy.Call] and the command / MCP
 * parts of [Policy.Ctx]; a job or a bound chat's origin → trigger, chain and RISKY text; a bot → the permissions it acts
 * with. PolicyRuntime (Android) only adds what the phone knows (Modes, taint, team.db, MCP servers). PolicyCallsTest.
 */
object PolicyCalls {
    /** The class of one call, and for shell / git its CommandPolicy level. */
    data class Classed(val call: Policy.Call, val command: CommandPolicy.Level? = null, val commandKey: String? = null)

    fun classify(tool: String, argsJson: String, mcp: Pair<Boolean, Boolean>? = null): Classed {
        val a = try { JSONObject(argsJson.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        if (ToolRegistry.isMcp(tool)) {
            val cls = mcp?.let { ActionClasses.mcpClass(it.first, it.second) } ?: ActionClass.WRITE_REMOTE
            return Classed(Policy.Call(tool, cls))
        }
        commandOf(tool, a)?.let { (kind, text) ->
            if (text.isNotBlank()) return Classed(Policy.Call(tool, CommandPolicy.actionClass(kind, text)), CommandPolicy.level(kind, text), CommandPolicy.key(kind, text))
        }
        val cls = ToolRegistry.classOf(tool) { k -> a.optString(k) } ?: ToolRegistry.UNDECLARED
        return Classed(Policy.Call(tool, cls, selfCard = ToolRegistry.selfCard(tool)))
    }

    /** The command a shell / git call runs (kind + text), or null. A git call with a named action uses the registry. */
    fun commandOf(tool: String, a: JSONObject): Pair<CommandPolicy.Kind, String>? = when (tool) {
        "run_shell", "termux_run" -> CommandPolicy.Kind.SHELL to a.optString("command", a.optString("cmd"))
        "git" -> a.optString("action").lowercase().let { act ->
            if (act.isEmpty() || act == "raw") CommandPolicy.Kind.GIT to a.optString("args", a.optString("command")) else null }
        else -> null
    }

    /** Origin of a job's run (JobsRuntime sets it on the bound chat; unbound Manager jobs are looked up). */
    fun originOf(job: Job): com.blaze.assistant.agent.TeamHooks.Origin = com.blaze.assistant.agent.TeamHooks.Origin(
        trigger = job.source.substringBefore(':').ifBlank { "user" },
        chain = listOfNotNull(job.source.takeIf { it.startsWith("bot:") }?.removePrefix("bot:")?.takeIf { it.isNotBlank() }),
        jobId = job.id, text = (job.title + " " + job.notes + " " + job.brief.take(600)).trim(), confirmed = job.confirmed)

    /**
     * The permissions [bot] acts with. The Manager's autonomy follows the app's approval mode ([autopilot]: ARGUS is on
     * Autopilot); every other bot keeps its own (default Act). An unknown bot gets the defaults.
     */
    fun permsOf(bot: Bot?, autopilot: Boolean): Permissions {
        val p = bot?.permissions ?: Permissions()
        return if (bot != null && BotRules.isManager(bot) && autopilot) p.copy(autonomy = Autonomy.AUTOPILOT) else p
    }

    /** A bot's own per-tool grant (BotAppGrant rules first, then Bot.toolGrants). */
    fun grantOf(bot: Bot?, tool: String): Rule? = bot?.apps?.firstNotNullOfOrNull { it.tools[tool] } ?: bot?.toolGrants?.get(tool)

    /** Whether the policy is enforced for this call: a bot other than the Manager, or work the user didn't start. */
    fun enforced(botId: String?, trigger: Policy.Trigger): Boolean = (botId != null && botId != BotRules.MANAGER_ID) || trigger.proactive
}
