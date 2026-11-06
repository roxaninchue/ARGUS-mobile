package com.blaze.assistant.ui

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 0.52.2: ONE look. The Living Themes (twelve particle themes + Weather, their scene pictures, the 3D theme carousel, the
 * Look & feel living backgrounds and the theme switchers on every screen) were removed with the ARGUS living 3D Home;
 * the app has the ARGUS palette in light and dark (docs/BRAND.md) and an optional accent. What stays is here (pure parts
 * tested by ArgusLookTest): colour maths, light / dark / auto, the accent presets, the chat bubbles' tones and the
 * long-press light / dark toggle.
 *
 * Old prefs (`look_theme`, `look_bg`, `look_music`, `look_still`, `look_photo`, `look_strength`) are left where they are and
 * no longer read; cloud sync may still carry them, harmlessly.
 */
object LookLogic {
    /** Mixes [a] toward [b] by [k] (0..1), per channel, alpha kept from [a]. Colours are ARGB longs. */
    fun mix(a: Long, b: Long, k: Float): Long {
        val kk = k.coerceIn(0f, 1f)
        fun ch(v: Long, s: Int) = ((v shr s) and 0xFF).toInt()
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * kk).toInt().coerceIn(0, 255).toLong()
        return (a and 0xFF000000) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /** Light / Dark / Auto: Auto is dark from 19:30 to 05:00 (local time); anything else follows "system" (dark-first). */
    fun darkFor(theme: String, hour: Int, minute: Int = 0, systemDark: Boolean = true): Boolean = when (theme) {
        "light" -> false
        "dark" -> true
        "auto" -> { val h = hour + minute / 60f; h < 5f || h >= 19.5f }
        else -> systemDark
    }

    /** Accent presets for Settings › Look & feel ("" = the ARGUS accent). */
    val ACCENTS = listOf("" to "ARGUS", "FF0056FF" to "Argus Blue", "FF1286FF" to "Electric", "FF14B8A6" to "Teal", "FFF59E0B" to "Amber", "FF8B5CF6" to "Violet")

    fun parseAccent(hex: String?): Long? = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 8 }?.toLongOrNull(16)
}

/** Look & feel prefs as live state: the accent (and the reload cloud sync calls). */
object Look {
    private fun s(k: String, d: String) = try { com.blaze.assistant.agent.Settings.str(k, d) } catch (_: Throwable) { d }
    val accent = MutableStateFlow(s("look_accent", ""))
    fun setAccent(v: String) { accent.value = v; try { com.blaze.assistant.agent.Settings.setStr("look_accent", v) } catch (_: Throwable) { } }
    /** Cloud sync brought Look & feel from another phone. */
    fun reload() { accent.value = s("look_accent", "") }
}

/**
 * The chat bubbles' tones (BubbleTonesTest): the user's bubble takes the accent, ARGUS's a quiet steel-blue, each mixed into
 * the page's neutral just enough to tell them apart and never so much that the text loses AA (the frost search in
 * BubblePalette then holds 4.5:1 over any ground).
 */
object BubbleTones {
    data class Tones(val mine: Long, val blaze: Long)

    /** The ARGUS tones (the user's bubble a calm cobalt, ARGUS's a steel blue-grey). */
    val ARGUS = Tones(0xFF3D7EFF, 0xFF5A6070)

    /** The neutral each bubble starts from. */
    const val DARK_MINE = 0xFF26262C
    const val DARK_BLAZE = 0xFF1B1B20
    const val LIGHT_GROUND = 0xFFFFFFFF
    /** How much of the tone goes in. */
    const val DARK_MINE_K = 0.42f
    const val DARK_BLAZE_K = 0.16f
    const val LIGHT_MINE_K = 0.22f
    const val LIGHT_BLAZE_K = 0.07f
    /** The least contrast the text keeps on the solid base. */
    const val SOLID_MIN = 5.0

    private fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
    fun luminance(argb: Long): Double = 0.2126 * lin(((argb shr 16) and 0xFF).toInt()) + 0.7152 * lin(((argb shr 8) and 0xFF).toInt()) + 0.0722 * lin((argb and 0xFF).toInt())
    fun ratio(a: Long, b: Long): Double { val la = luminance(a); val lb = luminance(b); return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) }

    /**
     * The opaque base of a bubble in [dark] mode. [mine] = the user's; [lookAccent] is the accent picked in Look & feel,
     * which then tints the user's bubble. [ink] is the text colour drawn on it: a tone that would leave it under [SOLID_MIN]
     * (a very pale custom accent in dark mode) is mixed in less until it reads.
     */
    fun base(dark: Boolean, mine: Boolean, ink: Long, lookAccent: Long? = null): Long {
        val tone = if (mine) (lookAccent ?: ARGUS.mine) else ARGUS.blaze
        val from = if (!dark) LIGHT_GROUND else if (mine) DARK_MINE else DARK_BLAZE
        var k = if (dark) (if (mine) DARK_MINE_K else DARK_BLAZE_K) else (if (mine) LIGHT_MINE_K else LIGHT_BLAZE_K)
        var b = LookLogic.mix(from, tone, k)
        while (k > 0f && ratio(ink or 0xFF000000, b) < SOLID_MIN) { k = (k - 0.04f).coerceAtLeast(0f); b = LookLogic.mix(from, tone, k) }
        return b or 0xFF000000
    }

    /** A pale accent deepened so it reads on a light ground (mixed toward black). */
    fun onLight(a: Long): Long {
        val r = (a shr 16) and 0xFF; val g = (a shr 8) and 0xFF; val b = a and 0xFF
        val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        return if (lum > 0.55) LookLogic.mix(a, 0xFF000000, ((lum - 0.4) * 1.2).toFloat().coerceIn(0f, 0.7f)) else a
    }
}

/** v0.45.2: long-press on the profile icon flips light / dark (pure: the new mode and the short message). */
object LightDark {
    fun toggle(currentlyDark: Boolean): Pair<String, String> = if (currentlyDark) "light" to "Light theme" else "dark" to "Dark theme"
    /** The long-press window the profile icon uses (the system long-press timeout, typically 400–500 ms). */
    const val MIN_MS = 400L
    const val MAX_MS = 500L
}
