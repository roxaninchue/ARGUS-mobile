package com.argus.assistant.glance

import com.argus.assistant.stage.ShelfCard
import com.argus.assistant.stage.StageShelf
import com.argus.assistant.store.Sql
import org.json.JSONObject

/**
 * 2026-11-19 (owner, round 2): where the glance layer reads shelf cards from, a page at a time, newest first. The phone's
 * is [ShelfDb] (SQLite, unbounded); tests and small lists use [ShelfSource.of]. [skip] = glance ids shown elsewhere
 * (pinned cards sit at the top of the layer, so the newest-first list leaves them out).
 */
interface ShelfSource {
    fun count(skip: Set<String> = emptySet()): Int
    fun page(offset: Int, limit: Int, skip: Set<String> = emptySet()): List<ShelfCard>
    fun byId(id: String): ShelfCard?
    /** Cards with this title (any case), at most [limit]: the tool names a card by its title. */
    fun byTitle(title: String, limit: Int = 2): List<ShelfCard>

    companion object {
        val EMPTY: ShelfSource = of(emptyList())

        /** The same paging over a list in memory (tests; a shelf that couldn't be opened). */
        fun of(cards: List<ShelfCard>): ShelfSource = object : ShelfSource {
            private val sorted = cards.sortedByDescending { it.at }
            private fun left(skip: Set<String>) = sorted.filter { StageShelf.glanceId(it) !in skip }
            override fun count(skip: Set<String>) = left(skip).size
            override fun page(offset: Int, limit: Int, skip: Set<String>) = left(skip).drop(offset.coerceAtLeast(0)).take(limit.coerceAtLeast(0))
            override fun byId(id: String) = sorted.firstOrNull { it.id == StageShelf.bare(id) }
            override fun byTitle(title: String, limit: Int) = sorted.filter { it.title.equals(title.trim(), true) }.take(limit)
        }
    }
}

/**
 * 2026-11-19 (owner, round 2): the shelf on the phone, every card ever taken off the Stage, in its own SQLite file
 * (StageStore opens stage_shelf.db; no table in the other databases, so no migration there). Read a page at a time,
 * newest first ([page]), so neither the glance layer nor the `glance` tool ever loads it all. GlanceShelfTest runs it on JDBC SQLite.
 */
class ShelfDb(private val sql: Sql) : ShelfSource {
    private val lock = Any()

    init {
        sql.exec("CREATE TABLE IF NOT EXISTS shelf(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, " +
            "title TEXT NOT NULL, props TEXT NOT NULL, at INTEGER NOT NULL)")
        sql.exec("CREATE INDEX IF NOT EXISTS shelf_newest ON shelf(at DESC, seq DESC)")
    }

    /** Cards on top, in the order they left (the last one ends up newest); the same id again moves to the top. */
    fun add(cards: List<ShelfCard>) = synchronized(lock) {
        if (cards.isEmpty()) return@synchronized
        sql.tx {
            for (c in cards) {
                sql.exec("DELETE FROM shelf WHERE id=?", c.id)
                sql.exec("INSERT INTO shelf(id, kind, title, props, at) VALUES(?,?,?,?,?)", c.id, c.kind.id, c.title,
                    JSONObject().also { o -> c.props.forEach { (k, v) -> o.put(k, v) } }.toString(), c.at)
            }
        }
    }

    fun drop(id: String): Boolean = synchronized(lock) {
        val had = byId(id) != null
        if (had) sql.exec("DELETE FROM shelf WHERE id=?", StageShelf.bare(id))
        had
    }

    private fun not(skip: Set<String>): Pair<String, List<String>> {
        val ids = skip.filter { it.startsWith("stage:") }.map { StageShelf.bare(it) }
        return if (ids.isEmpty()) "" to ids else " WHERE id NOT IN (" + ids.joinToString(",") { "?" } + ")" to ids
    }

    override fun count(skip: Set<String>): Int {
        val (w, a) = not(skip)
        return sql.query("SELECT count(*) FROM shelf$w", *a.toTypedArray()) { it.long(0).toInt() }.firstOrNull() ?: 0
    }

    override fun page(offset: Int, limit: Int, skip: Set<String>): List<ShelfCard> {
        if (limit <= 0) return emptyList()
        val (w, a) = not(skip)
        return rows("$w ORDER BY at DESC, seq DESC LIMIT ? OFFSET ?", *(a + listOf(limit, offset.coerceAtLeast(0))).toTypedArray())
    }

    override fun byId(id: String): ShelfCard? = rows(" WHERE id=?", StageShelf.bare(id)).firstOrNull()

    override fun byTitle(title: String, limit: Int): List<ShelfCard> =
        rows(" WHERE title=? COLLATE NOCASE ORDER BY at DESC, seq DESC LIMIT ?", title.trim(), limit)

    private fun rows(tail: String, vararg a: Any?): List<ShelfCard> = sql.query("SELECT id, kind, title, props, at FROM shelf$tail", *a) { r ->
        ShelfCard.from(JSONObject().put("id", r.text(0)).put("kind", r.text(1)).put("title", r.text(2))
            .put("props", try { JSONObject(r.text(3)) } catch (_: Exception) { JSONObject() }).put("at", r.long(4)))
    }.filterNotNull()
}
