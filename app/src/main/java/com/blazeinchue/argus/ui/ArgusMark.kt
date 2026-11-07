package com.blazeinchue.argus.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * 0.52.1 Argus mark: the brushed-silver "A" of four slanted bars (vector redraw of work/ref/argus/argus-logo-source.jpg;
 * the launcher icon res/drawable/ic_launcher_*.xml is generated from the same numbers by work/ref/argus/mk.py).
 * Each bar is a quadrilateral in units: x, y in -0.5..0.5 around the mark's centre, y down. Pure numbers + one draw call.
 */
object ArgusGeom {
    val BARS: List<FloatArray> = listOf(
        floatArrayOf(-0.0696f, -0.3066f, -0.0316f, -0.2461f, -0.3861f, 0.3896f, -0.5000f, 0.4121f),
        floatArrayOf(0.0387f, -0.2011f, 0.0710f, -0.1477f, -0.1976f, 0.2771f, -0.2820f, 0.3165f),
        floatArrayOf(0.0499f, -0.0661f, 0.1273f, -0.0520f, 0.3270f, 0.2841f, 0.2468f, 0.2771f),
        floatArrayOf(-0.0007f, -0.4177f, 0.5000f, 0.4177f, 0.4114f, 0.3980f, 0.0035f, -0.2771f),
    )
    /** Silver: highlight → mid → second highlight → shadow, along the top-left → bottom-right diagonal. */
    val SILVER = listOf(Color(0xFFF7F9FB), Color(0xFFB9C1CA), Color(0xFFEDF0F3), Color(0xFF8E98A3))
    /** The navy the mark sits on (launcher background centre / edge). */
    const val NAVY = 0xFF123A63
    const val NAVY_DEEP = 0xFF071526
}

/**
 * Draws the A centred on [center], [w] px across (the mark fills ~[fill] of it). [t] (radians, any range) slides the
 * silver's sheen slowly while working; 0 = still.
 */
fun DrawScope.drawArgusMark(center: Offset, w: Float, t: Float = 0f, alpha: Float = 1f, fill: Float = 0.62f) {
    val k = w * fill
    val shift = (kotlin.math.sin(t * 0.25f) * 0.18f) * k
    val brush = Brush.linearGradient(ArgusGeom.SILVER.map { it.copy(alpha = alpha) },
        start = Offset(center.x - k * 0.5f + shift, center.y - k * 0.5f + shift), end = Offset(center.x + k * 0.5f + shift, center.y + k * 0.5f + shift))
    for (b in ArgusGeom.BARS) {
        val p = Path().apply {
            moveTo(center.x + b[0] * k, center.y + b[1] * k)
            var i = 2
            while (i < b.size) { lineTo(center.x + b[i] * k, center.y + b[i + 1] * k); i += 2 }
            close()
        }
        drawPath(p, brush)
    }
}
