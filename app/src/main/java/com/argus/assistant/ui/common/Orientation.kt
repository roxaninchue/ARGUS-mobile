package com.argus.assistant.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Shared landscape test (added with Living UI 0.2.1). Landscape = the window is wider than tall and at least
 * [WIDE_MIN_DP] wide, so a tablet in portrait or a narrow split-screen window keeps the portrait layout.
 * [landscape] is pure (LivingLayoutTest); [isLandscape] reads the current configuration (MainActivity handles
 * orientation changes itself, so the composition and its state survive a turn).
 */
object Orientation {
    const val WIDE_MIN_DP = 560

    fun landscape(widthDp: Int, heightDp: Int): Boolean = widthDp > heightDp && widthDp >= WIDE_MIN_DP
}

@Composable
fun isLandscape(): Boolean {
    val c = LocalConfiguration.current
    return Orientation.landscape(c.screenWidthDp, c.screenHeightDp)
}
