package com.blaze.assistant.team

import org.json.JSONArray
import org.json.JSONObject

/**
 * P1-TM-02: one thread per bot (D1) with unread count, preview line and last activity, kept in the bots table's
 * thread columns (05 §9.2) through [TeamPort.thread].
 *
 * ARGUS's thread is the existing Argus main conversation: its messages stay in ChatStore (filesDir/chats, no data
 * move, AM history intact). [timeline] for the Manager shows the current AM chat ([ArgusBridge], read-only) merged
 * by time with the team rows ARGUS gets from the runtime (handoff cards, job summaries), which live in team.db.
 */
class BotThreads(
    private val port: TeamPort,
    val rows: Rows,
    private val team: TeamStore,
    private val argus: ArgusChat? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The current Argus main chat file (ChatStore JSON), null when there is none yet. */
    fun interface ArgusChat { fun current(): JSONObject? }

    data class Summary(val bot: Bot, val unread: Int, val preview: String, val lastActivityAt: Long?, val generation: Int)

    fun state(botId: String) = port.thread(botId) ?: ThreadState(botId)

    /** A message in [botId]'s thread. [viewing] = the user has that thread open (no unread). */
    fun post(botId: String, role: String, text: String, from: String = if (role == RowBody.USER) RowBody.USER else botId,
             jobId: String? = null, viewing: Boolean = false): Row =
        rows.append(botId, RowKind.MESSAGE, RowBody.message(role, text, from, jobId = jobId)).also { touch(it, viewing) }

    fun card(botId: String, type: String, fields: Map<String, Any?>, viewing: Boolean = false): Row =
        rows.append(botId, RowKind.CARD, RowBody.card(type, fields)).also { touch(it, viewing) }

    fun notice(botId: String, text: String, level: String = "info", viewing: Boolean = false): Row =
        rows.append(botId, RowKind.NOTICE, RowBody.notice(text, level)).also { touch(it, viewing) }

    /** Updates preview / last activity / unread for a row that was just written (by this class or an engine's sink). */
    @Synchronized fun touch(r: Row, viewing: Boolean = false) {
        val t = state(r.botId)
        val p = RowBody.preview(r)
        val fromUser = r.kind == RowKind.MESSAGE && RowBody.role(r) == RowBody.USER
        val counts = !viewing && !fromUser && (r.kind == RowKind.MESSAGE || r.kind == RowKind.CARD || r.kind == RowKind.NOTICE)
        port.putThread(t.copy(lastPreview = p ?: t.lastPreview, lastActivityAt = maxOf(t.lastActivityAt ?: 0, r.createdAt),
            unread = if (fromUser || viewing) 0 else t.unread + (if (counts) 1 else 0)))
    }

    @Synchronized fun markRead(botId: String) { val t = state(botId); if (t.unread != 0) port.putThread(t.copy(unread = 0)) }

    /** "New chat" for a bot: fresh context, rows kept for the history drawer. ARGUS's ✎ stays the AM new-chat flow (P1-SH-04). */
    fun newChat(botId: String): Int = rows.newGeneration(botId)

    /** The AM main chat was saved: refresh ARGUS's preview / activity / unread from its last message. */
    @Synchronized fun argusSaved(chat: JSONObject, viewing: Boolean) {
        val last = ArgusBridge.last(chat) ?: return
        val t = state(Bots.MANAGER_ID)
        val at = last.second.takeIf { it > 0 } ?: chat.optLong("updated", clock())
        if (t.lastActivityAt != null && at <= t.lastActivityAt && t.lastPreview == last.first) return
        val isUser = last.first.startsWith("You: ")
        port.putThread(t.copy(lastPreview = last.first, lastActivityAt = maxOf(t.lastActivityAt ?: 0, at),
            unread = if (viewing || isUser) 0 else t.unread + 1))
    }

    /** What the thread shows, oldest first. */
    fun timeline(botId: String): List<Row> {
        val own = rows.since(botId, 0)
        if (botId != Bots.MANAGER_ID) return own
        val am = argus?.current()?.let { ArgusBridge.rows(it) } ?: emptyList()
        return (am + own).sortedWith(compareBy({ it.createdAt }, { if (it.generation == ArgusBridge.GENERATION) 0 else 1 }, { it.seq }))
    }

    fun summary(b: Bot): Summary {
        val t = state(b.id)
        return Summary(b, t.unread, t.lastPreview.orEmpty(), t.lastActivityAt, t.generation)
    }

    /**
     * The Team list order (04 §4.1, A-team Home): ARGUS first (Lead), then pinned bots, then the rest by last
     * activity (newest first), never-used bots last in roster order. Hidden workers never show.
     */
    fun list(): List<Summary> {
        val bots = team.all()
        val s = bots.map { summary(it) }
        val m = s.first { Bots.isManager(it.bot) }
        val rest = s.filter { !Bots.isManager(it.bot) }
        val order = compareByDescending<Summary> { it.bot.pinned }.thenByDescending { it.lastActivityAt ?: Long.MIN_VALUE }
        return listOf(m) + rest.withIndex().sortedWith(compareBy<IndexedValue<Summary>, Summary>(order) { it.value }.thenBy { it.index }).map { it.value }
    }

    fun totalUnread(): Int = list().sumOf { it.unread }
}

/**
 * P1-TM-02: reads the Argus main conversation (ChatStore JSON, see ChatJson) as [Row]s for ARGUS's thread. Read only:
 * nothing is copied or rewritten, so the AM chat history stays exactly where and as it is. Pure.
 */
object ArgusBridge {
    /** Bridged rows carry this generation so they never collide with team.db rows. */
    const val GENERATION = -1

    fun rows(chat: JSONObject): List<Row> {
        val ms = chat.optJSONArray("messages") ?: JSONArray()
        val fallback = chat.optLong("updated", 0)
        return (0 until ms.length()).mapNotNull { i -> ms.optJSONObject(i)?.let { row(it, i, fallback) } }
    }

    private fun row(m: JSONObject, i: Int, fallback: Long): Row {
        val text = m.optString("text")
        val at = m.optLong("at").takeIf { it > 0 } ?: fallback
        val seq = (i + 1).toLong()
        val (kind, body) = when (m.optString("role").uppercase()) {
            "USER" -> RowKind.MESSAGE to RowBody.message(RowBody.USER, text)
            "STEP" -> RowKind.EVENT to RowBody.event("step", text)
            "ERROR" -> RowKind.NOTICE to RowBody.notice(text, "error")
            else -> RowKind.MESSAGE to RowBody.message(RowBody.ASSISTANT, text, Bots.MANAGER_ID)   // BLAZE / ARGUS (ChatJson.roleOf)
        }
        return Row(Bots.MANAGER_ID, GENERATION, seq, seq, kind, body, at)
    }

    /** The preview line and time of the chat's last user or assistant message. */
    fun last(chat: JSONObject): Pair<String, Long>? {
        val r = rows(chat).lastOrNull { it.kind == RowKind.MESSAGE && RowBody.text(it).isNotBlank() } ?: return null
        return RowBody.preview(r)!! to r.createdAt
    }
}
