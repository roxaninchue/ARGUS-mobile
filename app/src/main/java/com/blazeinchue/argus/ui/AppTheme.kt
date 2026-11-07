package com.blazeinchue.argus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * One look everywhere. 0.52.2: the Living Themes (picked theme, scenes, carousel) are gone; light / dark ([ThemeMode]) is
 * the one switch every screen follows: Home, Projects, Chat, Settings, sheets, Music, Video, Design and the Terminal.
 * ThemeEverywhereTest checks the rule and that each screen's switcher writes through [ThemeMode].
 */
object AppTheme {
    /** Every screen and view that follows light / dark. */
    val SCREENS = listOf("home", "projects", "chat", "settings", "sheets", "music", "video", "design", "terminal")

    /** Whether the app draws dark now (for non-Compose screens: the Terminal). Same rule as [ArgusTheme]. */
    fun darkNow(hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): Boolean =
        LookLogic.darkFor(ThemeMode.mode.value, hour, systemDark = true)

    /** The one write path for light / dark from a switch (the Terminal's menu, [ThemeSwitch]). */
    fun toggle(): String { val m = LightDark.toggle(darkNow()).first; ThemeMode.set(m); return m }
}

/**
 * The light / dark switch on each screen's top bar (it used to pick a Living Theme). Tap flips light and dark through
 * [AppTheme.toggle], so every other screen and activity changes too.
 */
@Composable
fun ThemeSwitch(modifier: Modifier = Modifier, tint: Color = LocalArgus.current.ink) {
    val dark = LocalArgus.current.dark
    Box(modifier) {
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = if (dark) "Switch to light" else "Switch to dark") { AppTheme.toggle() }
            .semantics { contentDescription = if (dark) "Dark theme. Switch to light" else "Light theme. Switch to dark" }, contentAlignment = Alignment.Center) {
            Icon(if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        }
    }
}
