package com.blaze.assistant.ui.living

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.blaze.assistant.i18n.tr
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.argus.icons.ArgusGlyphs
import com.blaze.assistant.ui.argus.kit.ArgusIconButton
import com.blaze.assistant.ui.argus.kit.IconButtonKind

/**
 * Living UI 0.2.1, landscape (Finn's landscape mockup): the slim left rail that replaces the ARGUS top bar, the Chat mode
 * row (history · private · new chat) and the Chat | Living switch while Living shows in landscape, so the canvas gets the
 * whole height. Top: chat history (dot = a new reply elsewhere), search (Spotlight), Living (on; a tap goes back to Chat,
 * where the full chrome and the mode notch come back). Bottom: leave the private chat (only in one), new chat, and the
 * profile (settings; long-press = light / dark), like the top bar's avatar.
 *
 * The rail paints the canvas colour, so rail and canvas read as one surface. ChatScreen draws it at the start edge and
 * pads the chat column by [LivingSpec.RAIL_DP].
 */
@Composable
fun LivingRail(
    initial: String?,
    historyBadge: Boolean,
    privateOn: Boolean,
    onHistory: () -> Unit,
    onSearch: () -> Unit,
    onNewChat: () -> Unit,
    onLeavePrivate: () -> Unit,
    onMe: () -> Unit,
    onMeLong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalBlaze.current.dark
    val p = remember(dark) { LivingSpec.palette(dark) }
    Column(modifier.fillMaxHeight().displayCutoutPadding().width(LivingSpec.RAIL_DP.dp).background(Color(p.canvas))
        .statusBarsPadding().navigationBarsPadding().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        ArgusIconButton(ArgusGlyphs.MENU_TAPER, tr("Chat history"), onHistory, kind = IconButtonKind.FLAT, badge = historyBadge)
        ArgusIconButton("search", tr("Search"), onSearch, kind = IconButtonKind.FLAT)
        Box(Modifier.padding(vertical = 4.dp).width(24.dp).height(1.dp).background(Color(p.link)))
        ArgusIconButton("layers", tr("Living, on. Tap for Chat"), { LivingSpec.show(LivingSpec.View.CHAT) }, kind = IconButtonKind.GLASS)
        Spacer(Modifier.weight(1f))
        if (privateOn) ArgusIconButton("lock", tr("Private chat on. Leave"), onLeavePrivate, kind = IconButtonKind.GLASS)
        ArgusIconButton("square-pen", tr("New chat"), onNewChat, kind = IconButtonKind.FLAT)
        ArgusIconButton("user", tr("Profile and settings"), onMe, kind = IconButtonKind.AVATAR, initial = initial?.ifBlank { null } ?: "A", onLongClick = onMeLong)
    }
}

/** The chat column's room for the rail: the camera cutout's side (landscape) plus [LivingSpec.RAIL_DP], like the rail. */
val LivingRailSpace: Modifier = Modifier.displayCutoutPadding().padding(start = LivingSpec.RAIL_DP.dp)

/**
 * The composer floating over the canvas in landscape Living: at most [LivingSpec.FLOAT_COMPOSER_MAX_DP] wide, centred,
 * its bottom where the composer row was, taking no height in the chat column (the canvas runs under it to the bottom;
 * LivingScreen keeps [LivingSpec.WIDE_BOTTOM_DP] clear for it).
 */
val LivingFloat: Modifier = Modifier.layout { m, c ->
    val full = if (c.hasBoundedWidth) c.maxWidth else c.minWidth
    val cap = LivingSpec.FLOAT_COMPOSER_MAX_DP.dp.roundToPx()
    val p = m.measure(c.copy(minWidth = 0, maxWidth = if (c.hasBoundedWidth) minOf(c.maxWidth, cap) else cap, minHeight = 0))
    layout(full, 0) { p.place((full - p.width) / 2, -p.height) }
}
