package com.blaze.assistant.ui.argus.kit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusGlassRule
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.OrbSize
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Lucide

/**
 * P1-SH-02 `ArgusSheet` (A-team `.sheet`, a ModalBottomSheet restyle): handle, title 19/650, content, and a sticky
 * action row (Cancel = glass, primary = ink or danger) when [primary] is set. Sheet glass (tint fallback: ModalBottomSheet
 * sits in its own window, so Haze has no source to blur there). Opens fully expanded; callers need no Material3 opt-in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArgusSheet(
    onDismiss: () -> Unit,
    title: String?,
    modifier: Modifier = Modifier,
    primary: String? = null,
    onPrimary: () -> Unit = {},
    primaryKind: ButtonKind = ButtonKind.PRIMARY,
    primaryEnabled: Boolean = true,
    cancel: String = "Cancel",
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Argus.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tint = ArgusGlassRule.tint(c.palette, GlassLevel.SHEET, ArgusGlassRule.Mode.TINT)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = state,
        shape = RoundedCornerShape(topStart = ArgusTokens.Radius.SHEET.dp, topEnd = ArgusTokens.Radius.SHEET.dp),
        containerColor = tint?.let { kc(it) } ?: c.sheet,
        contentColor = c.ink,
        scrimColor = c.scrim,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.padding(top = 8.dp, bottom = 6.dp).size(KitSpec.Sheet.HANDLE_W.dp, KitSpec.Sheet.HANDLE_H.dp)
                .clip(RoundedCornerShape(3.dp)).background(c.line2))
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            if (!title.isNullOrBlank()) Text(title, style = ArgusType.sheetTitle, color = c.ink,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() })
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 8.dp), content = content)
            if (primary != null) Row(
                Modifier.fillMaxWidth().heightIn(min = KitSpec.Sheet.ACTION_ROW.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                ArgusButton(cancel, onDismiss, Modifier.weight(1f), kind = ButtonKind.GLASS)
                ArgusButton(primary, onPrimary, Modifier.weight(1f), kind = primaryKind, enabled = primaryEnabled)
            }
        }
    }
}

/**
 * P1-SH-02 `MentionPopup` (A-team `.mention`): a sheet-coloured card above the composer with 44 dp rows (orb xs + name +
 * role for bots; a glyph for commands and files). Feed it [Mention.filter]'s result; it draws nothing when empty.
 */
@Composable
fun MentionPopup(
    items: List<Mention.Item>,
    onPick: (Mention.Item) -> Unit,
    modifier: Modifier = Modifier,
    avatar: @Composable (Mention.Item) -> Unit = { KitAvatar(it.title, it.key == "manager", OrbSize.XS) },
) {
    if (items.isEmpty()) return
    val c = Argus.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)
        .argusGlass(GlassLevel.SHEET, RoundedCornerShape(ArgusTokens.Radius.TOOL.dp)).padding(vertical = 4.dp)) {
        items.take(KitSpec.Mention.MAX_ROWS).forEach { it ->
            val desc = if (it.sub.isBlank()) "${it.kind.sigil}${it.title}" else "${it.title}, ${it.sub}"
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(it) }
                    .clearAndSetSemantics { contentDescription = desc; role = Role.Button; onClick { onPick(it); true } }
                    .heightIn(min = KitSpec.target(KitSpec.Mention.ROW).dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(KitSpec.Mention.ORB.dp), contentAlignment = Alignment.Center) {
                    when (it.kind) {
                        Mention.Kind.BOT -> avatar(it)
                        Mention.Kind.COMMAND -> Icon(Lucide.icon("square-terminal", 16), null, Modifier.size(16.dp), tint = c.mute)
                        Mention.Kind.FILE -> Icon(Lucide.icon("hash", 16), null, Modifier.size(16.dp), tint = c.mute)
                    }
                }
                Text(if (it.kind == Mention.Kind.COMMAND) "/${it.title}" else it.title, style = ArgusType.bodyStrong, color = c.ink, maxLines = 1)
                if (it.sub.isNotBlank()) Text(it.sub, style = ArgusType.preview, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * P1-SH-02 `AttachmentBar` (A-team `.attbar/.attc`): 52 dp tiles (40 dp thumb + short name + ×). [thumb] draws the
 * picture for image tiles; other files get a glyph. The × is its own 48 dp target named "Remove <file>".
 */
@Composable
fun AttachmentBar(
    items: List<AttachmentModel>,
    onRemove: (AttachmentModel) -> Unit,
    modifier: Modifier = Modifier,
    onOpen: ((AttachmentModel) -> Unit)? = null,
    thumb: (@Composable (AttachmentModel) -> Unit)? = null,
) {
    if (items.isEmpty()) return
    val c = Argus.colors
    LazyRow(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.id }) { a ->
            val open = onOpen?.let { f -> Modifier.clickable(role = Role.Button) { f(a) } } ?: Modifier
            Row(
                Modifier.heightIn(min = KitSpec.Attachment.TILE.dp).argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.TOOL.dp), shadow = false)
                    .padding(start = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(open.semantics(mergeDescendants = true) { contentDescription = KitA11y.attachment(a) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(KitSpec.Attachment.THUMB.dp).clip(RoundedCornerShape(9.dp)).background(c.soft), contentAlignment = Alignment.Center) {
                        if (a.image && thumb != null) thumb(a) else Icon(Lucide.icon("notebook", 18), null, Modifier.size(18.dp), tint = c.mute)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(Attachments.shortName(a.name), style = ArgusType.tool, color = c.ink, maxLines = 1)
                }
                ArgusIconButton("x", KitA11y.removeAttachment(a), { onRemove(a) }, kind = IconButtonKind.FLAT)
            }
        }
    }
}

/**
 * P1-SH-02 `EmptyState` (A-team `.empty` / `.hello`): icon 30 at 55 %, title 17, body mute, one or two buttons.
 * [hero] = the first-run look (serif title, e.g. "Build your team"), with [heroArt] above it (three orbs on Team).
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    body: String? = null,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
    hero: Boolean = false,
    heroArt: (@Composable () -> Unit)? = null,
) {
    val c = Argus.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hero && heroArt != null) heroArt()
        else if (icon != null) Icon(Lucide.icon(icon, KitSpec.Empty.ICON), null, Modifier.size(KitSpec.Empty.ICON.dp), tint = c.ink.copy(alpha = KitSpec.Empty.ICON_ALPHA))
        Text(title, style = if (hero) ArgusType.serifHello else ArgusType.topTitle, color = c.ink,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.semantics { heading() })
        if (!body.isNullOrBlank()) Text(body, style = ArgusType.body, color = c.mute, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (primary != null || secondary != null) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            secondary?.let { ArgusButton(it.first, it.second, kind = ButtonKind.GLASS) }
            primary?.let { ArgusButton(it.first, it.second, kind = ButtonKind.PRIMARY) }
        }
    }
}

/**
 * P1-SH-02 `ProgressLine` (A-team `.wsline`): a 44 dp one-line status with a 44×4 bar. [fraction] null = indeterminate
 * (the bar pulses unless [reducedMotion]; then it sits at a third).
 */
@Composable
fun ProgressLine(
    label: String,
    fraction: Float?,
    modifier: Modifier = Modifier,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    reducedMotion: Boolean = false,
) {
    val c = Argus.colors
    val click = onClick?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier
    val desc = KitA11y.progress(label, fraction)
    val shown = fraction ?: if (reducedMotion) 0.33f else indeterminate()
    Row(
        modifier.fillMaxWidth().then(click).clearAndSetSemantics { contentDescription = desc; if (onClick != null) role = Role.Button }
            .heightIn(min = KitSpec.Progress.HEIGHT.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = ArgusType.tool, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        (detail ?: Progress.percent(fraction))?.let { Text(it, style = ArgusType.meta, color = c.meta, maxLines = 1) }
        Box(Modifier.size(KitSpec.Progress.BAR_W.dp, KitSpec.Progress.BAR_H.dp).clip(CircleShape).background(c.soft)) {
            Box(Modifier.height(KitSpec.Progress.BAR_H.dp).width((KitSpec.Progress.BAR_W * shown.coerceIn(0f, 1f)).dp).clip(CircleShape).background(c.ink))
        }
    }
}

@Composable
private fun indeterminate(): Float {
    val t = rememberInfiniteTransition(label = "progress")
    val v = t.animateFloat(0.15f, 0.85f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "bar")
    return v.value
}

/**
 * P1-SH-02 `CloudChip` (A-team `.cloud-chip`): "● Cloud on · $0.055/h" (the dot is drawn, not typed) under the top bar while a cloud computer runs.
 * Hidden until P7: draws nothing unless [CloudChipSpec.ENABLED] (or [force], for shots) and [running].
 */
@Composable
fun CloudChip(
    running: Boolean,
    costPerHour: Double?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    force: Boolean = false,
) {
    if (!running || !(CloudChipSpec.ENABLED || force)) return
    val c = Argus.colors
    val text = CloudChipSpec.text(costPerHour)
    Box(modifier.heightIn(min = KitSpec.TARGET.dp).clickable(role = Role.Button, onClick = onClick)
        .clearAndSetSemantics { contentDescription = "$text. Open computers"; role = Role.Button; onClick { onClick(); true } },
        contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = KitSpec.Cloud.HEIGHT.dp).argusGlass(GlassLevel.RAISED, RoundedCornerShape(ArgusTokens.Radius.PILL.dp), shadow = false)
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(c.success))
            Text(text, style = ArgusType.statusChip, color = c.ink, maxLines = 1)
        }
    }
}
