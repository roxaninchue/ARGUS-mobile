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
 * 0.2.0, two columns per turn (Finn's mockups, 15 Nov 2026):
 *  - left ([LEFT_X], [LEFT_W]): your prompt ("You asked"), then the pages ARGUS read stacked under it, a down arrow
 *    from the prompt into the first page and between pages;
 *  - right ([RIGHT_X], [RIGHT_W]): the answer first when the turn ended on it (headline style), then files and the
 *    steps checklist in the order they happened;
 *  - connectors: from the prompt across into the right column, and from the last page of the chain into the answer.
 * The next turn starts under the taller column.
 *
 * Pure: LivingLayoutTest.
 */
object LivingLayout {
    const val LEFT_X = 0f
    const val LEFT_W = 240f
    const val PROMPT_W = LEFT_W
    const val COL_GAP = 40f
    const val RIGHT_X = LEFT_X + LEFT_W + COL_GAP
    const val RIGHT_W = 264f
    /** The gutter between the columns where the connectors run down. */
    const val GUTTER_X = LEFT_X + LEFT_W + COL_GAP / 2f
    const val GAP_Y = 30f
    const val TURN_GAP = 56f
    /** Corner radius of a connector's elbow. */
    const val ELBOW = 12f

    data class Box(val card: Card, val x: Float, val y: Float, val w: Float, val h: Float) {
        val key: String get() = card.key
        val right: Float get() = x + w
        val bottom: Float get() = y + h
        val midX: Float get() = x + w / 2f
    }

    /** A connector: a polyline (x0, y0, x1, y1, …) drawn with rounded elbows; [arrow] = an arrowhead at its end. */
    class Link(val pts: FloatArray, val arrow: Boolean = false)

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

    const val HEAD_LINES = 3
    const val BODY_LINES = 8
    const val HEAD_MAX = 140

    /** The answer as a headline (its first sentence, or first line) and the rest. The screen draws exactly this. */
    fun headline(text: String): Pair<String, String> {
        val t = text.trim()
        if (t.isEmpty()) return "" to ""
        val line = t.substringBefore('\n').trim()
        val end = Regex("[.!?](\\s|$)").find(line)?.range?.first
        return when {
            end != null && end < HEAD_MAX -> line.substring(0, end + 1) to t.substring(end + 1).trim()
            line.length <= HEAD_MAX -> line to t.substring(line.length).trim()
            else -> LivingModel.clip(line, HEAD_MAX) to t.substring(HEAD_MAX - 1).trim()
        }
    }

    /** Each card's height (dp). The screen draws cards at exactly this size, so layout and drawing agree. */
    fun height(c: Card): Float = when (c.kind) {
        Kind.PROMPT -> 46f + textLines(c.text, 28, 5) * 20f
        Kind.BROWSER -> 228f
        Kind.FILE -> 52f + max(1, c.lines.size) * 17f + 12f
        Kind.LOG -> 46f + max(1, c.rows.size) * ROW_H + c.rows.count { result(it) != null } * RESULT_H + 10f
        Kind.ANSWER -> {
            val (h, r) = headline(c.text)
            46f + textLines(h, 24, HEAD_LINES) * 23f + (if (r.isBlank()) 0f else 6f + textLines(r, 36, BODY_LINES) * 18f) + 8f
        }
    }

    const val ROW_H = 26f
    const val RESULT_H = 20f

    /** A command's result line under it in the steps card (`200 OK`, "Container gatus started"), or null. */
    fun result(r: LivingModel.Row): String? =
        if (!r.command) null else r.output?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.let { LivingModel.clip(it, 80) }

    fun width(c: Card): Float = when (c.kind) { Kind.PROMPT, Kind.BROWSER -> LEFT_W; else -> RIGHT_W }

    /** Where a connector meets a card's side: a little under its top, so links line up across the columns. */
    private fun port(b: Box): Float = b.y + min(28f, b.h / 2f)

    // ------------------------------------------------------------------ the two columns
    fun plan(board: Board): Plan {
        val boxes = ArrayList<Box>()
        val links = ArrayList<Link>()
        var y = 0f
        var maxR = 0f
        for (turn in board.turns) {
            val cards = turn.cards
            if (cards.isEmpty()) continue
            val top = y
            val prompt = cards.first()
            val pb = Box(prompt, LEFT_X, top, width(prompt), height(prompt))
            boxes += pb

            // left: the page chain under the prompt, a down arrow into each page
            var ly = pb.bottom
            var above = pb
            for (c in cards.drop(1)) {
                if (c.kind != Kind.BROWSER) continue
                val b = Box(c, LEFT_X, ly + GAP_Y, width(c), height(c))
                boxes += b
                links += Link(floatArrayOf(above.midX, above.bottom, b.midX, b.y), arrow = true)
                above = b
                ly = b.bottom
            }
            val lastPage = above.takeIf { it !== pb }

            // right: the answer first when the turn ended on it, then files and steps as they happened
            val rest = cards.drop(1).filter { it.kind != Kind.BROWSER }
            val answer = rest.lastOrNull()?.takeIf { it.kind == Kind.ANSWER && it === cards.last() }
            val right = if (answer != null) listOf(answer) + rest.dropLast(1) else rest
            var ry = top
            var prev: Box? = null
            var first: Box? = null
            var ab: Box? = null
            for (c in right) {
                val b = Box(c, RIGHT_X, if (prev == null) top else ry + GAP_Y, width(c), height(c))
                boxes += b
                if (prev != null) links += Link(floatArrayOf(prev.x + 24f, prev.bottom, b.x + 24f, b.y))
                if (first == null) first = b
                if (c === answer) ab = b
                prev = b
                ry = b.bottom
                maxR = max(maxR, b.right)
            }
            // prompt → the right column, page chain → the answer
            first?.let { f -> links += Link(floatArrayOf(pb.right, port(pb), GUTTER_X, port(pb), GUTTER_X, port(f), f.x, port(f)), arrow = true) }
            if (lastPage != null && ab != null) {
                val enter = min(ab.bottom - 12f, port(ab) + 22f)
                links += Link(floatArrayOf(lastPage.right, port(lastPage), GUTTER_X, port(lastPage), GUTTER_X, enter, ab.x, enter), arrow = true)
            }
            maxR = max(maxR, pb.right)
            y = max(ly, ry) + TURN_GAP
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
     * Follow the newest work at the reading zoom (1×, or less on a narrow screen): [b]'s column fills the width and the
     * other column peeks in from the side (Finn's mockup); [b]'s bottom sits in view, so the work grows up the screen.
     */
    fun follow(b: Box, density: Float, viewW: Float, viewH: Float, padPx: Float, topPx: Float, bottomPx: Float): Camera {
        val s = LivingSpec.clampZoom(min(1f, (viewW - 2 * padPx) / (b.w * density)))
        val ox = (viewW - b.w * density * s) / 2f - b.x * density * s
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
