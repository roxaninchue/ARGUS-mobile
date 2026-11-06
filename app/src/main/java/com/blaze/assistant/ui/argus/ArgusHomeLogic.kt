package com.blaze.assistant.ui.argus

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.52.2 ARGUS living 3D Home, pure part (ArgusHomeLogicTest). No Android or Compose here: the scene's state machine and
 * the look each state draws with, the greeting, the frame-rate / renderer policy, the pull-down notch's drag and spring
 * maths, and the gyroscope parallax. The GL renderer (ArgusGl.kt), the Canvas fallback (ArgusOrbCanvas.kt) and the screen
 * (ArgusHome.kt) only read these numbers.
 */
object ArgusScene {
    /** What the orb shows. Listening and speaking (voice) win over thinking (the agent working). */
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    fun stateOf(listening: Boolean, speaking: Boolean, working: Boolean): State = when {
        listening -> State.LISTENING
        speaking -> State.SPEAKING
        working -> State.THINKING
        else -> State.IDLE
    }

    /**
     * How the scene moves in a state. [ringSpeed]: the rings' turn rate (radians per second, the slowest ring);
     * [breathHz] / [breathAmp]: the orb's breathing (scale 1 ± amp); [glow]: rim light and pedestal glow 0..1;
     * [streak]: brightness of the blue light streaks on the rings 0..1; [ripple]: the pedestal ripples' speed (rings per second).
     */
    data class Look(val ringSpeed: Float, val breathHz: Float, val breathAmp: Float, val glow: Float, val streak: Float, val ripple: Float)

    /** Rings at rest turn this slowly (≈ one turn in 50 s): calm, never distracting. */
    const val IDLE_RING_SPEED = 0.125f

    /** The look for [s]; [level] (0..1) is the mic level while listening (0 when unknown), the rings speed up and pulse with it. */
    fun look(s: State, level: Float = 0f): Look {
        val l = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        return when (s) {
            State.IDLE -> Look(IDLE_RING_SPEED, 0.22f, 0.018f, 0.55f, 0.55f, 0.30f)
            State.LISTENING -> Look(0.42f + 0.38f * l, 0.9f, 0.025f + 0.05f * l, 0.80f + 0.20f * l, 0.9f + 0.1f * l, 0.9f + 0.6f * l)
            State.THINKING -> Look(0.62f, 0.5f, 0.022f, 0.72f, 1f, 0.6f)
            State.SPEAKING -> Look(0.30f, 1.6f, 0.032f, 0.88f, 0.85f, 0.7f)
        }
    }

    /** Frame-rate independent approach of [cur] toward [target] with time constant [tau] seconds (state changes ease, never jump). */
    fun approach(cur: Float, target: Float, dtSec: Float, tau: Float = 0.35f): Float {
        if (dtSec <= 0f || tau <= 0f) return if (tau <= 0f) target else cur
        val k = 1f - exp(-dtSec / tau)
        return cur + (target - cur) * k
    }

    fun approach(cur: Look, target: Look, dtSec: Float, tau: Float = 0.35f): Look = Look(
        approach(cur.ringSpeed, target.ringSpeed, dtSec, tau), approach(cur.breathHz, target.breathHz, dtSec, tau),
        approach(cur.breathAmp, target.breathAmp, dtSec, tau), approach(cur.glow, target.glow, dtSec, tau),
        approach(cur.streak, target.streak, dtSec, tau), approach(cur.ripple, target.ripple, dtSec, tau))

    /** The orb's scale at [tSec] (breathing). */
    fun breath(tSec: Float, look: Look): Float = 1f + look.breathAmp * sin(2f * PI.toFloat() * look.breathHz * tSec)

    /**
     * The three orbit rings: tilt about X and Z (degrees) and each ring's speed as a multiple of the look's [Look.ringSpeed]
     * (opposite signs turn the other way). Shared by the GL scene and the Canvas fallback, so both look alike.
     */
    data class Ring(val tiltX: Float, val tiltZ: Float, val speed: Float, val radius: Float, val width: Float)

    val RINGS = listOf(
        // the wide, nearly flat orbit (Saturn-like) with the long light streak
        Ring(tiltX = 12f, tiltZ = -12f, speed = 1.0f, radius = 1.75f, width = 0.05f),
        // two metal hoops wrapping the orb, turning opposite ways
        Ring(tiltX = 70f, tiltZ = 35f, speed = -1.35f, radius = 1.28f, width = 0.10f),
        Ring(tiltX = 64f, tiltZ = -40f, speed = 0.8f, radius = 1.20f, width = 0.08f),
    )

    /** The widest ring's reach (world units), so the camera can keep it on a portrait screen. */
    val REACH: Float get() = RINGS.maxOf { it.radius } + 0.2f

    /** A ring's angle (radians) given the accumulated phase [phase] the renderer integrates from the look's speed. */
    fun ringAngle(ring: Ring, phase: Float): Float = phase * ring.speed

    /** On screen a ring tilted [tiltX] degrees from flat is an ellipse this much flatter than a circle (Canvas fallback). */
    fun squash(tiltX: Float): Float = kotlin.math.abs(sin(Math.toRadians(tiltX.toDouble()).toFloat())).coerceIn(0.08f, 1f)
}

/** The greeting bubble beside the orb (time-aware) and the prompt bar's rotating hint. */
object ArgusGreeting {
    fun hello(hour: Int): String = when (((hour % 24) + 24) % 24) {
        in 5..11 -> "Good morning."
        in 12..16 -> "Good afternoon."
        in 17..23 -> "Good evening."
        else -> "Still up?"
    }

    /** "Good evening, Gaby." when a first name is known (only the first word of [name], never an e-mail). */
    fun hello(hour: Int, name: String?): String {
        val first = name.orEmpty().trim().substringBefore(' ').takeIf { it.isNotBlank() && '@' !in it && it.length <= 24 }
        val h = hello(hour)
        return if (first == null) h else h.removeSuffix(".").removeSuffix("?") + ", " + first + (if (h.endsWith("?")) "?" else ".")
    }

    fun ask(state: ArgusScene.State): String = when (state) {
        ArgusScene.State.IDLE -> "What would you like to do?"
        ArgusScene.State.LISTENING -> "I'm listening\u2026"
        ArgusScene.State.THINKING -> "Working on it\u2026"
        ArgusScene.State.SPEAKING -> "Here's what I found."
    }

    /** What the prompt bar suggests, in turn (the reference's "Try saying …"). */
    val TRY = listOf("Show my schedule", "Turn on Wi-Fi", "Show me the latest tech news", "Open YouTube", "What's the weather today?", "Remind me at 6 to call home")

    fun hint(index: Int): String = "Try saying \u201c" + TRY[((index % TRY.size) + TRY.size) % TRY.size] + "\u201d\u2026"

    /** The hint changes every [HINT_MS] while the bar is empty and motion is on. */
    const val HINT_MS = 4_500L
}

/** Which renderer the Home uses and how often it draws (ArgusHomeLogicTest). */
object ArgusFps {
    enum class Mode {
        /** OpenGL ES 3 scene (GLSurfaceView), the default. */
        GL,
        /** Compose Canvas orb, animated (no GLES3, or a very low-RAM phone). */
        CANVAS,
        /** Compose Canvas orb, drawn once (reduce motion / "Remove animations"). */
        STATIC,
    }

    /** Phones at or under this much RAM draw at 30 fps (the 3-4 GB phones the project cares about, Galaxy A07 included). */
    const val LOW_RAM_MB = 4_608L
    /** Phones under this much RAM (or Android's low-RAM flag, Go devices) get the Canvas orb instead of GL. */
    const val TINY_RAM_MB = 2_560L

    fun lowRam(totalRamMb: Long, lowRamFlag: Boolean): Boolean = lowRamFlag || (totalRamMb in 1..LOW_RAM_MB)
    fun tinyRam(totalRamMb: Long, lowRamFlag: Boolean): Boolean = lowRamFlag || (totalRamMb in 1 until TINY_RAM_MB)

    /** 60 fps, 30 on low-RAM phones or in battery saver. */
    fun cap(totalRamMb: Long, lowRamFlag: Boolean, powerSave: Boolean): Int = if (powerSave || lowRam(totalRamMb, lowRamFlag)) 30 else 60

    fun mode(gles3: Boolean, totalRamMb: Long, lowRamFlag: Boolean, motionOn: Boolean): Mode = when {
        !motionOn -> Mode.STATIC
        !gles3 || tinyRam(totalRamMb, lowRamFlag) -> Mode.CANVAS
        else -> Mode.GL
    }

    /** OpenGL ES version from ConfigurationInfo.reqGlEsVersion (0x00030000 = 3.0). */
    fun gles3(reqGlEsVersion: Int): Boolean = (reqGlEsVersion ushr 16) >= 3

    fun frameMs(fps: Int): Long = 1000L / fps.coerceIn(1, 120)

    /** The draw loop's gate: draw when a frame's worth of time has passed (2 ms slack for vsync jitter). */
    fun due(nowMs: Long, lastMs: Long, fps: Int): Boolean = lastMs <= 0L || nowMs - lastMs >= frameMs(fps) - 2

    /** Whether the scene animates at all: it pauses whenever it isn't seen (app in the background, Home covered). */
    fun animate(mode: Mode, resumed: Boolean, visible: Boolean): Boolean = mode != Mode.STATIC && resumed && visible
}

/**
 * The floating notch at the top: drag it down for Spotlight (a tap opens it too). Progress 0 = closed, 1 = open; past 1 a
 * rubber band. Release settles open or closed by position and fling, then a critically-damped-ish spring takes it there.
 */
object ArgusPull {
    /** Past this share of the sheet's height a release opens it. */
    const val OPEN_AT = 0.33f
    /** A fling faster than this (dp per second) decides on its own, whatever the position. */
    const val FLING_DP_S = 700f
    /** How far the rubber band lets it overshoot. */
    const val OVERSHOOT = 0.12f

    fun progress(dragPx: Float, sheetPx: Float): Float {
        if (sheetPx <= 0f) return 0f
        val x = dragPx / sheetPx
        return when {
            x <= 0f -> 0f
            x <= 1f -> x
            else -> 1f + OVERSHOOT * (1f - 1f / (1f + (x - 1f) * 3f))
        }
    }

    /** True = settle open. Positive velocity = pulling down (opening). */
    fun settleOpen(progress: Float, velocityDpS: Float, wasOpen: Boolean): Boolean = when {
        velocityDpS > FLING_DP_S -> true
        velocityDpS < -FLING_DP_S -> false
        wasOpen -> progress >= 1f - OPEN_AT
        else -> progress >= OPEN_AT
    }

    /** Spring constants (the app's finger-following spring: stiffness 380, damping ratio 0.86). */
    data class Spring(val stiffness: Float = 380f, val dampingRatio: Float = 0.86f)

    /** One semi-implicit Euler step of a damped spring toward [target]: returns the new position and velocity. */
    fun step(x: Float, v: Float, target: Float, dtSec: Float, s: Spring = Spring()): Pair<Float, Float> {
        val dt = dtSec.coerceIn(0f, 0.05f)
        val omega = sqrt(s.stiffness)
        val a = -s.stiffness * (x - target) - 2f * s.dampingRatio * omega * v
        val nv = v + a * dt
        return (x + nv * dt) to nv
    }

    fun settled(x: Float, v: Float, target: Float): Boolean = abs(x - target) < 0.002f && abs(v) < 0.02f

    /** Simulates to rest (for tests and to size an animation): steps until settled or [maxSteps]. */
    fun simulate(from: Float, target: Float, dtSec: Float = 1f / 60f, maxSteps: Int = 600, s: Spring = Spring()): List<Float> {
        val out = ArrayList<Float>(); var x = from; var v = 0f
        for (i in 0 until maxSteps) { val r = step(x, v, target, dtSec, s); x = r.first; v = r.second; out += x; if (settled(x, v, target)) break }
        return out
    }

    /** The scrim behind the sheet follows the progress. */
    fun scrim(progress: Float): Float = (progress.coerceIn(0f, 1f) * 0.55f)
}

/** Gyroscope / tilt parallax: optional, off with reduce motion or battery saver, small and smoothed. */
object ArgusParallax {
    /** The scene moves at most this many dp each way. */
    const val MAX_DP = 10f

    fun enabled(setting: Boolean, motionOn: Boolean, powerSave: Boolean): Boolean = setting && motionOn && !powerSave

    /** Tilt (radians about the phone's x and y axes, from the rotation vector) to an offset in -1..1 each way. */
    fun offset(pitch: Float, roll: Float): Pair<Float, Float> {
        fun n(v: Float) = if (v.isNaN()) 0f else (v / 0.45f).coerceIn(-1f, 1f)
        return n(roll) to n(pitch)
    }

    /** Low-pass filter for the sensor (k = share of the new reading). */
    fun smooth(prev: Float, next: Float, k: Float = 0.12f): Float = prev + (next - prev) * k.coerceIn(0f, 1f)
}

/**
 * The result card that floats above the pedestal after an ask from the Home bar (the reference's "Here are the top
 * headlines for you…"): the ask, then ARGUS's newest reply to it, cut to a few plain lines. The full answer, with its
 * rich cards, is one tap away in the chat. Pure: the screen feeds it the chat's messages.
 */
object ArgusAnswer {
    data class Msg(val id: Long, val fromArgus: Boolean, val text: String)
    data class Card(val ask: String, val text: String, val working: Boolean)

    const val MAX_CHARS = 280

    /**
     * The card for the newest ask made after message [askFrom] (the newest id when the Home came up, or when the last card
     * was dismissed) and ARGUS's newest reply to it; null when there is no ask, or nothing came back and nothing is running.
     */
    fun card(askFrom: Long, msgs: List<Msg>, busy: Boolean): Card? {
        if (askFrom < 0L) return null
        val after = msgs.filter { it.id > askFrom }
        val ask = after.lastOrNull { !it.fromArgus } ?: return null
        val reply = after.lastOrNull { it.fromArgus && it.id > ask.id && it.text.isNotBlank() }
        if (reply == null && !busy) return null
        return Card(plain(ask.text, 90), reply?.let { plain(it.text, MAX_CHARS) }.orEmpty(), busy)
    }

    /** Markdown and code fences out, whitespace folded, cut at a word with an ellipsis. */
    fun plain(text: String, max: Int = MAX_CHARS): String {
        var t = text.replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+]|\\d+\\.)\\s+"), "")
            .replace(Regex("[*_`~|]"), "")
            .replace(Regex("\\s+"), " ").trim()
        if (t.length > max) {
            val cut = t.substring(0, max)
            val sp = cut.lastIndexOf(' ')
            t = (if (sp > max / 2) cut.substring(0, sp) else cut).trimEnd(',', '.', ';', ':', ' ') + "\u2026"
        }
        return t
    }
}
