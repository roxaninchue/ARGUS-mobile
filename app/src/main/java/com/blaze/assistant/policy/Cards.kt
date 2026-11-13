package com.blaze.assistant.policy

import com.blaze.assistant.orchestra.KeyedLocks
import com.blaze.assistant.orchestra.ToolScheduler
import com.blaze.assistant.team.ActionClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2-SF-02: the cards (Bot HQ card kinds, 05 §7.1), pure Kotlin + coroutines so CardFlowTest runs them on virtual time.
 *
 *  - `approval`: Allow / Always allow (only where the policy lets a standing rule cover it) / Don't allow.
 *  - `draft`: every SEND. The whole draft and every recipient; Send / Edit / Discard. Send approves exactly that content
 *    to exactly those recipients (the call it sits in). Edit hands the edited words back to the bot with a one-time
 *    pass ([DraftPasses]) that covers that text to those recipients only, nothing else.
 *  - `question`: ask_user with at most [Cards.MAX_OPTIONS] choices or a free reply.
 *  - `connect`: request_app_connection; resumes the bot with "[System] connected X".
 *
 * Every card is an [Approvals] entry underneath, so they all end the same ways: answered, `skipped` by a new user
 * message in that chat, `expired` after 25 minutes (or with the process). [CardCenter] keeps what is on screen: one
 * card at a time per bot (ToolScheduler's `user` claim, per bot: [ToolScheduler.Resources.userLane]), never blocking
 * another bot. A card leaves the screen the moment its approval ends, however it ended (the P2-SF-01 gap, where a
 * skipped approval left the old confirm card up for its 2-minute timeout, is gone with it).
 */
object Cards {
    const val MAX_OPTIONS = 6
    const val OPTION_MAX = 80
    const val PASS_MS = 10 * 60_000L
    const val CONNECT_POLL_MS = 3_000L
    const val RECENT = 30

    enum class Kind(val code: String) { APPROVAL("approval"), DRAFT("draft"), QUESTION("question"), CONNECT("connect") }

    /** pending → approved / denied / expired / skipped (the card's own words for an approval's status). */
    enum class State(val code: String, val label: String) {
        PENDING("pending", "Waiting for you"), APPROVED("approved", "Approved"), DENIED("denied", "Not allowed"),
        EXPIRED("expired", "Expired"), SKIPPED("skipped", "Skipped");
        val open get() = this == PENDING
    }

    fun stateOf(s: Approvals.Status?): State = when (s) {
        null, Approvals.Status.PENDING -> State.PENDING
        Approvals.Status.ALLOWED, Approvals.Status.ALWAYS -> State.APPROVED
        Approvals.Status.DENIED -> State.DENIED
        Approvals.Status.EXPIRED -> State.EXPIRED
        Approvals.Status.SKIPPED -> State.SKIPPED
    }

    // ---------------- draft ----------------

    data class Draft(val tool: String, val recipients: List<String>, val subject: String?, val body: String) {
        /** The approval's scope: this tool, these recipients, this subject and text (order and case of recipients don't matter). */
        val key: String get() = fingerprint(tool, recipients, subject, body)
        val to: String get() = recipients.joinToString(", ").ifEmpty { "(no recipient given)" }
    }

    private val RECIPIENT_KEYS = listOf("to", "recipient", "recipients", "contact", "contact_name", "name", "number", "phone", "phone_number",
        "email", "address", "channel", "chat", "person", "who", "handle", "cc", "bcc")
    private val BODY_KEYS = listOf("body", "text", "message", "content", "reply", "msg", "caption", "post")

    private fun obj(json: String): JSONObject = try { JSONObject(json.ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }

    private fun strings(v: Any?): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).flatMap { strings(v.opt(it)) }
        is String -> v.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        is Number -> listOf(v.toString())
        else -> emptyList()
    }

    /** The draft a SEND call carries: recipients (cc / bcc marked), subject and body, read from its JSON args. */
    fun draftOf(tool: String, argsJson: String): Draft {
        val a = obj(argsJson)
        val rec = LinkedHashSet<String>()
        for (k in RECIPIENT_KEYS) if (a.has(k)) strings(a.opt(k)).forEach { rec += if (k == "cc" || k == "bcc") "$k: $it" else it }
        val body = BODY_KEYS.firstNotNullOfOrNull { k -> a.optString(k).takeIf { a.has(k) && it.isNotBlank() } }.orEmpty()
        val subject = a.optString("subject").takeIf { it.isNotBlank() } ?: a.optString("title").takeIf { it.isNotBlank() && body.isNotEmpty() }
        return Draft(tool, rec.toList(), subject, body)
    }

    /**
     * 0.53.0: the draft a call carries, whatever its route: a SEND tool's args ([draftOf]), or a screen plan that types
     * and taps Send ([ScreenSend.of]: Gmail compose on the screen showed a plain approval). Null = not a send.
     */
    fun draftFor(tool: String, argsJson: String, sendClass: Boolean): Draft? =
        ScreenSend.of(tool, argsJson)?.draft ?: if (sendClass) draftOf(tool, argsJson) else null

    /** The same call with [body] in place of the draft's text (what Edit asks the bot to send). */
    fun withBody(tool: String, argsJson: String, body: String): String {
        val a = obj(argsJson)
        val k = BODY_KEYS.firstOrNull { a.has(it) } ?: "text"
        return a.put(k, body).toString()
    }

    fun fingerprint(tool: String, recipients: List<String>, subject: String?, body: String): String {
        val text = tool.trim().lowercase() + "\n" + recipients.map { it.trim().lowercase() }.sorted().joinToString("|") + "\n" +
            subject.orEmpty().trim() + "\n" + body.trim()
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ---------------- question / connect ----------------

    data class Question(val text: String, val options: List<String>)

    /** ask_user's args: the question and up to six distinct, short choices (more are dropped; blanks ignored). */
    fun questionOf(argsJson: String): Question {
        val a = obj(argsJson)
        val q = a.optString("question").ifBlank { a.optString("text") }.trim()
        val opts = (a.optJSONArray("options") ?: a.optJSONArray("choices"))?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
            .map { it.trim().take(OPTION_MAX) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(MAX_OPTIONS)
        return Question(q, opts)
    }

    data class Connect(val app: String, val reason: String)

    fun connectOf(argsJson: String): Connect {
        val a = obj(argsJson)
        val app = listOf("app", "connector", "service", "name").firstNotNullOfOrNull { k -> a.optString(k).trim().takeIf { it.isNotEmpty() } }.orEmpty()
        return Connect(app.take(60), listOf("reason", "why", "purpose").firstNotNullOfOrNull { k -> a.optString(k).trim().takeIf { it.isNotEmpty() } }.orEmpty().take(300))
    }

    /** request_app_connection, offered to every chat (TeamHooks tool defs). */
    const val CONNECT_TOOL = "request_app_connection"
    fun connectToolDef(): String = JSONObject().put("type", "function").put("function", JSONObject().put("name", CONNECT_TOOL)
        .put("description", "Ask the user to connect an app or account you need (Gmail, Google Calendar, GitHub, Spotify, Notion, Slack…). " +
            "Shows a Connect card and waits; when they have connected it you get \"[System] connected <app>\" and carry on. Never ask for passwords or keys in chat.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("app", JSONObject().put("type", "string").put("description", "The app or account to connect, e.g. \"Gmail\""))
            .put("reason", JSONObject().put("type", "string").put("description", "One short line: what you need it for")))
            .put("required", JSONArray().put("app")))).toString()

    // ---------------- what the bot is told ----------------

    fun answered(a: String) = "The user answered: $a"
    const val QUESTION_SKIPPED = "The user sent a new message instead of answering your question. Don't ask it again; read their message."
    const val QUESTION_EXPIRED = "Nobody answered your question within 25 minutes. Stop here and say what you still need from the user."
    const val QUESTION_DISMISSED = "The user dismissed your question without answering. Don't ask it again; go on without it or say what you need."
    fun connected(app: String) = "[System] connected $app"
    fun notConnected(app: String) = "The user did NOT connect $app. Don't retry; say what you can do without it."
    fun connectSkipped(app: String) = "The user sent a new message instead of connecting $app. Don't ask again; read their message."
    fun connectExpired(app: String) = "$app wasn't connected within 25 minutes. Don't retry now; say that it is waiting for the user."
    fun edited(d: Draft) = "The user edited the draft, so it was NOT sent yet. Send exactly this text, unchanged, to the same recipients (${d.to}) " +
        "by calling ${d.tool} again" + (if (d.tool in ScreenSend.PLAN_TOOLS) " with the same steps, typing this text as the message" else "") +
        "; that one call is approved:\n\n${d.body}"
    fun discarded(tool: String) = "The user discarded the draft, so nothing was sent ($tool). Don't retry it; ask them what they'd like instead."

    /** The card's heading for a class (the bot's name goes on the card itself). */
    fun heading(k: Kind, cls: ActionClass?): String = when (k) {
        Kind.DRAFT -> "DRAFT \u00b7 NOT SENT YET"
        Kind.QUESTION -> "QUESTION"
        Kind.CONNECT -> "CONNECT AN APP"
        Kind.APPROVAL -> when (cls) {
            ActionClass.SPEND -> "SPEND \u00b7 NEEDS YOUR OK"; ActionClass.DELETE -> "DELETE \u00b7 NEEDS YOUR OK"
            ActionClass.CREDENTIAL -> "SIGN-IN \u00b7 NEEDS YOUR OK"; else -> "NEEDS YOUR OK"
        }
    }
}

/** One card on screen (or just closed, in [CardCenter.recent]). */
data class Card(
    val id: String,
    val kind: Cards.Kind,
    val chatId: String,
    val botId: String,
    val botName: String,
    val title: String,
    val detail: String,
    val tool: String = "",
    val cls: ActionClass? = null,
    val note: String? = null,
    val always: Boolean = false,
    val draft: Cards.Draft? = null,
    val question: Cards.Question? = null,
    val connect: Cards.Connect? = null,
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val state: Cards.State = Cards.State.PENDING,
) {
    val heading: String get() = Cards.heading(kind, cls)
}

/** A tap on a card. */
sealed class CardAnswer {
    object Allow : CardAnswer()
    object Always : CardAnswer()
    object Deny : CardAnswer()
    object Send : CardAnswer()
    data class Edit(val body: String) : CardAnswer()
    object Discard : CardAnswer()
    data class Reply(val text: String) : CardAnswer()
    object Dismiss : CardAnswer()
    object Connected : CardAnswer()
    object NotNow : CardAnswer()
}

/**
 * What is on screen. [show] waits for this bot's lane ([ToolScheduler.Resources.userLane] in [locks]), puts the card up,
 * waits for a tap, and takes the card down however the wait ends (a tap, or the caller cancelled because the approval
 * was skipped or expired). [onShow] / [onGone] let the phone add and clear a notification.
 */
class CardCenter(
    private val locks: KeyedLocks = KeyedLocks(),
    private val onShow: (Card) -> Unit = {},
    private val onGone: (Card) -> Unit = {},
) {
    private val _shown = MutableStateFlow<List<Card>>(emptyList())
    /** Cards on screen, oldest first; never two of one bot. */
    val shown: StateFlow<List<Card>> = _shown
    private val _recent = MutableStateFlow<List<Card>>(emptyList())
    /** Cards that closed lately with their final state, newest last (the thread's "Approved" / "Expired" line). */
    val recent: StateFlow<List<Card>> = _recent
    private val waits = ConcurrentHashMap<String, CompletableDeferred<CardAnswer>>()
    private val queued = ConcurrentHashMap<String, AtomicInteger>()

    /** How many more cards of [botId] wait behind the one on screen. */
    fun queued(botId: String): Int = queued[botId]?.get() ?: 0

    fun card(id: String): Card? = _shown.value.firstOrNull { it.id == id }

    suspend fun show(card: Card): CardAnswer {
        val q = queued.getOrPut(card.botId) { AtomicInteger() }
        q.incrementAndGet()
        var counted = true
        try {
            return locks.with(setOf(ToolScheduler.Resources.userLane(card.botId))) {
                q.decrementAndGet(); counted = false
                val d = CompletableDeferred<CardAnswer>()
                waits[card.id] = d
                synchronized(this) { _shown.value = _shown.value.filterNot { it.id == card.id } + card }
                safe { onShow(card) }
                try { d.await() } finally {
                    waits.remove(card.id)
                    synchronized(this) { _shown.value = _shown.value.filterNot { it.id == card.id } }
                    safe { onGone(card) }
                }
            }
        } finally { if (counted) q.decrementAndGet() }
    }

    /** A tap. False when the card isn't waiting any more. */
    fun answer(id: String, a: CardAnswer): Boolean = waits[id]?.complete(a) == true

    /** Records how a card ended (for [recent]). */
    fun closed(card: Card, state: Cards.State) {
        synchronized(this) { _recent.value = (_recent.value.filterNot { it.id == card.id } + card.copy(state = state)).takeLast(Cards.RECENT) }
    }

    private inline fun safe(f: () -> Unit) { try { f() } catch (e: Throwable) { if (e is CancellationException) throw e } }
}

/** One-time passes from an edited draft: exactly that text to those recipients, once, within [ttlMs]. */
class DraftPasses(private val clock: () -> Long = System::currentTimeMillis, private val ttlMs: Long = Cards.PASS_MS) {
    private val passes = ConcurrentHashMap<String, Pair<String, Long>>()
    fun grant(key: String, approvalId: String) { passes[key] = approvalId to clock() + ttlMs }
    /** The approval id behind a pass for [key], used up; null when there is none or it ran out. */
    fun take(key: String): String? {
        val p = passes.remove(key) ?: return null
        return p.first.takeIf { clock() <= p.second }
    }
    fun size() = passes.size
}

/**
 * The flow behind every card: [presenter] for [PolicyGate] (approval and draft cards), [ask] for ask_user, [connect]
 * for request_app_connection. [mirror] sees each card open and close (team threads keep a card row); [isConnected]
 * says whether an app is connected now (checked every [pollMs] while a connect card waits).
 */
class CardFlow(
    val approvals: Approvals,
    val center: CardCenter = CardCenter(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val mirror: Mirror? = null,
    private val isConnected: (String) -> Boolean = { false },
    private val pollMs: Long = Cards.CONNECT_POLL_MS,
) {
    interface Mirror {
        fun opened(card: Card) {}
        fun closed(card: Card, state: Cards.State) {}
    }

    val passes = DraftPasses(clock)
    private val outcomes = ConcurrentHashMap<String, String>()

    /** The tap on card [id]. */
    fun answer(id: String, a: CardAnswer): Boolean = center.answer(id, a)

    /** For [PolicyGate]: shows the approval or draft card for [p] and turns the tap into the approval's status. */
    val presenter = PolicyGate.Presenter { p, ask -> present(p, ask) }

    /** For [PolicyGate]: an edited draft's pass, when this call is exactly that draft. */
    fun pass(r: PolicyGate.Request, ask: Policy.Decision.Ask): String? =
        Cards.draftFor(r.tool, r.argsFull, ask.card == Policy.Card.DRAFT)?.let { passes.take(it.key) }

    /** For [PolicyGate]: what the bot is told when a card said no with more to say (Edit, Discard). */
    fun outcome(approvalId: String): String? = outcomes.remove(approvalId)

    private fun cardOf(p: Approvals.Pending, kind: Cards.Kind, ask: Policy.Decision.Ask?, draft: Cards.Draft? = null,
                       question: Cards.Question? = null, connect: Cards.Connect? = null): Card {
        val a = p.approval
        return Card(a.id, kind, p.chatId, a.botId, p.botName, a.title, a.detail, a.tool, a.actionClass, ask?.note,
            always = ask?.always == true && kind == Cards.Kind.APPROVAL, draft = draft, question = question, connect = connect,
            createdAt = a.createdAt, expiresAt = p.expiresAt)
    }

    private suspend fun present(p: Approvals.Pending, ask: Policy.Decision.Ask): Approvals.Status? {
        val draft = Cards.draftFor(p.approval.tool, p.args, ask.card == Policy.Card.DRAFT || p.approval.actionClass == ActionClass.SEND)
        val card = cardOf(p, if (draft != null) Cards.Kind.DRAFT else Cards.Kind.APPROVAL, ask, draft)
        return showing(p, card) { a ->
            when (a) {
                CardAnswer.Allow, CardAnswer.Send -> Approvals.Status.ALLOWED
                CardAnswer.Always -> if (card.always) Approvals.Status.ALWAYS else Approvals.Status.ALLOWED
                is CardAnswer.Edit -> {
                    val d = (draft ?: Cards.Draft(p.approval.tool, emptyList(), null, "")).copy(body = a.body.trim())
                    if (d.body.isEmpty()) { outcomes[p.id] = Cards.discarded(d.tool) } else {
                        passes.grant(d.key, p.id); outcomes[p.id] = Cards.edited(d)
                    }
                    Approvals.Status.DENIED
                }
                CardAnswer.Discard -> { outcomes[p.id] = Cards.discarded(p.approval.tool); Approvals.Status.DENIED }
                else -> Approvals.Status.DENIED
            }
        }
    }

    /**
     * Shows [card] (queued behind this bot's card on screen), maps the tap with [map] and resolves [p] with it; returns
     * that status. Cancelled (the approval ended elsewhere) it takes the card down and records the approval's state.
     */
    private suspend fun showing(p: Approvals.Pending, card: Card, map: (CardAnswer) -> Approvals.Status): Approvals.Status? {
        safe { mirror?.opened(card) }
        var st: Approvals.Status? = null
        try {
            val a = center.show(card)
            st = map(a)
            approvals.resolve(p.id, st, "card")
            return st
        } finally {
            val end = approvals.status(p.id)?.takeIf { it != Approvals.Status.PENDING } ?: st
            val state = Cards.stateOf(end)
            center.closed(card, state)
            safe { mirror?.closed(card, state) }
        }
    }

    /** Opens [req], shows [card] for it until it ends, and gives back its status (and the tap that ended it, if any). */
    private suspend fun waitCard(req: Approvals.Request, make: (Approvals.Pending) -> Card, poll: (suspend () -> Boolean)? = null,
                                 map: (CardAnswer) -> Approvals.Status): Pair<Approvals.Status, CardAnswer?> {
        val p = approvals.open(req)
        val card = make(p)
        var tap: CardAnswer? = null
        val st = coroutineScope {
            val shown = launch { showing(p, card) { a -> tap = a; map(a) } }
            val watch = poll?.let { f -> launch { while (true) { delay(pollMs); if (f()) { approvals.resolve(p.id, Approvals.Status.ALLOWED, "connected"); break } } } }
            val s = approvals.await(p)
            shown.cancel(); watch?.cancel()
            s
        }
        return st to tap
    }

    /** ask_user as a card: the bot waits in the call; the reply (or why there is none) is the result. */
    suspend fun ask(chatId: String, botId: String, botName: String, argsJson: String, jobId: String? = null): String {
        val q = Cards.questionOf(argsJson)
        if (q.text.isEmpty()) return "Error: ask_user needs a question."
        val (st, tap) = waitCard(Approvals.Request(chatId, botId, jobId, "ask_user", "ask_user", ActionClass.READ, "$botName asks", q.text,
            argsJson.take(600), false, botName = botName), { p -> cardOf(p, Cards.Kind.QUESTION, null, question = q) }) { a ->
            if (a is CardAnswer.Reply && a.text.isNotBlank()) Approvals.Status.ALLOWED else Approvals.Status.DENIED
        }
        return when (st) {
            Approvals.Status.ALLOWED, Approvals.Status.ALWAYS -> Cards.answered((tap as? CardAnswer.Reply)?.text?.trim().orEmpty())
            Approvals.Status.SKIPPED -> Cards.QUESTION_SKIPPED
            Approvals.Status.DENIED -> Cards.QUESTION_DISMISSED
            Approvals.Status.EXPIRED, Approvals.Status.PENDING -> Cards.QUESTION_EXPIRED
        }
    }

    /** request_app_connection as a card: resumes with "[System] connected X" once it is connected (or the user says so). */
    suspend fun connect(chatId: String, botId: String, botName: String, argsJson: String, jobId: String? = null): String {
        val c = Cards.connectOf(argsJson)
        if (c.app.isEmpty()) return "Error: request_app_connection needs the app to connect."
        if (safeBool { isConnected(c.app) }) return Cards.connected(c.app) + " (it already was)"
        val (st, _) = waitCard(Approvals.Request(chatId, botId, jobId, Cards.CONNECT_TOOL, Cards.CONNECT_TOOL, ActionClass.READ,
            "$botName needs ${c.app}", c.reason.ifEmpty { "Connect ${c.app} so $botName can carry on." }, argsJson.take(600), false, botName = botName),
            { p -> cardOf(p, Cards.Kind.CONNECT, null, connect = c) }, poll = { safeBool { isConnected(c.app) } }) { a ->
            if (a == CardAnswer.Connected) Approvals.Status.ALLOWED else Approvals.Status.DENIED
        }
        return when (st) {
            Approvals.Status.ALLOWED, Approvals.Status.ALWAYS -> Cards.connected(c.app)
            Approvals.Status.SKIPPED -> Cards.connectSkipped(c.app)
            Approvals.Status.DENIED -> Cards.notConnected(c.app)
            Approvals.Status.EXPIRED, Approvals.Status.PENDING -> Cards.connectExpired(c.app)
        }
    }

    private inline fun safe(f: () -> Unit) { try { f() } catch (e: Throwable) { if (e is CancellationException) throw e } }
    private inline fun safeBool(f: () -> Boolean): Boolean = try { f() } catch (e: Throwable) { if (e is CancellationException) throw e; false }
}
