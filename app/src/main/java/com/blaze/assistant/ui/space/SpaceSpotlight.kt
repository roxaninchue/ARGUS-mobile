package com.blaze.assistant.ui.space

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.blaze.assistant.composer.SelfMap
import com.blaze.assistant.composer.SpotlightGroups
import com.blaze.assistant.composer.SpotlightRecents
import com.blaze.assistant.composer.SpotlightSearch
import com.blaze.assistant.ui.MotionTokens
import com.blaze.assistant.ui.SearchGlyph
import com.blaze.assistant.ui.composer.ComposerState
import com.blaze.assistant.ui.composer.SpotRow
import com.blaze.assistant.ui.composer.rememberSpotlightActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 0.52.3 Spotlight in the new shell: opened by the round button left of the pill (+ on Home, search in Chat) or the
 * profile's Search pill. A rounded card drops in under the status bar over a scrim: the search field (focused, keyboard
 * up), then instant fuzzy results in groups (Apps, Actions, Settings, Chats & memory, Web), and before typing Recent
 * searches and Suggested asks. Tap the scrim or press Back to close. The search runs off the main thread
 * ([SpotlightSearch] + [SpotlightGroups]); the field is the composer's own query, so "!" commands and "@" views work as
 * before. (Moved from the orb Home's pull-down panel, which is gone.)
 */
@Composable
fun SpaceSpotlightLayer(
    composer: ComposerState,
    hour: Int,
    apps: () -> List<SpotlightGroups.App> = { emptyList() },
    said: () -> List<SpotlightGroups.Said> = { emptyList() },
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val open = composer.spotlightOpen
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(open) {
        if (open) { delay(60); runCatching { focus.requestFocus() }; keyboard?.show() } else keyboard?.hide()
    }
    BackHandler(enabled = open) { composer.closeSpotlight() }
    val appList by produceState(emptyList<SpotlightGroups.App>(), open) {
        if (open) value = withContext(Dispatchers.IO) { runCatching { apps() }.getOrDefault(emptyList()) }
    }
    val saidList by produceState(emptyList<SpotlightGroups.Said>(), open) {
        if (open) value = withContext(Dispatchers.Default) { runCatching { said() }.getOrDefault(emptyList()) }
    }
    var recent by remember { mutableStateOf(readRecent()) }
    val actions = rememberSpotlightActions(composer, onLeaveHome = {}, onAttach = {})
    fun runRow(r: SpotlightSearch.Row) {
        if (composer.query.isNotBlank() && r.act !is SpotlightSearch.Act.Complete) { recent = SpotlightRecents.push(recent, composer.query); saveRecent(recent) }
        val pkg = SpotlightGroups.appPackage(r.act)
        if (pkg != null) { composer.closeSpotlight(); com.blaze.assistant.phone.InstalledApps.launch(ctx, pkg); return }
        when (val a = r.act) {
            is SpotlightSearch.Act.Complete -> composer.query = a.text
            // no inline previews in this panel: a preview opens the real view
            is SpotlightSearch.Act.Preview -> actions.run(SpotlightSearch.Act.OpenView(a.view, a.arg))
            is SpotlightSearch.Act.Browse -> { composer.closeSpotlight(); com.blaze.assistant.ui.SelectionDo.openInBlazeBrowser(ctx, a.query.ifBlank { "https://www.google.com" }) }
            is SpotlightSearch.Act.RunCommand -> actions.run(SpotlightSearch.Act.AskBlaze("Run this in the terminal (${a.distro}): ${a.command}"))
            else -> actions.run(a)
        }
    }
    Box(modifier.fillMaxSize()) {
        // the shared overlay: a 55 % scrim and the panel arrive and leave on one clock
        SpaceScrim(open, onDismiss = { composer.closeSpotlight() }, label = "Close search", modifier = Modifier.testTag(SpaceTags.SCRIM))
        AnimatedVisibility(open, modifier = Modifier.align(Alignment.TopCenter),
            enter = MotionTokens.overlayIn(fromTop = true), exit = MotionTokens.overlayOut(fromTop = true)) {
            BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding().imePadding().padding(horizontal = SpaceInsets.GUTTER_DP.dp, vertical = 10.dp)) {
                SpaceSpotlightPanel(composer, appList, saidList, recent, hour, focus, onRow = { runRow(it) }, maxListHeight = maxHeight * 0.75f)
            }
        }
    }
}

/** The panel itself (stateless but for the search it runs): field + grouped results. Screenshot tests draw it directly. */
@Composable
fun SpaceSpotlightPanel(
    composer: ComposerState,
    apps: List<SpotlightGroups.App>,
    said: List<SpotlightGroups.Said>,
    recent: List<String>,
    hour: Int,
    focus: FocusRequester,
    onRow: (SpotlightSearch.Row) -> Unit,
    modifier: Modifier = Modifier,
    maxListHeight: androidx.compose.ui.unit.Dp = 460.dp,
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
    val look = SpaceLook.Dark   // the panel is always the dark card over its scrim
    // the rows and icon tiles read LocalBlaze, so they take the dark overlay's ink too, whatever the app's theme
    ProvideOverlayInk(dark = look.dark) {
        Column(modifier.fillMaxWidth().overlaySurface(look.dark, SpaceShapes.card, Color(SpacePalette.ELECTRIC).copy(alpha = 0.35f))
            .testTag(SpaceTags.SPOTLIGHT)
            // taps on the panel stay in it (the scrim around it closes Spotlight)
            .clickable(remember { MutableInteractionSource() }, indication = null) { }
            .semantics { contentDescription = "Spotlight" }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchGlyph(look.accent, 22.dp)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (composer.query.isEmpty()) Text("Search apps, actions, settings, chats\u2026", maxLines = 1, overflow = TextOverflow.Ellipsis, style = SpaceType.bodyText(look.sub))
                    BasicTextField(composer.query, { composer.query = it },
                        singleLine = true, textStyle = SpaceType.bodyText(look.ink),
                        cursorBrush = SolidColor(Color(SpacePalette.ELECTRIC)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { top?.let(onRow) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(SpaceTags.SPOTLIGHT_FIELD).semantics { contentDescription = "Spotlight search" })
                }
                if (composer.query.isNotEmpty()) Box(Modifier.size(40.dp).clip(CircleShape).clickable { composer.query = "" }
                    .semantics { contentDescription = "Clear the search"; role = Role.Button }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = look.sub, modifier = Modifier.size(18.dp))
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp).background(look.hair))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxListHeight), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                for (s in sections) {
                    item(key = "h:" + s.group.name) {
                        Text(s.group.title.uppercase(), modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp).semantics { heading() },
                            style = SpaceType.body(SpaceTypeScale.CAPTION.sp, look.sub, FontWeight.Medium).copy(letterSpacing = 0.12.em))
                    }
                    items(s.rows, key = { s.group.name + "|" + it.id }) { r -> SpotRow(r, top = r == top, ink = look.ink, sub = look.sub) { onRow(r) } }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun readRecent(): List<String> = try { SpotlightRecents.decode(com.blaze.assistant.agent.Settings.str(SpotlightRecents.PREF, "")) } catch (_: Throwable) { emptyList() }

private fun saveRecent(list: List<String>) { try { com.blaze.assistant.agent.Settings.setStr(SpotlightRecents.PREF, SpotlightRecents.encode(list)) } catch (_: Throwable) { } }
