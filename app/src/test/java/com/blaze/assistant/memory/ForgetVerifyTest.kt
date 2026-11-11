package com.blaze.assistant.memory

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.JdbcSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** P3-AG-04: forget removes a line from memories, FTS, mind_mem, mem_scope, projections and sync, then verifies by search. */
class ForgetVerifyTest {
    private lateinit var sql: JdbcSql
    private lateinit var db: BlazeDb
    private lateinit var st: MemScopeStore
    private var reprojected = 0
    private var synced = 0

    @Before fun setUp() {
        sql = JdbcSql(); db = BlazeDb(sql); st = MemScopeStore(sql)
        // MindStore's side table, so forget proves it cleans it too
        sql.exec("CREATE TABLE IF NOT EXISTS mind_mem(id INTEGER PRIMARY KEY, created INTEGER NOT NULL, subject TEXT NOT NULL DEFAULT '', related TEXT NOT NULL DEFAULT '', source_turn INTEGER NOT NULL DEFAULT 0)")
    }
    @After fun tearDown() = sql.close()

    private fun forget() = Forget(sql, reproject = { reprojected++ }, synced = { synced++ })

    private fun add(t: String, scope: String = "team") = db.add(t).first.also {
        st.put(it, scope); sql.exec("INSERT INTO mind_mem(id, created, subject) VALUES(?,?,?)", it, 1, "You")
    }

    private fun count(q: String, vararg a: Any?) = sql.query(q, *a) { it.long(0) }.first()

    @Test fun forgetRemovesEverywhereAndVerifies() {
        val a = add("Likes jazz on Friday evenings", "user:preferences"); val b = add("Gloria is my sister", "user:people")
        val r = forget().ids(listOf(a, 999))
        assertEquals(listOf("Likes jazz on Friday evenings"), r.forgotten); assertTrue(r.verified)
        assertEquals(0L, count("SELECT count(*) FROM memories WHERE id=?", a))
        assertEquals(0L, count("SELECT count(*) FROM mem_scope WHERE mem_id=?", a))
        assertEquals(0L, count("SELECT count(*) FROM mind_mem WHERE id=?", a))
        assertTrue(db.search("jazz Friday", 5).isEmpty())
        assertEquals(1, reprojected); assertEquals(1, synced)
        assertEquals(1L, count("SELECT count(*) FROM memories WHERE id=?", b))
        assertEquals("Forgotten, and checked: it's gone everywhere.", r.line)
    }

    @Test fun nothingMatchedTouchesNothing() {
        add("Gloria is my sister")
        val r = forget().ids(listOf(42))
        assertTrue(r.forgotten.isEmpty()); assertEquals(0, reprojected); assertEquals(0, synced)
        assertEquals("Nothing matched, so nothing was forgotten.", r.line)
    }

    @Test fun verifyCatchesALineThatCameBack() {
        val a = add("Allergic to peanuts", "user:core")
        val f = forget(); f.ids(listOf(a))
        db.add("Allergic to peanuts")     // a concurrent writer (sync pull, Desktop) puts it back
        val left = f.verify(listOf(a to "Allergic to peanuts"))
        assertEquals(listOf("Allergic to peanuts"), left)
    }

    @Test fun forgetByWordsAndForgetEverythingAboutTheUser() {
        add("Likes jazz", "user:preferences"); add("Likes jazz festivals", "team"); add("Call me Finn", "user:core"); add("Team ships Friday", "team")
        val m = forget().matching("jazz", st.lines().filter { it.scope.startsWith("user:") })
        assertEquals(listOf("Likes jazz"), m.forgotten)
        assertTrue(st.lines().any { it.text == "Likes jazz festivals" })
        val all = forget().everythingAboutUser(st)
        assertEquals(listOf("Call me Finn"), all.forgotten); assertTrue(all.verified)
        assertEquals(setOf("team"), st.lines().map { it.scope }.toSet())
        assertTrue(forget().matching("x", st.lines()).forgotten.isEmpty())
        assertFalse(db.memories().any { it.text.startsWith("Call me") })
    }
}
