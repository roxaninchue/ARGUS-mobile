package com.blaze.assistant.ui.team

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
import com.blaze.assistant.BrandName
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Lucide
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.ButtonSize
import com.blaze.assistant.ui.argus.kit.ProgressLine
import com.blaze.assistant.ui.argus.kit.SettingsGroup
import com.blaze.assistant.ui.argus.kit.SettingsItem
import com.blaze.assistant.ui.space.SpaceAnswer

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
}

/**
 * P1-SH-05 Life mode v1 (04 §4.12): serif day line, Today strip, domain tiles ("Set up my life team" until P5),
 * Projects, Panels (weather, latest answer, suggested asks). Stateless; [LifePage] feeds it from the live app.
 * The AM Home's cards (SpaceHome.kt) are here by [LifeModel.HOME_CARDS]; SpaceHome itself stays for `argus_look` off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LifeMode(
    state: LifeModel.LifeState,
    nav: LifeNav,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 24.dp),
    board: List<Pair<String, List<com.blaze.assistant.home.Widget>>> = emptyList(),
    boardData: Map<String, com.blaze.assistant.home.WidgetData> = emptyMap(),
    boardActions: LifeBoardActions = LifeBoardActions(),
    cal: LifeCal? = null,
    fold: LifeFold = LifeFold(),
) {
    val c = Argus.colors
    var menuFor by remember { mutableStateOf<com.blaze.assistant.home.Widget?>(null) }
    androidx.compose.runtime.CompositionLocalProvider(LocalLifeFold provides fold) {
    LazyColumn(modifier.fillMaxSize().testTag(LifeTags.PAGE), contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                    Text(state.dayLine, style = ArgusType.serifHello, color = c.ink,
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp).testTag(LifeTags.DAY).semantics { heading() })
                    LifeQuickAccess(nav.onConnect, nav.onApps)
                }
                LifeModel.Section.SIGN_IN -> LifeCard(Modifier.testTag(LifeTags.SIGN_IN)) {
                    Text("${BrandName.UPPER} can do more", style = ArgusType.title2, color = c.ink)
                    Text("Sign in to your ${BrandName.NAME} account and it starts working for you right away. No keys, no settings.",
                        style = ArgusType.body, color = c.mute, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                    ArgusButton("Sign in", nav.onSignIn, kind = ButtonKind.PRIMARY, icon = "user")
                }
                LifeModel.Section.SETUP -> Box(Modifier.padding(horizontal = 12.dp).testTag(LifeTags.SETUP)) {
                    ProgressLine("Finishing setup \u00b7 ${state.setupLeft} left", state.setupPercent / 100f, onClick = nav.onSetup)
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
    menuFor?.let { w ->
        com.blaze.assistant.ui.argus.kit.ArgusSheet(onDismiss = { menuFor = null }, title = w.title) {
            com.blaze.assistant.ui.argus.kit.MenuRow("refresh-cw", "Refresh", { menuFor = null; boardActions.onRefresh(w) })
            com.blaze.assistant.ui.argus.kit.MenuRow("trash-2", "Remove from Life", { menuFor = null; boardActions.onRemove(w) }, danger = true)
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
        Text(text.uppercase(), style = ArgusType.section, color = c.meta, modifier = Modifier.weight(1f).semantics { heading(); contentDescription = text })
        if (trailing != null && onTrailing != null) Text(trailing, style = ArgusType.sub, color = c.mute,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onTrailing).heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 14.dp))
    }
}

/** The Today strip: a line from 06 (or 00) to 24 with tick hours, a dot per item, the now mark, then the items as chips. */
@Composable
private fun TodayStripView(strip: LifeModel.TodayStrip, onOpen: () -> Unit) {
    val c = Argus.colors
    Column(Modifier.fillMaxWidth().testTag(LifeTags.TODAY)) {
        LifeFoldHead("Today", LifeBoard.SECTION + "Today", "Schedule \u203a", onOpen)
        if (!LocalLifeFold.current.isFolded(LifeBoard.SECTION + "Today")) Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.CARD.dp))
            .clickable(role = Role.Button, onClickLabel = "Open Schedule", onClick = onOpen).padding(vertical = 14.dp)) {
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
            if (strip.empty) Text("Nothing on today. Ask me to plan something, or add a routine.", style = ArgusType.sub, color = c.mute,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            else LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(strip.allDay + strip.timed) { i ->
                    val glyph = when (i.kind) { "event" -> "calendar"; "alarm" -> "bell"; else -> "repeat" }
                    Row(Modifier.clip(RoundedCornerShape(ArgusTokens.Radius.PILL.dp)).background(c.soft).padding(horizontal = 10.dp, vertical = 6.dp)
                        .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(i.time ?: "All day", i.title, if (i.passed) "done" else null).joinToString(", ") },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Lucide.icon(glyph, 14), null, Modifier.size(14.dp), tint = if (i.passed) c.dim else c.mute)
                        Text((i.time ?: "All day") + "  " + i.title, style = ArgusType.sub, color = if (i.passed) c.dim else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        LifeFoldHead("Your life", LifeBoard.SECTION + "Your life", LifeModel.SET_UP + " \u203a", onTrailing = { onTap(null) })
        if (!LocalLifeFold.current.isFolded(LifeBoard.SECTION + "Your life")) Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (pair in domains.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (d in pair) {
                    val hue = Color(d.color)
                    Column(Modifier.weight(1f).argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.CARD.dp), shadow = false)
                        .clickable(role = Role.Button, onClickLabel = LifeModel.SET_UP) { onTap(d) }
                        .semantics(mergeDescendants = true) { contentDescription = "${d.label}. ${d.line}" }
                        .testTag(LifeTags.domain(d.key)).heightIn(min = 48.dp).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(hue.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                                Icon(Lucide.icon(d.glyph, 16), null, Modifier.size(16.dp), tint = hue)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(d.label, style = ArgusType.rowTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(d.line, style = ArgusType.sub, color = c.mute, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
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
            LifeCard(onClick = nav.onOpenChat, label = "Open the answer in the chat") {
                Text(a.ask, style = ArgusType.meta, color = c.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (a.text.isBlank()) "Working on it\u2026" else a.text, style = ArgusType.body, color = c.ink, maxLines = 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            asks.forEach { s -> ArgusButton(s, { nav.onAsk(s) }, size = ButtonSize.S) }
        }
    }
}

/**
 * P1-SH-05: Life's page in the ARGUS shell with the live app's data. The day's items are read off the main thread
 * ([com.blaze.assistant.agent.TurnContextSource.dayEntries]: routines, alarms, calendar when allowed), again when the
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
    val messages by com.blaze.assistant.agent.Agent.messages.collectAsState()
    val routines by com.blaze.assistant.routine.Routines.all.collectAsState()
    val widgets by com.blaze.assistant.home.HomeBoard.widgets.collectAsState()
    val board by com.blaze.assistant.home.HomeBoard.data.collectAsState()
    var items by remember { mutableStateOf<List<com.blaze.assistant.agent.ScheduleDay.Item>>(emptyList()) }
    var now by remember { mutableStateOf(java.time.ZonedDateTime.now()) }
    LaunchedEffect(routines) {
        while (true) {
            now = java.time.ZonedDateTime.now()
            items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try { com.blaze.assistant.agent.ScheduleDay.itemsOn(now.toLocalDate(), now.zone, com.blaze.assistant.agent.TurnContextSource.dayEntries(now)) }
                catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("LifePage.items", t); emptyList() }
            }
            kotlinx.coroutines.delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    val answer = remember(messages, working) {
        SpaceAnswer.card(0L, messages.takeLast(40).filter { it.role == com.blaze.assistant.agent.Role.USER || it.role == com.blaze.assistant.agent.Role.BLAZE }
            .map { SpaceAnswer.Msg(it.id, it.role == com.blaze.assistant.agent.Role.BLAZE, com.blaze.assistant.agent.ToolLeak.bubbleText(it.text)) }, working)
    }
    val weather = remember(widgets, board, now) {
        val w = widgets.firstOrNull { it.type == com.blaze.assistant.home.WidgetTypes.WEATHER }
        val d = w?.let { board[it.id] }
        d?.let { LifeModel.weather(it.value, it.change, it.headline, it.fetched, System.currentTimeMillis()) }
    }
    // Owner 2026-11-14: the dashboard ARGUS fills (newest header first, each folds), the month calendar and the picked day
    val groups = remember(widgets) { LifeBoard.groups(widgets) }
    var folded by remember { mutableStateOf(LifeBoard.folded(try { com.blaze.assistant.agent.Settings.str(LifeBoard.FOLD_KEY) } catch (_: Throwable) { "" })) }
    val fold = LifeFold(folded) { k ->
        folded = LifeBoard.toggle(folded, k)
        try { com.blaze.assistant.agent.Settings.setStr(LifeBoard.FOLD_KEY, LifeBoard.save(folded)) } catch (_: Throwable) {}
    }
    val today = now.toLocalDate()
    var month by remember { mutableStateOf(java.time.YearMonth.from(today)) }
    var picked by remember { mutableStateOf(today) }
    var dayItems by remember { mutableStateOf<List<com.blaze.assistant.agent.ScheduleDay.Item>>(emptyList()) }
    LaunchedEffect(picked, items) {
        dayItems = if (picked == today) items else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val at = picked.atStartOfDay(now.zone).plusHours(12)
                com.blaze.assistant.agent.ScheduleDay.itemsOn(picked, now.zone, com.blaze.assistant.agent.TurnContextSource.dayEntries(at))
            } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("LifePage.day", t); emptyList() }
        }
    }
    val cal = LifeCal(month, picked, today, dayItems, onSelect = { picked = it }, onMonth = { d -> month = month.plusMonths(d.toLong()) })
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val actions = remember { liveBoardActions(ctx) }
    val state = LifeModel.state(now.toLocalDate(), now.toLocalTime(), items, projects, weather, answer, signedIn, setupLeft, setupPercent,
        board = groups.size)
    LifeMode(state, nav, modifier, board = groups, boardData = board, boardActions = actions, cal = cal, fold = fold)
}
