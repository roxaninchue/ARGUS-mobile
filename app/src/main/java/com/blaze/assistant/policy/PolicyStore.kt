package com.blaze.assistant.policy

import com.blaze.assistant.team.Rule

/**
 * P2-SF-01: standing per-tool rules ("Always allow", "Never"), the team.db `policies` table (05 §9.2):
 * `policies(tool, bot_id DEFAULT '*', rule, set_by, at, PRIMARY KEY(tool, bot_id))`.
 *
 * A rule for a bot wins over the `*` row for the same tool. [Policy.decide] honours ALLOW only for classes that can be
 * pre-approved (05 §7.1) and NEVER for every class. SF-04's Permissions page lists / clears them ("Ask again").
 * Implementations must be thread-safe.
 */
interface PolicyStore {
    data class Entry(val tool: String, val botId: String, val rule: Rule, val setBy: String, val at: Long)

    /** The rule for [tool] and [botId], falling back to the `*` row; null = no standing rule. */
    fun rule(tool: String, botId: String): Rule?
    fun set(tool: String, botId: String, rule: Rule, setBy: String, at: Long)
    /** "Ask again": drops the row for [tool] / [botId] (exactly that row, `*` included only when asked for). */
    fun clear(tool: String, botId: String)
    fun all(): List<Entry>

    companion object { const val ANY_BOT = "*" }
}

/** In-memory [PolicyStore] (tests, and the app until team.db exists). */
class InMemoryPolicyStore : PolicyStore {
    private val rows = LinkedHashMap<Pair<String, String>, PolicyStore.Entry>()
    @Synchronized override fun rule(tool: String, botId: String): Rule? =
        (rows[tool to botId] ?: rows[tool to PolicyStore.ANY_BOT])?.rule
    @Synchronized override fun set(tool: String, botId: String, rule: Rule, setBy: String, at: Long) {
        rows[tool to botId] = PolicyStore.Entry(tool, botId, rule, setBy, at)
    }
    @Synchronized override fun clear(tool: String, botId: String) { rows.remove(tool to botId) }
    @Synchronized override fun all(): List<PolicyStore.Entry> = rows.values.toList()
}

/**
 * The team.db `policies` table behind [PolicyStore], over a minimal SQL seam ([Sql]) so it stays pure and P0's
 * `TeamDb` (SQLiteOpenHelper) plugs in with a three-line adapter once it lands (P0-TM-01). Statements match 05 §9.2.
 */
class SqlPolicyStore(private val sql: Sql) : PolicyStore {
    /** The two calls the store needs; `args` are bound positionally (`?`). */
    interface Sql {
        fun exec(statement: String, args: List<Any?>)
        /** Rows as column→value maps. */
        fun query(statement: String, args: List<Any?>): List<Map<String, Any?>>
    }

    override fun rule(tool: String, botId: String): Rule? =
        sql.query(SELECT_ONE, listOf(tool, botId, PolicyStore.ANY_BOT, PolicyStore.ANY_BOT)).firstOrNull()
            ?.let { r -> runCatching { Rule.valueOf(r["rule"].toString()) }.getOrNull() }

    override fun set(tool: String, botId: String, rule: Rule, setBy: String, at: Long) =
        sql.exec(UPSERT, listOf(tool, botId, rule.name, setBy, at))

    override fun clear(tool: String, botId: String) = sql.exec(DELETE, listOf(tool, botId))

    override fun all(): List<PolicyStore.Entry> = sql.query(SELECT_ALL, emptyList()).mapNotNull { r ->
        val rule = runCatching { Rule.valueOf(r["rule"].toString()) }.getOrNull() ?: return@mapNotNull null
        PolicyStore.Entry(r["tool"].toString(), r["bot_id"].toString(), rule, r["set_by"].toString(), (r["at"] as? Number)?.toLong() ?: 0L)
    }

    companion object {
        /** The bot's own row first, then `*`. */
        const val SELECT_ONE = "SELECT rule FROM policies WHERE tool = ? AND (bot_id = ? OR bot_id = ?) ORDER BY CASE WHEN bot_id = ? THEN 1 ELSE 0 END LIMIT 1"
        const val UPSERT = "INSERT OR REPLACE INTO policies(tool, bot_id, rule, set_by, at) VALUES(?, ?, ?, ?, ?)"
        const val DELETE = "DELETE FROM policies WHERE tool = ? AND bot_id = ?"
        const val SELECT_ALL = "SELECT tool, bot_id, rule, set_by, at FROM policies ORDER BY tool, bot_id"
    }
}
