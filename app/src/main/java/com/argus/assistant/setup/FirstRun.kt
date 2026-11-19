package com.argus.assistant.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.argus.assistant.agent.LlmClient
import com.argus.assistant.agent.Settings
import com.argus.assistant.device.CrashLog
import com.argus.assistant.home.HomeBoard
import com.argus.assistant.home.HomeTodo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2026-11-18 first run, after sign-in (owner's spec): ARGUS introduces itself in the chat and the "Set up ARGUS" checklist
 * is in Life. Owner 2026-11-19, faster intro: the app puts the checklist there and the introduction lands with it
 * ([com.argus.assistant.agent.Agent.introduce], [FirstRunIntro]): in English the fixed introduction at once, in another
 * language one model reply with no tools (the fixed one, translated, when no model answers in time). Once per install ([PREF_WELCOMED]). Life keeps the list in step with the
 * phone ([sync], on each visit) and a tap on a step opens its screen ([open]).
 */
object FirstRun {
    const val PREF_WELCOMED = "first_run_welcomed"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashLog.guard)

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** What the phone says now about each step (cheap: permissions, prefs and system settings). */
    fun probe(ctx: Context): SetupChecklist.Probe {
        fun ok(what: String, f: () -> Boolean): Boolean = try { f() } catch (t: Throwable) { CrashLog.quiet("FirstRun.probe $what", t); false }
        return SetupChecklist.Probe(
            calendarPermission = ok("calendar") { granted(ctx, Manifest.permission.READ_CALENDAR) },
            googleConnected = ok("google") { com.argus.assistant.connect.Connectors.connected("google") },
            notifications = ok("notifications") { Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS) },
            wakeWord = ok("wake") { Settings.wakeWord && com.argus.assistant.voice.WakeModel.ready(ctx) },
            defaultAssistant = ok("assistant") { com.argus.assistant.assist.ArgusInteractionService.isDefault(ctx) },
            desktopPaired = ok("desktop") { com.argus.assistant.link.DesktopLink.paired },
            phoneControl = ok("a11y") { com.argus.assistant.phone.ArgusAccessibilityService.isEnabled(ctx) },
        )
    }

    /** The board opens in the background at start; wait for it a little (it is usually there already). */
    private suspend fun board(ctx: Context): Boolean {
        HomeBoard.init(ctx)
        var n = 0
        while (!HomeBoard.ready && n++ < 100) delay(100)
        return HomeBoard.ready
    }

    /**
     * Onboarding finished: the checklist goes in Life and ARGUS's introduction is posted with it (owner 2026-11-19). English
     * needs no model call; another language gets one model reply with no tools ([FirstRunIntro]). Runs once per install.
     */
    fun welcome(ctx: Context) {
        val app = ctx.applicationContext
        if (Settings.bool(PREF_WELCOMED, false)) return
        Settings.setBool(PREF_WELCOMED, true)
        scope.launch {
            val lang = com.argus.assistant.i18n.AppLang.current
            val write: (suspend () -> String?)? = if (!FirstRunIntro.needsModel(lang.code)) null else {
                val open = SetupChecklist.open(SetupChecklist.fresh(SetupChecklist.detected(probe(app))))
                val ask: suspend () -> String? = {
                    LlmClient.complete(FirstRunIntro.messages(open, lang.english), null, null, null,
                        LlmClient.Lane.MAIN).optString("content")
                }
                ask
            }
            com.argus.assistant.agent.Agent.introduce(com.argus.assistant.i18n.tr(FirstRunIntro.FALLBACK), write,
                beforePost = { withContext(Dispatchers.IO) { ensureChecklist(app) } })
        }
    }

    /** After the introduction's turn: the checklist ARGUS made is kept in step; with none (and none removed before), the app adds it. */
    private suspend fun ensureChecklist(app: Context) {
        if (board(app)) HomeBoard.checklist(SetupChecklist.detected(probe(app)))
    }

    /** Life on screen: ticks the steps the phone now reports done (never unticks). Nothing when there is no checklist. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { if (HomeBoard.ready) HomeBoard.checklist(SetupChecklist.detected(probe(app))) }
    }

    /** A tap on a to-do row that names a screen: opens it (SelfSetup knows the system screens and ARGUS's own pages). */
    fun open(ctx: Context, item: HomeTodo.Item): Boolean {
        if (item.open.isBlank()) return false
        return try { com.argus.assistant.agent.SelfSetup.open(ctx, item.open); true }
        catch (t: Throwable) { CrashLog.quiet("FirstRun.open", t); false }
    }
}
