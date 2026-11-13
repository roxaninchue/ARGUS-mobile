package com.blaze.assistant.ui.team.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.policy.Card
import com.blaze.assistant.policy.CardAnswer
import com.blaze.assistant.policy.Cards
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTheme
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ArgusField
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.ButtonSize
import com.blaze.assistant.ui.team.thread.ThreadModel.CardModel

/**
 * P2-SF-02: the cards (04 §2.8, Bot HQ card kinds) in the A-team look. Stateless: a card shows a [Card] (policy/Cards.kt)
 * and every tap is a [CardAnswer] through [onAnswer]; [ArgusCards] draws the live ones from PolicyRuntime wherever the
 * old confirm card is drawn. [DecisionThreadCard] is the same card as a team thread keeps it (its final state once it
 * has closed).
 */
@Composable
private fun CardFrame(card: Card?, heading: String, botName: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Argus.colors
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth().argusGlass(GlassLevel.RAISED, RoundedCornerShape(ArgusTokens.Radius.CARD.dp)).padding(14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { heading() }) {
                Text(heading, style = ArgusType.section, color = c.meta, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                botName?.let { Text(it, style = ArgusType.meta, color = c.mute, maxLines = 1) }
            }
            card?.note?.let { Text(it, style = ArgusType.meta, color = c.waitingText) }
            content()
        }
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()) { content() }
}

/** `ApprovalCard`: what the bot wants to do; Don't allow · Always allow (when the policy offers it) · Allow. */
@Composable
fun ApprovalCard(card: Card, onAnswer: (CardAnswer) -> Unit, modifier: Modifier = Modifier) {
    val c = Argus.colors
    CardFrame(card, card.heading, card.botName, modifier) {
        Text(card.title, style = ArgusType.rowTitle, color = c.ink)
        if (card.detail.isNotBlank()) Text(card.detail, style = ArgusType.sub, color = c.mute, maxLines = 8, overflow = TextOverflow.Ellipsis)
        Buttons {
            ArgusButton("Don't allow", { onAnswer(CardAnswer.Deny) }, size = ButtonSize.S)
            if (card.always) ArgusButton("Always allow", { onAnswer(CardAnswer.Always) }, size = ButtonSize.S)
            ArgusButton("Allow", { onAnswer(CardAnswer.Allow) }, size = ButtonSize.S,
                kind = if (card.cls == com.blaze.assistant.team.ActionClass.DELETE) ButtonKind.DANGER else ButtonKind.PRIMARY)
        }
    }
}

/** `DraftCard`: every recipient and the whole draft; Discard · Edit · Send. Edit opens the text in place. */
@Composable
fun DraftCard(card: Card, onAnswer: (CardAnswer) -> Unit, modifier: Modifier = Modifier) {
    val c = Argus.colors
    val d = card.draft ?: Cards.Draft(card.tool, emptyList(), null, card.detail)
    var editing by remember(card.id) { mutableStateOf(false) }
    var text by remember(card.id) { mutableStateOf(d.body) }
    CardFrame(card, card.heading, card.botName, modifier) {
        Text("To: " + d.to, style = ArgusType.bodyStrong, color = c.ink)
        d.subject?.let { Text("Subject: $it", style = ArgusType.sub, color = c.ink) }
        if (editing) ArgusField(text, { text = it }, "Edit the draft", singleLine = false)
        else Box(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
            Text(d.body.ifBlank { "(no text)" }, style = ArgusType.body, color = c.ink)
        }
        Text("Approving sends exactly this, to these recipients only.", style = ArgusType.meta, color = c.mute)
        Buttons {
            ArgusButton("Discard", { onAnswer(CardAnswer.Discard) }, size = ButtonSize.S)
            if (editing) ArgusButton("Use my edit", { onAnswer(CardAnswer.Edit(text)) }, size = ButtonSize.S, kind = ButtonKind.PRIMARY, enabled = text.isNotBlank())
            else {
                ArgusButton("Edit", { editing = true }, size = ButtonSize.S, icon = "pencil")
                ArgusButton("Send", { onAnswer(CardAnswer.Send) }, size = ButtonSize.S, kind = ButtonKind.PRIMARY)
            }
        }
    }
}

/** `QuestionCard`: the bot's question, up to six choices, or the user's own words. */
@Composable
fun QuestionCard(card: Card, onAnswer: (CardAnswer) -> Unit, modifier: Modifier = Modifier) {
    val c = Argus.colors
    val q = card.question ?: Cards.Question(card.detail, emptyList())
    var text by remember(card.id) { mutableStateOf("") }
    CardFrame(card, card.heading, card.botName, modifier) {
        Text(q.text, style = ArgusType.rowTitle.copy(fontWeight = FontWeight.Medium), color = c.ink)
        q.options.take(Cards.MAX_OPTIONS).forEach { o ->
            ArgusButton(o, { onAnswer(CardAnswer.Reply(o)) }, Modifier.fillMaxWidth(), size = ButtonSize.M)
        }
        ArgusField(text, { text = it }, "Your answer", placeholder = "Or type your own answer", onIme = { if (text.isNotBlank()) onAnswer(CardAnswer.Reply(text.trim())) })
        Buttons {
            ArgusButton("Dismiss", { onAnswer(CardAnswer.Dismiss) }, size = ButtonSize.S)
            ArgusButton("Reply", { onAnswer(CardAnswer.Reply(text.trim())) }, size = ButtonSize.S, kind = ButtonKind.PRIMARY, enabled = text.isNotBlank())
        }
    }
}

/** `ConnectCard`: which app and why; Not now · Connect (opens Accounts) · I've connected it. */
@Composable
fun ConnectCard(card: Card, onAnswer: (CardAnswer) -> Unit, onConnect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Argus.colors
    val app = card.connect?.app ?: "an app"
    var opened by remember(card.id) { mutableStateOf(false) }
    CardFrame(card, card.heading, card.botName, modifier) {
        Text(card.title, style = ArgusType.rowTitle, color = c.ink)
        if (card.detail.isNotBlank()) Text(card.detail, style = ArgusType.sub, color = c.mute)
        Buttons {
            ArgusButton("Not now", { onAnswer(CardAnswer.NotNow) }, size = ButtonSize.S)
            if (opened) ArgusButton("I've connected it", { onAnswer(CardAnswer.Connected) }, size = ButtonSize.S, kind = ButtonKind.PRIMARY)
            else ArgusButton("Connect $app", { opened = true; onConnect(app) }, size = ButtonSize.S, kind = ButtonKind.PRIMARY, icon = "link")
        }
    }
}

/** One live card. */
@Composable
fun CardView(card: Card, onAnswer: (CardAnswer) -> Unit, onConnect: (String) -> Unit = {}, modifier: Modifier = Modifier) {
    when (card.kind) {
        Cards.Kind.APPROVAL -> ApprovalCard(card, onAnswer, modifier)
        Cards.Kind.DRAFT -> DraftCard(card, onAnswer, modifier)
        Cards.Kind.QUESTION -> QuestionCard(card, onAnswer, modifier)
        Cards.Kind.CONNECT -> ConnectCard(card, onAnswer, onConnect, modifier)
    }
}

/** A card as a team thread keeps it: live buttons while pending and on screen, else its state line. */
@Composable
fun DecisionThreadCard(d: CardModel.Decision, live: Card?, onAnswer: (String, CardAnswer) -> Unit, modifier: Modifier = Modifier) {
    if (live != null && d.pending) { CardView(live, { onAnswer(live.id, it) }, modifier = modifier); return }
    val c = Argus.colors
    val heading = when (d.kind) { "draft" -> "DRAFT"; "question" -> "QUESTION"; "connect" -> "CONNECT AN APP"; else -> "APPROVAL" }
    CardFrame(null, heading, null, modifier) {
        Text(d.title, style = ArgusType.rowTitle, color = c.ink)
        d.to?.let { Text("To: $it", style = ArgusType.sub, color = c.mute) }
        d.text?.takeIf { it.isNotBlank() && it != d.title }?.let { Text(it, style = ArgusType.sub, color = c.mute, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        Text(d.stateLabel, style = ArgusType.meta, modifier = Modifier.semantics { stateDescription = d.stateLabel },
            color = when (d.state) { "approved" -> c.successText; "denied" -> c.dangerText; "pending" -> c.waitingText; else -> c.mute })
    }
}

/**
 * The live cards, one per bot (PolicyRuntime). Drawn in the A-team look over whatever screen hosts it (chat, assist
 * panel, call screen), next to the old confirm card.
 */
@Composable
fun ArgusCards(modifier: Modifier = Modifier, dark: Boolean = androidx.compose.foundation.isSystemInDarkTheme()) {
    val cards by com.blaze.assistant.policy.PolicyRuntime.cards.collectAsState()
    if (cards.isEmpty()) return
    val ctx = LocalContext.current
    ArgusTheme(dark = dark) {
        Column(modifier.fillMaxWidth()) {
            for (card in cards) CardView(card, { a -> com.blaze.assistant.policy.PolicyRuntime.answer(card.id, a) },
                onConnect = { runCatching { com.blaze.assistant.agent.SelfSetup.open(ctx, "settings connections") } })
        }
    }
}
