package com.blaze.assistant.ui.space

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.blaze.assistant.ui.DarkColors
import com.blaze.assistant.ui.LightColors
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MotionTokens

/*
 * One surface for everything that opens over the chat (Spotlight, the views sheet, floating windows): an opaque card,
 * a 55 % scrim behind it that fades on the overlay's own clock, and the ink of the overlay's own theme. Before this,
 * Spotlight drew a forced-dark card but its rows read the app's ink, so on the light theme the titles came out near
 * black on blue-black, and the glass sheets let the bubbles under them read through their rows.
 */
object SpaceOverlay {
    /** The overlay's fill: fully opaque in both themes. */
    fun surface(dark: Boolean): Color = Color(if (dark) SpacePalette.OVERLAY_DARK else SpacePalette.OVERLAY_LIGHT)
    /** The dim behind it. */
    val scrim: Color get() = Color(SpacePalette.OVERLAY_SCRIM)
    /** The same dim as an alpha, for a Dialog window's own dim amount. */
    val scrimAlpha: Float get() = ((SpacePalette.OVERLAY_SCRIM ushr 24) and 0xFFL).toFloat() / 255f
}

/** Clip to [shape], fill with the opaque overlay surface, and edge it with [rim]. */
fun Modifier.overlaySurface(dark: Boolean, shape: Shape, rim: Color): Modifier =
    this.clip(shape).background(SpaceOverlay.surface(dark), shape).border(1.dp, rim, shape)

/**
 * The scrim behind an overlay: fades in and out with it ([MotionTokens.scrimIn] / [MotionTokens.scrimOut]); a tap on
 * it calls [onDismiss], announced to TalkBack as [label].
 */
@Composable
fun SpaceScrim(visible: Boolean, onDismiss: () -> Unit, label: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, enter = MotionTokens.scrimIn(), exit = MotionTokens.scrimOut()) {
        Box(modifier.fillMaxSize().background(SpaceOverlay.scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = label) { onDismiss() })
    }
}

/**
 * Draws [content] with the ink of a [dark] or light overlay, whatever the app's theme is, so shared rows and icon tiles
 * that read LocalBlaze match the surface they sit on. Nothing changes when the app is already in that mode.
 */
@Composable
fun ProvideOverlayInk(dark: Boolean, content: @Composable () -> Unit) {
    val app = LocalBlaze.current
    if (app.dark == dark) content()
    else CompositionLocalProvider(LocalBlaze provides (if (dark) DarkColors else LightColors), content = content)
}
