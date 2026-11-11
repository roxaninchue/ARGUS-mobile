package com.blaze.assistant.team

/**
 * P0-TM-01: team.db's schema as versioned migration steps. Pure (only SQL text), so the DDL is checked on the JVM
 * against a real SQLite (TeamSchemaTest, sqlite-jdbc) and applied on the phone by [TeamDb] (SQLiteOpenHelper,
 * TeamDbTest). team.db is Argus-only and kept out of blaze.db, whose schema is shared with Blaze Desktop / CLI.
 *
 * Rules for whoever adds a version:
 *  - Never edit a shipped step. Add a new list at the end of [MIGRATIONS]; its index + 1 is the new version.
 *  - One statement per string (Android's execSQL runs exactly one), no trailing ';'.
 *  - Steps only add (tables, columns with defaults, indexes); data moves are written so a re-run changes nothing.
 *  - The DB version is SQLite's user_version (SQLiteOpenHelper sets it after the steps commit in one transaction).
 *
 * v1 is the 05-agent-ecosystem.md §9.2 DDL as written: bots, bot_soul_versions, bot_apps, rows, jobs, job_steps,
 * bot_threads, routines, suggestions, proactivity, approvals, policies, spend_ledger, audit_log, screens.
 * v2 adds the read-path indexes the P1/P2 stores query by (job queue per bot, pending approvals, the activity log per
 * bot, open suggestions, due routines, bot-to-bot threads per pair, a parent's workers); no table changes.
 */
object TeamSchema {
    /** The file name, in the app's databases dir. */
    const val NAME = "team.db"

    /** Every table v1 creates (05 §9.2 order). */
    val TABLES = listOf("bots", "bot_soul_versions", "bot_apps", "rows", "jobs", "job_steps", "bot_threads", "routines",
        "suggestions", "proactivity", "approvals", "policies", "spend_ledger", "audit_log", "screens")

    private val V1 = listOf(
        "CREATE TABLE bots(" +
            "id TEXT PRIMARY KEY, kind TEXT NOT NULL, name TEXT NOT NULL, label TEXT NOT NULL DEFAULT '', domain TEXT, " +
            "avatar TEXT NOT NULL, voice TEXT, mode TEXT NOT NULL DEFAULT 'personal', " +
            "soul_version INTEGER NOT NULL DEFAULT 1, skills TEXT NOT NULL DEFAULT '[]', " +
            "tool_groups TEXT NOT NULL DEFAULT '[]', tool_grants TEXT NOT NULL DEFAULT '{}', " +
            "engine TEXT NOT NULL, computer TEXT NOT NULL, permissions TEXT NOT NULL, routing_hints TEXT NOT NULL DEFAULT '[]', " +
            "reports_to TEXT, parent_id TEXT REFERENCES bots(id) ON DELETE CASCADE, members TEXT NOT NULL DEFAULT '[]', " +
            "hidden INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1, " +
            "generation INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0, last_preview TEXT, last_activity_at INTEGER, " +
            "created_at INTEGER NOT NULL, u INTEGER NOT NULL)",
        "CREATE TABLE bot_soul_versions(bot_id TEXT NOT NULL REFERENCES bots(id) ON DELETE CASCADE, version INTEGER NOT NULL, " +
            "body TEXT NOT NULL, at INTEGER NOT NULL, by TEXT NOT NULL, PRIMARY KEY(bot_id, version))",
        "CREATE TABLE bot_apps(bot_id TEXT NOT NULL REFERENCES bots(id) ON DELETE CASCADE, app_id TEXT NOT NULL, source TEXT NOT NULL, " +
            "tools TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(bot_id, app_id))",

        "CREATE TABLE rows(bot_id TEXT NOT NULL, generation INTEGER NOT NULL, seq INTEGER NOT NULL, updated_seq INTEGER NOT NULL, " +
            "kind TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(bot_id, generation, seq))",
        "CREATE INDEX rows_updated ON rows(bot_id, updated_seq)",

        "CREATE TABLE jobs(id TEXT PRIMARY KEY, title TEXT NOT NULL, bot_id TEXT, due TEXT, priority TEXT NOT NULL DEFAULT 'normal', " +
            "status TEXT NOT NULL DEFAULT 'todo', run TEXT NOT NULL DEFAULT 'QUEUED', deps TEXT NOT NULL DEFAULT '[]', " +
            "parent_job TEXT, source TEXT NOT NULL, brief TEXT NOT NULL, result TEXT, notes TEXT NOT NULL DEFAULT '', " +
            "confirmed INTEGER NOT NULL DEFAULT 1, retry_at INTEGER, created_at INTEGER NOT NULL, done_at INTEGER, u INTEGER NOT NULL)",
        "CREATE TABLE job_steps(job_id TEXT NOT NULL REFERENCES jobs(id) ON DELETE CASCADE, seq INTEGER NOT NULL, label TEXT NOT NULL, " +
            "state TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(job_id, seq))",

        "CREATE TABLE bot_threads(id TEXT PRIMARY KEY, a TEXT NOT NULL, b TEXT NOT NULL, origin_job TEXT, depth INTEGER NOT NULL, " +
            "budget_left INTEGER NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL)",

        "CREATE TABLE routines(id TEXT PRIMARY KEY, bot_id TEXT NOT NULL REFERENCES bots(id) ON DELETE CASCADE, name TEXT NOT NULL, " +
            "prompt TEXT NOT NULL, kind TEXT NOT NULL, schedule TEXT NOT NULL, timezone TEXT NOT NULL, runs_in TEXT NOT NULL DEFAULT 'phone', " +
            "enabled INTEGER NOT NULL DEFAULT 1, silent_if_nothing INTEGER NOT NULL DEFAULT 1, silent_streak INTEGER NOT NULL DEFAULT 0, " +
            "last_run_at INTEGER, next_run_at INTEGER, created_at INTEGER NOT NULL, u INTEGER NOT NULL)",

        "CREATE TABLE suggestions(id TEXT PRIMARY KEY, bot_id TEXT NOT NULL, title TEXT NOT NULL, why TEXT NOT NULL, task TEXT NOT NULL, " +
            "topic TEXT NOT NULL, urgency INTEGER NOT NULL, risk TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL)",
        "CREATE TABLE proactivity(id INTEGER PRIMARY KEY CHECK (id = 1), enabled INTEGER NOT NULL, quiet TEXT NOT NULL DEFAULT '22:00-07:00', " +
            "daily_cap INTEGER NOT NULL DEFAULT 3, sent_day TEXT, sent_n INTEGER NOT NULL DEFAULT 0, mutes TEXT NOT NULL DEFAULT '[]')",

        "CREATE TABLE approvals(id TEXT PRIMARY KEY, bot_id TEXT NOT NULL, job_id TEXT, call_id TEXT NOT NULL, tool TEXT NOT NULL, " +
            "action_class TEXT NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL, args_redacted TEXT NOT NULL, tainted INTEGER NOT NULL, " +
            "status TEXT NOT NULL DEFAULT 'pending', on_device_only INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, " +
            "resolved_at INTEGER, resolved_by TEXT, u INTEGER NOT NULL)",
        "CREATE TABLE policies(tool TEXT NOT NULL, bot_id TEXT NOT NULL DEFAULT '*', rule TEXT NOT NULL, set_by TEXT NOT NULL, " +
            "at INTEGER NOT NULL, PRIMARY KEY(tool, bot_id))",
        "CREATE TABLE spend_ledger(id TEXT PRIMARY KEY, bot_id TEXT NOT NULL, approval_id TEXT NOT NULL, amount_minor INTEGER NOT NULL, " +
            "currency TEXT NOT NULL, merchant TEXT NOT NULL, at INTEGER NOT NULL)",

        "CREATE TABLE audit_log(id INTEGER PRIMARY KEY, at INTEGER NOT NULL, bot_id TEXT NOT NULL, chain TEXT NOT NULL, " +
            "trigger TEXT NOT NULL, tool TEXT NOT NULL, action_class TEXT NOT NULL, args_redacted TEXT NOT NULL, decision TEXT NOT NULL, " +
            "decided_by TEXT, device TEXT, result TEXT, undo TEXT, cost_minor INTEGER, prev_hash TEXT NOT NULL, hash TEXT NOT NULL)",

        "CREATE TABLE screens(id TEXT PRIMARY KEY, bot_id TEXT REFERENCES bots(id) ON DELETE SET NULL, provider TEXT NOT NULL, " +
            "display INTEGER, cdp_port INTEGER, state TEXT NOT NULL, leased_at INTEGER, last_used_at INTEGER)",
    )

    private val V2 = listOf(
        "CREATE INDEX IF NOT EXISTS bots_parent ON bots(parent_id)",
        "CREATE INDEX IF NOT EXISTS jobs_bot_run ON jobs(bot_id, run)",
        "CREATE INDEX IF NOT EXISTS jobs_parent ON jobs(parent_job)",
        "CREATE INDEX IF NOT EXISTS bot_threads_pair ON bot_threads(a, b)",
        "CREATE INDEX IF NOT EXISTS routines_due ON routines(enabled, next_run_at)",
        "CREATE INDEX IF NOT EXISTS suggestions_state ON suggestions(state, created_at)",
        "CREATE INDEX IF NOT EXISTS approvals_status ON approvals(status, created_at)",
        "CREATE INDEX IF NOT EXISTS audit_bot ON audit_log(bot_id, at)",
    )

    /** MIGRATIONS[n] takes the file from version n to n + 1. */
    val MIGRATIONS: List<List<String>> = listOf(V1, V2)

    /** The version this build writes. */
    val VERSION: Int get() = MIGRATIONS.size

    /** Every index v1 + v2 create (rows_updated is 05 §9.2's own). */
    val INDEXES = listOf("rows_updated", "bots_parent", "jobs_bot_run", "jobs_parent", "bot_threads_pair", "routines_due",
        "suggestions_state", "approvals_status", "audit_bot")

    class NewerSchema(v: Int) : IllegalStateException("team.db was written by a newer build (schema v$v, this build knows v$VERSION)")

    /** The statements that take a file at version [from] to [to], in order. Throws [NewerSchema] for a file newer than this build. */
    fun steps(from: Int, to: Int = VERSION): List<String> {
        if (from > VERSION) throw NewerSchema(from)
        require(from >= 0 && to in from..VERSION) { "bad migration range $from → $to" }
        return MIGRATIONS.subList(from, to).flatten()
    }
}
