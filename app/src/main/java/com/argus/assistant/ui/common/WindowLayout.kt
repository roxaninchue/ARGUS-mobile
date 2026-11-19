package com.argus.assistant.ui.common

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Landscape overhaul (15 Nov 2026, owner: "a real usable interface in all rotations"): the pure rules every screen uses
 * to fit a phone on its side (~800x360 dp), a tablet or an open foldable (~1280x800 dp), while a phone held upright
 * (~360-430 dp wide) gets exactly what it had. Plain Kotlin, so WindowLayoutTest checks it on the JVM; the composables
 * that read the window are in WindowLayoutUi.kt.
 *
 * Breakpoints follow Material's window size classes (width 600 / 840 dp, height 480 dp).
 */
object WindowLayout {
    /** Width classes (Material: compact < 600 <= medium < 840 <= expanded). */
    const val MEDIUM_W = 600f
    const val EXPANDED_W = 840f

    /** Below this height the window is "short": a phone on its side. Chrome must shrink and content must scroll. */
    const val SHORT_H = 480f

    /** Readable widths: lists and settings rows stop growing here and sit centred. */
    const val READABLE_DP = 720f
    const val FORM_DP = 560f
    /** A two-pane split needs at least this much width. */
    const val TWO_PANE_MIN_W = 700f
    /** The list side of a list-detail split (the detail takes the rest). */
    const val LIST_PANE_DP = 360f

    enum class Width { COMPACT, MEDIUM, EXPANDED }

    data class Shape(val widthDp: Float, val heightDp: Float) {
        val width: Width get() = widthClass(widthDp)
        val landscape: Boolean get() = widthDp > heightDp
        /** A phone on its side (or any window under [SHORT_H] tall). */
        val short: Boolean get() = heightDp < SHORT_H
        /** Upright phone: nothing changes for it. */
        val portraitPhone: Boolean get() = width == Width.COMPACT && !short
    }

    fun widthClass(widthDp: Float): Width = when {
        widthDp >= EXPANDED_W -> Width.EXPANDED
        widthDp >= MEDIUM_W -> Width.MEDIUM
        else -> Width.COMPACT
    }

    fun isShort(heightDp: Float): Boolean = heightDp < SHORT_H

    /**
     * The side gutter that centres content of at most [maxDp] in [widthDp], never less than [minDp]. An upright phone
     * (narrower than [maxDp] + 2 x [minDp]) gets [minDp], i.e. its old padding.
     */
    fun gutter(widthDp: Float, maxDp: Float = READABLE_DP, minDp: Float = 0f): Float =
        max(minDp, (widthDp - maxDp) / 2f)

    /** Content width after [gutter]. */
    fun contentWidth(widthDp: Float, maxDp: Float = READABLE_DP): Float = min(widthDp, maxDp).coerceAtLeast(0f)

    /** Whether a list and its detail sit side by side (else the detail opens over the list as before). */
    fun twoPane(widthDp: Float, heightDp: Float): Boolean = widthDp >= TWO_PANE_MIN_W && widthDp > heightDp * 0.9f

    /** List pane width for a split of [widthDp]: [LIST_PANE_DP], but never more than 45 % of the window. */
    fun listPaneWidth(widthDp: Float): Float = min(LIST_PANE_DP, widthDp * 0.45f)

    /**
     * A hero screen (logo / headline beside its action) goes side by side when the window is short and wide enough:
     * stacking a 200 dp logo over a headline and a button can't fit 360 dp.
     */
    fun sideBySide(widthDp: Float, heightDp: Float): Boolean = isShort(heightDp) && widthDp >= MEDIUM_W && widthDp > heightDp

    /**
     * Columns for a grid whose upright-phone layout has [base] columns at [refWidthDp]: keeps tiles about the same size
     * as the window widens, so a phone on its side shows ~2x the tiles instead of giant ones. Never below [base], never
     * above [maxCols].
     */
    fun gridColumns(base: Int, widthDp: Float, refWidthDp: Float = 400f, maxCols: Int = 10): Int {
        if (base <= 0) return 1
        if (widthDp <= refWidthDp) return base
        val n = (base * widthDp / refWidthDp).roundToInt()
        return n.coerceIn(base, max(base, maxCols))
    }

    /** Columns for cards at least [minCardDp] wide (plus [gapDp] between them) in [widthDp], 1..[maxCols]. */
    fun cardColumns(widthDp: Float, minCardDp: Float, gapDp: Float = 10f, maxCols: Int = 4): Int {
        if (minCardDp <= 0f) return 1
        val n = ((widthDp + gapDp) / (minCardDp + gapDp)).toInt()
        return n.coerceIn(1, max(1, maxCols))
    }

    /**
     * The tallest a bottom sheet may be: the window minus the status bar and a small gap, so its title never slides under
     * the status bar or the cutout on a short window. Never below [floorDp] (a tiny window still shows something).
     */
    fun sheetMaxHeight(windowHeightDp: Float, statusTopDp: Float, gapDp: Float = 8f, floorDp: Float = 160f): Float =
        max(floorDp, windowHeightDp - statusTopDp - gapDp)

    /**
     * A fixed body cap a sheet was designed with ([designedDp], e.g. 360 for the plan list) on a window of
     * [windowHeightDp]: kept on an upright phone, shrunk on a short window so the title, the body and the action row
     * still fit together ([chromeDp] = handle + title + actions).
     */
    fun sheetBodyMax(designedDp: Float, windowHeightDp: Float, statusTopDp: Float, chromeDp: Float = 140f, floorDp: Float = 120f): Float =
        min(designedDp, max(floorDp, sheetMaxHeight(windowHeightDp, statusTopDp) - chromeDp))

    /** The Desk / Jobs style page header: title and chips on one row when the window is short. */
    fun compactHeader(heightDp: Float): Boolean = isShort(heightDp)

    /** The empty-chat greeting's orb: smaller on a short window so greeting and asks fit. */
    fun heroOrbDp(heightDp: Float, normal: Float = 72f): Float = if (isShort(heightDp)) normal * 0.6f else normal
}
