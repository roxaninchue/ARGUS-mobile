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
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.semantics
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

/**
 * 0.55.7 (owner 2026-11-22, the "grouped cards" concept): a header with a close button, search, filter chips (All, Tools,
 * Vision, Fast), the Auto card, the two most recent picks, then one glass card per maker with its rows healthiest and
 * fastest first. A model that didn't answer lately stays in its card, dimmed, with a red "Failing" pill instead of hiding.
 * Only the picked thing shows a tick and a highlight.
 */
@Composable
fun ModelSheet(list: List<LiveModels.Member>, onDismiss: () -> Unit) {
    val c = Argus.colors
    val choice by ModelPick.current.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ModelList.Filter.ALL) }
    val rows = remember(list, choice) { ModelList.rows(list) }
    val isDown: (ModelList.Row) -> Boolean = { r -> r.keys.any { ModelPick.isDown(it) } }
    val sections = remember(rows, query, filter, choice) { ModelList.sections(rows, filter, query, isDown) }
    val recent = remember(rows, choice) { ModelList.recent(rows, ModelPick.recentKeys(), isDown) }
    val pick: (ModelList.Row) -> Unit = { r -> ModelPick.set(r.best.key, choice.effort); onDismiss() }
    ArgusSheet(onDismiss = onDismiss, title = null, scroll = false) {
        val picked = list.firstOrNull { it.key == choice.key }
        val pickedRow = if (choice.auto) null else ModelList.rowOf(rows, choice.key)
        val pickedDown = !choice.auto && ModelPick.isDown(choice.key)
        val close = LocalSheetClose.current
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Model"), style = ArgusType.sheetTitle, color = c.ink, modifier = Modifier.weight(1f)
                .semantics { heading() })
            ArgusIconButton("x", tr("Close"), { close() })
        }
        if (rows.size > 6) ArgusSearch(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), placeholder = tr("Search models"))
        if (rows.isNotEmpty()) FilterRow(filter) { filter = it }
        // 2026-11-17 / 21 (owner): scrolling the list only scrolls it; a pull or flick down from the top closes the sheet.
        val listScroll = rememberScrollState()
        val pullPx = with(androidx.compose.ui.platform.LocalDensity.current) { ModelSheetScroll.PULL_DP.dp.toPx() }
        val keepSheet = remember(listScroll) { ModelSheetScroll.Hold({ listScroll.value == 0 }, pullPx) { close() } }
        val browsing = query.isBlank() && filter == ModelList.Filter.ALL
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()
            .pointerInput(listScroll) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    keepSheet.down(listScroll.value == 0)
                }
            }
            .nestedScroll(keepSheet)
            .verticalScroll(listScroll)
            .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (browsing) AutoCard(on = choice.auto) { ModelPick.auto(); onDismiss() }
            if (pickedDown && browsing) MenuRow("triangle-alert", picked?.name ?: LiveModels.prettyName(choice.key.substringAfter('/')), { ModelPick.auto(); onDismiss() },
                sub = tr("Didn't answer, so messages go Auto until it's healthy again. Tap to stay on Auto"))
            else if (!choice.auto && picked == null && browsing) MenuRow("triangle-alert", LiveModels.prettyName(choice.key.substringAfter('/')), { ModelPick.auto(); onDismiss() },
                sub = tr("Not healthy right now, so messages go Auto. Tap to switch to Auto"))
            if (browsing && recent.isNotEmpty()) {
                SectionHeader(tr("Recent"), icon = "history", brand = null)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (r in recent) RecentCard(r, Modifier.weight(1f)) { pick(r) }
                    if (recent.size == 1) Box(Modifier.weight(1f))
                }
            }
            if (list.isEmpty()) Text(tr("No healthy models listed yet. Auto still works."), style = ArgusType.meta, color = c.mute,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            else if (sections.isEmpty()) Text(if (query.isBlank()) tr("No {0} models right now.", tr(filter.label).lowercase()) else tr("No model matches \u201c{0}\u201d.", query.trim()),
                style = ArgusType.meta, color = c.mute, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            for (sec in sections) {
                SectionHeader(sec.title, icon = null, brand = sec.brand)
                androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()
                    .argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.ROW.dp), shadow = false)) {
                    sec.items.forEachIndexed { i, item ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(c.line))
                        ModelRow(item, on = item.row == pickedRow) { pick(item.row) }
                    }
                }
            }
            Box(Modifier.size(8.dp))
        }
    }
}

/** All / Tools / Vision / Fast: one at a time, the picked one in ink. */
@Composable
private fun FilterRow(current: ModelList.Filter, onPick: (ModelList.Filter) -> Unit) {
    val c = Argus.colors
    androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ModelList.Filter.entries) { f ->
            val on = f == current
            val shape = RoundedCornerShape(ArgusTokens.Radius.PILL.dp)
            Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Tab) { onPick(f) }
                .clearAndSetSemantics { contentDescription = tr(f.label); role = Role.Tab; selected = on; onClick { onPick(f); true } },
                contentAlignment = Alignment.Center) {
                Row(Modifier.heightIn(min = 34.dp).then(if (on) Modifier.clip(shape).background(c.ink) else Modifier.argusGlass(GlassLevel.RAISED, shape, shadow = false))
                    .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (f.icon.isNotEmpty()) Icon(Lucide.icon(f.icon, 14), null, Modifier.size(14.dp), tint = if (on) c.onInk else c.mute)
                    Text(tr(f.label), style = ArgusType.chip, color = if (on) c.onInk else c.ink, maxLines = 1)
                }
            }
        }
    }
}

/** Auto as its own card at the top: outlined and ticked only when it is the pick. */
@Composable
private fun AutoCard(on: Boolean, onClick: () -> Unit) {
    val c = Argus.colors
    val shape = RoundedCornerShape(ArgusTokens.Radius.ROW.dp)
    Row(Modifier.fillMaxWidth().clip(shape).argusGlass(if (on) GlassLevel.RAISED else GlassLevel.SURFACE, shape, shadow = false)
        .then(if (on) Modifier.border(1.dp, c.brandCobalt, shape) else Modifier)
        .clickable(role = Role.Button, onClick = onClick)
        .clearAndSetSemantics { contentDescription = tr("Auto. The gateway picks the fastest healthy model") + (if (on) ", selected" else ""); role = Role.Button; onClick { onClick(); true } }
        .heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Lucide.icon("sparkles", 22), null, Modifier.size(24.dp), tint = if (on) c.brandCobalt else c.ink)
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(tr("Auto"), style = ArgusType.field, color = c.ink, maxLines = 1)
            Text(tr("Picks the fastest healthy model"), style = ArgusType.meta, color = c.mute, maxLines = 2)
        }
        Tick(on)
    }
}

/** A small caps-free label above a card: the maker's logo (or an icon) and its name. */
@Composable
private fun SectionHeader(title: String, icon: String?, brand: String?) {
    val c = Argus.colors
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 2.dp)
        .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val logo = Brands.icon(brand, c.ink)
        if (logo != null) androidx.compose.foundation.Image(logo, null, Modifier.size(18.dp))
        else if (icon != null) Icon(Lucide.icon(icon, 16), null, Modifier.size(16.dp), tint = c.mute)
        Text(title, style = ArgusType.bodyStrong, color = c.ink, maxLines = 1)
    }
}

/** A recent pick: logo, name (size faint) and its speed. */
@Composable
private fun RecentCard(r: ModelList.Row, modifier: Modifier, onClick: () -> Unit) {
    val c = Argus.colors
    val shape = RoundedCornerShape(ArgusTokens.Radius.ROW.dp)
    val item = ModelList.Item(r, false)
    Row(modifier.clip(shape).argusGlass(GlassLevel.SURFACE, shape, shadow = false).clickable(role = Role.Button, onClick = onClick)
        .clearAndSetSemantics { contentDescription = r.name + ", " + tr("recent"); role = Role.Button; onClick { onClick(); true } }
        .heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MakerDisc(r, 32)
        Text(nameText(r.name, c.mute), style = ArgusType.meta.copy(color = c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Status(item, compact = true)
    }
}

/** One model: maker logo, the full name (size fainter, wraps to two lines rather than cut), tool / vision icons, status, tick. */
@Composable
private fun ModelRow(item: ModelList.Item, on: Boolean, onClick: () -> Unit) {
    val c = Argus.colors
    val r = item.row
    val detail = ModelList.detail(r)
    val status = if (item.down) tr("failing") else ModelList.speed(r.best.ms).ifEmpty { tr("not measured yet") }
    val shape = RoundedCornerShape(ArgusTokens.Radius.ROW.dp)
    Row(Modifier.fillMaxWidth().clip(shape).then(if (on) Modifier.background(c.brandCobalt.copy(alpha = 0.16f)).border(1.dp, c.brandCobalt.copy(alpha = 0.7f), shape) else Modifier)
        .heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
        .clearAndSetSemantics { contentDescription = r.name + (if (detail.isEmpty()) "" else ", $detail") + ", $status" + (if (on) ", selected" else ""); role = Role.Button; onClick { onClick(); true } }
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val a = if (item.down) 0.5f else 1f
        Box(Modifier.alpha(a)) { MakerDisc(r, 40) }
        Text(nameText(r.name, c.mute), style = ArgusType.field.copy(color = c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(a))
        Row(Modifier.alpha(a), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (r.tools) Icon(Lucide.icon("wrench", 16), null, Modifier.size(16.dp), tint = c.mute)
            if (r.vision) Icon(Lucide.icon("eye", 16), null, Modifier.size(16.dp), tint = c.mute)
        }
        Status(item, compact = false)
        if (on) Tick(true)
    }
}

/** The maker's logo in a glass disc; a wrench or a bubble when the maker has no logo. */
@Composable
private fun MakerDisc(r: ModelList.Row, sizeDp: Int) {
    val c = Argus.colors
    Box(Modifier.size(sizeDp.dp).argusGlass(GlassLevel.RAISED, androidx.compose.foundation.shape.CircleShape, shadow = false), contentAlignment = Alignment.Center) {
        val logo = Brands.icon(r.brand, c.ink)
        val inner = (sizeDp * 0.55f).toInt()
        if (logo != null) androidx.compose.foundation.Image(logo, null, Modifier.size(inner.dp))
        else Icon(Lucide.icon(if (r.tools) "wrench" else "message-circle", inner), null, Modifier.size(inner.dp), tint = c.ink)
    }
}

/** A coloured dot and the reply time; a red "Failing" pill when the model didn't answer lately. */
@Composable
private fun Status(item: ModelList.Item, compact: Boolean) {
    val c = Argus.colors
    val dot = when (item.tone) { ModelList.Tone.GOOD -> c.success; ModelList.Tone.SLOW -> c.waiting; else -> c.danger }
    val shape = RoundedCornerShape(ArgusTokens.Radius.PILL.dp)
    val text = if (item.tone == ModelList.Tone.DOWN) tr("Failing") else ModelList.speed(item.row.best.ms)
    Row(Modifier.then(if (item.tone == ModelList.Tone.DOWN) Modifier.clip(shape).background(c.danger.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot))
        if (text.isNotEmpty()) Text(text, style = ArgusType.meta, color = if (item.tone == ModelList.Tone.DOWN) c.dangerText else c.ink, maxLines = 1)
    }
}

@Composable
private fun Tick(on: Boolean) {
    val c = Argus.colors
    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c.brandCobalt), contentAlignment = Alignment.Center) {
            Icon(Lucide.icon("check", 14), null, Modifier.size(14.dp), tint = androidx.compose.ui.graphics.Color.White)
        }
    }
}

/** The name with its size ("120b") drawn in the mute colour. */
private fun nameText(name: String, mute: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(name)
        ModelList.sizeRange(name)?.let { addStyle(androidx.compose.ui.text.SpanStyle(color = mute), it.first, it.last + 1) }
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
