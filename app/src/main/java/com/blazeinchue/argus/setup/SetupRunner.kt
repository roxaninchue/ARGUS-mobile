package com.blazeinchue.argus.setup

import android.content.Context
import com.blazeinchue.argus.agent.Settings
import com.blazeinchue.argus.setup.SetupPlan.Phase
import com.blazeinchue.argus.setup.SetupPlan.Status
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
 * each job (Linux.install, LinuxReadyRunner.ensure, SkillLibrary.seed, BackupBrain.autoSetup, WakeModel.download,
 * FastLane.download), one at a time per item, Wi-Fi items only on Wi-Fi, failures retried with a back-off. It runs in
 * the app's own scope, so leaving the setup screen (or the app) doesn't stop it; settled results are saved and the next
 * start resumes what is left ([later], from ArgusApp's after-first-frame queue, about 30 s after the first frame).
 */
object SetupRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Map<String, Status>> = _state
    @Volatile private var loop: Job? = null
    private val running = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private lateinit var app: Context

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
                for (i in SetupPlan.ITEMS) SetupPlan.waiting(i, _state.value[i.id], online, wifi)?.let { if (it != _state.value[i.id]) set(i.id, it) }
                for (i in SetupPlan.runnable(_state.value, online, wifi, now)) if (running[i.id]?.isActive != true) running[i.id] = launch { run(i) }
                val busy = running.values.any { it.isActive }
                // nothing running and only items that used up their automatic tries: stop (Retry or the next start goes on)
                if (!busy && SetupPlan.left(_state.value).all { (_state.value[it.id]?.tries ?: 0) >= SetupPlan.MAX_AUTO_TRIES }) break
                delay(if (busy) 2_000 else 10_000)
            }
        }
    }

    /**
     * ArgusApp, after the first frame: for people past onboarding (or who started setup before), what is left is set up
     * by itself [SetupPlan.LATER_MS] later, so it never competes with start-up. First-run users get it from the setup screen.
     */
    fun later(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            delay(SetupPlan.LATER_MS)
            val onboarded = try { Settings.bool(com.blazeinchue.argus.ui.space.SpaceOnboarding.PREF_ONBOARDED, false) } catch (_: Throwable) { false }
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
                !com.blazeinchue.argus.terminal.Linux.supported() -> Status(Phase.SKIPPED, note = "Not available on this phone's CPU")
                com.blazeinchue.argus.terminal.Linux.installed() -> Status(Phase.DONE)
                else -> null
            }
            "tools" -> if (!com.blazeinchue.argus.terminal.Linux.supported()) Status(Phase.SKIPPED, note = "Comes with the Linux workspace") else null
            "voice" -> if (com.blazeinchue.argus.voice.WakeModel.ready(app)) Status(Phase.DONE) else null
            "quick" -> when {
                !com.blazeinchue.argus.agent.FastLane.supported(app) -> Status(Phase.SKIPPED, note = "Not available on this phone")
                com.blazeinchue.argus.agent.FastLane.ready(app) -> Status(Phase.DONE)
                else -> null
            }
            "brain" -> when {
                !Settings.bool("backup_brain", true) -> Status(Phase.SKIPPED, note = "Turned off")
                com.blazeinchue.argus.agent.BackupBrain.initialized() && !com.blazeinchue.argus.agent.BackupBrain.supported() -> Status(Phase.SKIPPED, note = "Not available on this phone")
                com.blazeinchue.argus.agent.BackupBrain.initialized() && com.blazeinchue.argus.agent.BackupBrain.installed() -> Status(Phase.DONE)
                else -> null
            }
            else -> null
        }
    } catch (t: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("SetupRunner.probe $id", t); null }

    private suspend fun run(i: SetupPlan.Item) {
        set(i.id, Status(Phase.RUNNING))
        val result: Status = try { work(i) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (t: Throwable) {
            com.blazeinchue.argus.device.CrashLog.quiet("SetupRunner ${i.id}", t)
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
                val watch = scope.launch { while (isActive) { val t = com.blazeinchue.argus.terminal.Linux.status.value; if (t.isNotBlank()) set(i.id, Status(Phase.RUNNING, percentIn(t), t.take(60))); delay(500) } }
                val r = try { com.blazeinchue.argus.terminal.Linux.install() } finally { watch.cancel() }
                if (com.blazeinchue.argus.terminal.Linux.installed()) Status(Phase.DONE) else fail(r)
            }
            "tools" -> {
                com.blazeinchue.argus.terminal.LinuxReadyRunner.ensure("alpine", force = true)
                val marker = java.io.File(com.blazeinchue.argus.terminal.Linux.rootfs(), com.blazeinchue.argus.terminal.LinuxReady.MARKER)
                if (marker.exists()) Status(Phase.DONE) else fail("The Linux tools didn't finish")
            }
            "skills" -> { com.blazeinchue.argus.agent.SkillLibrary.seed(app); Status(Phase.DONE) }
            "brain" -> {
                var waited = 0
                while (!com.blazeinchue.argus.agent.BackupBrain.initialized() && waited < 60) { delay(1_000); waited++ }
                if (!com.blazeinchue.argus.agent.BackupBrain.initialized()) return fail("Waiting for ${com.blazeinchue.argus.BrandName.NAME} to finish starting")
                com.blazeinchue.argus.agent.BackupBrain.autoSetup()
                probe(i.id)?.takeIf { SetupPlan.settled(it) } ?: fail("The backup brain didn't finish downloading")
            }
            "voice" -> {
                val watch = scope.launch { com.blazeinchue.argus.voice.WakeModel.progress.collect { p -> if (p in 0..100) set(i.id, Status(Phase.RUNNING, p)) } }
                val r = try { com.blazeinchue.argus.voice.WakeModel.download(app) } finally { watch.cancel() }
                if (r.isSuccess && com.blazeinchue.argus.voice.WakeModel.ready(app)) Status(Phase.DONE) else fail(r.exceptionOrNull()?.message ?: "Download failed")
            }
            "quick" -> {
                val watch = scope.launch { com.blazeinchue.argus.agent.FastLane.progress.collect { p -> if (p in 0..100) set(i.id, Status(Phase.RUNNING, p)) } }
                val r = try { com.blazeinchue.argus.agent.FastLane.download(app) } finally { watch.cancel() }
                if (r.isSuccess && com.blazeinchue.argus.agent.FastLane.ready(app)) Status(Phase.DONE) else fail(r.exceptionOrNull()?.message ?: "Download failed")
            }
            else -> Status(Phase.SKIPPED)
        }
    }

    /** "Downloading… 42%" → 42; -1 when the line has no percentage. */
    internal fun percentIn(line: String): Int = Regex("(\\d{1,3})\\s?%").find(line)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 } ?: -1

    private fun net(unmeteredOnly: Boolean): Boolean = try {
        if (unmeteredOnly) com.blazeinchue.argus.terminal.LinuxReadyRunner.unmetered() && com.blazeinchue.argus.terminal.LinuxReadyRunner.online()
        else com.blazeinchue.argus.terminal.LinuxReadyRunner.online()
    } catch (_: Throwable) { false }
}
