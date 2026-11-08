package com.blaze.assistant.browser


import com.blaze.assistant.BrandName
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.siteapps.SiteApp
import com.blaze.assistant.siteapps.SiteInstaller
import com.blaze.assistant.ui.BlazeSwitchTrack
import com.blaze.assistant.ui.Type
import com.blaze.assistant.ui.space.SpaceBackdrop
import com.blaze.assistant.ui.space.SpaceInsets
import com.blaze.assistant.ui.space.SpaceLook
import com.blaze.assistant.ui.space.SpaceSegmented
import com.blaze.assistant.ui.space.SpaceShapes
import com.blaze.assistant.ui.space.SpaceType
import com.blaze.assistant.ui.space.SpaceTypeScale
import com.blaze.assistant.ui.space.spaceLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * v0.45.25 "Sky", unification 8/9: the browser wears the shell. The names stay so the screen reads as before, but every
 * colour comes from the app's [SpaceLook] (light or dark with the app, the accent picker's accent), read in composition
 * like DialogColors: [frost] is the shell's translucent card (over the space view), [frostStrong] the solid card (sheets
 * and the bar over a web page), [rim] the hairline. No live blur: the page behind is a WebView.
 */
object Sky {
    private val look: SpaceLook @Composable get() = spaceLook()
    /** The blank page's ground (under the space view while it decodes). */
    val navy: Color @Composable get() = look.ground
    /** The floating bar and the chips over a web page: the solid card, so the address reads over any page. */
    val frost: Color @Composable get() = look.cardSolid
    /** Cards over the space view (the start page). */
    val frostSoft: Color @Composable get() = look.card
    /** Sheets (tabs, launcher, site sheet). */
    val frostStrong: Color @Composable get() = look.cardSolid
    val rim: Color @Composable get() = look.hair
    val ink: Color @Composable get() = look.ink
    val sub: Color @Composable get() = look.sub
    val weak: Color @Composable get() = look.sub.copy(alpha = 0.72f)
    /** Accent marks (the Ask sparkle). */
    val blue: Color @Composable get() = look.accent
    /** The Ask button: the one primary button style. */
    val primary: Color @Composable get() = look.primaryFill
    val onPrimary: Color @Composable get() = look.primaryInk
    /** Inner tiles (tools, stats) and the selected tab row. */
    val tile: Color @Composable get() = look.tile
    /** Secure / on: a calm green, deeper on light grounds. */
    val green: Color @Composable get() = if (look.dark) Color(0xFF34C759) else Color(0xFF1A7F37)
    /** Not secure, connecting. */
    val warn: Color @Composable get() = if (look.dark) Color(0xFFFFB86B) else Color(0xFFB54708)
    /** Failed. */
    val bad: Color @Composable get() = if (look.dark) Color(0xFFFF8A80) else Color(0xFFB42318)
    val pill = SpaceShapes.pill
    val card = SpaceShapes.card
    val sheet = SpaceShapes.card
    val tile20 = SpaceShapes.tile
    /** The one control height (round buttons, chips) and the pills that hold one (the control plus the pill's frame). */
    val control: Dp = SpaceInsets.CONTROL_DP.dp
    val bar: Dp = (SpaceInsets.CONTROL_DP + 2 * SpaceInsets.PILL_FRAME_DP).dp
    val gutter: Dp = SpaceInsets.GUTTER_DP.dp
}

/** Taps on a sheet stay on the sheet (the dimmed backdrop behind it closes it). */
internal fun Modifier.eatTaps(): Modifier = this.pointerInput(Unit) { detectTapGestures { } }

/** Unification: the fill and rim are explicit (Sky's colours are read in composition, a Modifier can't read them). */
fun Modifier.frost(shape: Shape, fill: Color, rim: Color): Modifier = this.clip(shape).background(fill).border(1.dp, rim, shape)

@Composable
internal fun SkyText(s: String, size: Float, color: Color = Sky.ink, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Normal,
                     maxLines: Int = 1, spacing: Float = 0f, center: Boolean = false, family: FontFamily = Type.Sans) =
    androidx.compose.material3.Text(s, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = family, fontWeight = weight, fontSize = size.sp, lineHeight = (size * 1.3f).sp, color = color,
            letterSpacing = spacing.sp, textAlign = if (center) TextAlign.Center else TextAlign.Start))

/** Sizes for [SkyText]: the shell's type scale (SpaceTypeScale). */
private const val T_TITLE = SpaceTypeScale.TITLE
private const val T_SECTION = SpaceTypeScale.SECTION
private const val T_ROW = SpaceTypeScale.ROW_TITLE
private const val T_BODY = SpaceTypeScale.BODY
private const val T_META = SpaceTypeScale.META
private const val T_CAPTION = SpaceTypeScale.CAPTION

/** A round letter tile in a colour picked from the site's name (no network, no bitmap). */
@Composable
internal fun SiteDot(label: String, icon: ImageBitmap? = null, size: Dp = 28.dp, shape: Shape = RoundedCornerShape(8.dp)) {
    if (icon != null) { Image(icon, null, Modifier.size(size).clip(shape)); return }
    val palette = listOf(0xFF1F6FEB, 0xFFD1242F, 0xFF8250DF, 0xFF1A7F37, 0xFFBF8700, 0xFF0969DA, 0xFFE16F24, 0xFF24292F)
    val c = Color(palette[(label.lowercase().hashCode() and 0x7fffffff) % palette.size])
    Box(Modifier.size(size).clip(shape).background(c), contentAlignment = Alignment.Center) {
        SkyText(label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•", (size.value * 0.45f).coerceAtLeast(9f), Color.White, weight = FontWeight.SemiBold)
    }
}

internal fun skyHost(u: String): String = u.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/').substringBefore('?')

/** The clock line on the new-tab page; it changes once a minute (no animation). */
@Composable
private fun rememberClock(): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000 - System.currentTimeMillis() % 60_000 + 50); now = System.currentTimeMillis() } }
    return remember(now / 60_000) {
        val d = java.util.Date(now)
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(d) + " \u00b7 " + java.text.SimpleDateFormat("EEEE, MMMM d", java.util.Locale.getDefault()).format(d)
    }
}

/** One place on the new-tab page's row: an installed site app or a site to open. */
private data class Pin(val label: String, val url: String, val app: SiteApp? = null)

/**
 * The Sky new-tab page (unification 8/9: on the shell's space view): wordmark, time and date, the search pill (with voice), pinned sites and installed site
 * apps, "pick up where you left off" (recent tabs and Blaze's task) and two chips (trackers, live tabs).
 */
@Composable
internal fun SkyStartPage(tick: Int, bottomSpace: Dp, open: (String) -> Unit, onSearch: () -> Unit, onVoice: () -> Unit) {
    val ctx = LocalContext.current
    val clock = rememberClock()
    var apps by remember { mutableStateOf<List<SiteApp>>(emptyList()) }
    LaunchedEffect(tick) { apps = withContext(Dispatchers.IO) { try { SiteInstaller.installed(ctx) } catch (_: Throwable) { emptyList() } } }
    val marks = remember(tick) { BrowserData.bookmarks(ctx) }
    val recent = remember(tick) { BrowserData.history(ctx).take(4) }
    val pins = remember(apps, marks) {
        val l = ArrayList<Pin>()
        apps.forEach { l += Pin(it.name, it.startUrl, it) }
        marks.forEach { m -> if (l.size < 6 && l.none { skyHost(it.url) == skyHost(m.url) }) l += Pin(m.title.ifBlank { skyHost(m.url) }, m.url) }
        listOf("Google" to "https://www.google.com/", "YouTube" to "https://m.youtube.com/", "Wikipedia" to "https://www.wikipedia.org/", "Gmail" to "https://mail.google.com/")
            .forEach { (n, u) -> if (l.size < 6 && l.none { skyHost(it.url) == skyHost(u) }) l += Pin(n, u) }
        l.take(6)
    }
    val cur = BrowserEngine.tab()
    val agent = BrowserEngine.agentTab()
    val others = BrowserEngine.tabs.filter { it !== cur && it.isPrivate == (cur?.isPrivate == true) && it.url.startsWith("http") }.sortedByDescending { it.lastSeen }

    SpaceBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = Sky.gutter),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(72.dp))
            // the wordmark keeps its own size and tracking: it is the logo, not a heading
            SkyText("${BrandName.UPPER}", 44f, weight = FontWeight.Normal, spacing = 18f)
            Spacer(Modifier.height(8.dp))
            SkyText(clock, T_META, Sky.sub)
            Spacer(Modifier.height(48.dp))
            Row(Modifier.fillMaxWidth().height(Sky.bar).frost(Sky.pill, Sky.frostSoft, Sky.rim).clickable(onClick = onSearch)
                .padding(start = 18.dp, end = SpaceInsets.PILL_FRAME_DP.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Sky.sub, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                SkyText(if (cur?.isPrivate == true) "Private: search or enter an address" else "Search or enter an address", T_BODY, Sky.sub, Modifier.weight(1f))
                Box(Modifier.size(Sky.control).clip(CircleShape).clickable(onClick = onVoice), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Mic, "Search by voice", tint = Sky.sub, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(16.dp))
            if (pins.isNotEmpty()) Row(Modifier.fillMaxWidth().frost(Sky.card, Sky.frostSoft, Sky.rim).padding(vertical = 14.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                pins.forEach { p ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable {
                        val a = p.app
                        if (a != null) try { ctx.startActivity(SiteInstaller.intent(ctx, a.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { open(a.startUrl) }
                        else open(p.url)
                    }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val icon = remember(p.app?.id) { p.app?.let { a -> try { SiteInstaller.icon(ctx, a.id)?.asImageBitmap() } catch (_: Throwable) { null } } }
                        SiteDot(p.label, icon, 40.dp, RoundedCornerShape(12.dp))
                        Spacer(Modifier.height(6.dp))
                        SkyText(p.label, T_CAPTION, Sky.sub)
                    }
                }
            }
            val pick = ArrayList<Triple<String, String, () -> Unit>>()
            others.take(2).forEach { t -> pick += Triple(t.title.ifBlank { skyHost(t.url) }, if (t === agent) "${BrandName.NAME}" else "Tab") { BrowserEngine.switchTo(t) } }
            if (agent != null && others.none { it === agent } && agent !== cur) pick += Triple(agent.title, "${BrandName.NAME}") { BrowserEngine.switchTo(agent) }
            if (pick.isEmpty()) recent.take(2).forEach { e -> pick += Triple(e.title.ifBlank { skyHost(e.url) }, "Recent") { open(e.url) } }
            if (pick.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().frost(Sky.card, Sky.frostSoft, Sky.rim).padding(vertical = 12.dp)) {
                    SkyLabel("Pick up where you left off")
                    pick.forEach { (title, tag, go) ->
                        Row(Modifier.fillMaxWidth().clickable(onClick = go).padding(horizontal = SpaceInsets.CARD_PAD_DP.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            SiteDot(title, null, 28.dp)
                            Spacer(Modifier.width(12.dp))
                            SkyText(title, T_BODY, modifier = Modifier.weight(1f))
                            SkyText(tag, T_CAPTION, Sky.weak)
                        }
                    }
                }
            }
            if (marks.size > 6) {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().frost(Sky.card, Sky.frostSoft, Sky.rim).padding(vertical = 12.dp)) {
                    SkyLabel("Bookmarks")
                    marks.drop(0).take(12).forEach { e ->
                        Row(Modifier.fillMaxWidth().clickable { open(e.url) }.padding(horizontal = SpaceInsets.CARD_PAD_DP.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            SiteDot(e.title.ifBlank { skyHost(e.url) }, null, 28.dp); Spacer(Modifier.width(12.dp))
                            SkyText(e.title.ifBlank { e.url }, T_BODY, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(Icons.Rounded.Shield, if (TrackerBlock.enabled) "Trackers blocked" else "Tracker blocking off", if (TrackerBlock.enabled) "${TrackerBlock.total}" else null)
                val live = BrowserEngine.liveCount()
                Chip(Icons.Rounded.Bolt, "RAM lean", "\u00b7 $live live tab${if (live == 1) "" else "s"}")
            }
            Spacer(Modifier.height(bottomSpace + 12.dp))
        }
    }
}

/** A small heading in a card ("Pick up where you left off"): the shell's section label, sentence case, aligned with the rows. */
@Composable
private fun SkyLabel(s: String) = SkyText(s, T_CAPTION, Sky.sub, Modifier.padding(start = SpaceInsets.CARD_PAD_DP.dp, bottom = 4.dp), weight = FontWeight.Medium)

@Composable
private fun Chip(icon: ImageVector, text: String, strong: String?) {
    Row(Modifier.height(36.dp).frost(Sky.pill, Sky.frostSoft, Sky.rim).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Sky.sub, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
        SkyText(text, T_CAPTION, Sky.ink)
        if (strong != null) { Spacer(Modifier.width(5.dp)); SkyText(strong, T_CAPTION, weight = FontWeight.SemiBold) }
    }
}

/** A round chip button (back, forward, reload, home): the shell's one control size. */
@Composable
internal fun SkyChipButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(Sky.control).clip(CircleShape).clickable(enabled = enabled, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = if (enabled) Sky.ink else Sky.weak, modifier = Modifier.size(com.blaze.assistant.ui.BlazeIconStyle.rowIcon))
    }
}

// ---------------------------------------------------------------------------------------------- tabs sheet
enum class TabSeg { OPEN, PRIVATE, BLAZE }

private fun age(ms: Long, now: Long): String {
    val m = ((now - ms).coerceAtLeast(0) / 60_000)
    return when { m < 1 -> "now"; m < 60 -> "${m} m"; m < 60 * 24 -> "${m / 60} h"; m < 60 * 48 -> "Yday"; else -> "${m / (60 * 24)} d" }
}

/**
 * Sky tabs: a frosted sheet with Open / Private / Blaze, tabs split into AWAKE (a live page in memory) and SLEEPING (a
 * record only), favicon rows with close, and New tab / Private / Search at the bottom. The picture grid stays one tap away.
 */
@Composable
internal fun SkyTabsSheet(startSeg: TabSeg, onDone: () -> Unit, onGrid: () -> Unit, onSearch: () -> Unit) {
    val ctx = LocalContext.current
    var seg by remember { mutableStateOf(startSeg) }
    val cur = BrowserEngine.tab()
    val agent = BrowserEngine.agentTab()
    val list = BrowserEngine.tabs.filter { when (seg) { TabSeg.OPEN -> !it.isPrivate; TabSeg.PRIVATE -> it.isPrivate; TabSeg.BLAZE -> it.agentUsed } }
    val awake = list.filter { it.live != null || it === cur }
    val asleep = list.filter { it.live == null && it !== cur }
    val now = System.currentTimeMillis()
    var menu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onDone)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Sky.gutter).padding(top = 8.dp)
            .windowInsetsPadding(skyBottomInsets())) {
            Column(Modifier.weight(1f).fillMaxWidth().frost(Sky.sheet, Sky.frostStrong, Sky.rim).eatTaps()) {
                Row(Modifier.fillMaxWidth().padding(start = SpaceInsets.CARD_PAD_DP.dp, end = 6.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SkyText("Tabs", T_TITLE, family = SpaceType.Serif, modifier = Modifier.weight(1f))
                    Box {
                        Box(Modifier.size(Sky.control).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.MoreVert, "Tab options", tint = Sky.ink, modifier = Modifier.size(20.dp)) }
                        com.blaze.assistant.ui.BlazeMenu(menu, onDismiss = { menu = false }, items = listOf(
                            "Picture grid" to { onGrid() },
                            (if (seg == TabSeg.PRIVATE) "Close all private tabs" else "Close all tabs") to {
                                BrowserEngine.closeAll(seg == TabSeg.PRIVATE)
                                if (BrowserEngine.normalTabs().isEmpty()) BrowserEngine.newTab(ctx)
                                if (BrowserEngine.tab()?.isPrivate == true && seg == TabSeg.PRIVATE) BrowserEngine.current = BrowserEngine.tabs.indexOfLast { !it.isPrivate }
                            }))
                    }
                }
                // unification: the shell's one segmented pill, on its own line so it fits a 360 dp phone
                SpaceSegmented(listOf(TabSeg.OPEN.name to "Open ${BrowserEngine.normalTabs().size}", TabSeg.PRIVATE.name to "Private ${BrowserEngine.privateTabs().size}",
                    TabSeg.BLAZE.name to "${BrandName.NAME}"), seg.name, Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 6.dp)) { id -> seg = TabSeg.valueOf(id) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (list.isEmpty()) item {
                        SkyText(when (seg) { TabSeg.PRIVATE -> "No private tabs. Private tabs keep no history; their cookies go when the last one closes."
                            TabSeg.BLAZE -> "Tabs ${BrandName.NAME} browses for you show up here."; else -> "No open tabs" }, T_META, Sky.sub, Modifier.padding(SpaceInsets.CARD_PAD_DP.dp), maxLines = 4)
                    }
                    if (awake.isNotEmpty()) { item { Section("Awake") }; items(awake, key = { it.id }) { t -> TabRow(t, t === cur, t === agent, now, onDone) } }
                    if (asleep.isNotEmpty()) { item { Section("Sleeping \u00b7 0 MB each") }; items(asleep, key = { it.id }) { t -> TabRow(t, false, t === agent, now, onDone) } }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().eatTaps(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).height(Sky.bar).frost(Sky.pill, Sky.frostStrong, Sky.rim).clickable { BrowserEngine.newTab(ctx, isPrivate = seg == TabSeg.PRIVATE); onDone() },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null, tint = Sky.ink, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                    SkyText(if (seg == TabSeg.PRIVATE) "New private tab" else "New tab", T_ROW, weight = FontWeight.Medium)
                }
                Spacer(Modifier.width(10.dp))
                RoundFrost(Icons.Rounded.VisibilityOff, "New private tab") { BrowserEngine.newTab(ctx, isPrivate = true); onDone() }
                Spacer(Modifier.width(10.dp))
                RoundFrost(Icons.Rounded.Search, "Search") { onSearch() }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable private fun Section(s: String) = SkyText(s, T_CAPTION, Sky.sub, Modifier.padding(start = SpaceInsets.CARD_PAD_DP.dp, top = 10.dp, bottom = 4.dp), weight = FontWeight.Medium)

@Composable
private fun RoundFrost(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(Sky.bar).frost(CircleShape, Sky.frostStrong, Sky.rim).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Sky.ink, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun TabRow(t: BrowserEngine.Tab, selected: Boolean, isAgent: Boolean, now: Long, onDone: () -> Unit) {
    val blank = t.url.isBlank() || t.url == "about:blank"
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(Sky.tile20)
        .background(if (selected) Sky.tile else Color.Transparent)
        .clickable { BrowserEngine.switchTo(t); onDone() }.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        SiteDot(t.title.ifBlank { skyHost(t.url) }, t.icon, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SkyText(t.title.ifBlank { if (t.isPrivate) "Private tab" else "New tab" }, T_BODY, weight = FontWeight.Medium)
            SkyText(if (t.isPrivate) "Private \u00b7 not saved" else if (blank) "New tab page" else if (SavedPages.isSavedUrl(t.url)) "Offline copy" else skyHost(t.url), T_CAPTION, Sky.sub)
        }
        SkyText(when { selected -> "Live"; isAgent -> "${BrandName.NAME}"; t.live != null -> "Awake"; else -> age(t.lastSeen, now) }, T_CAPTION, Sky.sub, Modifier.padding(start = 8.dp))
        Box(Modifier.size(Sky.control).clip(CircleShape).clickable {
            val i = BrowserEngine.tabs.indexOf(t); if (i >= 0) BrowserEngine.close(i)
            if (BrowserEngine.normalTabs().isEmpty() && BrowserEngine.tabs.none { it.isPrivate }) BrowserEngine.newTab(BrowserEngine.appContext())
        }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Close tab", tint = Sky.sub, modifier = Modifier.size(16.dp)) }
    }
}

/** Bottom insets the floating Sky controls keep clear of: the navigation bar and the home-gesture zone. */
@Composable
internal fun skyBottomInsets(): WindowInsets = WindowInsets.navigationBars.union(WindowInsets.mandatorySystemGestures).only(WindowInsetsSides.Bottom)

// ---------------------------------------------------------------------------------------------- launcher
/** Everything the launcher can do; the browser screen carries them out. */
enum class Tool(val label: String, val icon: ImageVector) {
    ASK("Ask page", Icons.Rounded.AutoAwesome), POINTER("Pointer", Icons.Rounded.AdsClick), TRANSLATE("Translate", Icons.Rounded.Translate),
    FIND("Find", Icons.Rounded.FindInPage), BOOKMARKS("Bookmarks", Icons.Rounded.BookmarkBorder), HISTORY("History", Icons.Rounded.History),
    DOWNLOADS("Downloads", Icons.Rounded.Download), SAVED("Saved", Icons.Rounded.OfflinePin), PASSWORDS("Passwords", Icons.Rounded.Key),
    INSTALL("Install app", Icons.Rounded.AddToHomeScreen), MY_APPS("My apps", Icons.Rounded.Apps), DESKTOP("Desktop", Icons.Rounded.DesktopWindows),
    SHARE("Share", Icons.Rounded.Share), TASKS("Tasks", Icons.Rounded.SmartToy),
}

/**
 * The Sky launcher (tap the address in the pill): a search field with matching open tabs, history and "Ask Blaze", a grid of
 * tools, and the privacy switches (VPN, location mask, tracker blocking, private browsing).
 */
@Composable
internal fun SkyLauncher(onDismiss: () -> Unit, onGo: (String) -> Unit, onAsk: (String) -> Unit, onTool: (Tool) -> Unit, onVpnMore: () -> Unit, onPrivate: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val tab = BrowserEngine.tab()
    var q by remember { mutableStateOf(tab?.url?.takeIf { it.startsWith("http") }.orEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }
    val http = tab?.url.orEmpty().startsWith("http")
    val typing = q.isNotBlank() && q != tab?.url
    val matchTabs = remember(q) { if (!typing) emptyList() else BrowserEngine.tabs.filter { it !== tab && (it.title.contains(q, true) || it.url.contains(q, true)) }.take(3) }
    val matchHist = remember(q) { if (!typing) emptyList() else (BrowserData.bookmarks(ctx) + BrowserData.history(ctx)).filter { it.title.contains(q, true) || it.url.contains(q, true) }.distinctBy { it.url }.take(4) }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onDismiss)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = Sky.gutter).padding(top = 8.dp)) {
            Row(Modifier.fillMaxWidth().height(Sky.bar).frost(Sky.pill, Sky.frostStrong, Sky.rim).eatTaps().padding(start = 18.dp, end = SpaceInsets.PILL_FRAME_DP.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Sky.sub, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (q.isEmpty()) SkyText(if (tab?.isPrivate == true) "Private: search or type a web address" else "Search or type a web address", T_BODY, Sky.weak)
                    BasicTextField(q, { q = it }, singleLine = true, cursorBrush = SolidColor(Sky.ink),
                        textStyle = SpaceType.bodyText(Sky.ink),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (q.isNotBlank()) onGo(q) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
                if (q.isNotEmpty()) Box(Modifier.size(Sky.control).clip(CircleShape).clickable { q = "" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Clear", tint = Sky.sub, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).frost(Sky.sheet, Sky.frostStrong, Sky.rim).eatTaps()
                .verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                if (typing) {
                    matchTabs.forEach { t -> Result(t.title.ifBlank { t.url }, skyHost(t.url), "Switch", t.icon) { BrowserEngine.switchTo(t); onDismiss() } }
                    matchHist.forEach { e -> Result(e.title.ifBlank { e.url }, skyHost(e.url), "History", null) { onGo(e.url) } }
                    Result("Go to \u201c${q.take(60)}\u201d", skyHost(BrowserEngine.toUrl(q)), "Enter", null) { onGo(q) }
                    if (http) Row(Modifier.fillMaxWidth().clickable { onAsk(q) }.padding(horizontal = SpaceInsets.CARD_PAD_DP.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = Sky.blue, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                        SkyText("Ask ${BrandName.NAME} \u201c${q.take(40)}\u201d", T_BODY, modifier = Modifier.weight(1f)); SkyText("${BrandName.NAME}", T_CAPTION, Sky.weak)
                    }
                    Divider()
                }
                // 4-column tool grid
                val tools = Tool.entries.filter { t -> when (t) {
                    Tool.ASK, Tool.POINTER, Tool.TRANSLATE, Tool.FIND, Tool.SHARE -> http
                    Tool.INSTALL -> tab?.url.orEmpty().startsWith("https://") && tab?.isPrivate != true
                    else -> true } }
                tools.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t ->
                            Column(Modifier.weight(1f).height(68.dp).clip(Sky.tile20).background(Sky.tile).border(1.dp, Sky.rim, Sky.tile20)
                                .clickable { onTool(t) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Icon(if (t == Tool.DESKTOP && BrowserEngine.desktop) Icons.Rounded.PhoneAndroid else t.icon, null, tint = Sky.ink, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.height(6.dp))
                                SkyText(if (t == Tool.DESKTOP && BrowserEngine.desktop) "Mobile" else t.label, T_CAPTION, Sky.sub)
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Divider()
                val vpn = BrowserVpn.status
                Toggle(Icons.Rounded.Shield, "VPN", when (vpn) {
                    BrowserVpn.Status.ON -> (BrowserVpn.exitCountry ?: BrowserVpn.current()?.country)?.let { Countries.byCode(it)?.name ?: it } ?: (BrowserVpn.current()?.label ?: "On")
                    BrowserVpn.Status.CONNECTING -> "Connecting\u2026"; BrowserVpn.Status.FAILED -> "Not connected"; else -> "Off" },
                    BrowserVpn.on, onRow = onVpnMore) { on -> if (on) BrowserVpn.connect(BrowserVpn.defaultId()) else BrowserVpn.disconnect() }
                Toggle(Icons.Rounded.LocationOn, "Mask location", if (BrowserVpn.maskLocation) "On" else "Off", BrowserVpn.maskLocation, onRow = onVpnMore) { BrowserVpn.setMask(it) }
                Toggle(Icons.Rounded.Cookie, "Block ads & trackers", if (TrackerBlock.enabled) "${TrackerBlock.total}" else "Off", TrackerBlock.enabled) { TrackerBlock.setOn(it) }
                Toggle(Icons.Rounded.VisibilityOff, "Private browsing", "", tab?.isPrivate == true) { onPrivate(it) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable private fun Divider() = Box(Modifier.padding(horizontal = SpaceInsets.CARD_PAD_DP.dp, vertical = 8.dp).fillMaxWidth().height(1.dp).background(Sky.rim))

@Composable
private fun Result(title: String, sub: String, tag: String, icon: ImageBitmap?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SiteDot(title, icon, 28.dp); Spacer(Modifier.width(12.dp))
        SkyText(title, T_BODY, modifier = Modifier.weight(1f, fill = false)); Spacer(Modifier.width(6.dp))
        SkyText(sub, T_CAPTION, Sky.weak, Modifier.weight(1f)); SkyText(tag, T_CAPTION, Sky.weak)
    }
}

@Composable
private fun Toggle(icon: ImageVector, label: String, value: String, on: Boolean, onRow: (() -> Unit)? = null, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = SpaceInsets.ROW_MIN_DP.dp).clickable { if (onRow != null) onRow() else set(!on) }
        .padding(horizontal = SpaceInsets.CARD_PAD_DP.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Sky.ink, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(14.dp))
        SkyText(label, T_BODY, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) { SkyText(value, T_CAPTION, Sky.sub); Spacer(Modifier.width(10.dp)) }
        // unification: the one switch (BlazeSwitchTrack). The switch itself still sets the value even when the row opens
        // more (VPN, location: [onRow]), exactly as the Material switch did.
        Box(Modifier.heightIn(min = Sky.control).toggleable(value = on, role = Role.Switch, onValueChange = set), contentAlignment = Alignment.Center) {
            BlazeSwitchTrack(on)
        }
    }
}

// ---------------------------------------------------------------------------------------------- site sheet
/** What the site sheet can open; the browser screen carries it out. */
enum class SiteAction { PASSWORDS, PRIVACY, INSTALL, ASK, BOOKMARK, SAVE, SHARE, OPEN_WITH }

/** The Sky site sheet (tap the lock): trackers, VPN exit, what's in memory, then the page's login, location, cookies and actions. */
@Composable
internal fun SkySiteSheet(onDismiss: () -> Unit, onAction: (SiteAction) -> Unit) {
    val ctx = LocalContext.current
    val t = BrowserEngine.tab() ?: return
    val url = t.url
    val host = skyHost(url)
    val http = url.startsWith("http")
    val insecure = com.blaze.assistant.connect.UrlSafety.notSecure(url)
    val logins = remember(host) { try { if (t.isPrivate) emptyList() else BrowserLogins.forHost(host) } catch (_: Throwable) { emptyList() } }
    val marked = remember(url) { BrowserData.isBookmarked(ctx, url) }
    val vpnOn = BrowserVpn.status == BrowserVpn.Status.ON
    val exit = BrowserVpn.exitCountry ?: BrowserVpn.current()?.country
    val masked = BrowserVpn.maskLocation && (t.isPrivate || BrowserVpn.on)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.padding(horizontal = Sky.gutter).windowInsetsPadding(skyBottomInsets()).padding(bottom = 8.dp).fillMaxWidth()
            .frost(Sky.sheet, Sky.frostStrong, Sky.rim).eatTaps().verticalScroll(rememberScrollState()).padding(vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = SpaceInsets.CARD_PAD_DP.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SiteDot(t.title.ifBlank { host }, t.icon, 40.dp, RoundedCornerShape(12.dp)); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    SkyText(if (SavedPages.isSavedUrl(url)) "Offline copy" else host.ifBlank { "New tab" }, T_SECTION, family = SpaceType.Serif)
                    SkyText((if (insecure) "Not secure" else if (url.startsWith("https")) "Secure" else "Page") +
                        (if (vpnOn) " \u00b7 via ${exit?.let { Countries.byCode(it)?.name ?: it } ?: "VPN"}" else " \u00b7 direct") +
                        (if (t.isPrivate) " \u00b7 private" else ""), T_CAPTION, if (insecure) Sky.warn else Sky.sub)
                }
                Box(Modifier.size(Sky.control).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Close", tint = Sky.ink, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(if (TrackerBlock.enabled) "${t.blocked}" else "Off", "trackers blocked", Modifier.weight(1f))
                Stat(if (vpnOn) (exit ?: "On") else "Off", "VPN exit", Modifier.weight(1f))
                val live = BrowserEngine.liveCount()
                Stat("$live", if (live == 1) "page in memory" else "pages in memory", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            if (!t.isPrivate) Row2(Icons.Rounded.Key, "Saved login", logins.firstOrNull()?.user?.let { if (it.length > 12) it.take(10) + "\u2026" else it }?.ifBlank { "Saved" } ?: "None") { onAction(SiteAction.PASSWORDS) }
            Row2(Icons.Rounded.LocationOn, "Location", if (masked) "Masked" else "Not masked") { onAction(SiteAction.PRIVACY) }
            Row2(Icons.Rounded.Cookie, "Cookies", if (t.isPrivate) "Wiped when private tabs close" else "Allowed") { onAction(SiteAction.PRIVACY) }
            if (http) {
                Row2(if (marked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (marked) "Remove bookmark" else "Bookmark this page", "") { onAction(SiteAction.BOOKMARK) }
                Row2(Icons.Rounded.OfflinePin, "Save page for offline", "") { onAction(SiteAction.SAVE) }
                if (url.startsWith("https://") && !t.isPrivate) Row2(Icons.Rounded.Apps, "Install as app", "Add to Home") { onAction(SiteAction.INSTALL) }
                Row2(Icons.Rounded.Share, "Share link", "") { onAction(SiteAction.SHARE) }
                Row2(Icons.Rounded.OpenInNew, "Open in another app", "") { onAction(SiteAction.OPEN_WITH) }
                Row2(Icons.Rounded.AutoAwesome, "Ask ${BrandName.NAME} about this page", "") { onAction(SiteAction.ASK) }
            }
        }
    }
}

@Composable
private fun Stat(big: String, small: String, modifier: Modifier) {
    Column(modifier.clip(Sky.tile20).background(Sky.tile).border(1.dp, Sky.rim, Sky.tile20).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        SkyText(big, T_SECTION, weight = FontWeight.SemiBold); SkyText(small, T_CAPTION, Sky.sub)
    }
}

@Composable
private fun Row2(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = SpaceInsets.ROW_MIN_DP.dp).clickable(onClick = onClick).padding(horizontal = SpaceInsets.CARD_PAD_DP.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Sky.ink, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(14.dp))
        SkyText(label, T_BODY, modifier = Modifier.weight(1f)); if (value.isNotEmpty()) SkyText(value, T_CAPTION, Sky.sub)
    }
}

// ---------------------------------------------------------------------------------------------- find in page
/** Find in page: a frosted bar with the match count and previous / next. */
@Composable
internal fun SkyFindBar(web: android.webkit.WebView?, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(0 to 0) }
    val focus = remember { FocusRequester() }
    DisposableEffect(web) {
        web?.setFindListener { active, n, done -> if (done) count = (if (n == 0) 0 else active + 1) to n }
        onDispose { try { web?.clearMatches(); web?.setFindListener(null) } catch (_: Throwable) { } }
    }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) { } }
    Row(Modifier.statusBarsPadding().padding(horizontal = Sky.gutter, vertical = 10.dp).fillMaxWidth().height(Sky.bar).frost(Sky.pill, Sky.frostStrong, Sky.rim)
        .padding(start = 16.dp, end = SpaceInsets.PILL_FRAME_DP.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.FindInPage, null, tint = Sky.sub, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (q.isEmpty()) SkyText("Find in page", T_BODY, Sky.weak)
            BasicTextField(q, { q = it; if (it.isBlank()) { web?.clearMatches(); count = 0 to 0 } else web?.findAllAsync(it) }, singleLine = true,
                cursorBrush = SolidColor(Sky.ink), textStyle = SpaceType.bodyText(Sky.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { web?.findNext(true) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        }
        if (q.isNotBlank()) SkyText("${count.first}/${count.second}", T_CAPTION, Sky.sub)
        SkyChipButton(Icons.Rounded.KeyboardArrowUp, "Previous match", enabled = count.second > 0) { web?.findNext(false) }
        SkyChipButton(Icons.Rounded.KeyboardArrowDown, "Next match", enabled = count.second > 0) { web?.findNext(true) }
        SkyChipButton(Icons.Rounded.Close, "Close find") { onClose() }
    }
}

/** Google's page translation for [url], into the phone's language. */
internal fun translateUrl(url: String): String =
    "https://translate.google.com/translate?sl=auto&tl=" + java.util.Locale.getDefault().language.ifBlank { "en" } + "&u=" + Uri.encode(url)
