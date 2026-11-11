package com.blaze.assistant.memory

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.Sql

/**
 * P3-AG-01: the `mem_scope` side table in blaze.db (05 §9.2), next to MindStore's `mind_mem`. Pure JVM over [Sql]
 * (the phone runs it on the bundled SQLite, tests on sqlite-jdbc).
 *
 * The shared `memories` table and blaze.db's `user_version` are never touched: Blaze Desktop / CLI open the file as
 * before and simply don't read this table. The DDL is 05 §9.2's plus one guard column, `mem_created`: a row is only
 * trusted while it equals `memories.created` (the `mind_mem` rule), because `memories.id` can be reused after a delete
 * made by Desktop or the CLI, and a reused id must never inherit a private scope.
 *
 * Lines with no (trusted) row read as [MemScopes.defaultScope]; [MemMigration] writes their rows once.
 */
class MemScopeStore(private val sql: Sql) {

    private val lock = Any()

    init {
        for (st in BlazeDb.statements(DDL)) sql.exec(st)
        prune()
    }

    /** Drops rows whose memory is gone or was replaced (id reused). Returns how many went. */
    fun prune(): Int = synchronized(lock) {
        val gone = sql.query("SELECT s.mem_id FROM mem_scope s LEFT JOIN memories m ON m.id=s.mem_id " +
            "WHERE m.id IS NULL OR (s.mem_created<>0 AND s.mem_created<>m.created)") { it.long(0) }
        for (id in gone) sql.exec("DELETE FROM mem_scope WHERE mem_id=?", id)
        gone.size
    }

    // ------------------------------------------------------------------------------------------------ write

    sealed class Put {
        object Ok : Put()
        data class No(val why: String) : Put()
    }

    /**
     * Sets the scope of memory [memId]. Refuses an unknown scope, a missing memory and a secret line (sensitivity 2:
     * the caller should never have stored it). Upserts; keeps nothing from an older row.
     */
    fun put(memId: Long, scope: String, botId: String? = null, kind: String? = null, stated: Boolean = true,
            sensitivity: Int = MemScopes.SENSITIVE_NORMAL, shareTags: Collection<String> = emptyList(),
            source: String = "chat", expires: Long? = null): Put = synchronized(lock) {
        val s = MemScopes.parse(scope) ?: return Put.No("unknown scope \"$scope\"")
        if (s is MemScopes.Scope.Session) return Put.No("session lines stay in the chat, not in memory")
        if (sensitivity >= MemScopes.SENSITIVE_SECRET) return Put.No("secrets are never saved")
        val created = sql.query("SELECT created FROM memories WHERE id=?", memId) { it.long(0) }.firstOrNull()
            ?: return Put.No("no memory #$memId")
        val owner = botId ?: (s as? MemScopes.Scope.BotNotes)?.botId
        val sens = if (s is MemScopes.Scope.Domain && MemScopes.isPrivateDomain(s.name)) maxOf(sensitivity, MemScopes.SENSITIVE_PRIVATE) else sensitivity
        sql.exec("INSERT INTO mem_scope(mem_id, scope, bot_id, kind, stated, sensitivity, share_tags, source, expires, mem_created) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(mem_id) DO UPDATE SET scope=excluded.scope, bot_id=excluded.bot_id, kind=excluded.kind, " +
            "stated=excluded.stated, sensitivity=excluded.sensitivity, share_tags=excluded.share_tags, source=excluded.source, " +
            "expires=excluded.expires, mem_created=excluded.mem_created",
            memId, s.key, owner, kind?.lowercase(), stated, sens, tagsJson(MemScopes.cleanShares(shareTags)), source.take(40), expires, created)
        Put.Ok
    }

    /** Moves a line to another scope, keeping its flags (Settings "Move to…"). */
    fun move(memId: Long, scope: String): Put {
        val old = line(memId) ?: return Put.No("no memory #$memId")
        val botId = (MemScopes.parse(scope) as? MemScopes.Scope.BotNotes)?.botId ?: old.botId
        return put(memId, scope, botId, old.kind, old.stated, if (MemScopes.isPrivateDomain(old.scope) && !MemScopes.isPrivateDomain(scope)) 0 else old.sensitivity,
            old.shareTags, old.source, old.expires)
    }

    /** "Mark as stated" (or back to inferred). */
    fun setStated(memId: Long, stated: Boolean): Put {
        val old = line(memId) ?: return Put.No("no memory #$memId")
        return put(memId, old.scope, old.botId, old.kind, stated, old.sensitivity, old.shareTags, old.source, old.expires)
    }

    fun setShares(memId: Long, tags: Collection<String>): Put {
        val old = line(memId) ?: return Put.No("no memory #$memId")
        return put(memId, old.scope, old.botId, old.kind, old.stated, old.sensitivity, tags, old.source, old.expires)
    }

    fun delete(memId: Long) = synchronized(lock) { sql.exec("DELETE FROM mem_scope WHERE mem_id=?", memId) }

    // ------------------------------------------------------------------------------------------------ read

    private val select = "SELECT m.id, m.text, s.scope, s.bot_id, s.kind, s.stated, s.sensitivity, s.share_tags, s.source, s.expires, " +
        "m.pinned, m.uses, m.updated, m.created, s.mem_created, m.category FROM memories m LEFT JOIN mem_scope s ON s.mem_id=m.id"

    private fun lineOf(r: Sql.Row): MemScopes.Line {
        val pinned = r.long(10) != 0L
        val trusted = r.text(2).isNotEmpty() && (r.long(14) == 0L || r.long(14) == r.long(13))
        return if (trusted) MemScopes.Line(
            id = r.long(0), text = r.text(1), scope = r.text(2), botId = r.text(3).ifEmpty { null }, kind = r.text(4).ifEmpty { null },
            stated = r.long(5) != 0L, sensitivity = r.long(6).toInt(), shareTags = tagsOf(r.text(7)), source = r.text(8).ifEmpty { "chat" },
            expires = r.long(9).takeIf { it > 0 }, pinned = pinned, uses = r.long(11).toInt(), updated = r.long(12), created = r.long(13),
        ) else MemScopes.Line(
            id = r.long(0), text = r.text(1), scope = MemScopes.defaultScope(pinned), kind = r.text(15).ifEmpty { null },
            source = "legacy", pinned = pinned, uses = r.long(11).toInt(), updated = r.long(12), created = r.long(13),
        )
    }

    fun line(memId: Long): MemScopes.Line? = sql.query("$select WHERE m.id=?", memId) { lineOf(it) }.firstOrNull()

    /** Every line with its (effective) scope, oldest first. */
    fun lines(): List<MemScopes.Line> = sql.query("$select ORDER BY m.created, m.id") { lineOf(it) }

    /** Lines in one scope (effective), oldest first. */
    fun inScope(scope: String): List<MemScopes.Line> = lines().filter { it.scope == scope }

    /** Every line [v] may read, oldest first. */
    fun visible(v: MemScopes.Viewer, now: Long = BlazeDb.now()): List<MemScopes.Line> = lines().filter { MemScopes.visible(v, it, now) }

    /** Memory ids with no trusted mem_scope row (what P3-AG-02 migrates). */
    fun unscoped(): List<Long> = sql.query("SELECT m.id FROM memories m LEFT JOIN mem_scope s ON s.mem_id=m.id " +
        "WHERE s.mem_id IS NULL OR (s.mem_created<>0 AND s.mem_created<>m.created) ORDER BY m.id") { it.long(0) }

    /**
     * memory_search with scopes: FTS5 (bm25) over the shared table, then only what [v] may read, best first. A query
     * with no usable words returns the viewer's newest lines.
     */
    fun search(v: MemScopes.Viewer, query: String, limit: Int = 8, now: Long = BlazeDb.now()): List<MemScopes.Line> {
        val fq = BlazeDb.ftsQuery(query, 32)
        if (fq.isEmpty()) return visible(v, now).sortedByDescending { it.updated }.take(limit)
        val hits = try {
            sql.query("SELECT m.id FROM memories_fts f JOIN memories m ON m.id=f.rowid WHERE memories_fts MATCH ? " +
                "ORDER BY bm25(memories_fts), m.uses DESC LIMIT ?", fq, limit * 6) { it.long(0) }
        } catch (_: Exception) { emptyList() }
        val out = ArrayList<MemScopes.Line>()
        for (id in hits) { val l = line(id) ?: continue; if (MemScopes.visible(v, l, now)) out += l; if (out.size >= limit) break }
        return out
    }

    /** Scope → number of lines (effective scopes), for Settings › What the team knows. */
    fun counts(): Map<String, Int> = lines().groupingBy { it.scope }.eachCount().toSortedMap()

    /** The prompt blocks for one turn of bot [v]: pinned core + relevant notes (05 §4.4 steps 1 and 3). */
    fun promptBlocks(v: MemScopes.Viewer, message: String, names: Map<String, String> = emptyMap(), now: Long = BlazeDb.now()): Pair<String, String> {
        val all = visible(v, now)
        val core = MemScopes.coreBlock(all)
        val hits = search(v, message, 8, now)
        val own = all.filter { it.scope == MemScopes.BOT_PREFIX + v.botId }
        return core to MemScopes.relevantBlock(v, hits, own, core, names)
    }

    companion object {
        /** 05 §9.2 verbatim, plus mem_created (see the class comment). IF NOT EXISTS: opening twice is a no-op. */
        const val DDL = """CREATE TABLE IF NOT EXISTS mem_scope(
  mem_id INTEGER PRIMARY KEY,
  scope TEXT NOT NULL,
  bot_id TEXT, kind TEXT, stated INTEGER NOT NULL DEFAULT 1,
  sensitivity INTEGER NOT NULL DEFAULT 0, share_tags TEXT NOT NULL DEFAULT '[]',
  source TEXT NOT NULL DEFAULT 'chat', expires INTEGER,
  mem_created INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS mem_scope_scope ON mem_scope(scope, bot_id);"""

        fun tagsJson(tags: Collection<String>): String = tags.joinToString(",", "[", "]") { "\"" + it.replace("\"", "") + "\"" }
        fun tagsOf(json: String): Set<String> = Regex("\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toSortedSet()
    }
}
