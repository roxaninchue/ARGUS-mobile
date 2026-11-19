package com.argus.assistant.policy

import com.argus.assistant.store.Sql

/**
 * P2-SF-05: team.db `audit_log` and `spend_ledger` (TeamSchema v1, no new columns). [append] chains a row onto the last
 * one under one lock and one transaction, so two tool calls finishing together can't fork the chain. `chain` keeps the
 * origin bot chain; the team a bot belongs to is read from `bots.parent_id` when the Activity page groups rows.
 */
class AuditStore(private val sql: Sql, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    fun append(d: Audit.Draft): Audit.Entry? = synchronized(lock) {
        sql.tx {
            val prev = last()
            val e = Audit.append(prev, d)
            sql.exec("INSERT INTO audit_log(id, at, bot_id, chain, trigger, tool, action_class, args_redacted, decision, decided_by, device, " +
                "result, undo, cost_minor, prev_hash, hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                e.seq, e.at, e.botId, e.chain.joinToString(","), e.trigger, e.tool, e.actionClass, e.argsRedacted, e.decision.name,
                e.decidedBy, "phone", e.result, null, e.costMinor, e.prevHash, e.hash)
            e
        }
    }

    private fun last(): Audit.Entry? = read("ORDER BY id DESC LIMIT 1").firstOrNull()

    /** The whole log in chain order (Activity page; [Audit.verify] needs every row). */
    fun all(): List<Audit.Entry> = read("ORDER BY id")

    private fun read(tail: String): List<Audit.Entry> = sql.query(
        "SELECT id, at, bot_id, chain, trigger, tool, action_class, args_redacted, decision, COALESCE(decided_by, ''), " +
            "COALESCE(result, ''), COALESCE(cost_minor, -1), cost_minor IS NULL, prev_hash, hash FROM audit_log $tail") { r ->
        Audit.Entry(r.long(0), r.long(1), r.text(2), r.text(3).split(',').filter { it.isNotEmpty() }, r.text(4), r.text(5), r.text(6),
            r.text(7), Audit.Decision.values().firstOrNull { it.name == r.text(8) } ?: Audit.Decision.STOPPED,
            r.text(9).ifEmpty { null }, r.text(10).ifEmpty { null }, if (r.long(12) != 0L) null else r.long(11),
            r.text(13), r.text(14))
    }

    // ---------------- spend_ledger ----------------

    /** One completed, approved payment. */
    fun spend(botId: String, approvalId: String, amountMinor: Long, currency: String, merchant: String, at: Long = clock()) {
        sql.exec("INSERT OR IGNORE INTO spend_ledger(id, bot_id, approval_id, amount_minor, currency, merchant, at) VALUES(?,?,?,?,?,?,?)",
            "sp_$approvalId", botId, approvalId, amountMinor, currency, merchant, at)
    }

    fun ledger(): List<SpendGuard.Entry> = sql.query("SELECT bot_id, amount_minor, currency, at FROM spend_ledger") { r ->
        SpendGuard.Entry(r.text(0), r.long(1), r.text(2), r.long(3)) }
}
