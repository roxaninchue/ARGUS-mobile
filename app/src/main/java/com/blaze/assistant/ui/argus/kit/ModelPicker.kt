package com.blaze.assistant.ui.argus.kit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import com.blaze.assistant.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.agent.LiveModels
import com.blaze.assistant.agent.LiveModelsFeed
import com.blaze.assistant.agent.ModelList
import com.blaze.assistant.agent.ModelPick
import com.blaze.assistant.agent.TextAssist
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Brands
import com.blaze.assistant.ui.argus.icons.Lucide

/**
 * 0.53.0 manual model picker (owner, 13 Nov 2026). A chip over the composer shows the pick ("Auto", "Kimi K3",
 * "… · unavailable"); a tap opens [ModelSheet]: Auto, then every model the gateway lists as healthy for this account
 * (GET /v1/models/live, plan-filtered). Nothing else is offered. Since 18 Nov 2026 the effort has its own chip, centred on
 * the same row ([ComposerTopRow]).
 */
@Composable
fun ModelChip(modifier: Modifier = Modifier, force: List<LiveModels.Member>? = null, flat: Boolean = false) {
    val c = Argus.colors
    val choice by ModelPick.current.collectAsState()
    var open by remember { mutableStateOf(false) }
    // Owner 2026-11-14: re-read the list whenever a fetch lands, and fetch on every return to the app, so the chip never
    // sits on "unavailable" until the picker is opened.
    val tick by LiveModels.shared.changes.collectAsState()
    val list = remember(tick, force) { force ?: LiveModels.shared.list() }
    LaunchedEffect(choice, open) { if (force == null) LiveModelsFeed.touchNow() }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner, force) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && force == null) LiveModelsFeed.touchNow() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val label = ModelPick.label(list, choice)
    // flat: drawn as the composer's top row (owner 2026-11-14: chip and chatbox one surface), so no pill of its own
    Box(modifier.heightIn(min = if (flat) 36.dp else 44.dp).clickable(role = Role.Button) { open = true }
        .clearAndSetSemantics { contentDescription = tr("Model: {0}. Change model", label); role = Role.Button; onClick { open = true; true } },
        contentAlignment = Alignment.CenterStart) {
        Row(Modifier.heightIn(min = 30.dp).then(if (flat) Modifier else Modifier.argusGlass(GlassLevel.RAISED, RoundedCornerShape(ArgusTokens.Radius.PILL.dp), shadow = false))
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val logo = if (choice.auto) null else Brands.icon(ModelList.brand(label, choice.key), c.ink)
            if (logo != null) androidx.compose.foundation.Image(logo, null, Modifier.size(14.dp))
            else Icon(Lucide.icon(if (choice.auto) "sparkles" else "cpu", 14), null, Modifier.size(14.dp), tint = c.mute)
            // weight(fill = false): a long name ellipsizes and the chevron stays when the row is narrow (ComposerTopRow)
            Text(tr(label), style = ArgusType.statusChip, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Lucide.icon("chevron-down", 14), null, Modifier.size(14.dp), tint = c.mute)
        }
    }
    if (open) ModelSheet(list, onDismiss = { open = false })
}

@Composable
fun ModelSheet(list: List<LiveModels.Member>, onDismiss: () -> Unit) {
    val c = Argus.colors
    val choice by ModelPick.current.collectAsState()
    var query by remember { mutableStateOf("") }
    val rows = remember(list, choice) { ModelList.rows(list) { ModelPick.isDown(it) } }
    val shown = remember(rows, query) { ModelList.filter(rows, query) }
    ArgusSheet(onDismiss = onDismiss, title = tr("Model"), scroll = false) {
        val picked = list.firstOrNull { it.key == choice.key }
        val pickedRow = if (choice.auto) null else ModelList.rowOf(rows, choice.key)
        val pickedDown = !choice.auto && ModelPick.isDown(choice.key)
        // 18 Nov 2026 (owner): the effort moved out of this sheet to its own chip on the composer ([ComposerTopRow]).
        if (rows.size > 6) ArgusSearch(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), placeholder = tr("Search models"))
        // 2026-11-17 (owner): scrolling the list only scrolls it. 21 Nov (owner: "it starts to close on its own even if
        // I'm not at the top", "it goes down in bits"): the list never hands a scroll or fling to the sheet any more, so
        // the sheet can't follow the finger in jumps. The title, search bar and handle drag it as before; on the list, a
        // pull or flick down that began with the list at the top closes it with the sheet's own smooth slide.
        val listScroll = rememberScrollState()
        val close = LocalSheetClose.current
        val pullPx = with(androidx.compose.ui.platform.LocalDensity.current) { ModelSheetScroll.PULL_DP.dp.toPx() }
        val keepSheet = remember(listScroll) { ModelSheetScroll.Hold({ listScroll.value == 0 }, pullPx) { close() } }
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()
            .pointerInput(listScroll) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    keepSheet.down(listScroll.value == 0)
                }
            }
            .nestedScroll(keepSheet)
            .verticalScroll(listScroll)) {
            if (query.isBlank()) MenuRow("sparkles", tr("Auto"), { ModelPick.auto(); onDismiss() }, sub = tr("The gateway picks the fastest healthy model"),
                trailing = if (choice.auto) ({ Icon(Lucide.icon("check", 18), null, Modifier.size(18.dp), tint = c.ink) }) else null)
            if (pickedDown && query.isBlank()) MenuRow("triangle-alert", picked?.name ?: LiveModels.prettyName(choice.key.substringAfter('/')), { ModelPick.auto(); onDismiss() },
                sub = tr("Didn't answer, so messages go Auto until it's healthy again. Tap to stay on Auto"))
            else if (!choice.auto && picked == null && query.isBlank()) MenuRow("triangle-alert", LiveModels.prettyName(choice.key.substringAfter('/')), { ModelPick.auto(); onDismiss() },
                sub = tr("Not healthy right now, so messages go Auto. Tap to switch to Auto"))
            if (list.isEmpty()) Text(tr("No healthy models listed yet. Auto still works."), style = ArgusType.meta, color = c.mute,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            else if (shown.isEmpty()) Text(tr("No model matches \u201c{0}\u201d.", query.trim()), style = ArgusType.meta, color = c.mute,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            for (r in shown) ModelRow(r, on = r == pickedRow) {
                val m = r.best
                // the effort carries over; a model that lacks it falls back to its own default (ModelPick.effort)
                ModelPick.set(m.key, choice.effort); onDismiss()
            }
        }
    }
}

/**
 * The model list's hold on the sheet: what the list doesn't use of a drag or fling stays with the list, so the sheet
 * never slides because a scroll ran into the top. Only a drag that began with the list at the top ([startedAtTop])
 * passes on to the sheet, so pulling down from the top still closes it.
 */
internal object ModelSheetScroll {
    /** How far a pull down from the top closes the sheet, and how fast a flick down does. */
    const val PULL_DP = 72
    const val FLICK_PX_S = 1500f

    /** Whether the sheet closes: the drag began with the list at the top, it's still there, and it was pulled or flicked down far enough. */
    fun closes(startedAtTop: Boolean, atTop: Boolean, pulled: Float, velocityY: Float, pullPx: Float): Boolean =
        startedAtTop && atTop && (pulled >= pullPx || velocityY >= FLICK_PX_S)

    /** The list's hold: it keeps every leftover scroll and fling (the sheet never moves under the finger) and calls [close] once. */
    class Hold(private val atTop: () -> Boolean, private val pullPx: Float, private val close: () -> Unit) : NestedScrollConnection {
        private var startedAtTop = true
        private var pulled = 0f
        private var closed = false

        fun down(listAtTop: Boolean) { startedAtTop = listAtTop; pulled = 0f }

        private fun maybeClose(velocityY: Float): Boolean {
            if (closed || !closes(startedAtTop, atTop(), pulled, velocityY, pullPx)) return false
            closed = true; close(); return true
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) pulled = if (available.y > 0f && consumed.y == 0f) pulled + available.y else 0f
            maybeClose(0f)
            return available
        }

        override suspend fun onPreFling(available: Velocity): Velocity = if (maybeClose(available.y)) available else Velocity.Zero
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
    }
}

/** One model: the maker's logo, the name, what it can do, then signal bars and the tick on the far right. No provider name. */
@Composable
private fun ModelRow(r: ModelList.Row, on: Boolean, onClick: () -> Unit) {
    val c = Argus.colors
    val detail = ModelList.detail(r)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
        .clearAndSetSemantics { contentDescription = r.name + (if (detail.isEmpty()) "" else ", $detail") + ", signal ${r.bars} of 4" + (if (on) ", selected" else ""); role = Role.Button; onClick { onClick(); true } }
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).argusGlass(GlassLevel.RAISED, androidx.compose.foundation.shape.CircleShape, shadow = false), contentAlignment = Alignment.Center) {
            val logo = Brands.icon(r.brand, c.ink)
            if (logo != null) androidx.compose.foundation.Image(logo, null, Modifier.size(22.dp))
            else Icon(Lucide.icon(if (r.tools) "wrench" else "message-circle", 20), null, Modifier.size(20.dp), tint = c.ink)
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(r.name, style = ArgusType.field, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = ArgusType.meta, color = c.mute, maxLines = 1)
        }
        SignalBars(r.bars)
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (on) Icon(Lucide.icon("check", 18), null, Modifier.size(18.dp), tint = c.ink)
        }
    }
}

/** Four rising bars like a phone's signal: 4 = fast and healthy; 2 goes amber and 1 red as replies get slow or fail. */
@Composable
fun SignalBars(bars: Int, modifier: Modifier = Modifier) {
    val c = Argus.colors
    val lit = when { bars <= 1 -> c.danger; bars == 2 -> c.waiting; else -> c.ink }
    val off = c.mute.copy(alpha = 0.35f)
    androidx.compose.foundation.Canvas(modifier.size(width = 18.dp, height = 14.dp)) {
        val w = size.width / 4f
        val gap = w * 0.28f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRoundRect(if (i < bars) lit else off, topLeft = androidx.compose.ui.geometry.Offset(i * w, size.height - h),
                size = androidx.compose.ui.geometry.Size(w - gap, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.2f))
        }
    }
}

/** Which picker is open on the composer's top row. */
internal enum class TopPick { NONE, EFFORT, ASSIST }

/**
 * 18 Nov 2026 (owner): the composer's top row. The model chip on the left, the effort chip centred, text assist on the
 * right ([TextAssist]); all three flat, the same height and type, drawn inside the composer's glass. The effort chip is
 * there only when the picked model lists efforts (never on Auto), and the row stays centred either way: the two side cells
 * share the width equally, so the middle chip sits on the composer's centre line and a long model name ellipsizes.
 *
 * Effort and assist are a selection, not a tap-to-cycle: a tap opens a short list that grows out of the chat box above
 * this row, like the + menu ([ComposerPlusMenu]): the same glass expanding upward, but only as wide as its list, under its
 * chip. A pick, a second tap on the chip or Back closes it. [undo] puts the text back as typed after an assist rewrite.
 */
@Composable
fun ComposerTopRow(modifier: Modifier = Modifier, undo: (() -> Unit)? = null, force: List<LiveModels.Member>? = null) {
    val choice by ModelPick.current.collectAsState()
    val tick by LiveModels.shared.changes.collectAsState()
    val list = remember(tick, force) { force ?: LiveModels.shared.list() }
    val efforts = ModelPick.efforts(list, choice)
    val effortNow = ModelPick.effort(list, choice)
    val level by TextAssist.level.collectAsState()
    val busy by TextAssist.busy.collectAsState()
    var open by remember { mutableStateOf(TopPick.NONE) }
    val shown = if (open == TopPick.EFFORT && efforts.isEmpty()) TopPick.NONE else open
    // the list keeps drawing while it collapses
    var last by remember { mutableStateOf(TopPick.EFFORT) }
    if (shown != TopPick.NONE) last = shown
    if (shown != TopPick.NONE) androidx.activity.compose.BackHandler { open = TopPick.NONE }
    fun toggle(p: TopPick) { open = if (shown == p) TopPick.NONE else p }
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth().padding(end = 8.dp)) {
        androidx.compose.animation.AnimatedVisibility(shown != TopPick.NONE,
            enter = com.blaze.assistant.ui.MotionTokens.expand(), exit = com.blaze.assistant.ui.MotionTokens.collapse()) {
            Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp), contentAlignment = if (last == TopPick.EFFORT) Alignment.TopCenter else Alignment.TopEnd) {
                if (last == TopPick.EFFORT) PickList(tr("Effort"), efforts.map { PickOption(it, it.replaceFirstChar { ch -> ch.uppercaseChar() }) }, effortNow) {
                    ModelPick.set(choice.key, it); open = TopPick.NONE
                }
                else PickList(tr("Text assist"), TextAssist.Level.entries.map { PickOption(it.id, it.label, it.sub) }, level.id) {
                    TextAssist.set(TextAssist.Level.of(it)); open = TopPick.NONE
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { ModelChip(force = force, flat = true) }
            if (efforts.isNotEmpty() && effortNow != null) {
                val e = effortNow.replaceFirstChar { it.uppercaseChar() }
                TopChip("brain", e, tr("Effort: {0}. Change effort", tr(e)), shown == TopPick.EFFORT) { toggle(TopPick.EFFORT) }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (undo != null) TopChip("rotate-ccw", tr("Undo"), tr("Undo text assist"), false, chevron = false, onClick = undo)
                TopChip("pencil", if (busy) tr("Improving\u2026") else tr(level.label), tr("Text assist: {0}. Change text assist", tr(level.label)),
                    shown == TopPick.ASSIST) { toggle(TopPick.ASSIST) }
            }
        }
    }
}

/** A flat chip on the composer's top row, built like [ModelChip] (flat): 36 dp touch height, 14 dp icon, statusChip type. */
@Composable
private fun TopChip(icon: String, label: String, description: String, open: Boolean, chevron: Boolean = true, onClick: () -> Unit) {
    val c = Argus.colors
    Box(Modifier.heightIn(min = 36.dp).clickable(role = Role.Button, onClick = onClick)
        .clearAndSetSemantics { contentDescription = description; role = Role.Button; onClick { onClick(); true } },
        contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = 30.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Lucide.icon(icon, 14), null, Modifier.size(14.dp), tint = c.mute)
            Text(label, style = ArgusType.statusChip, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (chevron) Icon(Lucide.icon(if (open) "chevron-up" else "chevron-down", 14), null, Modifier.size(14.dp), tint = c.mute)
        }
    }
}

internal data class PickOption(val id: String, val label: String, val sub: String = "")

/** The short list a top-row chip opens: a title, then one row per option with a tick on the current one; content-width. */
@Composable
private fun PickList(title: String, options: List<PickOption>, current: String?, onPick: (String) -> Unit) {
    val c = Argus.colors
    androidx.compose.foundation.layout.Column(Modifier.widthIn(min = 160.dp, max = 280.dp)
        .clip(RoundedCornerShape(ArgusTokens.Radius.ROW.dp)).background(c.soft).padding(vertical = 4.dp)) {
        Text(title, style = ArgusType.meta, color = c.mute, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        for (o in options) {
            val on = o.id == current
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { onPick(o.id) }
                .clearAndSetSemantics { contentDescription = tr(o.label) + (if (o.sub.isEmpty()) "" else ", " + tr(o.sub)) + (if (on) ", selected" else ""); role = Role.Button; onClick { onPick(o.id); true } }
                .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f, fill = false)) {
                    Text(tr(o.label), style = ArgusType.field, color = c.ink, maxLines = 1)
                    if (o.sub.isNotEmpty()) Text(tr(o.sub), style = ArgusType.meta, color = c.mute, maxLines = 2)
                }
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    if (on) Icon(Lucide.icon("check", 18), null, Modifier.size(18.dp), tint = c.ink)
                }
            }
        }
    }
}
