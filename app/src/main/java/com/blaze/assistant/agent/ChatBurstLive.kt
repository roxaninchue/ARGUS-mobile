package com.blaze.assistant.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.blaze.assistant.voice.Voice

/**
 * Android glue for [ChatBurst] (2026-11-13 double-text fix). Agent.send() calls [follow] for a message that arrives while
 * the main chat is replying and BusyRoute calls it SIDE (it used to go to the side lane). Nothing here is dropped:
 *  - MAIN: the message is queued like a mid-turn message (joins the running turn at its next step, else starts the next
 *    turn as soon as this one ends);
 *  - RESTART: the reply is cut quietly (no "Stopped." line, no "Continue where you stopped" chip), the first message is
 *    taken back out of the history and both messages start one new turn together, so one answer covers both.
 */
object ChatBurstLive {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** Per chat: restarts in the current burst and when the last one was. */
    private val chains = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()

    fun follow(s: Agent.Session, shown: String, byVoice: Boolean, untrusted: String?) {
        val now = System.currentTimeMillis()
        val from = s.turnFrom
        val first = s.msgs.firstOrNull { it.id == from && it.role == Role.USER }
        val h = s.history
        val head = if (h.length() > 0) h.optJSONObject(h.length() - 1) else null
        val chain = chains[s.id]?.let { ChatBurst.chain(it.first, it.second, now) } ?: 0
        val facts = ChatBurst.Facts(
            sinceTurnMs = now - s.turnAt,
            toolsRun = synchronized(s.turnTools) { s.turnTools.size },
            replyChars = s.msgs.filter { it.id > from && it.role == Role.BLAZE }.sumOf { it.text.length },
            turnUsers = s.msgs.count { it.role == Role.USER && it.id >= from },
            restarts = chain,
            voice = byVoice || Voice.lastTurnByVoice,
            plainHead = first != null && first.files.isEmpty() && ChatBurst.plainHead(head?.optString("role"), head?.opt("content"), from),
        )
        val job = s.job
        if (first == null || job == null || ChatBurst.lane(facts) == ChatBurst.Lane.MAIN) {
            s.queue(shown, emptyList(), byVoice, untrusted)
            scope.launch { s.job?.join(); s.drain() }
            return
        }
        chains[s.id] = (chain + 1) to now
        // Cut the reply first (nothing streamed after this lands), then take the first message back so it rides again.
        s.liveGate?.close(); s.liveGate = null
        job.cancel()
        val before = s.msgs.map { it.id to (it.role == Role.USER) }
        h.remove(h.length() - 1)
        s.inbox.add(Agent.Session.Inbound(first.id, first.text, emptyList(), false, null))
        s.queue(shown, emptyList(), byVoice, untrusted)
        val second = s.msgs.lastOrNull { it.role == Role.USER }?.id ?: Long.MAX_VALUE
        job.invokeOnCompletion {
            // Runs as the cut turn finishes, before the next turn can start: take its lines back off the screen.
            try {
                val after = s.msgs.map { it.id to (it.role == Role.USER) }
                val gone = ChatBurst.stale(before, from, Long.MAX_VALUE) + ChatBurst.stale(after.filter { it.first > second }, second, Long.MAX_VALUE)
                s.msgs = s.msgs.filterNot { it.id in gone }
                s.resume = null
                s.persist()
            } catch (e: Throwable) { com.blaze.assistant.device.CrashLog.quiet("ChatBurst: tidy", e) }
            scope.launch { s.drain() }
        }
    }
}
