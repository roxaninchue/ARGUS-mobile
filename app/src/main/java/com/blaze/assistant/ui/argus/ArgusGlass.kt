package com.blaze.assistant.ui.argus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blaze.assistant.ui.GlassTokens
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild

/**
 * P0-SH-01: 04 §2.5 glass in Compose. Surface / Raised = fill + top sheen + 1 dp rim + soft shadow, never blurred.
 * Chrome / Sheet = Haze (dev.chrisbanes.haze, Apache-2.0) over the content marked with [argusHazeSource] when
 * [ArgusGlassRule] allows it (API 31+, not low-RAM, thermal OK, the same gate as GlassTokens.blurSupported); otherwise
 * the layer's tint alone at 92 %. Nothing uses this until `argus_look` is on.
 */
object ArgusHaze {
    fun style(c: ArgusColors, level: GlassLevel): HazeStyle = HazeStyle(
        backgroundColor = c.bg,
        tint = HazeTint(c.fill(level)),
        blurRadius = ArgusGlassRule.blurDp(level).dp,
        noiseFactor = 0f,
        fallbackTint = HazeTint(ArgusGlassRule.tint(c.palette, level, ArgusGlassRule.Mode.TINT)?.let { ArgusColors.c(it) } ?: Color.Unspecified),
    )
}

/** Whether this phone may blur (read once per composition). */
@Composable
fun rememberArgusBlur(): Boolean {
    val ctx = LocalContext.current
    return remember { try { GlassTokens.blurSupported(ctx) } catch (_: Throwable) { false } }
}

/** The content the Chrome / Sheet layers blur (the pager / list under them). */
fun Modifier.argusHazeSource(state: HazeState): Modifier = haze(state)

/**
 * The glass of [level] on this element. [haze] = the screen's HazeState (needed for Chrome / Sheet blur; without it they
 * take the tint). [shadow] = false when nested inside another glass layer (AM rule).
 */
fun Modifier.argusGlass(level: GlassLevel, shape: Shape, haze: HazeState? = null, shadow: Boolean = true): Modifier = composed {
    val c = LocalArgusColors.current
    val blurOk = rememberArgusBlur()
    when (level) {
        GlassLevel.FLAT -> this
        GlassLevel.SURFACE, GlassLevel.RAISED -> {
            val withShadow = if (shadow) this.shadow(ArgusTokens.Elevation.SURFACE.dp, shape, clip = false,
                ambientColor = Color.Black.copy(alpha = ArgusTokens.Elevation.surfaceShadowAlpha(c.dark)),
                spotColor = Color.Black.copy(alpha = ArgusTokens.Elevation.surfaceShadowAlpha(c.dark))) else this
            withShadow.clip(shape)
                .background(c.fill(level))
                .background(Brush.verticalGradient(*c.specularStops))
                .border(1.dp, c.glassRim, shape)
        }
        GlassLevel.CHROME, GlassLevel.SHEET -> {
            val mode = if (haze != null && blurOk) ArgusGlassRule.Mode.BLUR else ArgusGlassRule.Mode.TINT
            val base = this.clip(shape)
            val glass = if (mode == ArgusGlassRule.Mode.BLUR) base.hazeChild(haze!!, ArgusHaze.style(c, level))
                else base.background(ArgusGlassRule.tint(c.palette, level, mode)?.let { ArgusColors.c(it) } ?: Color.Transparent)
            glass.border(1.dp, c.glassRim, shape)
        }
    }
}
