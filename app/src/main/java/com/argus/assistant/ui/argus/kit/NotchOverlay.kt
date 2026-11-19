package com.argus.assistant.ui.argus.kit

/**
 * 2026-11-19 (owner spec, feat/notch-glance): the notch overlay's rules, pure so NotchOverlayTest runs them on the JVM.
 * When the notch opens, the mode pill drops and the page behind it is softly blurred (or scrimmed) with the glance
 * layer on top (the glance package's Glance).
 *
 *  - a DIFFERENT mode in the pill: unchanged from P1-SH-01, the pill closes and the shell switches to that mode;
 *  - the SAME mode as the current one (in Chat, tap Chat): the pill closes and the page goes back to its normal state
 *    (an open view and Spotlight close; the mode stays). This was already how the shell behaved; it is named here;
 *  - Back (the button or the edge swipe) while the pill is open, from ANY mode (Team, Chat, Life, Desk): the overlay
 *    closes and the shell goes to the regular Chat, not that mode's own page (owner, round 2, 19 Nov; round 1 had Back
 *    = tapping the current mode);
 *  - the glance page's X: the same as Back; its badge (the top bar's avatar): the overlay closes and Settings opens;
 *  - a tap outside the pill and the glance items: the pill closes, nothing else changes.
 */
object NotchOverlay {
    /**
     * What the shell does after a step: nothing, back to the normal page of the current mode, switch mode, go to the
     * regular Chat (whatever the mode), or open Settings.
     */
    enum class Effect { NONE, NORMAL, SWITCH, CHAT, SETTINGS }

    /** The Chat mode's id (ShellSpec.MODES / ChatNav.CHAT). */
    const val CHAT = "chat"

    data class Step(val open: Boolean, val effect: Effect, val mode: String)

    fun toggle(open: Boolean, current: String): Step = Step(!open, Effect.NONE, current)

    fun tap(current: String, tapped: String): Step =
        if (tapped == current) Step(false, Effect.NORMAL, current) else Step(false, Effect.SWITCH, tapped)

    /** Back while open, from any mode, closes it and goes to the regular Chat; while closed it is not the overlay's. */
    fun back(open: Boolean, current: String): Step = if (open) close() else Step(false, Effect.NONE, current)

    /** The glance page's X: close the overlay and go to the regular Chat. */
    fun close(): Step = Step(false, Effect.CHAT, CHAT)

    /** The glance page's badge: close the overlay and open Settings (the top bar's badge does the same). */
    fun me(current: String): Step = Step(false, Effect.SETTINGS, current)

    fun outside(current: String): Step = Step(false, Effect.NONE, current)

    // ---------------------------------------------------------------- the backdrop

    enum class Backdrop { BLUR, SCRIM }

    /** Light blur (Haze) under the pill: well under the Chrome glass's 24 dp, so the page still reads as "there". */
    const val BLUR_DP = 12
    /** The slight scrim over the blur (ink at this alpha). */
    const val BLUR_SCRIM = 0.10f
    /** The plain scrim when the phone can't blur or Reduced motion is on: a touch stronger, since nothing is blurred. */
    const val PLAIN_SCRIM = 0.32f
    /** Fade of the backdrop and glance layer (0 with Reduced motion). */
    const val FADE_MS = 180

    /** Blur only where ArgusGlass would blur (API 31+, not low-RAM, thermal OK) and Reduced motion is off. */
    fun backdrop(blurOk: Boolean, reducedMotion: Boolean): Backdrop = if (blurOk && !reducedMotion) Backdrop.BLUR else Backdrop.SCRIM

    fun scrimAlpha(b: Backdrop): Float = if (b == Backdrop.BLUR) BLUR_SCRIM else PLAIN_SCRIM

    fun fadeMs(reducedMotion: Boolean): Int = if (reducedMotion) 0 else FADE_MS
}
