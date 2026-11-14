package com.blaze.assistant.ui.living

import com.blaze.assistant.ui.living.LivingModel.Board
import com.blaze.assistant.ui.living.LivingModel.Card
import com.blaze.assistant.ui.living.LivingModel.Kind
import kotlin.math.max
import kotlin.math.min

/**
 * Living UI: where each card sits on the canvas (world units = dp at zoom 1), the connectors between them, which
 * cards touch the screen, and the camera (pan, pinch zoom, fit everything, zoom in on a card, follow the newest).
 *
 * On a phone the tree flows down: each turn's prompt at the left edge, a trunk line down its left side and a rounded
 * elbow into every step card, which sit one column in. Page reads in a row (up to [BRANCH]) sit side by side, the
 * later ones peeking in from the right edge (reached by panning). The next turn starts under the last.
 *
 * Pure: LivingLayoutTest.
 */
object LivingLayout {
    const val CARD_W = 300f
    const val PROMPT_W = 280f
    const val INDENT = 36f
    const val TRUNK_X = 18f
    const val GAP_Y = 26f
    const val GAP_X = 24f
    const val TURN_GAP = 56f
    const val BRANCH = 3
    /** Corner radius of a connector's elbow. */
    const val ELBOW = 12f

    data class Box(val card: Card, val x: Float, val y: Float, val w: Float, val h: Float) {
        val key: String get() = card.key
        val right: Float get() = x + w
        val bottom: Float get() = y + h
    }

    /** A connector: a polyline (x0, y0, x1, y1, …) drawn with rounded elbows. */
    class Link(val pts: FloatArray)

    data class Plan(val boxes: List<Box>, val links: List<Link>, val width: Float, val height: Float) {
        fun box(key: String?): Box? = key?.let { k -> boxes.firstOrNull { it.key == k } }
    }

    // ------------------------------------------------------------------ sizes
    private fun textLines(s: String, perLine: Int, cap: Int): Int {
        if (s.isBlank()) return 1
        var n = 0
        for (p in s.lines()) n += max(1, (p.length + perLine - 1) / perLine)
        return min(cap, n)
    }

    /** Each card's height (dp). The screen draws cards at exactly this size, so layout and drawing agree. */
    fun height(c: Card): Float = when (c.kind) {
        Kind.PROMPT -> 46f + textLines(c.text, 34, 5) * 20f
        Kind.BROWSER -> 236f
        Kind.FILE -> 52f + max(1, c.lines.size) * 17f + 12f
        Kind.LOG -> 46f + max(1, c.rows.size) * 26f + 10f
        Kind.ANSWER -> 46f + textLines(c.text, 38, 9) * 19f + 8f
    }

    fun width(c: Card): Float = if (c.kind == Kind.PROMPT) PROMPT_W else CARD_W

    // ------------------------------------------------------------------ the tree
    fun plan(board: Board): Plan {
        val boxes = ArrayList<Box>()
        val links = ArrayList<Link>()
        var y = 0f
        var maxR = 0f
        for (turn in board.turns) {
            val cards = turn.cards
            if (cards.isEmpty()) continue
            val prompt = cards.first()
            val pb = Box(prompt, 0f, y, width(prompt), height(prompt))
            boxes += pb
            maxR = max(maxR, pb.right)
            y = pb.bottom + GAP_Y
            val trunkTop = pb.bottom
            var lastMid = trunkTop
            var i = 1
            while (i < cards.size) {
                val c = cards[i]
                // a run of page reads → one row, side by side
                val row = ArrayList<Card>()
                row += c
                if (c.kind == Kind.BROWSER) {
                    var j = i + 1
                    while (j < cards.size && row.size < BRANCH && cards[j].kind == Kind.BROWSER) { row += cards[j]; j++ }
                }
                var x = INDENT
                var rowH = 0f
                var prev: Box? = null
                for (rc in row) {
                    val b = Box(rc, x, y, width(rc), height(rc))
                    boxes += b
                    rowH = max(rowH, b.h)
                    maxR = max(maxR, b.right)
                    val midY = b.y + min(36f, b.h / 2f)
                    if (prev == null) {
                        // trunk down, rounded elbow into the card's left edge
                        links += Link(floatArrayOf(TRUNK_X, lastMid, TRUNK_X, midY, b.x, midY))
                        lastMid = midY
                    } else {
                        // the branch: from the previous card's right edge across into this one
                        links += Link(floatArrayOf(prev.right, midY, b.x, midY))
                    }
                    prev = b
                    x = b.right + GAP_X
                }
                y += rowH + GAP_Y
                i += row.size
            }
            y += TURN_GAP - GAP_Y
        }
        return Plan(boxes, links, maxR, max(0f, y - TURN_GAP))
    }

    // ------------------------------------------------------------------ camera
    /** screen px = world dp × density × [scale] + ([ox], [oy]). */
    data class Camera(val ox: Float, val oy: Float, val scale: Float) {
        fun sx(wx: Float, density: Float) = wx * density * scale + ox
        fun sy(wy: Float, density: Float) = wy * density * scale + oy
        fun wx(sx: Float, density: Float) = (sx - ox) / (density * scale)
        fun wy(sy: Float, density: Float) = (sy - oy) / (density * scale)
    }

    /** Does [b] touch the screen (with [marginPx] slack)? Cards that don't are not composed. */
    fun visible(b: Box, cam: Camera, density: Float, viewW: Float, viewH: Float, marginPx: Float = 0f): Boolean {
        val l = cam.sx(b.x, density); val t = cam.sy(b.y, density)
        val r = cam.sx(b.right, density); val btm = cam.sy(b.bottom, density)
        return r >= -marginPx && l <= viewW + marginPx && btm >= -marginPx && t <= viewH + marginPx
    }

    fun visibleKeys(plan: Plan, cam: Camera, density: Float, viewW: Float, viewH: Float, marginPx: Float = 0f): Set<String> =
        plan.boxes.filter { visible(it, cam, density, viewW, viewH, marginPx) }.mapTo(LinkedHashSet()) { it.key }

    /** Pan by a screen delta. */
    fun pan(cam: Camera, dx: Float, dy: Float) = cam.copy(ox = cam.ox + dx, oy = cam.oy + dy)

    /** Zoom by [factor] around the screen point ([cx], [cy]), clamped to the zoom limits. */
    fun zoom(cam: Camera, factor: Float, cx: Float, cy: Float): Camera {
        val s = LivingSpec.clampZoom(cam.scale * factor)
        val k = s / cam.scale
        return Camera(cx - (cx - cam.ox) * k, cy - (cy - cam.oy) * k, s)
    }

    /** Everything in view (double tap), with [padPx] round it and [topPx] kept clear for the pill. */
    fun fit(plan: Plan, density: Float, viewW: Float, viewH: Float, padPx: Float, topPx: Float = 0f): Camera {
        if (plan.boxes.isEmpty() || viewW <= 0f || viewH <= 0f) return Camera(padPx, topPx + padPx, 1f)
        val w = plan.width * density
        val h = plan.height * density
        val availW = viewW - 2 * padPx
        val availH = viewH - topPx - 2 * padPx
        val s = LivingSpec.clampZoom(min(min(availW / w, availH / h), 1f))
        val ox = (viewW - w * s) / 2f
        val oy = topPx + padPx + max(0f, (availH - h * s) / 2f)
        return Camera(ox, oy, s)
    }

    /** Zoom in on one card (tap): it fills the width with [padPx] each side, its top under [topPx]. */
    fun focus(b: Box, density: Float, viewW: Float, viewH: Float, padPx: Float, topPx: Float = 0f): Camera {
        val s = LivingSpec.clampZoom(min((viewW - 2 * padPx) / (b.w * density), (viewH - topPx - 2 * padPx) / (b.h * density)).coerceAtMost(2.2f))
        val ox = (viewW - b.w * density * s) / 2f - b.x * density * s
        val oy = topPx + padPx - b.y * density * s
        return Camera(ox, oy, s)
    }

    /**
     * Follow the newest work at the reading zoom (1×, or less on a narrow screen): the main column fits the width and
     * [b]'s bottom sits in view, so the tree grows up the screen.
     */
    fun follow(b: Box, density: Float, viewW: Float, viewH: Float, padPx: Float, topPx: Float, bottomPx: Float): Camera {
        val colW = (INDENT + CARD_W) * density
        val s = LivingSpec.clampZoom(min(1f, (viewW - 2 * padPx) / colW))
        val ox = padPx
        val wantBottom = viewH - bottomPx - padPx
        val bTop = b.y * density * s
        val bBottom = b.bottom * density * s
        val oy = if (bBottom - bTop > wantBottom - topPx - padPx) topPx + padPx - bTop else wantBottom - bBottom
        // never scroll the first card below the top
        return Camera(ox, min(oy, topPx + padPx), s)
    }

    /** The card under a screen point, or null. */
    fun hit(plan: Plan, cam: Camera, density: Float, sx: Float, sy: Float): Box? {
        val wx = cam.wx(sx, density); val wy = cam.wy(sy, density)
        return plan.boxes.lastOrNull { wx >= it.x && wx <= it.right && wy >= it.y && wy <= it.bottom }
    }
}
