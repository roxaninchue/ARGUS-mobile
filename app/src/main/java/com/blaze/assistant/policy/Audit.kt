package com.blaze.assistant.policy

import com.blaze.assistant.agent.PasteGuard
import com.blaze.assistant.team.ActionClass
import java.security.MessageDigest

/**
 * P2-SF-05 (05-agent-ecosystem.md §7.6): the append-only, hash-chained audit log, pure Kotlin (no android.*).
 *
 * One [AuditEntry] per non-read action and per decision: who (bot, parent chain, trigger), what (tool, args summary with
 * secrets redacted by the existing SecretGuards [PasteGuard] plus a secret-named-key pass), the decision (auto / allowed /
 * always / denied / expired / skipped, by whom, from which device), the result, the undo handle and the cost.
 *
 * Every row stores `prev_hash` (the previous row's `hash`, [GENESIS] for row 1) and its own `hash` = SHA-256 over a
 * length-prefixed canonical form of every other column, so an edited, deleted, reordered or inserted row breaks the chain
 * ([verify]). Cutting rows off the END cannot be seen from the rows alone, so the newest (id, hash) is also kept outside
 * team.db ([Head], [HeadStore]); [verify] with a head catches truncation and a rewritten tail.
 *
 * Columns match the 05 §9.2 `audit_log` DDL (team.db, created by P0-TM-01 TeamDb); [AuditDao] is the Android side.
 * AuditChainTest runs on the JVM.
 */

/** How a call was decided. Stored lower-case in `audit_log.decision` (same words as `approvals.status`). */
enum class Decision(val id: String, val label: String) {
    AUTO("auto", "Done automatically"),
    ALLOWED("allowed", "Allowed"),
    ALWAYS("always", "Always allowed"),
    DENIED("denied", "Denied"),
    EXPIRED("expired", "Expired"),
    SKIPPED("skipped", "Skipped");

    companion object {
        fun of(id: String?): Decision = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: AUTO
        /** Decisions a person (or a standing rule they set) made, as opposed to the policy running on its own. */
        val NEEDED_YOU = setOf(ALLOWED, ALWAYS, DENIED, EXPIRED, SKIPPED)
    }
}

/** What a caller hands to [Audit.append]: everything but the row id and the two hashes. Text is redacted on append. */
data class AuditEvent(
    val at: Long,
    val botId: String,
    /** Parent bots, outermost first (manager → money → scout means scout acted for money for the manager). */
    val chain: List<String> = emptyList(),
    /** user | routine | checkin | suggestion | bot | mcp | remote. */
    val trigger: String,
    val tool: String,
    val actionClass: ActionClass,
    /** Raw args (JSON or text). Redacted and summarised by [Audit.redactArgs] before it is stored. */
    val args: String,
    val decision: Decision,
    /** user | policy | autopilot | rule:<tool> | <bot id>; null = not recorded. */
    val decidedBy: String? = null,
    /** The device the decision came from ("Pixel 7", "web", a sync device id). */
    val device: String? = null,
    val result: String? = null,
    /** UndoCenter handle, when the action can be undone. */
    val undo: String? = null,
    val costMinor: Long? = null,
)

/** One stored `audit_log` row. [argsRedacted] and [result] are already redacted. */
data class AuditEntry(
    val id: Long,
    val at: Long,
    val botId: String,
    val chain: List<String>,
    val trigger: String,
    val tool: String,
    val actionClass: ActionClass,
    val argsRedacted: String,
    val decision: Decision,
    val decidedBy: String?,
    val device: String?,
    val result: String?,
    val undo: String?,
    val costMinor: Long?,
    val prevHash: String,
    val hash: String,
)

/** The newest row's id and hash, kept outside team.db so cutting rows off the end is visible. */
data class Head(val id: Long, val hash: String) {
    fun encode() = "$id:$hash"
    companion object {
        fun decode(s: String?): Head? {
            val p = s?.split(':', limit = 2) ?: return null
            if (p.size != 2) return null
            val id = p[0].toLongOrNull() ?: return null
            return if (p[1].length == 64) Head(id, p[1]) else null
        }
    }
}

/** Where the [Head] lives (SharedPreferences on the phone, a field in tests). */
interface HeadStore {
    fun read(): Head?
    fun write(head: Head)
}

/** Outcome of [Audit.verify]. [badIndex] = list index of the first bad row (-1 when ok or when only the head is wrong). */
data class ChainCheck(val ok: Boolean, val reason: Reason, val badIndex: Int = -1, val badId: Long? = null) {
    enum class Reason { OK, BAD_HASH, BAD_GENESIS, BROKEN_LINK, BAD_SEQUENCE, TRUNCATED, HEAD_MISMATCH }

    /** One line for the Activity page. */
    fun line(): String = when (reason) {
        Reason.OK -> "History intact"
        Reason.BAD_HASH -> "Row ${badId ?: "?"} was changed after it was written"
        Reason.BAD_GENESIS -> "The first rows of the history are missing"
        Reason.BROKEN_LINK -> "History broken at row ${badId ?: "?"}: a row was removed, moved or added"
        Reason.BAD_SEQUENCE -> "History broken at row ${badId ?: "?"}: row numbers skip or repeat"
        Reason.TRUNCATED -> "The newest rows of the history were removed"
        Reason.HEAD_MISMATCH -> "The newest rows of the history were rewritten"
    }

    companion object { val OK = ChainCheck(true, Reason.OK) }
}

object Audit {
    /** prev_hash of row 1. */
    val GENESIS: String = "0".repeat(64)

    /** Longest stored args summary / result, after redaction. */
    const val MAX_ARGS = 500
    const val MAX_RESULT = 300
    /** Raw text looked at before redaction (PasteGuard reads at most 20 000 chars). */
    private const val MAX_RAW = 4000

    /** 05 §7.6: every non-read action and every decision. A READ that ran on its own is not logged. */
    fun shouldRecord(actionClass: ActionClass, decision: Decision): Boolean =
        !(actionClass == ActionClass.READ && decision == Decision.AUTO)

    // ---- redaction ------------------------------------------------------------------------------------------------

    const val REDACTED = "[redacted]"

    // Key words that mark a value as secret, matched on whole key parts ("x-api-key" → x, api, key; "cardNumber" → card, number).
    private val secretParts = setOf("password", "passwd", "pwd", "pass", "passcode", "passphrase", "secret", "token", "pin", "otp",
        "cvc", "cvv", "cvv2", "csc", "iban", "mnemonic", "seed", "cookie", "authorization", "auth", "bearer", "credential", "credentials",
        "privatekey", "apikey", "sessionid")
    // Joined (separators dropped, lower case) key names that are secret as a whole.
    private val secretJoined = setOf("apikey", "xapikey", "accesskey", "secretkey", "privatekey", "accesstoken", "refreshtoken", "idtoken",
        "clientsecret", "cardnumber", "cardno", "ccnumber", "creditcard", "debitcard", "accountnumber", "routingnumber", "seedphrase",
        "recoveryphrase", "securitycode", "setcookie", "onetimecode", "verificationcode")

    /** True when a field called [key] holds a secret (password, api_key, cardNumber, X-Api-Key, cvc …). */
    fun secretKey(key: String): Boolean {
        val k = key.trim().trim('"', '\'')
        if (k.isEmpty()) return false
        val parts = k.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (parts.any { it in secretParts }) return true
        val joined = parts.joinToString("")
        return joined in secretJoined || secretJoined.any { joined.endsWith(it) }
    }

    private val jsonStr = Regex(""""([A-Za-z0-9_.\-]{1,64})"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    // The same inside a JSON string that holds JSON ({\"password\":\"x\"}), common in tool-call arguments.
    private val jsonEscStr = Regex("""\\"([A-Za-z0-9_.\-]{1,64})\\"\s*:\s*\\"((?:[^"\\]|\\[^"])*)\\"""")
    private val jsonNum = Regex(""""([A-Za-z0-9_.\-]{1,64})"\s*:\s*(-?[\d][\d .\-]*)""")
    private val kv = Regex("""(?<![A-Za-z0-9_\-])([A-Za-z][A-Za-z0-9_.\-]{0,63})(\s*[=:](?!//)\s*)([^\s&,;"'{}\[\]]{1,512})""")
    private val bearer = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=\-]{12,}""")

    /** A Secure Form handle ({{secret:x}}) is safe to keep: it names the secret, never holds it. */
    private const val PARKED_R = "`\u2063R\u2063`"
    private val parkedRe = Regex("\u2063H(\\d{1,6})\u2063")
    private fun handle(v: String) = parkedRe.matches(v.trim()) || com.blaze.assistant.agent.SecretVault.HANDLE.matches(v.trim())

    /**
     * [raw] with every secret removed: values of secret-named keys (JSON, key=value, key: value) become [REDACTED],
     * "Bearer …" tokens go, then the SecretGuards paste detector ([PasteGuard.detect]: token formats, Luhn card numbers,
     * "my password is …", high-entropy strings) replaces each hit with "[redacted <label>]". Handles are kept.
     */
    fun redact(raw: String): String {
        // Park Secure Form handles under short placeholders so no pass below can eat them, and put them back at the end.
        val parked = ArrayList<String>()
        var t = com.blaze.assistant.agent.SecretVault.HANDLE.replace(raw) { m -> parked += m.value; "\u2063H${parked.size - 1}\u2063" }
        t = bearer.replace(t) { m -> m.groupValues[1] + " " + REDACTED }
        t = jsonEscStr.replace(t) { m -> if (secretKey(m.groupValues[1]) && !handle(m.groupValues[2])) "\\\"${m.groupValues[1]}\\\":\\\"$REDACTED\\\"" else m.value }
        t = jsonStr.replace(t) { m -> if (secretKey(m.groupValues[1]) && !handle(m.groupValues[2])) "\"${m.groupValues[1]}\":\"$REDACTED\"" else m.value }
        t = jsonNum.replace(t) { m -> if (secretKey(m.groupValues[1])) "\"${m.groupValues[1]}\":\"$REDACTED\"" else m.value }
        t = kv.replace(t) { m ->
            val v = m.groupValues[3]
            if (secretKey(m.groupValues[1]) && v != REDACTED && !handle(v) && !v.startsWith("[redacted")) m.groupValues[1] + m.groupValues[2] + REDACTED else m.value
        }
        // PasteGuard's "token=X" rule would read "[redacted]&page=2" as a value; a backticked marker stops it (and is put back).
        t = t.replace(REDACTED, PARKED_R)
        val hits = PasteGuard.detect(t)
        if (hits.isNotEmpty()) t = PasteGuard.redact(t, hits, hits.map { "[redacted ${it.label.lowercase()}]" })
        t = t.replace(PARKED_R, REDACTED)
        return parkedRe.replace(t) { m -> parked.getOrNull(m.groupValues[1].toInt()) ?: m.value }
    }

    /** Redact first (on at most [MAX_RAW] chars), then cut to [max] chars, so a cut never leaves half a secret behind. */
    fun redactArgs(raw: String, max: Int = MAX_ARGS): String {
        val r = redact(raw.take(MAX_RAW)).replace(Regex("\\s+"), " ").trim()
        return if (r.length <= max) r else r.take(max - 1).trimEnd() + "…"
    }

    // ---- chain ----------------------------------------------------------------------------------------------------

    /** Length-prefixed canonical form of every column but `hash` (null ≠ ""), so no two rows share one. */
    fun canonical(e: AuditEntry): String {
        val sb = StringBuilder()
        fun f(v: String?) { if (v == null) sb.append("-1:") else sb.append(v.length).append(':').append(v); sb.append('|') }
        f("v1"); f(e.id.toString()); f(e.at.toString()); f(e.botId); f(chainText(e.chain)); f(e.trigger); f(e.tool)
        f(e.actionClass.name); f(e.argsRedacted); f(e.decision.id); f(e.decidedBy); f(e.device); f(e.result); f(e.undo)
        f(e.costMinor?.toString()); f(e.prevHash)
        return sb.toString()
    }

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun hashOf(e: AuditEntry): String = sha256(canonical(e))

    /** The `audit_log.chain` column: parent bot ids joined by '>' (bot ids never contain it). */
    fun chainText(chain: List<String>): String = chain.joinToString(">")
    fun chainOf(text: String?): List<String> = text.orEmpty().split('>').map { it.trim() }.filter { it.isNotEmpty() }

    /** The row that follows [prev] (null = first row): id + 1, prev_hash = prev.hash, text redacted, hash sealed. */
    fun append(prev: AuditEntry?, ev: AuditEvent): AuditEntry {
        val unsealed = AuditEntry(
            id = (prev?.id ?: 0L) + 1, at = ev.at, botId = ev.botId.trim(), chain = ev.chain.map { it.trim() }.filter { it.isNotEmpty() },
            trigger = ev.trigger.trim(), tool = ev.tool.trim(), actionClass = ev.actionClass, argsRedacted = redactArgs(ev.args),
            decision = ev.decision, decidedBy = ev.decidedBy?.trim(), device = ev.device?.trim(),
            result = ev.result?.let { redactArgs(it, MAX_RESULT) }, undo = ev.undo, costMinor = ev.costMinor,
            prevHash = prev?.hash ?: GENESIS, hash = "")
        return unsealed.copy(hash = hashOf(unsealed))
    }

    /** Append to the end of an in-memory list (tests, previews). */
    fun appendTo(log: List<AuditEntry>, ev: AuditEvent): List<AuditEntry> = log + append(log.lastOrNull(), ev)

    /**
     * Check [entries] (oldest first). By default the list must start at row 1; to check a page, pass the row before it as
     * [after]. With [head] (the newest row recorded outside team.db) a removed or rewritten tail is caught too.
     */
    fun verify(entries: List<AuditEntry>, head: Head? = null, after: AuditEntry? = null): ChainCheck {
        var prevHash = after?.hash ?: GENESIS
        var prevId = after?.id ?: 0L
        entries.forEachIndexed { i, e ->
            if (hashOf(e) != e.hash) return ChainCheck(false, ChainCheck.Reason.BAD_HASH, i, e.id)
            if (e.prevHash != prevHash) {
                val r = if (i == 0 && after == null) ChainCheck.Reason.BAD_GENESIS else ChainCheck.Reason.BROKEN_LINK
                return ChainCheck(false, r, i, e.id)
            }
            if (e.id != prevId + 1) return ChainCheck(false, ChainCheck.Reason.BAD_SEQUENCE, i, e.id)
            prevHash = e.hash; prevId = e.id
        }
        if (head != null) {
            val last = entries.lastOrNull()
            val lastId = last?.id ?: after?.id ?: 0L
            val lastHash = last?.hash ?: after?.hash ?: GENESIS
            if (lastId < head.id) return ChainCheck(false, ChainCheck.Reason.TRUNCATED, -1, lastId + 1)
            // The head may lag the table by a row or two (the app died between the commit and the head write): fine as
            // long as the row it names is still there, unchanged. Rows after it are covered by the chain itself.
            val named = if (lastId == head.id) lastHash else entries.firstOrNull { it.id == head.id }?.hash ?: (if (after?.id == head.id) after.hash else null)
            if (named != head.hash) return ChainCheck(false, ChainCheck.Reason.HEAD_MISMATCH, -1, head.id)
        }
        return ChainCheck.OK
    }

    // ---- filter + export (Activity page) ---------------------------------------------------------------------------

    /** Activity filter: [botId] null = team-wide (the bot itself or anywhere in its chain counts as "this bot"). */
    data class Filter(val botId: String? = null, val decisions: Set<Decision> = emptySet(), val classes: Set<ActionClass> = emptySet(),
                      val query: String = "", val sinceMs: Long? = null) {
        fun matches(e: AuditEntry): Boolean {
            if (botId != null && e.botId != botId && botId !in e.chain) return false
            if (decisions.isNotEmpty() && e.decision !in decisions) return false
            if (classes.isNotEmpty() && e.actionClass !in classes) return false
            if (sinceMs != null && e.at < sinceMs) return false
            val q = query.trim().lowercase()
            if (q.isNotEmpty()) {
                val hay = listOf(e.botId, e.tool, e.argsRedacted, e.result.orEmpty(), e.trigger, e.decision.id, e.decidedBy.orEmpty(),
                    e.actionClass.name, chainText(e.chain)).joinToString("\n").lowercase()
                if (q !in hay) return false
            }
            return true
        }
    }

    fun filter(entries: List<AuditEntry>, f: Filter): List<AuditEntry> = entries.filter(f::matches)

    private fun jsonEsc(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch < ' ' -> sb.append("\\u%04x".format(ch.code))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }

    /** JSON export: {"format":"argus-activity.v1","chain":"ok|…","entries":[…]} with every column, hashes included. */
    fun toJson(entries: List<AuditEntry>, check: ChainCheck = verify(entries)): String {
        fun s(v: String?) = if (v == null) "null" else jsonEsc(v)
        val rows = entries.joinToString(",\n") { e ->
            "  {\"id\":${e.id},\"at\":${e.at},\"bot\":${s(e.botId)},\"chain\":[${e.chain.joinToString(",") { jsonEsc(it) }}]," +
                "\"trigger\":${s(e.trigger)},\"tool\":${s(e.tool)},\"class\":${s(e.actionClass.name)},\"args\":${s(e.argsRedacted)}," +
                "\"decision\":${s(e.decision.id)},\"decided_by\":${s(e.decidedBy)},\"device\":${s(e.device)},\"result\":${s(e.result)}," +
                "\"undo\":${s(e.undo)},\"cost_minor\":${e.costMinor ?: "null"},\"prev_hash\":${s(e.prevHash)},\"hash\":${s(e.hash)}}"
        }
        return "{\"format\":\"argus-activity.v1\",\"chain\":${jsonEsc(if (check.ok) "ok" else check.reason.name.lowercase())},\"entries\":[\n$rows\n]}\n"
    }

    val CSV_HEADER = listOf("id", "at", "bot", "chain", "trigger", "tool", "class", "args", "decision", "decided_by", "device",
        "result", "undo", "cost_minor", "prev_hash", "hash")

    /** RFC 4180 cell; a cell a spreadsheet would run as a formula (= + - @) gets a leading apostrophe. */
    fun csvCell(v: String?): String {
        var s = v ?: ""
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun toCsv(entries: List<AuditEntry>): String {
        val sb = StringBuilder(CSV_HEADER.joinToString(",")).append("\r\n")
        for (e in entries) {
            sb.append(listOf(e.id.toString(), e.at.toString(), e.botId, chainText(e.chain), e.trigger, e.tool, e.actionClass.name,
                e.argsRedacted, e.decision.id, e.decidedBy, e.device, e.result, e.undo, e.costMinor?.toString(), e.prevHash, e.hash)
                .joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return sb.toString()
    }
}
