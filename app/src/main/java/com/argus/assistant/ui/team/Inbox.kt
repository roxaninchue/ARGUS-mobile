package com.blaze.assistant.ui.team

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.i18n.tr
import com.blaze.assistant.policy.CardAnswer
import com.blaze.assistant.policy.Cards
import com.blaze.assistant.policy.NeedsYou
import com.blaze.assistant.policy.PolicyRuntime
import com.blaze.assistant.team.JobRun
import com.blaze.assistant.team.JobStore
import com.blaze.assistant.team.TeamGraph
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.BotOrb
import com.blaze.assistant.ui.argus.OrbFace
import com.blaze.assistant.ui.argus.OrbSize
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.ButtonSize
import com.blaze.assistant.ui.argus.kit.ChipModel
import com.blaze.assistant.ui.argus.kit.FilterChips
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P2-SF-03 Android side of the one needs-you query: maps PolicyRuntime's cards and team.db's jobs onto [NeedsYou] and
 * publishes the list. The bell dot, the NeedYouBanner, the bell's Inbox sheet and Desk → Inbox all collect [items];
 * nothing else counts what waits on the user (NeedsYouSourceTest). Read-only: starting it opens nothing on screen.
 */
object NeedsYouFeed {
    private val _items = MutableStateFlow<List<NeedsYou.Item>>(emptyList())
    val items: StateFlow<List<NeedsYou.Item>> = _items

    /** bot id → (face, colour) for the row orbs. */
    private val _looks = MutableStateFlow<Map<String, Pair<Int?, String>>>(emptyMap())
    val looks: StateFlow<Map<String, Pair<Int?, String>>> = _looks

    @Volatile private var jobs: List<NeedsYou.JobSrc> = emptyList()
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(ctx: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        scope.launch { PolicyRuntime.cards.collect { publish() } }
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { readJobs(app) }.onFailure { com.blaze.assistant.device.CrashLog.quiet("NeedsYouFeed.jobs", it) }
                publish()   // also drops approvals whose 25 minutes ran out
                delay(10_000)
            }
        }
    }

    private fun readJobs(app: Context) {
        val g = TeamGraph.get(app)
        val bots = g.store.all()
        val names = bots.associate { it.id to it.name }
        _looks.value = bots.associate { it.id to (it.avatar.face to it.avatar.color) }
        jobs = JobStore(g.sql).all().mapNotNull { j ->
            val failed = j.run == JobRun.FAILED
            val suggestion = j.source == "suggestion" && !j.confirmed && j.run != JobRun.DONE && j.run != JobRun.CANCELLED && !failed
            if (!failed && !suggestion) null
            else NeedsYou.JobSrc(j.id, j.botId.orEmpty(), names[j.botId].orEmpty(), j.title,
                if (failed) j.result.orEmpty().ifBlank { j.notes } else j.brief, failed, suggestion, j.u)
        }
    }

    fun publish() {
        val cards = PolicyRuntime.cards.value.map { c ->
            NeedsYou.CardSrc(c.id, c.kind.code, c.botId, c.botName, c.cls, c.title, c.detail, c.createdAt, c.expiresAt,
                pending = c.state == Cards.State.PENDING)
        }
        _items.value = NeedsYou.build(cards, jobs, System.currentTimeMillis())
    }

    /**
     * Answers an approval through PolicyRuntime.answer, the same path as tapping the card in the bot's thread (so that
     * thread's card row changes state). SPEND / DELETE ask for the device unlock first ([NeedsYou.needsBiometric]).
     */
    fun decide(ctx: Context, item: NeedsYou.Item, allow: Boolean, always: Boolean = false, done: (Boolean) -> Unit = {}) {
        val draft = PolicyRuntime.center.card(item.id)?.kind == Cards.Kind.DRAFT
        val a: CardAnswer = when {
            draft -> if (allow) CardAnswer.Send else CardAnswer.Discard
            !allow -> CardAnswer.Deny
            always && NeedsYou.alwaysOffered(item) -> CardAnswer.Always
            else -> CardAnswer.Allow
        }
        if (allow && NeedsYou.needsBiometric(item)) {
            com.blaze.assistant.ui.DeviceUnlock.ask(ctx, tr("Allow: {0}", item.title), subtitle = tr("Inbox")) { ok, _ -> done(ok && PolicyRuntime.answer(item.id, a)) }
        } else done(PolicyRuntime.answer(item.id, a))
    }

    /** A question card's option, answered as in the thread. */
    fun reply(item: NeedsYou.Item, text: String): Boolean = PolicyRuntime.answer(item.id, CardAnswer.Reply(text))

    fun notNow(item: NeedsYou.Item): Boolean =
        PolicyRuntime.answer(item.id, if (item.kind == NeedsYou.Kind.CONNECT) CardAnswer.NotNow else CardAnswer.Dismiss)
}

/** Collects the one needs-you list (starting the feed on first use). Every needs-you surface calls this. */
@Composable
fun rememberNeedsYou(): List<NeedsYou.Item> {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { NeedsYouFeed.start(ctx) }
    val items by NeedsYouFeed.items.collectAsState()
    return items
}

/**
 * P2-SF-03: the Inbox list, drawn the same in the bell's sheet and Desk → Inbox: filter chips (All · Approvals ·
 * Questions · Connect · Failed · Suggestions, counts from the one list), urgency-sorted rows (bot orb, title, why, time),
 * Allow / Deny / Always for approvals, long-press to select for bulk (one bot + one kind, no money / delete), swipe right
 * to allow only low-risk ones. [onOpen] opens the owning place (a failed job, a suggestion, a connect card's thread).
 */
@Composable
fun InboxList(items: List<NeedsYou.Item>, onOpen: (NeedsYou.Item) -> Unit, modifier: Modifier = Modifier) {
    val c = Argus.colors
    val ctx = LocalContext.current
    var filter by rememberSaveable { mutableStateOf(NeedsYou.Filter.ALL.name) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val state = InboxModel.prune(items, InboxModel.State(NeedsYou.Filter.valueOf(filter), selected))
    val looks by NeedsYouFeed.looks.collectAsState()
    val now = System.currentTimeMillis()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChips(InboxModel.chips(items, state).map { ChipModel(it.filter.name, tr(it.label), it.count) }, state.filter.name, { k ->
            val s = InboxModel.setFilter(state, NeedsYou.Filter.valueOf(k)); filter = s.filter.name; selected = s.selected
        })
        if (InboxModel.selecting(state)) {
            val bulk = InboxModel.bulkEnabled(items, state)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("{0} selected", state.selected.size.toString()), style = ArgusType.meta, color = c.mute, modifier = Modifier.weight(1f))
                ArgusButton(tr("Allow all"), { InboxModel.selection(items, state).forEach { NeedsYouFeed.decide(ctx, it, true) }; selected = emptySet() },
                    kind = ButtonKind.PRIMARY, size = ButtonSize.S, enabled = bulk)
                ArgusButton(tr("Deny all"), { InboxModel.selection(items, state).forEach { NeedsYouFeed.decide(ctx, it, false) }; selected = emptySet() },
                    size = ButtonSize.S, enabled = bulk)
                ArgusButton(tr("Cancel"), { selected = emptySet() }, kind = ButtonKind.GLASS, size = ButtonSize.S)
            }
            if (!bulk) Text(tr("Bulk works for approvals from one bot, never for money or deleting."), style = ArgusType.meta, color = c.mute,
                modifier = Modifier.padding(horizontal = 12.dp))
        }
        val shown = InboxModel.visible(items, state)
        if (shown.isEmpty()) Text(tr(InboxModel.empty(state.filter)), style = ArgusType.meta, color = c.mute,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp))
        for (i in shown) InboxRow(i, looks[i.botId], now, i.id in state.selected,
            onToggle = { selected = InboxModel.toggle(state, i.id).selected }, onOpen = { onOpen(i) })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxRow(i: NeedsYou.Item, look: Pair<Int?, String>?, now: Long, picked: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val c = Argus.colors
    val ctx = LocalContext.current
    val swipe = NeedsYou.swipeApprove(i)
    val threshold = with(LocalDensity.current) { 120.dp.toPx() }
    var drag by remember(i.id) { mutableFloatStateOf(0f) }
    val shift by animateFloatAsState(drag, label = "inboxSwipe")
    val shape = RoundedCornerShape(ArgusTokens.Radius.ROW.dp)
    val swipeMod = if (swipe) Modifier.pointerInput(i.id) {
        detectHorizontalDragGestures(onDragEnd = { if (drag >= threshold) NeedsYouFeed.decide(ctx, i, true); drag = 0f },
            onDragCancel = { drag = 0f }) { _, dx -> drag = (drag + dx).coerceIn(0f, threshold * 1.4f) }
    } else Modifier
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).graphicsLayer { translationX = shift }.then(swipeMod)
        .clip(shape).background(if (picked) c.glassFill2 else c.glassFill)
        .combinedClickable(onClick = { if (picked) onToggle() else if (i.kind != NeedsYou.Kind.APPROVAL) onOpen() }, onLongClick = onToggle)
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BotOrb(look?.first?.let { OrbFace.of(it) } ?: OrbFace.forName(i.botName), ArgusTokens.botColor(look?.second) ?: 0xFFCBD5E1L, OrbSize.SM.dp)
            Column(Modifier.weight(1f)) {
                Text(i.title, style = ArgusType.bodyStrong, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (i.detail.isNotBlank()) Text(i.detail, style = ArgusType.meta, color = c.mute, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(tr(InboxModel.time(i, now)), style = ArgusType.meta, color = c.mute, maxLines = 1)
        }
        when (i.kind) {
            NeedsYou.Kind.APPROVAL -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArgusButton(tr("Allow"), { NeedsYouFeed.decide(ctx, i, true) }, kind = ButtonKind.PRIMARY, size = ButtonSize.S,
                    icon = if (NeedsYou.needsBiometric(i)) "lock" else null)
                ArgusButton(tr("Deny"), { NeedsYouFeed.decide(ctx, i, false) }, size = ButtonSize.S)
                if (NeedsYou.alwaysOffered(i)) ArgusButton(tr("Always"), { NeedsYouFeed.decide(ctx, i, true, always = true) }, kind = ButtonKind.GLASS, size = ButtonSize.S)
            }
            NeedsYou.Kind.QUESTION -> {
                val opts = PolicyRuntime.center.card(i.id)?.question?.options.orEmpty()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (o in opts.take(3)) ArgusButton(o, { NeedsYouFeed.reply(i, o) }, size = ButtonSize.S)
                    ArgusButton(tr("Open"), onOpen, kind = ButtonKind.GLASS, size = ButtonSize.S)
                }
            }
            NeedsYou.Kind.CONNECT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArgusButton(tr("Open"), onOpen, kind = ButtonKind.PRIMARY, size = ButtonSize.S)
                ArgusButton(tr("Not now"), { NeedsYouFeed.notNow(i) }, size = ButtonSize.S)
            }
            else -> Unit
        }
    }
}
