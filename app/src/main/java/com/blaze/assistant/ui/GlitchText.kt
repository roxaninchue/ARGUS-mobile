package com.blaze.assistant.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import com.blaze.assistant.agent.GlitchSpans

/**
 * fix/chat-flat-ui (owner, 14 Nov 2026): text whose [spans] are drawn glitched: a red / cyan colour split, thin
 * horizontal slices that jump sideways now and then, and an occasional flicker. The rest of the text is drawn as usual.
 *
 * API 33+: an AGSL [RuntimeShader] on the text's layer does it in one pass ([GLITCH_AGSL]). Older phones: the same look
 * from plain Compose drawing (the glitched rects drawn again, clipped, tinted and shifted). Both follow [GlitchMath].
 * With animations off (Remove animations / animator scale 0, [MotionTokens.enabled]) it is still: the colour split only.
 * Screen readers get the plain text.
 */
@Composable
fun GlitchText(text: String, spans: List<GlitchSpans.Span>, style: TextStyle, modifier: Modifier = Modifier) {
    if (spans.isEmpty()) { Text(text, modifier = modifier, style = style); return }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val rects = remember(layout, spans) { layout?.let { glitchRects(it, spans) }.orEmpty() }
    val motion = MotionTokens.enabled
    val tick: State<Float>? = if (motion) {
        val tr = rememberInfiniteTransition(label = "glitch")
        tr.animateFloat(0f, GlitchMath.LOOP_TICKS.toFloat(),
            infiniteRepeatable(tween(GlitchMath.LOOP_TICKS * 1000 / GlitchMath.TICKS_PER_SECOND, easing = LinearEasing)), label = "glitchTick")
    } else null
    val seed = remember(text, spans) { spans.fold(text.length) { a, s -> a * 31 + s.start } }
    val fx = if (Build.VERSION.SDK_INT >= 33 && rects.isNotEmpty()) shaderLayer(rects, tick, seed, motion) else Modifier
    val fallback = if (Build.VERSION.SDK_INT < 33 && rects.isNotEmpty()) Modifier.glitchDraw(rects, tick, seed, motion) else Modifier
    Text(text, modifier = modifier.then(fx).then(fallback), style = style, onTextLayout = { layout = it })
}

/** The boxes of [spans] on each line they cover, a little taller than the line so the slices have room. */
internal fun glitchRects(l: TextLayoutResult, spans: List<GlitchSpans.Span>): List<Rect> {
    val n = l.layoutInput.text.length
    val out = ArrayList<Rect>()
    for (s in spans) {
        val a = s.start.coerceIn(0, n); val b = s.end.coerceIn(0, n)
        if (b <= a) continue
        val first = l.getLineForOffset(a); val last = l.getLineForOffset(b - 1)
        for (line in first..last) {
            val ls = maxOf(a, l.getLineStart(line)); val le = minOf(b, l.getLineEnd(line, visibleEnd = true))
            if (le <= ls) continue
            val x1 = l.getHorizontalPosition(ls, usePrimaryDirection = true)
            val x2 = if (le >= l.getLineEnd(line, visibleEnd = true)) l.getLineRight(line).let { r -> if (l.getParagraphDirection(ls) == androidx.compose.ui.text.style.ResolvedTextDirection.Rtl) l.getLineLeft(line) else r }
                else l.getHorizontalPosition(le, usePrimaryDirection = true)
            out += Rect(minOf(x1, x2) - 2f, l.getLineTop(line), maxOf(x1, x2) + 2f, l.getLineBottom(line))
        }
    }
    return out
}

/* ---------------- API 33+: AGSL ---------------- */

/** Rects the shader takes at once; more are drawn by the fallback path on top. */
private const val MAX_RECTS = 16

/** The glitch as an AGSL shader over the text's layer. Only pixels inside [rects] change. */
private const val GLITCH_AGSL = """
uniform shader content;
uniform float4 rects[16];
uniform float count;
uniform float tick;
uniform float split;
uniform float jitter;
uniform float flicker;
uniform float sliceH;

float hash(float n) { return fract(sin(n) * 43758.5453); }

half4 main(float2 p) {
    float inside = 0.0;
    for (int i = 0; i < 16; i++) {
        if (float(i) < count) {
            float4 r = rects[i];
            if (p.x >= r.x && p.x <= r.z && p.y >= r.y && p.y <= r.w) { inside = 1.0; }
        }
    }
    if (inside < 0.5) { return content.eval(p); }
    float band = floor(p.y / sliceH);
    float jump = step(0.70, hash(band * 13.7 + tick * 3.1));
    float dx = jump * (hash(band * 7.3 + tick * 1.7) * 2.0 - 1.0) * jitter;
    float2 q = float2(p.x + dx, p.y);
    half4 base = content.eval(q);
    half4 red = content.eval(q + float2(split, 0.0));
    half4 cyan = content.eval(q - float2(split, 0.0));
    half4 ghost = (half4(1.0, 0.18, 0.28, 1.0) * red.a + half4(0.0, 0.9, 1.0, 1.0) * cyan.a) * 0.7;
    ghost = clamp(ghost, 0.0, 1.0);
    half4 b = base * half(1.0 - flicker);
    return b + ghost * (1.0 - b.a);
}
"""

@RequiresApi(33)
@Composable
private fun shaderLayer(rects: List<Rect>, tick: State<Float>?, seed: Int, motion: Boolean): Modifier {
    val shader = remember { try { RuntimeShader(GLITCH_AGSL) } catch (_: Throwable) { null } }
        ?: return Modifier.glitchDraw(rects, tick, seed, motion)   // a driver that can't build it gets the Compose look
    val shown = rects.take(MAX_RECTS)
    val rest = rects.drop(MAX_RECTS)
    val packed = remember(shown) { FloatArray(MAX_RECTS * 4).also { a -> shown.forEachIndexed { i, r -> a[i * 4] = r.left; a[i * 4 + 1] = r.top; a[i * 4 + 2] = r.right; a[i * 4 + 3] = r.bottom } } }
    return Modifier.graphicsLayer {
        val t = tick?.value?.toInt() ?: 0
        val f = GlitchMath.frame(t, seed, motion)
        try {
        shader.setFloatUniform("rects", packed)
        shader.setFloatUniform("count", shown.size.toFloat())
        shader.setFloatUniform("tick", t.toFloat())
        shader.setFloatUniform("split", f.splitDp * density)
        shader.setFloatUniform("jitter", if (motion) GlitchMath.JITTER_DP * density else 0f)
        shader.setFloatUniform("flicker", f.flicker)
        shader.setFloatUniform("sliceH", (shown.firstOrNull()?.height ?: 30f) / GlitchMath.SLICES)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        } catch (_: Throwable) { renderEffect = null }   // never crash a chat row over an effect
    }.let { if (rest.isEmpty()) it else it.glitchDraw(rest, tick, seed + 1, motion) }
}

/* ---------------- older phones: plain Compose ---------------- */

private fun Modifier.glitchDraw(rects: List<Rect>, tick: State<Float>?, seed: Int, motion: Boolean): Modifier = drawWithContent {
    val t = tick?.value?.toInt() ?: 0
    val f = GlitchMath.frame(t, seed, motion)
    val hole = Path().apply { rects.forEach { addRect(it) } }
    // Everything outside the glitched boxes, untouched.
    clipPath(hole, ClipOp.Difference) { this@drawWithContent.drawContent() }
    val split = f.splitDp * density
    rects.forEachIndexed { k, r ->
        ghost(r, Color(0xFFFF2E48), split)
        ghost(r, Color(0xFF00E5FF), -split)
        val h = r.height / GlitchMath.SLICES
        for (i in 0 until GlitchMath.SLICES) {
            val dx = (if (k % 2 == 0) f.sliceDp[i] else -f.sliceDp[GlitchMath.SLICES - 1 - i]) * density
            val top = r.top + i * h
            clipRect(r.left - 8f * density, top, r.right + 8f * density, top + h) {
                translate(left = dx) {
                    if (f.flicker > 0f) drawIntoCanvas { cv ->
                        cv.saveLayer(Rect(r.left - 8f * density, top, r.right + 8f * density, top + h), Paint().apply { alpha = 1f - f.flicker })
                        this@drawWithContent.drawContent(); cv.restore()
                    } else this@drawWithContent.drawContent()
                }
            }
        }
    }
}

/** One colour copy of the text inside [r], tinted [color] and shifted [dx] px. */
private fun ContentDrawScope.ghost(r: Rect, color: Color, dx: Float) {
    drawIntoCanvas { cv ->
        cv.saveLayer(r, Paint().apply { colorFilter = ColorFilter.tint(color, BlendMode.SrcIn); alpha = 0.7f })
        cv.translate(dx, 0f)
        drawContent()
        cv.restore()
    }
}
