package com.blaze.assistant.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 0.49.0 (mem1-ui-browse): particles around the screen while Blaze is driving the phone.
 *
 * Report: "when Blaze is controlling the screen, show particles around the screen to indicate Blaze is working. Make it
 * feel alive and intentional, not a generic spinner."
 *
 * A slow current of embers travels the screen's edge for as long as Blaze holds the phone. Each particle walks the
 * perimeter on its own speed and phase (spread by the golden ratio, so they never bunch into a ring of dots), fades in
 * and out over its cycle instead of popping, and breathes in size. Brand blue with a warm minority, so it reads as
 * Blaze rather than as a loading indicator.
 *
 * Deliberately not a spinner: nothing rotates, nothing is centred, and the motion is a perimeter drift, so it never
 * competes with the app underneath or with the pointer's own glide to a target.
 *
 * Light on a low-end phone: one plain View in an accessibility overlay window, FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE,
 * [COUNT] particles, no Compose. It only exists while the pointer does ([Pointer.ensure] starts it, [Pointer.done]
 * stops it), so it costs nothing when Blaze is idle.
 *
 * The geometry is pure and tested (ScreenParticlesTest).
 */
object ScreenParticles {
    /** How many embers travel the edge. Enough to read as a current, few enough for a low-end phone. */
    const val COUNT = 30

    /** Blaze blue, and the warm minority that keeps it from looking like a system progress bar. */
    const val ACCENT = 0xFF1F4BD8.toInt()
    const val WARM = 0xFFFF7A1A.toInt()

    /** How far in from the screen edge the current runs, in dp. */
    const val INSET_DP = 3f

    // ---------- pure rules (ScreenParticlesTest) ----------

    private fun frac(v: Float): Float = v - kotlin.math.floor(v)

    /** Each particle's fixed offset in its cycle: the golden ratio, so no two ever travel together. */
    fun phase(i: Int): Float = frac(i * 0.6180339887f)

    /** Each particle's own speed (0.6x .. 1.4x), so the current shears instead of moving as one block. */
    fun speed(i: Int): Float = 0.6f + 0.8f * frac(i * 0.3819660113f)

    /** Where particle [i] is in its cycle at [ms]: 0..1, wrapping. */
    fun cycle(i: Int, ms: Long): Float = frac(ms / 1000f * speed(i) + phase(i))

    /**
     * The particle's place on the inset ring at [ms]. Walks the perimeter clockwise: top edge, right, bottom, left.
     * Always inside the ring, whatever the screen size.
     */
    fun point(i: Int, ms: Long, w: Float, h: Float, inset: Float): Pair<Float, Float> {
        val left = inset
        val top = inset
        val right = (w - inset).coerceAtLeast(left + 1f)
        val bottom = (h - inset).coerceAtLeast(top + 1f)
        val rw = right - left
        val rh = bottom - top
        val perim = 2f * (rw + rh)
        if (perim <= 0f) return left to top
        var p = cycle(i, ms) * perim
        return when {
            p <= rw -> (left + p) to top
            p <= rw + rh -> { p -= rw; right to (top + p) }
            p <= 2f * rw + rh -> { p -= rw + rh; (right - p) to bottom }
            else -> { p -= 2f * rw + rh; left to (bottom - p) }
        }
    }

    /** Fade in and out over the cycle, so a particle appears and dissolves rather than popping at the corner. */
    fun alpha(i: Int, ms: Long): Float = kotlin.math.sin(cycle(i, ms) * Math.PI.toFloat()).coerceIn(0f, 1f)

    /** Breathing size in dp: 1.1 .. 2.4 dp, out of phase with the fade so it never blinks. */
    fun radiusDp(i: Int, ms: Long): Float {
        val c = cycle(i, ms)
        return 1.1f + 1.3f * (0.5f + 0.5f * kotlin.math.sin(c * 2f * Math.PI.toFloat() + 1.2f))
    }

    /** Brand blue, with roughly one in five warm. */
    fun color(i: Int): Int = if (i % 5 == 4) WARM else ACCENT

    // ---------- the window ----------

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var view: View? = null
    private var wm: WindowManager? = null

    /** Start the current. Safe to call repeatedly; a no-op while it is already running. */
    fun start(ctx: Context) {
        main.post {
            if (view != null) return@post
            val w = ctx.getSystemService(WindowManager::class.java) ?: return@post
            val d = ctx.resources.displayMetrics.density
            val v = Field(ctx, d)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try { w.addView(v, lp); view = v; wm = w } catch (_: Exception) { }
        }
    }

    /** Stop the current and take the window away. */
    fun stop() {
        main.post {
            view?.let { v -> try { wm?.removeView(v) } catch (_: Exception) { } }
            view = null; wm = null
        }
    }

    /** The embers. Plain Canvas, no Compose, no allocation per frame. */
    private class Field(ctx: Context, val d: Float) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val inset = INSET_DP * d

        init { setLayerType(LAYER_TYPE_HARDWARE, null) }

        override fun onDraw(c: Canvas) {
            val now = SystemClock.uptimeMillis()
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) { postInvalidateOnAnimation(); return }
            for (i in 0 until COUNT) {
                val a = alpha(i, now)
                if (a <= 0.02f) continue
                val (px, py) = point(i, now, w, h, inset)
                paint.color = color(i)
                paint.alpha = (a * 190f).toInt().coerceIn(0, 255)
                c.drawCircle(px, py, radiusDp(i, now) * d, paint)
            }
            postInvalidateOnAnimation()
        }
    }
}
