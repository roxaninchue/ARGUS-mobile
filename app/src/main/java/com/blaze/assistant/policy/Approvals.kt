package com.blaze.assistant.policy

import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Approval
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P2-SF-01: an approval card's lifecycle (Bot HQ "approval inside the tool call", 05 §7.1, BH-23).
 *
 * States ([ApprovalState], wire values = the team.db `approvals.status` column):
 * `pending` → `allowed` (Allow) · `always` (Always allow) · `denied` (Don't allow) · `expired` (25 minutes, or the app
 * restarted while it waited) · `skipped` (the user sent a new message instead of answering). Every state but pending is
 * final: a late tap on a closed card changes nothing ([ApprovalMachine.next]).
 *
 * The tool call that needs the card opens it ([Approvals.open]) and suspends in [Approvals.await] until it closes, so
 * approval works the same for every engine. [refusal] is the tool result the model gets when it wasn't allowed
 * ("Don't allow" tells it not to retry). Pure Kotlin + kotlinx.coroutines; the clock is injected.
 */
enum class ApprovalState(val wire: String) {
    PENDING("pending"), ALLOWED("allowed"), ALWAYS("always"), DENIED("denied"), EXPIRED("expired"), SKIPPED("skipped");

    val final: Boolean get() = this != PENDING
    val allowed: Boolean get() = this == ALLOWED || this == ALWAYS

    companion object { fun of(wire: String): ApprovalState = values().firstOrNull { it.wire == wire } ?: EXPIRED }
}

/** The pure transition table. */
object ApprovalMachine {
    sealed class Event {
        object Allow : Event(); object AlwaysAllow : Event(); object Deny : Event()
        /** The 25-minute timer ran out. */ object Timeout : Event()
        /** The user sent a new message in the thread. */ object UserMessage : Event()
        /** The app started again with this card still open. */ object Restart : Event()
    }

    fun next(state: ApprovalState, event: Event): ApprovalState {
        if (state.final) return state
        return when (event) {
            Event.Allow -> ApprovalState.ALLOWED
            Event.AlwaysAllow -> ApprovalState.ALWAYS
            Event.Deny -> ApprovalState.DENIED
            Event.Timeout, Event.Restart -> ApprovalState.EXPIRED
            Event.UserMessage -> ApprovalState.SKIPPED
        }
    }
}

/**
 * Open approval cards, in memory, with listeners for persistence (team.db `approvals`), audit (SF-05) and the card UI
 * (SF-02). Thread-safe. [initial] = rows loaded from team.db at start; call [onAppStart] to expire the ones left pending.
 */
class Approvals(
    private val clock: () -> Long = System::currentTimeMillis,
    val ttlMs: Long = TTL_MS,
    initial: List<Approval> = emptyList(),
) {
    companion object {
        /** Bot HQ: a card waits 25 minutes. */
        const val TTL_MS: Long = 25 * 60_000L

        /** The tool result for a call that wasn't allowed (null when it was). */
        fun refusal(state: ApprovalState, tool: String): String? = when (state) {
            ApprovalState.ALLOWED, ApprovalState.ALWAYS -> null
            ApprovalState.DENIED -> "The user declined ${tool.replace('_', ' ')}. Do not retry it or work around it; say what you would have done and ask how they want to go on."
            ApprovalState.EXPIRED -> "No answer on the approval card for ${tool.replace('_', ' ')} (it expired). Don't retry it on your own; tell the user it's waiting for their OK."
            ApprovalState.SKIPPED -> "The user sent a new message instead of answering the card for ${tool.replace('_', ' ')}. Don't run it; follow their new message."
            ApprovalState.PENDING -> "Still waiting for the user's OK on ${tool.replace('_', ' ')}."
        }
    }

    private val rows = LinkedHashMap<String, Approval>().apply { initial.forEach { put(it.id, it) } }
    private val waiters = HashMap<String, CompletableDeferred<ApprovalState>>()
    private val listeners = mutableListOf<(Approval) -> Unit>()
    private var seq = 0L

    /** Called with every row that changed (opened or closed). */
    @Synchronized fun listen(l: (Approval) -> Unit) { listeners += l }

    /** Opens a pending card for [call] decided as [card]. */
    fun open(botId: String, call: Policy.Call, card: Policy.Decision.Card, title: String, detail: String, argsRedacted: String,
             jobId: String? = null, onDeviceOnly: Boolean = card.cls == ActionClass.SPEND || card.cls == ActionClass.DELETE): Approval {
        val a = synchronized(this) {
            val now = clock()
            val id = "ap_${now.toString(36)}_${(seq++).toString(36)}"
            Approval(id = id, botId = botId, jobId = jobId, callId = call.id.ifBlank { id }, tool = call.tool, actionClass = card.cls,
                title = title, detail = detail, argsRedacted = argsRedacted, tainted = card.taintSource != null,
                status = ApprovalState.PENDING.wire, onDeviceOnly = onDeviceOnly, createdAt = now, resolvedAt = null, resolvedBy = null)
                .also { rows[it.id] = it }
        }
        notify(a)
        return a
    }

    @Synchronized fun get(id: String): Approval? = rows[id]
    @Synchronized fun pending(botId: String? = null): List<Approval> =
        rows.values.filter { it.status == ApprovalState.PENDING.wire && (botId == null || it.botId == botId) }

    /** The user's answer. A card past its 25 minutes closes as expired instead. Returns the row as it ends up. */
    fun answer(id: String, event: ApprovalMachine.Event, by: String = "user"): Approval? {
        val ev = if (event == ApprovalMachine.Event.Allow || event == ApprovalMachine.Event.AlwaysAllow || event == ApprovalMachine.Event.Deny) event
            else return null
        val cur = get(id) ?: return null
        return if (overdue(cur)) move(id, ApprovalMachine.Event.Timeout, "timeout") else move(id, ev, by)
    }

    /** Expires every card older than [ttlMs]; returns them. */
    fun sweep(): List<Approval> = pending().filter { overdue(it) }.mapNotNull { move(it.id, ApprovalMachine.Event.Timeout, "timeout") }

    /** A new user message in [botId]'s thread (null = any thread): its open cards close as skipped. */
    fun onUserMessage(botId: String? = null): List<Approval> = pending(botId).mapNotNull { move(it.id, ApprovalMachine.Event.UserMessage, "user") }

    /** The app started: nothing can still be waiting on a card from the last run. */
    fun onAppStart(): List<Approval> = pending().mapNotNull { move(it.id, ApprovalMachine.Event.Restart, "restart") }

    /**
     * Suspends the tool call until the card closes (or its time runs out → expired). Returns the final state; use
     * [refusal] for the tool result when it isn't allowed.
     */
    suspend fun await(id: String): ApprovalState {
        val (d, left) = synchronized(this) {
            val a = rows[id] ?: return ApprovalState.EXPIRED
            val st = ApprovalState.of(a.status)
            if (st.final) return st
            waiters.getOrPut(id) { CompletableDeferred() } to (a.createdAt + ttlMs - clock())
        }
        return withTimeoutOrNull(left.coerceAtLeast(0)) { d.await() }
            ?: (move(id, ApprovalMachine.Event.Timeout, "timeout")?.let { ApprovalState.of(it.status) } ?: ApprovalState.EXPIRED)
    }

    private fun overdue(a: Approval) = clock() - a.createdAt >= ttlMs

    private fun move(id: String, event: ApprovalMachine.Event, by: String): Approval? {
        val (row, changed, waiter) = synchronized(this) {
            val a = rows[id] ?: return null
            val from = ApprovalState.of(a.status)
            val to = ApprovalMachine.next(from, event)
            if (to == from) return a
            val b = a.copy(status = to.wire, resolvedAt = clock(), resolvedBy = by)
            rows[id] = b
            Triple(b, true, waiters.remove(id))
        }
        if (changed) { waiter?.complete(ApprovalState.of(row.status)); notify(row) }
        return row
    }

    private fun notify(a: Approval) {
        val ls = synchronized(this) { listeners.toList() }
        ls.forEach { runCatching { it(a) } }
    }
}
