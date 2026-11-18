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
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Learn (student pack, v0.16.0): flashcard decks with spaced repetition (the tutor skill fills them),
 * plus the student switches: Google Classroom and low-data mode.
 *
 * 2026-11-18 redesign (owner: "just two icons, thumbs up and down"; "icons are important and easy to read, not
 * just long words"): decks first with one Study all button; each deck has a ⋯ with icon actions (study, rename,
 * delete). Studying: tap the card (or the eye) to flip, then thumbs down (back in 10 min, and again before this
 * session ends) or thumbs up (spaced out); a progress bar; Undo, Edit and Delete as icons in the header; a done
 * screen with how many you knew and when the next cards come back.
 */
@Composable
internal fun LearnPage(startDeck: String = "", reveal: Boolean = false, now: Long? = null) {
    val c = LocalBlaze.current.mono
    val all by Flashcards.cards.collectAsState()
    val undo by Flashcards.undoable.collectAsState()
    LaunchedEffect(Unit) { Flashcards.load() }
    var deck by remember { mutableStateOf(startDeck) }
    val clock = now ?: System.currentTimeMillis()

    Spacer(Modifier.height(18.dp))
    if (deck.isNotEmpty()) {
        Study(deck, reveal, now, undo, onBack = { deck = "" })
        return
    }
    val decks = remember(all) { Flashcards.decks(clock) }
    if (decks.isEmpty()) {
        Rows { EmptyState(tr("No cards yet"), tr("Ask {0} to \u201cmake flashcards from my CIT102 notes\u201d, a PDF or slides. Decks show up here, and cards come back just before you'd forget them.", BrandName.NAME)) }
    } else {
        val waiting = remember(all) { Flashcards.waiting(clock) }
        if (waiting > 0) {
            Row(Modifier.fillMaxWidth().height(56.dp).clip(BlazeShapes.pill).background(c.accent, BlazeShapes.pill)
                .clickable(onClickLabel = tr("Study all")) { deck = Flashcards.ALL }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                ButtonText(tr("Study all"), color = Color.White, size = 15.sp)
                Spacer(Modifier.weight(1f))
                Text("$waiting", style = TextStyle(fontFamily = Type.Mono, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Color.White))
            }
        } else {
            val next = remember(all) { Flashcards.nextDue(Flashcards.ALL, clock) }
            IconLine(Icons.Rounded.CheckCircle, tr("All caught up") + (next?.let { " \u00b7 " + Flashcards.until(it - clock) } ?: ""), c.accent)
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(tr("Decks"), modifier = Modifier.weight(1f))
            if (undo == "delete_deck" || undo == "rename") GlassIconButton(Icons.AutoMirrored.Rounded.Undo, tr("Undo"), size = 36.dp) { Flashcards.undo() }
        }
        Spacer(Modifier.height(4.dp))
        decks.forEach { d -> DeckRow(d) { deck = d.name } }
    }
    Spacer(Modifier.height(22.dp))
    Label(tr("Student tools"), modifier = Modifier.padding(bottom = 4.dp))
    var classroom by remember { mutableStateOf(Classroom.enabled) }
    var low by remember { mutableStateOf(Settings.lowData) }
    ToggleRow("Google Classroom", "Read your courses and assignments, and turn each one into a reminder 24 hours before it's due. Reconnect Google once after switching this on", classroom) {
        classroom = !classroom; Classroom.enabled = classroom }
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
            if (d.fresh > 0) { Spacer(Modifier.width(6.dp)); StatusChip("${d.fresh} new", c.accent, filled = false) }
            if (d.due + d.fresh == 0) Icon(Icons.Rounded.CheckCircle, contentDescription = tr("Done for now"), tint = c.weak, modifier = Modifier.size(20.dp))
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
    val missed = forgot.toList()
    val card = remember(all, deck, missed) { Flashcards.next(deck, missed, clock) }
    val left = remember(all, deck, missed) { (Flashcards.queue(deck, clock).map { it.id } + missed).distinct().size }
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
        DoneCard(deck, seen, knew, clock, onBack)
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
        }
    }
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
        if (!reveal) {
            GlassIconButton(Icons.Rounded.Visibility, tr("Show the answer"), size = 72.dp, bg = c.accent, tint = Color.White) { reveal = true }
        } else {
            GradeButton(Icons.Rounded.ThumbDown, tr("Forgot it"), Flashcards.label(card, Flashcards.FORGOT, clock), FORGOT_RED.copy(alpha = 0.20f), FORGOT_RED) {
                grade(card.id, Flashcards.FORGOT) }
            GradeButton(Icons.Rounded.ThumbUp, tr("Knew it"), Flashcards.label(card, Flashcards.KNEW, clock), c.accent, Color.White) {
                grade(card.id, Flashcards.KNEW) }
        }
    }
}

/** A big round grade icon with its next wait under it ("10 min", "6 d"). */
@Composable
private fun GradeButton(icon: ImageVector, desc: String, wait: String, bg: Color, tint: Color, onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GlassIconButton(icon, desc, size = 72.dp, bg = bg, tint = tint, onClick = onClick)
        Spacer(Modifier.height(6.dp))
        Text(wait, maxLines = 1, style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.sub))
    }
}

/** End of a session: how many you knew, and when the next cards come back. */
@Composable
private fun DoneCard(deck: String, seen: Int, knew: Int, now: Long, onBack: () -> Unit) {
    val c = LocalBlaze.current.mono
    val next = remember(deck, seen) { Flashcards.nextDue(deck, now) }
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
        BackButton(onBack, desc = "Back to decks")
    }
}
