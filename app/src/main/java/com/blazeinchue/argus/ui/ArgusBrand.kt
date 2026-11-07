package com.blazeinchue.argus.ui

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

/**
 * 0.52.1 Argus brand tokens (owner's board work/ref/argus/argus-brand-reference.jpg; docs/BRAND.md). The palette, the
 * text-safe tints that keep WCAG AA, the cobalt-to-black ground with its thin light arcs, and the wordmark style.
 */
object ArgusBrand {
    // The five brand colours.
    val Black = Color(0xFF0A0A0A)
    val Cobalt = Color(0xFF0D28FF)
    val Electric = Color(0xFF3466FF)
    val Silver = Color(0xFFA6ABB3)
    val White = Color(0xFFFFFFFF)

    /**
     * AA notes (contrast ratios, normal text needs 4.5): white on Electric 4.68, white on Cobalt 7.6, Cobalt on white 7.6.
     * Electric on Black is only 4.23, so accent TEXT / icons on the dark ground use [ElectricText] (6.6 on Black);
     * Silver on Black 8.6 (fine for secondary text), Silver on white 2.3 (decoration only, never text).
     */
    val ElectricText = Color(0xFF6A8FFF)
    /** Deep cobalt-black: the top of the dark ground gradient. */
    val CobaltNight = Color(0xFF06103D)
    /** Light mode: silver-white ground and a silver hairline. */
    val Paper = Color(0xFFF6F7F9)
    val SilverLine = Color(0xFFD9DCE1)

    /** Primary fills (buttons, the send arrow, selected chips): cobalt → electric. White text on it. */
    val primary: Brush get() = Brush.linearGradient(listOf(Cobalt, Electric))

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
