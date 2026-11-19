package com.blaze.assistant.team

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * P0-TM-01: team.db on the phone. Raw SQLite (no Room), like cache.db and longchat.db: a plain [SQLiteOpenHelper]
 * whose schema is [TeamSchema] (05-agent-ecosystem.md §9.2 as versioned steps). Kept out of blaze.db, whose schema is
 * shared with Blaze Desktop / CLI and is never altered from here.
 *
 *  - Fresh install: [onCreate] runs every step (v0 → [TeamSchema.VERSION]).
 *  - Upgrade: [onUpgrade] runs the steps from the stored version on. SQLiteOpenHelper wraps both in one transaction
 *    and sets user_version only after they commit, so a failed step leaves the file at its old version.
 *  - A file from a newer build is refused ([TeamSchema.NewerSchema]), never downgraded or wiped.
 *  - Foreign keys are on (05 §9.2's ON DELETE CASCADE / SET NULL need them); WAL so readers don't block the writer.
 *
 * P0 only creates the store: nothing opens it yet and nothing reads it in the UI. The team stores (P1 TeamDbPort,
 * P2 AuditDao / PolicyStore) take this helper. Every call does disk I/O: open and use it off the main thread.
 * TeamDbTest (Robolectric) creates and migrates it; TeamSchemaTest checks the same SQL on the JVM.
 */
class TeamDb(context: Context, name: String? = TeamSchema.NAME) :
    SQLiteOpenHelper(context.applicationContext, name, null, TeamSchema.VERSION) {

    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = apply(db, 0)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = apply(db, oldVersion)

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw TeamSchema.NewerSchema(oldVersion)
    }

    private fun apply(db: SQLiteDatabase, from: Int) {
        for (st in TeamSchema.steps(from)) db.execSQL(st)
    }

    /** The schema version of the open file (user_version). */
    fun version(): Int = readableDatabase.version

    /** The user tables in the file, sorted (SQLite's and Android's own tables left out). */
    fun tables(): List<String> = names("table")

    /** The named indexes in the file, sorted (automatic primary-key indexes left out). */
    fun indexes(): List<String> = names("index")

    private fun names(type: String): List<String> =
        readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type=? AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name",
            arrayOf(type)).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    companion object {
        @Volatile private var shared: TeamDb? = null

        /** The app's one team.db helper (opened lazily by SQLiteOpenHelper on first use). Not called anywhere in P0. */
        fun get(context: Context): TeamDb = shared ?: synchronized(this) { shared ?: TeamDb(context).also { shared = it } }
    }
}
