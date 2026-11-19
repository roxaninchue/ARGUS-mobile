package com.blaze.assistant.team

import com.blaze.assistant.agent.ChatMeta
import com.blaze.assistant.agent.PrivateChat
import com.blaze.assistant.agent.engines.BotEngine
import com.blaze.assistant.agent.engines.RowSink
import com.blaze.assistant.agent.engines.TurnEvent
import com.blaze.assistant.agent.engines.TurnInput
import com.blaze.assistant.store.Sql
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * P1-TM-02: one thread per bot (D1: one continuous chat with ARGUS + one thread per bot), the repo the Team screen
 * (P1-TM-03) and the Chat screen (P1-SH-04) read. Ports Bot HQ's per-bot chat state: `store.ts toBot` (unread, last
 * preview, last activity), `routes.ts POST /bots/:id/read` and `/bots/:id/fresh` (BH-06, BH-16), the per-bot serial
 * turn queue of `agent/loop.ts` (one turn at a time per bot, BH-11/BH-12), and the `lib/hub.ts` event hub as an
 * in-process flow ([changes], BH-81: rows / cleared / read / live).
 *
 *  - **Every bot but the Manager** keeps its rows in team.db ([RowStore]). Its turns run on its engine ([send]) in the
 *    chat `team-<bot>-g<generation>`: a new generation is a new chat, so a fresh start is a fresh model context while
 *    memory stays (Bot HQ: one Hermes conversation per generation, `hq-<bot>-g<gen>`). Those chats are hidden from the
 *    user's chat list ([userChats]).
 *  - **The Manager (ARGUS)** is today's chat: its thread is read straight from the user's existing chats through
 *    [ArgusChats] (no data move; [ArgusBridge] shows a chat's messages as rows). Its live rows are streamed and counted
 *    (unread, preview) but not copied into team.db; the chat file stays the one record.
 *  - Unread goes up when a bot's reply closes with text or it posts an approval card, never for the user's own words,
 *    and not while the thread is open on screen ([open]); opening or [markRead] clears it.
 *
 * Every read and write does disk I/O: call it off the main thread. Pure apart from the engines it is handed, so the
 * rules run on the JVM (BotThreadsTest, RowStoreTest, ArgusUpgradeTest).
 */
class BotThreads(
    private val sql: Sql,
    private val team: TeamStore,
    private val argus: ArgusChats,
    /** The engine a bot's turns run on (LocalEngine on the phone), or null when it has none yet. */
    private val engineFor: (Bot) -> BotEngine?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val rows = RowStore(sql, clock)

    /** One row of the Team list (BH-06): what the thread shows without opening it. */
    data class Thread(
        val botId: String, val name: String, val generation: Int, val chatId: String?,
        val unread: Int, val preview: String?, val lastActivityAt: Long?,
        val pinned: Boolean, val running: Boolean, val waiting: Int,
    )

    /** What changed (BH-81 hub events, in-process). */
    sealed class Change {
        abstract val botId: String
        data class Rows(override val botId: String, val generation: Int, val rows: List<Row>) : Change()
        data class Cleared(override val botId: String, val generation: Int) : Change()
        data class Read(override val botId: String) : Change()
        data class Live(override val botId: String, val running: Boolean) : Change()
        /** P1-TM-04: a bot was created, edited, switched off or deleted (the Team screen reloads its roster). */
        data class Roster(override val botId: String) : Change()
    }

    sealed class Fresh {
        data class Ok(val generation: Int, val chatId: String?) : Fresh()
        data class Refused(val why: String) : Fresh()
    }

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    /** P1-TM-04: tells listeners the roster changed (the editor and the team tools call it after a save or delete). */
    fun rosterChanged(botId: String) { _changes.tryEmit(Change.Roster(botId)) }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val turns = AtomicLong()
    @Volatile private var viewing: String? = null

    // ---------------- reads ----------------

    /** The chat a bot's turn runs in: the Manager's current chat, else `team-<bot>-g<generation>`. */
    fun chatId(botId: String, generation: Int): String? =
        if (botId == BotRules.MANAGER_ID) argus.current() else teamChatId(botId, generation)

    /** The Team list: the roster with each thread's state, ARGUS first, then pinned, then most recent activity. */
    fun threads(): List<Thread> = team.roster().map { thread(it) }
        .sortedWith(compareBy<Thread>({ it.botId != BotRules.MANAGER_ID }, { !it.pinned }, { -(it.lastActivityAt ?: 0L) }, { it.name.lowercase() }))

    fun thread(botId: String): Thread? = team.get(botId)?.let { thread(it) }

    private fun thread(b: Bot): Thread {
        val c = counters(b.id)
        val waiting = sql.query("SELECT count(*) FROM approvals WHERE bot_id=? AND status='pending'", b.id) { it.long(0).toInt() }.firstOrNull() ?: 0
        val g = c?.generation ?: 0
        if (b.id != BotRules.MANAGER_ID)
            return Thread(b.id, b.name, g, teamChatId(b.id, g), c?.unread ?: 0, c?.preview, c?.lastActivityAt, b.pinned, b.id in running, waiting)
        val cur = argus.current()
        val meta = cur?.let { id -> argus.list().firstOrNull { it.id == id } }
        val preview = meta?.preview?.takeIf { it.isNotBlank() }?.let { clip(it) } ?: c?.preview
        val last = listOfNotNull(meta?.updated?.takeIf { it > 0 }, c?.lastActivityAt).maxOrNull()
        return Thread(b.id, b.name, g, cur, c?.unread ?: 0, preview, last, b.pinned, b.id in running, waiting)
    }

    /**
     * The open thread's rows, oldest first: the last [limit], or the page before [beforeSeq]. For the Manager, the
     * current chat's messages as rows ([ArgusBridge]).
     */
    fun rows(botId: String, beforeSeq: Long? = null, limit: Int = 200): List<Row> {
        val g = rows.generation(botId) ?: return emptyList()
        if (botId != BotRules.MANAGER_ID) return rows.list(botId, g, beforeSeq, limit)
        val all = argusRows(g)
        val upto = if (beforeSeq == null) all else all.filter { it.seq < beforeSeq }
        return upto.takeLast(limit.coerceIn(1, RowStore.MAX_PAGE))
    }

    /** Rows added or changed after revision [afterUpdatedSeq] (catch-up after a reconnect or a resume). */
    fun since(botId: String, afterUpdatedSeq: Long): List<Row> {
        val g = rows.generation(botId) ?: return emptyList()
        return if (botId != BotRules.MANAGER_ID) rows.since(botId, g, afterUpdatedSeq) else argusRows(g).filter { it.updatedSeq > afterUpdatedSeq }
    }

    private fun argusRows(g: Int): List<Row> {
        val id = argus.current() ?: return emptyList()
        return ArgusBridge.rows(argus.read(id) ?: return emptyList(), g)
    }

    fun isRunning(botId: String) = botId in running

    // ---------------- read state ----------------

    /** BH-06: the user opened [botId]'s thread (null = none open). Opening marks it read; rows arriving while open don't count. */
    fun open(botId: String?) { viewing = botId; if (botId != null) markRead(botId) }

    /** Bot HQ `POST /bots/:id/read`: unread = 0. */
    fun markRead(botId: String) {
        sql.exec("UPDATE bots SET unread=0 WHERE id=? AND unread<>0", botId)
        _changes.tryEmit(Change.Read(botId))
    }

    // ---------------- writes ----------------

    /**
     * BH-16 "New conversation": the bot's next turn starts a new generation and a new chat (fresh context, memory kept).
     * Refused while a turn runs (stop it first) and for an unknown bot. For the Manager the app then opens a new chat
     * (P1-SH-04 ✎); the old chats stay in the history drawer.
     */
    fun fresh(botId: String): Fresh {
        if (team.get(botId) == null) return Fresh.Refused("No bot $botId.")
        if (botId in running) return Fresh.Refused("It's still working. Stop it first, then start a new conversation.")
        val g = rows.fresh(botId) ?: return Fresh.Refused("No bot $botId.")
        _changes.tryEmit(Change.Cleared(botId, g))
        return Fresh.Ok(g, chatId(botId, g))
    }

    /** A row the app itself adds to a bot's thread (a notice, an event, a card), counted like any other. */
    fun post(botId: String, kind: String, body: String): Row? {
        require(botId != BotRules.MANAGER_ID) { "the ARGUS thread is the chat itself; post there" }
        val r = rows.append(botId, kind, body) ?: return null
        account(r, before = null)
        _changes.tryEmit(Change.Rows(botId, r.generation, listOf(r)))
        return r
    }

    /** P2-SF-02: a row the app posted gets a new body (a card's final state), counted and passed on like [put]. */
    fun update(botId: String, seq: Long, body: String): Row? {
        val before = rows.generation(botId)?.let { rows.get(botId, it, seq) } ?: return null
        val r = rows.update(botId, seq, body) ?: return null
        account(r, before)
        _changes.tryEmit(Change.Rows(botId, r.generation, listOf(r)))
        return r
    }

    /**
     * The engines' [RowSink]: stores a bot's row (by seq, newer revision wins) and keeps the thread's counters. The
     * Manager's rows are counted and passed on but not stored (its chat file is the record). The row write is one
     * transaction ([RowStore.upsert]); the counters follow it (a crash in between leaves at worst a stale badge).
     */
    fun put(row: Row) {
        if (row.botId != BotRules.MANAGER_ID) {
            val before = rows.get(row.botId, row.generation, row.seq)
            if (rows.upsert(row) == RowStore.Upsert.STALE) return
            account(row, before)
        } else account(row, before = null)
        _changes.tryEmit(Change.Rows(row.botId, row.generation, listOf(row)))
    }

    /** Unread (newly counting rows only, not while open, current generation only), preview and last activity. */
    private fun account(row: Row, before: Row?) {
        val g = rows.generation(row.botId) ?: return
        if (row.generation != g) return
        val counts = RowBodies.countsUnread(row.kind, row.body)
        val counted = before != null && RowBodies.countsUnread(before.kind, before.body)
        if (counts && !counted && viewing != row.botId) sql.exec("UPDATE bots SET unread=unread+1 WHERE id=?", row.botId)
        val p = if (row.botId == BotRules.MANAGER_ID) row.takeIf { it.kind in RowKind.PREVIEW } else rows.lastPreviewRow(row.botId, g)
        val text = p?.let { RowBodies.preview(it.kind, it.body) } ?: return
        sql.exec("UPDATE bots SET last_preview=?, last_activity_at=? WHERE id=?", text, maxOf(p.createdAt, row.createdAt), row.botId)
    }

    // ---------------- turns ----------------

    /**
     * Runs one turn of [botId] for [trigger] on its engine, one turn at a time per bot (a second send waits for the
     * first, Bot HQ's per-bot queue). Every row goes through [put]; the returned flow is the live stream for the open
     * thread. A bot that is unknown, switched off or has no engine fails at once with a plain reason.
     */
    fun send(botId: String, trigger: Trigger): Flow<TurnEvent> = flow {
        val turnId = "turn-$botId-${clock()}-${turns.incrementAndGet()}"
        val bot = team.get(botId)
        val engine = bot?.let(engineFor)
        when {
            bot == null -> { emit(TurnEvent.Failed(turnId, "No bot $botId.")); return@flow }
            !bot.enabled -> { emit(TurnEvent.Failed(turnId, "${bot.name} is switched off.")); return@flow }
            engine == null -> { emit(TurnEvent.Failed(turnId, "${bot.name} has no engine yet.")); return@flow }
        }
        locks.getOrPut(botId) { Mutex() }.withLock {
            val g = rows.generation(botId) ?: 0
            val chat = chatId(botId, g)
            if (chat == null) { emit(TurnEvent.Failed(turnId, "There's no chat open for ${bot!!.name}.")); return@withLock }
            val first = if (botId == BotRules.MANAGER_ID) argusRows(g).maxOfOrNull { it.updatedSeq + 1 } ?: 1L else rows.nextSeq(botId, g)
            running += botId; _changes.tryEmit(Change.Live(botId, true))
            try {
                val input = TurnInput(turnId, chat, trigger.text(), g, first, byVoice = (trigger as? Trigger.User)?.byVoice == true, untrusted = trigger.untrusted)
                emitAll(engine!!.turn(bot!!, input, RowSink { put(it) }))
            } finally {
                running -= botId; _changes.tryEmit(Change.Live(botId, false))
            }
        }
    }

    companion object {
        /** Bot threads' chats (`team-<bot>-g<gen>`): kept by the agent loop like any chat, never listed as the user's. */
        const val TEAM_CHAT_PREFIX = "team-"

        fun teamChatId(botId: String, generation: Int) = "$TEAM_CHAT_PREFIX$botId-g$generation"

        fun isTeamChat(id: String?): Boolean = id != null && id.startsWith(TEAM_CHAT_PREFIX)

        /** The user's own chats: every chat but the bot threads' (ChatStore.list, search, the history drawer). */
        fun userChats(all: List<ChatMeta>): List<ChatMeta> = all.filterNot { isTeamChat(it.id) }

        private fun clip(s: String) = s.trim().let { if (it.length <= RowBodies.PREVIEW_MAX) it else it.take(RowBodies.PREVIEW_MAX) }
    }

    private data class Counters(val generation: Int, val unread: Int, val preview: String?, val lastActivityAt: Long?)

    private fun counters(id: String): Counters? =
        sql.query("SELECT generation, unread, coalesce(last_preview,''), coalesce(last_activity_at,0) FROM bots WHERE id=?", id) { r ->
            Counters(r.long(0).toInt(), r.long(1).toInt(), r.text(2).ifEmpty { null }, r.long(3).takeIf { it > 0 })
        }.firstOrNull()
}

/**
 * P1-TM-02: what the ARGUS thread reads of the user's existing chats (ChatStore on the phone, [ChatStore.argus]). Read
 * only: the bridge never writes, moves or rewrites a chat file. Bot threads' chats and private chats are never served.
 */
interface ArgusChats {
    /** The user's chats, newest first (no bot-thread chats). */
    fun list(): List<ChatMeta>
    /** One chat file's text, or null. */
    fun read(id: String): String?
    /** The chat ARGUS is in now, or null before the first one. */
    fun current(): String?

    companion object {
        fun of(list: () -> List<ChatMeta>, read: (String) -> String?, current: () -> String?): ArgusChats = object : ArgusChats {
            override fun list() = BotThreads.userChats(list())
            override fun read(id: String) = if (BotThreads.isTeamChat(id) || PrivateChat.isPrivateId(id)) null else try { read(id) } catch (_: Exception) { null }
            override fun current() = current()?.takeUnless { BotThreads.isTeamChat(it) }
        }
    }
}

/**
 * P1-TM-02: shows one of the user's chats (ChatJson file) as ARGUS rows, without changing it. Message i (1-based) is
 * row seq i with updatedSeq i; roles map like [com.blaze.assistant.agent.engines.RowMapper]: USER → message from
 * "user", BLAZE (and the old/new brand names) → message from the Manager, STEP → event "step", ERROR → notice "error".
 * Attachments, sources, reactions and the rest stay in the chat file; the Chat screen keeps reading those from it.
 */
object ArgusBridge {
    fun rows(chatText: String, generation: Int): List<Row> {
        @Suppress("UNCHECKED_CAST")
        val o = (try { TeamJson.read(chatText) } catch (_: Exception) { null }) as? Map<String, Any?> ?: return emptyList()
        val updated = (o["updated"] as? Number)?.toLong() ?: 0L
        val msgs = o["messages"] as? List<*> ?: return emptyList()
        val out = ArrayList<Row>(msgs.size)
        msgs.forEachIndexed { i, x ->
            @Suppress("UNCHECKED_CAST") val m = x as? Map<String, Any?> ?: return@forEachIndexed
            val text = m["text"] as? String ?: ""
            val at = (m["at"] as? Number)?.toLong()?.takeIf { it > 0 } ?: updated
            val (kind, body) = when ((m["role"] as? String).orEmpty().uppercase()) {
                "USER" -> RowKind.MESSAGE to RowBodies.message(RowBodies.USER, text)
                "STEP" -> RowKind.EVENT to RowBodies.event("step", text)
                "ERROR" -> RowKind.NOTICE to RowBodies.notice(text, "error")
                else -> RowKind.MESSAGE to RowBodies.message(BotRules.MANAGER_ID, text)   // BLAZE / ARGUS / Blaze
            }
            val s = (i + 1).toLong()
            out += Row(BotRules.MANAGER_ID, generation, s, s, kind, body, at)
        }
        return out
    }
}
