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
 * `TeamDb` (SQLiteOpenHelper) plugs in through [TeamDbPolicySql] (TeamDbPolicySql.kt, the only Android part). Statements match 05 §9.2 / P0-TM-01 TeamSchema.
 *
 * [set] is an upsert written for the phone's system SQLite: team.db is a plain SQLiteOpenHelper, so on Android 8–10
 * (minSdk 26) it runs on SQLite 3.18–3.22, which has no `INSERT … ON CONFLICT … DO UPDATE` (added in 3.24). Inside one
 * transaction it runs [UPDATE] on the key and, only when that changed no row, [INSERT]. Never `INSERT OR REPLACE`:
 * that deletes and re-inserts the row, which fires ON DELETE CASCADE on anything referencing it and resets columns the
 * statement doesn't name. SqlPolicyStoreTest checks this on sqlite-jdbc 3.21 (an engine without 3.24 upsert) and 3.46.
 */
class SqlPolicyStore(private val sql: Sql) : PolicyStore {
    /** The calls the store needs; `args` are bound positionally (`?`). */
    interface Sql {
        fun exec(statement: String, args: List<Any?>)
        /** Rows as column→value maps. */
        fun query(statement: String, args: List<Any?>): List<Map<String, Any?>>
        /** Runs an UPDATE / DELETE and returns how many rows it changed (SQLite `changes()`). */
        fun update(statement: String, args: List<Any?>): Int
        /** Runs [block] in one transaction: committed when it returns, rolled back when it throws. */
        fun <T> transaction(block: () -> T): T
    }

    override fun rule(tool: String, botId: String): Rule? =
        sql.query(SELECT_ONE, listOf(tool, botId, PolicyStore.ANY_BOT, PolicyStore.ANY_BOT)).firstOrNull()
            ?.let { r -> runCatching { Rule.valueOf(r["rule"].toString()) }.getOrNull() }

    /** Upsert on (tool, bot_id): UPDATE, then INSERT only when no row matched, in one transaction (see the class doc). */
    @Synchronized override fun set(tool: String, botId: String, rule: Rule, setBy: String, at: Long) {
        sql.transaction {
            if (sql.update(UPDATE, listOf(rule.name, setBy, at, tool, botId)) == 0)
                sql.exec(INSERT, listOf(tool, botId, rule.name, setBy, at))
        }
    }

    @Synchronized override fun clear(tool: String, botId: String) = sql.exec(DELETE, listOf(tool, botId))

    override fun all(): List<PolicyStore.Entry> = sql.query(SELECT_ALL, emptyList()).mapNotNull { r ->
        val rule = runCatching { Rule.valueOf(r["rule"].toString()) }.getOrNull() ?: return@mapNotNull null
        PolicyStore.Entry(r["tool"].toString(), r["bot_id"].toString(), rule, r["set_by"].toString(), (r["at"] as? Number)?.toLong() ?: 0L)
    }

    companion object {
        /** The bot's own row first, then `*`. */
        const val SELECT_ONE = "SELECT rule FROM policies WHERE tool = ? AND (bot_id = ? OR bot_id = ?) ORDER BY CASE WHEN bot_id = ? THEN 1 ELSE 0 END LIMIT 1"
        /** Upsert, step 1: args (rule, set_by, at, tool, bot_id). */
        const val UPDATE = "UPDATE policies SET rule = ?, set_by = ?, at = ? WHERE tool = ? AND bot_id = ?"
        /** Upsert, step 2 (only when [UPDATE] changed no row): args (tool, bot_id, rule, set_by, at). */
        const val INSERT = "INSERT INTO policies(tool, bot_id, rule, set_by, at) VALUES(?, ?, ?, ?, ?)"
        const val DELETE = "DELETE FROM policies WHERE tool = ? AND bot_id = ?"
        const val SELECT_ALL = "SELECT tool, bot_id, rule, set_by, at FROM policies ORDER BY tool, bot_id"
    }
}
