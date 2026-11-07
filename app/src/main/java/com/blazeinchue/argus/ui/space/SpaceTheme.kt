package com.blazeinchue.argus.ui.space

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blazeinchue.argus.ui.LocalArgus
import com.blazeinchue.argus.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The shell's colours for one mode (made from [SpacePalette]). Dark = the space view; light = the light ARGUS variant. */
@Immutable
data class SpaceLook(
    val dark: Boolean,
    val ground: Color, val card: Color, val tile: Color, val hair: Color, val track: Color,
    val ink: Color, val sub: Color, val accent: Color,
    val selectedFill: Color, val selectedInk: Color,
    val primaryFill: Color, val primaryInk: Color,
) {
    companion object {
        val Dark = SpaceLook(true,
            ground = Color(SpacePalette.OBSIDIAN), card = Color(SpacePalette.CARD_DARK), tile = Color(SpacePalette.TILE_DARK),
            hair = Color(SpacePalette.HAIR_DARK), track = Color(SpacePalette.TRACK_DARK),
            ink = Color(SpacePalette.WHITE), sub = Color(SpacePalette.STEEL), accent = Color(SpacePalette.ELECTRIC_TEXT),
            selectedFill = Color(SpaceTabs.selectedFill(true)), selectedInk = Color(SpaceTabs.selectedInk(true)),
            primaryFill = Color(SpacePalette.WHITE), primaryInk = Color(SpacePalette.INK_LIGHT))
        val Light = SpaceLook(false,
            ground = Color(SpacePalette.GROUND_LIGHT), card = Color(SpacePalette.CARD_LIGHT), tile = Color(SpacePalette.TILE_LIGHT),
            hair = Color(SpacePalette.HAIR_LIGHT), track = Color(SpacePalette.TRACK_LIGHT),
            ink = Color(SpacePalette.INK_LIGHT), sub = Color(SpacePalette.SUB_LIGHT), accent = Color(SpacePalette.BLUE_LIGHT),
            selectedFill = Color(SpaceTabs.selectedFill(false)), selectedInk = Color(SpaceTabs.selectedInk(false)),
            primaryFill = Color(SpacePalette.ARGUS_BLUE), primaryInk = Color(SpacePalette.WHITE))

        fun of(dark: Boolean) = if (dark) Dark else Light
    }
}

/** The look for the app's current light / dark mode. */
@Composable
fun spaceLook(): SpaceLook = SpaceLook.of(LocalArgus.current.dark)

/** Hark's two families: a serif for display headlines, the app's grotesk (Inter, the Aeonik stand-in) for everything else. */
object SpaceType {
    val Serif: FontFamily = FontFamily.Serif
    fun headline(size: TextUnit, color: Color) = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = size, lineHeight = size * 1.15f, color = color)
    fun body(size: TextUnit, color: Color, weight: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = Type.Sans, fontWeight = weight, fontSize = size, lineHeight = size * 1.35f, color = color)
}

object SpaceShapes {
    val card = RoundedCornerShape(28.dp)
    val tile = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(50)
}

/**
 * The space image, decoded once off the main thread at the screen's size (RGB_565, power-of-two sampling; low-RAM phones
 * take half) and kept while the process lives. Null until decoded, and when decoding fails: the backdrop then draws its
 * own black-to-blue sky with the limb, so nothing ever waits on it.
 */
object SpaceImageCache {
    @Volatile private var cached: Pair<Int, ImageBitmap>? = null

    /** The image if it was decoded already (the first frame after that draws it at once). */
    fun get0(): ImageBitmap? = cached?.second

    // 0.52.3 CI fix: one decode at a time. The root backdrop and the onboarding's (forceDark) backdrop used to start two
    // decodes of the same 1080 x 2400 image at once, giving two bitmaps (two texture uploads) on two later frames.
    private val lock = Any()

    fun get(ctx: Context, screenW: Int, screenH: Int): ImageBitmap? {
        val lowRam = try { ctx.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true } catch (_: Throwable) { false }
        val sample = SpaceImage.sample(screenW, screenH, lowRam)
        cached?.let { (s, b) -> if (s <= sample) return b }
        synchronized(lock) {
            cached?.let { (s, b) -> if (s <= sample) return b }
            return try {
                val o = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
                BitmapFactory.decodeResource(ctx.resources, com.blazeinchue.argus.R.drawable.argus_space, o)
                    ?.also { it.prepareToDraw() }?.asImageBitmap()?.also { cached = sample to it }
            } catch (t: Throwable) { com.blazeinchue.argus.device.CrashLog.quiet("SpaceImageCache", t); null }
        }
    }

    /**
     * 0.52.3 CI fix (low-RAM smoke, release cold start 1850 ms): StartWarm calls this on its background thread before the
     * first Activity exists, so the space view is already decoded when the first frame is composed ([get0] is non-null)
     * and the bitmap is drawn IN the first frame. Before, it arrived one or more frames later, and that later frame's
     * texture upload + new SwiftShader pipeline held the main thread outside the one first-frame render wait the gate excludes.
     */
    fun prefetch(ctx: Context) {
        val m = ctx.resources.displayMetrics
        get(ctx, m.widthPixels, m.heightPixels)
    }
}

/**
 * The app's background. Dark: the space view (the Earth's blue limb on a black sky) cropped to fill, under a scrim so
 * text on the cards keeps its contrast. Light: the light ARGUS ground with a soft Argus Blue limb at the bottom.
 * [forceDark] keeps the space view in light mode too (the onboarding).
 */
@Composable
fun SpaceBackdrop(modifier: Modifier = Modifier, forceDark: Boolean = false, content: @Composable BoxScope.() -> Unit = {}) {
    val look = spaceLook()
    val dark = forceDark || look.dark
    val ctx = LocalContext.current
    val metrics = ctx.resources.displayMetrics
    val img by produceState<ImageBitmap?>(if (dark) SpaceImageCache.get0() else null, dark) {
        if (dark) value = withContext(Dispatchers.IO) { SpaceImageCache.get(ctx, metrics.widthPixels, metrics.heightPixels) }
    }
    Box(modifier.fillMaxSize().testTag(SpaceTags.BACKDROP)) {
        Canvas(Modifier.fillMaxSize()) {
            if (dark) {
                val b = img
                if (b != null) {
                    // cover: scale to fill, keep the limb at the bottom
                    val s = maxOf(size.width / b.width, size.height / b.height)
                    val w = b.width * s; val h = b.height * s
                    drawImage(b, dstOffset = IntOffset(((size.width - w) / 2f).toInt(), (size.height - h).toInt()), dstSize = IntSize(w.toInt() + 1, h.toInt() + 1))
                } else {
                    drawRect(Brush.verticalGradient(listOf(Color(SpacePalette.OBSIDIAN), Color(0xFF000814), Color(0xFF00143D))))
                    drawLimb(Color(SpacePalette.ELECTRIC), 0.9f)
                }
                drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = SpacePalette.scrimAlpha(true)), Color.Black.copy(alpha = SpacePalette.scrimAlpha(true) * 0.6f))))
            } else {
                drawRect(Brush.verticalGradient(listOf(Color(SpacePalette.GROUND_LIGHT), Color(0xFFE7EDF8))))
                drawLimb(Color(SpacePalette.ARGUS_BLUE), 0.35f)
            }
        }
        content()
    }
}

/** A planet's limb low on the screen: a wide arc with a soft glow (the fallback sky, and the light ground's accent). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLimb(color: Color, strength: Float) {
    val r = size.width * 1.35f
    val c = Offset(size.width / 2f, size.height * 0.80f + r)
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.28f * strength), Color.Transparent), center = Offset(c.x, c.y - r), radius = size.width * 0.9f),
        radius = size.width * 0.9f, center = Offset(c.x, c.y - r))
    drawArc(color.copy(alpha = 0.9f * strength), 200f, 140f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(width = 2.2f * density))
}

/** Hark's large rounded card. */
@Composable
fun SpaceCard(modifier: Modifier = Modifier, tag: String? = null, onClick: (() -> Unit)? = null, onClickLabel: String? = null,
              content: @Composable ColumnScope.() -> Unit) {
    val look = spaceLook()
    Column(modifier.fillMaxWidth().clip(SpaceShapes.card).background(look.card)
        .let { if (tag != null) it.testTag(tag) else it }
        .let { if (onClick != null) it.clickable(onClickLabel = onClickLabel, onClick = onClick) else it }
        .padding(horizontal = 22.dp, vertical = 20.dp), content = content)
}

/** A card's serif headline ("hark can do more" in the reference). */
@Composable
fun SpaceHeadline(text: String, modifier: Modifier = Modifier, size: TextUnit = 30.sp, color: Color = spaceLook().ink) {
    Text(text, modifier = modifier.semantics { heading() }, style = SpaceType.headline(size, color))
}

/** Hark's full-width pill button ("+ Create a new panel"). [primary] = white (dark) / Argus Blue (light), else the card fill. */
@Composable
fun SpacePillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, plus: Boolean = false,
                    tag: String? = null, enabled: Boolean = true) {
    val look = spaceLook()
    val fill = if (primary) look.primaryFill else look.card
    val ink = if (primary) look.primaryInk else look.ink
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clip(SpaceShapes.pill).background(if (enabled) fill else fill.copy(alpha = 0.5f))
        .let { if (tag != null) it.testTag(tag) else it }
        .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
        .semantics { role = Role.Button }
        .padding(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        if (plus) { PlusMark(ink, 20.dp); Spacer(Modifier.width(10.dp)) }
        Text(label, style = SpaceType.body(17.sp, ink, FontWeight.Medium))
    }
}

/** Hark's white action chip under a message ("Connect Google"). */
@Composable
fun SpaceChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = spaceLook()
    Box(modifier.clip(SpaceShapes.pill).background(look.primaryFill).clickable(onClickLabel = label, onClick = onClick)
        .semantics { role = Role.Button }.padding(horizontal = 18.dp, vertical = 11.dp)) {
        Text(label, style = SpaceType.body(15.sp, look.primaryInk, FontWeight.Medium))
    }
}

/** The round buttons of the top bar (+, search, menu) and the avatar's frame. */
@Composable
fun SpaceRoundButton(desc: String, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String? = null, size: androidx.compose.ui.unit.Dp = 52.dp,
                     fill: Color = spaceLook().track, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.size(size).clip(CircleShape).background(fill)
        .let { if (tag != null) it.testTag(tag) else it }
        .clickable(onClickLabel = desc, onClick = onClick)
        .semantics { contentDescription = desc; role = Role.Button }, contentAlignment = Alignment.Center, content = content)
}

/** A thin "+". */
@Composable
fun PlusMark(color: Color, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val sw = 2.dp.toPx()
        drawLine(color, Offset(w / 2, w * 0.08f), Offset(w / 2, w * 0.92f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(w * 0.08f, w / 2), Offset(w * 0.92f, w / 2), sw, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** A chevron pointing right (profile rows). */
@Composable
fun ChevronMark(color: Color, size: androidx.compose.ui.unit.Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val sw = 2.2.dp.toPx()
        drawLine(color, Offset(w * 0.35f, w * 0.18f), Offset(w * 0.68f, w * 0.5f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(w * 0.68f, w * 0.5f), Offset(w * 0.35f, w * 0.82f), sw, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** Three lines (the Projects menu button). */
@Composable
fun MenuMark(color: Color, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val sw = 2.dp.toPx(); val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawLine(color, Offset(w * 0.12f, w * 0.25f), Offset(w * 0.88f, w * 0.25f), sw, cap)
        drawLine(color, Offset(w * 0.12f, w * 0.5f), Offset(w * 0.7f, w * 0.5f), sw, cap)
        drawLine(color, Offset(w * 0.12f, w * 0.75f), Offset(w * 0.5f, w * 0.75f), sw, cap)
    }
}

/** A plain section gap. */
@Composable
fun SpaceGap(h: androidx.compose.ui.unit.Dp = 14.dp) = Spacer(Modifier.height(h))
