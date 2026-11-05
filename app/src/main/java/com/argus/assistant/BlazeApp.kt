package com.argus.assistant

import android.app.Application
import com.argus.assistant.agent.Agent
import com.argus.assistant.agent.Settings
import kotlinx.coroutines.launch

class BlazeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val t0 = android.os.SystemClock.elapsedRealtime()
        com.argus.assistant.device.CrashLog.init(this)
        com.argus.assistant.device.CrashLog.shieldMainThread()
        // Lazy start: Agent.Session's verification and opening blaze.db run on background threads meanwhile (StartWarm,
        // Mind.openLater); work the first frame doesn't need waits for it (StartLater, run on the main thread after the
        // first frame, or after StartLater.FALLBACK_MS when Blaze starts without a screen). Goal: onCreate < 200 ms.
        com.argus.assistant.device.StartWarm.begin(this)
        com.argus.assistant.device.StartLater.armFallback()
        // 0.45.19 fix 7: one Back rule for every Blaze window (Back returns to the chat).
        try { com.argus.assistant.ui.BackRule.install(this) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: BackRule", e) }
        com.argus.assistant.device.StartGuard.begin(this)   // 0.45.16: crash-loop detection (safe start)
        com.argus.assistant.device.MainWatch.start()        // 0.45.16: ANR / stall log with the main thread's stack
        com.argus.assistant.device.StartGuard.step("Settings.init") { Settings.init(this) }
        com.argus.assistant.device.StartGuard.step("Agent.preload") { Agent.preload(this) }   // v0.45.0 (#2): the main chat is read and parsed on a background thread while the rest starts
        com.argus.assistant.device.StartGuard.step("agent.SimpleMode.load") { com.argus.assistant.agent.SimpleMode.load() }   // v0.39.0 Simple / Advanced
        com.argus.assistant.device.StartGuard.step("agent.SwipeNav.load") { com.argus.assistant.agent.SwipeNav.load() }     // v0.39.0 Navigation: Swipe or Classic tabs
        try { com.argus.assistant.bank.BankMode.init(this) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: BankMode.initth", e) }   // v0.43.0 Bank mode (back on after a restart mid-banking)
        // v0.37.0 Awareness: what's playing, on screen and just happened, for every prompt.
        try { com.argus.assistant.agent.AwarenessHooks.install(this) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: AwarenessHooks", e) }
        // v0.24.4: back up prefs and data once per build before the setup gate (or anything after it) runs.
        try {
            if (!com.argus.assistant.setup.SetupGate.isDone()) com.argus.assistant.setup.SetupGate.backup(this, com.argus.assistant.update.Updater.installedCode(this))
        } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: SetupGate.i", e) }
        // v0.23.4: Auto's per-provider model lists and per-model cooldowns (new files, no prefs changes).
        try { com.argus.assistant.agent.ProviderCatalog.init(filesDir); com.argus.assistant.agent.ModelMemory.init(filesDir) } catch (e: Exception) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: ProviderCatalo", e) }
        try { com.argus.assistant.agent.Projects.load() } catch (e: Exception) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: Projects.load", e) }
        // v0.45.0 (#14): background-only scheduling (file reads + WorkManager, nothing the first frame needs) leaves the main thread.
        val bgApp = this
        Thread({
            // v0.33.0 Life Log 2: recordings stopped but not yet reviewed (phone restarted, app killed) carry on.
            try { com.argus.assistant.life.LifeLogWorker.resumePending(bgApp) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: LifeLogWorker.r", e) }
            try { com.argus.assistant.notify.MoneyRadar.schedule(bgApp) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: MoneyRadar.sc", e) }
            try { com.argus.assistant.agent.TimeCapsule.schedule(bgApp) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: TimeCapsule.sc", e) }
            try { com.argus.assistant.agent.MindReview.schedule(bgApp) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: MindReview.sch", e) }
        }, "blaze-start-bg").start()
        // v0.33.0 Safe Walk survives a reboot / process restart; sealed time capsules keep their wake-up. (After the first frame.)
        com.argus.assistant.device.StartLater.add("phone.SafeWalk.handle") { com.argus.assistant.phone.SafeWalk.handle(this, android.content.Intent.ACTION_BOOT_COMPLETED) }
        com.argus.assistant.device.StartLater.add("phone.CallCopilot.init") { com.argus.assistant.phone.CallCopilot.init(this) }
        try { com.argus.assistant.agent.OfflineBrain.init(this) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: OfflineBrain.i", e) }
        // v1.9.10 fix: a leftover "Auto" steps model silently sent phone steps through the free chain
        // even after the user picked a specific model. Clear it once; they can set it again.
        if (Settings.str("mig_steps_auto", "").isEmpty()) {
            if (Settings.providerId != com.argus.assistant.agent.Providers.AUTO && Settings.stepsProvider == com.argus.assistant.agent.Providers.AUTO) Settings.stepsProvider = ""
            Settings.setStr("mig_steps_auto", "1")
        }
        com.argus.assistant.device.StartGuard.step("agent.IntentCache.init") { com.argus.assistant.agent.IntentCache.init(this) }
        com.argus.assistant.device.StartGuard.step("phone.Watchers.init") { com.argus.assistant.phone.Watchers.init(this) }
        com.argus.assistant.device.StartGuard.step("agent.Autonomy.init") { com.argus.assistant.agent.Autonomy.init(this) }
        com.argus.assistant.device.StartGuard.step("agent.Processes.init") { com.argus.assistant.agent.Processes.init(this) }
        // One mind (v0.18.0): blaze.db first (mind.db until it is copied); Memory is now a view of its memories table.
        // Lazy start: blaze.db opens off the main thread (started by StartWarm; this only makes sure it was started) and
        // Memory attaches to the table there once it is open. Nothing here waits for it.
        com.argus.assistant.device.StartGuard.step("agent.Mind.openLater") { com.argus.assistant.agent.Mind.openLater(this) }
        com.argus.assistant.device.StartGuard.step("agent.Memory.init") { com.argus.assistant.agent.Memory.init(this, attachLater = true) }
        com.argus.assistant.device.StartGuard.step("agent.Usage.init") { com.argus.assistant.agent.Usage.init(this) }
        try { com.argus.assistant.agent.ToolStats.init(this) } catch (e: Exception) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: ToolStats.init", e) }
        try { com.argus.assistant.agent.ToolCache.init(filesDir) } catch (e: Exception) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: ToolCache.init", e) }
        com.argus.assistant.device.StartGuard.step("agent.Learning.init") { com.argus.assistant.agent.Learning.init(this) }
        com.argus.assistant.device.StartGuard.step("connect.Mcp.load") { com.argus.assistant.connect.Mcp.load() }
        com.argus.assistant.device.StartGuard.step("connect.BlazeAccount.start") { com.argus.assistant.connect.BlazeAccount.start() }
        // v0.40.0 Cloud sync: off by default; when on, live end-to-end encrypted sync with your other phones.
        try { com.argus.assistant.connect.CloudSync.init(this) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: CloudSync.in", e) }
        com.argus.assistant.device.StartGuard.step("agent.Traces.init") { com.argus.assistant.agent.Traces.init(this) }
        // v0.30.7: phone steps that worked, per request, sent back as one run_plan next time.
        try { com.argus.assistant.agent.TapLoop.Plans.init(java.io.File(filesDir, "plan_memory.json")) } catch (e: Exception) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: TapLoop.Plans.", e) }
        val tAgent = android.os.SystemClock.elapsedRealtime()
        com.argus.assistant.device.StartGuard.step("Agent.init") { Agent.init(this) }
        // 0.46.0 Blaze Stage: the saved layout and timers (re-arms running timers' alarms).
        try { com.argus.assistant.stage.StageStore.init(this) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: StageStore.init", e) }
        try { com.argus.assistant.movies.Movies.init(this) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: Movies.init", e) }
        val agentMs = android.os.SystemClock.elapsedRealtime() - tAgent
        com.argus.assistant.device.StartGuard.step("orchestra.OrchestraRuns.init") { com.argus.assistant.orchestra.OrchestraRuns.init(this) }
        com.argus.assistant.device.StartGuard.step("agent.BgAgents.init") { com.argus.assistant.agent.BgAgents.init(this) }
        com.argus.assistant.device.StartGuard.step("agent.TaskMemoryStore.init") { com.argus.assistant.agent.TaskMemoryStore.init(this) }   // 0.52.1: reads on IO
        com.argus.assistant.device.StartGuard.step("routine.Routines.init") { com.argus.assistant.routine.Routines.init(this) }
        // v0.29.0 Home board: widgets + cached content in blaze.db, refreshed by WorkManager (≥ 1 h).
        com.argus.assistant.device.StartGuard.step("home.HomeBoard.init") { com.argus.assistant.home.HomeBoard.init(this) }
        // 0.45.29: BlazeOS ("Blaze Phone", Home's virtual phone) was removed; once after the update, free its disk, prefs and profile.
        if (!com.argus.assistant.device.StartGuard.safeStart && !Settings.bool(com.argus.assistant.home.BlazeOsCleanup.PREF_DONE, false)) {
            val app = this
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                kotlinx.coroutines.delay(20_000)   // after start-up work, not competing with it
                com.argus.assistant.home.BlazeOsCleanup.runOnce(app)
            }
        }
        com.argus.assistant.device.StartGuard.step("notify.Notifier.init") { com.argus.assistant.notify.Notifier.init(this) }
        com.argus.assistant.device.StartLater.add("update.Updater.checkInBackground") { com.argus.assistant.update.Updater.checkInBackground(this) }
        com.argus.assistant.device.StartGuard.step("Settings.tellIfSecretsDropped") { Settings.tellIfSecretsDropped() }
        com.argus.assistant.device.StartGuard.step("voice.Voice.init") { com.argus.assistant.voice.Voice.init(this) }
        // Wake word starts from the screen (MainActivity/assistant) and at boot: starting a
        // microphone service while Blaze is in the background makes Android 12+ kill the app.
        com.argus.assistant.device.StartGuard.step("terminal.Terminal.init") { com.argus.assistant.terminal.Terminal.init(this) }
        // v0.45.26: the on-device backup brain (installs quietly on Wi-Fi, starts only when Blaze Cloud struggles) and
        // "ready out of the box" Linux tools for Linuxes installed before this version. Both wait a minute after start.
        // 0.46.6: low-RAM phones keep a smaller picture cache (provider engines run one at a time everywhere)
        try { com.argus.assistant.movies.ProviderSandbox.Gate.configure(this) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: Gate.configure", e) }
        com.argus.assistant.device.StartLater.add("device.Thermal.init") { com.argus.assistant.device.Thermal.init(this) }
        if (!com.argus.assistant.device.StartGuard.safeStart) com.argus.assistant.device.StartLater.add("agent.BackupBrain.init") { com.argus.assistant.agent.BackupBrain.init(this) }
        // Wave B-4: the on-phone Linux desktop was removed; once after the update, free what it left (packages, caches, prefs).
        if (!com.argus.assistant.device.StartGuard.safeStart && !Settings.bool(com.argus.assistant.terminal.LinuxDesktopCleanup.PREF_DONE, false)) {
            val app = this
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                kotlinx.coroutines.delay(30_000)   // after start-up work, not competing with it
                com.argus.assistant.terminal.LinuxDesktopCleanup.runOnce(app)
            }
        }
        com.argus.assistant.device.StartGuard.step("agent.SkillLibrary.init") { com.argus.assistant.agent.SkillLibrary.init(this) }
        // BlazeRouter: start when it's the brain or set to start with Blaze (after Terminal/Linux are ready).
        if (!com.argus.assistant.device.StartGuard.safeStart && (com.argus.assistant.agent.BlazeRouter.autostart || Settings.providerId == com.argus.assistant.agent.BlazeRouter.ID)) {
            val app = this
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
                try { com.argus.assistant.agent.BlazeRouter.start(app) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: BlazeRouter.st", e) }
            }
        }
        Thread { com.argus.assistant.agent.SkillPacks.ensureStarters(); com.argus.assistant.agent.SkillLibrary.seed(this) }.start()
        // Lazy start: triggers, proactive helpers, the task overlay, desktop link, dashboard and orb start after the first frame.
        com.argus.assistant.device.StartLater.add("proactive.Triggers.init") { com.argus.assistant.proactive.Triggers.init(this) }
        if (!com.argus.assistant.device.StartGuard.safeStart) com.argus.assistant.device.StartLater.add("proactive.Proactive.start") { com.argus.assistant.proactive.Proactive.start(this) }
        // v0.38.0 Jarvis mode: questions, screen glances, camera looks, its own routines (per level).
        if (!com.argus.assistant.device.StartGuard.safeStart) com.argus.assistant.device.StartLater.add("proactive.Jarvis.start") { com.argus.assistant.proactive.FollowUps.init(this); com.argus.assistant.proactive.Jarvis.start(this) }
        com.argus.assistant.device.StartLater.add("ui.Overlay.watchTasks") { com.argus.assistant.ui.Overlay.watchTasks(this) }
        // 0.50.0: edge particles while Blaze controls the screen (suspends while idle)
        com.argus.assistant.device.StartLater.add("ui.ScreenGlow.watch") { com.argus.assistant.ui.ScreenGlow.watch(this) }
        if (!com.argus.assistant.device.StartGuard.safeStart) com.argus.assistant.device.StartLater.add("link.DesktopLink.start") { com.argus.assistant.link.DesktopLink.start() }
        if (!com.argus.assistant.device.StartGuard.safeStart && Settings.bool("dash_auto", false)) com.argus.assistant.device.StartLater.add("Dashboard.start") { com.argus.assistant.dashboard.Dashboard.start(this) }
        if (!com.argus.assistant.device.StartGuard.safeStart) com.argus.assistant.device.StartLater.add("Overlay.showOrb") { if (Settings.orbPinned && android.provider.Settings.canDrawOverlays(this)) com.argus.assistant.ui.Overlay.showOrb(this) }
        // v0.28.4 blaze-docs: index Blaze's own docs (+ the real tool list) into blaze.db when the build changed. Off the main
        // thread and a few seconds after start so it never slows opening the app; a no-op when nothing changed.
        // v0.45.0 (#14): how long start took on the main thread (and Agent.init's share), in the speed log; very slow starts in the crash log.
        val startMs = android.os.SystemClock.elapsedRealtime() - t0
        try { android.util.Log.i("BlazeStart", "onCreate ${startMs} ms (Agent.init ${agentMs} ms)") } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: android.util.Log.iBlazeStart onCreate st", e) }
        com.argus.assistant.device.CrashLog.slow("app start (Agent.init ${agentMs} ms)", startMs)
        if (startMs > com.argus.assistant.agent.LazyStart.ON_CREATE_BUDGET_MS) com.argus.assistant.device.CrashLog.write("START over budget: onCreate ${startMs} ms > ${com.argus.assistant.agent.LazyStart.ON_CREATE_BUDGET_MS} ms")
        val app = this
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(2_500)
            val tools = try { Agent.toolCatalog() } catch (_: Throwable) { null }
            try { com.argus.assistant.agent.BlazeDocs.init(app, tools) } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: BlazeDocs.init", e) }
            // v0.28.5: index each project's full instructions / brief / notes (only what changed) for project_docs_search.
            try { com.argus.assistant.agent.ProjectDocs.init() } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("BlazeApp: ProjectDocs.in", e) }
        }
    }

    /**
     * 0.45.16 low memory (3 GB phones): drop the parse caches when Android asks, so the app is trimmed instead of
     * killed mid-chat. They refill from the chat on the next scroll.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (com.argus.assistant.device.MemoryTrim.shouldDropCaches(level)) {
            try { com.argus.assistant.device.MemoryTrim.dropCaches() } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("onTrimMemory", e) }
            // v0.45.23 perf: also give back the offline model's ~500 MB if it isn't answering (it reloads on demand).
            try { com.argus.assistant.agent.OfflineBrain.trim() } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("onTrimMemory", e) }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        try { com.argus.assistant.device.MemoryTrim.dropCaches() } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("onLowMemory", e) }
        try { com.argus.assistant.agent.OfflineBrain.trim() } catch (e: Throwable) { com.argus.assistant.device.CrashLog.quiet("onLowMemory", e) }
    }
}
