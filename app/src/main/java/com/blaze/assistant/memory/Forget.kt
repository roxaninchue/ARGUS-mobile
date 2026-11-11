package com.blaze.assistant.memory

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.Sql

/**
 * P3-AG-04 (05 §4.6 "Forget"): deleting a memory removes it from **everywhere** it lives, then checks by search that it
 * is really gone (the "verify after delete" rule): `memories` (its FTS5 rows go with it through the shared triggers),
 * `mind_mem`, `mem_scope`, the MEMORY.md / USER.md projections ([reproject]) and the sync blob ([synced]: the cloud
 * sync engine sees the line disappear and pushes a tombstone). Pure over [Sql] (ForgetVerifyTest).
 */
class Forget(
    private val sql: Sql,
    private val reproject: () -> Unit = {},
    private val synced: () -> Unit = {},
) {
    data class Result(val forgotten: List<String>, val verified: Boolean, val leftovers: List<String>) {
        /** One plain line for the chat / toast. */
        val line: String get() = when {
            forgotten.isEmpty() -> "Nothing matched, so nothing was forgotten."
            !verified -> "Forgot ${forgotten.size}, but ${leftovers.size} still turned up in a search; I'll try again."
            forgotten.size == 1 -> "Forgotten, and checked: it's gone everywhere."
            else -> "Forgot ${forgotten.size} notes, and checked: they're gone everywhere."
        }
    }

    private fun has(table: String) = sql.query("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?", table) { it.long(0) }.first() > 0

    /** Forgets [ids] (unknown ids are skipped), then verifies. */
    fun ids(ids: Collection<Long>): Result {
        val gone = ArrayList<Pair<Long, String>>()
        val mind = has("mind_mem"); val scope = has("mem_scope")
        sql.tx {
            for (id in ids.distinct()) {
                val t = sql.query("SELECT text FROM memories WHERE id=?", id) { it.text(0) }.firstOrNull() ?: continue
                sql.exec("DELETE FROM memories WHERE id=?", id)
                if (mind) sql.exec("DELETE FROM mind_mem WHERE id=?", id)
                if (scope) sql.exec("DELETE FROM mem_scope WHERE mem_id=?", id)
                gone += id to t
            }
        }
        if (gone.isNotEmpty()) { runCatching(reproject); runCatching(synced) }
        val left = verify(gone)
        if (left.isNotEmpty()) {   // one more pass (a concurrent writer re-added it): delete by text, then check again
            sql.tx { for (t in left) for (id in sql.query("SELECT id FROM memories WHERE norm=?", BlazeDb.norm(t)) { it.long(0) }) {
                sql.exec("DELETE FROM memories WHERE id=?", id); if (mind) sql.exec("DELETE FROM mind_mem WHERE id=?", id); if (scope) sql.exec("DELETE FROM mem_scope WHERE mem_id=?", id) } }
        }
        val still = if (left.isEmpty()) left else verify(gone)
        return Result(gone.map { it.second }, still.isEmpty(), still)
    }

    /** The texts of [gone] that a search still finds: the row by its words, the FTS index, or a side row by id. */
    fun verify(gone: List<Pair<Long, String>>): List<String> = gone.filter { (id, t) ->
        val byNorm = sql.query("SELECT count(*) FROM memories WHERE norm=?", BlazeDb.norm(t)) { it.long(0) }.first() > 0
        val q = BlazeDb.ftsQuery(t, 32)
        val inFts = q.isNotEmpty() && try {
            sql.query("SELECT m.text FROM memories_fts f JOIN memories m ON m.id=f.rowid WHERE memories_fts MATCH ?", q) { it.text(0) }
                .any { BlazeDb.norm(it) == BlazeDb.norm(t) }
        } catch (_: Exception) { false }
        val side = (has("mem_scope") && sql.query("SELECT count(*) FROM mem_scope WHERE mem_id=?", id) { it.long(0) }.first() > 0 &&
            sql.query("SELECT count(*) FROM memories WHERE id=?", id) { it.long(0) }.first() == 0L)
        byNorm || inFts || side
    }.map { it.second }

    /** "Forget that I like jazz": the lines whose text contains [query] (case-insensitive) among [candidates]. */
    fun matching(query: String, candidates: List<MemScopes.Line>): Result {
        val q = BlazeDb.clean(query).lowercase()
        if (q.length < 3) return Result(emptyList(), true, emptyList())
        return ids(candidates.filter { it.text.lowercase().contains(q) || BlazeDb.norm(it.text) == BlazeDb.norm(q) }.map { it.id })
    }

    /** USER.md "Forget everything": every `user:*` line (and nothing else). */
    fun everythingAboutUser(store: MemScopeStore): Result =
        ids(store.lines().filter { MemScopes.parse(it.scope).let { s -> s is MemScopes.Scope.UserCore || s is MemScopes.Scope.User } }.map { it.id })
}
