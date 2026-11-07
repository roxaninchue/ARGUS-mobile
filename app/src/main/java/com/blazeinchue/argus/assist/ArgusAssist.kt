package com.blazeinchue.argus.assist

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import com.blazeinchue.argus.MainActivity
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.blazeinchue.argus.agent.Agent
import com.blazeinchue.argus.ui.AssistPanel
import com.blazeinchue.argus.ui.Overlay
import com.blazeinchue.argus.ui.OverlayOwner
import com.blazeinchue.argus.ui.composeView
import com.blazeinchue.argus.voice.Voice

/**
 * Lets Argus be picked as the phone's Digital assistant app (instead of Gemini etc).
 * Long-press power / home, or the corner swipe, then opens Argus listening.
 */
class ArgusInteractionService : VoiceInteractionService() {
    companion object {
        @Volatile private var instance: ArgusInteractionService? = null

        /** Called by the wake-word listener. True when the session could be shown. */
        fun showFromWake(): Boolean = showPanel(listen = true)

        /** Show Argus's panel over the current app. False when Argus isn't the active assistant. */
        fun showPanel(listen: Boolean): Boolean {
            val s = instance ?: return false
            return try { s.showSession(Bundle().apply { putBoolean("listen", listen) }, 0); true }
            catch (_: Exception) { false }
        }

        fun isDefault(ctx: Context): Boolean {
            val cur = AndroidSettings.Secure.getString(ctx.contentResolver, "voice_interaction_service") ?: return false
            return cur.startsWith(ctx.packageName + "/")
        }

        /** Opens the system screen where the user picks the Digital assistant app. */
        fun openPicker(ctx: Context) {
            val tries = listOf(
                Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS),
                Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
                Intent(AndroidSettings.ACTION_SETTINGS)
            )
            for (i in tries) {
                try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) { }
            }
        }
    }

    override fun onReady() { super.onReady(); instance = this }
    override fun onShutdown() { instance = null; super.onShutdown() }
}

class ArgusSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = ArgusSession(this)
}

/**
 * What appears when you long-press home/power (or say "Hey Argus"): an orb and a compact
 * chat over the app you're in, instead of jumping to Argus's full screen.
 */
class ArgusSession(ctx: Context) : VoiceInteractionSession(ctx) {
    private val owner = OverlayOwner()
    private var listen = mutableStateOf(0)
    private var autoListen = true

    override fun onCreate() {
        super.onCreate()
        owner.start()
        window?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun onCreateContentView(): View = composeView(context, owner) {
        val n by listen
        androidx.compose.runtime.key(n) {
            AssistPanel(
                autoListen = autoListen,
                onClose = { hide() },
                onPin = { hide(); Overlay.showOrb(context) || run { Overlay.requestOverlayPermission(context); false } },
                onExpand = { openApp(); hide() }
            )
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Long-press of home/power arrives with SHOW_WITH_ASSIST; start listening right away.
        autoListen = args?.getBoolean("listen", true) ?: true
        Agent.overlayOpen = true
        listen.value = listen.value + 1
    }

    override fun onHide() {
        Voice.cancelListening()
        Agent.overlayOpen = Overlay.orbShowing
        super.onHide()
    }

    override fun onDestroy() { owner.destroy(); super.onDestroy() }

    // v0.33.0 Screen Doctor: the screen's text and a screenshot, only when the user summons Argus over an app.
    override fun onHandleAssist(state: AssistState) {
        try { ScreenDoctor.clear(); ScreenDoctor.onStructure(state.assistStructure) } catch (_: Throwable) { }
    }
    @Deprecated("pre-29 path")
    override fun onHandleAssist(data: Bundle?, structure: android.app.assist.AssistStructure?, content: android.app.assist.AssistContent?) {
        if (Build.VERSION.SDK_INT < 29) try { ScreenDoctor.clear(); ScreenDoctor.onStructure(structure) } catch (_: Throwable) { }
    }
    override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {
        try { ScreenDoctor.onScreenshot(screenshot) } catch (_: Throwable) { }
    }

    private fun openApp() {
        val i = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { if (Build.VERSION.SDK_INT >= 29) startAssistantActivity(i) else context.startActivity(i) }
        catch (_: Exception) { try { context.startActivity(i) } catch (_: Exception) { } }
    }
}

/** Android requires an assistant to name a recognizer; Argus uses the phone's own. */
class ArgusRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, cb: Callback?) { cb?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(cb: Callback?) {}
    override fun onStopListening(cb: Callback?) {}
}
