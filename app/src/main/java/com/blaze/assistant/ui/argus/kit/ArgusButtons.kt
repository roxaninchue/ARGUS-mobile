package com.blaze.assistant.ui.argus.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Press feedback (04 §2.6 motion.press): 0.97 while held. */
@Composable
internal fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    return if (pressed) 0.97f else 1f
}

/**
 * `.btn` / `.btn.p` / `.btn.d` (04 §2.9). Glass = raised glass with a rim; Primary = ink fill with the top gloss;
 * Danger = red 16 % fill, red 50 % rim, AA red text. The hit area is at least 48 dp tall.
 */
@Composable
fun ArgusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonSpec.Kind = ButtonSpec.Kind.GLASS,
    size: ButtonSpec.Size = ButtonSpec.Size.M,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val look = kitLook()
    val source = remember { MutableInteractionSource() }
    val shape = if (size == ButtonSpec.Size.S) KitShapes.buttonSm else KitShapes.button
    val danger = Color(ButtonSpec.dangerText(look.dark).toInt())
    val (fill, ink, rim) = when (kind) {
        ButtonSpec.Kind.PRIMARY -> Triple(look.ink, look.onInk, Color.Transparent)
        ButtonSpec.Kind.DANGER -> Triple(look.danger.copy(alpha = ButtonSpec.DANGER_FILL_ALPHA), danger, look.danger.copy(alpha = ButtonSpec.DANGER_RIM_ALPHA))
        ButtonSpec.Kind.GLASS -> Triple(look.glass2, look.ink, look.rim)
    }
    Box(modifier.heightIn(min = KitSize.TOUCH.dp).semantics { role = Role.Button }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClickLabel = text, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Row(Modifier.scale(pressScale(source)).heightIn(min = size.heightDp.dp).clip(shape).background(fill)
            .then(if (kind == ButtonSpec.Kind.PRIMARY) Modifier.background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.20f), 0.6f to Color.Transparent)) else Modifier)
            .border(BorderStroke(1.dp, rim), shape)
            .padding(horizontal = if (size == ButtonSpec.Size.S) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = ink.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(if (size == ButtonSpec.Size.S) 16.dp else 18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, maxLines = 1, style = KitType.style(KitText.Style(size.textSp, size.textSp + 5, 600), ink.copy(alpha = if (enabled) 1f else 0.4f)))
        }
    }
}

enum class IconButtonKind { GLASS, FLAT, AVATAR, PRIMARY }

/**
 * `.ib` / `.ib.flat` / `.me` (04 §2.9): a 38 dp visual inside a 48 dp target. [desc] is required (it is the only
 * label TalkBack has). [badge] draws the 8 dp dot (bell with something waiting).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArgusIconButton(
    icon: ImageVector?,
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: IconButtonKind = IconButtonKind.GLASS,
    badge: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    visual: Int = KitSize.ICON_BUTTON,
    tint: Color? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val look = kitLook()
    val source = remember { MutableInteractionSource() }
    Box(modifier.size(KitSize.target(visual).dp)
        .combinedClickable(interactionSource = source, indication = null, onClickLabel = desc, onClick = onClick,
            onLongClick = onLongClick, onLongClickLabel = longClickLabel)
        .semantics { contentDescription = desc; role = Role.Button }, contentAlignment = Alignment.Center) {
        val disc = Modifier.scale(pressScale(source)).size(visual.dp).clip(CircleShape)
        val styled = when (kind) {
            IconButtonKind.GLASS -> disc.background(look.glass2).border(BorderStroke(1.dp, look.rim), CircleShape)
            IconButtonKind.FLAT -> disc
            IconButtonKind.AVATAR, IconButtonKind.PRIMARY -> disc.background(look.ink)
        }
        val ink = tint ?: if (kind == IconButtonKind.AVATAR || kind == IconButtonKind.PRIMARY) look.onInk else look.ink
        Box(styled, contentAlignment = Alignment.Center) {
            when {
                content != null -> content()
                icon != null -> Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(if (visual >= 38) 20.dp else 16.dp))
            }
        }
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(8.dp).clip(CircleShape).background(look.danger))
    }
}

/** `.field` (04 §2.9): glass fill, 12 dp radius, 16 sp input (no zoom), focus rim. */
@Composable
fun ArgusField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    label: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onIme: () -> Unit = {},
) {
    val look = kitLook()
    androidx.compose.foundation.layout.Column(modifier) {
        if (label != null) {
            Text(label, style = KitType.style(KitText.META, look.mute), modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        BasicTextField(
            value = value, onValueChange = onValue, singleLine = singleLine,
            textStyle = KitType.style(KitText.INPUT, look.ink),
            cursorBrush = SolidColor(look.ink),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onIme() }),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = KitSize.TOUCH.dp)
                .clip(KitShapes.field).background(look.soft).border(BorderStroke(1.dp, look.line), KitShapes.field)
                .semantics { contentDescription = label ?: placeholder },
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = KitType.style(KitText.INPUT, look.dim))
                    inner()
                }
            },
        )
    }
}

/** `.search` (04 §2.9): the pill search field with a clear button. Used by Settings' floating search and in-place list search. */
@Composable
fun ArgusSearch(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
) {
    val look = kitLook()
    Row(modifier.heightIn(min = KitSize.TOUCH.dp)
        .then(if (floating) Modifier.kitChrome(look, KitShapes.pill) else Modifier.clip(KitShapes.pill).background(look.soft))
        .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = look.mute, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        BasicTextField(value = value, onValueChange = onValue, singleLine = true,
            textStyle = KitType.style(KitText.INPUT, look.ink), cursorBrush = SolidColor(look.ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, maxLines = 1, style = KitType.style(KitText.INPUT, look.dim))
                    inner()
                }
            })
        if (value.isNotEmpty()) ArgusIconButton(Icons.Rounded.Close, "Clear search", { onValue("") }, kind = IconButtonKind.FLAT, visual = 30)
        else Spacer(Modifier.size(KitSize.TOUCH.dp))
    }
}
