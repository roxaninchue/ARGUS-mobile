package com.argus.assistant.ui.team

import com.argus.assistant.ui.common.cutoutSafe
import com.argus.assistant.ui.common.withGutter

import com.argus.assistant.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.argus.assistant.BrandName
import com.argus.assistant.ui.argus.Argus
import com.argus.assistant.ui.argus.ArgusTokens
import com.argus.assistant.ui.argus.ArgusType
import com.argus.assistant.ui.argus.GlassLevel
import com.argus.assistant.ui.argus.argusGlass
import com.argus.assistant.ui.argus.icons.Lucide
import com.argus.assistant.ui.argus.kit.ArgusButton
import com.argus.assistant.ui.argus.kit.ButtonKind
import com.argus.assistant.ui.argus.kit.ButtonSize
import com.argus.assistant.ui.argus.kit.ProgressLine
import com.argus.assistant.ui.argus.kit.SettingsGroup
import com.argus.assistant.ui.argus.kit.SettingsItem
import com.argus.assistant.ui.space.SpaceAnswer

/** Where the Life page sends the user (the shell wires these, as it does for the 0.52 Home). */
class LifeNav(
    val onAsk: (String) -> Unit = {},
    val onOpenChat: () -> Unit = {},
    val onOpenProject: (String) -> Unit = {},
    val onOpenProjects: () -> Unit = {},
    val onSignIn: () -> Unit = {},
    val onSetup: () -> Unit = {},
    /** The Today strip → Schedule (AM SchedulePage, /schedule). */
    val onOpenSchedule: () -> Unit = {},
    /** A domain tile → "Set up my life team" (P5 replaces this with the domain page). */
    val onSetUpLife: (LifeModel.DomainTile?) -> Unit = {},
    /** Owner 2026-11-14: quick access to connecting accounts and adding apps. */
    val onConnect: () -> Unit = {},
    val onApps: () -> Unit = {},
)

object LifeTags {
    const val PAGE = "life"
    const val DAY = "life-day"
    const val TODAY = "life-today"
    const val DOMAINS = "life-domains"
    const val PROJECTS = "life-projects"
    const val PANELS = "life-panels"
    const val SIGN_IN = "life-sign-in"
    const val SETUP = "life-setup"
    fun domain(key: String) = "life-domain-$key"
    /** A to-do panel's row [i] (2026-11-18: the first-run checklist is tapped in tests and device checks by these). */
    fun todo(widget: String, i: Int) = "life-todo-$widget-$i"
}

/**
 * P1-SH-05 Life mode v1 (04 §4.12): serif day line, Today strip, domain tiles ("Set up my life team" until P5),
 * Projects, Panels (weather, latest answer, suggested asks). Stateless; [LifePage] feeds it from the live app.
 * The AM Home's cards are here by [LifeModel.HOME_CARDS]; the 0.52 SpaceHome screen is gone (ARGUS-only, 16 Nov 2026).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LifeMode(
    state: LifeModel.LifeState,
    nav: LifeNav,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    board: List<Pair<String, List<com.argus.assistant.home.Widget>>> = emptyList(),
    boardData: Map<String, com.argus.assistant.home.WidgetData> = emptyMap(),
    boardActions: LifeBoardActions = LifeBoardActions(),
    cal: LifeCal? = null,
    fold: LifeFold = LifeFold(),
) {
    val c = Argus.colors
    var menuFor by remember { mutableStateOf<com.argus.assistant.home.Widget?>(null) }
    androidx.compose.runtime.CompositionLocalProvider(LocalLifeFold provides fold) {
    // Landscape overhaul: clear of the cutout, cards centred at a readable width; 0 gutter upright
    com.argus.assistant.ui.common.WindowUi.WithGutter(modifier = modifier.fillMaxSize().cutoutSafe()) { gutter ->
    LazyColumn(Modifier.fillMaxSize().testTag(LifeTags.PAGE), contentPadding = contentPadding.withGutter(gutter), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (s in state.sections) {
            // Owner 2026-11-14: what ARGUS keeps on the dashboard, one folding header per category, a panel per item
            if (s == LifeModel.Section.BOARD) {
                for ((head, panels) in board) {
                    item(key = "board-h-$head") { LifeFoldHead(head, head, count = panels.size) }
                    if (!fold.isFolded(head)) items(panels, key = { "board-" + it.id }) { w -> LifeBoardPanel(w, boardData[w.id], boardActions) { menuFor = w } }
                }
                continue
            }
            item(key = s.name) {
            when (s) {
                LifeModel.Section.BOARD -> {}
                LifeModel.Section.CALENDAR -> if (cal != null) Column(Modifier.fillMaxWidth()) {
                    LifeFoldHead("Calendar", LifeBoard.SECTION + "Calendar")
                    if (!fold.isFolded(LifeBoard.SECTION + "Calendar")) LifeCalendarCard(cal, nav.onOpenSchedule)
                }
                LifeModel.Section.DAY_LINE -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.day?.takeIf { com.argus.assistant.i18n.AppLang.code.value != com.argus.assistant.i18n.AppLang.DEFAULT }
                        ?.let { LifeModel.dayLine(it, java.util.Locale.getDefault()) { s, a -> tr(s, *a) } } ?: state.dayLine, style = ArgusType.serifHello, color = c.ink,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp).testTag(LifeTags.DAY).semantics { heading() })
                    LifeQuickAccess(nav.onConnect, nav.onApps)
                }
                LifeModel.Section.SIGN_IN -> LifeCard(Modifier.testTag(LifeTags.SIGN_IN)) {
                    Text(tr("{0} can do more", BrandName.UPPER), style = ArgusType.title2, color = c.ink)
                    Text(tr("Sign in to your {0} account and it starts working for you right away. No keys, no settings.", BrandName.NAME),
                        style = ArgusType.body, color = c.mute, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                    ArgusButton(tr("Sign in"), nav.onSignIn, kind = ButtonKind.PRIMARY, icon = "user")
                }
                LifeModel.Section.SETUP -> Box(Modifier.padding(horizontal = 12.dp).testTag(LifeTags.SETUP)) {
                    ProgressLine(tr("Finishing setup \u00b7 {0} left", state.setupLeft), state.setupPercent / 100f, onClick = nav.onSetup)
                }
                LifeModel.Section.TODAY -> TodayStripView(state.strip, nav.onOpenSchedule)
                LifeModel.Section.DOMAINS -> DomainGrid(state.domains, nav.onSetUpLife)
                LifeModel.Section.PROJECTS -> {
                    val rows = state.projects.map { (id, name) -> SettingsItem("layers", name.ifBlank { "Untitled project" }, onClick = { nav.onOpenProject(id) }) } +
                        listOfNotNull(
                            if (state.projects.isNotEmpty()) SettingsItem("list-checks", "All projects", onClick = nav.onOpenProjects) else null,
                            SettingsItem("plus", "New project", onClick = nav.onOpenProjects),
                        )
                    Column(Modifier.fillMaxWidth().testTag(LifeTags.PROJECTS)) {
                        LifeFoldHead("Projects", LifeBoard.SECTION + "Projects", count = state.projects.size)
                        if (!fold.isFolded(LifeBoard.SECTION + "Projects")) SettingsGroup(rows, Modifier.padding(horizontal = 12.dp))
                    }
                }
                LifeModel.Section.PANELS -> Panels(state.weather, state.answer, state.asks, nav)
            }
            }
        }
    }
    }
    }
    menuFor?.let { w ->
        com.argus.assistant.ui.argus.kit.ArgusSheet(onDismiss = { menuFor = null }, title = w.title) {
            com.argus.assistant.ui.argus.kit.MenuRow("refresh-cw", tr("Refresh"), { menuFor = null; boardActions.onRefresh(w) })
            com.argus.assistant.ui.argus.kit.MenuRow("trash-2", tr("Remove from Life"), { menuFor = null; boardActions.onRemove(w) }, danger = true)
        }
    }
}

@Composable
private fun LifeCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, label: String? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(ArgusTokens.Radius.CARD.dp)
    val click = onClick?.let { Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = it) } ?: Modifier
    Column(modifier.padding(horizontal = 12.dp).fillMaxWidth().argusGlass(GlassLevel.SURFACE, shape).then(click).padding(16.dp)) { content() }
}

@Composable
private fun SectionHead(text: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    val c = Argus.colors
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr(text).uppercase(), style = ArgusType.section, color = c.meta, modifier = Modifier.weight(1f).semantics { heading(); contentDescription = text })
        if (trailing != null && onTrailing != null) Text(trailing, style = ArgusType.sub, color = c.mute,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onTrailing).heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 14.dp))
    }
}

/** The Today strip: a line from 06 (or 00) to 24 with tick hours, a dot per item, the now mark, then the items as chips. */
@Composable
private fun TodayStripView(strip: LifeModel.TodayStrip, onOpen: () -> Unit) {
    val c = Argus.colors
    Column(Modifier.fillMaxWidth().testTag(LifeTags.TODAY)) {
        LifeFoldHead("Today", LifeBoard.SECTION + "Today", tr("Schedule \u203a"), onOpen)
        if (!LocalLifeFold.current.isFolded(LifeBoard.SECTION + "Today")) Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.CARD.dp))
            .clickable(role = Role.Button, onClickLabel = tr("Open Schedule"), onClick = onOpen).padding(vertical = 14.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(30.dp)) {
                val w = maxWidth
                Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center).background(c.line2))
                for (h in strip.ticks) Text("%02d".format(h), style = ArgusType.meta, color = c.meta,
                    modifier = Modifier.align(Alignment.TopStart).offset(x = (w * strip.tickAt(h)) - 8.dp, y = (-2).dp))
                for (i in strip.timed) i.at?.let { at ->
                    Box(Modifier.align(Alignment.CenterStart).offset(x = w * at - 4.dp, y = 0.dp).size(8.dp).clip(CircleShape)
                        .background(if (i.passed) c.dim else c.ink))
                }
                strip.now?.let { at ->
                    Box(Modifier.align(Alignment.CenterStart).offset(x = w * at - 1.dp).width(2.dp).height(18.dp).background(c.brandCobalt))
                }
            }
            if (strip.empty) Text(tr("Nothing on today. Ask me to plan something, or add a routine."), style = ArgusType.sub, color = c.mute,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            else LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(strip.allDay + strip.timed) { i ->
                    val glyph = when (i.kind) { "event" -> "calendar"; "alarm" -> "bell"; else -> "repeat" }
                    Row(Modifier.clip(RoundedCornerShape(ArgusTokens.Radius.PILL.dp)).background(c.soft).padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(i.time ?: tr("All day"), i.title, if (i.passed) tr("done") else null).joinToString(", ") },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Lucide.icon(glyph, 14), null, Modifier.size(14.dp), tint = if (i.passed) c.dim else c.mute)
                        Text((i.time ?: tr("All day")) + "  " + i.title, style = ArgusType.sub, color = if (i.passed) c.dim else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Domain tiles, two per row: hue disc + glyph, the domain, and "Set up my life team" until its lead exists (P5). */
@Composable
private fun DomainGrid(domains: List<LifeModel.DomainTile>, onTap: (LifeModel.DomainTile?) -> Unit) {
    val c = Argus.colors
    Column(Modifier.fillMaxWidth().testTag(LifeTags.DOMAINS)) {
        LifeFoldHead("Your life", LifeBoard.SECTION + "Your life", tr(LifeModel.SET_UP) + " \u203a", onTrailing = { onTap(null) })
        if (!LocalLifeFold.current.isFolded(LifeBoard.SECTION + "Your life")) Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Landscape overhaul: two tiles a row upright, three or four when the page is wide
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cols = com.argus.assistant.ui.common.WindowLayout.cardColumns(maxWidth.value, 200f, maxCols = 4).coerceAtLeast(2)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (pair in domains.chunked(cols)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (d in pair) {
                    val hue = Color(d.color)
                    Column(Modifier.weight(1f).argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.CARD.dp), shadow = false)
                        .clickable(role = Role.Button, onClickLabel = tr(LifeModel.SET_UP)) { onTap(d) }
                        .semantics(mergeDescendants = true) { contentDescription = "${tr(d.label)}. ${tr(d.line)}" }
                        .testTag(LifeTags.domain(d.key)).heightIn(min = 48.dp).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(hue.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                                Icon(Lucide.icon(d.glyph, 16), null, Modifier.size(16.dp), tint = hue)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(tr(d.label), style = ArgusType.rowTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(tr(d.line), style = ArgusType.sub, color = c.mute, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                repeat(cols - pair.size) { Spacer(Modifier.weight(1f)) }
            }
            }
            }
        }
    }
}

/** The AM Home panels: weather (cached), the latest answer (opens Chat), suggested asks (at most three, sent to the Manager). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Panels(weather: LifeModel.Weather?, answer: SpaceAnswer.Card?, asks: List<String>, nav: LifeNav) {
    val c = Argus.colors
    Column(Modifier.fillMaxWidth().testTag(LifeTags.PANELS), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LifeFoldHead("Panels", LifeBoard.SECTION + "Panels")
        if (LocalLifeFold.current.isFolded(LifeBoard.SECTION + "Panels")) return@Column
        weather?.let { w ->
            LifeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.icon("sun", 20), null, Modifier.size(20.dp), tint = c.ink)
                    Spacer(Modifier.width(12.dp))
                    Text(w.value, style = ArgusType.title1, color = c.ink)
                    Spacer(Modifier.width(10.dp))
                    Text(w.line, style = ArgusType.sub, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        answer?.let { a ->
            LifeCard(onClick = nav.onOpenChat, label = tr("Open the answer in the chat")) {
                Text(a.ask, style = ArgusType.meta, color = c.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (a.text.isBlank()) tr("Working on it\u2026") else a.text, style = ArgusType.body, color = c.ink, maxLines = 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            asks.forEach { s -> ArgusButton(s, { nav.onAsk(tr(s)) }, size = ButtonSize.S) }
        }
    }
}

/**
 * P1-SH-05: Life's page in the ARGUS shell with the live app's data. The day's items are read off the main thread
 * ([com.argus.assistant.agent.TurnContextSource.dayEntries]: routines, alarms, calendar when allowed), again when the
 * routines change and every minute (the now mark and "still ahead"). The chat's messages are collected here, so a
 * streaming reply only recomposes this page.
 */
@Composable
fun LifePage(
    nav: LifeNav,
    projects: List<Pair<String, String>>,
    signedIn: Boolean,
    working: Boolean,
    setupLeft: Int,
    setupPercent: Int,
    modifier: Modifier = Modifier,
) {
    val messages by com.argus.assistant.agent.Agent.messages.collectAsState()
    val routines by com.argus.assistant.routine.Routines.all.collectAsState()
    val widgets by com.argus.assistant.home.HomeBoard.widgets.collectAsState()
    val board by com.argus.assistant.home.HomeBoard.data.collectAsState()
    var items by remember { mutableStateOf<List<com.argus.assistant.agent.ScheduleDay.Item>>(emptyList()) }
    var now by remember { mutableStateOf(java.time.ZonedDateTime.now()) }
    LaunchedEffect(routines) {
        while (true) {
            now = java.time.ZonedDateTime.now()
            items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { com.argus.assistant.agent.ScheduleDay.itemsOn(now.toLocalDate(), now.zone, com.argus.assistant.agent.TurnContextSource.dayEntries(now)) }
                catch (t: Throwable) { com.argus.assistant.device.CrashLog.quiet("LifePage.items", t); emptyList() }
            }
            kotlinx.coroutines.delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val answer = remember(messages, working) {
        SpaceAnswer.card(0L, messages.takeLast(40).filter { it.role == com.argus.assistant.agent.Role.USER || it.role == com.argus.assistant.agent.Role.BLAZE }
            .map { SpaceAnswer.Msg(it.id, it.role == com.argus.assistant.agent.Role.BLAZE, com.argus.assistant.agent.ToolLeak.bubbleText(it.text)) }, working)
    }
    val weather = remember(widgets, board, now) {
        val w = widgets.firstOrNull { it.type == com.argus.assistant.home.WidgetTypes.WEATHER }
        val d = w?.let { board[it.id] }
        d?.let { LifeModel.weather(it.value, it.change, it.headline, it.fetched, System.currentTimeMillis()) }
    }
    // Owner 2026-11-14: the dashboard ARGUS fills (newest header first, each folds), the month calendar and the picked day
    val groups = remember(widgets) { LifeBoard.groups(widgets) }
    var folded by remember { mutableStateOf(LifeBoard.folded(try { com.argus.assistant.agent.Settings.str(LifeBoard.FOLD_KEY) } catch (_: Throwable) { "" })) }
    val fold = LifeFold(folded) { k ->
        folded = LifeBoard.toggle(folded, k)
        try { com.argus.assistant.agent.Settings.setStr(LifeBoard.FOLD_KEY, LifeBoard.save(folded)) } catch (_: Throwable) {}
    }
    val today = now.toLocalDate()
    var month by remember { mutableStateOf(java.time.YearMonth.from(today)) }
    var picked by remember { mutableStateOf(today) }
    var dayItems by remember { mutableStateOf<List<com.argus.assistant.agent.ScheduleDay.Item>>(emptyList()) }
    LaunchedEffect(picked, items) {
        dayItems = if (picked == today) items else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val at = picked.atStartOfDay(now.zone).plusHours(12)
                com.argus.assistant.agent.ScheduleDay.itemsOn(picked, now.zone, com.argus.assistant.agent.TurnContextSource.dayEntries(at))
            } catch (t: Throwable) { com.argus.assistant.device.CrashLog.quiet("LifePage.day", t); emptyList() }
        }
    }
    val cal = LifeCal(month, picked, today, dayItems, onSelect = { picked = it }, onMonth = { d -> month = month.plusMonths(d.toLong()) })
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val actions = remember { liveBoardActions(ctx) }
    // 2026-11-18: the first-run checklist ticks the steps done outside ARGUS (a permission in Android's settings) on each visit.
    val life = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(life) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) com.argus.assistant.setup.FirstRun.sync(ctx) }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }
    val state = LifeModel.state(now.toLocalDate(), now.toLocalTime(), items, projects, weather, answer, signedIn, setupLeft, setupPercent,
        board = groups.size)
    LifeMode(state, nav, modifier, board = groups, boardData = board, boardActions = actions, cal = cal, fold = fold)
}
