package com.argus.assistant.ui.argus.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.argus.assistant.i18n.tr
import com.argus.assistant.ui.PlusItem
import com.argus.assistant.ui.argus.Argus
import com.argus.assistant.ui.argus.ArgusTokens
import com.argus.assistant.ui.argus.ArgusType
import com.argus.assistant.ui.argus.icons.Lucide

/**
 * Owner 2026-11-15: the + menu drawn inside the composer's glass, above its field row ([ArgusComposer]'s `menu`), so it
 * reads as the chat box opening up rather than a sheet over it ([PlusMenuSpec]). Tiles first (as many per row as fit,
 * wrapping instead of clipping), then compact rows, then a hairline above the field. Taller than [PlusMenuSpec.maxHeightDp]
 * it scrolls. [onPick] gets the entry's id; the caller closes the menu and runs it.
 */
@Composable
fun ComposerPlusMenu(items: List<PlusItem>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Argus.colors
    val tiles = items.filter { it.tile }
    val rows = items.filterNot { it.tile }
    val maxH = PlusMenuSpec.maxHeightDp(LocalConfiguration.current.screenHeightDp)
    Column(
        modifier.fillMaxWidth().heightIn(max = maxH.dp).verticalScroll(rememberScrollState()).testTag(PlusMenuSpec.TAG)
            .padding(start = PlusMenuSpec.PAD.dp, end = PlusMenuSpec.PAD.dp, top = PlusMenuSpec.PAD.dp),
    ) {
        if (tiles.isNotEmpty()) PlusTiles(tiles, onPick)
        if (tiles.isNotEmpty() && rows.isNotEmpty()) Spacer(Modifier.height(4.dp))
        rows.forEach { r -> PlusRow(r) { onPick(r.id) } }
        // the field row sits under a hairline, inside the same glass
        Box(Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp).fillMaxWidth().height(1.dp).background(c.line))
    }
}

private fun tileLabel() = ArgusType.meta.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium)

@Composable
private fun PlusTiles(tiles: List<PlusItem>, onPick: (String) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = tileLabel()
    // the widest label as it will really be drawn (font scale and app language included)
    val widest = remember(tiles, density, style) {
        tiles.maxOf { t ->
            with(density) { measurer.measure(tr(PlusMenuSpec.label(t.id, t.label)), style, softWrap = false, maxLines = 1).size.width.toDp().value }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cols = PlusMenuSpec.columns(maxWidth.value, tiles.size, PlusMenuSpec.minTile(widest))
        Column(verticalArrangement = Arrangement.spacedBy(PlusMenuSpec.GAP.dp)) {
            tiles.chunked(cols).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PlusMenuSpec.GAP.dp)) {
                    line.forEach { t -> PlusTile(t, Modifier.weight(1f)) { onPick(t.id) } }
                    repeat(cols - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PlusTile(t: PlusItem, modifier: Modifier, onClick: () -> Unit) {
    val c = Argus.colors
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(ArgusTokens.Radius.ROW.dp)
    Column(
        modifier.height(PlusMenuSpec.TILE_HEIGHT.dp).kitPress(src).clip(shape).background(c.soft)
            .clickable(src, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}.testTag(PlusMenuSpec.tileTag(t.id))
            .padding(horizontal = PlusMenuSpec.TILE_LABEL_PAD.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(Lucide.icon(PlusMenuSpec.icon(t.id, t.on), PlusMenuSpec.TILE_ICON), null, Modifier.size(PlusMenuSpec.TILE_ICON.dp), tint = c.ink)
        Spacer(Modifier.height(6.dp))
        // never ellipsised: the tile is at least as wide as this label (PlusMenuSpec.columns)
        Text(tr(PlusMenuSpec.label(t.id, t.label)), style = tileLabel(), color = c.ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
    }
}

@Composable
private fun PlusRow(r: PlusItem, onClick: () -> Unit) {
    val c = Argus.colors
    val src = remember { MutableInteractionSource() }
    val tint = if (r.on) c.cobaltText else c.ink
    Row(
        Modifier.fillMaxWidth().heightIn(min = PlusMenuSpec.ROW_MIN.dp).kitPress(src).clip(RoundedCornerShape(ArgusTokens.Radius.ROW.dp))
            .clickable(src, indication = null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}.testTag(PlusMenuSpec.rowTag(r.id))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.icon(PlusMenuSpec.icon(r.id, r.on), PlusMenuSpec.ROW_ICON), null, Modifier.size(PlusMenuSpec.ROW_ICON.dp), tint = tint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(tr(r.label), style = ArgusType.tool.copy(fontSize = 15.sp, lineHeight = 20.sp), color = tint)
            if (r.sub.isNotBlank()) Text(tr(r.sub), style = ArgusType.meta, color = c.mute)
        }
        if (PlusMenuSpec.opensPage(r.id)) {
            Spacer(Modifier.width(8.dp))
            Icon(Lucide.icon("chevron-right", 18), null, Modifier.size(18.dp), tint = c.mute)
        }
    }
}
