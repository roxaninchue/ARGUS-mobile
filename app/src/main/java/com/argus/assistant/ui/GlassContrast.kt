package com.argus.assistant.ui

/**
 * 2026-11-18 (owner screenshot: the "Ask instead" chip was near-white text on the near-white Glass.blue fill). The glass
 * look's "accent" fills (Glass.blue / orange / cyan) are light greys, but many buttons still drew white ink on them
 * (~1.2:1). [inkFor] picks the ink for a fill by contrast: the light glass ink on dark or see-through fills, the near-black
 * [ON_FILL] on light ones, so a solid button is always readable (WCAG AA, 4.5:1, for its label).
 *
 * Pure ARGB ints (no Compose), so GlassOnFillContrastTest runs on the JVM; Glass.inkFor wraps it for Compose colours.
 */
object GlassContrast {
    /** Ink on light fills: GlassTokens.lightInk. */
    const val ON_FILL: Int = 0xFF111112.toInt()
    /** Ink on dark fills: GlassTokens.ink. */
    const val ON_DARK: Int = 0xFFF2F2F3.toInt()
    /** The ground translucent fills sit on (the always-dark glass sheets). */
    const val GROUND: Int = 0xFF0A0A0C.toInt()

    private fun ch(c: Int, shift: Int): Double = ((c shr shift) and 0xFF) / 255.0
    private fun lin(v: Double): Double = if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)

    /** [fill] painted over [ground] (alpha composited), opaque. */
    fun over(fill: Int, ground: Int = GROUND): Int {
        val a = ((fill ushr 24) and 0xFF) / 255.0
        fun mix(shift: Int): Int = Math.round((ch(fill, shift) * a + ch(ground, shift) * (1 - a)) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** WCAG relative luminance of an opaque colour. */
    fun luminance(c: Int): Double = 0.2126 * lin(ch(c, 16)) + 0.7152 * lin(ch(c, 8)) + 0.0722 * lin(ch(c, 0))

    /** WCAG contrast ratio of two colours ([a] over [b] when translucent). */
    fun ratio(a: Int, b: Int): Double {
        val bb = over(b); val aa = over(a, bb)
        val la = luminance(aa); val lb = luminance(bb)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** The ink (ON_DARK or ON_FILL) with the higher contrast on [fill] over [ground]. */
    fun inkFor(fill: Int, ground: Int = GROUND): Int {
        val f = over(fill, ground)
        return if (ratio(ON_DARK, f) >= ratio(ON_FILL, f)) ON_DARK else ON_FILL
    }
}
