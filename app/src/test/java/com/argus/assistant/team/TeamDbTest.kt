package com.blaze.assistant.team

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P0-TM-01: team.db through Android's SQLiteOpenHelper ([TeamDb]): a fresh install creates every 05 §9.2 table at
 * [TeamSchema.VERSION], a v1 file upgrades with its rows intact, foreign keys are on, and a file from a newer build is
 * refused instead of being downgraded. The SQL itself is also checked on the JVM by TeamSchemaTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = android.app.Application::class)
class TeamDbTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val helpers = ArrayList<TeamDb>()
    private fun db(name: String) = TeamDb(ctx, name).also { helpers += it }

    @After fun clean() {
        helpers.forEach { runCatching { it.close() } }
        listOf("t-fresh.db", "t-v1.db", "t-newer.db", "t-fk.db").forEach { ctx.deleteDatabase(it) }
    }

    private fun count(db: TeamDb, table: String): Long =
        db.readableDatabase.rawQuery("SELECT count(*) FROM $table", null).use { c -> c.moveToFirst(); c.getLong(0) }

    @Test fun freshInstallCreatesEveryTable() {
        val db = db("t-fresh.db")
        assertEquals(TeamSchema.VERSION, db.version())
        assertEquals(TeamSchema.TABLES.sorted(), db.tables())
        assertEquals(TeamSchema.INDEXES.sorted(), db.indexes())
        assertTrue(ctx.getDatabasePath("t-fresh.db").isFile)
        // opening it again changes nothing
        db.close()
        val again = db("t-fresh.db")
        assertEquals(TeamSchema.VERSION, again.version())
        assertEquals(TeamSchema.TABLES.sorted(), again.tables())
    }

    @Test fun v1FileUpgradesWithItsRows() {
        // a file as a v1 build left it: the v1 steps, user_version 1, some data
        val path = ctx.getDatabasePath("t-v1.db").also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (st in TeamSchema.steps(0, 1)) old.execSQL(st)
            old.version = 1
            old.insertOrThrow("bots", null, ContentValues().apply {
                put("id", "manager"); put("kind", BotKind.MANAGER.name); put("name", "ARGUS")
                put("avatar", TeamJson.avatar(Avatar("sphere", "#2F5BFF"))); put("engine", TeamJson.engine(EngineSpec()))
                put("computer", TeamJson.computer(ComputerSpec())); put("permissions", TeamJson.permissions(Permissions()))
                put("created_at", 1L); put("u", 1L)
            })
            old.insertOrThrow("rows", null, ContentValues().apply {
                put("bot_id", "manager"); put("generation", 0); put("seq", 1L); put("updated_seq", 1L); put("kind", "message")
                put("body", "{\"text\":\"hello\"}"); put("created_at", 5L)
            })
        }

        val db = db("t-v1.db")
        assertEquals(TeamSchema.VERSION, db.version())
        assertEquals(TeamSchema.INDEXES.sorted(), db.indexes())
        assertEquals(1L, count(db, "bots")); assertEquals(1L, count(db, "rows"))
        val perms = db.readableDatabase.rawQuery("SELECT permissions FROM bots WHERE id='manager'", null).use { c -> c.moveToFirst(); c.getString(0) }
        assertEquals(Permissions(), TeamJson.permissions(perms))
    }

    @Test fun foreignKeysAreOn() {
        val db = db("t-fk.db")
        val w = db.writableDatabase
        w.rawQuery("PRAGMA foreign_keys", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        w.execSQL("INSERT INTO jobs(id, title, source, brief, created_at, u) VALUES('j1','t','user','b',1,1)")
        w.execSQL("INSERT INTO job_steps VALUES('j1', 1, 'step', 'done', 1)")
        w.execSQL("DELETE FROM jobs WHERE id='j1'")
        assertEquals(0L, count(db, "job_steps"))
    }

    @Test fun aNewerFileIsRefusedNotDowngraded() {
        val path = ctx.getDatabasePath("t-newer.db").also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { f -> f.execSQL("CREATE TABLE future(x)"); f.version = TeamSchema.VERSION + 5 }
        try { db("t-newer.db").writableDatabase; fail("a newer file must be refused") } catch (_: TeamSchema.NewerSchema) { }
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { f ->
            assertEquals(TeamSchema.VERSION + 5, f.version)
            f.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='future'", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test fun oneSharedHelper() {
        assertSame(TeamDb.get(ctx), TeamDb.get(ctx))
        assertEquals(TeamSchema.NAME, TeamDb.get(ctx).databaseName)
    }
}
