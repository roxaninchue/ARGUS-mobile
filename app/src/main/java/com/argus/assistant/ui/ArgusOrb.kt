package com.argus.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import kotlin.math.PI

/**
 * 0.52.1 Argus orb (owner's board: the obsidian sphere in silver bands, electric-blue orbit arcs and the glowing A at
 * its heart). This is the agent's face: the typing indicator, the streaming traveller and the floating orb all draw it
 * instead of the old water drop.
 *
 * Performance: one Canvas, no bitmaps, no per-frame Path (the A's paths live in [ArgusGeom]); ~8 fills per frame at
 * 30 fps only while the orb is awake ([ArgusOrb] spins only with `spin = true`, and the caller's clock already stops for
 * Thermal/reduce-motion).
 */
object ArgusOrbColors {
    val Core = Color(0xFF04070E)
    val Deep = Color(0xFF070B14)
    val Blue = Color(0xFF0056FF)
    val Electric = Color(0xFF2868FF)
    val Ice = Color(0xFFCFE0FF)
    val Silver = Color(0xFFE6EAF0)
}

/**
 * Draws the orb centred on [center] with radius [r]. [t] (radians, any range) turns the bands and slides the A's sheen;
 * 0 = still. [stretch] > 1 elongates it vertically (falling), < 1 squashes it (landing), exactly like the old drop.
 */
fun DrawScope.drawArgusOrb(center: Offset, r: Float, t: Float, stretch: Float = 1f, alpha: Float = 1f) {
    if (alpha <= 0f || r <= 0f) return
    // 1. Outer bloom: one radial fill, the only thing outside the sphere.
    drawCircle(
        Brush.radialGradient(
            listOf(ArgusOrbColors.Electric.copy(alpha = 0.26f * alpha), ArgusOrbColors.Blue.copy(alpha = 0.10f * alpha), Color.Transparent),
            center = center, radius = r * 2.45f),
        radius = r * 2.45f, center = center)
    val sy = stretch.coerceIn(0.55f, 1.6f)
    scale(scaleX = 1f / sy, scaleY = sy, pivot = center) {
        // 2. The glass body: obsidian, lit from the top-left.
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF1D2A45).copy(alpha = alpha), ArgusOrbColors.Deep.copy(alpha = alpha), Color(0xFF020408).copy(alpha = alpha)),
                center = Offset(center.x - r * 0.32f, center.y - r * 0.38f), radius = r * 1.7f),
            radius = r, center = center)
        // 3. Cool blue rim light along the lower-right (the board's sphere is lit from behind by its orbit).
        drawCircle(
            Brush.linearGradient(
                listOf(Color.Transparent, ArgusOrbColors.Electric.copy(alpha = 0.55f * alpha), ArgusOrbColors.Ice.copy(alpha = 0.35f * alpha)),
                start = Offset(center.x - r, center.y - r), end = Offset(center.x + r, center.y + r)),
            radius = r, center = center, style = Stroke(width = r * 0.10f))
        // 4. Silver rim, top-left: reads as polished metal against the dark body.
        drawCircle(
            Brush.linearGradient(
                listOf(ArgusOrbColors.Silver.copy(alpha = 0.55f * alpha), ArgusOrbColors.Silver.copy(alpha = 0.06f * alpha), Color.Transparent),
                start = Offset(center.x - r * 0.9f, center.y - r * 0.9f), end = Offset(center.x + r * 0.5f, center.y + r * 0.6f)),
            radius = r, center = center, style = Stroke(width = r * 0.055f))
        // 5. Two silver orbit bands, slowly turning.
        val band = (r * 0.062f).coerceAtLeast(1f)
        rotate(degrees = -18f + t * 6f, pivot = center) {
            drawOval(
                Brush.linearGradient(
                    listOf(Color.Transparent, ArgusOrbColors.Silver.copy(alpha = 0.75f * alpha), Color(0xFF8E98A3).copy(alpha = 0.35f * alpha)),
                    start = Offset(center.x - r * 1.2f, center.y - r * 0.2f), end = Offset(center.x + r * 1.2f, center.y + r * 0.2f)),
                topLeft = Offset(center.x - r * 1.16f, center.y - r * 0.40f), size = Size(r * 2.32f, r * 0.80f),
                style = Stroke(width = band))
        }
        rotate(degrees = 32f - t * 4f, pivot = center) {
            drawOval(
                ArgusOrbColors.Silver.copy(alpha = 0.34f * alpha),
                topLeft = Offset(center.x - r * 0.92f, center.y - r * 1.10f), size = Size(r * 1.84f, r * 2.20f),
                style = Stroke(width = band * 0.8f))
        }
        // 6. The bright electric arc that crosses the front of the sphere.
        rotate(degrees = -12f, pivot = center) {
            drawArc(
                Brush.sweepGradient(
                    listOf(Color.Transparent, ArgusOrbColors.Electric.copy(alpha = 0.30f * alpha), ArgusOrbColors.Ice.copy(alpha = 0.95f * alpha), Color.Transparent),
                    center = center),
                startAngle = 8f, sweepAngle = 118f, useCenter = false,
                topLeft = Offset(center.x - r * 1.22f, center.y - r * 0.52f), size = Size(r * 2.44f, r * 1.04f),
                style = Stroke(width = r * 0.10f))
        }
        // 7. Glowing A core.
        drawCircle(
            Brush.radialGradient(
                listOf(ArgusOrbColors.Blue.copy(alpha = 0.55f * alpha), ArgusOrbColors.Electric.copy(alpha = 0.18f * alpha), Color.Transparent),
                center = center, radius = r * 0.78f),
            radius = r * 0.78f, center = center)
        drawArgusMark(center, r * 1.55f, t, alpha = alpha, fill = 0.50f)
        // 8. Specular glint, top-left.
        drawOval(Color.White.copy(alpha = 0.55f * alpha),
            topLeft = Offset(center.x - r * 0.52f, center.y - r * 0.60f), size = Size(r * 0.34f, r * 0.20f))
    }
}

/** The orb on its own. [spin] turns the bands and the A's sheen; false = a still picture (no frames at all). */
@Composable
fun ArgusOrb(size: Dp, modifier: Modifier = Modifier, spin: Boolean = false) {
    val t = if (spin && MotionTokens.enabled) {
        val trans = rememberInfiniteTransition(label = "argusOrb")
        val v by trans.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "t")
        v
    } else 0f
    Canvas(modifier.size(size)) {
        drawArgusOrb(Offset(this.size.width / 2f, this.size.height / 2f), this.size.minDimension / 2f, t)
    }
}

/** The A alone, still: the emblem used in avatars and cards (no animation frames). */
@Composable
fun ArgusEmblem(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        drawArgusMark(Offset(this.size.width / 2f, this.size.height / 2f), this.size.minDimension, 0f)
    }
}
