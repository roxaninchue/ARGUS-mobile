package com.blaze.assistant.ui.argus.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** One choice in [FilterChips] / [Segmented]. */
data class ChipItem(val id: String, val label: String, val count: Int? = null)

/** `.chipb` (04 §2.8 #4): an action chip (frosted pill, 15 dp icon, 13.5 sp). 48 dp hit area. */
@Composable
fun ArgusChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val look = kitLook()
    Box(modifier.heightIn(min = KitSize.TOUCH.dp).clickable(onClickLabel = text, onClick = onClick)
        .clearAndSetSemantics { contentDescription = text; role = Role.Button }, contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = 36.dp).kitChrome(look, KitShapes.banner).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, contentDescription = null, tint = look.ink, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)) }
            Text(text, maxLines = 1, style = KitType.style(KitText.CHIP, look.ink))
        }
    }
}

/**
 * `.seg` (04 §2.8 #13): 99 dp pills, the selected one ink with onInk text; counts at 60 %. Scrolls sideways when it
 * doesn't fit (domain filters, Desk sections).
 */
@Composable
fun FilterChips(
    items: List<ChipItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = KitSize.PAGE.dp),
) {
    LazyRow(modifier, contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items, key = { it.id }) { c -> SegmentPill(c, c.id == selected) { onSelect(c.id) } }
    }
}

/** A fixed row of pills (no scrolling) for 2-4 choices, e.g. Appearance's Match phone / Dark / Light. */
@Composable
fun Segmented(items: List<ChipItem>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val look = kitLook()
    Row(modifier.clip(KitShapes.pill).background(look.soft).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (c in items) SegmentPill(c, c.id == selected, flat = true) { onSelect(c.id) }
    }
}

@Composable
private fun SegmentPill(c: ChipItem, on: Boolean, flat: Boolean = false, onClick: () -> Unit) {
    val look = kitLook()
    val fill = if (on) look.ink else if (flat) Color.Transparent else look.glass2
    val ink = if (on) look.onInk else look.ink
    Box(Modifier.heightIn(min = KitSize.TOUCH.dp).clickable(onClickLabel = "Show ${c.label}", onClick = onClick)
        .clearAndSetSemantics { contentDescription = Chips.spoken(c.label, c.count, on); selected = on; role = Role.Tab },
        contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = 34.dp).clip(KitShapes.pill).background(fill)
            .then(if (!on && !flat) Modifier.border(BorderStroke(1.dp, look.rim), KitShapes.pill) else Modifier)
            .padding(horizontal = 14.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(buildAnnotatedString {
                append(c.label)
                if (c.count != null && c.count > 0) withStyle(SpanStyle(color = ink.copy(alpha = 0.6f))) { append(" ${c.count}") }
            }, maxLines = 1, style = KitType.style(KitText.CHIP, ink, tnum = true))
        }
    }
}
