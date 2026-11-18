package com.blaze.assistant.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.home.HomeBoard
import com.blaze.assistant.home.HomeTodo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 2026-11-18 first run, after sign-in: the "Set up ARGUS" checklist goes to the top of Life ([SetupChecklist]), then ARGUS
 * introduces itself in the chat ([com.blaze.assistant.agent.Agent.introduce], model-written, [FirstRunIntro]). Once per
 * install ([PREF_WELCOMED]). Life keeps the list in step with the phone ([sync], on each visit) and a tap on a step opens
 * its screen ([open]).
 */
object FirstRun {
    const val PREF_WELCOMED = "first_run_welcomed"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.blaze.assistant.device.CrashLog.guard)

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** What the phone says now about each step (cheap: permissions, prefs and system settings). */
    fun probe(ctx: Context): SetupChecklist.Probe {
        fun ok(what: String, f: () -> Boolean): Boolean = try { f() } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("FirstRun.probe $what", t); false }
        return SetupChecklist.Probe(
            calendarPermission = ok("calendar") { granted(ctx, Manifest.permission.READ_CALENDAR) },
            googleConnected = ok("google") { com.blaze.assistant.connect.Connectors.connected("google") },
            notifications = ok("notifications") { Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS) },
            wakeWord = ok("wake") { Settings.wakeWord && com.blaze.assistant.voice.WakeModel.ready(ctx) },
            defaultAssistant = ok("assistant") { com.blaze.assistant.assist.BlazeInteractionService.isDefault(ctx) },
            desktopPaired = ok("desktop") { com.blaze.assistant.link.DesktopLink.paired },
            phoneControl = ok("a11y") { com.blaze.assistant.phone.BlazeAccessibilityService.isEnabled(ctx) },
        )
    }

    /** The board opens in the background at start; wait for it a little (it is usually there already). */
    private suspend fun board(ctx: Context): Boolean {
        HomeBoard.init(ctx)
        var n = 0
        while (!HomeBoard.ready && n++ < 100) delay(100)
        return HomeBoard.ready
    }

    /** Onboarding finished: the checklist, then the introduction. Runs once per install. */
    fun welcome(ctx: Context) {
        val app = ctx.applicationContext
        if (Settings.bool(PREF_WELCOMED, false)) return
        Settings.setBool(PREF_WELCOMED, true)
        scope.launch {
            val items = if (board(app)) HomeBoard.checklist(SetupChecklist.detected(probe(app)))?.items.orEmpty() else emptyList()
            val open = if (items.isNotEmpty()) SetupChecklist.open(items) else SetupChecklist.STEPS.map { it.text }
            val lang = com.blaze.assistant.i18n.AppLang.current.english
            com.blaze.assistant.agent.Agent.introduce(FirstRunIntro.kickoff(open, lang), com.blaze.assistant.i18n.tr(FirstRunIntro.FALLBACK))
        }
    }

    /** Life on screen: ticks the steps the phone now reports done (never unticks). Nothing when there is no checklist. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { if (HomeBoard.ready) HomeBoard.checklist(SetupChecklist.detected(probe(app))) }
    }

    /** A tap on a to-do row that names a screen: opens it (SelfSetup knows the system screens and ARGUS's own pages). */
    fun open(ctx: Context, item: HomeTodo.Item): Boolean {
        if (item.open.isBlank()) return false
        return try { com.blaze.assistant.agent.SelfSetup.open(ctx, item.open); true }
        catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("FirstRun.open", t); false }
    }
}
