package com.blaze.assistant.setup

import android.content.Context
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.setup.SetupPlan.Phase
import com.blaze.assistant.setup.SetupPlan.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 0.52.3 one-screen setup, the phone's side of [SetupPlan]: starts every item by itself with the code that already did
 * each job (Linux.install, LinuxReadyRunner.ensure, SkillLibrary.seed, WakeModel.download,
 * FastLane.download), one at a time per item, Wi-Fi items only on Wi-Fi, failures retried with a back-off. It runs in
 * the app's own scope, so leaving the setup screen (or the app) doesn't stop it; settled results are saved and the next
 * start resumes what is left ([later], from BlazeApp's after-first-frame queue, about 30 s after the first frame).
 */
object SetupRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Map<String, Status>> = _state
    @Volatile private var loop: Job? = null
    private val running = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private lateinit var app: Context
    /** 0.52.4: wakes the loop at once (the user chose mobile data) instead of after its 10 s nap. */
    private val kick = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** The user chose to finish on mobile data (kept, so later starts and the Home's card don't wait for Wi-Fi either). */
    fun useData(): Boolean = try { Settings.bool(SetupPlan.PREF_MOBILE_DATA, false) } catch (_: Throwable) { false }

    /**
     * 0.52.4 "Download now on mobile data": the Wi-Fi items start on the current network.
     */
    fun useMobileData(ctx: Context) {
        try { Settings.setBool(SetupPlan.PREF_MOBILE_DATA, true) } catch (_: Throwable) { }
        start(ctx)
        kick.trySend(Unit)
    }

    private fun load(): Map<String, Status> = try { SetupPlan.decode(Settings.str(SetupPlan.PREF, "")) } catch (_: Throwable) { emptyMap() }

    private fun set(id: String, s: Status) {
        _state.value = _state.value + (id to s)
        if (SetupPlan.settled(s)) try { Settings.setStr(SetupPlan.PREF, SetupPlan.encode(_state.value)) } catch (_: Throwable) { }
    }

    fun started(): Boolean = try { Settings.bool(SetupPlan.PREF_STARTED, false) } catch (_: Throwable) { false }

    /** The setup screen: start (or keep going). Safe to call any number of times. */
    fun start(ctx: Context) {
        app = ctx.applicationContext
        try { Settings.setBool(SetupPlan.PREF_STARTED, true) } catch (_: Throwable) { }
        if (loop?.isActive == true) return
        loop = scope.launch {
            probeSettled()
            while (isActive && !SetupPlan.complete(_state.value)) {
                val online = net(unmeteredOnly = false); val wifi = net(unmeteredOnly = true)
                val now = System.currentTimeMillis()
                val data = useData()
                for (i in SetupPlan.ITEMS) {
                    val w = SetupPlan.waiting(i, _state.value[i.id], online, wifi, data)
                    if (w != null) { if (w != _state.value[i.id]) set(i.id, w) }
                    // a row that waited for Wi-Fi and may now run (mobile data chosen, or Wi-Fi came back) goes back to waiting its turn
                    else if (_state.value[i.id]?.phase == Phase.WAITING_WIFI || _state.value[i.id]?.phase == Phase.OFFLINE) set(i.id, Status(tries = _state.value[i.id]?.tries ?: 0))
                }
                for (i in SetupPlan.runnable(_state.value, online, wifi, now, data)) if (running[i.id]?.isActive != true) running[i.id] = launch { run(i) }
                val busy = running.values.any { it.isActive }
                // nothing running and only items that used up their automatic tries: stop (Retry or the next start goes on)
                if (!busy && SetupPlan.left(_state.value).all { (_state.value[it.id]?.tries ?: 0) >= SetupPlan.MAX_AUTO_TRIES }) break
                kotlinx.coroutines.withTimeoutOrNull(if (busy) 2_000L else 10_000L) { kick.receive() }
            }
        }
    }

    /**
     * BlazeApp, after the first frame: for people past onboarding (or who started setup before), what is left is set up
     * by itself [SetupPlan.LATER_MS] later, so it never competes with start-up. First-run users get it when onboarding finishes.
     */
    fun later(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            delay(SetupPlan.LATER_MS)
            val onboarded = try { Settings.bool(com.blaze.assistant.ui.space.SpaceOnboarding.PREF_ONBOARDED, false) } catch (_: Throwable) { false }
            if (SetupPlan.startLater(onboarded, started(), _state.value)) start(app)
        }
    }

    /** The Retry button. */
    fun retry(ctx: Context) { _state.value = SetupPlan.retryAll(_state.value); start(ctx) }

    /** What is already there (an upgrade, or done by the old setup) counts as done without running again. */
    private fun probeSettled() {
        for (i in SetupPlan.ITEMS) {
            if (SetupPlan.settled(_state.value[i.id])) continue
            when (val p = probe(i.id)) { null -> {}; else -> set(i.id, p) }
        }
    }

    private fun probe(id: String): Status? = try {
        when (id) {
            "linux" -> when {
                !com.blaze.assistant.terminal.Linux.supported() -> Status(Phase.SKIPPED, note = "Not available on this phone's CPU")
                com.blaze.assistant.terminal.Distros.installed(com.blaze.assistant.terminal.Distros.setupId()) -> Status(Phase.DONE)
                else -> null
            }
            "tools" -> if (!com.blaze.assistant.terminal.Linux.supported()) Status(Phase.SKIPPED, note = "Comes with the Linux workspace") else null
            "voice" -> if (com.blaze.assistant.voice.WakeModel.ready(app)) Status(Phase.DONE) else null
            "quick" -> when {
                !com.blaze.assistant.agent.FastLane.supported(app) -> Status(Phase.SKIPPED, note = "Not available on this phone")
                com.blaze.assistant.agent.FastLane.ready(app) -> Status(Phase.DONE)
                else -> null
            }
            else -> null
        }
    } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("SetupRunner.probe $id", t); null }

    private suspend fun run(i: SetupPlan.Item) {
        set(i.id, Status(Phase.RUNNING))
        val result: Status = try { work(i) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) {
            com.blaze.assistant.device.CrashLog.quiet("SetupRunner ${i.id}", t)
            SetupPlan.failed(_state.value[i.id], t.message ?: t.javaClass.simpleName, System.currentTimeMillis())
        }
        set(i.id, result)
    }

    /** One item, with the existing code for it. Returns DONE / SKIPPED, or a FAILED status for the back-off. */
    private suspend fun work(i: SetupPlan.Item): Status {
        val now = { System.currentTimeMillis() }
        fun fail(why: String) = SetupPlan.failed(_state.value[i.id], why, now())
        probe(i.id)?.let { if (SetupPlan.settled(it)) return it }
        return when (i.id) {
            "linux" -> {
                // Owner 2026-11-20: the Linux picked on the distro step (Alpine when none was, or this CPU can't run it)
                val id = com.blaze.assistant.terminal.Distros.setupId()
                val watch = scope.launch { while (isActive) {
                    val t = if (id == "alpine") com.blaze.assistant.terminal.Linux.status.value else com.blaze.assistant.terminal.Distros.status.value
                    if (t.isNotBlank()) set(i.id, Status(Phase.RUNNING, percentIn(t), t.take(60))); delay(500) } }
                val r = try { com.blaze.assistant.terminal.Distros.install(id) } finally { watch.cancel() }
                if (com.blaze.assistant.terminal.Distros.installed(id)) Status(Phase.DONE) else fail(r)
            }
            "tools" -> {
                val id = com.blaze.assistant.terminal.Distros.setupId()
                com.blaze.assistant.terminal.LinuxReadyRunner.ensure(id, force = true)
                val marker = java.io.File(com.blaze.assistant.terminal.Linux.rootfs(id), com.blaze.assistant.terminal.LinuxReady.MARKER)
                if (marker.exists()) Status(Phase.DONE) else fail("The Linux tools didn't finish")
            }
            "skills" -> { com.blaze.assistant.agent.SkillLibrary.seed(app); Status(Phase.DONE) }
            "voice" -> {
                val watch = scope.launch { com.blaze.assistant.voice.WakeModel.progress.collect { p -> if (p in 0..100) set(i.id, Status(Phase.RUNNING, p)) } }
                val r = try { com.blaze.assistant.voice.WakeModel.download(app) } finally { watch.cancel() }
                if (r.isSuccess && com.blaze.assistant.voice.WakeModel.ready(app)) Status(Phase.DONE) else fail(r.exceptionOrNull()?.message ?: "Download failed")
            }
            "quick" -> {
                val watch = scope.launch { com.blaze.assistant.agent.FastLane.progress.collect { p -> if (p in 0..100) set(i.id, Status(Phase.RUNNING, p)) } }
                val r = try { com.blaze.assistant.agent.FastLane.download(app) } finally { watch.cancel() }
                if (r.isSuccess && com.blaze.assistant.agent.FastLane.ready(app)) Status(Phase.DONE) else fail(r.exceptionOrNull()?.message ?: "Download failed")
            }
            else -> Status(Phase.SKIPPED)
        }
    }

    /** "Downloading… 42%" → 42; -1 when the line has no percentage. */
    internal fun percentIn(line: String): Int = Regex("(\\d{1,3})\\s?%").find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 } ?: -1

    private fun net(unmeteredOnly: Boolean): Boolean = try {
        if (unmeteredOnly) com.blaze.assistant.terminal.LinuxReadyRunner.unmetered() && com.blaze.assistant.terminal.LinuxReadyRunner.online()
        else com.blaze.assistant.terminal.LinuxReadyRunner.online()
    } catch (_: Throwable) { false }
}
