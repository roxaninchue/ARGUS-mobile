package com.blaze.assistant.ui.argus

/**
 * P0-SH-01: the ARGUS design tokens of docs/overhaul/04-design-ia.md §2 (A-team v2 + liquid glass, the effective values
 * of its app.css cascade), as plain numbers so every rule is a JVM test (ArgusTokensTest). Colours are ARGB longs
 * (0xAARRGGBB); [ArgusTheme] turns them into Compose colours. Nothing reads these unless the `argus_look` setting is on
 * (default off in P0).
 *
 * AA light/dark variants (04 §5.2): every text token reaches 4.5:1 and every UI token (dots, rims, checks) 3:1 on every
 * ground ([Ground]) and glass level ([GlassLevel]), checked by ArgusTokensTest. Where the A-team value fails, the token
 * carries the nearest passing value and the original stays as a fill:
 *  - dark `mute` #9B9BA3 → #A6A6AE (4.30:1 on raised glass); dark `danger` text #FF5A5F → #FF8085 (fill stays);
 *    dark Manager `cobaltText` #6A8FFF → #86A3FF;
 *  - light `danger` text #C4282E → #C0272D, `success` text #0E7A4E → #0D744A, `waiting` text #8A5D00 → #895C00;
 *  - `dim` (#6B6B73 / #A2A2AA) stays decorative only (placeholders, disabled, glyph hairlines); timestamps and other
 *    meta text use [ArgusPalette.meta] (#A6A6AB / #646469);
 *  - light `success` fill (live dots, done ticks) #16A36A → #128A59 (2.5:1 on the page; dots need 3:1).
 *  - the Orbit ground's veil is 82 % (dark) / 95 % (light) instead of 04's 60 %: the Earth's limb in argus_space.webp
 *    reaches sRGB grey 0.67, and flat rows on it need 4.5:1.
 */
data class ArgusPalette(
    val dark: Boolean,
    val bg: Long, val ink: Long, val onInk: Long, val mute: Long, val meta: Long, val dim: Long,
    val soft: Long, val soft2: Long, val line: Long, val line2: Long,
    val glassFill: Long, val glassFill2: Long, val glassRim: Long, val glassRim2: Long,
    val chrome: Long, val sheet: Long, val scrim: Long,
    val danger: Long, val dangerText: Long, val success: Long, val successText: Long,
    val unread: Long, val waiting: Long, val waitingText: Long,
    val manager: Long, val brandCobalt: Long, val cobaltText: Long,
    /** The three soft radial blobs of the Plain ground: (x, y as fractions of the screen, colour). */
    val blobs: List<Blob>,
    /** Top sheen on glass, as (stop 0..1, colour) pairs, top to bottom. */
    val specular: List<Pair<Float, Long>>,
)

data class Blob(val x: Float, val y: Float, val color: Long, val radius: Float = 0.75f)

/** What sits under the glass (04 §2.1, Appearance). */
enum class Ground { PLAIN, ORBIT, COBALT_NIGHT;
    companion object {
        /** The stored setting value; anything unknown is Plain. */
        fun of(s: String?): Ground = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: PLAIN
    }
}

/** 04 §2.5: Flat (rows on the ground), Surface (cards), Raised (glass2: buttons, icon buttons), Chrome (frosted bars), Sheet. */
enum class GlassLevel { FLAT, SURFACE, RAISED, CHROME, SHEET }

object ArgusTokens {
    private fun rgb(v: Long): Long = 0xFF000000L or v
    /** [rgb] at [alpha] (0..1). */
    fun a(v: Long, alpha: Float): Long = (Math.round(alpha * 255).toLong().coerceIn(0, 255) shl 24) or (v and 0xFFFFFFL)

    private const val WHITE = 0xFFFFFFL
    private const val BLACK = 0x000000L

    val Dark = ArgusPalette(
        dark = true,
        bg = rgb(0x0B0B0D), ink = rgb(0xF2F2F4), onInk = rgb(0x0B0B0D), mute = rgb(0xA6A6AE), meta = rgb(0xA6A6AB), dim = rgb(0x6B6B73),
        soft = a(WHITE, 0.07f), soft2 = rgb(0x24242A), line = a(WHITE, 0.09f), line2 = a(WHITE, 0.22f),
        glassFill = a(WHITE, 0.055f), glassFill2 = a(WHITE, 0.09f), glassRim = a(WHITE, 0.14f), glassRim2 = a(WHITE, 0.26f),
        chrome = a(0x0E0E12, 0.58f), sheet = a(0x16161B, 0.93f), scrim = a(BLACK, 0.35f),
        danger = rgb(0xFF5A5F), dangerText = rgb(0xFF8085), success = rgb(0x3ECF8E), successText = rgb(0x3ECF8E),
        unread = rgb(0x3B82F6), waiting = rgb(0xE0A020), waitingText = rgb(0xE0A020),
        manager = rgb(0xD4A72C), brandCobalt = rgb(0x3466FF), cobaltText = rgb(0x86A3FF),
        blobs = listOf(Blob(0.15f, 0.08f, a(WHITE, 0.10f)), Blob(0.92f, 0.30f, a(WHITE, 0.06f)), Blob(0.40f, 1.00f, a(WHITE, 0.07f))),
        specular = listOf(0f to a(WHITE, 0.14f), 0.42f to a(WHITE, 0.02f), 0.60f to a(WHITE, 0f), 1f to a(WHITE, 0.04f)),
    )

    val Light = ArgusPalette(
        dark = false,
        bg = rgb(0xEEF0F4), ink = rgb(0x111114), onInk = rgb(0xFFFFFF), mute = rgb(0x64646C), meta = rgb(0x646469), dim = rgb(0xA2A2AA),
        soft = a(BLACK, 0.045f), soft2 = rgb(0xE8E8EC), line = a(BLACK, 0.08f), line2 = a(BLACK, 0.22f),
        glassFill = a(WHITE, 0.62f), glassFill2 = a(WHITE, 0.78f), glassRim = a(WHITE, 0.90f), glassRim2 = a(BLACK, 0.12f),
        chrome = a(WHITE, 0.60f), sheet = a(0xFAFAFC, 0.94f), scrim = a(BLACK, 0.35f),
        danger = rgb(0xE5484D), dangerText = rgb(0xC0272D), success = rgb(0x128A59), successText = rgb(0x0D744A),
        unread = rgb(0x2F7BFF), waiting = rgb(0xE0A020), waitingText = rgb(0x895C00),
        manager = rgb(0xD4A72C), brandCobalt = rgb(0x0D28FF), cobaltText = rgb(0x0D28FF),
        blobs = listOf(Blob(0.15f, 0.08f, rgb(0xFFFFFF)), Blob(0.92f, 0.30f, rgb(0xDFE3EC)), Blob(0.40f, 1.00f, rgb(0xE6E9F0))),
        specular = listOf(0f to a(WHITE, 0.95f), 0.45f to a(WHITE, 0.40f), 1f to a(WHITE, 0.20f)),
    )

    fun palette(dark: Boolean) = if (dark) Dark else Light

    /** The fill a [GlassLevel] puts over the ground (Flat = the ground itself). */
    fun fill(p: ArgusPalette, level: GlassLevel): Long? = when (level) {
        GlassLevel.FLAT -> null
        GlassLevel.SURFACE -> p.glassFill
        GlassLevel.RAISED -> p.glassFill2
        GlassLevel.CHROME -> p.chrome
        GlassLevel.SHEET -> p.sheet
    }

    // ---------------- grounds ----------------

    /** Cobalt night (AM BRAND): top → bottom. Light mode keeps the cobalt cast on a pale ground. */
    fun cobaltNight(dark: Boolean): List<Long> = if (dark) listOf(rgb(0x06103D), rgb(0x0A0A0A)) else listOf(rgb(0xE9EDFF), rgb(0xF4F5F8))

    /** Orbit: argus_space.webp under a veil of the page colour (black in dark) at [orbitVeil]. */
    fun orbitVeil(dark: Boolean): Float = if (dark) 0.82f else 0.95f
    fun orbitVeilColor(p: ArgusPalette): Long = if (p.dark) rgb(BLACK) else p.bg
    /** The darkest and brightest sRGB grey of argus_space.webp (measured, 1080×2400: max 0.675). */
    const val ORBIT_LO = 0f
    const val ORBIT_HI = 0.70f

    /**
     * Every opaque colour the ground can show under content, for contrast checks: the plain bg and each blob's peak;
     * both ends of the Cobalt gradient; the Orbit image's darkest and brightest pixels under the veil.
     */
    fun groundSamples(p: ArgusPalette, g: Ground): List<Long> = when (g) {
        Ground.PLAIN -> listOf(p.bg) + p.blobs.map { ArgusContrast.over(it.color, p.bg) }
        Ground.COBALT_NIGHT -> cobaltNight(p.dark)
        Ground.ORBIT -> listOf(ORBIT_LO, ORBIT_HI).map { ArgusContrast.over(ArgusTokens.a(orbitVeilColor(p), orbitVeil(p.dark)), ArgusContrast.grey(it)) }
    }

    /** What text actually sits on at [level] over [g]. */
    fun surfaces(p: ArgusPalette, g: Ground, level: GlassLevel): List<Long> =
        groundSamples(p, g).map { s -> fill(p, level)?.let { ArgusContrast.over(it, s) } ?: s }

    /** Text tokens (4.5:1) and UI tokens (3:1), by name, for the contrast matrix. */
    fun textTokens(p: ArgusPalette): Map<String, Long> = linkedMapOf("ink" to p.ink, "mute" to p.mute, "meta" to p.meta,
        "dangerText" to p.dangerText, "successText" to p.successText, "waitingText" to p.waitingText, "cobaltText" to p.cobaltText)
    fun uiTokens(p: ArgusPalette): Map<String, Long> = linkedMapOf("unread" to p.unread, "success" to p.success, "danger" to p.danger)

    // ---------------- bot + domain colours ----------------

    /** 04 §2.1 bot identity palette (A-team COLORS). */
    val BOT_COLORS: Map<String, Long> = linkedMapOf("violet" to rgb(0x8B5CF6), "green" to rgb(0x22C55E), "amber" to rgb(0xF59E0B),
        "red" to rgb(0xEF4444), "cyan" to rgb(0x06B6D4), "indigo" to rgb(0x6366F1), "blue" to rgb(0x3B82F6), "silver" to rgb(0xCBD5E1))

    /** Bot HQ's 11 named colours on import. */
    val BH_COLOR_MAP: Map<String, Long> = linkedMapOf("black" to rgb(0xCBD5E1), "brown" to rgb(0xB7791F), "red" to rgb(0xEF4444),
        "orange" to rgb(0xF59E0B), "yellow" to rgb(0xF59E0B), "green" to rgb(0x22C55E), "cyan" to rgb(0x06B6D4), "blue" to rgb(0x3B82F6),
        "violet" to rgb(0x8B5CF6), "magenta" to rgb(0xF93498), "gray" to rgb(0xCBD5E1))

    /** Life-domain hues (orb default + 3 dp row tick only, never big fills). */
    val DOMAIN_COLORS: Map<String, Long> = linkedMapOf("health" to rgb(0x22C55E), "money" to rgb(0xF59E0B), "work" to rgb(0x6366F1),
        "home" to rgb(0x06B6D4), "people" to rgb(0xEC4899), "learning" to rgb(0x8B5CF6), "travel" to rgb(0x3B82F6), "create" to rgb(0xEF4444),
        "mind" to rgb(0xCBD5E1), "phone" to rgb(0x9B9BA3))

    /** "#8B5CF6", "8b5cf6", a palette name or a Bot HQ name → ARGB; null when it's none of these. */
    fun botColor(s: String?): Long? {
        val t = s?.trim()?.lowercase() ?: return null
        BOT_COLORS[t]?.let { return it }
        BH_COLOR_MAP[t]?.let { return it }
        val hex = t.removePrefix("#")
        return if (hex.length == 6 && hex.all { it in '0'..'9' || it in 'a'..'f' }) rgb(hex.toLong(16)) else null
    }

    // ---------------- Material3 mapping ----------------

    /**
     * The Material3 colour roles, from the same tokens, so stock components inherit the look. Primary is ink (A-team's
     * `.btn.p`), not a hue: colour stays meaningful (bots, status).
     */
    fun m3(p: ArgusPalette): Map<String, Long> {
        val raised = ArgusContrast.over(p.glassFill2, p.bg)
        val surface = ArgusContrast.over(p.glassFill, p.bg)
        return linkedMapOf(
            "primary" to p.ink, "onPrimary" to p.onInk,
            "primaryContainer" to raised, "onPrimaryContainer" to p.ink,
            "secondary" to p.mute, "onSecondary" to p.bg,
            "secondaryContainer" to raised, "onSecondaryContainer" to p.ink,
            "tertiary" to p.cobaltText, "onTertiary" to p.bg,
            "background" to p.bg, "onBackground" to p.ink,
            "surface" to p.bg, "onSurface" to p.ink,
            "surfaceVariant" to surface, "onSurfaceVariant" to p.mute,
            "surfaceContainer" to surface, "surfaceContainerHigh" to raised,
            "outline" to ArgusContrast.over(p.line2, p.bg), "outlineVariant" to ArgusContrast.over(p.line, p.bg),
            "error" to p.dangerText, "onError" to p.bg,
            "inverseSurface" to p.ink, "inverseOnSurface" to p.bg,
            "scrim" to p.scrim,
        )
    }

    /** Role pairs that carry text, for the M3 contrast check. */
    val M3_TEXT_PAIRS = listOf("onPrimary" to "primary", "onPrimaryContainer" to "primaryContainer", "onSecondaryContainer" to "secondaryContainer",
        "onBackground" to "background", "onSurface" to "surface", "onSurfaceVariant" to "surfaceVariant", "inverseOnSurface" to "inverseSurface",
        "error" to "background", "onSurfaceVariant" to "surfaceContainerHigh", "tertiary" to "background")

    // ---------------- spacing, shapes, elevation, motion (dp / ms) ----------------

    object Space { const val XXS = 2; const val XS = 4; const val S = 6; const val M = 8; const val ML = 10; const val L = 12; const val XL = 14
        const val PAGE = 16; const val SECTION = 22; const val TOP_BAR = 62; const val COMPOSER = 58; const val CONTENT_BOTTOM = 100
        const val CONTENT_BOTTOM_THREAD = 150; const val CHIPS_OFFSET = COMPOSER + 10 }

    object Radius { const val COMPOSER = 29; const val SHEET = 26; const val CARD = 20; const val BUBBLE = 20; const val BUBBLE_TAIL = 6
        const val BANNER = 18; const val ROW = 16; const val TOOL = 14; const val BUTTON = 13; const val BUTTON_SM = 11; const val FIELD = 12
        const val CHECKBOX = 7; const val PILL = 99 }

    object Elevation {
        /** Surface: 6 dp ambient shadow at 12 % (dark 35 %), dropped when nested. */
        const val SURFACE = 6
        /** Composer, FAB, pop-up (`gsh`). */
        const val FLOAT = 12
        const val SHEET = 10
        fun surfaceShadowAlpha(dark: Boolean) = if (dark) 0.35f else 0.12f
    }

    object Motion {
        const val PRESS_SCALE = 0.97f; const val PRESS_MS = 150; const val SHEET_IN_MS = 200; const val PAGE_IN_MS = 180; const val SLIDE_DP = 40
        const val NOTCH_DAMPING = 0.62f; const val NOTCH_STIFFNESS = 520f; const val POP_DAMPING = 0.7f; const val POP_STIFFNESS = 400f
        const val PULSE_MS = 500; const val PULSE_MIN = 0.35f; const val SPIN_MS = 1000; const val EDGE_GLOW_MS = 2600; const val CHEVRON_MS = 150
        const val SHORT = 150; const val MEDIUM = 220; const val LONG = 320
        /** Reduced motion: every animation snaps or fades in at most this; the edge glow is one fade of [REDUCED_GLOW_MS]. */
        const val REDUCED_MAX_MS = 100; const val REDUCED_GLOW_MS = 400
        fun duration(ms: Int, reduced: Boolean) = if (reduced) minOf(ms, REDUCED_MAX_MS) else ms
    }
}

/** 04 §2.2 type scale: size / line height (sp), weight, tracking (sp), family. */
data class TypeSpec(val size: Float, val line: Float, val weight: Int, val tracking: Float = 0f, val family: ArgusFont = ArgusFont.SANS,
                    val upper: Boolean = false, val tabular: Boolean = false)

enum class ArgusFont { SANS, DISPLAY, SERIF, MONO }

object ArgusTypeScale {
    val display = TypeSpec(32f, 38f, 750, -0.8f, ArgusFont.DISPLAY)
    val serifHello = TypeSpec(30f, 36f, 400, -0.3f, ArgusFont.SERIF)
    val title1 = TypeSpec(27f, 33f, 750, -0.6f, ArgusFont.DISPLAY)
    val title2 = TypeSpec(20f, 26f, 650)
    val sheetTitle = TypeSpec(19f, 25f, 650)
    val topTitle = TypeSpec(17f, 22f, 650)
    val rowTitle = TypeSpec(16f, 21f, 620)
    val body = TypeSpec(15f, 21f, 400)
    /** Inputs stay at 16 sp. */
    val field = TypeSpec(16f, 22f, 400)
    val bodyStrong = TypeSpec(15.5f, 21f, 600)
    val sub = TypeSpec(14.5f, 20f, 400)
    val preview = TypeSpec(14f, 19f, 400)
    val tool = TypeSpec(13.5f, 18f, 560)
    val meta = TypeSpec(12.5f, 16f, 400, tabular = true)
    val section = TypeSpec(12f, 16f, 650, 1.0f, upper = true)
    val chip = TypeSpec(13.5f, 18f, 500)
    val statusChip = TypeSpec(11.5f, 14f, 500)
    val pill = TypeSpec(11f, 14f, 600)
    val mono = TypeSpec(12.5f, 18f, 400, family = ArgusFont.MONO)

    val all: Map<String, TypeSpec> = linkedMapOf("display" to display, "serifHello" to serifHello, "title1" to title1, "title2" to title2,
        "sheetTitle" to sheetTitle, "topTitle" to topTitle, "rowTitle" to rowTitle, "body" to body, "field" to field, "bodyStrong" to bodyStrong,
        "sub" to sub, "preview" to preview, "tool" to tool, "meta" to meta, "section" to section, "chip" to chip, "statusChip" to statusChip,
        "pill" to pill, "mono" to mono)
}

/** WCAG 2.x contrast on ARGB longs (the same math as ui/Contrast.kt, which works on Compose colours). */
object ArgusContrast {
    const val AA_TEXT = 4.5
    const val AA_UI = 3.0

    private fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toDouble() / 255.0
    private fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    fun alpha(c: Long) = ch(c, 24)
    fun luminance(c: Long) = 0.2126 * lin(ch(c, 16)) + 0.7152 * lin(ch(c, 8)) + 0.0722 * lin(ch(c, 0))

    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [top] (its own alpha) over the opaque [bottom], as an opaque colour. */
    fun over(top: Long, bottom: Long): Long {
        val a = alpha(top)
        fun mix(s: Int) = Math.round((ch(top, s) * a + ch(bottom, s) * (1 - a)) * 255).toLong().coerceIn(0, 255)
        return (0xFFL shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    fun grey(v: Float): Long { val g = Math.round(v * 255).toLong().coerceIn(0, 255); return (0xFFL shl 24) or (g shl 16) or (g shl 8) or g }

    /** The weakest ratio of [fg] (drawn over each surface) across [surfaces]. */
    fun worst(fg: Long, surfaces: List<Long>): Double = surfaces.minOf { ratio(over(fg, it), it) }
}

/**
 * 04 §2.5 glass rule (pure; ArgusGlass applies it): real blur (Haze) only on fixed layers (Chrome, Sheet), only on API 31+
 * (RenderEffect), not on low-RAM phones, not when the thermal governor has turned blur off. Otherwise the layer's tint
 * alone at [FALLBACK_ALPHA]. Surface and Raised never blur (smooth scrolling).
 */
object ArgusGlassRule {
    const val FALLBACK_ALPHA = 0.92f
    const val CHROME_BLUR_DP = 24
    const val SHEET_BLUR_DP = 30

    enum class Mode { NONE, BLUR, TINT }

    fun canBlur(api: Int, lowRam: Boolean, thermalBlur: Boolean = true): Boolean = api >= 31 && !lowRam && thermalBlur

    fun mode(level: GlassLevel, api: Int, lowRam: Boolean, thermalBlur: Boolean = true): Mode = when (level) {
        GlassLevel.FLAT, GlassLevel.SURFACE, GlassLevel.RAISED -> Mode.NONE
        GlassLevel.CHROME, GlassLevel.SHEET -> if (canBlur(api, lowRam, thermalBlur)) Mode.BLUR else Mode.TINT
    }

    fun blurDp(level: GlassLevel): Int = when (level) { GlassLevel.CHROME -> CHROME_BLUR_DP; GlassLevel.SHEET -> SHEET_BLUR_DP; else -> 0 }

    /** The tint a fixed layer draws: its token colour, raised to [FALLBACK_ALPHA] when it can't blur (never lowered). */
    fun tint(p: ArgusPalette, level: GlassLevel, mode: Mode): Long? {
        val base = ArgusTokens.fill(p, level) ?: return null
        if (mode != Mode.TINT) return base
        return ArgusTokens.a(base, maxOf(ArgusContrast.alpha(base).toFloat(), FALLBACK_ALPHA))
    }
}

/** P0-SH-01: the `argus_look` switch. P1-SH-01: on by default (the ARGUS shell); off = the 0.52 shell. */
object ArgusLookFlag {
    const val KEY = "argus_look"
    const val DEFAULT = true
    const val GROUND_KEY = "argus_ground"
}
