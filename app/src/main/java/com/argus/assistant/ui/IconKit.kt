package com.argus.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 0.52.4 icons everywhere: the round tinted tile a list row leads with (the reference's Duplicates / Junk rows). Sizes,
 * shape and the fill's strength come from [ArgusIconStyle]; [tint] is the icon's colour and, faint, the tile's.
 * [filled] = the strong state (a selected or switched-on row): a solid [tint] tile with a white icon.
 * The icon is decorative (the row's own text or description names it).
 */
@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = LocalArgus.current.accent, small: Boolean = false,
             filled: Boolean = false) {
    val dark = LocalArgus.current.dark
    val box = if (small) ArgusIconStyle.small else ArgusIconStyle.tile
    val glyph = if (small) ArgusIconStyle.smallIcon else ArgusIconStyle.tileIcon
    Box(modifier.size(box).clip(ArgusIconStyle.shape).background(if (filled) tint else tint.copy(alpha = ArgusIconStyle.fillAlpha(dark))),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = if (filled) Color.White else tint, modifier = Modifier.size(glyph))
    }
}

/** A bare leading icon for compact rows and buttons ([ArgusIconStyle.rowIcon] unless [size] says otherwise). */
@Composable
fun LeadIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = ArgusIconStyle.rowIcon) {
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}
