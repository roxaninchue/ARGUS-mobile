package com.argus.assistant.ui.team

import com.argus.assistant.ui.common.cutoutSafe
import com.argus.assistant.ui.common.readableWidth

import com.argus.assistant.i18n.tr
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.argus.assistant.team.JobStore
import com.argus.assistant.team.JobsRuntime
import com.argus.assistant.team.TeamGraph
import com.argus.assistant.ui.argus.Argus
import com.argus.assistant.ui.argus.ArgusType
import com.argus.assistant.ui.argus.kit.EmptyState
import com.argus.assistant.ui.argus.kit.FilterChips
import com.argus.assistant.ui.argus.kit.MenuRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Where Desk sends the user: [open] takes a [DeskModel.Targets] value (schedule, gallery, downloads, terminal, browser, chat). */
class DeskNav(val open: (String) -> Unit = {})

/**
 * P1-TM-07: Desk's segment and the Jobs filter, kept outside the page so the shell can open Desk on a segment (Projects
 * from a /projects route, Jobs on Unassigned or All from Team) and so they survive the pager composing only the page on
 * screen.
 */
object DeskState {
    val segment = MutableStateFlow(DeskModel.DEFAULT)
    val jobsFilter = MutableStateFlow<String?>(null)

    fun show(s: DeskModel.Segment, filter: String? = null) {
        segment.value = s
        if (filter != null) jobsFilter.value = filter
    }
}

/**
 * P1-TM-07 Desk v1 (04 §3.1): title, the segment row (scrolling chips with counts, [DeskModel.chips]) and the page of
 * the segment on. [projects] is the Projects list as the shell draws it (AM's SpaceProjectsScreen, embedded), so its
 * behaviour stays the shell's. [inboxWaiting] = what waits on the user in Chat (the Inbox placeholder's count).
 */
@Composable
fun DeskPage(
    nav: DeskNav,
    inboxWaiting: Int,
    projectsCount: Int,
    projects: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Argus.colors
    val ctx = LocalContext.current
    val seg by DeskState.segment.collectAsState()
    val filter by DeskState.jobsFilter.collectAsState()
    var graph by remember { mutableStateOf<TeamGraph.Graph?>(null) }
    var failed by remember { mutableStateOf(false) }
    var needYou by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        try { graph = withContext(Dispatchers.IO) { TeamGraph.get(ctx.applicationContext) } }
        catch (t: Throwable) { failed = true; com.argus.assistant.device.CrashLog.quiet("DeskPage.open", t) }
    }
    val g = graph
    // the Jobs chip's count (Needs you), read every few seconds whichever segment is on
    LaunchedEffect(g) {
        if (g == null) return@LaunchedEffect
        val store = JobStore(g.sql)
        while (true) {
            needYou = withContext(Dispatchers.IO) { runCatching { JobsFilter.counts(store.all(), LocalDate.now())[JobsFilter.NEEDS_YOU] ?: 0 }.getOrDefault(0) }
            delay(10_000)
        }
    }
    // Landscape overhaul: on a short window (a phone on its side) the title and the segment chips share one row, so the
    // page keeps its height for the jobs; upright it is the title over the chips as before.
    val compact = com.argus.assistant.ui.common.WindowLayout.compactHeader(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.toFloat())
    Column(modifier.fillMaxSize().cutoutSafe()) {
        // P2-SF-03: the Inbox chip, the banner and the Inbox segment all read the one needs-you list
        val needs = rememberNeedsYou()
        val chips = DeskModel.chips(com.argus.assistant.policy.NeedsYou.count(needs), needYou, projectsCount)
        val pick: (String) -> Unit = { k -> DeskModel.Segment.of(k)?.let { DeskState.show(it) } }
        if (compact) androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(tr("Desk"), style = ArgusType.topTitle, color = c.ink, modifier = Modifier.padding(start = 20.dp, end = 4.dp).semantics { heading() })
            FilterChips(chips, seg.key, pick, Modifier.weight(1f))
        } else {
            Text(tr("Desk"), style = ArgusType.display, color = c.ink,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp).semantics { heading() })
            FilterChips(chips, seg.key, pick, Modifier.padding(bottom = 6.dp))
        }
        if (seg != DeskModel.Segment.INBOX) com.argus.assistant.policy.NeedsYou.banner(needs).let { b ->
            com.argus.assistant.ui.argus.kit.NeedYouBanner(b.count, b.bots, { DeskState.show(DeskModel.Segment.INBOX) },
                Modifier.padding(bottom = 6.dp), approvalsOnly = b.approvalsOnly)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (seg) {
                DeskModel.Segment.INBOX -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // what the main Chat itself is asking (a question / OK there) is answered in Chat
                    if (inboxWaiting > 0) com.argus.assistant.ui.argus.kit.ArgusButton(tr("Chat is waiting on you \u00b7 Open"),
                        { nav.open(DeskModel.Targets.CHAT) }, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        size = com.argus.assistant.ui.argus.kit.ButtonSize.S, icon = "message-circle")
                    InboxList(needs, onOpen = { DeskState.show(DeskModel.Segment.JOBS) })
                    Spacer(Modifier.height(24.dp))
                }
                DeskModel.Segment.JOBS -> when {
                    g != null -> {
                        val actions = remember(g) { JobsActions(JobStore(g.sql), { JobsRuntime.queue() }, { JobsRuntime.kick() }) }
                        JobsPageScreen(g.sql, g.store, g.threads, actions, filter, onFilter = { DeskState.jobsFilter.value = it })
                    }
                    failed -> EmptyState(title = tr("Jobs couldn't open"), body = tr("Your jobs are safe. Try again after restarting the app."))
                }
                DeskModel.Segment.PROJECTS -> projects()
                else -> LinkPage(seg, nav)
            }
        }
    }
}

/** Routines, Library and Computers until their own pages: a line on what goes here and the entries that open it now. */
@Composable
private fun LinkPage(seg: DeskModel.Segment, nav: DeskNav) {
    val c = Argus.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).readableWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(DeskModel.intro(seg), style = ArgusType.sub, color = c.mute, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
        for (l in DeskModel.links(seg)) MenuRow(l.icon, l.title, { nav.open(l.target) }, sub = l.sub)
        Spacer(Modifier.height(120.dp))
    }
}
