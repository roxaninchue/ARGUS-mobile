package com.blaze.assistant.ui.argus.apps

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.i18n.tr
import com.blaze.assistant.siteapps.SiteInstaller
import com.blaze.assistant.ui.BlazeView
import com.blaze.assistant.ui.Views
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusGround
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Lucide
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ArgusIconButton
import com.blaze.assistant.ui.argus.kit.ArgusSearch
import com.blaze.assistant.ui.argus.kit.ArgusSheet
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.IconButtonKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Test tags for the page's controls. */
object AppsTags {
    const val CLOSE = "apps-close"
    const val PROFILE = "apps-profile"
    const val SEARCH = "apps-search"
    fun tile(t: AppsPageModel.Tile) = "apps-tile-${t.kind.name.lowercase()}-${t.id}"
}

/**
 * The Apps page: X (top left, closes; Back does the same) · title · the user's avatar (top right, opens the profile),
 * then a 4-wide grid of glass tiles for ARGUS's apps and, under "Your site apps", the sites installed from the browser.
 * A floating Search pill at the bottom filters the tiles. Long-press a site app: add it to the Home screen or remove it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsPage(
    initial: String,
    onClose: () -> Unit,
    onProfile: () -> Unit,
    onOpenView: (String) -> Unit,
) {
    val c = Argus.colors
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var sites by remember { mutableStateOf<List<Pair<AppsPageModel.Tile, Bitmap?>>>(emptyList()) }
    var siteMenu by remember { mutableStateOf<AppsPageModel.Tile?>(null) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(tick) {
        sites = withContext(Dispatchers.IO) {
            val list = try { SiteInstaller.installed(ctx) } catch (_: Throwable) { emptyList() }
            AppsPageModel.siteTiles(list.map { Triple(it.id, it.name, it.startUrl) }).map { it to SiteInstaller.icon(ctx, it.id) }
        }
    }
    val views = AppsPageModel.filter(AppsPageModel.viewTiles(), query)
    val shownSites = AppsPageModel.filter(sites.map { it.first }, query).toSet()
    val siteIcons = sites.toMap()
    fun open(t: AppsPageModel.Tile) {
        when (t.kind) {
            AppsPageModel.Kind.VIEW -> onOpenView(t.id)
            AppsPageModel.Kind.SITE -> try { ctx.startActivity(SiteInstaller.intent(ctx, t.id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) { }
        }
    }

    BackHandler { if (searching) { searching = false; query = "" } else onClose() }
    ArgusGround(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // top bar: glass X · title · me
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ArgusIconButton("x", tr("Close Apps"), onClose, Modifier.testTag(AppsTags.CLOSE), kind = IconButtonKind.GLASS)
                Text(tr("Apps"), style = ArgusType.topTitle, color = c.ink, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).semantics { heading() })
                ArgusIconButton("user", tr("Profile"), onProfile, Modifier.testTag(AppsTags.PROFILE), kind = IconButtonKind.AVATAR, initial = initial)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                Spacer(Modifier.height(8.dp))
                for (row in AppsPageModel.rows(views)) TileRow(row, onTap = ::open, onLong = null) { t -> ViewGlyph(t.icon) }
                val siteList = sites.map { it.first }.filter { it in shownSites }
                if (siteList.isNotEmpty()) {
                    Text(tr("Your site apps").uppercase(), style = ArgusType.section, color = c.meta,
                        modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 4.dp).semantics { heading() })
                    for (row in AppsPageModel.rows(siteList)) TileRow(row, onTap = ::open, onLong = { siteMenu = it }) { t ->
                        val bmp = siteIcons[t]
                        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)))
                        else ViewGlyph("globe")
                    }
                }
                if (views.isEmpty() && siteList.isEmpty()) Text(tr("No apps match \u201c$query\u201d."), style = ArgusType.sub, color = c.mute,
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp), textAlign = TextAlign.Center)
                if (note.isNotBlank()) Text(note, style = ArgusType.sub, color = c.mute, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), textAlign = TextAlign.Center)
                // room for the floating search pill so it never covers the last row
                Spacer(Modifier.height(96.dp))
            }
        }
        // the floating Search pill (a field once tapped), above the nav bar and the keyboard
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (searching) {
                val focus = remember { FocusRequester() }
                ArgusSearch(query, { query = it }, Modifier.fillMaxWidth().focusRequester(focus).testTag(AppsTags.SEARCH), placeholder = tr("Search apps"))
                LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Throwable) { } }
            } else Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .argusGlass(GlassLevel.RAISED, RoundedCornerShape(ArgusTokens.Radius.PILL.dp), shadow = true)
                .combinedClickable(onClickLabel = tr("Search apps"), onClick = { searching = true })
                .semantics { role = Role.Button }.testTag(AppsTags.SEARCH).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.icon("search", 18), null, Modifier.size(18.dp), tint = c.ink)
                Spacer(Modifier.width(10.dp))
                Text(tr("Search"), style = ArgusType.body, color = c.mute)
            }
        }
    }
    siteMenu?.let { t ->
        ArgusSheet(onDismiss = { siteMenu = null }, title = t.label) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ArgusButton(tr("Open"), { siteMenu = null; open(t) }, Modifier.fillMaxWidth(), kind = ButtonKind.PRIMARY, icon = "play")
                ArgusButton(tr("Add to Home screen"), {
                    siteMenu = null
                    note = SiteInstaller.pinToHome(ctx, t.id)
                }, Modifier.fillMaxWidth(), icon = "smartphone")
                ArgusButton(tr("Remove from Apps"), {
                    siteMenu = null
                    try { SiteInstaller.uninstall(ctx, t.id) } catch (_: Throwable) { }
                    tick++
                }, Modifier.fillMaxWidth(), kind = ButtonKind.DANGER, icon = "trash-2")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileRow(row: List<AppsPageModel.Tile>, onTap: (AppsPageModel.Tile) -> Unit, onLong: ((AppsPageModel.Tile) -> Unit)?,
                    glyph: @Composable (AppsPageModel.Tile) -> Unit) {
    val c = Argus.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in row) {
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .combinedClickable(role = Role.Button, onClick = { onTap(t) }, onLongClick = onLong?.let { { it(t) } })
                    .semantics(mergeDescendants = true) { contentDescription = t.label }
                    .testTag(AppsTags.tile(t)).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                glyph(t)
                Spacer(Modifier.size(6.dp))
                Text(t.label, style = ArgusType.meta, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp))
            }
        }
        // keep the last row's tiles in their columns
        repeat(AppsPageModel.COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun ViewGlyph(icon: String) {
    val c = Argus.colors
    Box(Modifier.size(56.dp).argusGlass(GlassLevel.RAISED, RoundedCornerShape(18.dp), shadow = false), contentAlignment = Alignment.Center) {
        Icon(Lucide.icon(icon, 26), null, Modifier.size(26.dp), tint = c.ink)
    }
}

