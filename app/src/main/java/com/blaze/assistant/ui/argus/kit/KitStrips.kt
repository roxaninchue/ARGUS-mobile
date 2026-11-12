package com.blaze.assistant.ui.argus.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.OrbSize
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Lucide
import kotlin.math.roundToInt

/**
 * P1-SH-02 `PinStrip` (A-team `.pins`): up to four lg orbs with first-name labels, a red 13 dp dot when overdue. Tap
 * opens; long-press and drag sideways reorders ([onReorder] gets the new order). TalkBack: one node per pin with
 * "Move left" / "Move right" actions in place of the drag.
 */
@Composable
fun PinStrip(
    pins: List<PinModel>,
    onOpen: (PinModel) -> Unit,
    modifier: Modifier = Modifier,
    onReorder: ((List<PinModel>) -> Unit)? = null,
    avatar: @Composable (PinModel) -> Unit = { KitAvatar(it.name, it.manager, OrbSize.LG) },
) {
    val c = Argus.colors
    val shown = Pins.visible(pins)
    if (shown.isEmpty()) return
    val slotPx = with(LocalDensity.current) { (OrbSize.LG + 18f).dp.toPx() }
    val latest by rememberUpdatedState(shown)
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        shown.forEachIndexed { i, pin ->
            val actions = buildList {
                if (onReorder != null && i > 0) add(CustomAccessibilityAction("Move left") { onReorder(Pins.move(shown, i, i - 1)); true })
                if (onReorder != null && i < shown.lastIndex) add(CustomAccessibilityAction("Move right") { onReorder(Pins.move(shown, i, i + 1)); true })
            }
            val drag = if (onReorder == null) Modifier else Modifier.pointerInput(pin.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragFrom = latest.indexOfFirst { it.id == pin.id }; dragDx = 0f },
                    onDragEnd = {
                        val from = dragFrom
                        if (from >= 0) { val to = from + (dragDx / slotPx).roundToInt(); if (to != from) onReorder(Pins.move(latest, from, to)) }
                        dragFrom = -1; dragDx = 0f
                    },
                    onDragCancel = { dragFrom = -1; dragDx = 0f },
                    onDrag = { ch, d -> ch.consume(); dragDx += d.x },
                )
            }
            val moving = dragFrom == i
            Column(
                Modifier
                    .then(if (moving) Modifier.offset { IntOffset(dragDx.roundToInt(), 0) } else Modifier)
                    .then(drag)
                    .clickable(role = Role.Button) { onOpen(pin) }
                    .clearAndSetSemantics {
                        contentDescription = KitA11y.pin(pin); role = Role.Button
                        onClick(label = "Open") { onOpen(pin); true }
                        if (actions.isNotEmpty()) customActions = actions
                    }
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(OrbSize.LG.dp)) {
                    avatar(pin)
                    if (pin.overdue) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(KitSpec.Pins.OVERDUE_DOT.dp).clip(CircleShape)
                        .background(c.danger).border(2.dp, c.bg, CircleShape))
                }
                Text(Pins.label(pin.name), style = ArgusType.meta, color = c.ink, maxLines = 1, modifier = Modifier.padding(top = 4.dp).width(OrbSize.LG.dp + 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/**
 * P1-SH-02 `NeedYouBanner` (A-team `.need`): full-width glossy ink banner, flag icon, "**3 need you** · Nova, Atlas" and
 * "Review ›". Amber when only approvals wait; draws nothing at zero. Static until P2: callers pass the numbers.
 */
@Composable
fun NeedYouBanner(
    count: Int,
    bots: List<String>,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
    approvalsOnly: Boolean = false,
) {
    val v = NeedYou.variant(count, approvalsOnly)
    if (v == NeedYou.Variant.HIDDEN) return
    val p = Argus.colors.palette
    val fill = kc(KitSpec.bannerFill(p, v))
    val fg = kc(KitSpec.bannerText(p, v))
    val shape = RoundedCornerShape(ArgusTokens.Radius.BANNER.dp)
    val names = NeedYou.names(bots)
    val desc = KitA11y.banner(count, bots)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .clip(shape).background(fill)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.04f))))
            .clickable(role = Role.Button, onClick = onReview)
            .clearAndSetSemantics { contentDescription = desc; role = Role.Button; onClick(label = NeedYou.ACTION) { onReview(); true } }
            .heightIn(min = KitSpec.Banner.MIN_HEIGHT.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Lucide.icon("flag", KitSpec.Banner.ICON), null, Modifier.size(KitSpec.Banner.ICON.dp), tint = fg)
        Text(buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(NeedYou.headline(count)) }
            if (names.isNotEmpty()) append(" · $names")
        }, style = ArgusType.sub, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("${NeedYou.ACTION} ›", style = ArgusType.bodyStrong, color = fg, maxLines = 1)
    }
}

/**
 * P1-SH-02 `FilterChips` (A-team `.seg`, scrolling): 99 dp pills, on = ink with onInk text, off = glass; a count after
 * the label at 60 %. Each chip is a 48 dp-tall target and a selectable tab for TalkBack.
 */
@Composable
fun FilterChips(
    chips: List<ChipModel>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(KitSpec.Chips.GAP.dp)) {
        itemsIndexed(chips, key = { _, it -> it.key }) { _, chip -> KitChip(chip, chip.key == selected) { onSelect(chip.key) } }
    }
}

/** P1-SH-02 `Segmented`: the same pills sharing the row's width (Desk tabs, Appearance). */
@Composable
fun Segmented(
    options: List<ChipModel>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.PILL.dp), shadow = false).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { o -> KitChip(o, o.key == selected, Modifier.weight(1f), flat = true) { onSelect(o.key) } }
    }
}

@Composable
internal fun KitChip(chip: ChipModel, on: Boolean, modifier: Modifier = Modifier, flat: Boolean = false, onClick: () -> Unit) {
    val c = Argus.colors
    val shape = RoundedCornerShape(ArgusTokens.Radius.PILL.dp)
    val src = remember { MutableInteractionSource() }
    val fg = if (on) c.onInk else c.ink
    val look = when {
        on -> Modifier.clip(shape).background(c.ink)
        flat -> Modifier.clip(shape)
        else -> Modifier.argusGlass(GlassLevel.RAISED, shape, shadow = false)
    }
    val desc = KitA11y.chip(chip, on)
    Box(
        modifier.heightIn(min = KitSpec.TARGET.dp).widthIn(min = KitSpec.TARGET.dp)
            .clickable(src, indication = null, role = Role.Tab, onClick = onClick)
            .clearAndSetSemantics { contentDescription = desc; role = Role.Tab; selected = on; onClick { onClick(); true } },
        contentAlignment = Alignment.Center,
    ) {
        Row(look.kitPress(src).heightIn(min = KitSpec.Chips.HEIGHT.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
            Text(chip.label, style = ArgusType.chip, color = fg, maxLines = 1)
            Chips.countText(chip)?.let { Text(it, style = ArgusType.chip, color = fg.copy(alpha = KitSpec.Chips.COUNT_ALPHA), maxLines = 1) }
        }
    }
}
