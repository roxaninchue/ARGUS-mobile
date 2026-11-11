package com.blaze.assistant.ui.argus.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.LocalBlaze

/**
 * P1-SH-02: what every kit component reads for colour, type and shape. ONE adapter: today it is built from
 * [KitPalette] (the 04 §2.1 values) for the app's light/dark mode; when the P0 `ui/argus` tokens land, [kitLook]
 * maps from `ArgusColors` instead and no component changes.
 *
 * Tests and previews can force a mode with [LocalKitDark].
 */
@Immutable
data class KitLook(
    val dark: Boolean,
    val bg: Color, val ink: Color, val onInk: Color, val mute: Color, val dim: Color,
    val soft: Color, val soft2: Color, val line: Color, val line2: Color,
    val glass: Color, val glass2: Color, val rim: Color, val rim2: Color,
    val chrome: Color, val sheet: Color, val scrim: Color,
    val danger: Color, val dangerText: Color, val success: Color, val successText: Color,
    val waiting: Color, val waitingText: Color, val unread: Color, val manager: Color, val cobalt: Color,
) {
    companion object {
        fun of(dark: Boolean): KitLook {
            val t = KitPalette.of(dark)
            fun c(v: Long) = Color(v.toInt())
            return KitLook(dark, c(t.bg), c(t.ink), c(t.onInk), c(t.mute), c(t.dim), c(t.soft), c(t.soft2), c(t.line), c(t.line2),
                c(t.glassFill), c(t.glassFill2), c(t.glassRim), c(t.glassRim2), c(t.chrome), c(t.sheet), c(t.scrim),
                c(t.danger), c(t.dangerText), c(t.success), c(t.successText), c(t.waiting), c(t.waitingText),
                c(t.unread), c(t.manager), c(t.cobalt))
        }
    }
}

/** Forces the kit's mode (shots, previews); null = follow the app theme. */
val LocalKitDark = compositionLocalOf<Boolean?> { null }

@Composable
fun kitLook(): KitLook = KitLook.of(LocalKitDark.current ?: LocalBlaze.current.dark)

/** Kit type (04 §2.2) as Compose styles in [color]. Inter is the app's default family, so only the serif is named. */
object KitType {
    private fun w(v: Int) = FontWeight(v.coerceIn(100, 900))
    fun style(s: KitText.Style, color: Color, family: FontFamily? = null, tnum: Boolean = false): TextStyle = TextStyle(
        color = color, fontSize = s.size.sp, lineHeight = s.line.sp, fontWeight = w(s.weight),
        letterSpacing = s.tracking.sp, fontFamily = family, fontFeatureSettings = if (tnum) "tnum" else null,
    )
    /** The serif accent (04 §2.2: Chat greeting, Life day line, empty heroes). Instrument Serif when bundled; system serif until then. */
    val Serif: FontFamily = FontFamily.Serif
    val Mono: FontFamily = FontFamily.Monospace
}

/** Kit shapes (04 §2.4). */
object KitShapes {
    val composer: Shape = RoundedCornerShape(KitRadius.COMPOSER.dp)
    val sheet: Shape = RoundedCornerShape(topStart = KitRadius.SHEET.dp, topEnd = KitRadius.SHEET.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
    val card: Shape = RoundedCornerShape(KitRadius.CARD.dp)
    val banner: Shape = RoundedCornerShape(KitRadius.BANNER.dp)
    val row: Shape = RoundedCornerShape(KitRadius.ROW.dp)
    val tool: Shape = RoundedCornerShape(KitRadius.TOOL.dp)
    val button: Shape = RoundedCornerShape(KitRadius.BUTTON.dp)
    val buttonSm: Shape = RoundedCornerShape(KitRadius.BUTTON_SM.dp)
    val field: Shape = RoundedCornerShape(KitRadius.FIELD.dp)
    val checkbox: Shape = RoundedCornerShape(KitRadius.CHECKBOX.dp)
    val pill: Shape = RoundedCornerShape(KitRadius.SEGMENT.dp)
    val bubbleUser: Shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
}

/**
 * Surface glass without blur (04 §2.5 "Surface"): glass fill + top sheen + 1 dp rim. The frosted Chrome level (Haze)
 * belongs to the P0 `ArgusGlass`; until it is on the branch, chrome surfaces use the tint alone (the low-RAM fallback).
 */
fun Modifier.kitSurface(look: KitLook, shape: Shape = KitShapes.card, raised: Boolean = false): Modifier = this
    .clip(shape)
    .background(if (raised) look.glass2 else look.glass)
    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (look.dark) 0.10f else 0.45f), 0.45f to Color.Transparent))
    .border(BorderStroke(1.dp, look.rim), shape)

/** Chrome tint (top bar, composer, chips, toast) at the fallback alpha. */
fun Modifier.kitChrome(look: KitLook, shape: Shape): Modifier = this
    .clip(shape)
    .background(look.chrome.copy(alpha = 0.92f))
    .border(BorderStroke(1.dp, look.rim), shape)
