package com.blaze.assistant.ui.argus.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A stand-in orb (a lit sphere in [color]) used until the P0 `BotOrb` is on the branch, and in shots. Every kit
 * component takes its orb as a slot, so swapping in `BotOrb(face, color, size, state)` touches no component.
 */
@Composable
fun KitOrb(color: Color, size: Dp, modifier: Modifier = Modifier, initial: String? = null) {
    Box(modifier.size(size).clip(CircleShape)
        .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), color, color.copy(alpha = 0.55f).compositeOver(Color.Black)))),
        contentAlignment = Alignment.Center) {
        if (initial != null) Text(initial.take(1), style = KitType.style(KitText.ROW_TITLE, Color.White))
    }
}

private fun Color.compositeOver(under: Color): Color {
    val a = alpha
    return Color(red * a + under.red * (1 - a), green * a + under.green * (1 - a), blue * a + under.blue * (1 - a), 1f)
}

/** The gold "Lead" badge (04 §2.9 `.mbadge`). */
@Composable
fun LeadBadge(modifier: Modifier = Modifier, text: String = "LEAD") {
    val look = kitLook()
    Box(modifier.clip(KitShapes.pill).background(look.manager.copy(alpha = 0.22f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, style = KitType.style(KitText.PILL, if (look.dark) look.manager else Color(0xFF7A5A00)))
    }
}

/**
 * `crow()` (04 §2.8 #7): orb · name + time on one baseline · one-line preview (red bold when overdue, green "All
 * clear") · unread dot. Manager row: 7 % gold tint and the Lead badge. Long-press opens the quick sheet; TalkBack
 * hears one merged line ([RowSpec.spoken]) plus custom actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BotChatRow(
    name: String,
    preview: RowSpec.Preview,
    time: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: String = "",
    unread: Boolean = false,
    lead: Boolean = false,
    working: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
    orb: @Composable () -> Unit,
) {
    val look = kitLook()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val spoken = RowSpec.spoken(name, role, preview.text, time, unread, lead, working)
    Row(modifier.fillMaxWidth().heightIn(min = 68.dp).clip(KitShapes.row)
        .background(when { pressed -> look.glass2; lead -> look.manager.copy(alpha = 0.07f); else -> Color.Transparent })
        .then(if (pressed) Modifier.border(BorderStroke(1.dp, look.rim), KitShapes.row) else Modifier)
        .combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onLongClick,
            onClickLabel = "Open $name", onLongClickLabel = if (onLongClick != null) "More for $name" else null)
        .clearAndSetSemantics {
            contentDescription = spoken; this.role = Role.Button
            if (actions.isNotEmpty()) customActions = actions.map { (label, run) -> CustomAccessibilityAction(label) { run(); true } }
        }
        .padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(KitSize.ORB_ROW.dp)) {
            orb()
            if (working) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(look.bg).padding(2.dp).clip(CircleShape).background(look.success))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KitType.style(KitText.ROW_TITLE, look.ink), modifier = Modifier.weight(1f, fill = false))
                if (lead) { Spacer(Modifier.width(8.dp)); LeadBadge() }
                Spacer(Modifier.weight(1f))
                if (time.isNotEmpty()) Text(time, maxLines = 1, style = KitType.style(KitText.META, look.dim, tnum = true))
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tone = when (preview.tone) { RowSpec.Tone.DANGER -> look.dangerText; RowSpec.Tone.SUCCESS -> look.successText; RowSpec.Tone.PLAIN -> look.mute }
                Text(preview.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = KitType.style(KitText.PREVIEW, tone).copy(fontWeight = if (preview.bold) FontWeight.SemiBold else FontWeight.Normal),
                    modifier = Modifier.weight(1f))
                if (unread) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(KitSize.UNREAD_DOT.dp).clip(CircleShape).background(look.unread)) }
            }
        }
    }
}

/** One pinned bot (04 §2.8 #8). */
data class PinItem(val id: String, val name: String, val overdue: Boolean = false)

/** `.pins` (04 §2.8 #8): up to four large orbs with names; a red dot when overdue. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinStrip(
    pins: List<PinItem>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: ((String) -> Unit)? = null,
    orb: @Composable (PinItem) -> Unit,
) {
    val look = kitLook()
    val shown = Pins.visible(pins)
    if (shown.isEmpty()) return
    Row(modifier.fillMaxWidth().kitSurface(look).padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (p in shown) {
            Column(Modifier.widthIn(min = KitSize.TOUCH.dp)
                .combinedClickable(onClick = { onOpen(p.id) }, onLongClick = onLongPress?.let { { it(p.id) } }, onClickLabel = "Open ${p.name}")
                .clearAndSetSemantics { contentDescription = if (p.overdue) "${p.name}, overdue" else p.name; role = Role.Button }
                .padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp)) {
                    orb(p)
                    if (p.overdue) Box(Modifier.align(Alignment.TopEnd).size(KitSize.PIN_DOT.dp).clip(CircleShape).background(look.bg).padding(2.dp).clip(CircleShape).background(look.danger))
                }
                Spacer(Modifier.height(6.dp))
                Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KitType.style(KitText.META, look.ink))
            }
        }
    }
}

/** `.menu button` (04 §2.8 #11): 44 dp glass disc icon · title · sub. */
@Composable
fun MenuRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    danger: Boolean = false,
) {
    val look = kitLook()
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clip(KitShapes.row)
        .clickable(onClickLabel = title, onClick = onClick)
        .semantics(mergeDescendants = true) { role = Role.Button }
        .padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(KitSize.MENU_DISC.dp).clip(CircleShape).background(look.glass2).border(BorderStroke(1.dp, look.rim), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (danger) look.dangerText else look.ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = KitType.style(KitText.BODY_STRONG, if (danger) look.dangerText else look.ink))
            if (!sub.isNullOrBlank()) Text(sub, style = KitType.style(KitText.MENU_SUB, look.mute))
        }
    }
}

/** A section head (04 §2.2 `section`): 12 sp uppercase, marked as a heading for TalkBack. */
@Composable
fun SectionHead(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val look = kitLook()
    Row(modifier.fillMaxWidth().padding(top = KitSize.SECTION_GAP.dp, bottom = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = KitType.style(KitText.SECTION, look.mute), modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/**
 * AM Space profile's grouped card (04 §2.8 #12) in A-team glass: an optional section head, then rows separated by
 * hairlines. Put [SettingsRow]s inside.
 */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val look = kitLook()
    Column(modifier.fillMaxWidth()) {
        if (title != null) SectionHead(title)
        Column(Modifier.fillMaxWidth().kitSurface(look), content = content)
    }
}

/** A row inside [SettingsGroup]: icon · label (+ sub) · value · chevron; [divider] draws the hairline above it. */
@Composable
fun SettingsRow(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    sub: String? = null,
    divider: Boolean = true,
    chevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val look = kitLook()
    Column(modifier.fillMaxWidth()) {
        if (divider) Box(Modifier.fillMaxWidth().padding(start = if (icon != null) 52.dp else 16.dp).height(1.dp).background(look.line))
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClickLabel = label, onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button }
            .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = look.ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = KitType.style(KitText.BODY, look.ink))
                if (!sub.isNullOrBlank()) Text(sub, style = KitType.style(KitText.META, look.mute))
            }
            if (!value.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KitType.style(KitText.PREVIEW, look.mute), modifier = Modifier.widthIn(max = 160.dp))
            }
            trailing?.invoke()
            if (chevron) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = look.dim, modifier = Modifier.size(20.dp))
        }
    }
}
