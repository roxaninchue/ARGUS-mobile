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
    override suspend fun run(threadId: String, text: String, byVoice: Boolean, untrusted: String?) {
        val s = Agent.teamSession(threadId)
        if (s.busy) s.job?.join()
        withContext(Dispatchers.Main) { s.send(text, byVoice = byVoice, untrusted = untrusted) }
        s.job?.join()
    }

    override fun cancel(threadId: String) { Agent.teamSession(threadId).stop() }

    override fun steer(threadId: String, text: String) {
        val s = Agent.teamSession(threadId)
        android.os.Handler(android.os.Looper.getMainLooper()).post { s.send(text) }
    }

    override val maxContextTokens: Int get() = Agent.ctxWindow()

    /** The engine every LOCAL bot runs on. */
    val engine: LocalEngine by lazy { LocalEngine(this, groupOf = { ToolRouter.groupOf(it) }) }
}
