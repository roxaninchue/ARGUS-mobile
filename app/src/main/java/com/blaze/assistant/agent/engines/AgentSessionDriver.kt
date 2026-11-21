package com.blaze.assistant.agent.engines

import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.ToolRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P0-AG-01: [LocalEngine]'s driver on the phone: the bot's thread is an ordinary `Agent.Session` (loaded or created by
 * [Agent.teamSession]), a turn is `Session.send` + waiting for its job, Stop is `Session.stop`, and a steer is another
 * `send` while it works (the loop reads it at its next step, like any message sent mid-turn). Android glue only; the
 * rules live in LocalEngine / TeamHooks. Not used by any screen yet (P1-TM-02).
 */
object AgentSessionDriver : LocalEngine.Driver {
    /**
     * Bot chat redesign: files the user attached in a bot's thread ride its next turn. The screen stages them for the
     * bot's chat ([BotFiles.stage]) and then sends through BotThreads, so the turn keeps the bot's binding.
     */
    val files = BotFiles<com.blaze.assistant.agent.Attachment>()

    override suspend fun run(threadId: String, text: String, byVoice: Boolean, untrusted: String?) {
        val s = Agent.teamSession(threadId)
        if (s.busy) s.job?.join()
        val attached = if (untrusted == null) files.take(threadId) else emptyList()   // the user's own message only
        withContext(Dispatchers.Main) { s.send(text, byVoice = byVoice, attachments = attached, untrusted = untrusted) }
        s.job?.join()
    }

    override fun cancel(threadId: String) { Agent.teamSession(threadId).stop() }

    override fun steer(threadId: String, text: String) {
        val s = Agent.teamSession(threadId)
        android.os.Handler(android.os.Looper.getMainLooper()).post { s.send(text) }
    }

    override val maxContextTokens: Int get() = Agent.ctxWindow()

    /** The engine every LOCAL bot runs on. */
    /** A new bot's hello into its own chat ([BotThreads.greet]). */
    fun greet(chatId: String, text: String) = Agent.greet(chatId, text)

    val engine: LocalEngine by lazy { LocalEngine(this, groupOf = { ToolRouter.groupOf(it) }) }
}

/**
 * Bot chat redesign: attachments waiting for one chat's next user turn. Per chat, so a file staged in one bot's thread
 * never rides another bot's or the main chat's turn. Pure (BotChatTest).
 */
class BotFiles<T> {
    private val staged = java.util.concurrent.ConcurrentHashMap<String, List<T>>()
    fun stage(chatId: String, files: List<T>) { if (files.isEmpty()) staged.remove(chatId) else staged[chatId] = files }
    fun take(chatId: String): List<T> = staged.remove(chatId).orEmpty()
    fun waiting(chatId: String): Int = staged[chatId]?.size ?: 0
}
