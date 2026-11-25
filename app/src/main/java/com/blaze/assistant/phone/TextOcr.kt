package com.blaze.assistant.phone

import android.graphics.Bitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 0.55.11: on-device OCR (ML Kit, bundled Latin model: no network) for the camera's "read text". It used to live in
 * Magic Hand (MagicHandScreen / MagicHandOcr), which went with phone control; the camera keeps only this part.
 * [keep] is pure (TextOcrTest).
 */
object TextOcr {
    /** A rectangle in pixels. */
    data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
        val w: Int get() = r - l
        val h: Int get() = b - t
        val cy: Int get() = (t + b) / 2
        fun isEmpty() = w <= 0 || h <= 0
    }

    /** One OCR line and its bounding box. */
    data class Line(val box: Box, val text: String)

    /** Fraction (0..1) of [line]'s area inside [region]. An empty line box counts as outside. */
    fun insideFraction(line: Box, region: Box): Float {
        if (line.isEmpty()) return 0f
        val l = maxOf(line.l, region.l); val t = maxOf(line.t, region.t)
        val r = minOf(line.r, region.r); val b = minOf(line.b, region.b)
        if (r <= l || b <= t) return 0f
        return ((r - l).toLong() * (b - t)).toFloat() / (line.w.toLong() * line.h).toFloat()
    }

    /** Lines mostly inside [region], top to bottom then left to right, joined with newlines. */
    fun keep(lines: List<Line>, region: Box, minInside: Float): String =
        lines.filter { it.text.isNotBlank() && insideFraction(it.box, region) >= minInside }
            .sortedWith(compareBy<Line>({ rowOf(it, lines) }, { it.box.l }))
            .joinToString("\n") { it.text.trim() }

    /** Lines whose vertical centres are within half a line height share a row (ML Kit can split one row in two). */
    private fun rowOf(l: Line, all: List<Line>): Int {
        val h = maxOf(1, l.box.h)
        return all.filter { kotlin.math.abs(it.box.cy - l.box.cy) <= h / 2 }.minOf { it.box.t }
    }

    @Volatile private var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
    private val recLock = Any()
    private fun rec(): com.google.mlkit.vision.text.TextRecognizer = recognizer ?: synchronized(recLock) {
        recognizer ?: com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
    }

    /** Every text line ML Kit finds in [bmp], with its box. Empty on any failure. */
    suspend fun lines(bmp: Bitmap): List<Line> = suspendCancellableCoroutine { cont ->
        try {
            rec().process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { r ->
                    val out = r.textBlocks.flatMap { blk -> blk.lines.mapNotNull { ln ->
                        val bb = ln.boundingBox ?: return@mapNotNull null
                        Line(Box(bb.left, bb.top, bb.right, bb.bottom), ln.text.orEmpty())
                    } }
                    if (cont.isActive) cont.resume(out)
                }
                .addOnFailureListener { if (cont.isActive) cont.resume(emptyList()) }
        } catch (_: Throwable) { if (cont.isActive) cont.resume(emptyList()) }
    }

    /** All the text in [bmp], in reading order. */
    suspend fun readAll(bmp: Bitmap): String = keep(lines(bmp), Box(0, 0, bmp.width, bmp.height), 0.01f)
}
