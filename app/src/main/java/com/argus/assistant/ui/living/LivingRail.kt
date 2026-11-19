package com.blaze.assistant.ui.living

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.i18n.tr
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.argus.icons.ArgusGlyphs
import com.blaze.assistant.ui.argus.icons.Lucide

/**
 * The landscape shell rail (Finn's landscape mockup). Living UI 0.2.1 drew it for Living only; 0.2.3 draws it in every
 * mode of the ARGUS shell on a phone on its side (Team, Chat, Life, Desk, Projects; LivingSpec.shellRail), in place of
 * the top bar, the mode notch and the Chat | Living switch, so pages get the whole height.
 *
 * Top: the four modes (the open one filled), then in Chat: the history (dot = a new reply elsewhere) and Living (filled
 * when on; a tap switches Chat ↔ Living). Bottom: search (Spotlight), leave the private chat (only in one), new (a new
 * chat in Chat, else the New sheet) and the profile (settings; long-press light / dark). Items are 36 dp so they fit a
 * 360 dp tall window; when they still don't, the rail scrolls.
 *
 * The rail paints the canvas colour so rail and Living's canvas read as one surface. ChatScreen draws it at the start
 * edge and pads the shell column with [shellPad].
 */
@Composable
fun LivingRail(
    mode: String,
    onMode: (String) -> Unit,
    chat: Boolean,
    livingShown: Boolean,
    livingOn: Boolean,
    initial: String?,
    historyBadge: Boolean,
    privateOn: Boolean,
    onHistory: () -> Unit,
    onSearch: () -> Unit,
    onNew: () -> Unit,
    onLeavePrivate: () -> Unit,
    onMe: () -> Unit,
    onMeLong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalBlaze.current.dark
    val p = remember(dark) { LivingSpec.palette(dark) }
    Box(modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start))
        .width(LivingSpec.RAIL_DP.dp).background(Color(p.canvas)).statusBarsPadding().navigationBarsPadding()) {
        BoxWithConstraints(Modifier.fillMaxHeight()) {
            val top = LivingSpec.SHELL_MODES.size + (if (chat) 1 + (if (livingShown) 1 else 0) else 0)
            val bottom = 3 + (if (privateOn) 1 else 0)
            val used = (top + bottom) * ITEM + 9 + 12
            val gap = (maxHeight.value - used).coerceAtLeast(0f)
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for ((m, icon) in LivingSpec.SHELL_MODES) RailItem(icon, com.blaze.assistant.ui.argus.kit.ShellSpec.label(m), m == mode, p) { onMode(m) }
                Box(Modifier.padding(vertical = 4.dp).width(22.dp).height(1.dp).background(Color(p.link)))
                if (chat) {
                    RailItem(ArgusGlyphs.MENU_TAPER, tr("Chat history"), false, p, badge = historyBadge, onClick = onHistory)
                    if (livingShown) RailItem("layers", if (livingOn) tr("Living, on. Tap for Chat") else tr("Living"), livingOn, p) {
                        LivingSpec.show(if (livingOn) LivingSpec.View.CHAT else LivingSpec.View.LIVING)
                    }
                }
                Spacer(Modifier.height(gap.dp))
                RailItem("search", tr("Search"), false, p, onClick = onSearch)
                if (privateOn) RailItem("lock", tr("Private chat on. Leave"), true, p, onClick = onLeavePrivate)
                RailItem(if (chat) "square-pen" else "plus", if (chat) tr("New chat") else tr("New"), false, p, onClick = onNew)
                RailAvatar(initial?.ifBlank { null } ?: "A", p, onMe, onMeLong)
            }
        }
    }
}

private const val ITEM = 36

@Composable
private fun RailItem(icon: String, label: String, on: Boolean, p: LivingSpec.Palette, badge: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(ITEM.dp).semantics { contentDescription = label; selected = on }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (on) Color(p.bar) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(Lucide.icon(icon, 20), null, Modifier.size(20.dp), tint = Color(if (on) p.ink else p.mute))
        }
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(7.dp).clip(CircleShape).background(Color(p.orb)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailAvatar(initial: String, p: LivingSpec.Palette, onMe: () -> Unit, onMeLong: () -> Unit) {
    Box(Modifier.size(ITEM.dp).semantics { contentDescription = tr("Profile and settings") }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(p.ink)).combinedClickable(role = Role.Button, onLongClick = onMeLong, onClick = onMe),
            contentAlignment = Alignment.Center) {
            Text(initial.trim().take(1).uppercase(), color = Color(p.canvas), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The shell column's padding (0.2.3): the display cutout's sides, once for every page under it (their own cutoutSafe()
 * then pads nothing), plus the rail's width when [rail] is on.
 */
@Composable
fun shellPad(rail: Boolean): Modifier =
    Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
        .then(if (rail) Modifier.padding(start = LivingSpec.RAIL_DP.dp) else Modifier)

/** The composer in landscape outside Living (0.2.3): in the column, centred, at most [LivingSpec.FLOAT_COMPOSER_MAX_DP] wide. */
val LivingCompact: Modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = LivingSpec.FLOAT_COMPOSER_MAX_DP.dp)

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
