package com.blaze.assistant.policy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2-SF-06 (0.54.0) kill switches, 05 §7.7: **Stop**, **Pause bot**, **Pause all agents**, and the per-turn step budget.
 *
 * Pure state (no android.*): who is running / stopping / paused, the global pause, the work held while paused, and one
 * [Core.Turn] handle per running turn. Engines, the job queue and routines ask this file; it never reaches into them. Every
 * reach into the app goes through a callback the caller registers, so `Agent.kt` gets zero lines from this ticket.
 * Every type is nested in [Kill] so the shared `policy` package (SF-01 / SF-05 are writing there too) gets no generic
 * top-level names from this ticket.
 *
 * - **Stop** ([stop], [Core.Turn.stop]) is A-team v2.16 `CHAT.stop()` + `ctl.stopP`: one tap stops everything now. The turn
 *   is marked stopped, every registered cancel runs once (abort the model stream, cancel running commands, release a cloud
 *   desk), [Core.Turn.signal] completes so every await raced with [Core.Turn.race] loses at once (a hung cloud call, a
 *   review, a check), and the global [onStopHooks] run (P7 adds `tmux wait-for -S` + `pkill` for the bot's window there).
 *   Idempotent: a second Stop is a no-op that returns false.
 * - **Pause bot** ([pauseBot]): "finish current step, stop". The running turn sees [Step.PAUSE] at its next step boundary
 *   ([Core.Turn.step]) and ends; the bot's jobs and routines are held ([admit]) until [resumeBot].
 * - **Pause all agents** ([pauseAll]; Notch long-press via [toggleAll]; QS tile later): stops every running turn and holds
 *   the job queue and routines for every bot until [resumeAll]. The flags are persisted ([Store]; Android: KillPrefs) so a
 *   restart does not silently resume. A message the user types is never held ([Source.USER]): pausing the team does not
 *   take the user's own hands away, and a user's message to a paused bot runs (only a pause that lands mid-turn ends it).
 * - **Step budget** ([Budget], [STEP_BUDGET] = 40, A-team `CHAT.MAX_STEPS`): the 41st model call of a turn gets
 *   [Step.BUDGET]; the turn ends with the WorkChip "Stopped after 40 steps · Go on" ([Budget.chip] ==
 *   `StopResume.budgetLine`); "Go on" continues through `StopResume` (`isResume("Go on")`, `budgetPending`, `note`).
 *
 * Hooks (documented, not wired by this ticket):
 * - P0 `TeamHooks` / `LocalEngine` (P0-AG-01): `val t = Kill.begin(botId, source) ?: return /* paused */` at turn start;
 *   `t.onStop { Agent.stopChat(chatId) }` (cancels the turn coroutine: LlmClient closes its socket, StopResume tidies the
 *   history); before each model call `when (t.step()) { Step.CONTINUE -> go; Step.BUDGET -> keep
 *   StopResume.budgetPending(..) + show GoOnChip; else -> end }`; `Kill.end(t)` in finally. The AM loop keeps its own
 *   MAX_STEPS (30) until LocalEngine passes the step through TeamHooks; Agent.kt is not edited here.
 * - P1 `team/JobQueue.kt` (P1-TM-05, not on any overhaul/p1* branch yet): `if (!Kill.admit(botId, Kind.JOB, jobId)) return`
 *   before a job starts, and `Kill.onRelease { if (it.kind == Kind.JOB) queue.kick(it.id) }`.
 * - Routines (`routine/RoutineReceiver.kt` `RoutineService.runRoutine`, owned by P6): first line
 *   `if (!Kill.admit(owner, Kind.ROUTINE, id.toString())) return`, and `Kill.onRelease { if (it.kind == Kind.ROUTINE) fire(it.id) }`.
 * - P1 Notch (P1-SH-01): long-press -> `Kill.toggleAll()`; collect `Kill.state` (a [StateFlow] of [Snapshot]). QS tile later.
 * - P7: `Kill.onStopHooks += { botId -> /* tmux wait-for -S argus-<bot>; pkill -P <pane pid> */ }`.
 * - App start: `Kill.install(KillPrefs(this))` in BlazeApp.onCreate, before any UI collects [state].
 *
 * StopTest / PauseAllTest.
 */
object Kill {
    const val STEP_BUDGET = 40

    /** Persisted flags (the Android glue KillPrefs backs this with SharedPreferences; tests use [Store.Memory]). */
    interface Store {
        fun allPaused(): Boolean
        fun setAllPaused(on: Boolean)
        fun pausedBots(): Set<String>
        fun setPausedBots(ids: Set<String>)

        class Memory : Store {
            @Volatile private var all = false
            @Volatile private var bots: Set<String> = emptySet()
            override fun allPaused() = all
            override fun setAllPaused(on: Boolean) { all = on }
            override fun pausedBots() = bots
            override fun setPausedBots(ids: Set<String>) { bots = ids.toSet() }
        }
    }

    /** What a bot is doing, as the Notch, the bot header and the Activity page show it. */
    enum class BotRun { IDLE, RUNNING, STOPPING, PAUSED }

    /** Who started the work (a user's own message is never held by a pause). */
    enum class Source { USER, JOB, ROUTINE, AGENT }

    /** What [Core.Turn.step] tells the loop before each model call. */
    enum class Step { CONTINUE, STOP, PAUSE, BUDGET }

    /** Work held while paused, released in arrival order by [resumeAll] / [resumeBot]. */
    data class Held(val botId: String, val kind: Kind, val id: String)
    enum class Kind { JOB, ROUTINE }

    data class Snapshot(
        val allPaused: Boolean = false,
        val pausedBots: Set<String> = emptySet(),
        val bots: Map<String, BotRun> = emptyMap(),
        val held: List<Held> = emptyList(),
    ) {
        fun run(botId: String): BotRun = bots[botId] ?: if (allPaused || botId in pausedBots) BotRun.PAUSED else BotRun.IDLE
    }

    /** Step budget of one turn (A-team v2.15: chats and jobs share one budget of 40; loops are stopped by StepGuard). */
    class Budget(val limit: Int = STEP_BUDGET) {
        init { require(limit > 0) }
        var used = 0; private set
        var allowed = limit; private set
        /** Counts one model call; false once the budget is spent (this call must not run). */
        fun take(): Boolean { if (used >= allowed) return false; used++; return true }
        val exhausted get() = used >= allowed
        /** "Go on": another [limit] steps for the same turn (or the resumed one). */
        fun goOn() { allowed = used + limit }
        /** WorkChip line for a turn the budget stopped (04 §WorkChip: "Stopped after 12 steps · Go on"). */
        fun chip(): String = chip(used)

        companion object {
            const val GO_ON = "Go on"
            fun chip(steps: Int): String = "Stopped after $steps step${if (steps == 1) "" else "s"} \u00b7 $GO_ON"
        }
    }

    class Core(private val store: Store = Store.Memory()) {

        /** One running turn: A-team `ctl` ({ stopped, abort, stopP }). */
        inner class Turn internal constructor(val botId: String, val id: Int, val source: Source, val budget: Budget) {
            /** A user's own message to a paused bot runs; only a pause that arrives during the turn ends it. */
            private val startedPaused = isPaused(botId)
            private val stopped = AtomicBoolean(false)
            private val cancels = CopyOnWriteArrayList<() -> Unit>()
            /** `ctl.stopP`: completes on Stop; race any await with it ([race]). */
            val signal = CompletableDeferred<Unit>()
            val isStopped get() = stopped.get()
            @Volatile var ended = false; internal set
            /** Why the loop ended (null = it ended by itself). */
            @Volatile var endedBy: Step? = null; internal set

            /** Registers a cancel (abort the stream, cancel a command). Runs at once if the turn is already stopped. */
            fun onStop(cancel: () -> Unit) {
                if (stopped.get()) { safe(cancel); return }
                cancels += cancel
                if (stopped.get() && cancels.remove(cancel)) safe(cancel)   // Stop landed between the check and the add
            }

            /** Instant Stop. True the first time only. */
            fun stop(): Boolean {
                if (!stopped.compareAndSet(false, true)) return false
                publish()   // STOPPING shows at once, before the cancels finish (A-team: the UI shows it straight away)
                while (cancels.isNotEmpty()) { val c = cancels.removeAt(0); safe(c) }
                signal.complete(Unit)
                onStopHooks.forEach { h -> safe { h(botId) } }
                return true
            }

            /** Called before each model call. Order: Stop > Pause > budget. */
            fun step(): Step {
                val s = when {
                    stopped.get() -> Step.STOP
                    isPaused(botId) && !startedPaused -> Step.PAUSE
                    !budget.take() -> Step.BUDGET
                    else -> Step.CONTINUE
                }
                if (s != Step.CONTINUE) endedBy = s
                return s
            }

            /** Runs [block]; null as soon as Stop wins (the block is cancelled). */
            suspend fun <T> race(block: suspend () -> T): T? {
                if (stopped.get()) return null
                return coroutineScope {
                    val work = async { block() }
                    val stop = async { signal.await() }
                    val out: T? = select {
                        work.onAwait { v -> v }
                        stop.onAwait { null }
                    }
                    work.cancel(); stop.cancel()
                    out
                }
            }
        }

        private val seq = AtomicInteger(0)
        private val turns = CopyOnWriteArrayList<Turn>()
        private val held = CopyOnWriteArrayList<Held>()
        private val releaseListeners = CopyOnWriteArrayList<(Held) -> Unit>()

        /** Extra cancels run on every Stop with the bot id (P7: `tmux wait-for -S`, `pkill` in the bot's window). */
        val onStopHooks = CopyOnWriteArrayList<(String) -> Unit>()

        private val _state = MutableStateFlow(Snapshot())
        /** For the Notch, the bot header and the Pause-all banner. */
        val state: StateFlow<Snapshot> = _state.asStateFlow()

        init { publish() }

        // ---- reading ---------------------------------------------------------------------------------------------
        val allPaused get() = store.allPaused()
        fun isBotPaused(botId: String) = botId in store.pausedBots()
        /** The check the job queue and routines make (global pause or this bot paused). */
        fun isPaused(botId: String) = store.allPaused() || isBotPaused(botId)
        fun running(botId: String): List<Turn> = turns.filter { it.botId == botId && !it.ended }
        fun run(botId: String): BotRun = state.value.run(botId)

        // ---- turns -----------------------------------------------------------------------------------------------
        /**
         * A turn starts. Null = paused, don't run it (never for [Source.USER]). Work that must come back after the pause
         * is held through [admit] by its owner (job queue, routines), which knows how to start it again.
         */
        fun begin(botId: String, source: Source = Source.USER, budget: Budget = Budget()): Turn? {
            if (source != Source.USER && isPaused(botId)) return null
            val t = Turn(botId, seq.incrementAndGet(), source, budget)
            turns += t; publish()
            return t
        }

        /** The turn finished (always call, in finally). */
        fun end(t: Turn) { t.ended = true; turns.remove(t); publish() }

        /** Stop every running turn of [botId]. True if anything was stopped now (false = repeat / idle). */
        fun stop(botId: String): Boolean = running(botId).map { it.stop() }.any { it }

        /** Stop every running turn. Returns how many were stopped now. */
        fun stopAll(): Int = turns.filter { !it.ended }.count { it.stop() }

        // ---- pause -----------------------------------------------------------------------------------------------
        /** Pause bot: its turn ends at the next step boundary; its jobs and routines are held. */
        fun pauseBot(botId: String) { store.setPausedBots(store.pausedBots() + botId); publish() }

        /** Resume bot: returns (and announces) its held work, unless Pause all still holds it. */
        fun resumeBot(botId: String): List<Held> {
            store.setPausedBots(store.pausedBots() - botId)
            return release { it.botId == botId && !isPaused(it.botId) }
        }

        /** Pause all agents: stops every running turn, holds the job queue and routines until [resumeAll]. */
        fun pauseAll(): Int { store.setAllPaused(true); val n = stopAll(); publish(); return n }

        /** Resume all: held work comes back in arrival order (a bot still paused on its own keeps its work held). */
        fun resumeAll(): List<Held> { store.setAllPaused(false); return release { !isBotPaused(it.botId) } }

        /** Notch long-press. True = now paused. */
        fun toggleAll(): Boolean = if (allPaused) { resumeAll(); false } else { pauseAll(); true }

        /**
         * The job queue / routines ask before starting work. True = run it now. False = it is held (deduplicated) and
         * comes back through [onRelease] and the return of [resumeAll] / [resumeBot].
         */
        fun admit(botId: String, kind: Kind, id: String, source: Source = if (kind == Kind.ROUTINE) Source.ROUTINE else Source.JOB): Boolean {
            if (source == Source.USER || !isPaused(botId)) return true
            hold(Held(botId, kind, id)); return false
        }

        fun onRelease(listener: (Held) -> Unit) { releaseListeners += listener }

        private fun hold(h: Held) { if (h !in held) held += h; publish() }

        private fun release(which: (Held) -> Boolean): List<Held> {
            val out = held.filter(which)
            held.removeAll(out.toSet())
            publish()
            out.forEach { h -> releaseListeners.forEach { l -> safe { l(h) } } }
            return out
        }

        private fun publish() {
            val paused = store.pausedBots()
            val bots = HashMap<String, BotRun>()
            paused.forEach { bots[it] = BotRun.PAUSED }
            // A bot with a live turn: STOPPING wins (Stop shows at once), then RUNNING (incl. a user's turn on a paused bot).
            turns.filter { !it.ended }.forEach { t ->
                val r = if (t.isStopped) BotRun.STOPPING else BotRun.RUNNING
                if (bots[t.botId] != BotRun.STOPPING) bots[t.botId] = r
            }
            _state.value = Snapshot(store.allPaused(), paused, bots, held.toList())
        }

        private inline fun safe(f: () -> Unit) { try { f() } catch (_: Throwable) {} }
    }

    // ---- the app-wide switch: [install] binds the persisted [Store] at start (BlazeApp.onCreate) ----------------------
    @Volatile var core = Core(); private set
    fun install(store: Store) { core = Core(store) }

    val state: StateFlow<Snapshot> get() = core.state
    val allPaused get() = core.allPaused
    val onStopHooks get() = core.onStopHooks
    fun isPaused(botId: String) = core.isPaused(botId)
    fun run(botId: String) = core.run(botId)
    fun begin(botId: String, source: Source = Source.USER, budget: Budget = Budget()) = core.begin(botId, source, budget)
    fun end(t: Core.Turn) = core.end(t)
    fun stop(botId: String) = core.stop(botId)
    fun stopAll() = core.stopAll()
    fun pauseBot(botId: String) = core.pauseBot(botId)
    fun resumeBot(botId: String) = core.resumeBot(botId)
    fun pauseAll() = core.pauseAll()
    fun resumeAll() = core.resumeAll()
    fun toggleAll() = core.toggleAll()
    fun admit(botId: String, kind: Kind, id: String) = core.admit(botId, kind, id)
    fun onRelease(listener: (Held) -> Unit) = core.onRelease(listener)
}
