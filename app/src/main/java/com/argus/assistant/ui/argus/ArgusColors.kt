package com.blaze.assistant.ui.argus

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * P0-SH-01: [ArgusPalette] as Compose colours (04 §2.9 `ArgusColors`, provided by [LocalArgusColors] inside [ArgusTheme]).
 * Built only from [ArgusTokens], so ContrastTest checks the colours the app draws, not a copy.
 */
@Immutable
data class ArgusColors(
    val dark: Boolean,
    val bg: Color, val ink: Color, val onInk: Color, val mute: Color, val meta: Color, val dim: Color,
    val soft: Color, val soft2: Color, val line: Color, val line2: Color,
    val glassFill: Color, val glassFill2: Color, val glassRim: Color, val glassRim2: Color,
    val chrome: Color, val sheet: Color, val scrim: Color,
    val danger: Color, val dangerText: Color, val success: Color, val successText: Color,
    val unread: Color, val waiting: Color, val waitingText: Color,
    val manager: Color, val brandCobalt: Color, val cobaltText: Color,
    val palette: ArgusPalette,
) {
    /** The colour a [GlassLevel] fills with (Flat = transparent: the ground shows). */
    fun fill(level: GlassLevel): Color = ArgusTokens.fill(palette, level)?.let { c(it) } ?: Color.Transparent

    /** Top-sheen stops for a vertical brush (Brush.verticalGradient(*specularStops)). */
    val specularStops: Array<Pair<Float, Color>> get() = palette.specular.map { it.first to c(it.second) }.toTypedArray()

    companion object {
        fun c(argb: Long): Color = Color(argb.toInt())

        fun of(p: ArgusPalette) = ArgusColors(
            p.dark, c(p.bg), c(p.ink), c(p.onInk), c(p.mute), c(p.meta), c(p.dim),
            c(p.soft), c(p.soft2), c(p.line), c(p.line2),
            c(p.glassFill), c(p.glassFill2), c(p.glassRim), c(p.glassRim2),
            c(p.chrome), c(p.sheet), c(p.scrim),
            c(p.danger), c(p.dangerText), c(p.success), c(p.successText),
            c(p.unread), c(p.waiting), c(p.waitingText),
            c(p.manager), c(p.brandCobalt), c(p.cobaltText), p,
        )

        val Dark: ArgusColors by lazy { of(ArgusTokens.Dark) }
        val Light: ArgusColors by lazy { of(ArgusTokens.Light) }
        fun of(dark: Boolean) = if (dark) Dark else Light
    }
}
