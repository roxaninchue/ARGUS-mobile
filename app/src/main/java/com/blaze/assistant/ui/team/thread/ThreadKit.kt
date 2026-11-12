package com.blaze.assistant.ui.team.thread

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.agent.StepTool
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.OrbSize
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Lucide
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.ButtonSize
import com.blaze.assistant.ui.argus.kit.KitAvatar
import com.blaze.assistant.ui.argus.kit.KitSpec
import com.blaze.assistant.ui.team.thread.ThreadModel.CardModel
import com.blaze.assistant.ui.team.thread.ThreadModel.State
import com.blaze.assistant.ui.team.thread.ThreadModel.Step
import com.blaze.assistant.ui.team.thread.ThreadModel.ThreadItem
import kotlinx.coroutines.delay

/**
 * P1-SH-03: the thread components (04 §2.8 Thread 14–30) and [ThreadList], which draws [ThreadModel.items]. Stateless:
 * everything a tap does goes through [ThreadActions]; the bot thread screen (with its top bar and composer) puts them
 * together. [AgentText]'s body is a slot so the screen passes AM's Markdown / RichBlocks renderer; the default is
 * plain text.
 */
data class ThreadActions(
    val undo: (callId: String) -> Unit = {},
    val goOn: () -> Unit = {},
    val jobDone: (id: String) -> Unit = {},
    val jobTomorrow: (id: String) -> Unit = {},
    val openJob: (id: String) -> Unit = {},
    val openFile: (path: String) -> Unit = {},
    val shareFile: (path: String) -> Unit = {},
    val saveFile: (path: String) -> Unit = {},
    val openHandoff: (fromId: String, toId: String) -> Unit = { _, _ -> },
)

/** What AgentText draws its words with (AM's Md / RichBlocks on the phone). */
typealias AgentBody = @Composable (text: String, streaming: Boolean) -> Unit

private val plainBody: AgentBody = { t, _ -> Text(t, style = ArgusType.body, color = Argus.colors.ink) }

object ThreadSpec {
    const val SIDE = 16
    const val BUBBLE_MAX = 0.82f
    const val TOOL_HEADER = 40
    const val TOOL_BODY_MAX = 260
    const val SPINNER = 16
    const val WORK_CHIP = 32
}

private fun hex(s: String?): Long? = ArgusTokens.botColor(s)

/** `ThreadIntro`: orb xl, name, role, engine pill (where it thinks, never which model), about text. */
@Composable
fun ThreadIntro(i: ThreadItem.Intro, modifier: Modifier = Modifier) {
    val c = Argus.colors
    Column(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KitAvatar(i.name, i.manager, OrbSize.XL, hex(i.color))
        Text(i.name, style = ArgusType.title2, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(i.role, style = ArgusType.sub, color = c.mute)
        Row(Modifier.argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.PILL.dp), shadow = false)
            .padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(c.success))
            Spacer(Modifier.width(6.dp))
            Text(i.engine, style = ArgusType.statusChip, color = c.ink)
        }
        i.about?.let { Text(it, style = ArgusType.sub, color = c.mute, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp)) }
    }
}

/** `UserBubble`: ink fill, onInk text, max 82 % wide, at the end. */
@Composable
fun UserBubble(text: String, via: String?, modifier: Modifier = Modifier) {
    val c = Argus.colors
    val r = ArgusTokens.Radius.BUBBLE.dp
    Column(modifier.fillMaxWidth().padding(horizontal = ThreadSpec.SIDE.dp, vertical = 4.dp), horizontalAlignment = Alignment.End) {
        via?.let { Text(it, style = ArgusType.meta, color = c.meta, modifier = Modifier.padding(bottom = 2.dp, end = 4.dp)) }
        Box(Modifier.fillMaxWidth(ThreadSpec.BUBBLE_MAX), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.clip(RoundedCornerShape(r, r, ArgusTokens.Radius.BUBBLE_TAIL.dp, r)).background(c.ink).padding(horizontal = 14.dp, vertical = 10.dp)) {
                SelectionContainer { Text(text, style = ArgusType.body, color = c.onInk) }
            }
        }
    }
}

/** `AgentText`: no bubble, full width; [body] draws the words (AM Markdown / RichBlocks on the phone). */
@Composable
fun AgentText(text: String, streaming: Boolean, via: String?, modifier: Modifier = Modifier, body: AgentBody = plainBody) {
    val c = Argus.colors
    Column(modifier.fillMaxWidth().padding(horizontal = ThreadSpec.SIDE.dp, vertical = 6.dp)) {
        via?.let { Text(it, style = ArgusType.meta, color = c.meta, modifier = Modifier.padding(bottom = 2.dp)) }
        body(text, streaming)
    }
}

/** `SystemNote`: centred, dashed 16 dp outline, 13 sp mute (error: danger text). */
@Composable
fun SystemNote(text: String, error: Boolean, modifier: Modifier = Modifier) {
    val c = Argus.colors
    val line = c.line2
    Box(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.drawBehind {
            drawRoundRect(line, cornerRadius = CornerRadius(16.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
        }.padding(horizontal = 14.dp, vertical = 7.dp)) {
            Text(text, style = ArgusType.meta, color = if (error) c.dangerText else c.mute, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StepDot(s: State) {
    val c = Argus.colors
    when (s) {
        State.RUNNING -> CircularProgressIndicator(Modifier.size(10.dp), color = c.mute, strokeWidth = 1.5.dp)
        State.DONE -> Box(Modifier.size(8.dp).clip(CircleShape).background(c.success))
        State.FAILED -> Box(Modifier.size(8.dp).clip(CircleShape).background(c.danger))
        State.WAITING -> Box(Modifier.size(8.dp).clip(CircleShape).background(c.waiting))
    }
}

/** The step list under an open ActivityLine: dot · bold verb · its state when not plain done. */
@Composable
fun StepList(steps: List<Step>, modifier: Modifier = Modifier) {
    val c = Argus.colors
    Column(modifier.padding(start = 36.dp, end = ThreadSpec.SIDE.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in steps) Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = s.status }) {
            Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) { StepDot(s.state) }
            Spacer(Modifier.width(8.dp))
            Text(s.title, style = ArgusType.sub, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            when (s.state) {
                State.FAILED -> Text("  Failed", style = ArgusType.meta, color = c.dangerText)
                State.WAITING -> Text("  Waiting for your OK", style = ArgusType.meta, color = c.waitingText)
                else -> {}
            }
        }
    }
}

/**
 * `ActivityLine` (live): spinner · present-tense line · "· N steps" · elapsed · chevron; open → the [StepList]. A
 * polite live region, so TalkBack reads a new step without taking focus.
 */
@Composable
fun ActivityLine(live: ThreadItem.Live, modifier: Modifier = Modifier, now: () -> Long = { System.currentTimeMillis() }) {
    val c = Argus.colors
    var open by rememberSaveable(live.key) { mutableStateOf(false) }
    var t by remember { mutableLongStateOf(now()) }
    LaunchedEffect(live.startedAt) { while (true) { t = now(); delay(1_000) } }
    val ms = if (live.startedAt > 0) t - live.startedAt else 0L
    val a11y = ThreadModel.liveA11y(live.line, live.steps.size, ms)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = KitSpec.TARGET.dp).clickable(role = Role.Button) { open = !open }
            .padding(horizontal = ThreadSpec.SIDE.dp)
            .clearAndSetSemantics { contentDescription = a11y; liveRegion = LiveRegionMode.Polite; stateDescription = if (open) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(ThreadSpec.SPINNER.dp), color = c.ink, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(live.line, style = ArgusType.sub, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (live.steps.isNotEmpty()) Text(" \u00b7 ${ThreadModel.count(live.steps.size)}", style = ArgusType.mono, color = c.dim, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (live.startedAt > 0) Text(ThreadModel.clock(ms), style = ArgusType.mono, color = c.dim)
            Icon(Lucide.icon(if (open) "chevron-down" else "chevron-right", 16), null, Modifier.padding(start = 6.dp).size(16.dp), tint = c.mute)
        }
        AnimatedVisibility(open) { StepList(live.steps) }
    }
}

/** `WorkChip` (done): "Worked for 2m 10s · 7 steps ›"; open → the turn's steps grouped (AT `.tg`), each a [ToolCard]. */
@Composable
fun WorkChip(w: ThreadItem.Work, actions: ThreadActions, modifier: Modifier = Modifier) {
    val c = Argus.colors
    var open by rememberSaveable(w.key) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = ThreadSpec.SIDE.dp, vertical = 4.dp)) {
        val shape = RoundedCornerShape(ArgusTokens.Radius.PILL.dp)
        val look = if (w.failed) Modifier.clip(shape).drawBehind { drawRoundRect(c.danger, cornerRadius = CornerRadius(size.height / 2), style = Stroke(1.dp.toPx())) }
            else Modifier.argusGlass(GlassLevel.SURFACE, shape, shadow = false)
        Row(Modifier.heightIn(min = KitSpec.TARGET.dp).clickable(role = Role.Button) { if (w.failed && !open) actions.goOn() else open = !open }
            .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed"; contentDescription = w.chip + ". " + w.summary },
            verticalAlignment = Alignment.CenterVertically) {
            Row(look.heightIn(min = ThreadSpec.WORK_CHIP.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(w.chip, style = ArgusType.meta, color = if (w.failed) c.dangerText else c.ink)
                Icon(Lucide.icon(if (open) "chevron-down" else "chevron-right", 14), null, Modifier.padding(start = 4.dp).size(14.dp), tint = c.mute)
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (w.groups.size > 1) Text(w.summary, style = ArgusType.meta, color = c.mute)
                for (g in w.groups) {
                    Text(g.kind.group.uppercase(), style = ArgusType.section, color = c.meta, modifier = Modifier.semantics { heading() })
                    for (s in g.steps) ToolCard(s, onUndo = { actions.undo(s.key) })
                }
                if (w.failed) ArgusButton("Go on", actions.goOn, size = ButtonSize.S)
            }
        }
    }
}

/** `ToolCard`: 40 dp header (icon · title · status · chevron); open → `$ cmd` + output (260 dp max, scrolls); Undo. */
@Composable
fun ToolCard(s: Step, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val c = Argus.colors
    var open by rememberSaveable(s.key) { mutableStateOf(false) }
    val shape = RoundedCornerShape(ArgusTokens.Radius.TOOL.dp)
    val hasBody = s.command != null || !s.output.isNullOrBlank()
    Column(modifier.fillMaxWidth().argusGlass(GlassLevel.SURFACE, shape, shadow = false)) {
        Row(Modifier.fillMaxWidth().heightIn(min = KitSpec.TARGET.dp).then(if (hasBody) Modifier.clickable(role = Role.Button) { open = !open } else Modifier)
            .padding(horizontal = 12.dp).semantics(mergeDescendants = true) { stateDescription = s.status },
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.icon(s.icon, 16), null, Modifier.size(16.dp), tint = c.ink)
            Spacer(Modifier.width(10.dp))
            Text(s.title, style = ArgusType.tool, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(s.status, style = ArgusType.meta, color = when (s.state) { State.FAILED -> c.dangerText; State.WAITING -> c.waitingText; else -> c.mute })
            if (hasBody) Icon(Lucide.icon(if (open) "chevron-down" else "chevron-right", 14), null, Modifier.padding(start = 6.dp).size(14.dp), tint = c.mute)
        }
        AnimatedVisibility(open && hasBody) {
            Column(Modifier.fillMaxWidth().heightIn(max = ThreadSpec.TOOL_BODY_MAX.dp).verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                SelectionContainer {
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        s.command?.let { Text("$ $it", style = ArgusType.mono, color = c.ink, softWrap = false) }
                        s.output?.takeIf { it.isNotBlank() }?.let { Text(it, style = ArgusType.mono, color = c.mute, softWrap = false) }
                    }
                }
            }
        }
        if (s.undoable) ArgusButton("Undo", onUndo, Modifier.padding(start = 8.dp, bottom = 8.dp), size = ButtonSize.S, icon = "rotate-ccw")
    }
}

@Composable
private fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().padding(horizontal = ThreadSpec.SIDE.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.CARD.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** `JobCard`: "NOVA · JOBS — 3 open", up to 3 jobs with checks, "+N more". */
@Composable
fun JobCard(j: CardModel.Jobs, actions: ThreadActions, modifier: Modifier = Modifier) {
    val c = Argus.colors
    CardBox(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
            Text(j.header, style = ArgusType.section, color = c.meta, modifier = Modifier.weight(1f))
            Text(j.count, style = ArgusType.meta, color = c.mute)
        }
        for (job in j.shown) Row(Modifier.fillMaxWidth().heightIn(min = KitSpec.TARGET.dp).clickable(role = Role.Button) { actions.openJob(job.id) }
            .semantics(mergeDescendants = true) { stateDescription = if (job.done) "Done" else "Open" }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(21.dp).clip(RoundedCornerShape(ArgusTokens.Radius.CHECKBOX.dp))
                .then(if (job.done) Modifier.background(c.ink) else Modifier.argusGlass(GlassLevel.RAISED, RoundedCornerShape(ArgusTokens.Radius.CHECKBOX.dp), shadow = false)),
                contentAlignment = Alignment.Center) { if (job.done) Icon(Lucide.icon("check", 14), null, Modifier.size(14.dp), tint = c.onInk) }
            Spacer(Modifier.width(10.dp))
            Text(job.title, style = ArgusType.body, color = if (job.done) c.mute else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (job.done) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
            job.due?.let { Text(it, style = ArgusType.meta, color = c.mute) }
        }
        if (j.more > 0) Text("+${j.more} more", style = ArgusType.meta, color = c.mute)
    }
}

/** `OkCard` ("Needs your ok"): an overdue job, Tomorrow · Done. */
@Composable
fun OkCard(o: CardModel.Ok, actions: ThreadActions, modifier: Modifier = Modifier) {
    val c = Argus.colors
    CardBox(modifier) {
        Text("NEEDS YOUR OK", style = ArgusType.section, color = c.meta, modifier = Modifier.semantics { heading() })
        Text(o.title, style = ArgusType.rowTitle, color = c.ink)
        o.due?.let { Text(it, style = ArgusType.meta, color = c.dangerText) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            ArgusButton("Tomorrow", { actions.jobTomorrow(o.jobId) }, size = ButtonSize.S)
            ArgusButton("Done", { actions.jobDone(o.jobId) }, size = ButtonSize.S, kind = ButtonKind.PRIMARY)
        }
    }
}

/** `FileCard` (basic): icon · name · size or path · Open · Share · Save to Downloads. */
@Composable
fun FileCard(f: CardModel.File, actions: ThreadActions, modifier: Modifier = Modifier) {
    val c = Argus.colors
    CardBox(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.soft), contentAlignment = Alignment.Center) {
                Icon(Lucide.icon(if (f.image) "palette" else "scroll-text", 18), null, Modifier.size(18.dp), tint = c.ink)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, style = ArgusType.rowTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                (f.sizeText ?: f.path)?.let { Text(it, style = ArgusType.meta, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        f.path?.let { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArgusButton("Open", { actions.openFile(p) }, size = ButtonSize.S)
                ArgusButton("Share", { actions.shareFile(p) }, size = ButtonSize.S)
                ArgusButton("Save", { actions.saveFile(p) }, size = ButtonSize.S, icon = "download")
            }
        }
    }
}

/** `HandoffCard`: "Nova → Atlas", what was asked, the answer once there, Open conversation. */
@Composable
fun HandoffCard(h: CardModel.Handoff, actions: ThreadActions, modifier: Modifier = Modifier) {
    val c = Argus.colors
    CardBox(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            KitAvatar(h.fromName, h.fromId == "manager", OrbSize.XS)
            Box(Modifier.width(22.dp).padding(horizontal = 4.dp).heightIn(min = 2.dp).drawBehind {
                drawLine(c.line2, androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2),
                    strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)))
            })
            KitAvatar(h.toName, h.toId == "manager", OrbSize.XS)
            Spacer(Modifier.width(8.dp))
            Text(h.line, style = ArgusType.tool, color = c.ink, modifier = Modifier.weight(1f))
            h.status?.let { Text(it, style = ArgusType.meta, color = c.mute) }
        }
        if (h.text.isNotBlank()) Text(h.text, style = ArgusType.body, color = c.ink)
        h.reply?.let { Text("\u2713 $it", style = ArgusType.sub, color = c.successText) }
        ArgusButton("Open conversation", { actions.openHandoff(h.fromId, h.toId) }, size = ButtonSize.S)
    }
}

/** Any other card (approval, question, connect, draft…) until its own component lands: title and text. */
@Composable
fun PlainThreadCard(o: CardModel.Other, modifier: Modifier = Modifier) {
    val c = Argus.colors
    CardBox(modifier) {
        Text(o.title, style = ArgusType.rowTitle, color = c.ink)
        o.text?.let { Text(it, style = ArgusType.sub, color = c.mute) }
    }
}

/** One item of the thread. */
@Composable
fun ThreadItemView(item: ThreadItem, actions: ThreadActions, body: AgentBody = plainBody) {
    when (item) {
        is ThreadItem.Intro -> ThreadIntro(item)
        is ThreadItem.User -> UserBubble(item.text, item.via)
        is ThreadItem.Agent -> AgentText(item.text, item.streaming, item.via, body = body)
        is ThreadItem.System -> SystemNote(item.text, item.error)
        is ThreadItem.Live -> ActivityLine(item)
        is ThreadItem.Work -> WorkChip(item, actions)
        is ThreadItem.Card -> when (val k = item.card) {
            is CardModel.Jobs -> JobCard(k, actions)
            is CardModel.Ok -> OkCard(k, actions)
            is CardModel.File -> FileCard(k, actions)
            is CardModel.Handoff -> HandoffCard(k, actions)
            is CardModel.Other -> PlainThreadCard(k)
        }
    }
}

/**
 * The thread, oldest first. New items follow only while the list is at its end (AT `atEnd`), so reading back is never
 * yanked down.
 */
@Composable
fun ThreadList(items: List<ThreadItem>, actions: ThreadActions, modifier: Modifier = Modifier, body: AgentBody = plainBody,
               state: LazyListState = rememberLazyListState(), contentPadding: PaddingValues = PaddingValues(bottom = 12.dp)) {
    val atEnd = !state.canScrollForward
    LaunchedEffect(items.size, items.lastOrNull()) { if (atEnd && items.isNotEmpty()) state.scrollToItem(items.lastIndex) }
    LazyColumn(modifier.fillMaxWidth(), state = state, contentPadding = contentPadding) {
        items(items, key = { it.key }, contentType = { it::class.simpleName }) { ThreadItemView(it, actions, body) }
    }
}
