package com.argus.assistant.ui.argus

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * P0-SH-02: a bot's avatar (04 §2.7). Since 2026-11-17 a friendly body with two eyes instead of the ringed orb: [face]
 * picks the shape ([OrbGeometry.shape]), [color] any ARGB colour, [state] adds motion (Working: the eyes glance side to
 * side) and the status dot. [amplitude] (0..1) drives Talking. Drawn on Canvas from [OrbGeometry]; no images.
 */
@Composable
fun BotOrb(face: OrbFace, color: Long, size: Dp = OrbSize.MD.dp, state: OrbState = OrbState.IDLE, amplitude: Float = 0f,
           reducedMotion: Boolean = com.argus.assistant.ui.argus.settings.ArgusPrefs.reducedNow(), contentDescription: String? = null, modifier: Modifier = Modifier) {
    val p = LocalArgusColors.current.palette
    val spin = if (state == OrbState.WORKING && !reducedMotion) {
        val t = rememberInfiniteTransition(label = "orb")
        val v by t.animateFloat(0f, OrbGeometry.EYE_LOOK_MS.toFloat(), infiniteRepeatable(tween(OrbGeometry.EYE_LOOK_MS, easing = LinearEasing), RepeatMode.Restart), label = "eyes")
        v
    } else 0f
    val sem = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier.size(size).then(sem)) {
        val c = OrbGeometry.stateColor(color, state)
        drawBotBody(face, c, OrbGeometry.eyeShift(state, spin.toLong(), reducedMotion), OrbGeometry.bodyScale(state, amplitude))
        OrbGeometry.dotColor(state, p)?.let { drawStatusDot(it, OrbGeometry.dotDp(size.value).dp.toPx(), p) }
    }
}

/**
 * The Manager (ARGUS, the lead). Since 2026-11-17 (owner: "yes Argus too please") a friendly cobalt blob with two eyes,
 * like the bots, with its blue glow; only the Manager uses cobalt (04 §2.7).
 */
@Composable
fun ManagerOrb(size: Dp = OrbSize.MD.dp, state: OrbState = OrbState.IDLE, contentDescription: String? = "Manager", modifier: Modifier = Modifier,
               reducedMotion: Boolean = com.argus.assistant.ui.argus.settings.ArgusPrefs.reducedNow()) {
    val p = LocalArgusColors.current.palette
    val look = if (state == OrbState.WORKING && !reducedMotion) {
        val t = rememberInfiniteTransition(label = "manager")
        val v by t.animateFloat(0f, OrbGeometry.EYE_LOOK_MS.toFloat(), infiniteRepeatable(tween(OrbGeometry.EYE_LOOK_MS, easing = LinearEasing), RepeatMode.Restart), label = "eyes")
        v
    } else 0f
    val sem = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier.size(size).then(sem)) {
        val glowR = size.toPx() / 2f
        drawCircle(Brush.radialGradient(listOf(c(OrbGeometry.MANAGER_GLOW), Color.Transparent), center = center, radius = glowR), radius = glowR)
        drawBotBody(OrbGeometry.MANAGER_FACE, OrbGeometry.managerColor(p), OrbGeometry.eyeShift(state, look.toLong(), reducedMotion), 1f)
        OrbGeometry.dotColor(state, p)?.let { drawStatusDot(it, OrbGeometry.dotDp(size.value).dp.toPx(), p) }
    }
}

private fun c(argb: Long) = Color(argb.toInt())

/** The friendly body: [face]'s shape in [color], two eyes shifted [eyeShift] units sideways, scaled by [bodyScale]. */
fun DrawScope.drawBotBody(face: OrbFace, color: Long, eyeShift: Float, bodyScale: Float) {
    val k = size.minDimension / OrbGeometry.BOX
    val col = OrbGeometry.colors(color)
    val body = c(col.body)
    val ctr = Offset(size.width / 2f, size.height / 2f)
    fun u(x: Float, y: Float) = Offset(ctr.x + (x - OrbGeometry.CENTER) * k, ctr.y + (y - OrbGeometry.CENTER) * k)
    fun rr(r: BodyRect) = Path().apply {
        val a = u(r.l, r.t); val z = u(r.r, r.b)
        addRoundRect(RoundRect(a.x, a.y, z.x, z.y, CornerRadius(r.tl * k), CornerRadius(r.tr * k), CornerRadius(r.br * k), CornerRadius(r.bl * k)))
    }
    val shape = OrbGeometry.shape(face)
    val outline: Path = OrbGeometry.bodyRect(shape)?.let { rr(it) } ?: when (shape) {
        BotShape.DROP -> Path().apply {
            val s0 = u(OrbGeometry.DROP_START[0], OrbGeometry.DROP_START[1]); moveTo(s0.x, s0.y)
            for (q in OrbGeometry.DROP_PATH) { val a = u(q[0], q[1]); val b = u(q[2], q[3]); val e = u(q[4], q[5]); cubicTo(a.x, a.y, b.x, b.y, e.x, e.y) }
            close()
        }
        else -> {
            var acc = rr(OrbGeometry.CLOUD_BASE)
            for (ci in OrbGeometry.CLOUD_CIRCLES) {
                val o = u(ci[0], ci[1]); val r = ci[2] * k
                val circle = Path().apply { addOval(Rect(o.x - r, o.y - r, o.x + r, o.y + r)) }
                acc = Path().apply { op(acc, circle, PathOperation.Union) }
            }
            acc
        }
    }

    // a soft glow behind, lighter than the old halo
    drawCircle(Brush.radialGradient(0f to body.copy(alpha = OrbGeometry.SOFT_HALO_ALPHA), 1f to body.copy(alpha = 0f),
        center = ctr, radius = OrbGeometry.HALO_R * k), radius = OrbGeometry.HALO_R * k, center = ctr)

    scale(bodyScale, ctr) {
        val top = u(0f, OrbGeometry.BODY_T).y; val bottom = u(0f, OrbGeometry.BODY_B).y
        drawPath(outline, Brush.verticalGradient(0f to c(OrbGeometry.mix(col.body, 0xFFFFFF, 0.12f)), 0.55f to body,
            1f to c(OrbGeometry.mix(col.body, 0x02030A, 0.22f)), startY = top, endY = bottom))
        val g = OrbGeometry.glossAt(shape); val gp = u(g[0], g[1])
        withTransform({ rotate(OrbGeometry.GLOSS_ROT, gp) }) {
            drawOval(Color.White.copy(alpha = OrbGeometry.GLOSS_ALPHA), topLeft = Offset(gp.x - OrbGeometry.GLOSS_RX * k, gp.y - OrbGeometry.GLOSS_RY * k),
                size = Size(2 * OrbGeometry.GLOSS_RX * k, 2 * OrbGeometry.GLOSS_RY * k))
        }
        val eye = if (OrbGeometry.isLight(color)) Color(0xFF1F2430.toInt()).copy(alpha = 0.85f) else Color.White.copy(alpha = OrbGeometry.EYE_ALPHA)
        for (e in OrbGeometry.EYES) {
            val at = u(e.x + eyeShift, e.y)
            withTransform({ rotate(OrbGeometry.EYE_TILT, at) }) {
                drawRoundRect(eye, topLeft = Offset(at.x - e.w * k / 2, at.y - e.h * k / 2), size = Size(e.w * k, e.h * k), cornerRadius = CornerRadius(e.r * k))
            }
        }
    }
}

/** botSVG in a 64-unit box scaled to this canvas (the old ringed orb, kept for reference shots). */
fun DrawScope.drawBotOrb(face: OrbFace, color: Long, ringDeg: Float, bodyScale: Float) {
    val k = size.minDimension / OrbGeometry.BOX
    val col = OrbGeometry.colors(color)
    val body = c(col.body)
    val ctr = Offset(size.width / 2f, size.height / 2f)
    fun u(x: Float, y: Float) = Offset(ctr.x + (x - OrbGeometry.CENTER) * k, ctr.y + (y - OrbGeometry.CENTER) * k)
    val rings = OrbGeometry.rings(face)

    // halo: flat 45 % to 55 % of the radius, then out to 0
    drawCircle(Brush.radialGradient(0f to body.copy(alpha = 0.45f), 0.55f to body.copy(alpha = 0.45f), 1f to body.copy(alpha = 0f),
        center = ctr, radius = OrbGeometry.HALO_R * k), radius = OrbGeometry.HALO_R * k, center = ctr)

    // back halves of the rings (top arc), behind the body
    rotate(ringDeg, ctr) {
        for (r in rings) drawArc(body.copy(alpha = r.alpha * OrbGeometry.BACK_RING_ALPHA), 180f, 180f, false,
            topLeft = Offset(ctr.x - r.rx * k, ctr.y - r.ry * k), size = Size(2 * r.rx * k, 2 * r.ry * k),
            style = Stroke(width = r.width * OrbGeometry.BACK_RING_WIDTH * k, cap = StrokeCap.Round))
    }

    scale(bodyScale, ctr) {
        val br = OrbGeometry.BODY_R * k
        val box = 2 * br
        drawCircle(Brush.radialGradient(0f to c(col.hi), 0.42f to body, 1f to c(col.lo),
            center = Offset(ctr.x - br + box * OrbGeometry.BODY_GX, ctr.y - br + box * OrbGeometry.BODY_GY), radius = box * OrbGeometry.BODY_GR),
            radius = br, center = ctr)
        val core = u(OrbGeometry.CORE_X, OrbGeometry.CORE_Y)
        drawCircle(Brush.radialGradient(0f to Color.White, 0.35f to c(col.hi).copy(alpha = 0.8f), 1f to body.copy(alpha = 0f),
            center = core, radius = OrbGeometry.CORE_R * k), radius = OrbGeometry.CORE_R * k, center = core, alpha = OrbGeometry.CORE_ALPHA)
        drawCircle(c(col.rim).copy(alpha = OrbGeometry.RIM_ALPHA), radius = OrbGeometry.RIM_R * k, center = ctr, style = Stroke(width = 1f * k))
        val sp = u(OrbGeometry.SPEC_X, OrbGeometry.SPEC_Y)
        withTransform({ rotate(OrbGeometry.SPEC_ROT, sp) }) {
            drawOval(Color.White.copy(alpha = OrbGeometry.SPEC_ALPHA), topLeft = Offset(sp.x - OrbGeometry.SPEC_RX * k, sp.y - OrbGeometry.SPEC_RY * k),
                size = Size(2 * OrbGeometry.SPEC_RX * k, 2 * OrbGeometry.SPEC_RY * k))
        }
    }

    // front halves (bottom arc), over the body
    rotate(ringDeg, ctr) {
        for (r in rings) drawArc(c(col.rim).copy(alpha = r.alpha), 0f, 180f, false,
            topLeft = Offset(ctr.x - r.rx * k, ctr.y - r.ry * k), size = Size(2 * r.rx * k, 2 * r.ry * k),
            style = Stroke(width = r.width * k, cap = StrokeCap.Round))
    }
}

/** The 04 §2.7 status dot at the bottom-left, ringed in the page colour so it reads on any orb. */
fun DrawScope.drawStatusDot(color: Long, dotPx: Float, p: ArgusPalette) {
    val r = dotPx / 2f
    val at = Offset(r + size.width * 0.06f, size.height - r - size.height * 0.06f)
    drawCircle(c(p.bg), radius = r + dotPx * 0.2f, center = at)
    drawCircle(c(color), radius = r, center = at)
}
