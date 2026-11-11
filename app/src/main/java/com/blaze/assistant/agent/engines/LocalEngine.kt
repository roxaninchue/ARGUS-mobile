package com.blaze.assistant.agent.engines

import com.blaze.assistant.agent.TeamHooks
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.EngineKind
import com.blaze.assistant.team.Row
import com.blaze.assistant.team.Rule
import com.blaze.assistant.team.TeamJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * P0-AG-01: the LOCAL engine. A bot's turn runs on today's agent loop (`Agent.Session`, frozen size) in the bot's thread:
 * the turn [TeamHooks.bind]s the thread to the bot (identity, tool subset, row sink), hands the text to the [Driver]
 * (the real one is [AgentSessionDriver]), and maps what the loop reports into team [Row]s ([RowMapper]) for the
 * [RowSink] and the returned flow. Pure apart from the driver, so the mapping runs on the JVM against a fake loop and
 * a fake model (LocalEngineTest).
 */
class LocalEngine(
    private val driver: Driver,
    /** A tool's group (ToolRouter.groupOf on the phone); null = a core tool every bot keeps. */
    private val groupOf: (String) -> String? = { null },
    /** The bot's SOUL.md text, or null. */
    private val soul: (Bot) -> String? = ::readSoul,
    private val clock: () -> Long = System::currentTimeMillis,
) : BotEngine {

    /** Runs text in a chat on the agent loop. [run] returns when the turn has ended (its End was reported). */
    interface Driver {
        suspend fun run(threadId: String, text: String, byVoice: Boolean, untrusted: String?)
        fun cancel(threadId: String)
        fun steer(threadId: String, text: String)
        val maxContextTokens: Int get() = 32_768
        val voice: Boolean get() = true
    }

    private val live = ConcurrentHashMap<String, String>()   // turnId → threadId

    override val kind = EngineKind.LOCAL

    override fun turn(bot: Bot, input: TurnInput, sink: RowSink): Flow<TurnEvent> = channelFlow {
        val t = input.turnId
        val mapper = RowMapper(bot.id, input.generation, input.firstSeq, clock)
        val binding = TeamHooks.Binding(bot.id, identity(bot, soul(bot)), ToolSubset.of(bot, groupOf)) { e ->
            for (row in mapper.map(e)) { runCatching { sink.put(row) }; trySend(TurnEvent.RowOut(t, row)) }
            RowMapper.activity(e)?.let { trySend(TurnEvent.Activity(t, it)) }
        }
        TeamHooks.bind(input.threadId, binding)
        live[t] = input.threadId
        send(TurnEvent.Started(t, bot.id))
        try {
            driver.run(input.threadId, input.text, input.byVoice, input.untrusted)
            // a loop that ended without reporting it still closes its open tool calls
            if (mapper.endReason == null) binding.sink(TeamHooks.Event.End("DONE"))
            send(TurnEvent.Done(t, mapper.lastReply, mapper.endReason ?: "DONE"))
        } catch (e: CancellationException) {
            runCatching { driver.cancel(input.threadId) }   // the collector went away: stop the loop too
            throw e
        } catch (e: Throwable) {
            send(TurnEvent.Failed(t, e.message ?: e.javaClass.simpleName))
        } finally {
            TeamHooks.unbind(input.threadId, binding)
            live.remove(t, input.threadId)
        }
    }.buffer(Channel.UNLIMITED)

    override suspend fun cancel(turnId: String) { live[turnId]?.let { driver.cancel(it) } }

    override suspend fun steer(turnId: String, text: String) { if (text.isNotBlank()) live[turnId]?.let { driver.steer(it, text) } }

    override fun capabilities() = EngineCaps(EngineKind.LOCAL, localTools = true, maxContextTokens = driver.maxContextTokens, voice = driver.voice)

    fun isRunning(turnId: String) = live.containsKey(turnId)

    companion object {
        const val SOUL_MAX = 6_000

        /**
         * The identity text a bound chat gets. The Manager (ARGUS) is today's chat agent: only its SOUL, if any. Every
         * other bot gets its name, label and life area, then its SOUL (clipped to [SOUL_MAX]).
         */
        fun identity(bot: Bot, soul: String?): String {
            val s = soul?.trim().orEmpty().let { if (it.length > SOUL_MAX) it.take(SOUL_MAX) else it }
            if (bot.kind == BotKind.MANAGER) return s
            val head = buildString {
                append("Name: ").append(bot.name)
                if (bot.label.isNotBlank() && bot.label != bot.name) append(" (").append(bot.label).append(")")
                bot.domain?.takeIf { it.isNotBlank() }?.let { append("\nLife area: ").append(it) }
                bot.reportsTo?.let { append("\nReports to: ").append(it) }
            }
            return if (s.isEmpty()) head else "$head\n\n$s"
        }

        fun readSoul(bot: Bot): String? = try { File(bot.soulPath).takeIf { it.isFile }?.readText() } catch (_: Exception) { null }
    }
}

/** P0-AG-01: which tools a bot may use (05 §2 tool groups + per-tool grants). */
object ToolSubset {
    /** A tool group meaning "every tool". */
    const val ALL = "*"

    /** Null = every tool (the Manager, or a bot granted [ALL]); else the rule for one tool name. */
    fun of(bot: Bot, groupOf: (String) -> String?): ((String) -> Boolean)? =
        if (bot.kind == BotKind.MANAGER || ALL in bot.toolGroups) null else { name -> allows(bot, name, groupOf(name)) }

    /**
     * A per-tool grant wins (NEVER removes it; ALLOW or ASK adds it, ASK being the policy layer's job later); otherwise
     * core tools (no group) stay and grouped tools need their group.
     */
    fun allows(bot: Bot, name: String, group: String?): Boolean = when (bot.toolGrants[name]) {
        Rule.NEVER -> false
        Rule.ALLOW, Rule.ASK -> true
        null -> group == null || group in bot.toolGroups
    }
}

/**
 * P0-AG-01: the loop's [TeamHooks.Event]s → Bot HQ rows. One counter gives both seq (at creation) and updatedSeq (at
 * every change), so a reader asking "since N" gets new and changed rows. A tool call is one row that changes from
 * running to done / refused / interrupted. Kinds: USER/BLAZE bubbles → message, STEP → event, ERROR → notice.
 */
class RowMapper(private val botId: String, private val generation: Int, firstSeq: Long, private val clock: () -> Long) {
    private var counter = firstSeq - 1
    private val open = LinkedHashMap<String, Pair<Row, MutableMap<String, Any?>>>()   // callId → (row, body)
    @Volatile var lastReply = ""; private set
    @Volatile var endReason: String? = null; private set

    @Synchronized fun map(e: TeamHooks.Event): List<Row> = when (e) {
        is TeamHooks.Event.Message -> listOf(when (e.role) {
            "USER" -> row("message", linkedMapOf("from" to "user", "text" to e.text))
            "BLAZE" -> { lastReply = e.text; row("message", linkedMapOf("from" to botId, "text" to e.text)) }
            "ERROR" -> row("notice", linkedMapOf("level" to "error", "text" to e.text))
            else -> row("event", linkedMapOf("type" to "step", "text" to e.text))
        })
        is TeamHooks.Event.ToolCall -> {
            val body = linkedMapOf<String, Any?>("callId" to e.callId, "name" to e.name, "args" to e.args, "state" to "running")
            val r = row("tool-call", body)
            open[e.callId] = r to body
            listOf(r)
        }
        is TeamHooks.Event.ToolResult -> {
            val state = if (e.refused) "refused" else "done"
            val o = open.remove(e.callId)
            if (o == null) listOf(row("tool-call", linkedMapOf("callId" to e.callId, "name" to e.name, "state" to state, "output" to e.output)))
            else listOf(update(o, state, e.output))
        }
        is TeamHooks.Event.End -> {
            endReason = e.reason
            val closed = open.values.map { update(it, "interrupted", null) }
            open.clear()
            closed
        }
    }

    private fun row(kind: String, body: Map<String, Any?>): Row {
        val s = ++counter
        return Row(botId, generation, s, s, kind, TeamJson.write(body), clock())
    }

    private fun update(o: Pair<Row, MutableMap<String, Any?>>, state: String, output: String?): Row {
        val body = o.second
        body["state"] = state
        if (output != null) body["output"] = output
        return o.first.copy(updatedSeq = ++counter, body = TeamJson.write(body))
    }

    companion object {
        /** The live line for an event, or null. */
        fun activity(e: TeamHooks.Event): String? = when (e) {
            is TeamHooks.Event.Message -> e.text.takeIf { e.role == "STEP" }
            is TeamHooks.Event.ToolCall -> "Using ${e.name}"
            else -> null
        }
    }
}
