package com.blaze.assistant.team

import com.blaze.assistant.store.JdbcSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P0-TM-01: team.db's SQL ([TeamSchema]) on a real SQLite (sqlite-jdbc, like CacheStoreTest / BlazeDbTest): a fresh file
 * gets every 05 §9.2 table with its columns, a v1 file migrates to the current version with its rows intact, the
 * foreign keys cascade, every step is one statement, and a newer file is refused. TeamDbTest runs the same steps
 * through the Android SQLiteOpenHelper.
 */
class TeamSchemaTest {
    private val dir: File = Files.createTempDirectory("teamdb").toFile()
    private val open = ArrayList<JdbcSql>()
    private fun sql(name: String = "team.db") = JdbcSql("jdbc:sqlite:" + File(dir, name).path).also { open += it }

    @After fun clean() { open.forEach { runCatching { it.close() } }; dir.deleteRecursively() }

    /** What SQLiteOpenHelper does: the steps and the new user_version in one transaction, foreign keys on. */
    private fun migrate(s: JdbcSql, to: Int = TeamSchema.VERSION) {
        s.exec("PRAGMA foreign_keys = ON")
        val from = version(s)
        s.tx { for (st in TeamSchema.steps(from, to)) s.exec(st); s.exec("PRAGMA user_version = $to") }
    }

    private fun version(s: JdbcSql) = s.query("PRAGMA user_version") { it.long(0).toInt() }.first()
    private fun names(s: JdbcSql, type: String) =
        s.query("SELECT name FROM sqlite_master WHERE type=? AND name NOT LIKE 'sqlite_%' ORDER BY name", type) { it.text(0) }
    private fun columns(s: JdbcSql, table: String) = s.query("PRAGMA table_info($table)") { it.text(1) }
    private fun count(s: JdbcSql, table: String) = s.query("SELECT count(*) FROM $table") { it.long(0) }.first()

    /** 05 §9.2, column by column, in DDL order. */
    private val expected = mapOf(
        "bots" to "id kind name label domain avatar voice mode soul_version skills tool_groups tool_grants engine computer permissions " +
            "routing_hints reports_to parent_id members hidden pinned enabled generation unread last_preview last_activity_at created_at u",
        "bot_soul_versions" to "bot_id version body at by",
        "bot_apps" to "bot_id app_id source tools",
        "rows" to "bot_id generation seq updated_seq kind body created_at",
        "jobs" to "id title bot_id due priority status run deps parent_job source brief result notes confirmed retry_at created_at done_at u",
        "job_steps" to "job_id seq label state at",
        "bot_threads" to "id a b origin_job depth budget_left state created_at",
        "routines" to "id bot_id name prompt kind schedule timezone runs_in enabled silent_if_nothing silent_streak last_run_at next_run_at created_at u",
        "suggestions" to "id bot_id title why task topic urgency risk state created_at",
        "proactivity" to "id enabled quiet daily_cap sent_day sent_n mutes",
        "approvals" to "id bot_id job_id call_id tool action_class title detail args_redacted tainted status on_device_only created_at resolved_at resolved_by u",
        "policies" to "tool bot_id rule set_by at",
        "spend_ledger" to "id bot_id approval_id amount_minor currency merchant at",
        "audit_log" to "id at bot_id chain trigger tool action_class args_redacted decision decided_by device result undo cost_minor prev_hash hash",
        "screens" to "id bot_id provider display cdp_port state leased_at last_used_at",
    ).mapValues { it.value.split(' ') }

    private fun insertBot(s: JdbcSql, b: Bot) = s.exec(
        "INSERT INTO bots(id, kind, name, label, domain, avatar, voice, mode, soul_version, skills, tool_groups, tool_grants, engine, computer, " +
            "permissions, routing_hints, reports_to, parent_id, members, hidden, pinned, enabled, created_at, u) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        b.id, b.kind.name, b.name, b.label, b.domain, TeamJson.avatar(b.avatar), TeamJson.voice(b.voice), b.mode, b.soulVersion,
        TeamJson.strings(b.skills), TeamJson.strings(b.toolGroups), TeamJson.grants(b.toolGrants), TeamJson.engine(b.engine),
        TeamJson.computer(b.computer), TeamJson.permissions(b.permissions), TeamJson.strings(b.routingHints), b.reportsTo, b.parentId,
        TeamJson.strings(b.members), b.hidden, b.pinned, b.enabled, b.createdAt, b.u)

    private fun bot(id: String, parent: String? = null) = Bot(id = id, kind = if (parent == null) BotKind.MANAGER else BotKind.WORKER,
        name = id.uppercase(), label = "Lead", avatar = Avatar("orb", "#2F5BFF", 1), soulPath = "bots/$id/SOUL.md",
        toolGroups = setOf("core"), reportsTo = if (parent == null) null else "manager", parentId = parent, createdAt = 1, u = 1)

    @Test fun freshFileGetsTheWholeSchema() {
        val s = sql()
        migrate(s)
        assertEquals(TeamSchema.VERSION, version(s))
        assertEquals(2, TeamSchema.VERSION)
        assertEquals(TeamSchema.TABLES.sorted(), names(s, "table"))
        assertEquals(expected.keys.sorted(), TeamSchema.TABLES.sorted())
        for ((t, cols) in expected) assertEquals(t, cols, columns(s, t))
        assertEquals(TeamSchema.INDEXES.sorted(), names(s, "index"))
    }

    @Test fun v1FileMigratesWithItsRowsIntact() {
        val s = sql()
        migrate(s, to = 1)
        assertEquals(1, version(s))
        assertEquals(listOf("rows_updated"), names(s, "index"))
        insertBot(s, bot("manager"))
        s.exec("INSERT INTO rows VALUES(?,?,?,?,?,?,?)", "manager", 0, 1L, 1L, "message", "{\"text\":\"hi\"}", 5L)
        s.exec("INSERT INTO jobs(id, title, bot_id, source, brief, created_at, u) VALUES('j1','Pay','manager','user','b',1,1)")
        s.exec("INSERT INTO audit_log(at, bot_id, chain, trigger, tool, action_class, args_redacted, decision, prev_hash, hash) " +
            "VALUES(1,'manager','[]','user','pay','SPEND','{}','asked','','h1')")
        s.close(); open.remove(s)

        val again = sql()
        migrate(again)
        assertEquals(TeamSchema.VERSION, version(again))
        assertEquals(TeamSchema.INDEXES.sorted(), names(again, "index"))
        assertEquals(1L, count(again, "bots")); assertEquals(1L, count(again, "rows")); assertEquals(1L, count(again, "jobs")); assertEquals(1L, count(again, "audit_log"))
        assertEquals("{\"text\":\"hi\"}", again.query("SELECT body FROM rows WHERE bot_id='manager'") { it.text(0) }.first())
        // the job took the 05 §9.2 defaults
        assertEquals(listOf("normal", "todo", "QUEUED", "[]", "", "1"),
            again.query("SELECT priority, status, run, deps, notes, confirmed FROM jobs") { r -> (0..5).map { r.text(it) } }.first())
        // nothing left to do at the current version
        assertTrue(TeamSchema.steps(TeamSchema.VERSION).isEmpty())
    }

    @Test fun botColumnsRoundTripThroughJson() {
        val s = sql(); migrate(s)
        val b = bot("manager").copy(skills = listOf("a"), toolGrants = mapOf("x" to Rule.NEVER), members = listOf("m1"), routingHints = listOf("money"),
            voice = Voice("v", 0.9f, "en"), permissions = Permissions(autonomy = Autonomy.ASSIST))
        insertBot(s, b)
        val back = s.query("SELECT id, kind, name, label, domain, avatar, voice, mode, soul_version, skills, tool_groups, tool_grants, engine, " +
            "computer, permissions, routing_hints, reports_to, parent_id, members, hidden, pinned, enabled, created_at, u, reports_to IS NULL, " +
            "domain IS NULL, parent_id IS NULL FROM bots") { r ->
            Bot(id = r.text(0), kind = BotKind.valueOf(r.text(1)), name = r.text(2), label = r.text(3), domain = if (r.long(25) == 1L) null else r.text(4),
                avatar = TeamJson.avatar(r.text(5)), voice = TeamJson.voice(r.text(6)), mode = r.text(7), soulPath = b.soulPath,
                soulVersion = r.long(8).toInt(), skills = TeamJson.strings(r.text(9)), toolGroups = TeamJson.strings(r.text(10)).toSet(),
                toolGrants = TeamJson.grants(r.text(11)), engine = TeamJson.engine(r.text(12)), computer = TeamJson.computer(r.text(13)),
                permissions = TeamJson.permissions(r.text(14)), routingHints = TeamJson.strings(r.text(15)),
                reportsTo = if (r.long(24) == 1L) null else r.text(16), parentId = if (r.long(26) == 1L) null else r.text(17),
                members = TeamJson.strings(r.text(18)), hidden = r.long(19) != 0L, pinned = r.long(20) != 0L, enabled = r.long(21) != 0L,
                createdAt = r.long(22), u = r.long(23))
        }.single()
        assertEquals(b, back)
    }

    @Test fun foreignKeysCascade() {
        val s = sql(); migrate(s)
        insertBot(s, bot("manager")); insertBot(s, bot("w1", parent = "manager"))
        s.exec("INSERT INTO bot_soul_versions VALUES('w1', 1, 'soul', 1, 'user')")
        s.exec("INSERT INTO bot_apps(bot_id, app_id, source) VALUES('w1', 'gmail', 'argus-connect')")
        s.exec("INSERT INTO routines(id, bot_id, name, prompt, kind, schedule, timezone, created_at, u) VALUES('r1','w1','n','p','CRON','0 7 * * *','Africa/Lusaka',1,1)")
        s.exec("INSERT INTO screens(id, bot_id, provider, state) VALUES('sc1','w1','local','idle')")
        s.exec("INSERT INTO jobs(id, title, source, brief, created_at, u) VALUES('j1','t','user','b',1,1)")
        s.exec("INSERT INTO job_steps VALUES('j1', 1, 'step', 'done', 1)")
        try { s.exec("INSERT INTO routines(id, bot_id, name, prompt, kind, schedule, timezone, created_at, u) VALUES('r2','ghost','n','p','CRON','x','UTC',1,1)"); fail("orphan routine") }
        catch (_: Exception) { }

        s.exec("DELETE FROM bots WHERE id='manager'")          // the worker goes with its parent, and everything of the worker's
        assertEquals(0L, count(s, "bots")); assertEquals(0L, count(s, "bot_soul_versions")); assertEquals(0L, count(s, "bot_apps"))
        assertEquals(0L, count(s, "routines"))
        assertEquals(1L, count(s, "screens"))
        assertEquals(1L, s.query("SELECT bot_id IS NULL FROM screens") { it.long(0) }.first())   // a lease outlives its bot (SET NULL)
        s.exec("DELETE FROM jobs WHERE id='j1'")
        assertEquals(0L, count(s, "job_steps"))
    }

    @Test fun proactivityIsASingleRow() {
        val s = sql(); migrate(s)
        s.exec("INSERT INTO proactivity(id, enabled) VALUES(1, 1)")
        assertEquals(listOf("22:00-07:00", "3", "0", "[]"), s.query("SELECT quiet, daily_cap, sent_n, mutes FROM proactivity") { r -> (0..3).map { r.text(it) } }.first())
        try { s.exec("INSERT INTO proactivity(id, enabled) VALUES(2, 1)"); fail("second row") } catch (_: Exception) { }
    }

    @Test fun stepsAreSingleStatementsAndTheRangeIsChecked() {
        for ((i, step) in TeamSchema.MIGRATIONS.withIndex()) {
            assertTrue("v${i + 1} is empty", step.isNotEmpty())
            for (st in step) {
                assertFalse("one statement each, no ';': $st", st.contains(';'))
                assertTrue(st, st.startsWith("CREATE "))
            }
        }
        assertEquals(TeamSchema.MIGRATIONS.flatten(), TeamSchema.steps(0))
        assertEquals(TeamSchema.MIGRATIONS[1], TeamSchema.steps(1))
        try { TeamSchema.steps(TeamSchema.VERSION + 1); fail("newer file") } catch (_: TeamSchema.NewerSchema) { }
        try { TeamSchema.steps(2, 1); fail("backwards") } catch (_: IllegalArgumentException) { }
    }

    @Test fun aFailedStepLeavesTheOldVersion() {
        val s = sql(); migrate(s, to = 1)
        s.exec("CREATE TABLE x(y)")
        // a step that fails part-way (here: an index on a column that isn't there) rolls the whole upgrade back
        try { s.tx { for (st in TeamSchema.steps(1)) s.exec(st); s.exec("CREATE INDEX broken ON x(nope)"); s.exec("PRAGMA user_version = 2") }; fail("should fail") }
        catch (_: Exception) { }
        assertEquals(1, version(s))
        assertEquals(listOf("rows_updated"), names(s, "index"))
    }
}
