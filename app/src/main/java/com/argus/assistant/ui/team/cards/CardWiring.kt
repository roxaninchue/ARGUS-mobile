package com.blaze.assistant.ui.team.cards

import com.blaze.assistant.policy.PolicyRuntime
import com.blaze.assistant.team.JobsRuntime
import com.blaze.assistant.ui.team.thread.ThreadActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * P2-SF-02: what a team thread's cards do on the phone. OkCard ("Needs your ok"): Done closes the job through the job
 * queue (its dependants may start), Tomorrow moves its due date a day (JobQueue.tomorrow); approval / draft / question /
 * connect rows answer the live card (PolicyRuntime). The bot thread screen passes [base] for its own actions (undo,
 * files, handoffs) and gets these added.
 */
object CardWiring {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun threadActions(base: ThreadActions = ThreadActions()): ThreadActions = base.copy(
        jobDone = { id -> io.launch { runCatching { JobsRuntime.queue()?.done(id, null) }; JobsRuntime.kick() } },
        jobTomorrow = { id -> io.launch { runCatching { JobsRuntime.queue()?.tomorrow(id) } } },
        liveCard = { id -> PolicyRuntime.center.card(id) },
        answerCard = { id, a -> PolicyRuntime.answer(id, a) },
    )
}
