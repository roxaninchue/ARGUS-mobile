package com.blaze.assistant.ui.argus

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.blaze.assistant.ui.drawArgusMark

/**
 * P0-SH-02: a bot's avatar, the A-team orb (04 §2.7). [face] picks the ring style, [color] any ARGB colour, [state]
 * adds motion and the status dot. [amplitude] (0..1) drives Talking. Drawn on Canvas from [OrbGeometry]; no images.
 */
@Composable
fun BotOrb(face: OrbFace, color: Long, size: Dp = OrbSize.MD.dp, state: OrbState = OrbState.IDLE, amplitude: Float = 0f,
           reducedMotion: Boolean = com.blaze.assistant.ui.argus.settings.ArgusPrefs.reducedNow(), contentDescription: String? = null, modifier: Modifier = Modifier) {
    val p = LocalArgusColors.current.palette
    val spin = if (state == OrbState.WORKING && !reducedMotion) {
        val t = rememberInfiniteTransition(label = "orb")
        val v by t.animateFloat(0f, OrbGeometry.WORKING_TURN_MS.toFloat(), infiniteRepeatable(tween(OrbGeometry.WORKING_TURN_MS, easing = LinearEasing), RepeatMode.Restart), label = "ring")
        v
    } else 0f
    val sem = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier.size(size).then(sem)) {
        val c = OrbGeometry.stateColor(color, state)
        drawBotOrb(face, c, OrbGeometry.ringAngle(face, state, spin.toLong(), reducedMotion), OrbGeometry.bodyScale(state, amplitude))
        OrbGeometry.dotColor(state, p)?.let { drawStatusDot(it, OrbGeometry.dotDp(size.value).dp.toPx(), p) }
    }
}

/** The Manager: the cobalt sphere with the ARGUS "A" (A-team's img/manager.png redrawn, not copied). */
@Composable
fun ManagerOrb(size: Dp = OrbSize.MD.dp, state: OrbState = OrbState.IDLE, contentDescription: String? = "Manager", modifier: Modifier = Modifier) {
    val p = LocalArgusColors.current.palette
    val sem = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier.size(size).then(sem)) {
        val glowR = size.toPx() / 2f
        drawCircle(Brush.radialGradient(listOf(c(OrbGeometry.MANAGER_GLOW), Color.Transparent), center = center, radius = glowR), radius = glowR)
        drawBotOrb(OrbFace.PURE, OrbGeometry.managerColor(p), 0f, 1f)
        drawArgusMark(center, this.size.minDimension * (OrbGeometry.BODY_R * 2 / OrbGeometry.BOX), fill = OrbGeometry.MANAGER_MARK_FILL)
        OrbGeometry.dotColor(state, p)?.let { drawStatusDot(it, OrbGeometry.dotDp(size.value).dp.toPx(), p) }
    }
}

private fun c(argb: Long) = Color(argb.toInt())

/** botSVG in a 64-unit box scaled to this canvas. */
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
