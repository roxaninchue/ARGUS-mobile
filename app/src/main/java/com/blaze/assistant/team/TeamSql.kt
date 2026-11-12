package com.blaze.assistant.team

import android.content.Context
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteProgram
import com.blaze.assistant.agent.ChatNav
import com.blaze.assistant.agent.ChatStore
import com.blaze.assistant.store.Sql

/**
 * P1-SH-01: [Sql] over team.db's own [TeamDb] connection (framework SQLite), so the team stores share the helper that
 * created and migrated the file. Not [com.blaze.assistant.store.AndroidSql]: that is the bundled SQLite, and two SQLite
 * libraries holding locks on one WAL file in one process can corrupt it. Arguments are bound with their real types
 * (a cursor factory binds the query's), never as strings.
 */
class TeamSql(private val db: SQLiteDatabase) : Sql {
    private fun bind(p: SQLiteProgram, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val n = i + 1
            when (a) {
                null -> p.bindNull(n)
                is String -> p.bindString(n, a)
                is Long -> p.bindLong(n, a)
                is Int -> p.bindLong(n, a.toLong())
                is Boolean -> p.bindLong(n, if (a) 1L else 0L)
                is Double -> p.bindDouble(n, a)
                is Float -> p.bindDouble(n, a.toDouble())
                else -> p.bindString(n, a.toString())
            }
        }
    }

    override fun exec(sql: String, vararg args: Any?) {
        db.compileStatement(sql).use { st -> bind(st, args); st.execute() }
    }

    override fun <T> query(sql: String, vararg args: Any?, row: (Sql.Row) -> T): List<T> {
        val factory = SQLiteDatabase.CursorFactory { _, driver, table, q -> bind(q, args); SQLiteCursor(driver, table, q) }
        return db.rawQueryWithFactory(factory, sql, null, null).use { c ->
            val r = object : Sql.Row {
                override fun long(i: Int): Long = c.getLong(i)
                override fun text(i: Int): String = c.getString(i) ?: ""
            }
            val out = ArrayList<T>()
            while (c.moveToNext()) out += row(r)
            out
        }
    }

    /** Nested calls join the outer transaction (framework SQLite nests them; the outermost commits). */
    override fun <T> tx(block: () -> T): T {
        db.beginTransaction()
        try { val v = block(); db.setTransactionSuccessful(); return v } finally { db.endTransaction() }
    }

    /** The connection belongs to [TeamDb]; closing this adapter leaves it open. */
    override fun close() {}
}

/**
 * P1-SH-01: the app's one team graph (store + threads over team.db), opened on first use off the main thread. The Team
 * screen reads it; bot turns aren't wired here yet ([BotThreads] gets no engines: P1-SH-03 / P1-TM-04 add them), so
 * nothing here runs a model.
 */
object TeamGraph {
    class Graph(val sql: Sql, val store: TeamStore, val threads: BotThreads)

    @Volatile private var g: Graph? = null

    /** Opens team.db, makes sure the Manager and the seeded domains are there ([TeamStore.ensure]), and keeps the graph. */
    fun get(context: Context): Graph = g ?: synchronized(this) {
        g ?: run {
            val sql = TeamSql(TeamDb.get(context).writableDatabase)
            val store = TeamStore(sql)
            store.ensure()
            Graph(sql, store, BotThreads(sql, store, ChatStore.argus { ChatNav.MAIN }, engineFor = { null }))
        }.also { g = it }
    }
}
