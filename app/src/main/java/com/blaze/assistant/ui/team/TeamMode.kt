package com.blaze.assistant.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blaze.assistant.store.Sql
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.BotThreads
import com.blaze.assistant.team.TeamStore
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.BotOrb
import com.blaze.assistant.ui.argus.ManagerOrb
import com.blaze.assistant.ui.argus.OrbFace
import com.blaze.assistant.ui.argus.OrbSize
import com.blaze.assistant.ui.argus.kit.ArgusSheet
import com.blaze.assistant.ui.argus.kit.BotChatRow
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.EmptyState
import com.blaze.assistant.ui.argus.kit.FilterChips
import com.blaze.assistant.ui.argus.kit.MenuRow
import com.blaze.assistant.ui.argus.kit.PinStrip
import com.blaze.assistant.ui.argus.kit.SettingsGroup
import com.blaze.assistant.ui.argus.kit.SettingsItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Where the Team screen sends the user (the shell, P1-SH-01, wires these to its routes). */
class TeamNav(
    val openBot: (String) -> Unit = {},
    val memory: (String) -> Unit = {},
    val editSoul: (String) -> Unit = {},
    val newBot: () -> Unit = {},
    val unassigned: () -> Unit = {},
    val allJobs: () -> Unit = {},
)

/**
 * P1-TM-03 Team mode (04 §3.1, A-team `home()`): team name (display) and sub line, domain chips (All until P5), pins,
 * the Manager row, bots and groups by recency, Unassigned, All jobs; empty → "Build your team" with New bot and Demo
 * team. Long-press a row or pin → quick sheet ([QuickAction.of]: the Manager has no Delete). Stateless: [TeamModeScreen]
 * loads the state. Nothing in the app opens it yet (the shell, P1-SH-01, adds the mode).
 */
@Composable
fun TeamMode(
    state: TeamState,
    bots: Map<String, Bot>,
    nav: TeamNav,
    onPin: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onDemoTeam: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 100.dp),
) {
    val c = Argus.colors
    var sheetFor by remember { mutableStateOf<Bot?>(null) }
    var confirmDelete by remember { mutableStateOf<Bot?>(null) }
    var chip by remember { mutableStateOf(TeamModel.ALL) }
    fun avatar(id: String, name: String, size: Float): @Composable () -> Unit = {
        if (id == BotRules.MANAGER_ID) ManagerOrb(size.dp, contentDescription = null)
        else BotOrb(state.faces[id]?.let { OrbFace.of(it) } ?: OrbFace.forName(name), state.colors[id] ?: 0xFFCBD5E1L, size.dp)
    }
    val longPress: (String) -> Unit = { id -> bots[id]?.let { sheetFor = it } }

    LazyColumn(modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "header") {
            Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp)) {
                androidx.compose.foundation.layout.Column {
                    Text(state.header.name, style = ArgusType.display, color = c.ink, modifier = Modifier.semantics { heading() })
                    Text(state.header.sub, style = ArgusType.sub, color = c.mute)
                }
            }
        }
        if (state.empty) {
            item(key = "empty") {
                EmptyState(
                    title = "Build your team",
                    body = "Bots that each own a part of your life, with ${BotRules.MANAGER_NAME} as the lead.",
                    primary = "New bot" to nav.newBot,
                    secondary = "Demo team" to onDemoTeam,
                    hero = true,
                    heroArt = {
                        Row(horizontalArrangement = Arrangement.spacedBy((-14).dp), verticalAlignment = Alignment.CenterVertically) {
                            BotOrb(OrbFace.TILT, 0xFF22C55EL, OrbSize.LG.dp)
                            ManagerOrb(OrbSize.XL.dp, contentDescription = null)
                            BotOrb(OrbFace.TWIN, 0xFFF59E0BL, OrbSize.LG.dp)
                        }
                    },
                )
            }
            state.manager?.let { m -> item(key = "manager") { BotChatRow(m, { nav.openBot(m.id) }, onLongClick = { longPress(m.id) }, avatar = avatar(m.id, m.name, OrbSize.MD)) } }
            return@LazyColumn
        }
        item(key = "chips") { FilterChips(state.chips, chip, { chip = it }, Modifier.padding(bottom = 8.dp)) }
        if (state.pins.isNotEmpty()) item(key = "pins") {
            PinStrip(state.pins, { nav.openBot(it.id) }, Modifier.padding(vertical = 6.dp),
                avatar = { p -> avatar(p.id, p.name, OrbSize.LG)() })
        }
        state.manager?.let { m ->
            item(key = "manager") { BotChatRow(m, { nav.openBot(m.id) }, onLongClick = { longPress(m.id) }, avatar = avatar(m.id, m.name, OrbSize.MD)) }
        }
        items(state.rows, key = { it.id }) { r ->
            BotChatRow(r, { nav.openBot(r.id) }, onLongClick = { longPress(r.id) }, onPin = { onPin(r.id, !r.pinned) },
                avatar = avatar(r.id, r.name, OrbSize.MD))
        }
        item(key = "jobs") {
            val items = buildList {
                if (state.unassigned > 0) add(SettingsItem("list-checks", "Unassigned", state.unassigned.toString(), onClick = nav.unassigned))
                add(SettingsItem("list-checks", "All jobs", state.openJobs.takeIf { it > 0 }?.toString(), onClick = nav.allJobs))
            }
            SettingsGroup(items, Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
        }
    }

    sheetFor?.let { b ->
        ArgusSheet(onDismiss = { sheetFor = null }, title = b.name) {
            for (a in QuickAction.of(b)) MenuRow(a.icon, a.label, onClick = {
                sheetFor = null
                when (a) {
                    QuickAction.OPEN -> nav.openBot(b.id)
                    QuickAction.PIN -> onPin(b.id, true)
                    QuickAction.UNPIN -> onPin(b.id, false)
                    QuickAction.MEMORY -> nav.memory(b.id)
                    QuickAction.EDIT_SOUL -> nav.editSoul(b.id)
                    QuickAction.DELETE -> confirmDelete = b
                }
            }, danger = a == QuickAction.DELETE)
        }
    }
    confirmDelete?.let { b ->
        ArgusSheet(
            onDismiss = { confirmDelete = null }, title = "Delete ${b.name}?",
            primary = if (b.kind == BotKind.DOMAIN) "Switch off" else "Delete", primaryKind = ButtonKind.DANGER,
            onPrimary = { confirmDelete = null; onDelete(b.id) },
        ) {
            Text(TeamCopy.deleteBody(b), style = ArgusType.body, color = c.mute, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

/** Words the Team screen shows (kept pure for tests). */
object TeamCopy {
    fun deleteBody(b: Bot): String =
        if (b.kind == BotKind.DOMAIN) "${b.name} is switched off and leaves the team. Its memory stays, and you can bring it back from Settings."
        else "${b.name} leaves the team. Its open jobs move to Unassigned."
}

/**
 * P1-TM-03 the Team screen with its data: reads the roster, threads and job counts off the main thread, and again on
 * every [BotThreads.changes] and after each action. [teamName] is the user's (Settings), [zone] the user's time zone.
 */
@Composable
fun TeamModeScreen(
    store: TeamStore,
    threads: BotThreads,
    sql: Sql,
    nav: TeamNav,
    modifier: Modifier = Modifier,
    teamName: String? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    clock: () -> Long = { System.currentTimeMillis() },
) {
    var state by remember { mutableStateOf<TeamState?>(null) }
    var bots by remember { mutableStateOf<Map<String, Bot>>(emptyMap()) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val actions = remember(store) { TeamActions(store) }
    LaunchedEffect(tick, teamName, zone) {
        val (s, b) = withContext(Dispatchers.IO) {
            val now = clock()
            val roster = store.roster()
            val jobs = JobCounts.read(sql, java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
            TeamModel.state(teamName, roster, threads.threads(), jobs, now, zone) to roster.associateBy { it.id }
        }
        state = s; bots = b
    }
    LaunchedEffect(threads) { threads.changes.collect { tick++ } }
    val s = state ?: return
    TeamMode(
        s, bots, nav,
        onPin = { id, on -> scope.launch { withContext(Dispatchers.IO) { actions.setPinned(id, on) }; tick++ } },
        onDelete = { id -> scope.launch { withContext(Dispatchers.IO) { actions.delete(id) }; tick++ } },
        onDemoTeam = { scope.launch { withContext(Dispatchers.IO) { actions.addDemoTeam(clock()) }; tick++ } },
        modifier = modifier,
    )
}

/**
 * P1-SH-01: the Team page of the ARGUS shell. Opens team.db ([com.blaze.assistant.team.TeamGraph]) off the main thread,
 * then shows [TeamModeScreen]; a failure to open shows a short note instead of crashing the shell.
 */
@Composable
fun TeamPage(nav: TeamNav, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var graph by remember { mutableStateOf<com.blaze.assistant.team.TeamGraph.Graph?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { graph = withContext(Dispatchers.IO) { com.blaze.assistant.team.TeamGraph.get(ctx.applicationContext) } }
        catch (t: Throwable) { failed = true; com.blaze.assistant.device.CrashLog.quiet("TeamPage.open", t) }
    }
    val g = graph
    when {
        g != null -> TeamModeScreen(g.store, g.threads, g.sql, nav, modifier)
        failed -> EmptyState(title = "Team couldn't open", body = "Your chats are safe. Try again after restarting the app.", modifier = modifier)
    }
}
