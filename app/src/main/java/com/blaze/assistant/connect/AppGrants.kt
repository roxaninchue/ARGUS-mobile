package com.blaze.assistant.connect

import org.json.JSONArray
import org.json.JSONObject

/**
 * P4-CN-03/04/06: which apps each bot may use (05 §9.1 `BotAppGrant`, team.db `bot_apps`). Pure.
 *
 * - The Manager (ARGUS, id `manager`) and the plain chat with no bot (`null`) see every app the user switched on,
 *   like A-team agents did; a grant list for the Manager narrows it only when [Grants.managerRestricted] is set.
 * - Every other bot sees only the apps granted to it (06 P4 gate: "a bot without the grant can't see the app").
 * - A grant can carry per-tool rules (`always` / `ask` / `off`), stored exactly like `bot_apps.tools` (`{"tool":"ask"}`).
 *
 * Seam: until P0-TM-01's team.db and P1's `team.Bot.apps` are on this branch, [Store] is backed by one Settings JSON
 * (`bot_app_grants`); `fromBot()` in the team adapter maps `team.BotAppGrant` 1:1 (same fields) once it lands.
 */
object AppGrants {
    const val MANAGER_ID = "manager"
    const val SRC_CONNECT = "argus-connect"
    const val SRC_MCP = "mcp"
    const val SRC_DEVICE = "device"

    data class Grant(val appId: String, val source: String = SRC_CONNECT, val tools: Map<String, String> = emptyMap()) {
        fun rule(tool: String): ConnectRisk.Rule? = ConnectRisk.Rule.of(tools[tool])
        fun json(): JSONObject = JSONObject().put("app_id", appId).put("source", source).put("tools", JSONObject(tools))
        companion object {
            fun from(o: JSONObject?): Grant? {
                val id = o?.optString("app_id").orEmpty().ifBlank { o?.optString("appId").orEmpty() }
                if (id.isBlank()) return null
                val t = o!!.optJSONObject("tools") ?: JSONObject()
                val tools = t.keys().asSequence().associateWith { t.optString(it) }.filterValues { ConnectRisk.Rule.of(it) != null }
                return Grant(id, o.optString("source").ifBlank { SRC_CONNECT }, tools)
            }
        }
    }

    /** One bot's grants. [managerRestricted]: the Manager uses only these too (default: Manager sees everything). */
    data class Grants(val botId: String, val grants: List<Grant> = emptyList(), val managerRestricted: Boolean = false)

    interface Store { fun read(): String; fun write(v: String) }

    fun isManager(botId: String?) = botId == null || botId == MANAGER_ID

    fun parse(text: String): Map<String, Grants> {
        val o = try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (_: Exception) { JSONObject() }
        return o.keys().asSequence().associateWith { id ->
            val b = o.optJSONObject(id) ?: JSONObject()
            val arr = b.optJSONArray("apps") ?: JSONArray()
            Grants(id, (0 until arr.length()).mapNotNull { Grant.from(arr.optJSONObject(it)) }.distinctBy { it.source to it.appId },
                b.optBoolean("restricted"))
        }
    }

    fun encode(all: Map<String, Grants>): String = JSONObject().apply {
        all.toSortedMap().forEach { (id, g) -> put(id, JSONObject().put("apps", JSONArray(g.grants.map { it.json() })).put("restricted", g.managerRestricted)) }
    }.toString()

    fun of(all: Map<String, Grants>, botId: String?): Grants = all[botId ?: MANAGER_ID] ?: Grants(botId ?: MANAGER_ID)

    /** The grant that lets [botId] use [appId] from [source], or null when it may not. Manager → an implicit grant. */
    fun grantFor(all: Map<String, Grants>, botId: String?, appId: String, source: String = SRC_CONNECT): Grant? {
        val g = of(all, botId)
        val explicit = g.grants.firstOrNull { it.appId == appId && it.source == source }
        if (explicit != null) return explicit
        return if (isManager(botId) && !g.managerRestricted) Grant(appId, source) else null
    }

    fun allows(all: Map<String, Grants>, botId: String?, appId: String, source: String = SRC_CONNECT) = grantFor(all, botId, appId, source) != null

    fun grant(all: Map<String, Grants>, botId: String, appId: String, source: String = SRC_CONNECT): Map<String, Grants> {
        val g = of(all, botId)
        if (g.grants.any { it.appId == appId && it.source == source }) return all
        return all + (botId to g.copy(grants = g.grants + Grant(appId, source)))
    }

    fun revoke(all: Map<String, Grants>, botId: String, appId: String, source: String = SRC_CONNECT): Map<String, Grants> {
        val g = of(all, botId)
        return all + (botId to g.copy(grants = g.grants.filterNot { it.appId == appId && it.source == source }))
    }

    fun setToolRule(all: Map<String, Grants>, botId: String, appId: String, tool: String, rule: ConnectRisk.Rule?, source: String = SRC_CONNECT): Map<String, Grants> {
        val g = of(all, botId)
        val cur = g.grants.firstOrNull { it.appId == appId && it.source == source } ?: Grant(appId, source)
        val tools = if (rule == null) cur.tools - tool else cur.tools + (tool to rule.wire())
        return all + (botId to g.copy(grants = g.grants.filterNot { it.appId == appId && it.source == source } + cur.copy(tools = tools)))
    }

    /** A bot was deleted: its grants go with it (team.db ON DELETE CASCADE). */
    fun dropBot(all: Map<String, Grants>, botId: String) = all - botId
}
