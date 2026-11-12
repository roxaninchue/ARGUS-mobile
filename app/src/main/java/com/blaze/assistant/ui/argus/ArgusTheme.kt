package com.blaze.assistant.ui.argus

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.blaze.assistant.R
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.ui.drawArgusArcs

/**
 * P0-SH-01: the ARGUS theme (04 §2): [ArgusColors] via [LocalArgusColors], the ground via [LocalArgusGround], and
 * Material3's colour scheme and typography mapped from the same tokens so stock components inherit the look.
 * Behind the `argus_look` setting ([ArgusLookFlag]): P1-SH-01 wraps the new shell (ChatScreen) in it and turns it on by
 * default; switching it off brings back the 0.52 shell.
 */
val LocalArgusColors = staticCompositionLocalOf { ArgusColors.Dark }
val LocalArgusGround = staticCompositionLocalOf { Ground.PLAIN }

object Argus {
    val colors: ArgusColors @Composable @ReadOnlyComposable get() = LocalArgusColors.current
    val ground: Ground @Composable @ReadOnlyComposable get() = LocalArgusGround.current

    /** The `argus_look` switch (Settings; default on from P1-SH-01, which draws the ARGUS shell with it). */
    fun lookOn(): Boolean = try { Settings.bool(ArgusLookFlag.KEY, ArgusLookFlag.DEFAULT) } catch (_: Throwable) { ArgusLookFlag.DEFAULT }
    /** The chosen ground (Appearance), [default] unless set (the shell passes Orbit: SpaceBackdrop's space view, P1-SH-01). */
    fun groundSetting(default: Ground = Ground.PLAIN): Ground = try { Ground.of(Settings.str(ArgusLookFlag.GROUND_KEY, default.name)) } catch (_: Throwable) { default }
}

@Composable
fun ArgusTheme(dark: Boolean, ground: Ground = Ground.PLAIN, content: @Composable () -> Unit) {
    val c = ArgusColors.of(dark)
    CompositionLocalProvider(LocalArgusColors provides c, LocalArgusGround provides ground) {
        MaterialTheme(colorScheme = argusColorScheme(c), typography = argusTypography(), content = content)
    }
}

/** Material3 roles from [ArgusTokens.m3]. */
fun argusColorScheme(c: ArgusColors): ColorScheme {
    val m = ArgusTokens.m3(c.palette)
    fun r(k: String): Color = ArgusColors.c(m.getValue(k))
    return if (c.dark) darkColorScheme(
        primary = r("primary"), onPrimary = r("onPrimary"), primaryContainer = r("primaryContainer"), onPrimaryContainer = r("onPrimaryContainer"),
        secondary = r("secondary"), onSecondary = r("onSecondary"), secondaryContainer = r("secondaryContainer"), onSecondaryContainer = r("onSecondaryContainer"),
        tertiary = r("tertiary"), onTertiary = r("onTertiary"),
        background = r("background"), onBackground = r("onBackground"), surface = r("surface"), onSurface = r("onSurface"),
        surfaceVariant = r("surfaceVariant"), onSurfaceVariant = r("onSurfaceVariant"), surfaceTint = Color.Transparent,
        inverseSurface = r("inverseSurface"), inverseOnSurface = r("inverseOnSurface"), error = r("error"), onError = r("onError"),
        outline = r("outline"), outlineVariant = r("outlineVariant"), scrim = r("scrim"),
        surfaceContainer = r("surfaceContainer"), surfaceContainerHigh = r("surfaceContainerHigh"),
    ) else lightColorScheme(
        primary = r("primary"), onPrimary = r("onPrimary"), primaryContainer = r("primaryContainer"), onPrimaryContainer = r("onPrimaryContainer"),
        secondary = r("secondary"), onSecondary = r("onSecondary"), secondaryContainer = r("secondaryContainer"), onSecondaryContainer = r("onSecondaryContainer"),
        tertiary = r("tertiary"), onTertiary = r("onTertiary"),
        background = r("background"), onBackground = r("onBackground"), surface = r("surface"), onSurface = r("onSurface"),
        surfaceVariant = r("surfaceVariant"), onSurfaceVariant = r("onSurfaceVariant"), surfaceTint = Color.Transparent,
        inverseSurface = r("inverseSurface"), inverseOnSurface = r("inverseOnSurface"), error = r("error"), onError = r("onError"),
        outline = r("outline"), outlineVariant = r("outlineVariant"), scrim = r("scrim"),
        surfaceContainer = r("surfaceContainer"), surfaceContainerHigh = r("surfaceContainerHigh"),
    )
}

fun argusTypography(): Typography = Typography(
    displayLarge = ArgusType.display, displayMedium = ArgusType.serifHello, headlineLarge = ArgusType.title1,
    headlineMedium = ArgusType.title2, titleLarge = ArgusType.sheetTitle, titleMedium = ArgusType.topTitle, titleSmall = ArgusType.rowTitle,
    bodyLarge = ArgusType.body, bodyMedium = ArgusType.sub, bodySmall = ArgusType.meta,
    labelLarge = ArgusType.chip, labelMedium = ArgusType.statusChip, labelSmall = ArgusType.pill,
)

/** The Plain blobs or the Cobalt-night gradient (+ the thin arcs in dark) behind this element. Orbit needs [ArgusGround]. */
fun Modifier.argusGround(c: ArgusColors, ground: Ground): Modifier = when (ground) {
    Ground.COBALT_NIGHT -> background(Brush.verticalGradient(ArgusTokens.cobaltNight(c.dark).map { ArgusColors.c(it) }))
        .drawBehind { if (c.dark) drawArgusArcs() }
    else -> background(c.bg).drawBehind {
        val r = maxOf(size.width, size.height)
        for (b in c.palette.blobs) {
            val col = ArgusColors.c(b.color)
            drawRect(Brush.radialGradient(listOf(col, col.copy(alpha = 0f)), center = Offset(size.width * b.x, size.height * b.y), radius = r * b.radius))
        }
    }
}

/** A full-screen ground with [content] on it; Orbit draws argus_space.webp under its veil. */
@Composable
fun ArgusGround(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Argus.colors
    val g = Argus.ground
    if (g == Ground.ORBIT) Box(modifier.fillMaxSize().background(c.bg)) {
        Image(painterResource(R.drawable.argus_space), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(ArgusColors.c(ArgusTokens.a(ArgusTokens.orbitVeilColor(c.palette), ArgusTokens.orbitVeil(c.dark)))))
        content()
    } else Box(modifier.fillMaxSize().argusGround(c, g), content = content)
}
