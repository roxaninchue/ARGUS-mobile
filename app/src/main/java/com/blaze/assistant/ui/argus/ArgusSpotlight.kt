package com.blaze.assistant.ui.argus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blaze.assistant.composer.SelfMap
import com.blaze.assistant.composer.SpotlightGroups
import com.blaze.assistant.composer.SpotlightSearch
import com.blaze.assistant.ui.Type
import com.blaze.assistant.ui.composer.ComposerState
import com.blaze.assistant.ui.composer.SpotRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 0.52.2 the pull-down Spotlight of the ARGUS Home: a dark glass panel that comes down from the notch. The search field
 * (focused, keyboard up) on top, then instant fuzzy results in groups (Apps, Actions, Settings, Chats & memory, Web);
 * before typing, Recent searches and Suggested asks. Pull the handle up, tap the scrim or press Back to dismiss.
 * Search runs off the main thread ([SpotlightSearch] + [SpotlightGroups]); the field is the composer's own query, so
 * "!" commands and "@" views work as they always did.
 */
@Composable
fun ArgusSpotlightPanel(
    composer: ComposerState,
    apps: List<SpotlightGroups.App>,
    said: List<SpotlightGroups.Said>,
    recent: List<String>,
    hour: Int,
    focus: FocusRequester,
    drag: DraggableState,
    onDragStopped: (Float) -> Unit,
    onRow: (SpotlightSearch.Row) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    maxListHeight: Dp = 460.dp,
) {
    val songs by com.blaze.assistant.mediaapps.MediaLib.songs.collectAsState()
    val videos by com.blaze.assistant.mediaapps.MediaLib.videos.collectAsState()
    val map = remember { SelfMap.default }
    val sections by produceState(
        SpotlightGroups.sections("", SpotlightSearch.Result(SpotlightSearch.Mode.CURATED, SpotlightSearch.curated(map)), apps, said, recent, hour),
        composer.query, songs, videos, apps, said, recent, hour) {
        if (composer.query.isNotEmpty()) delay(50)   // a short debounce while typing
        val q = composer.query
        value = withContext(Dispatchers.Default) {
            val base = SpotlightSearch.search(q, map, songs.map { SpotlightSearch.Media(it.key, it.title, it.artist) },
                videos.map { SpotlightSearch.Media(it.key, it.title, it.artist) }, emptyList(), composer.distro)
            SpotlightGroups.sections(q, base, apps, said, recent, hour)
        }
    }
    val top = SpotlightGroups.top(sections)
    val shape = RoundedCornerShape(28.dp)
    val ink = Color.White; val sub = ArgusHomeTokens.Steel
    Column(modifier.fillMaxWidth()
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xF2101830), Color(0xF5060A14))))
        .border(1.dp, Brush.verticalGradient(listOf(ArgusHomeTokens.Electric.copy(alpha = 0.55f), Color.White.copy(alpha = 0.08f))), shape)
        // taps on the panel stay in it (the scrim around it closes Spotlight)
        .clickable(remember { MutableInteractionSource() }, indication = null) { }
        .semantics { contentDescription = "Spotlight" }) {
        // the field: drag it (or the handle below) up to dismiss
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).draggable(drag, Orientation.Vertical, onDragStopped = { v -> onDragStopped(v) })
            .padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = ArgusHomeTokens.ElectricText, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (composer.query.isEmpty()) Text("Search apps, actions, settings, chats\u2026", maxLines = 1,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 18.sp, color = sub))
                BasicTextField(composer.query, { composer.query = it },
                    singleLine = true, textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 18.sp, color = ink),
                    cursorBrush = SolidColor(ArgusHomeTokens.Electric),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { top?.let(onRow) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(ArgusHomeTags.SPOTLIGHT_FIELD).semantics { contentDescription = "Spotlight search" })
            }
            if (composer.query.isNotEmpty()) Box(Modifier.size(40.dp).clip(CircleShape).clickable { composer.query = "" }
                .semantics { contentDescription = "Clear the search"; role = Role.Button }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = sub, modifier = Modifier.size(18.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Color.White.copy(alpha = 0.10f)))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxListHeight), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
            for (s in sections) {
                item(key = "h:" + s.group.name) {
                    Text(s.group.title.uppercase(), modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp).semantics { heading() },
                        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.12.em, color = sub))
                }
                items(s.rows, key = { s.group.name + "|" + it.id }) { r -> SpotRow(r, top = r == top) { onRow(r) } }
            }
        }
        // the handle: pull up (or tap) to put Spotlight away
        Box(Modifier.fillMaxWidth().height(28.dp).testTag(ArgusHomeTags.SPOTLIGHT_HANDLE).draggable(drag, Orientation.Vertical, onDragStopped = { v -> onDragStopped(v) })
            .clickable(onClickLabel = "Close Spotlight") { onClose() }
            .semantics { contentDescription = "Close Spotlight"; role = Role.Button }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
        }
    }
}
