package com.blaze.assistant.team

import org.json.JSONObject

/**
 * P1-TM-02: every bot's chat is a list of Bot HQ transcript rows (05 §9.1 [Row]; BH `rows` with seq / updatedSeq /
 * generation). Rules:
 * - one counter per bot: a new row takes the next value as both `seq` and `updatedSeq`; changing a row (a streaming
 *   reply, a card that got answered) keeps its `seq` and takes the next value as `updatedSeq`, so a reader that
 *   remembers the last `updatedSeq` it saw gets exactly the rows that are new or changed ([since]);
 * - `generation` is the bot's context: "new chat" (✎, 06 R4/D1) bumps it — the old rows stay (history drawer),
 *   the model sees only the current generation, memory is kept;
 * - kinds: message · tool-call · card · event · notice. The body is JSON ([RowBody]).
 * Pure over [TeamPort] (org.json is the real one in JVM tests).
 */
class Rows(private val port: TeamPort, private val clock: () -> Long = System::currentTimeMillis) {

    fun generation(botId: String): Int = port.thread(botId)?.generation ?: 0

    @Synchronized private fun next(botId: String) = port.maxSeq(botId) + 1

    /** Adds a row to the bot's current generation. */
    @Synchronized fun append(botId: String, kind: String, body: String): Row {
        require(kind in RowKind.ALL) { "unknown row kind $kind" }
        val n = next(botId)
        val r = Row(botId, generation(botId), n, n, kind, body, clock())
        port.putRow(r); return r
    }

    /** Replaces a row's body (same seq, new updatedSeq). Null when there is no such row in the current generation. */
    @Synchronized fun update(botId: String, seq: Long, body: String): Row? {
        val old = port.row(botId, generation(botId), seq) ?: return null
        if (old.body == body) return old
        val r = old.copy(body = body, updatedSeq = next(botId)); port.putRow(r); return r
    }

    /**
     * Upsert by seq (a row from an engine or a sync): an unknown seq is inserted as is; a known one is replaced when
     * the incoming [Row.updatedSeq] is newer. Returns the row now stored.
     */
    @Synchronized fun upsert(r: Row): Row {
        require(r.kind in RowKind.ALL) { "unknown row kind ${r.kind}" }
        val old = port.row(r.botId, r.generation, r.seq)
        if (old != null && old.updatedSeq >= r.updatedSeq) return old
        port.putRow(r); return r
    }

    /** Rows of the current generation that are new or changed after [updatedSeq], in seq order. */
    fun since(botId: String, updatedSeq: Long = 0): List<Row> = port.rows(botId, generation(botId), updatedSeq)

    fun all(botId: String, generation: Int = generation(botId)): List<Row> = port.rows(botId, generation, 0)

    /** "New chat": a fresh context for this bot. Returns the new generation. */
    @Synchronized fun newGeneration(botId: String): Int {
        val t = port.thread(botId) ?: ThreadState(botId)
        val g = t.generation + 1
        port.putThread(t.copy(generation = g, unread = 0)); return g
    }

    /** The generations that hold rows, newest first (history drawer over generations, 06 R4). */
    fun generations(botId: String): List<Int> = (generation(botId) downTo 0).filter { port.rows(botId, it, 0).isNotEmpty() }
}

object RowKind {
    const val MESSAGE = "message"; const val TOOL_CALL = "tool-call"; const val CARD = "card"; const val EVENT = "event"; const val NOTICE = "notice"
    val ALL = setOf(MESSAGE, TOOL_CALL, CARD, EVENT, NOTICE)
}

/** Row bodies. Field names follow Bot HQ (`role`, `text`, `status`) so a later sync or Hermes engine reads them as is. */
object RowBody {
    const val USER = "user"; const val ASSISTANT = "assistant"; const val SYSTEM = "system"

    /** A chat message. [from] = who wrote it: "user", a bot id, or "bot:<id>" for a bot-to-bot message. */
    fun message(role: String, text: String, from: String = role, status: String = "done", jobId: String? = null): String =
        JSONObject().put("role", role).put("text", text).put("from", from).put("status", status).apply { if (jobId != null) put("job", jobId) }.toString()

    fun toolCall(name: String, title: String, state: String, args: String = "", result: String = ""): String =
        JSONObject().put("name", name).put("title", title).put("state", state).put("args", args).put("result", result).toString()

    /** A card (handoff, job, approval later in P2). [fields] are flat strings. */
    fun card(type: String, fields: Map<String, Any?>): String = JSONObject().put("type", type).apply { fields.forEach { (k, v) -> if (v != null) put(k, v) } }.toString()

    fun event(type: String, text: String): String = JSONObject().put("type", type).put("text", text).toString()

    fun notice(text: String, level: String = "info"): String = JSONObject().put("text", text).put("level", level).toString()

    private fun obj(body: String): JSONObject? = try { JSONObject(body) } catch (_: Exception) { null }

    fun role(r: Row): String = obj(r.body)?.optString("role").orEmpty()
    fun text(r: Row): String = obj(r.body)?.optString("text").orEmpty()
    fun type(r: Row): String = obj(r.body)?.optString("type").orEmpty()
    fun field(r: Row, key: String): String = obj(r.body)?.optString(key).orEmpty()

    /** The one line a chat list shows for this row (null = this row doesn't change the preview). */
    fun preview(r: Row): String? = when (r.kind) {
        RowKind.MESSAGE -> text(r).replace(Regex("\\s+"), " ").trim().take(120).ifEmpty { null }?.let { if (role(r) == USER) "You: $it" else it }
        RowKind.CARD -> field(r, "title").ifEmpty { field(r, "text") }.trim().take(120).ifEmpty { null }
        RowKind.NOTICE -> text(r).trim().take(120).ifEmpty { null }
        else -> null
    }
}
