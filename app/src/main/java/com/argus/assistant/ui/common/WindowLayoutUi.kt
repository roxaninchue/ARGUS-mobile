package com.argus.assistant.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Landscape overhaul: the composable side of [WindowLayout]. Kept apart from `ui/living/` and the chat shell (another
 * agent owns those); any screen may use it. Nothing here changes an upright phone: the gutters are 0 there, the cutout
 * has no horizontal part and the sheet caps sit above any sheet the app draws.
 */
object WindowUi {
    /** The window's shape now (recomposes on rotation: MainActivity handles orientation changes itself). */
    @Composable
    fun shape(): WindowLayout.Shape {
        val cfg = LocalConfiguration.current
        return WindowLayout.Shape(cfg.screenWidthDp.toFloat(), cfg.screenHeightDp.toFloat())
    }

    /** A phone on its side (or any window under 480 dp tall). */
    @Composable
    fun short(): Boolean = WindowLayout.isShort(LocalConfiguration.current.screenHeightDp.toFloat())

    /** The tallest a bottom sheet may be on this window ([WindowLayout.sheetMaxHeight]). */
    @Composable
    fun sheetMaxHeight(): Dp {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
        return WindowLayout.sheetMaxHeight(LocalConfiguration.current.screenHeightDp.toFloat(), top).dp
    }

    /** A sheet body designed at [designed] tall, shrunk on a short window ([WindowLayout.sheetBodyMax]). */
    @Composable
    fun sheetBody(designed: Dp): Dp {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value
        return WindowLayout.sheetBodyMax(designed.value, LocalConfiguration.current.screenHeightDp.toFloat(), top).dp
    }

    /**
     * [content] gets the side gutter that centres it at [max] wide in the space it is given (never under [min]).
     * For lazy lists: pass the gutter as horizontal contentPadding, so the whole width still scrolls.
     */
    @Composable
    fun WithGutter(max: Dp = WindowLayout.READABLE_DP.dp, min: Dp = 0.dp, modifier: Modifier = Modifier, content: @Composable (Dp) -> Unit) {
        BoxWithConstraints(modifier) {
            content(WindowLayout.gutter(maxWidth.value, max.value, min.value).dp)
        }
    }
}

/**
 * Pads the sides the display cutout (camera hole / notch) takes. Upright it has no horizontal part, so nothing moves;
 * on its side the cutout sits left or right. Uses windowInsetsPadding, so a parent that already padded (and consumed)
 * the cutout makes this zero.
 */
@Composable
fun Modifier.cutoutSafe(): Modifier = this.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))

/** Fills the width but keeps the content at most [max] wide, centred (an upright phone is narrower, so it is unchanged). */
fun Modifier.readableWidth(max: Dp = WindowLayout.READABLE_DP.dp): Modifier =
    this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()

/** [this] padding with [gutter] added to both sides (lazy lists: the gutter centres the rows, the whole width scrolls). */
@Composable
fun androidx.compose.foundation.layout.PaddingValues.withGutter(gutter: Dp): androidx.compose.foundation.layout.PaddingValues {
    val dir = androidx.compose.ui.platform.LocalLayoutDirection.current
    return androidx.compose.foundation.layout.PaddingValues(
        start = calculateStartPadding(dir) + gutter, top = calculateTopPadding(),
        end = calculateEndPadding(dir) + gutter, bottom = calculateBottomPadding())
}
