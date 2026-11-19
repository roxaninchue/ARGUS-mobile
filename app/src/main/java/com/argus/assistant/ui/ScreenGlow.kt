package com.blaze.assistant.ui


import com.blaze.assistant.i18n.tr
import com.blaze.assistant.BrandName
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.phone.BlazeAccessibilityService
import com.blaze.assistant.phone.ScreenWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 0.50.0: while Blaze controls the screen ([ScreenWork.active]), Blaze's own particles drift along the screen edges over a
 * soft glow, so the user can see Blaze is working; gone ~1 s after the last step. Low-RAM rules:
 *  - one window, added only while active and removed after; nothing runs while idle (the watcher suspends on the flow);
 *  - the existing [EdgeParticles] (40 dots, ~24 fps, one Canvas, no bitmaps or blur; frozen when the phone is warm or
 *    saving battery) over a still gradient glow; reduced motion = the glow only;
 *  - never takes a touch: FLAG_NOT_TOUCHABLE + NOT_FOCUSABLE, and on Android 12+ an app overlay's alpha is kept at 0.8 (the
 *    platform's "untrusted touch" limit) so taps pass through to the app below. The accessibility overlay is preferred.
 * Settings › Look & feel "Glow while Blaze uses the screen" (screen_glow, default on) switches it off.
 */
object ScreenGlow {
    private var view: View? = null
    private var owner: OverlayOwner? = null
    private var watch: Job? = null
    private val shown = mutableStateOf(false)

    val enabled: Boolean get() = try { Settings.bool("screen_glow", true) } catch (_: Throwable) { true }

    /** Started once from BlazeApp (after the first frame). Suspends while Blaze isn't touching the screen. */
    fun watch(ctx: Context) {
        if (watch != null) return
        val app = ctx.applicationContext
        watch = CoroutineScope(Dispatchers.Main + com.blaze.assistant.device.CrashLog.guard).launch {
            while (true) {
                ScreenWork.active.first { it }
                if (enabled && !com.blaze.assistant.bank.BankMode.active) show(app)
                while (true) { delay(400); ScreenWork.refresh(); if (!ScreenWork.active.value) break }
                hide()
            }
        }
    }

    private fun host(ctx: Context): Pair<Context, Int>? {
        BlazeAccessibilityService.instance?.let { return it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
        if (Overlay.canDrawOverApps(ctx)) return ctx to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return null
    }

    private fun show(ctx: Context) {
        if (view != null) { shown.value = true; return }
        val (hc, type) = host(ctx) ?: return
        val wm = hc.getSystemService(WindowManager::class.java) ?: return
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && Build.VERSION.SDK_INT >= 31) alpha = EdgeParticleMath.MAX_PASS_THROUGH_ALPHA
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = tr("{0} screen glow", BrandName.NAME)
        }
        val o = OverlayOwner().also { it.start() }
        shown.value = true
        val v = composeView(hc, o) { ScreenWorkGlow(shown.value) }
        try { wm.addView(v, lp); view = v; owner = o } catch (_: Exception) { o.destroy() }
    }

    private fun hide() {
        shown.value = false
        val v = view ?: return
        val o = owner
        view = null; owner = null
        v.postDelayed({ runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }; o?.destroy() }, 360)
    }
}

/** Pure numbers for the glow window (ScreenWorkTest). */
object EdgeParticleMath {
    /** Android 12+ passes touches through an app overlay only at or below this window alpha (untrusted-touch rule). */
    const val MAX_PASS_THROUGH_ALPHA = 0.8f
    /** How deep the soft glow reaches in from each edge, as a fraction of the shorter side. */
    const val GLOW = 0.075f
}

/**
 * Blaze's own signature edge particles ([EdgeParticles], the assistant-summon effect: white dots with blue / orange sparks
 * drifting round the border) over a soft warm glow, faded in and out with [visible]. With reduced motion only the still
 * glow shows (no frames).
 */
@Composable
fun ScreenWorkGlow(visible: Boolean, modifier: Modifier = Modifier) {
    val fade by animateFloatAsState(if (visible) 1f else 0f, tween(320), label = "glow")
    val glow = Color(0xFFFFB648)      // the logo's gold halo
    val cool = Color(0xFF7FB2FF)
    Box(modifier.fillMaxSize().graphicsLayer { alpha = fade }) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val g = minOf(w, h) * EdgeParticleMath.GLOW
            val edge = glow.copy(alpha = 0.22f)
            drawRect(Brush.verticalGradient(listOf(edge, Color.Transparent), 0f, g), Offset.Zero, Size(w, g))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, cool.copy(alpha = 0.2f)), h - g, h), Offset(0f, h - g), Size(w, g))
            drawRect(Brush.horizontalGradient(listOf(edge, Color.Transparent), 0f, g), Offset.Zero, Size(g, h))
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, edge), w - g, w), Offset(w - g, 0f), Size(g, h))
        }
        if (visible && MotionTokens.enabled) EdgeParticles(energy = 0.85f)
    }
}
