package com.blaze.assistant.team

import com.blaze.assistant.agent.LoopGuard
import com.blaze.assistant.memory.MemScopes
import com.blaze.assistant.team.BotMessaging.Handoff
import com.blaze.assistant.team.BotMessaging.HandoffStatus
import org.json.JSONObject

/**
 * # TeamBus — governed bot-to-bot messaging (P5-TM-01, 05 §2.4, §7.2, §10.2)
 *
 * Every bot can message any teammate with `send_to_bot(bot, message, expects_reply=true, thread?)` (Bot HQ BH-45/57).
 * The bus is the only way one bot reaches another; it is pure over its ports, so the rules run on the JVM (TeamBusTest).
 *
 * ## Public API (Rooms P5-TM-02, Workers P5-TM-03, People P5-SH-04 and voice P8-VC-01 build on exactly this)
 *  - [send] `(from, to, message, expectsReply, thread, via, originJob) → Sent` — the whole exchange. Blocking for
 *    `expectsReply = true` (the caller's tool call waits for the answer); `false` hands the receiver's turn to
 *    [later] and returns at once.
 *  - [exec] the tool adapter for `send_to_bot` / `stay_silent` ([BotMessaging.schemas]); returns the text the model reads.
 *  - [staySilent] ends the caller's part of the exchange with no message (BH-42).
 *  - [BotRunner] the engine seam: runs one turn of a bot with a prompt inside a [ThreadBudget] and returns a [BotReply].
 *    Until BotEngine / TeamHooks (P0-AG-01) land, TeamHooks wires `BotRunner { id, prompt, b -> engine(id).run(prompt, b) }`.
 *  - [ThreadBudget] the handle a receiving turn runs in: thread id, depth, model calls left ([ThreadBudget.charge]),
 *    the bot chain above it ([ThreadBudget.chain], for `Policy.Ctx(trigger = BOT, chain = …)`), and the way a nested
 *    send_to_bot / stay_silent from inside that turn finds its context (pass it as `via`).
 *  - reads: [thread], [threadsOf], [transcript], [talk] (who a bot talks to: People → Bots talking, Bot profile →
 *    Connections), [pairs] (Team list "Atlas ⇄ Ledger · 4 today").
 *
 * ## Rules
 *  - **Hop cap**: a message carries its depth; a turn started by the user / a routine is depth 0, a message sent from
 *    it is depth 1, and so on. A turn at depth ≥ [BotMessaging.maxHops] (3, or the sender's lower Permissions.maxHops)
 *    can't send: "answer the user directly" (Bot HQ `ctx.depth >= 3`).
 *  - **Thread budget**: an exchange has [BotMessaging.THREAD_BUDGET] (20) model calls, shared by every hop under the same
 *    root thread. The receiver's engine charges as it goes ([ThreadBudget.charge]); whatever [BotReply.calls] says it
 *    used beyond that is charged after. No calls left → the send is refused; running out mid-turn ends the thread
 *    ([HandoffStatus.OUT_OF_BUDGET]).
 *  - **Ends**: stay_silent from either side ends the thread (SILENT); a one-way message ends when the receiver's turn
 *    returns (DELIVERED); a failed turn ends it (FAILED). An answered thread (REPLIED) can be continued by the asker
 *    with `thread = <id>` while budget is left.
 *  - **Loops**: [LoopGuard] (reused, one per root exchange / per top-level turn) stops the same message to the same bot
 *    that already got the same answer twice; a bot can't message a bot that is waiting on it up the chain (it just
 *    replies), nor answer through send_to_bot in the thread it is answering.
 *  - **Scope redaction**: the ask is passed through [BotMessaging.Redact] with the receiver's memory viewer, the reply
 *    with the asker's (lines the writer could see are supplied by [MemoryView]). Nothing the receiver may not read
 *    reaches its context.
 *  - **No side doors**: the payload is plain language ([BotMessaging.looksLikeToolCall] is refused), the receiver runs
 *    as itself with its own tools, and [ThreadBudget.chain] lets Policy apply the lowest permission along the chain
 *    (05 §7.2); [BotMessaging.sideDoor] is the tool-dispatch check.
 *  - **Visible**: each exchange has one live HandoffCard ([Handoff]) in BOTH bots' threads, carrying the status and the
 *    collapsed "A ↔ B · n messages" line; the full transcript is kept under [BotMessaging.transcriptKey] and opens from
 *    the card. Nothing is hidden from the user.
 *  - **Who can be messaged**: any enabled, visible teammate by name or id. Rooms are refused (post_to_group, P5-TM-02).
 *    A hidden worker / subagent may only message its parent (05 §2.5: subagents report via send_to_bot), and only its
 *    parent may message it.
 */
class TeamBus(
    private val team: TeamStore,
    private val threads: BotThreads,
    private val port: BotThreadPort,
    private val runner: BotRunner,
    private val memory: MemoryView = MemoryView { emptyList() },
    /** Where one-way (expects_reply = false) turns run. Inline by default; the phone posts them to the job executor. */
    private val later: (() -> Unit) -> Unit = { it() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: (() -> String)? = null,
) {
    private val rows get() = threads.rows
    private val lock = Any()
    private var seq = 0
    private val guards = HashMap<String, LoopGuard>()

    // ------------------------------------------------------------------------------------------------ results

    sealed class Sent {
        abstract val text: String
        /** The receiver answered ([reply] is already redacted for the asker). */
        data class Replied(val thread: BusThread, val reply: String, val withheld: Int, override val text: String) : Sent()
        /** One-way message handed over (its turn runs on [later]). */
        data class Delivered(val thread: BusThread, override val text: String) : Sent()
        /** The receiver ended the exchange without a message. */
        data class Silent(val thread: BusThread, override val text: String) : Sent()
        /** Nothing was sent (hop cap, budget, unknown bot, loop…). [why] is the model-facing reason. */
        data class Refused(val why: Why, override val text: String) : Sent()
        /** The receiver's turn failed. */
        data class Failed(val thread: BusThread, override val text: String) : Sent()
        val ok: Boolean get() = this !is Refused && this !is Failed
        val threadId: String? get() = when (this) { is Replied -> thread.id; is Delivered -> thread.id; is Silent -> thread.id; is Failed -> thread.id; is Refused -> null }
    }

    enum class Why { EMPTY, UNKNOWN_BOT, SELF, ROOM, DISABLED, HIDDEN, HOP_CAP, BUDGET, THREAD, WAITING_ON_YOU, TOOL_CALL, LOOP }

    // ------------------------------------------------------------------------------------------------ send

    /**
     * Sends [message] from bot [from] to the teammate [to] (name or id). [via] = the [ThreadBudget] of the turn the
     * sender is in (null for a turn the user, a routine or voice started: depth 0). [thread] continues an exchange.
     */
    fun send(from: String, to: String, message: String, expectsReply: Boolean = true, thread: String? = null,
             via: ThreadBudget? = null, originJob: String? = null): Sent {
        val sender = team.get(from) ?: return refuse(Why.UNKNOWN_BOT, "You aren't on the team roster.")
        val msg = message.trim()
        if (msg.isEmpty()) return refuse(Why.EMPTY, "The message is empty: say what you need.")
        if (BotMessaging.looksLikeToolCall(msg)) return refuse(Why.TOOL_CALL,
            "Not sent: write what you need in plain language. Teammates never run your tool calls (and you never run theirs); they decide how with their own tools.")

        val depth = via?.depth ?: 0
        val cap = BotMessaging.maxHops(sender)
        if (depth >= cap) return refuse(Why.HOP_CAP, "Not sent: this is already hop $depth of $cap, the most bots may pass a message on. Answer the user directly with what you have.")

        // continue an exchange, or open a new one
        val cont = thread?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
            val t = port.thread(id) ?: return refuse(Why.THREAD, "No thread $id. Leave thread out to start a new exchange.")
            if (sender.id != t.a && sender.id != t.b) return refuse(Why.THREAD, "Thread $id isn't yours.")
            if (t.state.ended) return refuse(Why.THREAD, "Thread $id has ended (${t.state.name.lowercase()}). Leave thread out to start a new one.")
            if (via?.threadId == t.id) return refuse(Why.WAITING_ON_YOU, "You are answering this thread: just reply, your answer goes back on its own.")
            t
        }
        val target = (if (cont != null) team.get(if (cont.a == sender.id) cont.b else cont.a) else resolve(to))
            ?: return refuse(Why.UNKNOWN_BOT, "Not found: no teammate called \"${to.trim()}\". Teammates: ${team.all().filter { it.id != sender.id }.joinToString { it.name }}.")
        if (cont != null && to.isNotBlank() && resolve(to)?.id.let { it != null && it != target.id })
            return refuse(Why.THREAD, "Thread ${cont.id} is with ${target.name}, not ${to.trim()}.")
        whoCanTalk(sender, target)?.let { return it }
        if (via != null && target.id in via.chain) return refuse(Why.WAITING_ON_YOU,
            "Not sent: ${target.name} is waiting for your answer. Reply to it directly instead of messaging it.")

        val root = cont?.root ?: via?.rootId
        val left = root?.let { budgetLeft(it) } ?: BotMessaging.THREAD_BUDGET
        if (left < 1) return refuse(Why.BUDGET, "Not sent: this exchange used its ${BotMessaging.THREAD_BUDGET} model calls. Answer the user with what you have.")

        val guardKey = root ?: "turn:${sender.id}"
        val gArgs = JSONObject().put("to", target.id).put("message", msg)
        guard(guardKey).check(BotMessaging.SEND_TO_BOT, gArgs)?.let { return refuse(Why.LOOP, it) }

        // open / reuse the thread
        val now = clock()
        val t0 = cont ?: run {
            val id = nextId()
            BusThread(id = id, a = sender.id, b = target.id, originJob = originJob ?: via?.originJob, depth = depth + 1,
                budgetLeft = left, state = BusState.OPEN, createdAt = now, root = root ?: id, parent = via?.threadId,
                messages = 0, updatedAt = now, expectsReply = expectsReply)
        }
        val askerIsA = t0.a == sender.id

        // scope redaction of the ask, for the receiver
        val red = BotMessaging.Redact.pass(msg, memory.lines(sender.id), sender.id, viewer(target), clock() / 1000, names())
        val chain = listOf(sender.id) + (via?.chain ?: emptyList())
        val budget = ThreadBudget(t0.id, t0.root, t0.depth, BotMessaging.maxHops(target), target.id, chain, t0.originJob, this)

        var t = t0.copy(state = BusState.OPEN, messages = t0.messages + 1, updatedAt = now, expectsReply = expectsReply)
        transcriptAdd(t.id, sender.id, red.text, red.withheld.size)
        t = cards(t, sender, target, askerIsA, ask = msg, askSeen = red.text, reply = null, replySeen = null,
            status = HandoffStatus.ASKING, withheldAsk = red.withheld.size, final = false)
        port.putThread(t)

        val prompt = BotMessaging.botEnvelope(sender.name, sender.label, t.id, t.depth, budget.maxHops, budgetLeft(t.root),
            expectsReply, red.text)

        if (!expectsReply) {
            later { runTurn(t.id, sender, target, askerIsA, prompt, budget, guardKey, gArgs, oneWay = true) }
            val cur = port.thread(t.id) ?: t
            return Sent.Delivered(cur, "[send_to_bot] Sent to ${target.name} (no reply expected) \u00b7 thread ${t.id}." +
                if (red.changed) " ${red.withheld.size} note(s) ${target.name} can't see were withheld." else "")
        }
        return runTurn(t.id, sender, target, askerIsA, prompt, budget, guardKey, gArgs, oneWay = false)
    }

    /** Runs the receiver's turn and settles the thread. */
    private fun runTurn(threadId: String, sender: Bot, target: Bot, askerIsA: Boolean, prompt: String, budget: ThreadBudget,
                        guardKey: String, gArgs: JSONObject, oneWay: Boolean): Sent {
        setStatus(threadId, sender, target, askerIsA, HandoffStatus.WORKING)
        val reply: BotReply = try { runner.run(target.id, prompt, budget) } catch (e: Exception) {
            val t = update(threadId) { it.copy(state = BusState.FAILED, updatedAt = clock()) }!!
            setStatus(threadId, sender, target, askerIsA, HandoffStatus.FAILED, final = true)
            val text = "[send_to_bot] Couldn't reach ${target.name}: ${e.message ?: e.javaClass.simpleName}. Don't retry now; carry on without it."
            guard(guardKey).record(BotMessaging.SEND_TO_BOT, gArgs, text)
            return Sent.Failed(t, text)
        }
        // charge what the engine didn't already charge
        val extra = (reply.calls - budget.charged).coerceAtLeast(if (budget.charged == 0) 1 else 0)
        if (extra > 0) charge(budget.rootId, extra)
        val left = budgetLeft(budget.rootId)
        val silent = reply.silent || budget.silenced || reply.text.isBlank()

        if (silent) {
            // A one-way message is meant to end in silence: that is delivery, not a refusal to answer.
            val t = update(threadId) { it.copy(state = if (oneWay) BusState.DONE else BusState.SILENT, budgetLeft = left, updatedAt = clock()) }!!
            setStatus(threadId, sender, target, askerIsA, if (oneWay) HandoffStatus.DELIVERED else HandoffStatus.SILENT, final = true)
            val text = if (oneWay) "[send_to_bot] ${target.name} handled your message. Thread ${t.id} ended."
            else "[send_to_bot] ${target.name} chose not to reply (stay_silent). Thread ${t.id} ended."
            guard(guardKey).record(BotMessaging.SEND_TO_BOT, gArgs, text)
            clearGuard(t)
            return Sent.Silent(t, text)
        }

        // scope redaction of the reply, for the asker
        val red = BotMessaging.Redact.pass(reply.text.trim(), memory.lines(target.id), target.id, viewer(sender), clock() / 1000, names())
        transcriptAdd(threadId, target.id, red.text, red.withheld.size)
        val state = when { left <= 0 -> BusState.OUT_OF_BUDGET; oneWay -> BusState.DONE; else -> BusState.ANSWERED }
        val status = when (state) { BusState.OUT_OF_BUDGET -> HandoffStatus.OUT_OF_BUDGET; BusState.DONE -> HandoffStatus.DELIVERED; else -> HandoffStatus.REPLIED }
        val cur0 = port.thread(threadId)!!
        var t = cur0.copy(state = state, budgetLeft = left, messages = cur0.messages + 1, updatedAt = clock())
        t = cards(t, sender, target, askerIsA, ask = null, askSeen = null, reply = red.text, replySeen = reply.text.trim(),
            status = status, withheldReply = red.withheld.size, final = true)
        port.putThread(t)
        val text = "[send_to_bot] ${target.name} replied (thread ${t.id}, ${left.coerceAtLeast(0)} model calls left" +
            (if (state == BusState.OUT_OF_BUDGET) ", thread budget used up: answer the user now" else "") + "):\n${red.text}" +
            (if (red.changed) "\n(${red.withheld.size} note(s) you can't see were withheld.)" else "")
        guard(guardKey).record(BotMessaging.SEND_TO_BOT, gArgs, red.text)
        if (state.ended) clearGuard(t)
        return Sent.Replied(t, red.text, red.withheld.size, text)
    }

    /**
     * `stay_silent` (BH-42): the caller ends its turn with no message. Inside a bot↔bot turn it ends that thread when the
     * turn returns (the asker gets [Sent.Silent]); in a routine / check-in / room turn the engine simply ends the turn.
     */
    fun staySilent(botId: String, via: ThreadBudget?, reason: String = ""): String {
        if (via != null && via.botId == botId) {
            via.silenced = true
            return "[stay_silent] Ending without a reply; thread ${via.threadId} closes."
        }
        return "[stay_silent] Ending this turn without a message."
    }

    // ------------------------------------------------------------------------------------------------ tool adapter

    data class Out(val ok: Boolean, val text: String, val threadId: String? = null, val silent: Boolean = false)

    /** The `send_to_bot` / `stay_silent` tools for [caller] (its current [via] when it is answering another bot). */
    fun exec(name: String, a: JSONObject, caller: Bot, via: ThreadBudget? = null, jobId: String? = null): Out = when (name) {
        BotMessaging.SEND_TO_BOT -> {
            val r = send(caller.id, s(a, "bot", "to", "agent", "name"), s(a, "message", "text", "task"),
                expectsReply = if (a.has("expects_reply")) a.optBoolean("expects_reply", true) else true,
                thread = s(a, "thread", "thread_id").ifEmpty { null }, via = via, originJob = jobId)
            Out(r.ok, r.text, r.threadId)
        }
        BotMessaging.STAY_SILENT -> Out(true, staySilent(caller.id, via, s(a, "reason")), via?.threadId, silent = true)
        else -> Out(false, "[$name] unknown tool")
    }

    /** Clears the per-turn loop guard of a top-level turn (the engine calls this when [botId]'s user turn ends). */
    fun endTurn(botId: String) { synchronized(lock) { guards.remove("turn:$botId") } }

    // ------------------------------------------------------------------------------------------------ reads

    fun thread(id: String): BusThread? = port.thread(id)

    /** Every exchange [botId] took part in, newest first. */
    fun threadsOf(botId: String): List<BusThread> = port.threads(botId).sortedByDescending { it.updatedAt }

    /** The bot↔bot transcript the HandoffCard expands to (what each side actually received), oldest first. */
    fun transcript(threadId: String): List<Row> = rows.all(BotMessaging.transcriptKey(threadId))

    /** One teammate [botId] talks to: exchanges, messages, the last one (People → Bots talking; profile → Connections). */
    data class Talk(val other: String, val threads: Int, val messages: Int, val lastAt: Long, val asked: Int, val answered: Int)

    fun talk(botId: String): List<Talk> = threadsOf(botId).groupBy { if (it.a == botId) it.b else it.a }.map { (other, ts) ->
        Talk(other, ts.size, ts.sumOf { it.messages }, ts.maxOf { it.updatedAt }, ts.count { it.a == botId }, ts.count { it.b == botId })
    }.sortedByDescending { it.lastAt }

    /** Bot pairs that talked since [sinceMs] ("Atlas ⇄ Ledger · 4 today"): (a, b) sorted ids → exchanges. */
    fun pairs(sinceMs: Long = 0): Map<Pair<String, String>, Int> = port.all().filter { it.updatedAt >= sinceMs }
        .groupingBy { if (it.a <= it.b) it.a to it.b else it.b to it.a }.eachCount()

    // ------------------------------------------------------------------------------------------------ budget (ThreadBudget calls these)

    fun budgetLeft(rootId: String): Int = synchronized(lock) { port.thread(rootId)?.budgetLeft ?: BotMessaging.THREAD_BUDGET }

    /** Spends [n] calls of the root exchange's budget. False when nothing was left before. */
    internal fun charge(rootId: String, n: Int): Boolean = synchronized(lock) {
        val r = port.thread(rootId) ?: return false
        if (r.budgetLeft <= 0) return false
        val left = (r.budgetLeft - n).coerceAtLeast(0)
        port.putThread(r.copy(budgetLeft = left))
        // child threads mirror the shared pool (display only)
        port.all().filter { it.root == rootId && it.id != rootId }.forEach { port.putThread(it.copy(budgetLeft = left)) }
        true
    }

    // ------------------------------------------------------------------------------------------------ internals

    private fun refuse(why: Why, text: String) = Sent.Refused(why, "[send_to_bot] $text")

    private fun s(a: JSONObject, vararg k: String) = k.asSequence().map { a.optString(it).trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun nextId(): String = newId?.invoke() ?: synchronized(lock) { "t" + java.lang.Long.toString(clock(), 36) + (++seq) }

    private fun guard(key: String): LoopGuard = synchronized(lock) { guards.getOrPut(key) { LoopGuard() } }
    private fun clearGuard(t: BusThread) { if (t.id == t.root) synchronized(lock) { guards.remove(t.root) } }

    /** A teammate by name / id; hidden bots only by exact id (the parent ↔ subagent rule is in [whoCanTalk]). */
    private fun resolve(ref: String): Bot? {
        val r = ref.trim().removePrefix("@").trim()
        if (r.isEmpty()) return null
        return team.find(r) ?: team.get(r)
    }

    private fun whoCanTalk(sender: Bot, target: Bot): Sent.Refused? = when {
        target.id == sender.id -> refuse(Why.SELF, "That's you. Answer yourself.")
        target.kind == BotKind.ROOM -> refuse(Why.ROOM, "${target.name} is a group chat: post there with post_to_group.")
        !target.enabled -> refuse(Why.DISABLED, "${target.name} is turned off right now. Tell the user, or carry on without it.")
        sender.hidden && target.id != (sender.parentId ?: sender.reportsTo) ->
            refuse(Why.HIDDEN, "As a worker you report to ${sender.parentId ?: sender.reportsTo ?: "your parent"} only.")
        target.hidden && target.parentId != sender.id -> refuse(Why.HIDDEN, "Not found: no teammate called \"${target.name}\".")
        else -> null
    }

    private fun names(): Map<String, String> = team.all(hidden = true).associate { it.id to it.name }

    /** The memory viewer of [b] (05 §4.2 visibility rule) from the roster. */
    fun viewer(b: Bot): MemScopes.Viewer {
        val roster = team.all(hidden = true).map { Triple(it.id, it.domain, it.reportsTo) }
        return MemScopes.viewer(b.id, b.domain, Bots.isManager(b), b.permissions.dataScopes, roster)
    }

    private fun transcriptAdd(threadId: String, from: String, text: String, withheld: Int) {
        val body = JSONObject(RowBody.message(BotMessaging.ROLE_BOT, text, "bot:$from")).put("thread", threadId)
        if (withheld > 0) body.put("withheld", withheld)
        rows.append(BotMessaging.transcriptKey(threadId), RowKind.MESSAGE, body.toString())
    }

    private fun update(id: String, f: (BusThread) -> BusThread): BusThread? = synchronized(lock) {
        val t = port.thread(id) ?: return null
        f(t).also { port.putThread(it) }
    }

    private fun setStatus(threadId: String, sender: Bot, target: Bot, askerIsA: Boolean, status: HandoffStatus, final: Boolean = false) {
        val t = port.thread(threadId) ?: return
        port.putThread(cards(t, sender, target, askerIsA, null, null, null, null, status, final = final))
    }

    /**
     * Writes / updates the HandoffCard in both threads. The asker's card keeps what the asker wrote and what it got back
     * (redacted); the answerer's card keeps what it received (redacted) and its own reply. Null fields keep their value.
     */
    private fun cards(t: BusThread, sender: Bot, target: Bot, askerIsA: Boolean, ask: String?, askSeen: String?,
                      reply: String?, replySeen: String?, status: HandoffStatus, withheldAsk: Int = 0, withheldReply: Int = 0,
                      final: Boolean): BusThread {
        val left = budgetLeft(t.root).let { if (port.thread(t.root) == null) t.budgetLeft else it }
        fun one(botId: String, seq: Long?, side: String, askText: String?, replyText: String?, withheld: Int): Long {
            val old = seq?.let { s -> rows.all(botId).firstOrNull { it.seq == s } }?.let { Handoff.of(it) }
            val h = Handoff(thread = t.id, root = t.root, from = sender.id, to = target.id, fromName = sender.name, toName = target.name,
                ask = askText?.let { BotMessaging.preview(it) } ?: old?.ask.orEmpty(), status = status,
                reply = replyText?.let { BotMessaging.preview(it) } ?: old?.reply.orEmpty(), messages = t.messages, depth = t.depth,
                budgetLeft = left, side = side, expectsReply = t.expectsReply, withheld = (old?.withheld ?: 0) + withheld)
            val updated = if (seq != null && old != null) rows.update(botId, seq, h.body()) else null
            val r = updated ?: threads.card(botId, BotMessaging.CARD_HANDOFF, h.fields())
            if (updated != null && final) threads.touch(updated)
            return r.seq
        }
        val askerSeq = if (askerIsA) t.cardA else t.cardB
        val answererSeq = if (askerIsA) t.cardB else t.cardA
        // A continued thread may be asked from the other side; each side's card names who asked this time.
        val sA = one(sender.id, askerSeq, Handoff.SIDE_ASKER, ask, reply, withheldReply)
        val sB = one(target.id, answererSeq, Handoff.SIDE_ANSWERER, askSeen, replySeen, withheldAsk)
        return if (askerIsA) t.copy(cardA = sA, cardB = sB, budgetLeft = left) else t.copy(cardA = sB, cardB = sA, budgetLeft = left)
    }
}

/** The engine seam (P5-PLAN seams table): run one turn of bot [botId] on [prompt] inside [budget]. Replaced by TeamHooks. */
fun interface BotRunner {
    fun run(botId: String, prompt: String, budget: ThreadBudget): BotReply
}

/**
 * What a receiving turn produced. [text] = its answer (blank = nothing to say); [calls] = model calls it used in total
 * (the bus charges whatever [ThreadBudget.charge] didn't already); [silent] = it called stay_silent.
 */
data class BotReply(val text: String, val calls: Int = 1, val silent: Boolean = false) {
    companion object { fun silent(calls: Int = 1) = BotReply("", calls, silent = true) }
}

/** The memory lines bot [botId] can see (memories ⨝ mem_scope, already scope-filtered for it): what it might quote. */
fun interface MemoryView {
    fun lines(botId: String): List<MemScopes.Line>
}

/**
 * The budget handle a receiving turn runs in. Pass it as `via` to [TeamBus.send] / [TeamBus.exec] for any
 * send_to_bot / stay_silent made inside that turn, so depth, budget and chain carry over.
 */
class ThreadBudget internal constructor(
    val threadId: String, val rootId: String,
    /** Depth of the message this turn answers (1 = sent from a user / routine turn). */
    val depth: Int,
    val maxHops: Int,
    /** The bot running this turn. */
    val botId: String,
    /** Bots above this turn, nearest first (the sender, its sender…): Policy.Ctx(trigger = BOT, chain = these). */
    val chain: List<String>,
    val originJob: String?,
    private val bus: TeamBus,
) {
    /** Model calls left in the whole exchange. */
    val left: Int get() = bus.budgetLeft(rootId)
    /** May this turn message another bot (not at the hop cap)? Engines drop send_to_bot from the tool list when false. */
    val canSend: Boolean get() = depth < maxHops
    @Volatile var charged: Int = 0; private set
    @Volatile var silenced: Boolean = false; internal set

    /** Charges one or more model calls as the engine makes them. False = the budget is used up: stop the turn. */
    fun charge(n: Int = 1): Boolean {
        val ok = bus.charge(rootId, n)
        if (ok) charged += n
        return ok && left > 0
    }
}

/** bot_threads `state` (05 §9.2). [ended] threads can't be continued. */
enum class BusState(val ended: Boolean) { OPEN(false), ANSWERED(false), DONE(true), SILENT(true), OUT_OF_BUDGET(true), FAILED(true) }

/**
 * One bot↔bot exchange: a team.db `bot_threads` row (05 §9.2: id, a, b, origin_job, depth, budget_left, state,
 * created_at). P5-local fields until P0-TM-01's TeamDb maps them (extra columns, or derived): [root] / [parent] (hops
 * under one exchange share its budget), [messages], [updatedAt], [expectsReply], and the HandoffCard row seqs in a's
 * and b's threads ([cardA], [cardB]). [a] is the bot that opened it.
 */
data class BusThread(
    val id: String, val a: String, val b: String, val originJob: String?, val depth: Int, val budgetLeft: Int,
    val state: BusState, val createdAt: Long,
    val root: String = id, val parent: String? = null, val messages: Int = 0, val updatedAt: Long = createdAt,
    val expectsReply: Boolean = true, val cardA: Long? = null, val cardB: Long? = null,
)

/** Storage for [BusThread]s (team.db `bot_threads` on the phone; [MemoryBotThreadPort] in tests). */
interface BotThreadPort {
    fun thread(id: String): BusThread?
    fun putThread(t: BusThread)
    /** Threads where [botId] is a or b. */
    fun threads(botId: String): List<BusThread>
    fun all(): List<BusThread>
}

class MemoryBotThreadPort : BotThreadPort {
    private val m = LinkedHashMap<String, BusThread>()
    @Synchronized override fun thread(id: String) = m[id]
    @Synchronized override fun putThread(t: BusThread) { m[t.id] = t }
    @Synchronized override fun threads(botId: String) = m.values.filter { it.a == botId || it.b == botId }
    @Synchronized override fun all() = m.values.toList()
}
