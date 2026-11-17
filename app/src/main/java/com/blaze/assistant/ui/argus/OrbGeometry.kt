package com.blaze.assistant.ui.argus

/**
 * P0-SH-02: the bot orb's geometry and states as pure numbers (OrbGeometryTest); [BotOrb] draws them. A redraw in
 * Compose of A-team's `botSVG(face, colour)` (app.js v2.16): a glowing glass sphere with an orbit ring, six ring styles,
 * any colour, in a 64-unit box. Nothing here comes from Bot HQ artwork.
 */
/**
 * 2026-11-17 (owner: "change agent icons to these kinds of shapes, they look friendlier"): each face is now a friendly
 * body shape with two eyes ([OrbGeometry.shape]); the names stay so saved bots, A-team exports and the Manager's
 * `face` 0..5 keep working.
 */
enum class OrbFace(val tiltDeg: Float) {
    ORBIT(-16f), TILT(14f), STEEP(-34f), PURE(0f), TWIN(-20f), POLAR(62f);

    companion object {
        fun of(i: Int): OrbFace { val n = entries.size; return entries[((i % n) + n) % n] }
        /** A-team `agentFace`: a bot without a chosen face gets the sum of its name's chars mod 6. */
        fun forName(name: String?): OrbFace = of((name ?: "").sumOf { it.code })
    }
}

enum class OrbState { IDLE, WORKING, WAITING, TALKING, AWAY }

/** The friendly body shapes, one per [OrbFace]. */
enum class BotShape(val label: String) { BLOB("Blob"), SQUIRCLE("Square"), DROP("Drop"), PEBBLE("Pebble"), CLOUD("Cloud"), LEAF("Leaf") }

/** A rounded rectangle in the 64-unit box with its own corner radii (top-left, top-right, bottom-right, bottom-left). */
data class BodyRect(val l: Float, val t: Float, val r: Float, val b: Float, val tl: Float, val tr: Float, val br: Float, val bl: Float)

/** One eye: centre, size, corner radius (units). */
data class BotEye(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float)

/** One ring: radii, stroke width and opacity in the 64-unit box (back half behind the body, front half over it). */
data class OrbRing(val rx: Float, val ry: Float, val width: Float, val alpha: Float)

data class OrbColors(val body: Long, val hi: Long, val lo: Long, val rim: Long)

object OrbGeometry {
    const val BOX = 64f
    const val CENTER = 32f
    const val HALO_R = 31f
    const val BODY_R = 20f
    const val RIM_R = 19.5f
    const val CORE_X = 34f; const val CORE_Y = 33f; const val CORE_R = 9f; const val CORE_ALPHA = 0.85f
    const val SPEC_X = 25f; const val SPEC_Y = 22f; const val SPEC_RX = 7f; const val SPEC_RY = 3.6f; const val SPEC_ROT = -28f; const val SPEC_ALPHA = 0.32f
    /** Body gradient centre (38 %, 32 % of the body's box) and radius (70 %). */
    const val BODY_GX = 0.38f; const val BODY_GY = 0.32f; const val BODY_GR = 0.70f
    const val BACK_RING_WIDTH = 0.8f; const val BACK_RING_ALPHA = 0.55f
    const val RIM_ALPHA = 0.75f

    /** A-team default when a colour doesn't parse. */
    const val DEFAULT_COLOR = 0xFF7C5CFFL

    // ---------------- friendly bodies (2026-11-17) ----------------

    /** The body's bounds in the 64-unit box (every shape stays inside them). */
    const val BODY_L = 11f; const val BODY_T = 10f; const val BODY_R2 = 53f; const val BODY_B = 53f

    fun shape(face: OrbFace): BotShape = when (face) {
        OrbFace.ORBIT -> BotShape.BLOB
        OrbFace.TILT -> BotShape.SQUIRCLE
        OrbFace.STEEP -> BotShape.DROP
        OrbFace.PURE -> BotShape.PEBBLE
        OrbFace.TWIN -> BotShape.CLOUD
        OrbFace.POLAR -> BotShape.LEAF
    }

    /** The rounded-rectangle shapes; null for Drop and Cloud, which are drawn from [DROP_PATH] and [CLOUD_PARTS]. */
    fun bodyRect(shape: BotShape): BodyRect? = when (shape) {
        BotShape.BLOB -> BodyRect(11f, 12f, 53f, 52f, 20f, 20f, 20f, 6f)
        BotShape.SQUIRCLE -> BodyRect(12f, 13f, 52f, 51f, 14f, 14f, 14f, 14f)
        BotShape.PEBBLE -> BodyRect(12f, 12f, 52f, 52f, 20f, 20f, 20f, 20f)
        BotShape.LEAF -> BodyRect(11f, 12f, 53f, 52f, 6f, 20f, 20f, 20f)
        BotShape.DROP, BotShape.CLOUD -> null
    }

    /** Drop: start point, then cubics (c1x, c1y, c2x, c2y, x, y), tip at the top. */
    val DROP_START = floatArrayOf(32f, 10f)
    val DROP_PATH = listOf(
        floatArrayOf(40f, 20f, 52f, 29f, 52f, 38f),
        floatArrayOf(52f, 46.5f, 43f, 53f, 32f, 53f),
        floatArrayOf(21f, 53f, 12f, 46.5f, 12f, 38f),
        floatArrayOf(12f, 29f, 24f, 20f, 32f, 10f))

    /** Cloud: two bumps on top of a rounded base, joined into one outline. Circles are (cx, cy, r). */
    val CLOUD_CIRCLES = listOf(floatArrayOf(24f, 30f, 12f), floatArrayOf(38.5f, 26f, 13.5f))
    val CLOUD_BASE = BodyRect(10f, 28f, 54f, 52f, 11f, 11f, 12f, 12f)

    /** The two eyes, a little right of centre and slanted, like a pair of quote marks. */
    val EYES = listOf(BotEye(28.5f, 32f, 5.2f, 9f, 2.6f), BotEye(38f, 32f, 5.2f, 9f, 2.6f))
    const val EYE_TILT = 10f
    const val EYE_ALPHA = 0.92f
    /** The gloss on the top-left. */
    fun glossAt(shape: BotShape): FloatArray = if (shape == BotShape.DROP) floatArrayOf(26f, 27f) else floatArrayOf(22f, 20f)
    const val GLOSS_RX = 7f; const val GLOSS_RY = 3.4f; const val GLOSS_ROT = -24f; const val GLOSS_ALPHA = 0.28f
    const val SOFT_HALO_ALPHA = 0.22f

    /** Working: the eyes glance left and right, one look every 2.4 s, up to 2 units. */
    const val EYE_LOOK_MS = 2400
    const val EYE_LOOK_MAX = 2f
    fun eyeShift(state: OrbState, timeMs: Long, reducedMotion: Boolean = false): Float =
        if (state == OrbState.WORKING && !reducedMotion) (EYE_LOOK_MAX * Math.sin(2 * Math.PI * (timeMs % EYE_LOOK_MS) / EYE_LOOK_MS)).toFloat() else 0f

    /** The old orbit rings (still used by A-team exports and kept for its tests; the friendly bodies draw none). */
    fun rings(face: OrbFace): List<OrbRing> = when (face) {
        OrbFace.PURE -> emptyList()
        OrbFace.TWIN -> listOf(OrbRing(30f, 7.5f, 1.8f, 0.95f), OrbRing(25f, 5f, 1.1f, 0.7f))
        else -> listOf(OrbRing(30f, 8f, 2f, 0.95f))
    }

    /** A-team mixHex: [c] toward [t] by [k] (0..1), opaque. */
    fun mix(c: Long, t: Long, k: Float): Long {
        fun ch(v: Long, s: Int) = ((v shr s) and 0xFF).toFloat()
        fun m(s: Int) = Math.round(ch(c, s) * (1 - k) + ch(t, s) * k).toLong().coerceIn(0, 255)
        return (0xFFL shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    fun colors(c: Long): OrbColors = OrbColors(body = c or (0xFFL shl 24), hi = mix(c, 0xFFFFFF, 0.7f), lo = mix(c, 0x02030A, 0.82f), rim = mix(c, 0xFFFFFF, 0.45f))

    /** A-team lightColor: perceived brightness > 190 (silver, pale colours get an ink ring in light mode). */
    fun isLight(c: Long): Boolean = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000.0 > 190

    // ---------------- states (04 §2.7) ----------------

    /** Working: the ring turns once per 8 s. */
    const val WORKING_TURN_MS = 8000
    /** Talking: the body breathes with the voice, up to +8 %. */
    const val TALK_MAX_SCALE = 0.08f
    /** Away: 40 % toward grey. */
    const val AWAY_DESAT = 0.4f
    /** Status dot: 8 dp at every size ≥ sm, a quarter of the orb below that; bottom-left. */
    const val DOT_DP = 8f

    fun ringAngle(face: OrbFace, state: OrbState, timeMs: Long, reducedMotion: Boolean = false): Float =
        if (state == OrbState.WORKING && !reducedMotion) face.tiltDeg + 360f * ((timeMs % WORKING_TURN_MS).toFloat() / WORKING_TURN_MS) else face.tiltDeg

    fun bodyScale(state: OrbState, amplitude: Float): Float = if (state == OrbState.TALKING) 1f + TALK_MAX_SCALE * amplitude.coerceIn(0f, 1f) else 1f

    /** The colour the orb draws in: Away is 40 % desaturated. */
    fun stateColor(c: Long, state: OrbState): Long {
        if (state != OrbState.AWAY) return c or (0xFFL shl 24)
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val y = Math.round(0.299 * r + 0.587 * g + 0.114 * b)
        return mix(c, (y shl 16) or (y shl 8) or y, AWAY_DESAT)
    }

    /** The status dot's colour for [state] (null = no dot): green working, amber waiting. */
    fun dotColor(state: OrbState, p: ArgusPalette): Long? = when (state) {
        OrbState.WORKING -> p.success
        OrbState.WAITING -> p.waiting
        else -> null
    }

    fun dotDp(sizeDp: Float): Float = if (sizeDp >= OrbSize.SM) DOT_DP else sizeDp / 4f

    // ---------------- Manager (04 §2.7: only the Manager uses cobalt) ----------------

    /** The Manager's sphere: a ringless (Pure) cobalt orb with the ARGUS "A" inside and a blue glow. */
    fun managerColor(p: ArgusPalette): Long = p.brandCobalt
    const val MANAGER_MARK_FILL = 0.50f
    const val MANAGER_GLOW = 0x733B82F6L   // rgba(59,130,246,.45)
    const val MANAGER_GLOW_DP = 6f
}

/** 04 §2.7 orb sizes (dp). */
object OrbSize { const val XS = 18f; const val SM = 34f; const val MD = 50f; const val LG = 72f; const val XL = 84f; const val HERO = 120f
    val ALL = listOf(XS, SM, MD, LG, XL, HERO) }
