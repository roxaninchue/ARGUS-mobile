package com.blaze.assistant.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * P0-AG-01: the one seam between the agent loop (`Agent.Session`) and the team layer. A [com.blaze.assistant.agent.engines.LocalEngine]
 * turn [bind]s a chat id to a bot for the length of the turn; the loop then asks this object three things and tells it four:
 *
 *  - asks: the bot's identity block for the system prompt ([identityBlock]), whether a tool is in the bot's subset
 *    ([allows] / [refuse]), and nothing else;
 *  - tells: every chat bubble it adds ([message]), every tool call it starts and its result ([call] / [result]), and the
 *    end of the turn ([end]). These go to the binding's sink, which LocalEngine turns into team `Row`s.
 *
 * A chat with no binding (every chat today) costs one empty-map check per hook and behaves exactly as before. A sink
 * that throws never breaks the loop. Pure Kotlin (no Android, no org.json), so its rules are JVM-tested (TeamHooksTest).
 * `Agent.kt` holds only the one-line calls (≤ 20 lines in total, owner rule); everything else lives here and in
 * `agent/engines/`.
 */
object TeamHooks {

    /** What the loop reports for a bound chat. Text is already scrubbed of saved secrets by the caller. */
    sealed class Event {
        /** A chat bubble: [role] is the loop's Role name (USER, BLAZE, STEP, ERROR). */
        data class Message(val role: String, val text: String) : Event()
        data class ToolCall(val callId: String, val name: String, val args: String) : Event()
        data class ToolResult(val callId: String, val name: String, val output: String, val refused: Boolean = false) : Event()
        /** The turn ended; [reason] is the loop's TurnEnd.Reason name (DONE, STOPPED, …). */
        data class End(val reason: String) : Event()
    }

    /**
     * One bot's hold on one chat for one turn. [identity] is the bot's SOUL / persona text ("" = none). [allow] = the
     * bot's tool subset, null = every tool (the Manager). [sink] receives the [Event]s, on whatever thread the loop runs.
     */
    class Binding(val botId: String, val identity: String, val allow: ((String) -> Boolean)?, val sink: (Event) -> Unit)

    private val bound = ConcurrentHashMap<String, Binding>()
    private val calls = ConcurrentHashMap<String, ArrayDeque<Pair<String, String>>>()   // chat → open (callId, tool)
    @Volatile private var nextCall = 0L

    fun bind(chatId: String, b: Binding) { bound[chatId] = b; calls.remove(chatId) }

    /** Releases [chatId]; with [b], only if it is still that binding (a newer turn may have re-bound the chat). */
    fun unbind(chatId: String, b: Binding? = null) {
        if (b == null) bound.remove(chatId) else bound.remove(chatId, b)
        if (bound[chatId] == null) calls.remove(chatId)
    }

    fun binding(chatId: String): Binding? = if (bound.isEmpty()) null else bound[chatId]
    fun isBound(chatId: String): Boolean = binding(chatId) != null

    /** The system message a bound chat gets after the app's own prompt; null when unbound or the bot has no identity. */
    fun identityBlock(chatId: String): String? = binding(chatId)?.let { identityText(it.identity) }

    /** Whether [tool] may run in [chatId]: always true unbound, else the bot's subset. */
    fun allows(chatId: String, tool: String): Boolean = binding(chatId)?.allow?.let { f -> safe { f(tool) } ?: false } ?: true

    /** The tool result for a call outside the bot's subset (reported as a refused result), or null to run it. */
    fun refuse(chatId: String, tool: String): String? {
        if (allows(chatId, tool)) return null
        val out = refusal(tool)
        emit(chatId, Event.ToolResult(newCallId(chatId), tool, out, refused = true))
        return out
    }

    fun message(chatId: String, role: String, text: String) { if (text.isNotBlank()) emit(chatId, Event.Message(role, text)) }

    /** A tool call starts; its [result] pairs with the oldest open call of the same tool (parallel calls stay paired). */
    fun call(chatId: String, tool: String, args: String) {
        val b = binding(chatId) ?: return
        val id = newCallId(chatId)
        synchronized(this) { calls.getOrPut(chatId) { ArrayDeque() }.addLast(id to tool) }
        deliver(b, Event.ToolCall(id, tool, clip(args, ARGS_MAX)))
    }

    fun result(chatId: String, tool: String, output: String) {
        val b = binding(chatId) ?: return
        val id = synchronized(this) { calls[chatId]?.let { q -> q.firstOrNull { it.second == tool }?.also { q.remove(it) }?.first } } ?: newCallId(chatId)
        deliver(b, Event.ToolResult(id, tool, clip(output, OUTPUT_MAX)))
    }

    fun end(chatId: String, reason: String) = emit(chatId, Event.End(reason))

    // ---------------- rules (pure) ----------------

    const val ARGS_MAX = 4_000
    const val OUTPUT_MAX = 4_000

    fun identityText(identity: String): String? = identity.trim().takeIf { it.isNotEmpty() }?.let {
        "[Team bot]\nIn this chat you are the team bot described below. Its name, voice and focus override any earlier " +
            "name or persona; every other rule above still applies.\n\n" + it
    }

    fun refusal(tool: String) = "Error: \"$tool\" isn't one of this bot's tools. Use a tool you were given, or hand the job to the Manager."

    fun clip(s: String, max: Int): String = if (s.length <= max) s else s.take(max) + "… [${s.length - max} more chars]"

    private fun newCallId(chatId: String) = "tc-" + synchronized(this) { ++nextCall }

    private fun emit(chatId: String, e: Event) { binding(chatId)?.let { deliver(it, e) } }

    private fun deliver(b: Binding, e: Event) { safe { b.sink(e) } }

    private inline fun <T> safe(f: () -> T): T? = try { f() } catch (e: Throwable) { if (e is InterruptedException) throw e; null }
}
