package com.blaze.assistant.ui.team

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.home.HomeTodo
import com.blaze.assistant.home.Widget
import com.blaze.assistant.home.WidgetData
import com.blaze.assistant.home.WidgetLine
import com.blaze.assistant.home.WidgetTypes
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Lucide
import java.time.LocalDate
import java.time.YearMonth

/**
 * Owner 2026-11-14: every Life header folds (the dashboard grows with what ARGUS tracks, so nothing has to be scrolled
 * past). [folded] holds header names, sections as [LifeBoard.SECTION] + name. Kept in Settings by [LifePage].
 */
class LifeFold(val folded: Set<String> = emptySet(), val onToggle: (String) -> Unit = {}) {
    fun isFolded(key: String) = key in folded
}

val LocalLifeFold = compositionLocalOf { LifeFold() }

/** What a board panel can do: tick a to-do, open a line's link, refresh, remove, ask ARGUS to rework it. */
class LifeBoardActions(
    val onVisible: (String) -> Unit = {},
    val onTodo: (Widget, List<HomeTodo.Item>) -> Unit = { _, _ -> },
    val onLine: (Widget, WidgetLine) -> Unit = { _, _ -> },
    val onRefresh: (Widget) -> Unit = {},
    val onRemove: (Widget) -> Unit = {},
)

/** The live board's actions (the Home board store). The page never fetches; a panel asks to be filled when it shows. */
fun liveBoardActions(ctx: android.content.Context) = LifeBoardActions(
    onVisible = { com.blaze.assistant.home.HomeBoard.visible(it) },
    onTodo = { w, list -> com.blaze.assistant.home.HomeBoard.setConfig(w.id, mapOf("items" to HomeTodo.toJson(list))) },
    onLine = { _, l -> com.blaze.assistant.ui.openHomeLink(ctx, l.url) },
    onRefresh = { com.blaze.assistant.home.HomeBoard.refresh(it.id) },
    onRemove = { com.blaze.assistant.home.HomeBoard.remove(it.id) },
)

/** The calendar card's state, fed by [LifePage]: the month on show, the picked day and that day's items. */
class LifeCal(
    val month: YearMonth,
    val selected: LocalDate,
    val today: LocalDate,
    val items: List<com.blaze.assistant.agent.ScheduleDay.Item> = emptyList(),
    val onSelect: (LocalDate) -> Unit = {},
    val onMonth: (Int) -> Unit = {},
)

/** A header that folds: name, an optional action on the right, and a chevron. */
@Composable
fun LifeFoldHead(text: String, key: String, trailing: String? = null, onTrailing: (() -> Unit)? = null, count: Int? = null) {
    val c = Argus.colors
    val fold = LocalLifeFold.current
    val shut = fold.isFolded(key)
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = if (shut) "Show $text" else "Fold $text") { fold.onToggle(key) }
            .heightIn(min = 48.dp).padding(start = 12.dp, end = 8.dp)
            .semantics(mergeDescendants = true) { heading(); contentDescription = text; stateDescription = if (shut) "Folded" else "Shown" },
            verticalAlignment = Alignment.CenterVertically) {
            Text(text.uppercase(), style = ArgusType.section, color = c.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (shut && count != null && count > 0) Text("  $count", style = ArgusType.section, color = c.dim)
            Spacer(Modifier.width(6.dp))
            Icon(Lucide.icon(if (shut) "chevron-right" else "chevron-down", 14), null, Modifier.size(14.dp), tint = c.meta)
        }
        if (trailing != null && onTrailing != null && !shut) Text(trailing, style = ArgusType.sub, color = c.mute,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onTrailing).heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 14.dp))
    }
}

/** Quick access under the day line: connect accounts and add apps, one tap each. */
@Composable
fun LifeQuickAccess(onConnect: () -> Unit, onApps: () -> Unit) {
    val c = Argus.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((icon, label, act) in listOf(Triple("plug", "Connect accounts", onConnect), Triple("blocks", "Apps", onApps))) {
            Row(Modifier.weight(1f).heightIn(min = 48.dp).argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.PILL.dp), shadow = false)
                .clickable(role = Role.Button, onClick = act).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Lucide.icon(icon, 16), null, Modifier.size(16.dp), tint = c.ink)
                Text(label, style = ArgusType.rowTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Icon(Lucide.icon("plus", 14), null, Modifier.size(14.dp), tint = c.mute)
            }
        }
    }
}

private fun glyphOf(type: String) = when (type) {
    WidgetTypes.TODO -> "list-checks"; WidgetTypes.NEWS -> "scroll-text"; WidgetTypes.SPORTS -> "flag"; WidgetTypes.PRICES -> "wallet"
    WidgetTypes.CALENDAR -> "calendar"; WidgetTypes.ROUTINES -> "repeat"; WidgetTypes.DEADLINES -> "calendar-clock"; WidgetTypes.GITHUB -> "github"
    WidgetTypes.CREATORS -> "play"; WidgetTypes.APPS -> "smartphone"; else -> "notebook"
}

/** One board panel: icon + title, the big value, a line of text, then its lines (a to-do ticks on tap). Long-press: options. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LifeBoardPanel(w: Widget, d: WidgetData?, actions: LifeBoardActions, onMenu: () -> Unit) {
    val c = Argus.colors
    LaunchedEffect(w.id) { actions.onVisible(w.id) }
    val shape = RoundedCornerShape(ArgusTokens.Radius.CARD.dp)
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().argusGlass(GlassLevel.SURFACE, shape)
        .combinedClickable(onClickLabel = "Panel options", onClick = onMenu, onLongClick = onMenu).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.icon(glyphOf(w.type), 16), null, Modifier.size(16.dp), tint = c.mute)
            Spacer(Modifier.width(8.dp))
            Text(w.title, style = ArgusType.rowTitle, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            d?.value?.takeIf { it.isNotBlank() }?.let { Text(it, style = ArgusType.sub, color = c.mute, maxLines = 1) }
        }
        if (w.type == WidgetTypes.TODO) {
            val items = HomeTodo.items(w.cfg())
            if (items.isEmpty()) Text("Empty. Ask me to add something.", style = ArgusType.sub, color = c.mute, modifier = Modifier.padding(top = 8.dp))
            items.forEachIndexed { i, it ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Checkbox, onClickLabel = if (it.done) "Untick" else "Tick") {
                        actions.onTodo(w, HomeTodo.toggle(items, i)) }
                    .heightIn(min = 44.dp).semantics(mergeDescendants = true) { stateDescription = if (it.done) "Done" else "Not done" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.icon(if (it.done) "circle-check" else "square", 18), null, Modifier.size(18.dp), tint = if (it.done) c.success else c.mute)
                    Spacer(Modifier.width(10.dp))
                    Text(it.text, style = ArgusType.body.copy(textDecoration = if (it.done) TextDecoration.LineThrough else null),
                        color = if (it.done) c.dim else c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            return@Column
        }
        when {
            d == null -> Text("Filling in\u2026", style = ArgusType.sub, color = c.mute, modifier = Modifier.padding(top = 8.dp))
            d.error.isNotBlank() -> Text(d.error, style = ArgusType.sub, color = c.dangerText, modifier = Modifier.padding(top = 8.dp))
            else -> {
                val text = d.change.ifBlank { d.headline.takeIf { it != w.title }.orEmpty() }
                if (text.isNotBlank()) Text(text, style = ArgusType.sub, color = c.mute, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                for (l in d.lines.take(LINES)) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .then(if (l.url.isNotBlank()) Modifier.clickable(role = Role.Button) { actions.onLine(w, l) } else Modifier)
                    .heightIn(min = 44.dp).padding(vertical = 6.dp)) {
                    Text(l.title, style = ArgusType.body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOf(l.sub, l.note).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
                    if (sub.isNotBlank()) Text(sub, style = ArgusType.meta, color = c.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private const val LINES = 5

/** The month calendar: ‹ month › , weekday initials, the days (today ringed, the picked day filled), then that day's items. */
@Composable
fun LifeCalendarCard(cal: LifeCal, onOpenSchedule: () -> Unit) {
    val c = Argus.colors
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.CARD.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Previous month") { cal.onMonth(-1) }, contentAlignment = Alignment.Center) {
                Icon(Lucide.icon("chevron-left", 18), "Previous month", Modifier.size(18.dp), tint = c.ink)
            }
            Text(LifeCalendar.title(cal.month), style = ArgusType.rowTitle, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).semantics { heading() })
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Next month") { cal.onMonth(1) }, contentAlignment = Alignment.Center) {
                Icon(Lucide.icon("chevron-right", 18), "Next month", Modifier.size(18.dp), tint = c.ink)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            for (h in LifeCalendar.heads()) Text(h, style = ArgusType.meta, color = c.meta, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        }
        for (week in LifeCalendar.weeks(cal.month)) Row(Modifier.fillMaxWidth()) {
            for (day in week) Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                if (day != null) {
                    val picked = day == cal.selected
                    val today = day == cal.today
                    Box(Modifier.size(36.dp).clip(CircleShape)
                        .then(if (picked) Modifier.background(c.ink) else if (today) Modifier.background(c.soft) else Modifier)
                        .clickable(role = Role.Button, onClickLabel = "Show ${LifeCalendar.dayLabel(day, cal.today)}") { cal.onSelect(day) },
                        contentAlignment = Alignment.Center) {
                        Text(day.dayOfMonth.toString(), style = ArgusType.sub, color = if (picked) c.onInk else if (day.isBefore(cal.today)) c.dim else c.ink)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(LifeCalendar.dayLabel(cal.selected, cal.today), style = ArgusType.rowTitle, color = c.ink, modifier = Modifier.weight(1f))
            Text("Schedule \u203a", style = ArgusType.sub, color = c.mute,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onOpenSchedule).heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 12.dp))
        }
        if (cal.items.isEmpty()) Text("Nothing planned. Ask me to add something.", style = ArgusType.sub, color = c.mute, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp))
        else for (i in cal.items) Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val glyph = when (i.kind) { "event" -> "calendar"; "alarm" -> "bell"; else -> "repeat" }
            Icon(Lucide.icon(glyph, 14), null, Modifier.size(14.dp), tint = c.mute)
            Spacer(Modifier.width(8.dp))
            Text(i.label(), style = ArgusType.sub, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
