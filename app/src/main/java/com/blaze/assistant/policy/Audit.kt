package com.blaze.assistant.policy

import com.blaze.assistant.agent.PasteGuard
import java.security.MessageDigest

/**
 * P2-SF-05: the audit log (05 §7.1, team.db `audit_log`): one row for every non-read tool call the policy decided, with
 * what was decided (allowed, asked, refused, approved, denied, stopped), the call's args with every secret-looking value
 * cut out ([redact], through [PasteGuard]'s detector), and how the call ended. Rows are hash-chained: each row's [Entry.hash]
 * is sha256(prevHash + its canonical fields), so changing a field, deleting a row in the middle or reordering rows breaks
 * the chain at that row ([verify]). Nothing here is shown to the model.
 *
 * Pure Kotlin (no android.*): AuditChainTest. team.db reads and writes are in [AuditStore] (AuditStore.kt).
 */
object Audit {
    /** What the policy did with the call. ASK = asked in shadow (the call ran as before), the rest are final. */
    enum class Decision { ALLOW, ASK, DENY, APPROVED, DENIED, STOPPED }

    /** The first row's prevHash. */
    val GENESIS: String = "0".repeat(64)

    /** Longest args summary a row keeps. */
    const val ARGS_MAX = 300

    /** A row before it is chained: what [append] needs. */
    data class Draft(
        val at: Long, val botId: String, val chain: List<String> = emptyList(), val trigger: String = "user", val tool: String,
        val actionClass: String, val args: String, val decision: Decision, val decidedBy: String? = null, val result: String? = null,
        val costMinor: Long? = null,
    )

    /** One chained row. [seq] is its position (team.db `audit_log.id`), 1-based and gap-free in a whole chain. */
    data class Entry(
        val seq: Long, val at: Long, val botId: String, val chain: List<String>, val trigger: String, val tool: String,
        val actionClass: String, val argsRedacted: String, val decision: Decision, val decidedBy: String?, val result: String?,
        val costMinor: Long?, val prevHash: String, val hash: String,
    )

    /**
     * [args] with every secret-looking value replaced by `[label]` ("[GitHub token]", "[Card]", "[Password]"), saved
     * vault handles kept as they are, then cut to [ARGS_MAX]. Redaction runs before the cut, so a value at the edge
     * can't half-survive.
     */
    fun redact(args: String): String {
        val t = args.take(8_000)
        val hits = try { PasteGuard.detect(t) } catch (_: Throwable) { emptyList() }
        val out = if (hits.isEmpty()) t else PasteGuard.redact(t, hits, hits.map { "[${it.label}]" })
        return if (out.length <= ARGS_MAX) out else out.take(ARGS_MAX - 1) + "…"
    }

    /** The fields a row's hash covers, length-prefixed so no two different rows read the same. */
    fun canonical(seq: Long, at: Long, botId: String, chain: List<String>, trigger: String, tool: String, actionClass: String,
                  argsRedacted: String, decision: Decision, decidedBy: String?, result: String?, costMinor: Long?): String {
        val sb = StringBuilder()
        fun f(v: Any?) { val s = v?.toString() ?: "\u0000"; sb.append(s.length).append(':').append(s).append('|') }
        f(seq); f(at); f(botId); f(chain.joinToString(",")); f(trigger); f(tool); f(actionClass); f(argsRedacted)
        f(decision.name); f(decidedBy); f(result); f(costMinor)
        return sb.toString()
    }

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun hashOf(e: Entry): String = sha256(e.prevHash + canonical(e.seq, e.at, e.botId, e.chain, e.trigger, e.tool, e.actionClass,
        e.argsRedacted, e.decision, e.decidedBy, e.result, e.costMinor))

    /** The row that follows [prev] (null = the first row): args redacted, chained to it. */
    fun append(prev: Entry?, d: Draft): Entry {
        val seq = (prev?.seq ?: 0) + 1
        // empty decidedBy / result are stored as null (team.db reads NULL and '' alike)
        val partial = Entry(seq, d.at, d.botId, d.chain, d.trigger, d.tool, d.actionClass, redact(d.args), d.decision, d.decidedBy?.ifEmpty { null },
            d.result?.take(40)?.ifEmpty { null }, d.costMinor, prev?.hash ?: GENESIS, "")
        return partial.copy(hash = hashOf(partial))
    }

    /**
     * The index (0-based, in [entries] order) of the first row that doesn't hold: its hash doesn't match its fields, it
     * doesn't point at the row before it, or its seq isn't the next one. -1 = the whole chain verifies. [entries] must be
     * the whole log in seq order from the first row (a chain whose first row isn't seq 1 on GENESIS is broken at 0).
     * Deleting the last rows can't be seen from the rows alone; the store keeps the row count to compare (see AuditStore).
     */
    fun verify(entries: List<Entry>): Int {
        var prevHash = GENESIS; var prevSeq = 0L
        entries.forEachIndexed { i, e ->
            if (e.prevHash != prevHash || e.seq != prevSeq + 1 || hashOf(e) != e.hash) return i
            prevHash = e.hash; prevSeq = e.seq
        }
        return -1
    }

    /** Short result status of a tool's output: "ok", "error" (it says so), or "not_run" when the policy stopped it. */
    fun resultOf(output: String?, ran: Boolean): String = when {
        !ran -> "not_run"
        output == null -> "error"
        output.trimStart().startsWith("Error", ignoreCase = true) -> "error"
        else -> "ok"
    }

    // ---------------- spend_ledger ----------------

    /** A payment's amount as the tool call states it: `amount_minor` (whole minor units) or `amount` (major units). */
    data class Amount(val minor: Long, val currency: String?, val merchant: String)

    /**
     * Reads the amount a SPEND call carries in its JSON args (`amount_minor`, else `amount` × 100), with `currency` and
     * `merchant` / `payee` / `to` when given. Null when the args name no amount (run_ussd today), so the policy's card
     * shows no limit line and nothing is written to the ledger.
     */
    fun amountOf(args: String): Amount? {
        val o = try { org.json.JSONObject(args) } catch (_: Throwable) { return null }
        val minor = when {
            o.has("amount_minor") -> o.optString("amount_minor").trim().toLongOrNull()
            o.has("amount") -> o.optString("amount").trim().toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.toLong()
            else -> null
        } ?: return null
        if (minor < 0) return null
        val cur = o.optString("currency").trim().uppercase().takeIf { it.length in 3..4 }
        val merchant = listOf("merchant", "payee", "to").firstNotNullOfOrNull { k -> o.optString(k).trim().takeIf { it.isNotEmpty() } }.orEmpty()
        return Amount(minor, cur, redact(merchant).take(80))
    }
}
