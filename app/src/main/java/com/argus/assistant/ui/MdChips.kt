package com.argus.assistant.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult

/**
 * 0.54.2 (owner, 15 Nov 2026: "the text layout looks ugly"): how chat replies lay out inline code and list items.
 * [Md.render] with `rich = true` tags each `code` run ([TAG]) and gives list items a hanging indent ([indentEm]); MdText
 * draws a chip behind each tagged run with [drawCodeChips]. The numbers are pure (MdChipsTest).
 *
 * The chip sits behind the glyphs only: from a little above the code's cap height to just under its descenders, never the
 * whole line box (a span background fills the line box, which made tall square blocks that jammed the lines together).
 */
object MdChips {
    /** String annotation on an inline code run (its two [PAD]s included). */
    const val TAG = "md-code"
    /** Narrow no-break space each side of the code: the chip's side padding, and it never wraps away from the code. */
    const val PAD = "\u202F"
    /** Inline code is a touch smaller than the words around it. */
    const val CODE_EM = 0.88f
    /** Corner radius (dp): a soft chip, consistent with the Argus kit's small radii (checkbox 7, field 12). */
    const val RADIUS_DP = 5f
    /** Chip top above the baseline and bottom below it, in code font sizes. */
    const val TOP_EM = 0.90f
    const val BOTTOM_EM = 0.30f

    /** A light tint, one step over the Argus `soft` fill (white 7 % dark / black 4.5 % light) so code still reads as code. */
    fun fill(dark: Boolean): Color = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)

    /** Top and bottom of a chip on a line whose baseline is [baseline], for code drawn at [codePx]. */
    fun vertical(baseline: Float, codePx: Float): Pair<Float, Float> = (baseline - codePx * TOP_EM) to (baseline + codePx * BOTTOM_EM)

    /** List markers are mono, so their width is known: this many em per character. */
    const val MONO_ADVANCE = 0.6f
    /** Each nesting level indents by this many em. */
    const val LEVEL_EM = 1.2f

    /** First-line and wrapped-line indents (em) of a list item at [depth] whose marker ("• ", "12. ") is [markerChars] long:
     *  the wrapped lines start where the item's text starts. */
    fun indentEm(depth: Int, markerChars: Int): Pair<Float, Float> {
        val first = depth.coerceAtLeast(0) * LEVEL_EM
        return first to first + markerChars * MONO_ADVANCE
    }

    /**
     * The pieces of the run [start, end) per line, as (line, from, to): a run that wraps gets one chip per line. [lineOf]
     * gives an offset's line, [lineStart] / [lineVisibleEnd] a line's first offset and its end without trailing spaces.
     */
    fun segments(start: Int, end: Int, lineOf: (Int) -> Int, lineStart: (Int) -> Int, lineVisibleEnd: (Int) -> Int): List<Triple<Int, Int, Int>> {
        if (end <= start) return emptyList()
        val out = ArrayList<Triple<Int, Int, Int>>()
        var s = start
        var guard = 0
        while (s < end && guard++ < 200) {
            val l = lineOf(s)
            val e = minOf(end, lineVisibleEnd(l))
            if (e > s) out += Triple(l, s, e)
            val next = lineStart(l + 1)
            if (next <= s) break
            s = maxOf(e, next)
        }
        return out
    }

    /** Draws the chips of every [TAG] run of [text] (as laid out in [layout]); [bodyPx] is the reply's font size in px. */
    fun DrawScope.drawCodeChips(layout: TextLayoutResult, text: AnnotatedString, bodyPx: Float, color: Color) {
        val runs = text.getStringAnnotations(TAG, 0, text.length)
        if (runs.isEmpty()) return
        val n = layout.layoutInput.text.length
        val codePx = bodyPx * CODE_EM
        val r = CornerRadius(RADIUS_DP * density)
        for (run in runs) {
            val end = minOf(run.end, n)
            if (end <= run.start) continue
            val parts = try {
                segments(run.start, end, { layout.getLineForOffset(it) },
                    { if (it >= layout.lineCount) n else layout.getLineStart(it) },
                    { layout.getLineEnd(it, visibleEnd = true) })
            } catch (_: Throwable) { emptyList() }
            for ((line, a, b) in parts) {
                val left = layout.getBoundingBox(a).left
                val right = layout.getBoundingBox(b - 1).right
                val (top, bottom) = vertical(layout.getLineBaseline(line), codePx)
                val x = minOf(left, right); val w = kotlin.math.abs(right - left)
                if (w > 0f) drawRoundRect(color, Offset(x, top), Size(w, bottom - top), r)
            }
        }
    }
}
