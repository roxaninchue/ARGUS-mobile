package com.blaze.assistant.ui.team.thread

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.icons.Lucide
import com.blaze.assistant.ui.team.thread.ChatToolRows.Flow
import com.blaze.assistant.ui.team.thread.ChatToolRows.Run
import com.blaze.assistant.ui.team.thread.ChatToolRows.Seg
import com.blaze.assistant.ui.team.thread.ThreadModel.State
import com.blaze.assistant.ui.team.thread.ThreadModel.Step

/**
 * 0.53.3 (owner, 14 Nov 2026, with Claude / Gemini screenshots: "group similar tool calls", "tools and answers coming
 * together, final reply at the end", "remove the pills"). A turn reads top to bottom as it happened, with no pill and
 * no card per call:
 *
 *   I'll start by checking your Gmail.            ← the model's own words (Seg.Say)
 *   ⌕ Searched the web · 4                    ›   ← consecutive calls of one tool, ONE quiet row (Seg.Tools)
 *   Nothing in your inbox, so I'll look up…       ← more words
 *   ◌ Reading a page                              ← the running row shimmers
 *   [final reply, drawn by the chat as before]
 *
 * A row opens to its calls, one line each (the search words, the page, the command) with a state dot; a call opens to
 * its output. A failed call says so in the row ("4 · 2 failed"), never a red card.
 */
@Composable
fun TurnFlowView(flow: Flow, modifier: Modifier = Modifier, showTools: Boolean = true) {
    // 0.53.4: one fold over all of a turn's rows (open while it runs, folded once it's done); the model's words stay out.
    val fold = showTools && ChatToolRows.folds(flow)
    var open by rememberSaveable(flow.key, flow.live) { mutableStateOf(flow.live) }
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (fold) FoldRow(ChatToolRows.foldTitle(flow), open, running = flow.live && flow.runs.any { it.running }) { open = !open }
        for (s in flow.segs) when (s) {
            is Seg.Say -> SayText(s.text)
            is Seg.Tools -> if (showTools) {
                if (!fold) RunRow(s.run, live = flow.live)
                else AnimatedVisibility(open) { RunRow(s.run, live = flow.live, modifier = Modifier.padding(start = 12.dp)) }
            }
        }
    }
}

/** The turn's fold: layers icon · "Used 3 tools · 6 steps" · chevron. */
@Composable
private fun FoldRow(title: String, open: Boolean, running: Boolean, onToggle: () -> Unit) {
    val c = Argus.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { onToggle() }
        .semantics(mergeDescendants = true) { contentDescription = title; stateDescription = if (open) "Expanded" else "Collapsed" },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (running) CircularProgressIndicator(Modifier.size(13.dp), color = c.mute, strokeWidth = 1.5.dp)
            else Icon(Lucide.icon("layers", 16), null, Modifier.size(16.dp), tint = c.mute)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = ArgusType.sub, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Lucide.icon(if (open) "chevron-down" else "chevron-right", 14), null, Modifier.padding(start = 6.dp).size(14.dp), tint = c.dim)
    }
}

@Composable
private fun SayText(text: String) {
    val c = Argus.colors
    Text(text, style = ArgusType.body, color = c.ink, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp))
}

/** One row for a run of the same tool: icon · title · count · chevron; open → each call. */
@Composable
fun RunRow(run: Run, live: Boolean, modifier: Modifier = Modifier) {
    val c = Argus.colors
    var open by rememberSaveable(run.key) { mutableStateOf(false) }
    val running = live && run.running
    val pulse = if (running) {
        val t = rememberInfiniteTransition(label = "run")
        t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a").value
    } else 1f
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Button) { open = !open }
            .semantics(mergeDescendants = true) {
                contentDescription = run.title + (if (run.count.isNotEmpty()) ", " + run.count else "")
                stateDescription = if (open) "Expanded" else "Collapsed"
                if (running) liveRegion = LiveRegionMode.Polite
            }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (running) CircularProgressIndicator(Modifier.size(13.dp), color = c.mute, strokeWidth = 1.5.dp)
                else Icon(Lucide.icon(run.first.icon, 16), null, Modifier.size(16.dp), tint = if (run.allFailed) c.dangerText else c.mute)
            }
            Spacer(Modifier.width(10.dp))
            Text(run.title, style = ArgusType.sub, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).alpha(pulse))
            if (run.count.isNotEmpty()) Text("  \u00b7 " + run.count, style = ArgusType.meta, color = if (run.failed > 0) c.dangerText else c.dim, maxLines = 1)
            Icon(Lucide.icon(if (open) "chevron-down" else "chevron-right", 14), null, Modifier.padding(start = 6.dp).size(14.dp), tint = c.dim)
        }
        AnimatedVisibility(open) {
            val rail = c.line2
            Column(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp)
                .drawBehind { drawLine(rail, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
                .padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (s in run.steps) CallLine(s)
            }
        }
    }
}

/** One call under an open row: dot · what it was about; tap → its output. */
@Composable
private fun CallLine(s: Step) {
    val c = Argus.colors
    var open by rememberSaveable(s.key) { mutableStateOf(false) }
    val body = s.output?.takeIf { it.isNotBlank() }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).then(if (body != null) Modifier.clickable(role = Role.Button) { open = !open } else Modifier)
            .semantics(mergeDescendants = true) { stateDescription = s.status }, verticalAlignment = Alignment.CenterVertically) {
            val dot = when (s.state) { State.RUNNING -> c.mute; State.DONE -> c.success; State.FAILED -> c.danger; State.WAITING -> c.waiting }
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Text(s.command?.let { "$ $it" } ?: s.detail ?: s.title, style = if (s.command != null) ArgusType.mono else ArgusType.meta,
                color = if (s.state == State.FAILED) c.dangerText else c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        AnimatedVisibility(open && body != null) {
            Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(start = 16.dp, bottom = 6.dp)) {
                SelectionContainer {
                    Text(body.orEmpty().take(ThreadModel.OUTPUT_MAX), style = ArgusType.mono, color = c.dim, softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()))
                }
            }
        }
    }
}
