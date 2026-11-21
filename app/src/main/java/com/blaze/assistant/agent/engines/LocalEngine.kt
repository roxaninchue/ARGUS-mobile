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
        // P2-SF-01: another bot's message is a bot-started turn; the policy decides it on the lowest permission in the chain
        input.untrusted?.takeIf { it.startsWith("bot:") }?.let { binding.origin = TeamHooks.Origin("bot", listOf(it.removePrefix("bot:"))) }
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
                // owner 21 Nov 2026: "the agents still reply like Argus rather than their role": the role comes first, in words
                append(role(bot.name, bot.label, bot.portfolio, s.isNotEmpty())).append("\n\n")
                append("Name: ").append(bot.name)
                if (bot.label.isNotBlank() && bot.label != bot.name) append(" (").append(bot.label).append(")")
                bot.domain?.takeIf { it.isNotBlank() }?.let { append("\nLife area: ").append(it) }
                bot.reportsTo?.let { append("\nReports to: ").append(it) }
                // P1-TM-04: the editor's A-team fields, and what mode "teammate" means (Bot HQ stored it unused)
                bot.email.takeIf { it.isNotBlank() }?.let { append("\nEmail: ").append(it) }
                bot.portfolio.takeIf { it.isNotBlank() }?.let { append("\nPortfolio: ").append(it) }
                if (bot.mode == "teammate") append("\nMode: teammate. You work alongside the other bots: take work they hand you, and save what the whole team should know with note_team.")
            }
            return if (s.isEmpty()) head else "$head\n\n$s"
        }

        private val ROLES = listOf(
            "research" to "dig into a topic: search the web, read the sources, check the facts against each other, and report clear findings with their sources",
            "writ" to "write and edit: drafts, rewrites, emails, posts and documents, in the user's voice",
            "cod|dev|engineer|program" to "write, run, test and fix code in the terminal, and explain what changed",
            "design" to "design: layouts, visuals, slides and the look of things",
            "plan|travel|trip" to "plan: trips, schedules and step-by-step plans with real times, places and prices",
            "money|financ|budget|account" to "money: budgets, spending, bills and the numbers behind a decision",
            "fit|health|coach|gym" to "health and fitness: workouts, habits and progress",
            "shop|buy|deal" to "shopping: find, compare and track things to buy",
            "news|brief|digest" to "news: follow topics and sum up what changed",
            "study|tutor|learn|teach" to "teaching: explain topics step by step and quiz the user")

        /** The opening of a bot's identity: who it is, what its job is, and that it is not the Manager. Pure. */
        fun role(name: String, label: String, portfolio: String, hasSoul: Boolean): String {
            val l = label.trim().lowercase().takeUnless { it.isEmpty() || it == "bot" || it == name.trim().lowercase() } ?: name.trim().lowercase()
            val job = portfolio.trim().replace(Regex("\\s+"), " ").take(300).trimEnd('.', ' ').ifEmpty {
                if (hasSoul) "" else ROLES.firstOrNull { Regex(it.first).containsMatchIn(l) }?.second ?: "the $l work the user gives you" }
            return "You are $name, the user's $l bot on their team. You are not the Manager: always speak as $name, in the first person." +
                (if (job.isNotEmpty()) " Your job: $job." else "") +
                " Asked who you are or what you can do, answer for this role in two or three lines, never the Manager's full list of abilities. " +
                "Work outside your role: do it only if it is quick, otherwise say it fits the Manager better and offer to hand it over."
        }

        fun readSoul(bot: Bot): String? = try { File(bot.soulPath).takeIf { it.isFile }?.readText() } catch (_: Exception) { null }
    }
}

/** P0-AG-01: which tools a bot may use (05 §2 tool groups + per-tool grants). */
object ToolSubset {
    /** A tool group meaning "every tool". */
    const val ALL = "*"

    /**
     * P1-TM-04: tools every bot has whatever its groups (A-team BASE_TOOLS): update_agent (its own name, role, email,
     * portfolio) and note_team (a note in the team's shared memory). A NEVER grant still removes them.
     */
    val SELF = setOf("update_agent", "note_team")

    /** Owner 2026-11-20 ("Argus + all agents get terminal use"): the group every bot has whatever it was given. A NEVER grant still removes a tool. */
    const val EVERYONE = "terminal"

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
        null -> group == null || group in bot.toolGroups || name in SELF || group == EVERYONE   // owner 2026-11-20: every agent has the terminal
    }
}

/**
 * P0-AG-01: the loop's [TeamHooks.Event]s → Bot HQ rows. One counter gives both seq (at creation) and updatedSeq (at
 * every change), so a reader asking "since N" gets new and changed rows. A tool call is one row that changes from
 * running to done / refused / interrupted. Kinds: USER/BLAZE bubbles → message, STEP → event, ERROR → notice.
 *
 * Bot-chat fix 2026-11-20 (Vega's "Hey…" saved as "Hey"): the loop opens a streamed reply with its first token and
 * grows it in place, so a reply bubble is one row that changes too. [TeamHooks.Event.Revise] frames rewrite the open
 * reply row (passed on at most every [REVISE_MS], so team.db isn't written per frame; the newest one always lands
 * before anything else and at the end), and [TeamHooks.Event.Reply] writes its final text. A streamed bubble the
 * loop dropped and started again (a retry) keeps its row; one that turned into narration becomes a step.
 */
class RowMapper(private val botId: String, private val generation: Int, firstSeq: Long, private val clock: () -> Long) {
    private var counter = firstSeq - 1
    private val open = LinkedHashMap<String, Pair<Row, MutableMap<String, Any?>>>()   // callId → (row, body)
    /** The reply bubble the stream opened and no final [TeamHooks.Event.Reply] closed yet; [streamed] once a frame grew it. */
    private class Live(var row: Row, var streamed: Boolean, var pending: Row? = null, var sentAt: Long)
    private var live: Live? = null
    @Volatile var lastReply = ""; private set
    @Volatile var endReason: String? = null; private set

    @Synchronized fun map(e: TeamHooks.Event): List<Row> {
        if (e is TeamHooks.Event.Revise) return revise(e)
        val flushed = if (e is TeamHooks.Event.Reply) { live?.pending = null; emptyList() } else flush()   // the final text supersedes a held frame
        return flushed + when (e) {
            is TeamHooks.Event.Message -> listOf(when (e.role) {
                "USER" -> { live = null; row("message", linkedMapOf("from" to "user", "text" to e.text)) }
                "BLAZE" -> reopen(e.text)
                "ERROR" -> row("notice", linkedMapOf("level" to "error", "text" to e.text))
                else -> row("event", linkedMapOf("type" to "step", "text" to e.text))
            })
            is TeamHooks.Event.Reply -> listOf(reply(e))
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
                open.clear(); live = null
                closed
            }
            is TeamHooks.Event.Revise -> emptyList()   // handled above
        }
    }

    /** An assistant bubble: a new row, or (a stream the loop dropped and opened again) the open streamed reply's row. */
    private fun reopen(text: String): Row {
        lastReply = text
        val l = live
        if (l != null && l.streamed) { val r = message(l.row, text); l.row = r; l.streamed = false; l.sentAt = clock(); return r }
        val r = row("message", linkedMapOf("from" to botId, "text" to text))
        live = Live(r, streamed = false, sentAt = clock())
        return r
    }

    private fun revise(e: TeamHooks.Event.Revise): List<Row> {
        val l = live ?: return emptyList()
        if (e.role == "STEP") {   // the streamed text became narration before a tool call
            live = null
            return listOf(l.row.copy(updatedSeq = ++counter, kind = "event", body = TeamJson.write(linkedMapOf("type" to "step", "text" to e.text))))
        }
        if (e.role != "BLAZE") return emptyList()
        lastReply = e.text
        l.streamed = true
        val r = message(l.row, e.text)
        l.row = r
        val now = clock()
        if (now - l.sentAt < REVISE_MS) { l.pending = r; return emptyList() }
        l.pending = null; l.sentAt = now
        return listOf(r)
    }

    private fun reply(e: TeamHooks.Event.Reply): Row {
        lastReply = e.text
        val l = live
        live = null
        return if (l != null && (e.streamed || l.streamed)) message(l.row, e.text)
        else row("message", linkedMapOf("from" to botId, "text" to e.text))
    }

    /** The newest throttled frame, before anything else lands. */
    private fun flush(): List<Row> {
        val p = live?.pending ?: return emptyList()
        live?.pending = null
        return listOf(p)
    }

    private fun message(r: Row, text: String) = r.copy(updatedSeq = ++counter, kind = "message", body = TeamJson.write(linkedMapOf("from" to botId, "text" to text)))

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
        /** How often a streamed reply's growing row is passed on (team.db and the live thread); the final text always is. */
        const val REVISE_MS = 400L

        /**
         * The live line for an event, or null. P1-SH-04: on its way to a screen, so it is shown-safe: a step's text only
         * when [com.blaze.assistant.agent.StepPill.clean] passes it (no raw tool, model or provider names, code or
         * URLs), a tool call by what it does ([com.blaze.assistant.agent.StepTool.label]), never by its name.
         */
        fun activity(e: TeamHooks.Event): String? = when (e) {
            is TeamHooks.Event.Message -> if (e.role == "STEP") com.blaze.assistant.agent.StepPill.clean(e.text) else null
            is TeamHooks.Event.ToolCall -> com.blaze.assistant.agent.StepTool.label(e.name).now
            else -> null
        }
    }
}
