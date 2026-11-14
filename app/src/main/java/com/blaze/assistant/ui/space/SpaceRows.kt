package com.blaze.assistant.ui.space

import com.blaze.assistant.i18n.tr
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.BackTitle
import com.blaze.assistant.ui.BlazeIconStyle
import com.blaze.assistant.ui.BlazeSwitchTrack
import com.blaze.assistant.ui.IconTile
import com.blaze.assistant.ui.LeadIcon
import com.blaze.assistant.ui.MenuRowShape
import com.blaze.assistant.ui.MotionTokens

/*
 * Unification 5/9: the shell's list parts, shared by the profile page and every settings page. They grew out of the
 * profile page's own group / row / hairline, so a settings page, a project's chat list and the profile root read as one
 * material: a solid rounded group (SpaceLook.cardSolid), rows that lead with the round icon tile, inset hairlines, the
 * one switch, one text field, one segmented pill, one menu card and one sub-page frame (back circle at the gutter).
 * Sizes and type come from SpaceInsets, SpaceIcons and SpaceTypeScale only.
 */

/**
 * Row heights. [LARGE] = the profile rows (a 40 dp tile with 12 dp above and below, 64 dp), [REGULAR] = settings rows
 * (56 dp, 14 dp padding), [COMPACT] = dense lists (chats, skills, schedules: a 32 dp tile, 52 dp).
 */
enum class SpaceRowSize(val minDp: Int, val padVDp: Int) {
    LARGE(SpaceInsets.ROW_MIN_LARGE_DP, (SpaceInsets.ROW_MIN_LARGE_DP - SpaceIcons.TILE_DP) / 2),
    REGULAR(SpaceInsets.ROW_MIN_DP, SpaceInsets.ROW_PAD_V_DP),
    COMPACT(SpaceInsets.CONTROL_DP + 8, (SpaceInsets.CONTROL_DP + 8 - SpaceIcons.SMALL_DP) / 2);

    /** The row's title style: [SpaceType.rowTitle], or the meta size in medium weight for [COMPACT]. */
    fun title(color: Color): TextStyle =
        if (this == COMPACT) SpaceType.body(SpaceTypeScale.META.sp, color, FontWeight.Medium) else SpaceType.rowTitle(color)

    /** The second line: [SpaceType.meta], or [SpaceType.caption] for [COMPACT]. */
    fun sub(color: Color): TextStyle = if (this == COMPACT) SpaceType.caption(color) else SpaceType.meta(color)

    /** The gap after the leading tile. */
    val gapDp: Int get() = if (this == COMPACT) 12 else SpaceIcons.GAP_DP
}

/** A grouped card: the shell's solid card ([SpaceLook.cardSolid]), the card radius. Rows inside are split by [SpaceHairline]. */
@Composable
fun SpaceGroup(modifier: Modifier = Modifier, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val look = spaceLook()
    Column(modifier.fillMaxWidth().clip(SpaceShapes.card).background(look.cardSolid)
        .let { if (tag != null) it.testTag(tag) else it }, content = content)
}

/** The hairline between two rows of a group, inset like the rows' text ([SpaceInsets.CARD_PAD_DP] by default). */
@Composable
fun SpaceHairline(inset: Dp = SpaceInsets.CARD_PAD_DP.dp) {
    val look = spaceLook()
    Box(Modifier.padding(horizontal = inset).fillMaxWidth().height(1.dp).background(look.hair))
}

/** A small heading over a group ("Voice and assistant"), the caption size in the sub colour, aligned with the rows' text. */
@Composable
fun SpaceSectionLabel(text: String, modifier: Modifier = Modifier) {
    val look = spaceLook()
    Text(text, modifier = modifier.padding(start = SpaceInsets.CARD_PAD_DP.dp, end = SpaceInsets.CARD_PAD_DP.dp, top = 18.dp, bottom = 8.dp),
        style = SpaceType.body(SpaceTypeScale.CAPTION.sp, look.sub, FontWeight.Medium))
}

/**
 * One list row: an optional round icon tile ([icon], tinted [iconTint], solid when [iconFilled]) or any [lead], the
 * title (one line, [titleEnd] right after it, e.g. a tag) over an optional [sub] line, then [trailing] and a chevron.
 * [onClick] (the trailing lambda) and [onLongClick] make it a button; [padStart] / [padEnd] are the row's own side
 * insets (the group's card padding by default; 0 for rows that sit straight on a padded page). [titleStyle] overrides
 * the size's title style (mono or dimmed titles).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpaceRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String = "",
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    iconFilled: Boolean = false,
    lead: (@Composable () -> Unit)? = null,
    size: SpaceRowSize = SpaceRowSize.REGULAR,
    titleStyle: TextStyle? = null,
    subLines: Int = 2,
    chevron: Boolean = false,
    tag: String? = null,
    padStart: Dp = SpaceInsets.CARD_PAD_DP.dp,
    padEnd: Dp = SpaceInsets.CARD_PAD_DP.dp,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    titleEnd: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val look = spaceLook()
    Row(modifier.fillMaxWidth().heightIn(min = size.minDp.dp)
        .let { if (tag != null) it.testTag(tag) else it }
        .let {
            if (onClick == null) it
            else it.combinedClickable(onClickLabel = onClickLabel ?: title, onClick = onClick, onLongClick = onLongClick).semantics { role = Role.Button }
        }
        .padding(start = padStart, end = padEnd, top = size.padVDp.dp, bottom = size.padVDp.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            IconTile(icon, tint = if (iconTint == Color.Unspecified) look.accent else iconTint, small = size == SpaceRowSize.COMPACT, filled = iconFilled)
            Spacer(Modifier.width(size.gapDp.dp))
        } else if (lead != null) {
            lead()
            Spacer(Modifier.width(size.gapDp.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = titleStyle ?: size.title(look.ink))
                titleEnd?.invoke(this)
            }
            if (sub.isNotBlank()) Text(sub, maxLines = subLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                style = size.sub(look.sub))
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); Row(verticalAlignment = Alignment.CenterVertically, content = trailing) }
        if (chevron) { Spacer(Modifier.width(8.dp)); ChevronMark(look.ink) }
    }
}

/** A row with the one switch ([BlazeSwitchTrack]) at its end; the whole row toggles and reads as a switch. */
@Composable
fun SpaceToggleRow(
    title: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    sub: String = "",
    icon: ImageVector? = null,
    size: SpaceRowSize = SpaceRowSize.REGULAR,
    tag: String? = null,
    padStart: Dp = SpaceInsets.CARD_PAD_DP.dp,
    padEnd: Dp = SpaceInsets.CARD_PAD_DP.dp,
    onToggle: () -> Unit,
) {
    SpaceRow(title, modifier.toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() }), sub = sub, icon = icon, size = size,
        tag = tag, padStart = padStart, padEnd = padEnd, trailing = { BlazeSwitchTrack(on) })
}

/**
 * The one text field of the shell: the track fill with a hairline that turns accent when focused. Single line = a pill
 * [SpaceInsets.CONTROL_DP] tall; multi-line = a tile-radius box at least [minHeight]. [desc] names it for TalkBack.
 */
@Composable
fun SpaceField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minHeight: Dp = if (singleLine) SpaceInsets.CONTROL_DP.dp else 120.dp,
    desc: String = hint,
    textSize: TextUnit = SpaceTypeScale.BODY.sp,
) {
    val look = spaceLook()
    var focused by remember { mutableStateOf(false) }
    val shape = if (singleLine) SpaceShapes.pill else SpaceShapes.tile
    Box(modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(look.track).border(1.dp, if (focused) look.accent else look.hair, shape)
        .padding(horizontal = if (singleLine) 18.dp else 16.dp, vertical = if (singleLine) 10.dp else 14.dp),
        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
        if (value.isEmpty()) Text(hint, maxLines = if (singleLine) 1 else 4, overflow = TextOverflow.Ellipsis, style = SpaceType.body(textSize, look.sub))
        BasicTextField(value, onChange, singleLine = singleLine, textStyle = SpaceType.body(textSize, look.ink), cursorBrush = SolidColor(look.accent),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = desc })
    }
}

/**
 * The shell's segmented pill (the top bar's look, for any two- to four-way choice): the track, the selected segment in
 * the selected fill, [SpaceInsets.CONTROL_DP] tall overall. [options] are (id, label) pairs.
 * [scroll]: for more options than fit a narrow phone (Gallery's five): the pill keeps each segment's own width and
 * scrolls sideways inside [modifier], with [edge] of room before and after it (the gutter, so it lines up with the page).
 */
@Composable
fun SpaceSegmented(options: List<Pair<String, String>>, value: String, modifier: Modifier = Modifier, tag: String? = null,
                   scroll: Boolean = false, edge: Dp = 0.dp, onPick: (String) -> Unit) {
    if (scroll) {
        Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = edge)) { SegmentTrack(options, value, Modifier, tag, false, onPick) }
    } else SegmentTrack(options, value, modifier, tag, true, onPick)
}

@Composable
private fun SegmentTrack(options: List<Pair<String, String>>, value: String, modifier: Modifier, tag: String?, share: Boolean, onPick: (String) -> Unit) {
    val look = spaceLook()
    Row(modifier.clip(SpaceShapes.pill).background(look.track).let { if (tag != null) it.testTag(tag) else it }
        .padding(SpaceInsets.PILL_FRAME_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((id, label) in options) {
            val on = id == value
            val fill by animateColorAsState(if (on) look.selectedFill else Color.Transparent, MotionTokens.medium(), label = "spaceSegment")
            val ink by animateColorAsState(if (on) look.selectedInk else look.sub, MotionTokens.medium(), label = "spaceSegmentInk")
            Box((if (share) Modifier.weight(1f, fill = false) else Modifier).clip(SpaceShapes.pill).background(fill)
                .clickable(onClickLabel = tr("Show {0}", label)) { onPick(id) }
                .semantics { selected = on; role = Role.Tab }
                .heightIn(min = SpaceInsets.SEGMENT_DP.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(label, maxLines = 1, softWrap = false, style = SpaceType.body(SpaceTypeScale.META.sp, ink, FontWeight.Medium))
            }
        }
    }
}

/** One item of [SpaceMenuCard]: a label, an optional leading icon, what it does. */
data class SpaceMenuItem(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

/**
 * The shell's menu card: the opaque menu colour ([SpaceLook.menu]), the card radius, a hairline, rows that round to the
 * card less their inset. Rows without an icon line up with the text of rows that have one.
 */
@Composable
fun SpaceMenuCard(items: List<SpaceMenuItem>, onDismiss: () -> Unit = {}) {
    val look = spaceLook()
    val shape = SpaceShapes.card
    val anyIcon = items.any { it.icon != null }
    Column(Modifier.widthIn(min = 200.dp, max = 280.dp).shadow(12.dp, shape).background(look.menu, shape)
        .border(1.dp, look.hair, shape).padding(vertical = 8.dp)) {
        for (item in items) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(MenuRowShape).clickable(onClickLabel = item.label) { onDismiss(); item.onClick() }
                .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val icon = item.icon
                if (icon != null) LeadIcon(icon, look.ink)
                else if (anyIcon) Spacer(Modifier.width(BlazeIconStyle.rowIcon))
                if (anyIcon) Spacer(Modifier.width(BlazeIconStyle.gap))
                Text(item.label, style = SpaceType.body(SpaceTypeScale.META.sp, look.ink))
            }
        }
    }
}

/** [SpaceMenuCard] in a popup, dismissed by a tap outside. Shows nothing unless [expanded]. */
@Composable
fun SpaceMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<SpaceMenuItem>, alignment: Alignment = Alignment.TopEnd) {
    if (!expanded) return
    androidx.compose.ui.window.Popup(alignment = alignment, onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
        SpaceMenuCard(items, onDismiss)
    }
}

/**
 * A settings sub-page: the header ([BackTitle]: back circle at the gutter, the serif title, [right] actions) over a
 * scrolling column inset by the gutter ([SpaceInsets.GUTTER_DP]), with room at the bottom for the floating pills.
 */
@Composable
fun SpaceSubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    eyebrow: String = "",
    tag: String? = null,
    right: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().let { if (tag != null) it.testTag(tag) else it }) {
        BackTitle(title, onBack = onBack, eyebrow = eyebrow, right = right)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            .padding(start = SpaceInsets.GUTTER_DP.dp, end = SpaceInsets.GUTTER_DP.dp, bottom = 110.dp), content = content)
    }
}
