package com.blaze.assistant.policy

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper
import com.blaze.assistant.team.ActionClass

/**
 * P2-SF-05 Android side: `audit_log` + `spend_ledger` in team.db (tables created by P0-TM-01 `TeamDb` from the 05 §9.2
 * DDL; this file never creates or alters them). Pass the TeamDb instance (it is a SQLiteOpenHelper) and a [HeadStore];
 * the chain logic is [Audit] (pure, AuditChainTest), this is only reads and writes.
 *
 * One writer: [append] and [recordSpend] are synchronized and run in a transaction, so two bots deciding at once still
 * get consecutive ids and a single unbroken chain. The newest (id, hash) is mirrored to [HeadStore] (SharedPreferences,
 * outside team.db) after each commit, so [verify] also sees rows cut off the end.
 *
 * Hook: the team bootstrap calls `AuditDao.install(teamDb, context)` once; Policy / ConfirmGate / the cards then call
 * `AuditDao.shared?.append(...)`, and the Activity page reads `AuditDao.shared`. Checked by hand (no Robolectric run
 * without "build").
 */
class AuditDao(private val helper: SQLiteOpenHelper, private val head: HeadStore) {

    private val cols = "id, at, bot_id, chain, trigger, tool, action_class, args_redacted, decision, decided_by, device, result, undo, cost_minor, prev_hash, hash"

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun Cursor.entry(): AuditEntry = AuditEntry(
        id = getLong(0), at = getLong(1), botId = getString(2), chain = Audit.chainOf(getString(3)), trigger = getString(4),
        tool = getString(5), actionClass = runCatching { ActionClass.valueOf(getString(6)) }.getOrDefault(ActionClass.WRITE_LOCAL),
        argsRedacted = getString(7), decision = Decision.of(getString(8)), decidedBy = str(9), device = str(10), result = str(11),
        undo = str(12), costMinor = if (isNull(13)) null else getLong(13), prevHash = getString(14), hash = getString(15))

    /** The newest stored row, or null for an empty log. */
    fun last(): AuditEntry? = helper.readableDatabase.rawQuery("SELECT $cols FROM audit_log ORDER BY id DESC LIMIT 1", null).use { c ->
        if (c.moveToFirst()) c.entry() else null
    }

    /**
     * Seal [ev] onto the end of the chain and store it. Returns the stored row, or null when [Audit.shouldRecord] says the
     * call is a plain read that ran on its own (05 §7.6 logs non-read actions and every decision).
     */
    @Synchronized
    fun append(ev: AuditEvent): AuditEntry? {
        if (!Audit.shouldRecord(ev.actionClass, ev.decision)) return null
        val db = helper.writableDatabase
        val row: AuditEntry
        db.beginTransaction()
        try {
            val prev = db.rawQuery("SELECT $cols FROM audit_log ORDER BY id DESC LIMIT 1", null).use { c -> if (c.moveToFirst()) c.entry() else null }
            row = Audit.append(prev, ev)
            val v = ContentValues().apply {
                put("id", row.id); put("at", row.at); put("bot_id", row.botId); put("chain", Audit.chainText(row.chain))
                put("trigger", row.trigger); put("tool", row.tool); put("action_class", row.actionClass.name)
                put("args_redacted", row.argsRedacted); put("decision", row.decision.id); put("decided_by", row.decidedBy)
                put("device", row.device); put("result", row.result); put("undo", row.undo); put("cost_minor", row.costMinor)
                put("prev_hash", row.prevHash); put("hash", row.hash)
            }
            db.insertOrThrow("audit_log", null, v)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        head.write(Head(row.id, row.hash))
        return row
    }

    /** Newest first, filtered; [beforeId] pages back (rows with a smaller id). [Audit.Filter.botId] also matches the chain. */
    fun list(filter: Audit.Filter = Audit.Filter(), limit: Int = 100, beforeId: Long? = null): List<AuditEntry> {
        val where = ArrayList<String>(); val args = ArrayList<String>()
        if (beforeId != null) { where += "id < ?"; args += beforeId.toString() }
        filter.botId?.let { where += "(bot_id = ? OR ('>' || chain || '>') LIKE ?)"; args += it; args += "%>$it>%" }
        if (filter.decisions.isNotEmpty()) { where += "decision IN (${filter.decisions.joinToString(",") { "?" }})"; args += filter.decisions.map { it.id } }
        if (filter.classes.isNotEmpty()) { where += "action_class IN (${filter.classes.joinToString(",") { "?" }})"; args += filter.classes.map { it.name } }
        filter.sinceMs?.let { where += "at >= ?"; args += it.toString() }
        val sql = "SELECT $cols FROM audit_log" + (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY id DESC"
        // The free-text query runs in Kotlin (same rule as the pure filter), so read in pages until [limit] match.
        val out = ArrayList<AuditEntry>()
        helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (out.size < limit && c.moveToNext()) { val e = c.entry(); if (filter.matches(e)) out += e }
        }
        return out
    }

    /** Every row, oldest first (verify, export). */
    fun all(): List<AuditEntry> = helper.readableDatabase.rawQuery("SELECT $cols FROM audit_log ORDER BY id ASC", null).use { c ->
        val out = ArrayList<AuditEntry>(c.count.coerceAtLeast(0)); while (c.moveToNext()) out += c.entry(); out
    }

    /** Check the whole chain against the head kept outside team.db. */
    fun verify(): ChainCheck = verify(all())

    /** Check rows already read with [all] (export reads once and checks the same list). */
    fun verify(all: List<AuditEntry>): ChainCheck = Audit.verify(all, head.read())

    /** Bots that appear in the log (for the Activity page's per-bot chips), most recent first. */
    fun bots(): List<String> = helper.readableDatabase.rawQuery(
        "SELECT bot_id, MAX(id) m FROM audit_log GROUP BY bot_id ORDER BY m DESC", null).use { c ->
        val out = ArrayList<String>(); while (c.moveToNext()) out += c.getString(0); out
    }

    // ---- spend_ledger ---------------------------------------------------------------------------------------------

    @Synchronized
    fun recordSpend(e: SpendEntry) {
        val v = ContentValues().apply {
            put("id", e.id); put("bot_id", e.botId); put("approval_id", e.approvalId); put("amount_minor", e.amountMinor)
            put("currency", e.currency.uppercase()); put("merchant", e.merchant); put("at", e.at)
        }
        helper.writableDatabase.insertOrThrow("spend_ledger", null, v)
    }

    /** What [botId] (null = team) spent in [currency] this calendar month in [zone] — feeds [SpendGuard.check]. */
    fun monthSpent(botId: String?, currency: String, nowMs: Long, zone: String): Long {
        val (s, e) = SpendMonth.bounds(nowMs, zone)
        val sql = "SELECT COALESCE(SUM(amount_minor), 0) FROM spend_ledger WHERE UPPER(currency) = ? AND at >= ? AND at < ?" +
            if (botId != null) " AND bot_id = ?" else ""
        val args = arrayListOf(currency.uppercase(), s.toString(), e.toString()).apply { if (botId != null) add(botId) }
        return helper.readableDatabase.rawQuery(sql, args.toTypedArray()).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    fun spends(botId: String? = null, limit: Int = 200): List<SpendEntry> {
        val sql = "SELECT id, bot_id, approval_id, amount_minor, currency, merchant, at FROM spend_ledger" +
            (if (botId != null) " WHERE bot_id = ?" else "") + " ORDER BY at DESC LIMIT $limit"
        return helper.readableDatabase.rawQuery(sql, if (botId != null) arrayOf(botId) else null).use { c ->
            val out = ArrayList<SpendEntry>()
            while (c.moveToNext()) out += SpendEntry(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5), c.getLong(6))
            out
        }
    }

    companion object {
        @Volatile var shared: AuditDao? = null
            private set

        /** Call once from the team bootstrap with P0's TeamDb (a SQLiteOpenHelper). Idempotent. */
        fun install(teamDb: SQLiteOpenHelper, context: Context): AuditDao =
            shared ?: synchronized(this) { shared ?: AuditDao(teamDb, PrefsHeadStore(context.applicationContext)).also { shared = it } }
    }
}

/** The chain head in SharedPreferences ("argus_audit"), i.e. outside team.db. */
class PrefsHeadStore(context: Context) : HeadStore {
    private val prefs = context.getSharedPreferences("argus_audit", Context.MODE_PRIVATE)
    override fun read(): Head? = Head.decode(prefs.getString("head", null))
    override fun write(head: Head) { prefs.edit().putString("head", head.encode()).commit() }
}
