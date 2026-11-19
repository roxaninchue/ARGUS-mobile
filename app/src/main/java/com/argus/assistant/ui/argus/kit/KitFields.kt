package com.blaze.assistant.ui.argus.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Lucide

/**
 * P1-SH-02 `ArgusField` (`.field`): glass fill, 16 sp text (inputs never shrink), 12 dp radius, focus ring 3 dp
 * `glassFill2` + `glassRim2`. [label] is the TalkBack name when there is no visible label; the placeholder is never
 * the only name.
 */
@Composable
fun ArgusField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    leadingIcon: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Done,
    onIme: (() -> Unit)? = null,
) {
    val c = Argus.colors
    val shape = RoundedCornerShape(ArgusTokens.Radius.FIELD.dp)
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val ring = if (focused) Modifier.border(KitSpec.Field.FOCUS_RING.dp, c.glassFill2, shape).padding(KitSpec.Field.FOCUS_RING.dp)
        .border(1.dp, c.glassRim2, shape) else Modifier
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 5,
        textStyle = ArgusType.field.copy(color = c.ink),
        cursorBrush = SolidColor(c.ink),
        interactionSource = src,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onIme?.invoke() }),
        decorationBox = { inner ->
            Box(ring.argusGlass(GlassLevel.SURFACE, shape, shadow = false).heightIn(min = KitSpec.Field.HEIGHT.dp)) {
                Row(Modifier.heightIn(min = KitSpec.Field.HEIGHT.dp).padding(start = 14.dp, end = if (trailing != null) 0.dp else 14.dp,
                    top = if (singleLine) 0.dp else 12.dp, bottom = if (singleLine) 0.dp else 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (leadingIcon != null) Icon(Lucide.icon(leadingIcon, KitSpec.Field.SEARCH_ICON), null, Modifier.size(KitSpec.Field.SEARCH_ICON.dp), tint = c.mute)
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = ArgusType.field, color = c.mute, maxLines = 1)
                        inner()
                    }
                    trailing?.invoke()
                }
            }
        },
    )
}

/**
 * P1-SH-02 `ArgusSearch` (`.search`): an [ArgusField] with the search glyph and a clear (×) button once there is text.
 */
@Composable
fun ArgusSearch(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    onSearch: (() -> Unit)? = null,
) {
    ArgusField(
        value = value, onValueChange = onValueChange, label = placeholder, modifier = modifier, placeholder = placeholder,
        leadingIcon = "search", imeAction = ImeAction.Search, onIme = onSearch,
        trailing = if (value.isEmpty()) null else ({
            ArgusIconButton("x", "Clear search", { onValueChange("") }, kind = IconButtonKind.FLAT)
        }),
    )
}
