package com.blaze.assistant.ui.argus.kit

import com.blaze.assistant.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.BotOrb
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.ManagerOrb
import com.blaze.assistant.ui.argus.OrbFace
import com.blaze.assistant.ui.argus.OrbSize
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Lucide

/** The default avatar for a row or pin: the Manager sphere, else a BotOrb picked from the bot's name and [color]. */
@Composable
fun KitAvatar(name: String, manager: Boolean, sizeDp: Float, color: Long? = null) {
    if (manager) ManagerOrb(sizeDp.dp, contentDescription = null)
    else BotOrb(OrbFace.forName(name), color ?: KitColors.forName(name), sizeDp.dp)
}

/** A stable palette colour for a bot with none set (by name). */
object KitColors {
    fun forName(name: String): Long {
        val v = ArgusTokens.BOT_COLORS.values.toList()
        return v[((name.sumOf { it.code } % v.size) + v.size) % v.size]
    }
}

/**
 * P1-SH-02 `BotChatRow` (A-team `crow()`, 04 §2.8 item 7): orb md · name (16/620) + time (12.5 meta) on one baseline ·
 * preview (14 mute; red bold when overdue, green when all clear) · 10 dp unread dot. Manager row: gold tint 7 % + "Lead".
 * Pressed = `glassFill2` + rim. Long-press → [onLongClick] (the quick sheet, P1-TM-03). Swipe toward the end → [onPin],
 * toward the start → [onMute]; the row snaps back, and both are also TalkBack custom actions. One merged node
 * ([KitA11y.botRow]). No fixed height, so it grows at 200 % font.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotChatRow(
    model: BotRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
    onMute: (() -> Unit)? = null,
    color: Long? = null,
    avatar: @Composable () -> Unit = { KitAvatar(model.name, model.manager, OrbSize.MD, color) },
) {
    if (onPin == null && onMute == null) { BotChatRowBody(model, onClick, modifier, onLongClick, onPin, onMute, avatar); return }
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> onPin?.invoke()
            SwipeToDismissBoxValue.EndToStart -> onMute?.invoke()
            SwipeToDismissBoxValue.Settled -> Unit
        }
        false // never dismiss: the row stays, the action ran
    })
    val c = Argus.colors
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = onPin != null,
        enableDismissFromEndToStart = onMute != null,
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End) {
                val (icon, label) = if (toEnd) (if (model.pinned) "pin-off" to "Unpin" else "pin" to "Pin") else "bell" to (if (model.muted) "Unmute" else "Mute")
                Icon(Lucide.icon(icon, 20), null, Modifier.size(20.dp), tint = c.mute)
                Text(tr(label), style = ArgusType.tool, color = c.mute, modifier = Modifier.padding(start = 6.dp))
            }
        },
    ) { BotChatRowBody(model, onClick, Modifier, onLongClick, onPin, onMute, avatar) }
}

@Composable
private fun BotChatRowBody(
    model: BotRowModel, onClick: () -> Unit, modifier: Modifier, onLongClick: (() -> Unit)?,
    onPin: (() -> Unit)?, onMute: (() -> Unit)?, avatar: @Composable () -> Unit,
) {
    val c = Argus.colors
    val p = c.palette
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val shape = RoundedCornerShape(ArgusTokens.Radius.ROW.dp)
    val bg = when {
        pressed -> Modifier.clip(shape).background(c.glassFill2).border(1.dp, c.glassRim, shape)
        model.manager -> Modifier.clip(shape).background(c.manager.copy(alpha = KitSpec.MANAGER_TINT_ALPHA))
        else -> Modifier.clip(shape)
    }
    val desc = KitA11y.botRow(model)
    val actions = buildList {
        if (onPin != null) add(CustomAccessibilityAction(if (model.pinned) "Unpin" else "Pin") { onPin(); true })
        if (onMute != null) add(CustomAccessibilityAction(if (model.muted) "Unmute" else "Mute") { onMute(); true })
    }
    Row(
        modifier
            .fillMaxWidth()
            .then(bg)
            .kitCombinedClick(src, onClick, onLongClick)
            .clearAndSetSemantics {
                contentDescription = desc
                role = Role.Button
                onClick(label = tr("Open")) { onClick(); true }
                if (onLongClick != null) onLongClick(label = tr("More")) { onLongClick(); true }
                if (actions.isNotEmpty()) customActions = actions
            }
            .heightIn(min = KitSpec.Row.MIN_HEIGHT.dp)
            .padding(horizontal = KitSpec.Row.PAD_H.dp, vertical = KitSpec.Row.PAD_V.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KitSpec.Row.GAP.dp),
    ) {
        Box(Modifier.size(KitSpec.Row.ORB.dp), contentAlignment = Alignment.Center) { avatar() }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.name, style = ArgusType.rowTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline())
                if (model.manager) Text(BotRow.LEAD, style = ArgusType.pill, color = kc(KitSpec.LEAD_TEXT),
                    modifier = Modifier.alignByBaseline().clip(RoundedCornerShape(ArgusTokens.Radius.PILL.dp)).background(c.manager).padding(horizontal = 7.dp, vertical = 1.dp))
                Box(Modifier.weight(1f))
                if (model.time.isNotBlank()) Text(model.time, style = ArgusType.meta, color = if (model.manager) kc(KitSpec.managerMeta(p)) else c.meta, maxLines = 1, modifier = Modifier.alignByBaseline())
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val line = BotRow.previewLine(model.preview)
                val (col, weight) = when (model.tone) {
                    PreviewTone.OVERDUE -> c.dangerText to FontWeight.SemiBold
                    PreviewTone.CLEAR -> c.successText to FontWeight.Normal
                    PreviewTone.NORMAL -> (if (model.manager) kc(KitSpec.managerPreview(p)) else c.mute) to FontWeight.Normal
                }
                Text(line, style = ArgusType.preview.copy(fontWeight = weight), color = col, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (BotRow.showDot(model)) Box(Modifier.size(KitSpec.Row.UNREAD_DOT.dp).clip(CircleShape).background(c.unread))
            }
        }
    }
}

/**
 * P1-SH-02 `MenuRow` (A-team `.menu button`): 44 dp glass disc icon · title 15.5/600 · sub 13 mute. Used in the + menu,
 * sheets and Settings. One merged node.
 */
@Composable
fun MenuRow(
    icon: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    danger: Boolean = false,
) {
    val c = Argus.colors
    val src = remember { MutableInteractionSource() }
    val tint = if (danger) kc(KitSpec.dangerText(c.palette)) else c.ink
    Row(
        modifier.fillMaxWidth().kitPress(src)
            .clickable(src, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .heightIn(min = KitSpec.Menu.MIN_HEIGHT.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(KitSpec.Menu.DISC.dp).argusGlass(GlassLevel.RAISED, CircleShape, shadow = false), contentAlignment = Alignment.Center) {
            Icon(Lucide.icon(icon, KitSpec.Menu.ICON), null, Modifier.size(KitSpec.Menu.ICON.dp), tint = tint)
        }
        Column(Modifier.weight(1f)) {
            Text(tr(title), style = ArgusType.bodyStrong, color = tint)
            if (!sub.isNullOrBlank()) Text(tr(sub), style = ArgusType.preview.copy(fontSize = 13.sp, lineHeight = 18.sp), color = c.mute)
        }
        trailing?.invoke()
    }
}

/** One row of a [SettingsGroup]. */
data class SettingsItem(
    val icon: String,
    val label: String,
    val value: String? = null,
    val chevron: Boolean = true,
    val onClick: (() -> Unit)? = null,
)

/**
 * P1-SH-02 `SettingsGroup` (AM Space profile pattern in A-team glass): an optional section title (12/650 caps), then a
 * Surface card of rows (icon + label + value / chevron) with hairlines between. [extra] adds custom rows (a switch row)
 * after [items].
 */
@Composable
fun SettingsGroup(
    items: List<SettingsItem>,
    modifier: Modifier = Modifier,
    title: String? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Argus.colors
    Column(modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) Text(tr(title).uppercase(), style = ArgusType.section, color = c.meta,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp).semantics { this.contentDescription = title })
        Column(Modifier.fillMaxWidth().argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.CARD.dp))) {
            items.forEachIndexed { i, it ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 50.dp), thickness = 1.dp, color = c.line)
                SettingsRow(it)
            }
            extra?.invoke(this)
        }
    }
}

@Composable
fun SettingsRow(item: SettingsItem, modifier: Modifier = Modifier) {
    val c = Argus.colors
    val click = item.onClick?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier
    Row(
        modifier.fillMaxWidth().then(click).semantics(mergeDescendants = true) {}
            .heightIn(min = KitSpec.Settings.MIN_HEIGHT.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Lucide.icon(item.icon, KitSpec.Settings.ICON), null, Modifier.size(KitSpec.Settings.ICON.dp), tint = c.ink)
        Text(tr(item.label), style = ArgusType.body, color = c.ink, modifier = Modifier.weight(1f))
        if (!item.value.isNullOrBlank()) Text(tr(item.value), style = ArgusType.sub, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.chevron && item.onClick != null) Icon(Lucide.icon("chevron-right", 16), null, Modifier.size(16.dp), tint = c.dim)
    }
}
