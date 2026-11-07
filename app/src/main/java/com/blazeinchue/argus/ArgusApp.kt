package com.blazeinchue.argus

import android.app.Application
import com.blazeinchue.argus.agent.Agent
import com.blazeinchue.argus.agent.Settings
import kotlinx.coroutines.launch

class ArgusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val t0 = android.os.SystemClock.elapsedRealtime()
        com.blazeinchue.argus.device.CrashLog.init(this)
        com.blazeinchue.argus.device.CrashLog.shieldMainThread()
        // Lazy start: Agent.Session's verification and opening argus.db run on background threads meanwhile (StartWarm,
        // Mind.openLater); work the first frame doesn't need waits for it (StartLater, run on the main thread after the
        // first frame, or after StartLater.FALLBACK_MS when Argus starts without a screen). Goal: onCreate < 200 ms.
        com.blazeinchue.argus.device.StartWarm.begin(this)
        com.blazeinchue.argus.device.StartLater.armFallback()
        // 0.45.19 fix 7: one Back rule for every Argus window (Back returns to the chat).
        try { com.blazeinchue.argus.ui.BackRule.install(this) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: BackRule", e) }
        com.blazeinchue.argus.device.StartGuard.begin(this)   // 0.45.16: crash-loop detection (safe start)
        com.blazeinchue.argus.device.MainWatch.start()        // 0.45.16: ANR / stall log with the main thread's stack
        com.blazeinchue.argus.device.StartGuard.step("Settings.init") { Settings.init(this) }
        com.blazeinchue.argus.device.StartGuard.step("Agent.preload") { Agent.preload(this) }   // v0.45.0 (#2): the main chat is read and parsed on a background thread while the rest starts
        com.blazeinchue.argus.device.StartGuard.step("agent.SimpleMode.load") { com.blazeinchue.argus.agent.SimpleMode.load() }   // v0.39.0 Simple / Advanced
        com.blazeinchue.argus.device.StartGuard.step("agent.SwipeNav.load") { com.blazeinchue.argus.agent.SwipeNav.load() }     // v0.39.0 Navigation: Swipe or Classic tabs
        try { com.blazeinchue.argus.bank.BankMode.init(this) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: BankMode.initth", e) }   // v0.43.0 Bank mode (back on after a restart mid-banking)
        // v0.37.0 Awareness: what's playing, on screen and just happened, for every prompt.
        try { com.blazeinchue.argus.agent.AwarenessHooks.install(this) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: AwarenessHooks", e) }
        // v0.24.4: back up prefs and data once per build before the setup gate (or anything after it) runs.
        try {
            if (!com.blazeinchue.argus.setup.SetupGate.isDone()) com.blazeinchue.argus.setup.SetupGate.backup(this, com.blazeinchue.argus.update.Updater.installedCode(this))
        } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: SetupGate.i", e) }
        // v0.23.4: Auto's per-provider model lists and per-model cooldowns (new files, no prefs changes).
        try { com.blazeinchue.argus.agent.ProviderCatalog.init(filesDir); com.blazeinchue.argus.agent.ModelMemory.init(filesDir) } catch (e: Exception) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: ProviderCatalo", e) }
        try { com.blazeinchue.argus.agent.Projects.load() } catch (e: Exception) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: Projects.load", e) }
        // v0.45.0 (#14): background-only scheduling (file reads + WorkManager, nothing the first frame needs) leaves the main thread.
        val bgApp = this
        Thread({
            // v0.33.0 Life Log 2: recordings stopped but not yet reviewed (phone restarted, app killed) carry on.
            try { com.blazeinchue.argus.life.LifeLogWorker.resumePending(bgApp) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: LifeLogWorker.r", e) }
            try { com.blazeinchue.argus.notify.MoneyRadar.schedule(bgApp) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: MoneyRadar.sc", e) }
            try { com.blazeinchue.argus.agent.TimeCapsule.schedule(bgApp) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: TimeCapsule.sc", e) }
            try { com.blazeinchue.argus.agent.MindReview.schedule(bgApp) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: MindReview.sch", e) }
        }, "argus-start-bg").start()
        // v0.33.0 Safe Walk survives a reboot / process restart; sealed time capsules keep their wake-up. (After the first frame.)
        com.blazeinchue.argus.device.StartLater.add("phone.SafeWalk.handle") { com.blazeinchue.argus.phone.SafeWalk.handle(this, android.content.Intent.ACTION_BOOT_COMPLETED) }
        com.blazeinchue.argus.device.StartLater.add("phone.CallCopilot.init") { com.blazeinchue.argus.phone.CallCopilot.init(this) }
        try { com.blazeinchue.argus.agent.OfflineBrain.init(this) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: OfflineBrain.i", e) }
        // v1.9.10 fix: a leftover "Auto" steps model silently sent phone steps through the free chain
        // even after the user picked a specific model. Clear it once; they can set it again.
        if (Settings.str("mig_steps_auto", "").isEmpty()) {
            if (Settings.providerId != com.blazeinchue.argus.agent.Providers.AUTO && Settings.stepsProvider == com.blazeinchue.argus.agent.Providers.AUTO) Settings.stepsProvider = ""
            Settings.setStr("mig_steps_auto", "1")
        }
        com.blazeinchue.argus.device.StartGuard.step("agent.IntentCache.init") { com.blazeinchue.argus.agent.IntentCache.init(this) }
        com.blazeinchue.argus.device.StartGuard.step("phone.Watchers.init") { com.blazeinchue.argus.phone.Watchers.init(this) }
        com.blazeinchue.argus.device.StartGuard.step("agent.Autonomy.init") { com.blazeinchue.argus.agent.Autonomy.init(this) }
        com.blazeinchue.argus.device.StartGuard.step("agent.Processes.init") { com.blazeinchue.argus.agent.Processes.init(this) }
        // One mind (v0.18.0): argus.db first (mind.db until it is copied); Memory is now a view of its memories table.
        // Lazy start: argus.db opens off the main thread (started by StartWarm; this only makes sure it was started) and
        // Memory attaches to the table there once it is open. Nothing here waits for it.
        com.blazeinchue.argus.device.StartGuard.step("agent.Mind.openLater") { com.blazeinchue.argus.agent.Mind.openLater(this) }
        com.blazeinchue.argus.device.StartGuard.step("agent.Memory.init") { com.blazeinchue.argus.agent.Memory.init(this, attachLater = true) }
        com.blazeinchue.argus.device.StartGuard.step("agent.Usage.init") { com.blazeinchue.argus.agent.Usage.init(this) }
        try { com.blazeinchue.argus.agent.ToolStats.init(this) } catch (e: Exception) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: ToolStats.init", e) }
        try { com.blazeinchue.argus.agent.ToolCache.init(filesDir) } catch (e: Exception) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: ToolCache.init", e) }
        com.blazeinchue.argus.device.StartGuard.step("agent.Learning.init") { com.blazeinchue.argus.agent.Learning.init(this) }
        com.blazeinchue.argus.device.StartGuard.step("connect.Mcp.load") { com.blazeinchue.argus.connect.Mcp.load() }
        com.blazeinchue.argus.device.StartGuard.step("connect.ArgusAccount.start") { com.blazeinchue.argus.connect.ArgusAccount.start() }
        // v0.40.0 Cloud sync: off by default; when on, live end-to-end encrypted sync with your other phones.
        try { com.blazeinchue.argus.connect.CloudSync.init(this) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: CloudSync.in", e) }
        com.blazeinchue.argus.device.StartGuard.step("agent.Traces.init") { com.blazeinchue.argus.agent.Traces.init(this) }
        // v0.30.7: phone steps that worked, per request, sent back as one run_plan next time.
        try { com.blazeinchue.argus.agent.TapLoop.Plans.init(java.io.File(filesDir, "plan_memory.json")) } catch (e: Exception) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: TapLoop.Plans.", e) }
        val tAgent = android.os.SystemClock.elapsedRealtime()
        com.blazeinchue.argus.device.StartGuard.step("Agent.init") { Agent.init(this) }
        // 0.46.0 Argus Stage: the saved layout and timers (re-arms running timers' alarms).
        try { com.blazeinchue.argus.stage.StageStore.init(this) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: StageStore.init", e) }
        try { com.blazeinchue.argus.movies.Movies.init(this) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: Movies.init", e) }
        val agentMs = android.os.SystemClock.elapsedRealtime() - tAgent
        com.blazeinchue.argus.device.StartGuard.step("orchestra.OrchestraRuns.init") { com.blazeinchue.argus.orchestra.OrchestraRuns.init(this) }
        com.blazeinchue.argus.device.StartGuard.step("agent.BgAgents.init") { com.blazeinchue.argus.agent.BgAgents.init(this) }
        com.blazeinchue.argus.device.StartGuard.step("agent.TaskMemoryStore.init") { com.blazeinchue.argus.agent.TaskMemoryStore.init(this) }   // 0.52.1: reads on IO
        com.blazeinchue.argus.device.StartGuard.step("routine.Routines.init") { com.blazeinchue.argus.routine.Routines.init(this) }
        // v0.29.0 Home board: widgets + cached content in argus.db, refreshed by WorkManager (≥ 1 h).
        com.blazeinchue.argus.device.StartGuard.step("home.HomeBoard.init") { com.blazeinchue.argus.home.HomeBoard.init(this) }
        // 0.45.29: ArgusOS ("Argus Phone", Home's virtual phone) was removed; once after the update, free its disk, prefs and profile.
        if (!com.blazeinchue.argus.device.StartGuard.safeStart && !Settings.bool(com.blazeinchue.argus.home.ArgusOsCleanup.PREF_DONE, false)) {
            val app = this
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                kotlinx.coroutines.delay(20_000)   // after start-up work, not competing with it
                com.blazeinchue.argus.home.ArgusOsCleanup.runOnce(app)
            }
        }
        com.blazeinchue.argus.device.StartGuard.step("notify.Notifier.init") { com.blazeinchue.argus.notify.Notifier.init(this) }
        com.blazeinchue.argus.device.StartLater.add("update.Updater.checkInBackground") { com.blazeinchue.argus.update.Updater.checkInBackground(this) }
        com.blazeinchue.argus.device.StartGuard.step("Settings.tellIfSecretsDropped") { Settings.tellIfSecretsDropped() }
        com.blazeinchue.argus.device.StartGuard.step("voice.Voice.init") { com.blazeinchue.argus.voice.Voice.init(this) }
        // Wake word starts from the screen (MainActivity/assistant) and at boot: starting a
        // microphone service while Argus is in the background makes Android 12+ kill the app.
        com.blazeinchue.argus.device.StartGuard.step("terminal.Terminal.init") { com.blazeinchue.argus.terminal.Terminal.init(this) }
        // v0.45.26: the on-device backup brain (installs quietly on Wi-Fi, starts only when Argus Cloud struggles) and
        // "ready out of the box" Linux tools for Linuxes installed before this version. Both wait a minute after start.
        // 0.46.6: low-RAM phones keep a smaller picture cache (provider engines run one at a time everywhere)
        try { com.blazeinchue.argus.movies.ProviderSandbox.Gate.configure(this) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: Gate.configure", e) }
        com.blazeinchue.argus.device.StartLater.add("device.Thermal.init") { com.blazeinchue.argus.device.Thermal.init(this) }
        if (!com.blazeinchue.argus.device.StartGuard.safeStart) com.blazeinchue.argus.device.StartLater.add("agent.BackupBrain.init") { com.blazeinchue.argus.agent.BackupBrain.init(this) }
        // Wave B-4: the on-phone Linux desktop was removed; once after the update, free what it left (packages, caches, prefs).
        if (!com.blazeinchue.argus.device.StartGuard.safeStart && !Settings.bool(com.blazeinchue.argus.terminal.LinuxDesktopCleanup.PREF_DONE, false)) {
            val app = this
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                kotlinx.coroutines.delay(30_000)   // after start-up work, not competing with it
                com.blazeinchue.argus.terminal.LinuxDesktopCleanup.runOnce(app)
            }
        }
        // 0.52.3 one-screen setup: for people past onboarding, what is left (Linux, tools, skills, backup brain, voice, quick
        // commands) downloads by itself about 30 s after the first frame (setup/SetupRunner.later). Never in safe start.
        if (!com.blazeinchue.argus.device.StartGuard.safeStart) com.blazeinchue.argus.device.StartLater.add("setup.SetupRunner.later") { com.blazeinchue.argus.setup.SetupRunner.later(this) }
        com.blazeinchue.argus.device.StartGuard.step("agent.SkillLibrary.init") { com.blazeinchue.argus.agent.SkillLibrary.init(this) }
        // ArgusRouter: start when it's the brain or set to start with Argus (after Terminal/Linux are ready).
        if (!com.blazeinchue.argus.device.StartGuard.safeStart && (com.blazeinchue.argus.agent.ArgusRouter.autostart || Settings.providerId == com.blazeinchue.argus.agent.ArgusRouter.ID)) {
            val app = this
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                try { com.blazeinchue.argus.agent.ArgusRouter.start(app) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: ArgusRouter.st", e) }
            }
        }
        Thread { com.blazeinchue.argus.agent.SkillPacks.ensureStarters(); com.blazeinchue.argus.agent.SkillLibrary.seed(this) }.start()
        // Lazy start: triggers, proactive helpers, the task overlay, desktop link, dashboard and orb start after the first frame.
        com.blazeinchue.argus.device.StartLater.add("proactive.Triggers.init") { com.blazeinchue.argus.proactive.Triggers.init(this) }
        if (!com.blazeinchue.argus.device.StartGuard.safeStart) com.blazeinchue.argus.device.StartLater.add("proactive.Proactive.start") { com.blazeinchue.argus.proactive.Proactive.start(this) }
        // v0.38.0 Jarvis mode: questions, screen glances, camera looks, its own routines (per level).
        if (!com.blazeinchue.argus.device.StartGuard.safeStart) com.blazeinchue.argus.device.StartLater.add("proactive.Jarvis.start") { com.blazeinchue.argus.proactive.FollowUps.init(this); com.blazeinchue.argus.proactive.Jarvis.start(this) }
        com.blazeinchue.argus.device.StartLater.add("ui.Overlay.watchTasks") { com.blazeinchue.argus.ui.Overlay.watchTasks(this) }
        // 0.50.0: edge particles while Argus controls the screen (suspends while idle)
        com.blazeinchue.argus.device.StartLater.add("ui.ScreenGlow.watch") { com.blazeinchue.argus.ui.ScreenGlow.watch(this) }
        if (!com.blazeinchue.argus.device.StartGuard.safeStart) com.blazeinchue.argus.device.StartLater.add("link.DesktopLink.start") { com.blazeinchue.argus.link.DesktopLink.start() }
        if (!com.blazeinchue.argus.device.StartGuard.safeStart && Settings.bool("dash_auto", false)) com.blazeinchue.argus.device.StartLater.add("Dashboard.start") { com.blazeinchue.argus.dashboard.Dashboard.start(this) }
        if (!com.blazeinchue.argus.device.StartGuard.safeStart) com.blazeinchue.argus.device.StartLater.add("Overlay.showOrb") { if (Settings.orbPinned && android.provider.Settings.canDrawOverlays(this)) com.blazeinchue.argus.ui.Overlay.showOrb(this) }
        // v0.28.4 argus-docs: index Argus's own docs (+ the real tool list) into argus.db when the build changed. Off the main
        // thread and a few seconds after start so it never slows opening the app; a no-op when nothing changed.
        // v0.45.0 (#14): how long start took on the main thread (and Agent.init's share), in the speed log; very slow starts in the crash log.
        val startMs = android.os.SystemClock.elapsedRealtime() - t0
        try { android.util.Log.i("ArgusStart", "onCreate ${startMs} ms (Agent.init ${agentMs} ms)") } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: android.util.Log.iArgusStart onCreate st", e) }
        com.blazeinchue.argus.device.CrashLog.slow("app start (Agent.init ${agentMs} ms)", startMs)
        if (startMs > com.blazeinchue.argus.agent.LazyStart.ON_CREATE_BUDGET_MS) com.blazeinchue.argus.device.CrashLog.write("START over budget: onCreate ${startMs} ms > ${com.blazeinchue.argus.agent.LazyStart.ON_CREATE_BUDGET_MS} ms")
        val app = this
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(2_500)
            val tools = try { Agent.toolCatalog() } catch (_: Throwable) { null }
            try { com.blazeinchue.argus.agent.ArgusDocs.init(app, tools) } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: ArgusDocs.init", e) }
            // v0.28.5: index each project's full instructions / brief / notes (only what changed) for project_docs_search.
            try { com.blazeinchue.argus.agent.ProjectDocs.init() } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("ArgusApp: ProjectDocs.in", e) }
        }
    }

    /**
     * 0.45.16 low memory (3 GB phones): drop the parse caches when Android asks, so the app is trimmed instead of
     * killed mid-chat. They refill from the chat on the next scroll.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (com.blazeinchue.argus.device.MemoryTrim.shouldDropCaches(level)) {
            try { com.blazeinchue.argus.device.MemoryTrim.dropCaches() } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("onTrimMemory", e) }
            // v0.45.23 perf: also give back the offline model's ~500 MB if it isn't answering (it reloads on demand).
            try { com.blazeinchue.argus.agent.OfflineBrain.trim() } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("onTrimMemory", e) }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        try { com.blazeinchue.argus.device.MemoryTrim.dropCaches() } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("onLowMemory", e) }
        try { com.blazeinchue.argus.agent.OfflineBrain.trim() } catch (e: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("onLowMemory", e) }
    }
}
