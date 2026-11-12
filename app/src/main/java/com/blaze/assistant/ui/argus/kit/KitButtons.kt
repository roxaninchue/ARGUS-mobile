package com.blaze.assistant.ui.argus.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusColors
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Lucide
import com.blaze.assistant.ui.argus.icons.LucideSpec

internal fun kc(argb: Long) = ArgusColors.c(argb)

/** Press feedback shared by the kit: scale to 0.97 over 150 ms (instant with reduced motion). */
@Composable
internal fun Modifier.kitPress(source: MutableInteractionSource, reducedMotion: Boolean = com.blaze.assistant.ui.argus.settings.ArgusPrefs.reducedNow()): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) ArgusTokens.Motion.PRESS_SCALE else 1f,
        tween(ArgusTokens.Motion.duration(ArgusTokens.Motion.PRESS_MS, reducedMotion)), label = "press")
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

/**
 * P1-SH-02 `ArgusButton` (`.btn`, `.btn.p`, `.btn.d`, `.btn.sm`): Glass = raised glass, Primary = ink with onInk text,
 * Danger = red tint + 50 % red border + red text (AA values in [KitSpec.dangerText]). Small buttons still take a 48 dp
 * target ([minimumInteractiveComponentSize]).
 */
@Composable
fun ArgusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.GLASS,
    size: ButtonSize = ButtonSize.M,
    icon: String? = null,
    enabled: Boolean = true,
) {
    val c = Argus.colors
    val p = c.palette
    val shape = RoundedCornerShape((if (size == ButtonSize.S) ArgusTokens.Radius.BUTTON_SM else ArgusTokens.Radius.BUTTON).dp)
    val src = remember { MutableInteractionSource() }
    val fg = when (kind) {
        ButtonKind.GLASS -> c.ink
        ButtonKind.PRIMARY -> c.onInk
        ButtonKind.DANGER -> kc(KitSpec.dangerText(p))
    }
    val look = when (kind) {
        ButtonKind.GLASS -> Modifier.argusGlass(GlassLevel.RAISED, shape, shadow = false)
        ButtonKind.PRIMARY -> Modifier.clip(shape).background(c.ink)
        ButtonKind.DANGER -> Modifier.clip(shape).background(kc(KitSpec.dangerFill(p)))
            .border(1.dp, c.danger.copy(alpha = KitSpec.DANGER_BORDER_ALPHA), shape)
    }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .kitPress(src)
            .then(look)
            .clickable(src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = size.heightDp.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = size.padDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(Lucide.icon(icon, LucideSpec.Size.BUTTON), null, Modifier.size(LucideSpec.Size.BUTTON.dp), tint = fg)
            Text(text, style = ArgusType.bodyStrong.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * P1-SH-02 `ArgusIconButton` (`.ib`, `.ib.flat`, `.me`): 38 dp visual inside a 48 dp target. Avatar = ink disc with the
 * user's initial (the top bar's "me"). [badge] draws the 8 dp unread dot (bell).
 */
@Composable
fun ArgusIconButton(
    icon: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: IconButtonKind = IconButtonKind.GLASS,
    initial: String? = null,
    badge: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val c = Argus.colors
    val src = remember { MutableInteractionSource() }
    val vis = KitSpec.IconButton.VISUAL.dp
    val look = when (kind) {
        IconButtonKind.GLASS -> Modifier.argusGlass(GlassLevel.RAISED, CircleShape, shadow = false)
        IconButtonKind.FLAT -> Modifier.clip(CircleShape)
        IconButtonKind.AVATAR -> Modifier.clip(CircleShape).background(c.ink)
    }
    val click = if (onLongClick != null) Modifier.kitCombinedClick(src, onClick, onLongClick)
        else Modifier.clickable(src, indication = null, role = Role.Button, onClick = onClick)
    Box(
        modifier
            .size(KitSpec.target(KitSpec.IconButton.VISUAL).dp)
            .then(click)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(vis).kitPress(src).then(look), contentAlignment = Alignment.Center) {
            if (kind == IconButtonKind.AVATAR && !initial.isNullOrBlank()) {
                Text(initial.trim().take(1).uppercase(), style = ArgusType.bodyStrong, color = c.onInk)
            } else {
                Icon(Lucide.icon(icon, KitSpec.IconButton.ICON), null, Modifier.size(KitSpec.IconButton.ICON.dp), tint = c.ink)
            }
        }
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp).size(8.dp).clip(CircleShape).background(c.unread))
    }
}

/** Tap + long-press with no ripple (the kit draws its own pressed state); one call shared by the kit. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.kitCombinedClick(src: MutableInteractionSource, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    this.combinedClickable(interactionSource = src, indication = null, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
