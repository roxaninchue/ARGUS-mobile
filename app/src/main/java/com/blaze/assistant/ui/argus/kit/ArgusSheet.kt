package com.blaze.assistant.ui.argus.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected as semSelected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * `.sheet` (04 §2.8 #32): handle 40 x 5, title 19/650, scrolling content, and a sticky action row at the bottom
 * (Cancel glass · Primary ink on the thumb side). Opens at most ~60 % unless content asks for more (skipPartiallyExpanded = false).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArgusSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    primary: Pair<String, () -> Unit>? = null,
    cancel: String? = if (primary != null) "Cancel" else null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val look = kitLook()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, modifier = modifier,
        shape = KitShapes.sheet, containerColor = look.sheet, scrimColor = look.scrim, tonalElevation = 0.dp,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(KitSize.SHEET_HANDLE_W.dp, KitSize.SHEET_HANDLE_H.dp).clip(CircleShape).background(look.line2)) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            if (title != null) Text(title, style = KitType.style(KitText.SHEET_TITLE, look.ink),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() })
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), content = content)
            if (primary != null || cancel != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cancel != null) ArgusButton(cancel, onDismiss, Modifier.weight(1f))
                    if (primary != null) ArgusButton(primary.first, primary.second, Modifier.weight(1f), kind = ButtonSpec.Kind.PRIMARY)
                }
            }
        }
    }
}

/** One row of [MentionPopup]. [leading] is the orb (bots), the icon (commands, files). */
data class MentionItem(val key: String, val title: String, val sub: String = "")

/**
 * `.mention` (04 §2.8 #5): a sheet-coloured card above the composer listing bots (orb xs + name + role), commands
 * (`/`) or files (`#`), 44 dp rows, at most [Mention.MAX_ROWS]. The caller filters with [Mention.filter].
 */
@Composable
fun MentionPopup(
    items: List<MentionItem>,
    onPick: (MentionItem) -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (MentionItem) -> Unit,
) {
    if (items.isEmpty()) return
    val look = kitLook()
    LazyColumn(modifier.fillMaxWidth().heightIn(max = (KitSize.MENTION_ROW * Mention.MAX_ROWS + 12).dp)
        .clip(KitShapes.card).background(look.sheet).padding(vertical = 6.dp)) {
        items(items.take(Mention.MAX_ROWS), key = { it.key }) { m ->
            Row(Modifier.fillMaxWidth().heightIn(min = KitSize.MENTION_ROW.dp).clickable(onClickLabel = "Pick ${m.title}") { onPick(m) }
                .clearAndSetSemantics { contentDescription = listOf(m.title, m.sub).filter { it.isNotBlank() }.joinToString(", "); role = Role.Button }
                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { leading(m) }
                Spacer(Modifier.width(10.dp))
                Text(m.title, maxLines = 1, style = KitType.style(KitText.BODY_STRONG, look.ink))
                if (m.sub.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(m.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KitType.style(KitText.META, look.mute), modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** One pending attachment. [thumb] null = a file icon. */
data class AttachmentItem(val key: String, val name: String, val thumb: ImageBitmap? = null)

/** `.attbar/.attc` (04 §2.8 #6): 52 dp tiles (40 dp thumb + name + remove). Takes the chips row's slot. */
@Composable
fun AttachmentBar(items: List<AttachmentItem>, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val look = kitLook()
    LazyRow(modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items, key = { it.key }) { a ->
            Row(Modifier.height(KitSize.ATTACH_TILE.dp).kitSurface(look, KitShapes.tool, raised = true).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(KitSize.ATTACH_THUMB.dp).clip(KitShapes.field).background(look.soft), contentAlignment = Alignment.Center) {
                    if (a.thumb != null) androidx.compose.foundation.Image(BitmapPainter(a.thumb), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(KitSize.ATTACH_THUMB.dp))
                    else Icon(Icons.Rounded.Description, contentDescription = null, tint = look.mute, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(Attach.shortName(a.name), maxLines = 1, style = KitType.style(KitText.META, look.ink))
                ArgusIconButton(Icons.Rounded.Close, "Remove ${a.name}", { onRemove(a.key) }, kind = IconButtonKind.FLAT, visual = 28)
            }
        }
    }
}

/** A plain clickable row used inside sheets for simple choices (radio-like), 48 dp. */
@Composable
fun SheetChoice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = kitLook()
    Row(modifier.fillMaxWidth().heightIn(min = KitSize.TOUCH.dp).clip(KitShapes.row).clickable(onClickLabel = text, onClick = onClick)
        .semantics(mergeDescendants = true) { role = Role.RadioButton; semSelected = selected }
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = KitType.style(KitText.BODY, look.ink), modifier = Modifier.weight(1f))
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (selected) look.ink else look.soft))
    }
}
