package com.blaze.assistant.ui.argus

import android.opengl.GLSurfaceView
import android.view.Choreographer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.blaze.assistant.ui.drawArgusMark
import kotlinx.coroutines.isActive
import kotlin.math.sin

/**
 * 0.52.2 the Home's centrepiece: the OpenGL ES 3 scene ([ArgusRenderer]) when the phone can, else the Canvas orb
 * ([ArgusOrbCanvas]): same orb, rings, streaks and pedestal, drawn flat. [mode] comes from [ArgusFps.mode];
 * [animate] is false whenever the Home isn't seen (another screen over it, Spotlight fully open, app in the background),
 * and then nothing is drawn at all. A GL failure at any point switches this view to the Canvas orb for the session.
 */
@Composable
fun ArgusSceneView(state: ArgusScene.State, level: Float, mode: ArgusFps.Mode, fps: Int, visible: Boolean,
                   orbAt: Float, parallax: Pair<Float, Float> = 0f to 0f, modifier: Modifier = Modifier.fillMaxSize()) {
    var glFailed by remember { mutableStateOf(false) }
    val desc = Modifier.semantics { contentDescription = "ARGUS orb: " + state.name.lowercase() }
    if (mode == ArgusFps.Mode.GL && !glFailed) {
        ArgusGlView(state, level, fps, visible, orbAt, parallax, onFail = { msg ->
            glFailed = true
            try { com.blaze.assistant.device.CrashLog.quiet("ArgusGl", IllegalStateException(msg)) } catch (_: Throwable) { }
        }, modifier = modifier.then(desc))
    } else {
        ArgusOrbCanvas(state, level, animate = mode != ArgusFps.Mode.STATIC && visible, fps = fps.coerceAtMost(30), orbAt = orbAt, modifier = modifier.then(desc))
    }
}

@Composable
private fun ArgusGlView(state: ArgusScene.State, level: Float, fps: Int, visible: Boolean, orbAt: Float,
                        parallax: Pair<Float, Float>, onFail: (String) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val fail by rememberUpdatedState(onFail)
    val renderer = remember { ArgusRenderer(ctx) { msg -> fail(msg) } }
    val holder = remember { arrayOfNulls<GLSurfaceView>(1) }
    SideEffect {
        renderer.target = ArgusScene.look(state, level)
        renderer.orbAt = orbAt
        renderer.parallaxX = parallax.first; renderer.parallaxY = parallax.second
    }
    AndroidView(factory = { c ->
        GLSurfaceView(c).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(ArgusConfigChooser(msaa = fps >= 60))
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }.also { holder[0] = it }
    }, modifier = modifier)

    // Pause with the activity (GLSurfaceView needs onPause / onResume) and draw only while resumed and seen.
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { resumed = true; holder[0]?.onResume() }
                Lifecycle.Event.ON_PAUSE -> { resumed = false; holder[0]?.onPause() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val animate = ArgusFps.animate(ArgusFps.Mode.GL, resumed, visible)
    // The frame clock: vsync callbacks on the main thread, a frame asked for only when one is due at the capped rate.
    DisposableEffect(animate, fps) {
        val ch = Choreographer.getInstance()
        var running = animate
        var last = 0L
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                val ms = frameTimeNanos / 1_000_000L
                if (ArgusFps.due(ms, last, fps)) { last = ms; holder[0]?.requestRender() }
                ch.postFrameCallback(this)
            }
        }
        if (running) ch.postFrameCallback(cb) else holder[0]?.requestRender()
        onDispose { running = false; ch.removeFrameCallback(cb) }
    }
}

/**
 * The Canvas orb: the fallback when GLES 3 is missing, on very low-RAM phones, after a GL failure, and (still) with
 * reduce motion. Same composition as the GL scene, drawn with plain Canvas calls; [animate] false = one still frame.
 */
@Composable
fun ArgusOrbCanvas(state: ArgusScene.State, level: Float, animate: Boolean, fps: Int, orbAt: Float, modifier: Modifier = Modifier.fillMaxSize()) {
    val target by rememberUpdatedState(ArgusScene.look(state, level))
    val clock = remember { mutableFloatStateOf(0f) }
    val phase = remember { mutableFloatStateOf(0.6f) }
    val look = remember { mutableStateOf(ArgusScene.look(state, level)) }
    LaunchedEffect(animate, fps) {
        if (!animate) { look.value = target; return@LaunchedEffect }
        var last = 0L
        var prev = 0L
        while (isActive) {
            withFrameMillis { ms ->
                if (ArgusFps.due(ms, last, fps)) {
                    val dt = if (prev == 0L) 0f else ((ms - prev) / 1000f).coerceIn(0f, 0.1f)
                    prev = ms; last = ms
                    val l = ArgusScene.approach(look.value, target, dt)
                    look.value = l
                    phase.floatValue += l.ringSpeed * dt
                    clock.floatValue += dt
                }
            }
        }
    }
    Canvas(modifier) { drawArgusScene(look.value, clock.floatValue, phase.floatValue, orbAt) }
}

/** The scene in plain Canvas calls (also used by screenshot tests, where there is no GL). */
fun DrawScope.drawArgusScene(look: ArgusScene.Look, t: Float, phase: Float, orbAt: Float) {
    val w = size.width; val h = size.height
    val cx = w / 2f; val cy = h * orbAt
    val r = (w * 0.205f).coerceAtMost(h * 0.16f) * ArgusScene.breath(t, look)
    val blue = Color(0xFF1286FF); val argus = Color(0xFF0056FF)
    // ground: obsidian with the deep-blue atmosphere around the orb
    drawRect(Color.Black)
    drawRect(Brush.radialGradient(listOf(Color(0xFF00133F), Color(0xFF000814), Color.Black), center = Offset(cx, cy), radius = w * 0.95f))
    // pedestal: concentric glowing ellipses and a ripple running outward
    val py = cy + r * 2.25f
    val pr = r * 2.3f
    for ((k, a) in listOf(0.36f to 0.9f, 0.52f to 0.7f, 0.70f to 0.5f, 0.86f to 0.3f)) {
        val rr = pr * k
        drawOval(blue.copy(alpha = 0.18f * a * look.glow), Offset(cx - rr, py - rr * 0.26f), Size(rr * 2, rr * 0.52f), style = Stroke(r * 0.09f))
        drawOval(blue.copy(alpha = a * (0.45f + 0.5f * look.glow)), Offset(cx - rr, py - rr * 0.26f), Size(rr * 2, rr * 0.52f), style = Stroke(r * 0.018f))
    }
    val p = (t * look.ripple * 0.25f) % 1f
    val rp = pr * (0.2f + 0.8f * p)
    drawOval(blue.copy(alpha = (1f - p) * 0.5f * look.glow), Offset(cx - rp, py - rp * 0.26f), Size(rp * 2, rp * 0.52f), style = Stroke(r * 0.02f))
    // beam and halo
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, argus.copy(alpha = 0.22f * look.glow)), startY = cy, endY = py),
        topLeft = Offset(cx - r * 0.55f, cy), size = Size(r * 1.1f, py - cy))
    drawCircle(Brush.radialGradient(listOf(argus.copy(alpha = 0.42f * look.glow), Color.Transparent), center = Offset(cx, cy), radius = r * 2.3f), r * 2.3f, Offset(cx, cy))
    // rings: the far half behind the orb, the near half in front
    fun ring(i: Int, front: Boolean) {
        val g = ArgusScene.RINGS[i]
        val rw = r * g.radius; val rh = rw * ArgusScene.squash(g.tiltX)
        rotate(g.tiltZ, Offset(cx, cy)) {
            val tl = Offset(cx - rw, cy - rh); val sz = Size(rw * 2, rh * 2)
            val start = if (front) 0f else 180f
            drawArc(Color(0xFF8E98A3).copy(alpha = 0.55f), start, 180f, false, tl, sz, style = Stroke(r * g.width * 1.4f))
            drawArc(Color(0xFFEDF0F3).copy(alpha = 0.35f), start, 180f, false, tl, sz, style = Stroke(r * g.width * 0.35f))
            val a = ((Math.toDegrees(ArgusScene.ringAngle(g, phase).toDouble()).toFloat() % 360f) + 360f) % 360f
            val inHalf = if (front) a < 180f else a >= 180f
            if (inHalf) drawArc(blue.copy(alpha = 0.95f * look.streak), a, 26f, false, tl, sz, style = Stroke(r * g.width * 1.1f))
        }
    }
    for (i in ArgusScene.RINGS.indices) ring(i, front = false)
    // the orb: glossy black, blue rim, two highlights
    drawCircle(Brush.radialGradient(listOf(Color(0xFF1A2340), Color(0xFF05070D), Color.Black), center = Offset(cx - r * 0.35f, cy - r * 0.4f), radius = r * 1.5f), r, Offset(cx, cy))
    drawCircle(Brush.radialGradient(0.72f to Color.Transparent, 1f to blue.copy(alpha = 0.35f + 0.45f * look.glow), center = Offset(cx, cy), radius = r), r, Offset(cx, cy))
    drawOval(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), center = Offset(cx - r * 0.38f, cy - r * 0.48f), radius = r * 0.32f),
        Offset(cx - r * 0.7f, cy - r * 0.8f), Size(r * 0.64f, r * 0.64f))
    drawArgusMark(Offset(cx, cy + r * 0.02f), r * 0.95f, t, alpha = 0.92f)
    for (i in ArgusScene.RINGS.indices) ring(i, front = true)
    // a faint shimmer on the floor line
    drawLine(blue.copy(alpha = 0.10f + 0.05f * sin(t * 0.8f)), Offset(0f, py + r * 0.8f), Offset(w, py + r * 0.8f), strokeWidth = 1f)
}
