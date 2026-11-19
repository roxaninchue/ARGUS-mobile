package com.blaze.assistant.team

import com.blaze.assistant.store.Sql

/**
 * P1-TM-02: the Bot HQ transcript-row model (BH-10) for every bot thread, stored in team.db `rows` (05 §9.2). It is a
 * pure port of Bot HQ `server/src/store.ts` (appendRow / updateRow / getRow / listRows / clearConversation) and
 * `server/src/types.ts` (Row, RowKind, RowBody), over the same [Sql] seam as [TeamStore], so it runs on the phone
 * (AndroidSql on team.db) and in JVM tests (JdbcSql, RowStoreTest).
 *
 *  - A thread is the rows of one bot in one **generation** (Bot HQ "conversation number"). A fresh start
 *    ([RowStore.fresh], BH-16 `clearConversation`) bumps `bots.generation`; older generations stay on disk for the
 *    history drawer (P1-SH-04) and are never shown in the live thread.
 *  - `seq` orders rows; `updatedSeq` is the thread's revision counter. A row rewritten while it streams, or a tool call
 *    going from running to done, keeps its seq and gets a higher updatedSeq. Readers upsert by seq and ask
 *    "everything with updatedSeq > N" ([RowStore.since], BH `listRows({afterRev})`).
 *  - Bot HQ keeps `next_seq` and `rev` on the bot; here one counter per (bot, generation) gives both (the same rule as
 *    [com.blaze.assistant.agent.engines.RowMapper]): the next number is max(updated_seq) + 1, so a new seq never meets an
 *    older one and an update always moves updatedSeq forward. No schema change (team.db stays v2).
 *  - [RowStore.upsert] takes rows made elsewhere (an engine's [com.blaze.assistant.agent.engines.RowSink]): a row with
 *    a seq already there replaces it only when its updatedSeq is higher, so a late or repeated delivery never rolls a
 *    row back.
 *
 * Every call does disk I/O: run it off the main thread.
 */
object RowKind {
    const val MESSAGE = "message"
    const val TOOL_CALL = "tool-call"
    const val CARD = "card"
    const val EVENT = "event"
    const val NOTICE = "notice"

    /** Bot HQ `RowKind`, in its order. */
    val ALL = listOf(MESSAGE, TOOL_CALL, CARD, EVENT, NOTICE)

    /** Kinds that can be a thread's preview line (BH store.ts toBot: message, card, event; never tool calls or notices). */
    val PREVIEW = listOf(MESSAGE, CARD, EVENT)
}

/**
 * Row bodies. The shapes are the ones [com.blaze.assistant.agent.engines.RowMapper] already writes (message
 * `{from, text}`, event `{type, text}`, notice `{level, text}`, tool-call `{callId, name, args, state, output}`) plus
 * Bot HQ's card and provenance fields (`types.ts` CardBody, MessageBody.from → [via]). `from` is "user" or the bot id.
 */
object RowBodies {
    const val USER = "user"
    const val PREVIEW_MAX = 140

    fun message(from: String, text: String, streaming: Boolean = false, via: Via? = null): String = TeamJson.write(linkedMapOf(
        "from" to from, "text" to text, "streaming" to (if (streaming) true else null), "via" to via?.map()))

    fun event(type: String, text: String, ref: String? = null): String = TeamJson.write(linkedMapOf("type" to type, "text" to text, "ref" to ref))

    fun notice(text: String, level: String = "info"): String = TeamJson.write(linkedMapOf("level" to level, "text" to text))

    /** A card: [card] = approval | question | connect | draft | handoff … (Bot HQ CardBody), [fields] its own keys. */
    fun card(card: String, fields: Map<String, Any?>): String = TeamJson.write(LinkedHashMap<String, Any?>().apply { put("card", card); putAll(fields) })

    /** BH-17 provenance carried on a message that did not come from the user typing: a bot, a routine or a voice call. */
    data class Via(val kind: String /* bot|routine|voice */, val id: String, val name: String) {
        fun map() = linkedMapOf<String, Any?>("kind" to kind, "id" to id, "name" to name)
    }

    /** The body as a map; an unreadable body is an empty map (a bad row never breaks a thread). */
    fun read(body: String): Map<String, Any?> = try {
        @Suppress("UNCHECKED_CAST")
        (TeamJson.read(body) as? Map<String, Any?>) ?: emptyMap()
    } catch (_: Exception) { emptyMap() }

    private fun Map<String, Any?>.s(k: String): String? = (this[k] as? String)?.takeIf { it.isNotBlank() }

    /**
     * The thread's preview line for a row, or null when the row is not one (Bot HQ store.ts `toBot` lastPreview):
     * a message's text; an approval card "Waiting for you: <title>", a question card its question, a connect card
     * "Connect <name>", any other card its title (Bot HQ: "Draft"); an event its text. Clipped to [PREVIEW_MAX].
     */
    fun preview(kind: String, body: String): String? {
        if (kind !in RowKind.PREVIEW) return null
        val b = read(body)
        val p = when (kind) {
            RowKind.MESSAGE -> b.s("text")
            RowKind.CARD -> when (b.s("card")) {
                "approval" -> b.s("title")?.let { "Waiting for you: $it" }
                "question" -> b.s("question") ?: b.s("title")
                "connect" -> (b.s("name") ?: b.s("toolkitName"))?.let { "Connect $it" }
                else -> b.s("title") ?: b.s("text") ?: "Draft"
            }
            else -> b.s("text")
        } ?: return null
        val one = p.trim()
        return if (one.length <= PREVIEW_MAX) one else one.take(PREVIEW_MAX)
    }

    /**
     * Whether a row, as stored now, counts toward the bot's unread badge (Bot HQ loop.ts: `unread + 1` when a reply
     * closes with text, when the bot says something, and when an approval card is posted). A user message never does;
     * a reply still streaming does not until it closes.
     */
    fun countsUnread(kind: String, body: String): Boolean {
        val b = read(body)
        return when (kind) {
            RowKind.MESSAGE -> b["from"] != USER && b.s("text") != null && b["streaming"] != true
            RowKind.CARD -> b["card"] == "approval"
            else -> false
        }
    }
}

/**
 * BH-12: what starts a turn, and the text the bot's loop receives for it (Bot HQ loop.ts `triggerText`). The user's
 * own words go in as they are; a routine, another bot or the app itself are wrapped in a one-line envelope so the
 * model knows who is speaking. [untrusted] is the TurnInput.untrusted mark (ConfirmGate taint): only another bot's
 * words count as untrusted here; P2-SF-01 decides the rest.
 */
sealed class Trigger {
    abstract fun text(): String
    open val untrusted: String? get() = null
    /** The provenance a UI shows on the turn's first row (BH-17), or null for the user typing. */
    open val via: RowBodies.Via? get() = null

    data class User(val text: String, val byVoice: Boolean = false) : Trigger() {
        override fun text() = text
        override val via get() = if (byVoice) RowBodies.Via("voice", "", "voice") else null
    }
    data class Routine(val id: String, val name: String, val prompt: String) : Trigger() {
        override fun text() = "[Routine \"$name\" is running now. Do this:]\n$prompt"
        override val via get() = RowBodies.Via("routine", id, name)
    }
    data class Bot(val fromId: String, val fromName: String, val text: String, val depth: Int = 1) : Trigger() {
        override fun text() = "[Message from $fromName, another Bot on the team. Reply to them with send_to_bot if they asked you something.]\n$text"
        override val untrusted get() = "bot:$fromId"
        override val via get() = RowBodies.Via("bot", fromId, fromName)
    }
    data class System(val text: String) : Trigger() {
        override fun text() = "[System notice] $text"
    }
}

class RowStore(private val sql: Sql, private val clock: () -> Long = { System.currentTimeMillis() }) {

    enum class Upsert { INSERTED, UPDATED, STALE }

    // ---------------- generation ----------------

    /** The bot's current generation, or null when there is no such bot. */
    fun generation(botId: String): Int? =
        sql.query("SELECT generation FROM bots WHERE id=?", botId) { it.long(0).toInt() }.firstOrNull()

    /**
     * BH-16 "New conversation": bumps the bot's generation (Bot HQ `clearConversation`: generation + 1, next_seq = 1).
     * The rows of older generations stay. Unread and the preview start empty; last activity is kept. Returns the new
     * generation, or null when there is no such bot.
     */
    fun fresh(botId: String): Int? = sql.tx {
        val g = generation(botId) ?: return@tx null
        sql.exec("UPDATE bots SET generation=?, unread=0, last_preview=NULL WHERE id=?", g + 1, botId)
        g + 1
    }

    // ---------------- reads ----------------

    /** The next free number in the thread: a new row's seq, and the updatedSeq of the next change. Starts at 1. */
    fun nextSeq(botId: String, generation: Int): Long =
        (sql.query("SELECT max(updated_seq) FROM rows WHERE bot_id=? AND generation=?", botId, generation) { it.long(0) }.firstOrNull() ?: 0L) + 1

    fun get(botId: String, generation: Int, seq: Long): Row? =
        sql.query("SELECT $COLS FROM rows WHERE bot_id=? AND generation=? AND seq=?", botId, generation, seq, row = ::toRow).firstOrNull()

    /**
     * The thread's last [limit] rows (default 200, at most 500), oldest first; with [beforeSeq], the page before it
     * (Bot HQ listRows: newest page first, reversed).
     */
    fun list(botId: String, generation: Int, beforeSeq: Long? = null, limit: Int = 200): List<Row> {
        val n = limit.coerceIn(1, MAX_PAGE)
        val rows = if (beforeSeq != null)
            sql.query("SELECT $COLS FROM rows WHERE bot_id=? AND generation=? AND seq<? ORDER BY seq DESC LIMIT ?", botId, generation, beforeSeq, n, row = ::toRow)
        else sql.query("SELECT $COLS FROM rows WHERE bot_id=? AND generation=? ORDER BY seq DESC LIMIT ?", botId, generation, n, row = ::toRow)
        return rows.reversed()
    }

    /** Every row of the thread added or changed after revision [afterUpdatedSeq], in seq order (BH listRows afterRev). */
    fun since(botId: String, generation: Int, afterUpdatedSeq: Long): List<Row> =
        sql.query("SELECT $COLS FROM rows WHERE bot_id=? AND generation=? AND updated_seq>? ORDER BY seq ASC", botId, generation, afterUpdatedSeq, row = ::toRow)

    /** The newest row that can be a preview line, or null. */
    fun lastPreviewRow(botId: String, generation: Int): Row? =
        sql.query("SELECT $COLS FROM rows WHERE bot_id=? AND generation=? AND kind IN ('message','card','event') ORDER BY seq DESC LIMIT 1",
            botId, generation, row = ::toRow).firstOrNull()

    // ---------------- writes ----------------

    /** Adds a row at the end of the bot's current thread (Bot HQ appendRow). Null when there is no such bot. */
    fun append(botId: String, kind: String, body: String): Row? = sql.tx {
        checkKind(kind)
        val g = generation(botId) ?: return@tx null
        val s = nextSeq(botId, g)
        val r = Row(botId, g, s, s, kind, body, clock())
        insert(r); r
    }

    /** Rewrites a row of the current thread (Bot HQ updateRow): same seq, new body, the next updatedSeq. Null when absent. */
    fun update(botId: String, seq: Long, body: String): Row? = sql.tx {
        val g = generation(botId) ?: return@tx null
        val old = get(botId, g, seq) ?: return@tx null
        val r = old.copy(updatedSeq = nextSeq(botId, g), body = body)
        sql.exec("UPDATE rows SET body=?, updated_seq=? WHERE bot_id=? AND generation=? AND seq=?", r.body, r.updatedSeq, botId, g, seq)
        r
    }

    /**
     * Stores a row made elsewhere, by seq: new → inserted; same seq with a higher updatedSeq → replaced (kind and body);
     * same or lower updatedSeq → ignored as [Upsert.STALE]. Rows of any generation are taken (a turn that started before a
     * fresh start finishes in its own generation).
     */
    fun upsert(r: Row): Upsert = sql.tx {
        checkKind(r.kind)
        require(r.seq >= 1 && r.updatedSeq >= r.seq) { "bad row numbers seq=${r.seq} updatedSeq=${r.updatedSeq}" }
        val old = sql.query("SELECT updated_seq FROM rows WHERE bot_id=? AND generation=? AND seq=?", r.botId, r.generation, r.seq) { it.long(0) }.firstOrNull()
        when {
            old == null -> { insert(r); Upsert.INSERTED }
            r.updatedSeq > old -> {
                sql.exec("UPDATE rows SET updated_seq=?, kind=?, body=? WHERE bot_id=? AND generation=? AND seq=?",
                    r.updatedSeq, r.kind, r.body, r.botId, r.generation, r.seq)
                Upsert.UPDATED
            }
            else -> Upsert.STALE
        }
    }

    private fun insert(r: Row) = sql.exec("INSERT INTO rows(bot_id, generation, seq, updated_seq, kind, body, created_at) VALUES(?,?,?,?,?,?,?)",
        r.botId, r.generation, r.seq, r.updatedSeq, r.kind, r.body, r.createdAt)

    private fun checkKind(kind: String) = require(kind in RowKind.ALL) { "unknown row kind '$kind'" }

    companion object {
        const val MAX_PAGE = 500
        private const val COLS = "bot_id, generation, seq, updated_seq, kind, body, created_at"

        private fun toRow(r: Sql.Row) = Row(r.text(0), r.long(1).toInt(), r.long(2), r.long(3), r.text(4), r.text(5), r.long(6))
    }
}
