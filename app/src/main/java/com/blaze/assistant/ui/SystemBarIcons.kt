package com.blaze.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Owner 2026-11-18: the clock, Wi-Fi, signal and battery at the top of the screen were white on ARGUS's light pages
 * (Settings › You, Life...), so nothing could be read. enableEdgeToEdge's SystemBarStyle.auto picks the icon colour from
 * the PHONE's dark mode, but ARGUS has its own light / dark ([ThemeMode]): a light ARGUS on a dark phone got white icons
 * on a light page, and a dark ARGUS on a light phone got dark icons on a dark page. [SyncSystemBarIcons] sets the icons
 * from the theme ARGUS actually draws: dark icons on light grounds, light icons on dark ones.
 *
 * A screen that is black whatever the theme (the photo viewer, a full-screen image) calls [DarkSystemBars] while it is
 * shown, so the icons turn light over it and come back when it closes.
 */
object SystemBarIcons {
    /** How many always-dark full-screen surfaces are showing (the icons stay light while any is). */
    internal val darkScreens = mutableIntStateOf(0)

    /** True when the status / navigation bar icons should be dark (drawn over a light ground). Pure, so it is tested. */
    fun darkIcons(appDark: Boolean, darkScreenShowing: Boolean): Boolean = !appDark && !darkScreenShowing
}

/** Keeps the status bar and navigation bar icons readable on the theme ARGUS draws ([dark] = ARGUS is dark). */
@Composable
fun SyncSystemBarIcons(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val darkIcons = SystemBarIcons.darkIcons(dark, SystemBarIcons.darkScreens.intValue > 0)
    SideEffect {
        val window = view.context.barsActivity()?.window ?: return@SideEffect
        try {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                if (isAppearanceLightStatusBars != darkIcons) isAppearanceLightStatusBars = darkIcons
                if (isAppearanceLightNavigationBars != darkIcons) isAppearanceLightNavigationBars = darkIcons
            }
        } catch (_: Throwable) { }
    }
}

/** While composed, the system bar icons stay light (for a surface that is black in both themes). */
@Composable
fun DarkSystemBars() {
    DisposableEffect(Unit) {
        SystemBarIcons.darkScreens.intValue += 1
        onDispose { SystemBarIcons.darkScreens.intValue = (SystemBarIcons.darkScreens.intValue - 1).coerceAtLeast(0) }
    }
}

private tailrec fun Context.barsActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.barsActivity()
    else -> null
}
