package com.blaze.assistant.ui.living

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Living UI (docs/overhaul/overhaul.md §1): "show, don't tell". The work ARGUS does in the main chat drawn as a canvas
 * that grows while it works: your message at the root, a thin trunk with rounded elbows into each step card (pages it
 * reads, files it touches, commands it runs) and the answer at the end.
 *
 * This file holds what the other Living files share and that needs no Android: the Living UI version (its own number,
 * Finn's rule until v0.5), the light and dark palettes, the dot grid, the zoom limits and the Chat | Living state.
 *
 * Chat | Living: a switch over the chatbox in Chat mode. Chat is the default and is never saved, so the app always opens
 * on Chat; Living draws in Chat's place and Back returns to Chat. Living never replaces Chat (Finn, 14 Nov 2026).
 *
 * Pure Kotlin (LivingLayoutTest checks the grid and the state).
 */
object LivingSpec {
    /** Living UI's own version (v0.xz.y: a minor update is y + 1; a major one moves x and/or z). */
    const val VERSION = "0.2.2"

    enum class View { CHAT, LIVING }

    private val _view = MutableStateFlow(View.CHAT)
    /** What Chat mode shows. Starts on Chat on every app start (in memory only). */
    val view: StateFlow<View> = _view.asStateFlow()

    fun show(v: View) { _view.value = v }

    /**
     * 0.2.1: the landscape Living chrome (Finn's landscape mockup): a slim left rail in place of the top bars and the
     * Chat | Living switch, one header line on the canvas, the composer floating at the bottom. Only for Living on the
     * main chat (or a private chat) in the ARGUS shell, with no view (Design, Gallery…) open; portrait never changes.
     */
    fun railOn(landscape: Boolean, argus: Boolean, living: Boolean, mainChat: Boolean, hasMessages: Boolean, viewOpen: Boolean): Boolean =
        landscape && argus && living && mainChat && hasMessages && !viewOpen

    /** Rail width (dp), the landscape header line's height and the floating composer's room at the bottom (dp). */
    const val RAIL_DP = 64
    const val WIDE_TOP_DP = 60f
    const val WIDE_BOTTOM_DP = 84f
    /** The floating composer's widest (dp). */
    const val FLOAT_COMPOSER_MAX_DP = 560

    /** Back on Living returns to Chat. True = Back was used here. */
    fun back(): Boolean = if (_view.value == View.LIVING) { _view.value = View.CHAT; true } else false

    // ------------------------------------------------------------------ canvas
    /** Dot grid spacing on the canvas (dp at zoom 1) and the dot radius (dp, screen size: dots never grow with zoom). */
    const val GRID_DP = 22f
    const val DOT_DP = 1.1f
    /** The grid thins itself out (doubles its step) until no more than this many dots are drawn. */
    const val MAX_DOTS = 2_400

    const val MIN_ZOOM = 0.25f
    const val MAX_ZOOM = 3f

    /** The newest turns drawn, and the cap on cards (older turns drop first). */
    const val TURNS_SHOWN = 3
    const val MAX_CARDS = 48

    /** Grid step in px for this zoom: [GRID_DP] × density × zoom, doubled until the viewport holds ≤ [MAX_DOTS] dots. */
    fun gridStepPx(viewW: Float, viewH: Float, density: Float, zoom: Float): Float {
        var step = GRID_DP * density * zoom
        if (step <= 0f || viewW <= 0f || viewH <= 0f) return GRID_DP * density
        while ((viewW / step + 1f) * (viewH / step + 1f) > MAX_DOTS) step *= 2f
        return step
    }

    fun clampZoom(z: Float): Float = z.coerceIn(MIN_ZOOM, MAX_ZOOM)

    // ------------------------------------------------------------------ colours (ARGB)
    data class Palette(
        val canvas: Long, val dot: Long, val card: Long, val cardEdge: Long, val shadow: Long,
        val ink: Long, val mute: Long, val link: Long, val bar: Long, val code: Long, val lineNo: Long,
        val ok: Long, val fail: Long, val orb: Long, val pill: Long, val pillEdge: Long, val note: Long,
    )

    /** Soft light-grey canvas, white rounded cards, soft shadow, thin grey connectors (Finn's light reference). */
    val LIGHT = Palette(
        canvas = 0xFFEEF0F3, dot = 0x2E3C4150, card = 0xFFFFFFFF, cardEdge = 0x14000000, shadow = 0x1F1B2233,
        ink = 0xFF16181D, mute = 0xFF5F6470, link = 0xFFB4B9C3, bar = 0xFFF1F3F6, code = 0xFF22262E, lineNo = 0xFF9AA0AB,
        ok = 0xFF128A59, fail = 0xFFC0272D, orb = 0xFF3D6BFF, pill = 0xEBFFFFFF, pillEdge = 0x1A000000, note = 0xFFFFF6D9,
    )

    /** Near-black canvas, dark cards, faint dots. */
    val DARK = Palette(
        canvas = 0xFF0E0F12, dot = 0x30FFFFFF, card = 0xFF1A1C21, cardEdge = 0x1FFFFFFF, shadow = 0x66000000,
        ink = 0xFFECEDF0, mute = 0xFFA6A6AE, link = 0xFF3A3E47, bar = 0xFF24272E, code = 0xFFD9DCE2, lineNo = 0xFF6E737E,
        ok = 0xFF4CC38A, fail = 0xFFFF8085, orb = 0xFF86A3FF, pill = 0xE623262C, pillEdge = 0x24FFFFFF, note = 0xFF3A3420,
    )

    fun palette(dark: Boolean): Palette = if (dark) DARK else LIGHT
}
