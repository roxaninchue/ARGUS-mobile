package com.blaze.assistant.ui.team

import com.blaze.assistant.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.store.Sql
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.BotThreads
import com.blaze.assistant.team.JobStore
import com.blaze.assistant.team.TeamStore
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.BotOrb
import com.blaze.assistant.ui.argus.ManagerOrb
import com.blaze.assistant.ui.argus.OrbFace
import com.blaze.assistant.ui.argus.OrbSize
import com.blaze.assistant.ui.argus.icons.Lucide
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ArgusField
import com.blaze.assistant.ui.argus.kit.ArgusSearch
import com.blaze.assistant.ui.argus.kit.ArgusSheet
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.ButtonSize
import com.blaze.assistant.ui.argus.kit.FilterChips
import com.blaze.assistant.ui.argus.kit.Segmented
import com.blaze.assistant.ui.argus.kit.ChipModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * P1-TM-07 Desk → Jobs (04 §4.7, A-team jobs page): filter chips with counts, search, the job rows ([JobRowModel]), a FAB
 * for a new job and the job sheet (status, brief, result, the log, Retry / Done / Cancel). Stateless; [JobsPageScreen]
 * loads it. Every rule is in [JobsPageModel] / [JobSheetModel] / [NewJob].
 */
@Composable
fun JobsPage(
    state: JobsState,
    query: String,
    onQuery: (String) -> Unit,
    onFilter: (String) -> Unit,
    onBot: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDone: (String) -> Unit,
    onNew: () -> Unit,
    avatar: (String?) -> (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val c = Argus.colors
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item(key = "search") { ArgusSearch(query, onQuery, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), placeholder = tr("Search jobs")) }
            item(key = "filters") { FilterChips(state.chips, state.filter, onFilter, Modifier.padding(vertical = 4.dp)) }
            if (state.botChips.isNotEmpty()) item(key = "bots") { FilterChips(state.botChips, state.bot ?: "", onBot, Modifier.padding(vertical = 4.dp)) }
            state.empty?.let { e ->
                item(key = "empty") { Text(e, style = ArgusType.body, color = c.mute, modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp)) }
            }
            items(state.rows, key = { it.id }) { r -> JobRow(r, { onOpen(r.id) }, { onDone(r.id) }, avatar(r.botId)) }
        }
        // the FAB (A-team "+"): 56 dp ink disc, above the composer
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 22.dp).size(56.dp).clip(CircleShape).background(c.ink)
            .clickable(role = Role.Button, onClick = onNew).semantics { contentDescription = tr("New job") }, contentAlignment = Alignment.Center) {
            Icon(Lucide.icon("plus", 24), null, Modifier.size(24.dp), tint = c.onInk)
        }
    }
}

/** 04 §2.8 `TaskRow`: 4 dp priority tick · 21 dp check (7 dp radius) · title + line · due (red when overdue, tabular). */
@Composable
fun JobRow(r: JobRowModel, onOpen: () -> Unit, onDone: () -> Unit, avatar: (@Composable () -> Unit)?) {
    val c = Argus.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).semantics(mergeDescendants = true) { contentDescription = r.a11y }
            .heightIn(min = 56.dp).padding(end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(34.dp).background(if (r.high) c.danger else androidx.compose.ui.graphics.Color.Transparent))
        Spacer(Modifier.width(10.dp))
        val box = RoundedCornerShape(7.dp)
        Box(Modifier.size(48.dp).clickable(enabled = !r.done, role = Role.Checkbox, onClick = onDone), contentAlignment = Alignment.Center) {
            Box(Modifier.size(21.dp).clip(box).then(if (r.done) Modifier.background(c.ink) else Modifier.border(1.5.dp, c.line2, box)),
                contentAlignment = Alignment.Center) {
                if (r.done) Icon(Lucide.icon("check", 14), null, Modifier.size(14.dp), tint = c.onInk)
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(r.title, style = ArgusType.rowTitle, color = if (r.done) c.mute else c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                avatar?.invoke()
                Text(r.line, style = ArgusType.preview, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        r.due?.let { Text(it, style = ArgusType.meta, color = if (r.overdue) c.dangerText else c.meta, maxLines = 1, modifier = Modifier.padding(start = 8.dp)) }
    }
}

/** The job sheet: status, who and due, brief, result, the log with times, then Retry / Done / Cancel job. */
@Composable
fun JobSheet(s: JobSheetModel.Sheet, onDismiss: () -> Unit, onRetry: () -> Unit, onDone: () -> Unit, onCancel: () -> Unit) {
    val c = Argus.colors
    ArgusSheet(onDismiss = onDismiss, title = s.title, primary = s.retry, onPrimary = onRetry, cancel = tr("Close")) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text(listOfNotNull(s.who, s.due).joinToString(" \u00b7 "), style = ArgusType.meta, color = if (s.overdue) c.dangerText else c.meta)
            Text(s.status, style = ArgusType.bodyStrong, color = c.ink, modifier = Modifier.padding(top = 4.dp))
            s.brief?.let { Text(it, style = ArgusType.body, color = c.ink, modifier = Modifier.padding(top = 10.dp)) }
            s.result?.let {
                Text(tr("RESULT"), style = ArgusType.section, color = c.meta, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                Text(it, style = ArgusType.body, color = c.ink)
            }
            Text(tr("LOG"), style = ArgusType.section, color = c.meta, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            if (s.log.isEmpty()) Text(tr("Nothing yet."), style = ArgusType.body, color = c.mute)
            s.log.forEach { l ->
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Lucide.icon(l.icon, 16), null, Modifier.size(16.dp).padding(top = 1.dp), tint = if (l.bad) c.dangerText else c.mute)
                    Text(l.label, style = ArgusType.preview, color = if (l.bad) c.dangerText else c.ink, modifier = Modifier.weight(1f))
                    Text(l.time, style = ArgusType.meta, color = c.meta)
                }
            }
            if (s.canDone || s.canCancel) Row(Modifier.padding(top = 14.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.canDone) ArgusButton(tr("Done"), onDone, size = ButtonSize.S, icon = "check")
                if (s.canCancel) ArgusButton(tr("Cancel job"), onCancel, size = ButtonSize.S, kind = ButtonKind.DANGER)
            }
        }
    }
}

/** The FAB's New job sheet: title, bot, due words (read back as you type), priority. */
@Composable
fun NewJobSheet(roster: List<Bot>, today: LocalDate, initialBot: String?, onDismiss: () -> Unit, onAdd: (NewJob.Draft) -> Unit) {
    val c = Argus.colors
    var d by remember { mutableStateOf(NewJob.Draft(botId = initialBot)) }
    val problems = NewJob.problems(d, today)
    ArgusSheet(onDismiss = onDismiss, title = tr("New job"), primary = tr("Add"), primaryEnabled = problems.isEmpty(), onPrimary = { onAdd(d) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ArgusField(d.title, { d = d.copy(title = it.take(NewJob.TITLE_MAX + 20)) }, "Title", Modifier.padding(horizontal = 8.dp), placeholder = tr("What needs doing"))
            Text(tr("WHO"), style = ArgusType.section, color = c.meta, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
            FilterChips(NewJob.botChips(roster), d.botId ?: "", { d = d.copy(botId = it.ifBlank { null }) })
            ArgusField(d.due, { d = d.copy(due = it) }, "Due", Modifier.padding(horizontal = 8.dp), placeholder = tr("Due: Fri, tomorrow 5pm, 5 Jan (optional)"))
            NewJob.dueHint(d, today)?.let { Text(it, style = ArgusType.meta, color = c.meta, modifier = Modifier.padding(start = 12.dp)) }
            Segmented(NewJob.PRIORITIES.map { ChipModel(it, it.replaceFirstChar { ch -> ch.uppercase() }) }, d.priority, { d = d.copy(priority = it) },
                Modifier.padding(horizontal = 8.dp))
            if (d.title.isNotBlank() || d.due.isNotBlank()) problems.forEach { Text(it, style = ArgusType.preview, color = c.dangerText, modifier = Modifier.padding(start = 12.dp)) }
        }
    }
}

/**
 * The Jobs page with its data: reads every job and the roster off the main thread, again every few seconds while shown,
 * on every [BotThreads.changes] and after each action. [filter] / [onFilter] are hoisted so Team's Unassigned and All jobs
 * can open Desk on a filter. Actions go through [JobsActions] (the jobs runtime's queue when it is up).
 */
@Composable
fun JobsPageScreen(
    sql: Sql,
    store: TeamStore,
    threads: BotThreads?,
    actions: JobsActions,
    filter: String?,
    onFilter: (String) -> Unit,
    onNeedYou: (Int) -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault(),
    clock: () -> Long = { System.currentTimeMillis() },
) {
    val jobs = remember(sql) { JobStore(sql) }
    var tick by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var bot by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf<Triple<List<com.blaze.assistant.team.Job>, Map<String, Bot>, List<Bot>>?>(null) }
    var sheet by remember { mutableStateOf<JobSheetModel.Sheet?>(null) }
    var newOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
    LaunchedEffect(tick) {
        data = withContext(Dispatchers.IO) { val all = store.all(); Triple(jobs.all(), all.associateBy { it.id }, store.roster()) }
        // keep an open sheet current (a run ending, a retry)
        sheet?.let { s -> sheet = withContext(Dispatchers.IO) { jobs.get(s.id)?.let { JobSheetModel.of(it, jobs.steps(it.id), data!!.second, today(), zone) } } }
    }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick++ } }
    LaunchedEffect(threads) { threads?.changes?.collect { tick++ } }
    val (all, bots, roster) = data ?: return
    val state = JobsPageModel.state(all, bots, filter, query, bot, today())
    LaunchedEffect(state.needsYou) { onNeedYou(state.needsYou) }
    fun act(f: () -> Unit) { scope.launch { withContext(Dispatchers.IO) { runCatching(f) }; tick++ } }
    JobsPage(
        state, query, { query = it }, onFilter, { bot = it },
        onOpen = { id -> scope.launch { sheet = withContext(Dispatchers.IO) { jobs.get(id)?.let { JobSheetModel.of(it, jobs.steps(id), bots, today(), zone) } } } },
        onDone = { id -> act { actions.done(id) } },
        onNew = { newOpen = true },
        avatar = { id ->
            val b = id?.let { bots[it] }
            when {
                id == BotRules.MANAGER_ID -> ({ ManagerOrb(OrbSize.XS.dp, contentDescription = null) })
                b != null -> ({ BotOrb(b.avatar.face?.let { OrbFace.of(it) } ?: OrbFace.forName(b.name),
                    ArgusTokens.botColor(b.avatar.color) ?: ArgusTokens.BOT_COLORS.getValue("silver"), OrbSize.XS.dp) })
                else -> null
            }
        },
    )
    sheet?.let { s ->
        JobSheet(s, onDismiss = { sheet = null },
            onRetry = { act { actions.retry(s.id) } },
            onDone = { act { actions.done(s.id) } },
            onCancel = { act { actions.cancel(s.id) } })
    }
    if (newOpen) NewJobSheet(roster, today(), initialBot = if (state.filter == JobsFilter.BY_BOT) state.bot else null,
        onDismiss = { newOpen = false },
        onAdd = { d -> newOpen = false; act { actions.add(d, today()) } })
}
