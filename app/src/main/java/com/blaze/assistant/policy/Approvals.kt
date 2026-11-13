package com.blaze.assistant.policy

import com.blaze.assistant.store.Sql
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Approval
import com.blaze.assistant.team.Rule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * P2-SF-01: approvals inside the tool call (Bot HQ). A call the policy says to ask about opens an [Approval] and the
 * tool call waits on it, so the bot's turn resumes right where it was with the answer. An approval ends one way:
 *
 *  - allowed / always: the user said yes (always also saves a standing policy for that tool and bot);
 *  - denied: the user said no; the bot is told not to retry;
 *  - expired: nobody answered within [EXPIRY_MS] (25 minutes);
 *  - skipped: the user sent a new message to that chat or bot instead ([userMessage]);
 *  - expired on restart: the waiting tool call died with the process, so a pending row left in team.db is closed as
 *    expired when the app starts ([ApprovalStore.expireLeftovers]).
 *
 * In memory (one [CompletableDeferred] per approval) with an optional [Sink] that mirrors every row into team.db
 * `approvals` for the Inbox and the activity log. Pure Kotlin + coroutines; ApprovalsTest runs it on virtual time.
 */
class Approvals(private val clock: () -> Long = System::currentTimeMillis, private val sink: Sink? = null,
                private val newId: () -> String = { "ap-" + System.currentTimeMillis().toString(36) + "-" + seq.incrementAndGet() }) {

    enum class Status(val code: String) {
        PENDING("pending"), ALLOWED("allowed"), ALWAYS("always"), DENIED("denied"), EXPIRED("expired"), SKIPPED("skipped");
        val yes get() = this == ALLOWED || this == ALWAYS
        companion object { fun of(code: String): Status? = values().firstOrNull { it.code == code } }
    }

    /** Where approval rows go (team.db). Errors in a sink never break the tool call. */
    interface Sink {
        fun put(a: Approval)
        fun resolve(id: String, status: Status, at: Long, by: String?)
    }

    /** What the gate asks for. [chatId] is where the call runs (a new user message there skips it). */
    data class Request(val chatId: String, val botId: String, val jobId: String?, val callId: String, val tool: String,
                       val cls: ActionClass, val title: String, val detail: String, val argsRedacted: String, val tainted: Boolean,
                       val onDeviceOnly: Boolean = false)

    class Pending internal constructor(val approval: Approval, val chatId: String, val expiresAt: Long) {
        internal val done = CompletableDeferred<Status>()
        val id get() = approval.id
        val botId get() = approval.botId
    }

    private val open = ConcurrentHashMap<String, Pending>()

    fun open(r: Request): Pending {
        val now = clock()
        val a = Approval(newId(), r.botId, r.jobId, r.callId, r.tool, r.cls, r.title, r.detail, r.argsRedacted, r.tainted,
            Status.PENDING.code, r.onDeviceOnly || r.cls == ActionClass.SPEND || r.cls == ActionClass.DELETE, now, null, null)
        val p = Pending(a, r.chatId, now + EXPIRY_MS)
        open[a.id] = p
        safe { sink?.put(a) }
        return p
    }

    /** Waits for the answer; past [Pending.expiresAt] it is closed as expired. */
    suspend fun await(p: Pending): Status {
        val left = p.expiresAt - clock()
        val got = if (left <= 0) null else withTimeoutOrNull(left) { p.done.await() }
        return got ?: finish(p.id, Status.EXPIRED, "timeout") ?: p.done.await()
    }

    /** The user's (or a surface's) answer. False when it was already closed. */
    fun resolve(id: String, status: Status, by: String? = "user"): Boolean = status != Status.PENDING && finish(id, status, by) != null

    /** A new user message in [chatId] (or to [botId]) skips what waits there. Returns how many were skipped. */
    fun userMessage(chatId: String, botId: String? = null): Int =
        open.values.filter { it.chatId == chatId || (botId != null && it.botId == botId) }.count { finish(it.id, Status.SKIPPED, "new message") != null }

    /** Closes every approval past its 25 minutes (a sweeper for surfaces that list them). */
    fun expireStale(): Int { val now = clock(); return open.values.filter { now >= it.expiresAt }.count { finish(it.id, Status.EXPIRED, "timeout") != null } }

    /** Closes everything still waiting as expired (the process is going away). */
    fun expireAll(): Int = open.values.toList().count { finish(it.id, Status.EXPIRED, "restart") != null }

    fun pending(): List<Approval> = open.values.map { it.approval }.sortedBy { it.createdAt }
    fun pendingFor(chatId: String): List<Approval> = open.values.filter { it.chatId == chatId }.map { it.approval }

    private fun finish(id: String, status: Status, by: String?): Status? {
        val p = open.remove(id) ?: return null
        if (!p.done.complete(status)) return null
        safe { sink?.resolve(id, status, clock(), by) }
        return status
    }

    private inline fun safe(f: () -> Unit) { try { f() } catch (e: Throwable) { if (e is InterruptedException) throw e } }

    companion object {
        const val EXPIRY_MS = 25 * 60_000L
        private val seq = AtomicLong()
    }
}

/** team.db `approvals` rows (05 §9.2). */
class ApprovalStore(private val sql: Sql, private val clock: () -> Long = System::currentTimeMillis) : Approvals.Sink {
    override fun put(a: Approval) {
        sql.exec("INSERT OR REPLACE INTO approvals(id, bot_id, job_id, call_id, tool, action_class, title, detail, args_redacted, tainted, status, " +
            "on_device_only, created_at, resolved_at, resolved_by, u) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            a.id, a.botId, a.jobId, a.callId, a.tool, a.actionClass.name, a.title.take(200), a.detail.take(2000), a.argsRedacted.take(4000),
            if (a.tainted) 1 else 0, a.status, if (a.onDeviceOnly) 1 else 0, a.createdAt, a.resolvedAt, a.resolvedBy, clock())
    }

    override fun resolve(id: String, status: Approvals.Status, at: Long, by: String?) {
        sql.exec("UPDATE approvals SET status=?, resolved_at=?, resolved_by=?, u=? WHERE id=? AND status='pending'", status.code, at, by, clock(), id)
    }

    /** On start: a pending row is a tool call that died with the old process; it can't be answered any more. */
    fun expireLeftovers(): Int {
        val ids = sql.query("SELECT id FROM approvals WHERE status='pending'") { it.text(0) }
        for (id in ids) resolve(id, Approvals.Status.EXPIRED, clock(), "restart")
        return ids.size
    }

    fun status(id: String): String? = sql.query("SELECT status FROM approvals WHERE id=?", id) { it.text(0) }.firstOrNull()

    fun count(status: String): Int = sql.query("SELECT COUNT(*) FROM approvals WHERE status=?", status) { it.long(0).toInt() }.first()
}

/**
 * team.db `policies` (05 §9.2): standing rules per tool and bot ('*' = every bot). "Always allow" on a card writes ALLOW
 * for that tool and bot; "Ask again" (P2-SF-04) removes it. A bot-specific row wins over the '*' row.
 */
class PolicyStore(private val sql: Sql, private val clock: () -> Long = System::currentTimeMillis) {
    fun rule(tool: String, botId: String): Rule? {
        val rows = sql.query("SELECT bot_id, rule FROM policies WHERE tool=? AND (bot_id=? OR bot_id='*')", tool, botId) { it.text(0) to it.text(1) }
        val pick = rows.firstOrNull { it.first == botId } ?: rows.firstOrNull { it.first == "*" } ?: return null
        return Rule.values().firstOrNull { it.name.equals(pick.second, ignoreCase = true) }
    }

    fun set(tool: String, botId: String, rule: Rule, by: String = "user") {
        sql.exec("INSERT OR REPLACE INTO policies(tool, bot_id, rule, set_by, at) VALUES(?,?,?,?,?)", tool, botId, rule.name, by, clock())
    }

    fun forget(tool: String, botId: String) { sql.exec("DELETE FROM policies WHERE tool=? AND bot_id=?", tool, botId) }

    fun all(): List<Triple<String, String, Rule>> = sql.query("SELECT tool, bot_id, rule FROM policies ORDER BY tool, bot_id") { r ->
        Triple(r.text(0), r.text(1), Rule.values().firstOrNull { it.name.equals(r.text(2), true) } ?: Rule.ASK) }
}
