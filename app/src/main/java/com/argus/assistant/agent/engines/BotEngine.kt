package com.blaze.assistant.agent.engines

import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.EngineKind
import com.blaze.assistant.team.Row
import kotlinx.coroutines.flow.Flow

/**
 * P0-AG-01: the engine contract of 05 §5.2, so LOCAL, CLOUD_RUN and HERMES are interchangeable. Every engine emits the
 * same team [Row] model (message / tool-call / card / event / notice), so the UI, approvals and audit never care which
 * one ran. P0 ships [LocalEngine] (wraps `Agent.Session`); CloudRunEngine (P-later) and HermesEngine (1.1.0) follow.
 * P1-TM-02: [com.blaze.assistant.team.BotThreads.send] runs bot turns on it; no screen calls that yet (P1-TM-03, P1-SH-04).
 */
interface BotEngine {
    val kind: EngineKind
    /** Runs one turn of [bot]; every row goes to [sink] (persisted) and rides the returned flow (live UI) as [TurnEvent.RowOut]. */
    fun turn(bot: Bot, input: TurnInput, sink: RowSink): Flow<TurnEvent>
    suspend fun cancel(turnId: String)
    /** A message for a turn in progress (Argus message_agent / Bot HQ "new message"): read at its next step. */
    suspend fun steer(turnId: String, text: String)
    /** Tools it can run locally, max context, voice. */
    fun capabilities(): EngineCaps
}

/** Where an engine's rows are kept (team.db `rows` via BotThreads.put, P1-TM-02). Called on the engine's thread; must not block long. */
fun interface RowSink { fun put(row: Row) }

/**
 * One turn's input. [threadId] = the chat the bot's turn runs in (its thread; the ARGUS thread is today's chat id).
 * [generation] = the thread's current generation (a Bot HQ "fresh start" bumps it). [firstSeq] = the next free row seq
 * in the thread. [untrusted] = where the text came from when it isn't the user on this phone (ConfirmGate).
 */
data class TurnInput(
    val turnId: String,
    val threadId: String,
    val text: String,
    val generation: Int = 0,
    val firstSeq: Long = 1,
    val byVoice: Boolean = false,
    val untrusted: String? = null,
)

data class EngineCaps(
    val kind: EngineKind,
    /** Runs the phone / Linux / browser tools itself (LOCAL) instead of asking the phone through a relay. */
    val localTools: Boolean,
    val maxContextTokens: Int,
    val voice: Boolean,
    val steer: Boolean = true,
    val cancel: Boolean = true,
)

/** What a turn's flow carries. Rows are the durable record; [Activity] is the live "what it's doing" line. */
sealed class TurnEvent {
    abstract val turnId: String
    data class Started(override val turnId: String, val botId: String) : TurnEvent()
    /** A new row, or a newer version of one already sent (same seq, higher updatedSeq). */
    data class RowOut(override val turnId: String, val row: Row) : TurnEvent()
    data class Activity(override val turnId: String, val text: String) : TurnEvent()
    /** The turn ended: [reply] = its last bot message ("" when none), [reason] = DONE, STOPPED, … */
    data class Done(override val turnId: String, val reply: String, val reason: String) : TurnEvent()
    data class Failed(override val turnId: String, val error: String) : TurnEvent()
}
