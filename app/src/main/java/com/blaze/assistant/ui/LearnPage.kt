package com.blaze.assistant.ui


import com.blaze.assistant.i18n.tr
import com.blaze.assistant.BrandName
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Flashcards
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.connect.Classroom
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.border
import kotlinx.coroutines.launch
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.StudyNudge
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Learn (student pack, v0.16.0): flashcard decks with spaced repetition (the tutor skill fills them),
 * plus the student switches: Google Classroom and low-data mode.
 *
 * 2026-11-18 redesign (owner: "just two icons, thumbs up and down"; "icons are important and easy to read, not
 * just long words"): decks first with one Study all button; each deck has a ⋯ with icon actions (study, rename,
 * delete). Studying: tap the card (or the eye) to flip, then thumbs down (back in 1 min, and again before this
 * session ends) or thumbs up (spaced out); a progress bar; Undo, Edit and Delete as icons in the header; a done
 * screen with how many you knew, when the next cards come back, and (phase 2) "+ 20 more" past the daily new-card limit.
 * Phase 3: a keyboard icon to type an answer the model checks by meaning (its pick gets a ring), each card's source under
 * the answer, and the Study reminder switch (StudyNudge).
 */
@Composable
internal fun LearnPage(startDeck: String = "", reveal: Boolean = false, now: Long? = null, onBack: () -> Unit = {}, onAsk: (String) -> Unit = {}) {
    val c = LocalBlaze.current.mono
    val all by Flashcards.cards.collectAsState()
    val undo by Flashcards.undoable.collectAsState()
    LaunchedEffect(Unit) { Flashcards.load() }
    var deck by remember { mutableStateOf(startDeck) }
    val clock = now ?: System.currentTimeMillis()

    if (deck.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        Study(deck, reveal, now, undo, onBack = { deck = "" })
        return
    }
    // 23 Nov 2026 redesign (Finn's Learn mockup, "Studying"): the shared view header, a greeting, the streak card, today's
    // review with a progress ring and Continue, the decks as a two-column grid ("Explore topics"), and the ask box.
    val decks = remember(all) { Flashcards.decks(clock) }
    val waiting = remember(all) { Flashcards.waiting(clock) }
    val next = remember(all) { Flashcards.nextDue(Flashcards.ALL, clock) }
    var search by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    ViewHeader("Studying", initial = Settings.userName, backLabel = "Back", onBack = onBack, onSearch = { search = !search; query = "" },
        notices = ViewNotices.cards(waiting, next?.let { Flashcards.until(it - clock) }), noticeDot = waiting > 0, inset = 0.dp)
    Box(Modifier.fillMaxWidth().height(150.dp)) {
        HorizonGlow(Modifier.matchParentSize())
        Column(Modifier.align(Alignment.CenterStart)) {
            Text(tr(LearnHome.greeting(java.util.Calendar.getInstance().apply { timeInMillis = clock }.get(java.util.Calendar.HOUR_OF_DAY))),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 22.sp, color = c.ink))
            Text(tr("Keep learning."), style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = c.ink))
            Box(Modifier.padding(top = 6.dp).width(110.dp).height(3.dp).clip(CircleShape).background(c.accent, CircleShape))
        }
    }
    if (search) PillField(query, { query = it }, tr("Find a deck"), c.ink, c.sub, c.accent, Modifier.padding(vertical = 6.dp))
    val week = remember(all) { LearnHome.week(all.flatMap { listOf(it.first, it.last) }, clock) }
    val streak = remember(all) { LearnHome.streak(all.flatMap { listOf(it.first, it.last) }, clock) }
    // Streak card: flame, "N day streak", the last seven days as dots.
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(CardShape).glass(CardShape, GlassIntensity.Low, shadow = false).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, tint = c.accent, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (streak > 0) tr("{0} day streak", streak.toString()) else tr("Start a streak"),
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.ink))
            Text(tr(LearnHome.streakLine(streak)), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
        }
        week.forEach { (day, on) ->
            Column(Modifier.padding(horizontal = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(if (on) c.accent else c.hair, CircleShape))
                Text(day, style = TextStyle(fontFamily = Type.Sans, fontSize = 10.sp, color = c.sub), modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
    if (decks.isEmpty()) {
        Spacer(Modifier.height(12.dp))
        Rows { EmptyState(tr("No cards yet"), tr("Ask {0} to \u201cmake flashcards from my CIT102 notes\u201d, a PDF or slides. Decks show up here, and cards come back just before you'd forget them.", BrandName.NAME)) }
    } else {
        // Today's review: the ring is today's share done (cards reviewed today against those still waiting).
        val doneToday = remember(all) { all.count { it.last >= Flashcards.dayStart(clock) } }
        val pct = LearnHome.todayPct(doneToday, waiting)
        val top = decks.firstOrNull { it.due + it.today > 0 }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(CardShape).glass(CardShape, GlassIntensity.Low, shadow = false).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                    val w = 8.dp.toPx()
                    drawArc(c.hair, 0f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(w),
                        topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w))
                    drawArc(c.accent, -90f, 360f * pct / 100f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w))
                }
                Text("$pct%", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = c.ink))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Today's review"), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
                Text(top?.name ?: tr("All caught up"), maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.ink))
                Text(if (waiting > 0) tr("{0} cards waiting", waiting.toString()) else next?.let { tr("Next cards in {0}", Flashcards.until(it - clock)) } ?: "",
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
            }
            if (waiting > 0) AccentPill(tr("Continue"), Icons.Rounded.PlayArrow, onClick = { deck = Flashcards.ALL })
        }
        SeeAllHeader(tr("Your decks"), inset = 0.dp)
        if (undo == "delete_deck" || undo == "rename") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            GlassIconButton(Icons.AutoMirrored.Rounded.Undo, tr("Undo"), size = 36.dp) { Flashcards.undo() }
        }
        val shown = decks.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        shown.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { d -> DeckTile(d, c.accent, c.ink, c.sub, Modifier.weight(1f)) { deck = d.name } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    // The ask box (the mockup's "Ask me to teach you anything…"): the words go to the chat's message box to send.
    var ask by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(top = 14.dp).clip(CircleShape).glass(CircleShape, GlassIntensity.Low, shadow = false).padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(ask, { ask = it }, Modifier.weight(1f), singleLine = true,
            textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            decorationBox = { inner -> Box { if (ask.isEmpty()) Text(tr("Ask me to teach you anything\u2026"), style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.sub)); inner() } })
        GlassIconButton(Icons.AutoMirrored.Rounded.Send, tr("Send to chat"), bg = c.accent, tint = Color.White) {
            onAsk(ask.ifBlank { tr("Teach me something new") }); ask = "" }
    }
    Spacer(Modifier.height(22.dp))
    Label(tr("Student tools"), modifier = Modifier.padding(bottom = 4.dp))
    var classroom by remember { mutableStateOf(Classroom.enabled) }
    var low by remember { mutableStateOf(Settings.lowData) }
    ToggleRow("Google Classroom", "Read your courses and assignments, and turn each one into a reminder 24 hours before it's due. Reconnect Google once after switching this on", classroom) {
        classroom = !classroom; Classroom.enabled = classroom }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var nudge by remember { mutableStateOf(StudyNudge.enabled) }
    ToggleRow("Study reminder", "A notification at 6 pm when flashcards are waiting, and none when they aren't", nudge) {
        nudge = !nudge; StudyNudge.enabled = nudge; StudyNudge.schedule(ctx) }
    ToggleRow("Low-data mode", "Text models only (photos are described once), no screenshots, no instant pictures, and each answer shows the MB it used", low) {
        low = !low; Settings.lowData = low }
    Text(tr("Study skills: study-planner, tutor, lecture-notes, essay-coach, group-project and classroom-sync (Settings \u203a Skill library)."),
        modifier = Modifier.padding(top = 10.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.weak))
}

/** An icon with a short line beside it (the caught-up line, the done screen's counts). */
@Composable
private fun IconLine(icon: ImageVector, text: String, tint: Color) {
    val c = LocalBlaze.current.mono
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
    }
}

@Composable
private fun DeckRow(d: Flashcards.Deck, onStudy: () -> Unit) {
    val c = LocalBlaze.current.mono
    var open by remember(d.name) { mutableStateOf(false) }
    var renaming by remember(d.name) { mutableStateOf(false) }
    var name by remember(d.name) { mutableStateOf(d.name) }
    Column(Modifier.fillMaxWidth().hairlineBottom(c.hair)) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = tr("Study")) { onStudy() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Style, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
                Text(tr("{0} cards", d.total.toString()), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
            if (d.due > 0) StatusChip("${d.due} due", c.accent, filled = true)
            if (d.today > 0) { Spacer(Modifier.width(6.dp)); StatusChip("${d.today} new", c.accent, filled = false) }
            if (d.due + d.today == 0) Icon(Icons.Rounded.CheckCircle, contentDescription = tr("Done for now"), tint = c.weak, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.Rounded.MoreHoriz, tr("Deck options"), size = 36.dp) { open = !open; renaming = false }
        }
        if (open) {
            if (renaming) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BlazeTextField(name, { name = it }, tr("Deck name"), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Rounded.Check, tr("Save the name"), bg = c.accent, tint = Color.White) {
                    Flashcards.renameDeck(d.name, name); renaming = false; open = false }
                Spacer(Modifier.width(6.dp))
                GlassIconButton(Icons.Rounded.Close, tr("Cancel")) { renaming = false; name = d.name }
            } else Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, start = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassIconButton(Icons.Rounded.PlayArrow, tr("Study this deck"), bg = c.accent, tint = Color.White) { onStudy() }
                GlassIconButton(Icons.Rounded.Edit, tr("Rename deck")) { renaming = true }
                GlassIconButton(Icons.Rounded.Delete, tr("Delete deck"), tint = FORGOT_RED) { Flashcards.deleteDeck(d.name); open = false }
            }
        }
    }
}

/** A deck in the "Your decks" grid: a lettered square, the name, its cards, and a ⋯ with study, rename and delete. */
@Composable
private fun DeckTile(d: Flashcards.Deck, accent: Color, ink: Color, sub: Color, modifier: Modifier, onStudy: () -> Unit) {
    var menu by remember(d.name) { mutableStateOf(false) }
    var renaming by remember(d.name) { mutableStateOf(false) }
    var name by remember(d.name) { mutableStateOf(d.name) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().clip(CardShape).glass(CardShape, GlassIntensity.Low, shadow = false)
            .clickable(onClickLabel = tr("Study")) { onStudy() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(TileShape).background(accent.copy(alpha = 0.16f), TileShape), contentAlignment = Alignment.Center) {
                Text(d.name.trim().take(1).uppercase(), style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = accent))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = ink))
                Text(if (d.due + d.today > 0) tr("{0} due", (d.due + d.today).toString()) else tr("{0} cards", d.total.toString()),
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = if (d.due + d.today > 0) accent else sub))
            }
            Box {
                Icon(Icons.Rounded.MoreHoriz, contentDescription = tr("Deck options"), tint = sub,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable(onClickLabel = tr("Deck options")) { menu = true })
                PopMenu(menu, { menu = false }, listOf(tr("Study this deck") to onStudy, tr("Rename deck") to { renaming = true },
                    tr("Delete deck") to { Flashcards.deleteDeck(d.name) }))
            }
        }
        if (renaming) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PillField(name, { name = it }, tr("Deck name"), ink, sub, accent, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            GlassIconButton(Icons.Rounded.Check, tr("Save the name"), size = 36.dp, bg = accent, tint = Color.White) { Flashcards.renameDeck(d.name, name); renaming = false }
        }
    }
}

/** A one-line text field in a glass pill (find a deck, rename a deck). */
@Composable
private fun PillField(value: String, onChange: (String) -> Unit, hint: String, ink: Color, sub: Color, accent: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicTextField(value, onChange, modifier.fillMaxWidth().clip(CircleShape).glass(CircleShape, GlassIntensity.Low, shadow = false)
        .padding(horizontal = 16.dp, vertical = 12.dp), singleLine = true, textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = ink),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = sub)); inner() } })
}

private val CardShape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
private val TileShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

private val FORGOT_RED = Color(0xFFFF6B6B)

@Composable
private fun Study(deck: String, reveal0: Boolean, now: Long?, undo: String, onBack: () -> Unit) {
    val c = LocalBlaze.current.mono
    val all by Flashcards.cards.collectAsState()
    val clock = now ?: System.currentTimeMillis()
    val forgot = remember(deck) { mutableStateListOf<String>() }
    var seen by remember(deck) { mutableStateOf(0) }
    var knew by remember(deck) { mutableStateOf(0) }
    var last by remember(deck) { mutableStateOf<Pair<String, Int>?>(null) }
    var more by remember(deck) { mutableStateOf(false) }   // Study more: past today's 20 new cards
    val missed = forgot.toList()
    val card = remember(all, deck, missed, more) { Flashcards.next(deck, missed, clock, more) }
    val left = remember(all, deck, missed, more) { (Flashcards.queue(deck, clock, more).map { it.id } + missed).distinct().size }
    val title = if (deck == Flashcards.ALL) tr("All decks") else deck

    fun grade(id: String, g: Int) {
        Flashcards.answer(id, g)
        seen++; if (g == Flashcards.KNEW) knew++
        forgot.remove(id); if (g == Flashcards.FORGOT) forgot.add(id)
        last = id to g
    }
    fun takeBack() {
        val l = last
        if (Flashcards.undo() && undo == "grade" && l != null) {
            seen = (seen - 1).coerceAtLeast(0)
            if (l.second == Flashcards.KNEW) knew = (knew - 1).coerceAtLeast(0) else forgot.remove(l.first)
        }
        last = null
    }

    var editing by remember(card?.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BackButton(onBack, modifier = Modifier.offset(x = (-4).dp), desc = "Back to decks")
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
            if (card != null) Label("${seen + 1} / ${seen + left}", color = c.sub, size = 10.sp, maxLines = 1)
        }
        GlassIconButton(Icons.AutoMirrored.Rounded.Undo, tr("Undo"), enabled = undo.isNotEmpty()) { takeBack() }
        if (card != null) {
            Spacer(Modifier.width(6.dp))
            GlassIconButton(Icons.Rounded.Edit, tr("Edit this card")) { editing = !editing }
            Spacer(Modifier.width(6.dp))
            GlassIconButton(Icons.Rounded.Delete, tr("Delete this card"), tint = FORGOT_RED) { Flashcards.deleteCard(card.id); forgot.remove(card.id) }
        }
    }
    Spacer(Modifier.height(10.dp))
    // Progress through this session.
    val done = if (seen + left == 0) 1f else seen.toFloat() / (seen + left)
    Box(Modifier.fillMaxWidth().height(4.dp).clip(BlazeShapes.pill).background(c.hair, BlazeShapes.pill)) {
        Box(Modifier.fillMaxWidth(done.coerceIn(0f, 1f)).height(4.dp).clip(BlazeShapes.pill).background(c.accent, BlazeShapes.pill))
    }
    Spacer(Modifier.height(14.dp))

    if (card == null) {
        DoneCard(deck, seen, knew, clock, onBack, onMore = { more = true })
        return
    }

    if (editing) {
        var front by remember(card.id) { mutableStateOf(card.front) }
        var back by remember(card.id) { mutableStateOf(card.back) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BlazeTextField(front, { front = it }, tr("Question"), singleLine = false)
            BlazeTextField(back, { back = it }, tr("Answer"), singleLine = false)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                GlassIconButton(Icons.Rounded.Close, tr("Cancel"), size = 52.dp) { editing = false }
                GlassIconButton(Icons.Rounded.Check, tr("Save the card"), size = 52.dp, bg = c.accent, tint = Color.White) {
                    Flashcards.edit(card.id, front, back); editing = false }
            }
        }
        return
    }

    var reveal by remember(card.id) { mutableStateOf(reveal0) }
    // Phase 3: type an answer and the model checks it by meaning; its pick gets a ring, the user still taps.
    val scope = rememberCoroutineScope()
    var typing by remember(card.id) { mutableStateOf(false) }
    var typed by remember(card.id) { mutableStateOf("") }
    var checking by remember(card.id) { mutableStateOf(false) }
    var verdict by remember(card.id) { mutableStateOf<Boolean?>(null) }
    var note by remember(card.id) { mutableStateOf("") }
    fun check() {
        if (typed.isBlank() || checking) return
        checking = true
        scope.launch {
            val r = try { Agent.ask("Flashcard check", Flashcards.gradePrompt(card, typed)) } catch (_: Throwable) { "" }
            verdict = Flashcards.verdict(r); note = if (r.isBlank()) tr("Couldn't check it right now.") else Flashcards.verdictNote(r)
            checking = false; typing = false; reveal = true
        }
    }
    Column(Modifier.fillMaxWidth().heightIn(min = 240.dp).clip(BlazeShapes.medium).hairline(c.ink.copy(alpha = 0.35f))
        .clickable(onClickLabel = if (reveal) tr("Show the question") else tr("Show the answer")) { reveal = !reveal }.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(if (card.isNew) "New" else "Review", color = c.accent, size = 9.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Flip, contentDescription = null, tint = c.weak, modifier = Modifier.size(16.dp))
        }
        Text(card.front, style = TextStyle(fontFamily = Type.Display, fontSize = 20.sp, lineHeight = 27.sp, color = c.ink))
        if (reveal) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
            Text(card.back, style = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, lineHeight = 23.sp, color = c.body))
            if (card.source.isNotEmpty()) Label(card.source, color = c.weak, size = 10.sp, maxLines = 1)
        }
    }
    if (typed.isNotBlank() && (checking || note.isNotEmpty())) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Icon(when (verdict) { true -> Icons.Rounded.ThumbUp; false -> Icons.Rounded.ThumbDown; null -> Icons.Rounded.Keyboard },
                contentDescription = null, tint = when (verdict) { true -> c.accent; false -> FORGOT_RED; null -> c.sub }, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (checking) tr("Checking\u2026") else "\u201c${typed.trim()}\u201d \u00b7 $note",
                style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
        }
    }
    if (typing) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BlazeTextField(typed, { typed = it }, tr("Your answer"), modifier = Modifier.weight(1f), enabled = !checking,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { check() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done))
            Spacer(Modifier.width(8.dp))
            GlassIconButton(Icons.AutoMirrored.Rounded.Send, tr("Check my answer"), size = 48.dp, bg = c.accent, tint = Color.White,
                enabled = typed.isNotBlank() && !checking) { check() }
        }
    }
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
        if (!reveal) {
            if (!typing) GlassIconButton(Icons.Rounded.Keyboard, tr("Type your answer"), size = 56.dp) { typing = true }
            GlassIconButton(Icons.Rounded.Visibility, tr("Show the answer"), size = 72.dp, bg = c.accent, tint = Color.White) { reveal = true }
        } else {
            GradeButton(Icons.Rounded.ThumbDown, tr("Forgot it"), Flashcards.label(card, Flashcards.FORGOT, clock), FORGOT_RED.copy(alpha = 0.20f), FORGOT_RED,
                picked = verdict == false) { grade(card.id, Flashcards.FORGOT) }
            GradeButton(Icons.Rounded.ThumbUp, tr("Knew it"), Flashcards.label(card, Flashcards.KNEW, clock), c.accent, Color.White,
                picked = verdict == true) { grade(card.id, Flashcards.KNEW) }
        }
    }
}

/** A big round grade icon with its next wait under it ("10 min", "6 d"). */
@Composable
private fun GradeButton(icon: ImageVector, desc: String, wait: String, bg: Color, tint: Color, picked: Boolean = false, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // The model's pick after a typed answer gets a ring; the user still decides.
        Box(Modifier.size(82.dp).then(if (picked) Modifier.border(3.dp, if (bg.alpha >= 0.99f) bg else tint, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
            GlassIconButton(icon, desc, size = 72.dp, bg = bg, tint = tint, onClick = onClick)
        }
        Spacer(Modifier.height(6.dp))
        Text(wait, maxLines = 1, style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.sub))
    }
}

/** End of a session: how many you knew, and when the next cards come back. */
@Composable
private fun DoneCard(deck: String, seen: Int, knew: Int, now: Long, onBack: () -> Unit, onMore: () -> Unit) {
    val c = LocalBlaze.current.mono
    val next = remember(deck, seen) { Flashcards.nextDue(deck, now) }
    val held = remember(deck, seen) { Flashcards.heldBack(deck, now) }
    Column(Modifier.fillMaxWidth().hairline(c.hair).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = c.accent, modifier = Modifier.size(56.dp))
        Text(if (seen == 0) tr("All caught up") else tr("Session done"),
            style = TextStyle(fontFamily = Type.Display, fontSize = 22.sp, color = c.ink), textAlign = TextAlign.Center)
        if (seen > 0) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            IconLine(Icons.Rounded.ThumbUp, "$knew", c.accent)
            IconLine(Icons.Rounded.ThumbDown, "${seen - knew}", FORGOT_RED)
        }
        if (next != null) IconLine(Icons.Rounded.Schedule, tr("Next cards in {0}", Flashcards.until(next - now)), c.sub)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack, desc = "Back to decks")
            // Today's 20 new cards are done; more are waiting: one tap studies another 20 (owner 2026-11-18: keep the limit, soft).
            if (held > 0) Row(Modifier.height(48.dp).clip(BlazeShapes.pill).background(c.accent, BlazeShapes.pill)
                .clickable(onClickLabel = tr("Study more")) { onMore() }.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                ButtonText(tr("{0} more", minOf(held, Flashcards.NEW_PER_DAY).toString()), color = Color.White, size = 14.sp)
            }
        }
    }
}

/** The Studying header's pieces (pure; ViewRedesignTest). Days are local days; a day counts when any card was first studied or last reviewed then. */
object LearnHome {
    private const val DAY = 86_400_000L
    fun greeting(hour: Int): String = when (hour) { in 5..11 -> "Good morning,"; in 12..16 -> "Good afternoon,"; else -> "Good evening," }

    private fun day(t: Long, tz: java.util.TimeZone): Long = Flashcards.dayStart(t, tz)

    /** The last seven days, oldest first, as (weekday letter pair, studied that day). */
    fun week(times: List<Long>, now: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): List<Pair<String, Boolean>> {
        val days = times.filter { it > 0 }.map { day(it, tz) }.toSet()
        val today = day(now, tz)
        return (6 downTo 0).map { back ->
            val d = day(today - back * DAY + DAY / 2, tz)
            val cal = java.util.Calendar.getInstance(tz).apply { timeInMillis = d }
            val name = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
            name to (d in days)
        }
    }

    /**
     * Days in a row with study, ending today (or yesterday, so the streak isn't lost before today's session). Cards keep
     * only their first and last study times, so a day whose cards were all studied again later can be missed: the
     * count never overstates.
     */
    fun streak(times: List<Long>, now: Long, tz: java.util.TimeZone = java.util.TimeZone.getDefault()): Int {
        val days = times.filter { it > 0 }.map { day(it, tz) }.toSet()
        var d = day(now, tz)
        if (d !in days) d = day(d - DAY / 2, tz)
        var n = 0
        while (d in days) { n++; d = day(d - DAY / 2, tz) }
        return n
    }

    fun streakLine(streak: Int): String = when { streak >= 7 -> "You're on fire!"; streak > 0 -> "Keep it going today"; else -> "Study a few cards today" }

    /** Today's ring: reviewed today out of reviewed + still waiting, 0..100 (100 when nothing waits and some were done). */
    fun todayPct(done: Int, waiting: Int): Int = when {
        done + waiting == 0 -> 0
        else -> (done * 100 / (done + waiting)).coerceIn(0, 100)
    }
}
