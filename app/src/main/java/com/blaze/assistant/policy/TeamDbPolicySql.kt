package com.blaze.assistant.policy

import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper

/**
 * [SqlPolicyStore.Sql] over an Android [SQLiteOpenHelper], i.e. P0-TM-01's `TeamDb` on the phone's system SQLite
 * (3.18–3.22 on Android 8–10, so [SqlPolicyStore] never sends 3.24 upsert syntax through here).
 * Use: `SqlPolicyStore(TeamDbPolicySql(TeamDb.get(context)))`. Off the main thread (disk I/O).
 */
class TeamDbPolicySql(private val helper: SQLiteOpenHelper) : SqlPolicyStore.Sql {
    private fun bind(args: List<Any?>): Array<Any?> = args.map { if (it is Boolean) (if (it) 1L else 0L) else it }.toTypedArray()

    override fun exec(statement: String, args: List<Any?>) = helper.writableDatabase.execSQL(statement, bind(args))

    override fun query(statement: String, args: List<Any?>): List<Map<String, Any?>> =
        helper.readableDatabase.rawQuery(statement, args.map { it?.toString() }.toTypedArray()).use { c ->
            val out = ArrayList<Map<String, Any?>>()
            while (c.moveToNext()) out += (0 until c.columnCount).associate { i ->
                c.getColumnName(i) to when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    else -> c.getString(i)
                }
            }
            out
        }

    override fun update(statement: String, args: List<Any?>): Int =
        helper.writableDatabase.compileStatement(statement).use { st ->
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> st.bindNull(i + 1)
                    is Long -> st.bindLong(i + 1, a)
                    is Int -> st.bindLong(i + 1, a.toLong())
                    is Boolean -> st.bindLong(i + 1, if (a) 1L else 0L)
                    is Double -> st.bindDouble(i + 1, a)
                    else -> st.bindString(i + 1, a.toString())
                }
            }
            st.executeUpdateDelete()
        }

    override fun <T> transaction(block: () -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try { val v = block(); db.setTransactionSuccessful(); return v } finally { db.endTransaction() }
    }
}

