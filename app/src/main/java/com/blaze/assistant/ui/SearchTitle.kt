package com.blaze.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.i18n.tr
import com.blaze.assistant.ui.space.spaceLook

/**
 * 16 Nov 2026 (Finn): search lives in the top bar as a real magnifier. Tap it and the bar slides open to the left over
 * the title; × (or Back with an empty box) closes it and clears the words. Used by the You page (search settings) and
 * Apps & connectors (search apps). The page reads [SettingsSearch.query].
 */
object SettingsSearch {
    const val ICON_TAG = "search-open"
    const val FIELD_TAG = "search-field"
    val query = mutableStateOf("")
    val open = mutableStateOf(false)
    fun close() { query.value = ""; open.value = false }
}

/** [BackTitle] with the expanding search in its right slot; [right] stays at the far end (the theme switch). */
@Composable
fun SearchTitle(
    title: String,
    onBack: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    right: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalBlaze.current.mono
    var open by SettingsSearch.open
    var query by SettingsSearch.query
    val focus = remember { FocusRequester() }
    val fm = LocalFocusManager.current
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = BackTitleEdge, end = BackTitleEdge),
        verticalAlignment = Alignment.CenterVertically) {
        BackButton({ if (open) SettingsSearch.close() else onBack() })
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (!open) Text(tr(title), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart),
                style = com.blaze.assistant.ui.space.SpaceType.headline(com.blaze.assistant.ui.space.SpaceTypeScale.SECTION.sp, spaceLook().ink))
            AnimatedVisibility(open, enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(CircleShape).border(1.dp, c.hair, CircleShape)
                    .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = c.sub, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(tr(placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.weak))
                        BasicTextField(query, { query = it }, singleLine = true, cursorBrush = SolidColor(LocalBlaze.current.accent),
                            textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { fm.clearFocus() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(SettingsSearch.FIELD_TAG))
                    }
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = tr("Close search")) { SettingsSearch.close() }
                        .semantics { contentDescription = "Close search" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, contentDescription = null, tint = c.sub, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (!open) Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = tr("Search")) { open = true }
            .semantics { contentDescription = "Search" }.testTag(SettingsSearch.ICON_TAG), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = LocalBlaze.current.ink, modifier = Modifier.size(21.dp))
        }
        right?.invoke(this)
    }
    LaunchedEffect(open) { if (open) runCatching { focus.requestFocus() } }
}
