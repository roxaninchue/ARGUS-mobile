package com.blaze.assistant.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.space.SpacePalette

/**
 * 0.52.1 Argus brand tokens (owner's board work/ref/argus/argus-brand-reference.jpg; docs/BRAND.md). The palette, the
 * text-safe tints that keep WCAG AA, the cobalt-to-black ground with its thin light arcs, and the wordmark style.
 * Unification: ONE palette. The brand colours are [SpacePalette]'s (the ARGUS x Hark shell); the old names stay so
 * nothing that reads them has to change: Cobalt = Argus Blue, Electric = Electric, Black = Obsidian, Silver = Steel.
 */
object ArgusBrand {
    // The five brand colours (SpacePalette).
    val Black = Color(SpacePalette.OBSIDIAN)
    val Cobalt = Color(SpacePalette.ARGUS_BLUE)
    val Electric = Color(SpacePalette.ELECTRIC)
    val Silver = Color(SpacePalette.STEEL)
    val White = Color(SpacePalette.WHITE)

    /**
     * AA notes (contrast ratios, normal text needs 4.5): white on Cobalt (Argus Blue) 5.6, Cobalt on the light ground 5.1.
     * White on Electric is only 3.6, so Electric is a fill / ring colour, never under text; accent TEXT / icons on the dark
     * ground use [ElectricText] (8.5 on Black). Silver (Steel) on Black 8.1, on white 2.6: decoration only on light grounds.
     */
    val ElectricText = Color(SpacePalette.ELECTRIC_TEXT)
    /** Deep cobalt-black: the top of the dark ground gradient. */
    val CobaltNight = Color(0xFF06103D)
    /** Light mode: the shell's light ground and a silver hairline. */
    val Paper = Color(SpacePalette.GROUND_LIGHT)
    val SilverLine = Color(0xFFD9DCE1)

    /**
     * Primary fills (buttons, the send arrow, selected chips). Unification: solid Argus Blue (the 0.52.1 cobalt-to-electric
     * gradient is retired), white text on it.
     */
    val primary: Brush get() = androidx.compose.ui.graphics.SolidColor(Cobalt)

    /** Wordmark: "ARGUS", wide-tracked uppercase (~0.35 em). */
    fun wordmark(size: TextUnit = 14.sp, color: Color = White): TextStyle =
        TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = size, letterSpacing = 0.35.em, color = color)

    /** Clear space around the A: about one cap height of the A, i.e. ~0.7 of the mark's height. */
    const val CLEAR_SPACE = 0.7f
}

/** The dark ground's thin blue light arcs (two strokes, very faint, bottom-right like the board's covers). */
fun DrawScope.drawArgusArcs(alpha: Float = 1f) {
    val w = size.width; val h = size.height
    val stroke = Stroke(width = (w * 0.0025f).coerceAtLeast(1f))
    drawArc(Brush.linearGradient(listOf(Color.Transparent, ArgusBrand.Electric.copy(alpha = 0.35f * alpha), Color.Transparent),
        start = Offset(w * 0.2f, h), end = Offset(w, h * 0.45f)),
        startAngle = 200f, sweepAngle = 70f, useCenter = false, topLeft = Offset(w * 0.15f, h * 0.55f),
        size = androidx.compose.ui.geometry.Size(w * 1.6f, h * 1.1f), style = stroke)
    drawArc(Brush.linearGradient(listOf(Color.Transparent, ArgusBrand.Cobalt.copy(alpha = 0.28f * alpha), Color.Transparent),
        start = Offset(w * 0.3f, h), end = Offset(w, h * 0.55f)),
        startAngle = 205f, sweepAngle = 60f, useCenter = false, topLeft = Offset(w * 0.3f, h * 0.68f),
        size = androidx.compose.ui.geometry.Size(w * 1.5f, h), style = stroke)
}
