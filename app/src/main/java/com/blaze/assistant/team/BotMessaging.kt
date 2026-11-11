package com.blaze.assistant.team

import com.blaze.assistant.memory.MemScopes
import org.json.JSONArray
import org.json.JSONObject

/**
 * P5-TM-01: the pure rules of bot-to-bot messaging (05 §2.4, §7.2, §10.2; Bot HQ send_to_bot / stay_silent, BH-12,
 * BH-17, BH-42, BH-45, BH-57; 04 §2.8 #30 HandoffCard). No Android, no I/O: [TeamBus] is the runtime that uses them,
 * and Rooms (P5-TM-02), Workers (P5-TM-03), People (P5-SH-04) and voice (P8-VC-01) read the same rules.
 *
 * What lives here:
 *  - limits: [MAX_HOPS] (hop cap 3, Bot HQ `ctx.depth >= 3`) and [THREAD_BUDGET] (20 model calls per exchange);
 *  - [Provenance]: who started a message (user · bot · routine · voice · system), the Row `from` string it is stored
 *    as, the badge a thread shows under it ("from Juno", "routine Morning brief", "said on a call") and the trigger
 *    envelope a model reads ([trigger], [botEnvelope]);
 *  - [HandoffStatus] + [Handoff]: the HandoffCard's data (04 §7.3: from, to, ask, status, room/thread) and live states;
 *  - [talkLine]: the collapsed "Atlas ↔ Ledger · 3 messages" line shown in both bots' threads;
 *  - [Redact]: the scope redaction pass over a payload (05 §2.4 "Data scope check"): memory lines the receiver may not
 *    read are withheld, using each line's `mem_scope` scope / share tags and [MemScopes.visible];
 *  - no side doors ([sideDoor], [looksLikeToolCall]): a bot never runs another bot's tools; it asks the owner bot in
 *    plain language and the owner decides with its own tools and permissions;
 *  - the `send_to_bot` / `stay_silent` tool schemas ([schemas]).
 */
object BotMessaging {
    /** Hop cap (05 §2.4, §7.2): a turn at this depth can't message another bot ("answer the user directly"). */
    const val MAX_HOPS = 3
    /** Model calls one exchange may spend, shared by every hop under the same root thread (05 §2.4 default 20). */
    const val THREAD_BUDGET = 20

    const val SEND_TO_BOT = "send_to_bot"
    const val STAY_SILENT = "stay_silent"
    val TOOL_NAMES = listOf(SEND_TO_BOT, STAY_SILENT)

    /** Row role of a message one bot wrote to another (shown in the bot↔bot transcript). */
    const val ROLE_BOT = "bot"
    /** Row card type of the HandoffCard. */
    const val CARD_HANDOFF = "handoff"
    /** Transcript rows of a bot↔bot thread live under this pseudo bot id in team.db `rows` (no schema change). */
    const val TRANSCRIPT_PREFIX = "bt:"
    fun transcriptKey(threadId: String) = TRANSCRIPT_PREFIX + threadId
    fun isTranscriptKey(botId: String) = botId.startsWith(TRANSCRIPT_PREFIX)

    /** Longest ask / reply preview a card or tool result carries. */
    const val PREVIEW = 140

    /** The hop cap for a sender: the global cap, lowered by the bot's own Permissions.maxHops (05 §9.1). */
    fun maxHops(sender: Bot?): Int = minOf(MAX_HOPS, (sender?.permissions?.maxHops ?: MAX_HOPS).coerceAtLeast(0))

    fun preview(s: String, max: Int = PREVIEW): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "\u2026"
    }

    // ------------------------------------------------------------------------------------------------ provenance

    /**
     * Who a message comes from (BH `MessageBody.from {kind: bot|routine|voice}`, BH-12 trigger types). Stored in a
     * Row body's `from` field as [from]: "user", "bot:<id>", "routine:<id>", "voice", "system". Older rows wrote a
     * plain bot id there (P1-TM-02); [parse] reads both.
     */
    data class Provenance(val kind: Kind, val id: String? = null, val name: String? = null) {
        enum class Kind { USER, BOT, ROUTINE, VOICE, SYSTEM }

        val from: String get() = when (kind) {
            Kind.USER -> RowBody.USER
            Kind.BOT -> "bot:${id.orEmpty()}"
            Kind.ROUTINE -> "routine:${id.orEmpty()}"
            Kind.VOICE -> "voice"
            Kind.SYSTEM -> RowBody.SYSTEM
        }

        companion object {
            val USER = Provenance(Kind.USER)
            val VOICE = Provenance(Kind.VOICE)
            val SYSTEM = Provenance(Kind.SYSTEM)
            fun bot(id: String, name: String? = null) = Provenance(Kind.BOT, id, name)
            fun routine(id: String, name: String? = null) = Provenance(Kind.ROUTINE, id, name)

            /** Reads a Row `from`. [self] = the thread's own bot (its own messages carry no badge). Null = no provenance. */
            fun parse(from: String?, self: String? = null): Provenance? {
                val f = from?.trim().orEmpty()
                return when {
                    f.isEmpty() -> null
                    f == RowBody.USER -> USER
                    f == RowBody.SYSTEM -> SYSTEM
                    f == "voice" || f == "call" -> VOICE
                    f.startsWith("bot:") -> f.removePrefix("bot:").takeIf { it.isNotEmpty() }?.let { bot(it) }
                    f.startsWith("routine:") -> routine(f.removePrefix("routine:"))
                    f == RowBody.ASSISTANT || f == self -> null
                    else -> bot(f)          // a plain bot id (P1-TM-02 rows)
                }
            }
        }
    }

    /**
     * The provenance badge under a message (BH-17 `sender-label`): "from Juno" · "routine Morning brief" ·
     * "said on a call". Null for the user's own messages, system notices and the thread bot's own replies.
     * [names] maps bot / routine ids to display names.
     */
    fun badge(p: Provenance?, self: String? = null, names: Map<String, String> = emptyMap()): String? = when (p?.kind) {
        null, Provenance.Kind.USER, Provenance.Kind.SYSTEM -> null
        Provenance.Kind.BOT -> if (p.id == self) null else "from ${p.name ?: names[p.id] ?: p.id}"
        Provenance.Kind.ROUTINE -> "routine ${p.name ?: names[p.id] ?: p.id}".trim()
        Provenance.Kind.VOICE -> "said on a call"
    }

    /** The badge of a stored row (reads its `from`). */
    fun badge(r: Row, names: Map<String, String> = emptyMap()): String? =
        if (r.kind != RowKind.MESSAGE) null else badge(Provenance.parse(RowBody.field(r, "from"), r.botId), r.botId, names)

    /** BH-12 `triggerText`: the line put in front of a turn that the user didn't start. */
    fun trigger(p: Provenance, names: Map<String, String> = emptyMap()): String = when (p.kind) {
        Provenance.Kind.USER -> ""
        Provenance.Kind.BOT -> "[Message from ${p.name ?: names[p.id] ?: p.id}, another bot on your team]"
        Provenance.Kind.ROUTINE -> "[Routine ${p.name ?: names[p.id] ?: p.id} is running now]"
        Provenance.Kind.VOICE -> "[Said on a call]"
        Provenance.Kind.SYSTEM -> "[System notice]"
    }

    /**
     * The prompt a receiving bot's turn starts with (BH-12 bot trigger + hop depth + budget). It tells the bot how to
     * answer (plain text goes back; [STAY_SILENT] when no answer is needed), that it can't use the sender's tools,
     * and whether it may message another bot (hops left) or must answer directly.
     */
    fun botEnvelope(fromName: String, fromLabel: String, threadId: String, depth: Int, maxHops: Int, budgetLeft: Int,
                    expectsReply: Boolean, message: String): String {
        val who = if (fromLabel.isBlank()) fromName else "$fromName \u00b7 $fromLabel"
        val hopsLeft = (maxHops - depth).coerceAtLeast(0)
        val head = "[Message from $who, another bot on your team \u00b7 thread $threadId \u00b7 hop $depth of $maxHops \u00b7 $budgetLeft model calls left in this thread]"
        val how = if (expectsReply) "Your reply (plain text) goes back to $fromName. If no answer is needed, call $STAY_SILENT."
        else "$fromName doesn't need an answer: do what it asks if it is yours to do, then end with $STAY_SILENT."
        val hop = if (hopsLeft > 0) "If you need another teammate, use $SEND_TO_BOT (hops left: $hopsLeft)."
        else "You can't message other bots from here: answer with what you have."
        return "$head\n$message\n\n$how You can't use $fromName's tools and $fromName can't use yours: you decide which of your own tools to use, " +
            "and anything that sends, spends or deletes still needs the user's OK. $hop"
    }

    // ------------------------------------------------------------------------------------------------ handoff card

    /** The HandoffCard's live states (04 §2.8 #30, NEW-05). [live] = still moving (the dotted line animates). */
    enum class HandoffStatus(val live: Boolean, val ended: Boolean) {
        /** Sent; the receiver hasn't started yet. */
        ASKING(true, false),
        /** The receiver is working on it. */
        WORKING(true, false),
        /** The receiver answered; the asker may follow up in the same thread. */
        REPLIED(false, false),
        /** A one-way message (expects_reply=false) was handled. */
        DELIVERED(false, true),
        /** Someone called stay_silent: the exchange ended without a message. */
        SILENT(false, true),
        /** The thread's model-call budget ran out. */
        OUT_OF_BUDGET(false, true),
        /** The receiver's turn failed. */
        FAILED(false, true);

        val wire: String get() = name.lowercase()

        /** The card's status line. [to] = the receiver's name. */
        fun label(to: String): String = when (this) {
            ASKING -> "Asking $to\u2026"
            WORKING -> "$to is on it"
            REPLIED -> "$to replied"
            DELIVERED -> "Delivered"
            SILENT -> "No reply needed"
            OUT_OF_BUDGET -> "Stopped \u00b7 thread budget used"
            FAILED -> "Couldn't reach $to"
        }

        companion object { fun of(s: String?) = values().firstOrNull { it.wire == s?.trim()?.lowercase() } }
    }

    /**
     * The HandoffCard data, as stored in a Row card body (type [CARD_HANDOFF]). The same exchange has one card in each
     * bot's thread; [side] says whose thread it is in ("asker" or "answerer"), and each side's card only holds what
     * that bot sent or received (after redaction), because thread rows are model context.
     */
    data class Handoff(
        val thread: String, val root: String, val from: String, val to: String, val fromName: String, val toName: String,
        val ask: String, val status: HandoffStatus, val reply: String = "", val messages: Int = 1, val depth: Int = 1,
        val budgetLeft: Int = THREAD_BUDGET, val side: String = SIDE_ASKER, val expectsReply: Boolean = true,
        val withheld: Int = 0,
    ) {
        val title: String get() = "$fromName \u2192 $toName: $ask"
        val talk: String get() = talkLine(fromName, toName, messages)
        val statusText: String get() = status.label(toName)
        /** The one line TalkBack reads for the whole card. */
        val spoken: String get() = buildString {
            fun end(t: String) = if (t.isEmpty() || t.last() in ".?!\u2026") t else "$t."
            append("$fromName asked $toName: ${end(ask)} ${end(statusText)}")
            if (reply.isNotBlank()) append(" Reply: ${end(reply)}")
            append(" $talk.")
            if (withheld > 0) append(" $withheld private ${if (withheld == 1) "note" else "notes"} withheld.")
        }

        fun fields(): Map<String, Any?> = linkedMapOf(
            "title" to title, "text" to ask, "thread" to thread, "root" to root, "from" to from, "to" to to,
            "fromName" to fromName, "toName" to toName, "ask" to ask, "status" to status.wire, "statusText" to statusText,
            "reply" to reply, "messages" to messages, "talk" to talk, "depth" to depth, "budgetLeft" to budgetLeft,
            "side" to side, "expectsReply" to expectsReply, "withheld" to withheld, "live" to status.live,
        )

        fun body(): String = RowBody.card(CARD_HANDOFF, fields())

        companion object {
            const val SIDE_ASKER = "asker"
            const val SIDE_ANSWERER = "answerer"

            /** Reads a card row back (null when it isn't a handoff card or is missing its thread). */
            fun of(r: Row): Handoff? = if (r.kind != RowKind.CARD) null else of(r.body)

            fun of(body: String): Handoff? {
                val o = try { JSONObject(body) } catch (_: Exception) { return null }
                if (o.optString("type") != CARD_HANDOFF) return null
                val thread = o.optString("thread").ifEmpty { return null }
                return Handoff(thread, o.optString("root").ifEmpty { thread }, o.optString("from"), o.optString("to"),
                    o.optString("fromName"), o.optString("toName"), o.optString("ask"),
                    HandoffStatus.of(o.optString("status")) ?: HandoffStatus.ASKING, o.optString("reply"),
                    o.optInt("messages", 1), o.optInt("depth", 1), o.optInt("budgetLeft", THREAD_BUDGET),
                    o.optString("side").ifEmpty { SIDE_ASKER }, o.optBoolean("expectsReply", true), o.optInt("withheld", 0))
            }
        }
    }

    /** The collapsed bot↔bot line (05 §2.4): "Money ↔ Travel · 3 messages", in both bots' threads. */
    fun talkLine(a: String, b: String, messages: Int): String =
        "$a \u2194 $b \u00b7 $messages ${if (messages == 1) "message" else "messages"}"

    // ------------------------------------------------------------------------------------------------ redaction

    /**
     * The data-scope check on a payload (05 §2.4, §10.2): a bot can't pass on memory lines from a scope the receiver
     * can't read. [lines] = the memory lines the writer could see this turn (memories ⨝ mem_scope); every one the
     * receiver may NOT read ([MemScopes.visible] with its viewer) is withheld from the text:
     *  1. a payload line in the prompt-block shape "- #12 [money] …" whose id or label names a scope the receiver
     *     can't read is replaced whole ("your notes" = the writer's own `bot:<id>`);
     *  2. the text of every hidden line (≥ [MIN_MATCH] chars), matched case- and whitespace-insensitively, is replaced;
     *  3. a leftover "#12" reference to a hidden line is replaced.
     * Lines tagged `share:<receiver domain>` / `share:bot:<id>` are readable and pass (Ledger → Atlas "budget left").
     * Paraphrases can't be caught deterministically; the receiver's own memory reads stay scope-checked regardless.
     */
    object Redact {
        const val WITHHELD = "[withheld: a note you can't see]"
        const val MIN_MATCH = 8

        data class Out(val text: String, val withheld: List<Long>) { val changed get() = withheld.isNotEmpty() }

        private val blockLine = Regex("""^(\s*[-*\u2022]?\s*)#(\d+)\s*\[([^\]\n]{1,48})\]\s*(.*)$""")

        /**
         * [writer] = the bot whose text this is (its "your notes" label means `bot:<writer>`); [receiver] = who reads
         * it; [now] in unix seconds (expiry), 0 = ignore expiry.
         */
        fun pass(text: String, lines: List<MemScopes.Line>, writer: String, receiver: MemScopes.Viewer, now: Long = 0,
                 names: Map<String, String> = emptyMap()): Out {
            if (text.isEmpty()) return Out(text, emptyList())
            val hidden = lines.filter { !MemScopes.visible(receiver, it, now) }
            val byId = hidden.associateBy { it.id }
            val visibleIds = lines.filter { MemScopes.visible(receiver, it, now) }.map { it.id }.toSet()
            val out = LinkedHashSet<Long>()
            val byName = names.entries.associate { (id, n) -> n.lowercase() to id }

            // 1. prompt-block shaped lines
            var t = text.lines().joinToString("\n") { ln ->
                val m = blockLine.matchEntire(ln) ?: return@joinToString ln
                val id = m.groupValues[2].toLong()
                val scope = labelScope(m.groupValues[3], writer, byName)
                val hiddenById = byId.containsKey(id)
                val hiddenByLabel = id !in visibleIds && scope != null &&
                    !MemScopes.visible(receiver, MemScopes.Line(id = id, text = "", scope = scope), now)
                if (hiddenById || hiddenByLabel) { out += id; m.groupValues[1] + WITHHELD } else ln
            }
            // 2. hidden line texts, longest first
            for (l in hidden.sortedByDescending { it.text.length }) {
                val words = l.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (l.text.trim().length < MIN_MATCH || words.isEmpty()) continue
                val re = Regex(words.joinToString("\\s+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
                if (re.containsMatchIn(t)) { t = re.replace(t, Regex.escapeReplacement(WITHHELD)); out += l.id }
            }
            // 3. "#12" references
            for (l in hidden) {
                val re = Regex("""#${l.id}\b(\s*\[[^\]\n]{1,48}\])?""")
                if (re.containsMatchIn(t)) { t = re.replace(t, Regex.escapeReplacement(WITHHELD)); out += l.id }
            }
            if (out.isNotEmpty()) t = t.replace(Regex("(" + Regex.escape(WITHHELD) + "\\s*){2,}"), Regex.escapeReplacement("$WITHHELD "))
                .replace(Regex("[ \\t]+\n"), "\n").trimEnd()
            return Out(t, out.toList())
        }

        /** A [MemScopes.relevantBlock] label back to its scope ("your notes" → bot:<writer>, "from Atlas" → bot:atlas). */
        fun labelScope(label: String, writer: String, byName: Map<String, String> = emptyMap()): String? {
            val l = label.trim().lowercase()
            return when {
                l == "your notes" -> MemScopes.BOT_PREFIX + writer
                l.startsWith("from ") -> l.removePrefix("from ").trim().let { n -> MemScopes.BOT_PREFIX + (byName[n] ?: n) }
                l == "about the user" -> MemScopes.USER_CORE
                l.startsWith("about the user:") -> MemScopes.USER_PREFIX + l.removePrefix("about the user:").trim()
                else -> MemScopes.parse(l)?.key
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ no side doors

    /**
     * No side doors (05 §2.4): a bot runs only its own tools. When [caller] asks for [tool] (in [group]) that isn't in
     * its tool groups or grants, the note names the teammates that own it so the bot asks them with send_to_bot.
     * Null = the caller may (still subject to Policy). Team tools ([TOOL_NAMES], "team", "jobs", "memory") are every
     * bot's. TeamHooks calls this before Policy.decide when it wires the engine (P0-AG-01).
     */
    fun sideDoor(caller: Bot, tool: String, group: String?, roster: List<Bot>): String? {
        if (tool in TOOL_NAMES || group == null || group in COMMON_GROUPS) return null
        if (group in caller.toolGroups || caller.toolGrants[tool] == Rule.ALLOW || caller.toolGrants[tool] == Rule.ASK) return null
        val owners = roster.filter { it.id != caller.id && !it.hidden && it.enabled && group in it.toolGroups && it.kind != BotKind.ROOM }
        return if (owners.isEmpty()) "Not run: $tool isn't one of your tools."
        else "Not run: $tool belongs to ${owners.joinToString(" / ") { it.name }}, not you. Bots never use each other's tools: " +
            "ask ${owners.first().name} with $SEND_TO_BOT and it decides how to do it."
    }

    /** Groups every bot has for team work (P1 team / jobs tools, memory). */
    val COMMON_GROUPS = setOf("team", "jobs", "memory")

    /**
     * A send_to_bot payload must be a plain-language request, never a tool call for the receiver to execute (that would
     * be a side door around its permissions). True for a JSON object that names a tool / function with arguments.
     */
    fun looksLikeToolCall(message: String): Boolean {
        val t = message.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (!t.startsWith("{")) return false
        val o = try { JSONObject(t) } catch (_: Exception) { return false }
        val named = o.has("tool") || o.has("tool_name") || o.has("function") || (o.has("name") && (o.has("arguments") || o.has("args") || o.has("parameters")))
        return named
    }

    // ------------------------------------------------------------------------------------------------ tool schemas

    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun fn(name: String, d: String, props: JSONObject, req: List<String>) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", d).put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(req))))

    /** The two tools every bot gets (OpenAI-function JSON, like JobTools.schemas). [canSend] = false at the hop cap. */
    fun schemas(canSend: Boolean = true): List<JSONObject> = listOfNotNull(
        if (canSend) fn(SEND_TO_BOT, "Message a teammate bot by name and (by default) wait for its answer. Use it when the work or the facts belong " +
            "to that bot (its domain, its tools, its private notes): it answers with its own tools and permissions; you never use its tools. " +
            "Write a plain-language request with what it needs. The user sees the exchange. Max $MAX_HOPS hops; a thread has $THREAD_BUDGET model calls.",
            JSONObject().put("bot", str("The teammate's name or id"))
                .put("message", str("What you need, in plain language"))
                .put("expects_reply", JSONObject().put("type", "boolean").put("description", "Wait for an answer (default true). false = just tell it."))
                .put("thread", str("Continue an earlier exchange with this bot (thread id from its last answer)")),
            listOf("bot", "message")) else null,
        fn(STAY_SILENT, "End this turn without a message: nothing worth saying (a routine or check-in with nothing new), or a teammate's message needs no answer.",
            JSONObject().put("reason", str("Optional, for the log: why nothing was said")), emptyList()),
    )
}
