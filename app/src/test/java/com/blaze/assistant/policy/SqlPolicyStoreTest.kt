package com.blaze.assistant.policy

import com.blaze.assistant.team.Rule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection

/**
 * [SqlPolicyStore] against a real SQLite (sqlite-jdbc), with the team.db `policies` DDL from P0-TM-01 TeamSchema v1.
 *
 * team.db runs on the phone's system SQLite: 3.18–3.22 on Android 8–10 (minSdk 26), which has no
 * `INSERT … ON CONFLICT … DO UPDATE` (3.24). The store's upsert is UPDATE-then-INSERT in one transaction; this test
 * proves insert-then-update semantics, that a row referencing the policy survives an update (no delete + re-insert,
 * so no ON DELETE CASCADE), and that no statement uses 3.24 upsert syntax. Run it on sqlite-jdbc 3.21.0.1 too (an
 * engine older than 3.24, where that syntax is a syntax error) as well as the build's 3.46.
 */
class SqlPolicyStoreTest {
    private lateinit var conn: Connection
    private val sent = mutableListOf<String>()

    /** [SqlPolicyStore.Sql] over a JDBC connection; one connection, so `changes()` semantics match the phone. */
    private inner class JdbcPolicySql : SqlPolicyStore.Sql {
        private fun prep(statement: String, args: List<Any?>) = conn.prepareStatement(statement).apply {
            sent += statement
            args.forEachIndexed { i, a -> if (a == null) setNull(i + 1, java.sql.Types.NULL) else setObject(i + 1, a) }
        }
        override fun exec(statement: String, args: List<Any?>) { prep(statement, args).use { it.execute() } }
        override fun query(statement: String, args: List<Any?>): List<Map<String, Any?>> = prep(statement, args).use { st ->
            st.executeQuery().use { rs ->
                val out = ArrayList<Map<String, Any?>>()
                while (rs.next()) out += (1..rs.metaData.columnCount).associate { rs.metaData.getColumnLabel(it) to rs.getObject(it) }
                out
            }
        }
        override fun update(statement: String, args: List<Any?>): Int = prep(statement, args).use { it.executeUpdate() }
        override fun <T> transaction(block: () -> T): T {
            conn.autoCommit = false
            try { val v = block(); conn.commit(); return v } catch (e: Throwable) { conn.rollback(); throw e } finally { conn.autoCommit = true }
        }
    }

    private lateinit var store: SqlPolicyStore

    @Before fun open() {
        conn = org.sqlite.JDBC().connect("jdbc:sqlite::memory:", java.util.Properties())!!
        conn.createStatement().use { s ->
            s.execute("PRAGMA foreign_keys = ON")   // TeamDb.onConfigure turns them on
            // P0-TM-01 TeamSchema v1, verbatim.
            s.execute("CREATE TABLE policies(tool TEXT NOT NULL, bot_id TEXT NOT NULL DEFAULT '*', rule TEXT NOT NULL, set_by TEXT NOT NULL, " +
                "at INTEGER NOT NULL, PRIMARY KEY(tool, bot_id))")
            // Test-only child: nothing in team.db references policies today, but an upsert must never delete the row.
            s.execute("CREATE TABLE policy_notes(tool TEXT NOT NULL, bot_id TEXT NOT NULL, note TEXT NOT NULL, " +
                "FOREIGN KEY(tool, bot_id) REFERENCES policies(tool, bot_id) ON DELETE CASCADE)")
        }
        store = SqlPolicyStore(JdbcPolicySql())
    }

    @After fun close() = conn.close()

    private fun count(sql: String): Int = conn.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getInt(1) } }

    @Test fun engineVersionIsReported() {
        val v = conn.createStatement().use { s -> s.executeQuery("SELECT sqlite_version()").use { it.next(); it.getString(1) } }
        println("SqlPolicyStoreTest: SQLite $v")
        assertTrue(v.isNotBlank())
    }

    @Test fun insertThenUpdate() {
        store.set("gcal_create", "nova", Rule.ALLOW, "user", 1)
        assertEquals(Rule.ALLOW, store.rule("gcal_create", "nova"))
        assertEquals(listOf(PolicyStore.Entry("gcal_create", "nova", Rule.ALLOW, "user", 1)), store.all())

        store.set("gcal_create", "nova", Rule.NEVER, "settings", 2)
        assertEquals(Rule.NEVER, store.rule("gcal_create", "nova"))
        assertEquals(listOf(PolicyStore.Entry("gcal_create", "nova", Rule.NEVER, "settings", 2)), store.all())
        assertEquals(1, count("SELECT COUNT(*) FROM policies"))

        // Same values again: UPDATE still matches the row, so no duplicate INSERT (it would hit the primary key).
        store.set("gcal_create", "nova", Rule.NEVER, "settings", 2)
        assertEquals(1, count("SELECT COUNT(*) FROM policies"))
    }

    @Test fun keysAreIndependentAndBotRowBeatsStar() {
        store.set("send_sms", PolicyStore.ANY_BOT, Rule.NEVER, "user", 1)
        store.set("send_sms", "nova", Rule.ALLOW, "user", 2)
        store.set("gcal_create", "nova", Rule.ALLOW, "user", 3)
        store.set("send_sms", PolicyStore.ANY_BOT, Rule.ASK, "user", 4)
        assertEquals(3, count("SELECT COUNT(*) FROM policies"))
        assertEquals(Rule.ALLOW, store.rule("send_sms", "nova"))
        assertEquals(Rule.ASK, store.rule("send_sms", "kai"))
        store.clear("send_sms", "nova")
        assertEquals(Rule.ASK, store.rule("send_sms", "nova"))
        assertNull(store.rule("delete_file", "nova"))
    }

    @Test fun childRowsSurviveAnUpdate() {
        store.set("gcal_create", "nova", Rule.ALLOW, "user", 1)
        conn.createStatement().use { it.execute("INSERT INTO policy_notes(tool, bot_id, note) VALUES('gcal_create', 'nova', 'kept')") }
        store.set("gcal_create", "nova", Rule.NEVER, "user", 2)
        store.set("gcal_create", "nova", Rule.ALLOW, "user", 3)
        assertEquals(1, count("SELECT COUNT(*) FROM policy_notes WHERE tool = 'gcal_create' AND bot_id = 'nova'"))
        assertEquals(Rule.ALLOW, store.rule("gcal_create", "nova"))

        // Control: the cascade is real, so the check above would catch a delete + re-insert (INSERT OR REPLACE).
        conn.createStatement().use { it.execute("INSERT OR REPLACE INTO policies(tool, bot_id, rule, set_by, at) VALUES('gcal_create', 'nova', 'ASK', 'user', 4)") }
        assertEquals(0, count("SELECT COUNT(*) FROM policy_notes"))
    }

    @Test fun aFailedUpsertRollsBack() {
        store.set("gcal_create", "nova", Rule.ALLOW, "user", 1)
        conn.createStatement().use { it.execute("CREATE TRIGGER no_never BEFORE UPDATE ON policies WHEN NEW.rule = 'NEVER' BEGIN SELECT RAISE(ABORT, 'nope'); END") }
        assertTrue(runCatching { store.set("gcal_create", "nova", Rule.NEVER, "user", 2) }.isFailure)
        assertTrue(conn.autoCommit)
        assertEquals(listOf(PolicyStore.Entry("gcal_create", "nova", Rule.ALLOW, "user", 1)), store.all())
    }

    @Test fun noUpsertSyntaxNewerThanTheSystemSqlite() {
        store.set("gcal_create", "nova", Rule.ALLOW, "user", 1)
        store.set("gcal_create", "nova", Rule.NEVER, "user", 2)
        store.clear("gcal_create", "nova")
        assertTrue(sent.isNotEmpty())
        for (s in sent) {
            assertTrue(s, !s.contains("ON CONFLICT", ignoreCase = true))
            assertTrue(s, !s.contains("OR REPLACE", ignoreCase = true))
            assertTrue(s, !s.contains("UPSERT", ignoreCase = true))
        }
    }
}
