package com.blaze.assistant.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
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
import kotlin.random.Random

/**
 * 0.50.0: while Blaze controls the screen ([ScreenWork.active]), Blaze's own particles drift along the screen edges over a
 * soft glow, so the user can see Blaze is working; gone ~1 s after the last step. Low-RAM rules:
 *  - one window, added only while active and removed after; nothing runs while idle (the watcher suspends on the flow);
 *  - 32 dots at ≤ 30 fps on one Canvas (no bitmaps, no blur), a still glow when the phone is warm or saving battery;
 *  - never takes a touch: FLAG_NOT_TOUCHABLE + NOT_FOCUSABLE, and on Android 12+ an app overlay's alpha is kept at 0.8 (the
 *    platform's "untrusted touch" limit) so taps pass through to the app below. The accessibility overlay is preferred.
 * Settings › Look & feel "screen_glow" (default on) switches it off. [EdgeParticleMath] is pure (ScreenGlowTest).
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
            title = "Blaze screen glow"
        }
        val o = OverlayOwner().also { it.start() }
        shown.value = true
        val v = composeView(hc, o) { EdgeParticles(shown.value) }
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

/** Where a dot is on the screen edge: pure geometry, shared by the composable and the test. */
object EdgeParticleMath {
    const val DOTS = 32
    const val MAX_PASS_THROUGH_ALPHA = 0.8f
    /** The band along each edge the dots live in, as a fraction of the shorter side. */
    const val BAND = 0.06f

    /**
     * A dot at perimeter position [p] (0..1, clockwise from the top-left corner), [inset] (0..1 of the band) in from the edge,
     * on a [w]×[h] screen. Always inside the screen and within the band of its edge.
     */
    fun at(p: Float, inset: Float, w: Float, h: Float): Offset {
        val band = minOf(w, h) * BAND * inset.coerceIn(0f, 1f)
        val per = 2f * (w + h)
        var d = (((p % 1f) + 1f) % 1f) * per
        return when {
            d < w -> Offset(d, band)
            d < w + h -> { d -= w; Offset(w - band, d) }
            d < 2 * w + h -> { d -= w + h; Offset(w - d, h - band) }
            else -> { d -= 2 * w + h; Offset(band, h - d) }
        }.let { Offset(it.x.coerceIn(0f, w), it.y.coerceIn(0f, h)) }
    }

    /** True when [o] lies within the edge band (so the middle of the screen stays clear). */
    fun inBand(o: Offset, w: Float, h: Float): Boolean {
        val b = minOf(w, h) * BAND + 0.5f
        return o.x <= b || o.y <= b || o.x >= w - b || o.y >= h - b
    }
}

private data class EdgeDot(val p: Float, val inset: Float, val r: Float, val speed: Float, val hot: Boolean)

/** The particles (Blaze's ParticleField dust) running round the edges over a soft warm glow. */
@Composable
fun EdgeParticles(visible: Boolean, modifier: Modifier = Modifier) {
    val fade by animateFloatAsState(if (visible) 1f else 0f, tween(320), label = "glow")
    var time by remember { mutableFloatStateOf(0f) }
    val hot by com.blaze.assistant.device.Thermal.hot.collectAsState()
    val moving = visible && !hot && MotionTokens.enabled
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (true) withFrameMillis { now ->
            if (last == 0L) last = now
            if (now - last >= 33L) { time = (time + (now - last) / 20_000f) % 1f; last = now }
        }
    }
    val dots = remember {
        val rnd = Random(23)
        List(EdgeParticleMath.DOTS) { EdgeDot(rnd.nextFloat(), rnd.nextFloat(), 0.9f + rnd.nextFloat() * 1.2f, 0.6f + rnd.nextFloat() * 1.2f, it % 6 == 0) }
    }
    val glow = Color(0xFFFFB648)      // the logo's gold halo
    val cool = Color(0xFF7FB2FF)
    Canvas(modifier.fillMaxSize().graphicsLayer { alpha = fade }) {
        val w = size.width; val h = size.height
        val g = minOf(w, h) * 0.075f
        val pulse = 0.75f + 0.25f * kotlin.math.sin(time * 6.283f * 8f)
        val edge = glow.copy(alpha = 0.26f * pulse)
        drawRect(Brush.verticalGradient(listOf(edge, Color.Transparent), 0f, g), Offset.Zero, Size(w, g))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, cool.copy(alpha = 0.22f * pulse)), h - g, h), Offset(0f, h - g), Size(w, g))
        drawRect(Brush.horizontalGradient(listOf(edge, Color.Transparent), 0f, g), Offset.Zero, Size(g, h))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, edge), w - g, w), Offset(w - g, 0f), Size(g, h))
        dots.forEach { d ->
            val o = EdgeParticleMath.at(d.p + time * d.speed, d.inset, w, h)
            val col = if (d.hot) Color.White.copy(alpha = 0.85f) else ParticleBlue.of(true).copy(alpha = 0.6f)
            drawCircle(col, d.r * 1.6f * density, o)
            if (d.hot) drawCircle(glow.copy(alpha = 0.25f), d.r * 4.5f * density, o)
        }
    }
}
