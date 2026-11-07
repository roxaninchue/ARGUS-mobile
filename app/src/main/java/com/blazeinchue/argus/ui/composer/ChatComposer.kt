package com.blazeinchue.argus.ui.composer


import com.blazeinchue.argus.BrandName
import com.blazeinchue.argus.composer.ComposerLook
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blazeinchue.argus.R
import com.blazeinchue.argus.agent.Agent
import com.blazeinchue.argus.agent.ChatMessage
import com.blazeinchue.argus.agent.Role
import com.blazeinchue.argus.composer.ChatOverlayLogic
import com.blazeinchue.argus.ui.DuoColors
import com.blazeinchue.argus.ui.GlassIntensity
import com.blazeinchue.argus.ui.LocalArgus
import com.blazeinchue.argus.ui.MotionTokens
import com.blazeinchue.argus.ui.Type
import com.blazeinchue.argus.ui.glass
import kotlinx.coroutines.delay

/** 0.47 chat composer: sizes the Home layout reserves under its cards for the bar. */
object ChatBarTokens {
    const val HEIGHT_DP = 64
    const val AVATAR_DP = 40
}

// ---------------------------------------------------------------- avatars

/**
 * The user's avatar (the chat bar, their bubbles): the signed-in Argus account's photo, else the initial of its name /
 * email (the same person as the profile button); a neutral person icon when signed out. 0.48.0 RC4: never a static "Y".
 */
@Composable
fun MeAvatar(size: Int = ChatBarTokens.AVATAR_DP, onClick: (() -> Unit)? = null) {
    val acct by com.blazeinchue.argus.connect.ArgusAccount.view.collectAsState()
    val u = acct.user
    val av = remember(u) { ChatOverlayLogic.avatar(u != null, u?.name, u?.email, u?.avatarUrl) }
    MeAvatarView(av, size, onClick)
}

/** [MeAvatar] on plain data (screenshots). */
@Composable
fun MeAvatarView(av: ChatOverlayLogic.Avatar, size: Int = ChatBarTokens.AVATAR_DP, onClick: (() -> Unit)? = null) {
    val c = LocalArgus.current
    val photo = (av as? ChatOverlayLogic.Avatar.Photo)?.let { com.blazeinchue.argus.ui.rememberHomeImage(it.url) }
    val letter = when (av) { is ChatOverlayLogic.Avatar.Photo -> av.letter; is ChatOverlayLogic.Avatar.Letter -> av.letter; else -> null }
    Box(Modifier.size(size.dp).clip(CircleShape).background(ComposerLook.solidChip(c))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .semantics { contentDescription = if (av is ChatOverlayLogic.Avatar.Neutral) "Profile: signed out" else "Your profile" }, contentAlignment = Alignment.Center) {
        when {
            photo != null -> androidx.compose.foundation.Image(photo, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            letter != null -> Text(letter, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = (size * 0.4f).sp, color = c.ink))
            else -> Icon(Icons.Rounded.Person, null, tint = c.sub, modifier = Modifier.size((size * 0.55f).dp))
        }
    }
}

/**
 * Argus's avatar: the real Argus logo, the same one as the launcher icon and BrandMark (the glass drop in its gold halo
 * on the dark disc). 0.49.0 (RC5 a): it was the white notification silhouette on a blue disc, which read as a blue droplet.
 * Drawn as vectors on a Canvas (no bitmap decode per bubble on a low-RAM phone).
 */
@Composable
fun ArgusAvatar(size: Int = 30) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(com.blazeinchue.argus.phone.Mascot.ICON_BG))
        .semantics { contentDescription = "${BrandName.NAME}" }, contentAlignment = Alignment.Center) {
        com.blazeinchue.argus.ui.DuoEmblem((size * 1.05f).dp, animate = false)
    }
}

// ---------------------------------------------------------------- the chat bar

/**
 * The chat bar that replaces the bottom Argus button: profile avatar LEFT, the "Message Argus" pill, "+" RIGHT.
 * A send arrow shows inside the pill while there's text; "+" always opens Spotlight. The attachment
 * chips slide up above the pill on the first typed character or attachment.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ArgusChatBar(state: ComposerState, modifier: Modifier = Modifier, onProfile: () -> Unit = {}) {
    val c = LocalArgus.current
    // RC4: with the keyboard up the bar sits on its own ground, so the cards it floats over never read through it.
    val ime = WindowInsets.isImeVisible
    Column(modifier.fillMaxWidth()
        .then(if (ime) Modifier.background(Brush.verticalGradient(listOf(ComposerLook.dock(c).copy(alpha = 0f), ComposerLook.dock(c).copy(alpha = 0.94f), ComposerLook.dock(c)))) else Modifier)
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        AnimatedVisibility(state.chipShown,
            enter = if (MotionTokens.enabled) slideInVertically(tween(MotionTokens.MEDIUM)) { it } + fadeIn(tween(MotionTokens.MEDIUM)) else fadeIn(tween(0)),
            exit = if (MotionTokens.enabled) slideOutVertically(tween(MotionTokens.SHORT)) { it } + fadeOut(tween(MotionTokens.SHORT)) else fadeOut(tween(0))) {
            Column(Modifier.padding(start = (ChatBarTokens.AVATAR_DP + 8).dp, bottom = 6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 0.52.3: no model chip / picker (ARGUS picks the brain automatically)
                    state.attachments.forEach { a ->
                        SmallChip(a.name.take(22), desc = "Attachment ${a.name}. Tap to remove", close = true) { state.attachments.remove(a) }
                    }
                    if (state.attaching) SmallChip("Adding\u2026", desc = "Adding a file") { }
                }
            }
        }
        com.blazeinchue.argus.ui.ClearFocusOnLeave()   // 0.52.1: the keyboard only shows when you tap the box
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            MeAvatar(onClick = onProfile)
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(50)).background(ComposerLook.pill(c))
                .border(1.dp, c.ink.copy(alpha = 0.10f), RoundedCornerShape(50))
                .padding(start = 18.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (state.text.isEmpty()) Text("Message ${BrandName.NAME}", style = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, color = c.sub))
                    BasicTextField(state.text, { state.text = it },
                        textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 16.sp, color = c.ink), cursorBrush = SolidColor(DuoColors.argusBlue),
                        maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (state.canSend) state.send() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(state.barFocus).semantics { contentDescription = "Message ${BrandName.NAME}" })
                }
                // The send arrow lives INSIDE the pill and only while there's text.
                AnimatedVisibility(state.canSend, enter = fadeIn(tween(MotionTokens.ms(MotionTokens.SHORT).toInt())), exit = fadeOut(tween(MotionTokens.ms(MotionTokens.SHORT).toInt()))) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(DuoColors.argusBlue).clickable { state.send() }
                        .semantics { contentDescription = "Send" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ArrowUpward, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // "+" always opens Spotlight (a floating panel of its own; the bar steps aside while it is up).
            Box(Modifier.size(44.dp).clip(CircleShape).background(ComposerLook.solidChip(c))
                .clickable { if (state.spotlightOpen) state.closeSpotlight() else state.openSpotlight() }
                .semantics { contentDescription = if (state.spotlightOpen) "Close Spotlight" else "Open Spotlight" }, contentAlignment = Alignment.Center) {
                val turn = remember { Animatable(0f) }
                LaunchedEffect(state.spotlightOpen) { if (MotionTokens.enabled) turn.animateTo(if (state.spotlightOpen) 45f else 0f, tween(MotionTokens.MEDIUM)) else turn.snapTo(if (state.spotlightOpen) 45f else 0f) }
                Icon(Icons.Rounded.Add, null, tint = c.ink, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = turn.value })
            }
        }
    }
}

@Composable
private fun SmallChip(label: String, desc: String, trailing: Boolean = false, close: Boolean = false, onClick: () -> Unit) {
    val c = LocalArgus.current
    Row(Modifier.clip(RoundedCornerShape(50)).background(ComposerLook.solidChip(c)).border(1.dp, c.ink.copy(alpha = 0.10f), RoundedCornerShape(50))
        .clickable(onClick = onClick).semantics { contentDescription = desc }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.ink))
        if (trailing) Icon(Icons.Rounded.ExpandMore, null, tint = c.sub, modifier = Modifier.padding(start = 2.dp).size(16.dp))
        if (close) Icon(Icons.Rounded.Close, null, tint = c.sub, modifier = Modifier.padding(start = 4.dp).size(14.dp))
    }
}

// ---------------------------------------------------------------- chat over Home

/**
 * The chat over the blurred widgets: user bubbles RIGHT with the user's avatar, Argus LEFT with the Argus logo
 * (typing dots, then the text types in), a centred timestamp, and a × that unblurs Home.
 */
@Composable
fun ChatOverHome(state: ComposerState, modifier: Modifier = Modifier, bottom: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {}) {
    val all by Agent.messages.collectAsState()
    val busyChats by Agent.busyChats.collectAsState()
    val chatId by Agent.chatId.collectAsState()
    // 0.49.0 (RC5 a): the live work line beside the typing dots, as in the old chat (step pill → task chip → status).
    val status by Agent.status.collectAsState()
    val chip by Agent.taskChip.collectAsState()
    val pill by com.blazeinchue.argus.agent.StepPills.current.collectAsState()
    val busy = chatId in busyChats
    val lastStep = if (busy) all.lastOrNull { it.role == Role.STEP }?.text else null
    // 0.52.0: no status line beside the typing dots (ChatPolicy): the work pill above carries the progress.
    val work = if (busy && com.blazeinchue.argus.ui.ChatPolicy.STATUS_LINES) ChatOverlayLogic.workLine(pill?.takeIf { it.chat == chatId }?.text, chip, status, lastStep) else null
    ChatOverHomeContent(state, all, busy, modifier, bottom = bottom, workLine = work, onSend = { state.send(it) })
}

/**
 * [ChatOverHome] on plain data (screenshots). 0.48.0 RC4: the only chat surface (the old full chat screen is gone):
 * a title for project / private chats, "Show earlier messages" for the whole thread, Argus's replies with their rich
 * formatting (lists, tables, links, pictures), and [bottom] for what Argus is waiting on (approvals, questions, secure
 * forms, the listening strip), above the bar.
 */
@Composable
fun ChatOverHomeContent(state: ComposerState, all: List<ChatMessage>, busy: Boolean, modifier: Modifier = Modifier,
                        now: Long = System.currentTimeMillis(), bottom: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {},
                        workLine: String? = null, onSend: ((String) -> Unit)? = null, stage: Boolean = true) {
    val c = LocalArgus.current
    val shown = remember(all, state.sinceId) { ChatOverlayLogic.visible(all, state.sinceId) }
    // 0.49.0 (RC5 a): tool steps show as one compact chip per turn (tap to expand), follow-up chips under the newest idle
    // reply, and choice buttons / forms inside replies send (RichSend), as in the old chat.
    val rows = remember(all, state.sinceId, busy) { ChatOverlayLogic.rows(all, state.sinceId, busy) }
    val followFor = ChatOverlayLogic.followupsFor(rows, busy)
    val more = remember(all, state.sinceId) { state.sinceId > 0L && ChatOverlayLogic.visible(all, 0L).size > shown.size }
    val earlier = more
    val typing = ChatOverlayLogic.typing(shown, busy)
    val rowsShown = remember(rows) { rows.filter { it !is ChatOverlayLogic.Row.Steps && !(it is ChatOverlayLogic.Row.Msg && it.m.meta.startsWith(com.blazeinchue.argus.agent.BgAgents.CARD)) } }
    val agentsAll by com.blazeinchue.argus.agent.BgAgents.all.collectAsState()
    val chatNow by com.blazeinchue.argus.agent.Agent.chatId.collectAsState()
    val agentLogs = remember(agentsAll, chatNow) { agentsAll.filter { it.live && it.parent == chatNow }.sortedBy { it.started }
        .map { b -> b.log.ifEmpty { listOfNotNull(b.last.takeIf { l -> l.isNotBlank() }) } } }
    val turnLines = (rows.lastOrNull() as? ChatOverlayLogic.Row.Steps)?.takeIf { it.live }?.lines ?: emptyList()
    @Suppress("UNUSED_VARIABLE") val unusedWorkLine = workLine
    val pillLine = com.blazeinchue.argus.agent.TaskLine.pick(if (busy) turnLines else null, agentLogs)
    val listState = rememberLazyListState()
    LaunchedEffect(rowsShown.size, pillLine != null, shown.lastOrNull()?.text?.length, followFor) { val n = rowsShown.size + (if (pillLine != null) 1 else 0) + 2; if (n > 0) listState.scrollToItem(n - 1) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val title = if (state.privateChat) "Private chat" else state.title
            if (title.isNotBlank()) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp),
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink))
            else Spacer(Modifier.weight(1f))
            if (earlier) { RoundButton(Icons.Rounded.History, "Show earlier messages", state::showEarlier); Spacer(Modifier.width(8.dp)) }
            // 0.50.0: maximise the chat over Home to full screen (opaque, the whole thread) and restore it
            RoundButton(if (com.blazeinchue.argus.ui.ChatExpand.on) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                if (com.blazeinchue.argus.ui.ChatExpand.on) "Restore the chat" else "Maximise the chat") {
                com.blazeinchue.argus.ui.ChatExpand.toggle(); if (com.blazeinchue.argus.ui.ChatExpand.on) state.openChat(full = true) }
            Spacer(Modifier.width(8.dp))
            RoundButton(Icons.Rounded.Close, if (state.privateChat) "Leave private chat" else "Close chat", state::closeChat)
        }
        val list: @Composable () -> Unit = {
        LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            if (earlier) item(key = "earlier") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Show earlier messages", modifier = Modifier.clip(RoundedCornerShape(50)).background(ComposerLook.solidChip(c))
                        .clickable { state.showEarlier() }.padding(horizontal = 14.dp, vertical = 7.dp),
                        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.ink))
                }
            }
            item(key = "stamp") {
                val at = shown.firstOrNull()?.at ?: now
                Text(ChatOverlayLogic.stamp(at, now), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
            // 0.52.1 (owner): no step chips, work cards or agent pills in the chat: the task's work is ONE TaskPill at the end.
            items(rowsShown, key = { it.key }) { r ->
                when (r) {
                    is ChatOverlayLogic.Row.Steps -> Unit
                    is ChatOverlayLogic.Row.Msg -> Column { val m = r.m
                        if (m.role == Role.USER) UserBubble(m, lift = m.id > state.liveFrom) else ArgusBubble(m, typeIn = m.id > state.liveFrom)
                        if (m.id == followFor && onSend != null) com.blazeinchue.argus.ui.FollowupRow(m.followups, Modifier.padding(start = 38.dp, top = 6.dp)) { onSend(it) }
                    }
                }
            }
            if (pillLine != null) item(key = "typing") { Row(verticalAlignment = Alignment.CenterVertically) { ArgusAvatar(); Spacer(Modifier.width(8.dp))
                com.blazeinchue.argus.ui.TaskPill(pillLine) } }
        }
        }
        // Choice buttons and forms in replies answer only while Argus is idle (like the old chat).
        androidx.compose.runtime.CompositionLocalProvider(com.blazeinchue.argus.ui.RichSend.Local provides (if (!busy) onSend else null)) {
            // 0.49.0 (RC5 a): on-chat cards (browser card, timers, mini players) on the Stage around the chat, as in the old chat.
            val st by com.blazeinchue.argus.stage.StageStore.state.collectAsState()
            if (stage && com.blazeinchue.argus.stage.StageStore.enabled && st.items.isNotEmpty())
                Box(Modifier.weight(1f).fillMaxWidth()) { com.blazeinchue.argus.ui.StageHost(shown.lastOrNull { it.role == Role.ARGUS }?.text.orEmpty()) { m -> Box(m) { list() } } }
            else Box(Modifier.weight(1f).fillMaxWidth()) { list() }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { bottom() }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val c = LocalArgus.current
    Box(Modifier.size(36.dp).clip(CircleShape).glass(CircleShape, GlassIntensity.High, shadow = false).clickable(onClick = onClick)
        .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.ink, modifier = Modifier.size(18.dp))
    }
}

/** The user's line: lifts out of the pill (rises from the bar) into a bubble on the RIGHT with their avatar on its right. */
@Composable
private fun UserBubble(m: ChatMessage, lift: Boolean) {
    val c = LocalArgus.current
    val rise = remember(m.id) { Animatable(if (lift && MotionTokens.enabled) 1f else 0f) }
    LaunchedEffect(m.id) { if (rise.value > 0f) rise.animateTo(0f, tween(MotionTokens.LONG, easing = MotionTokens.Decelerate)) }
    Row(Modifier.fillMaxWidth().graphicsLayer { translationY = rise.value * 260f; alpha = 1f - rise.value * 0.4f; scaleX = 1f - rise.value * 0.06f; scaleY = scaleX },
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
        val text = com.blazeinchue.argus.ui.AttachView.bubbleText(m.text, m.media)
        Column(Modifier.weight(1f, fill = false).widthIn(max = 280.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
            .background(DuoColors.argusBlue.copy(alpha = if (LocalComposerSolid.current) 1f else if (c.dark) 0.85f else 0.95f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (m.files.isNotEmpty()) Text(m.files.joinToString(" \u00b7 "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f)))
            if (text.isNotBlank()) Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = Color.White))
        }
        Spacer(Modifier.width(8.dp))
        MeAvatar(size = 30)
    }
}

/** Argus's line on the LEFT with the Argus logo; a fresh reply types in (reduce motion: shown whole). */
@Composable
private fun ArgusBubble(m: ChatMessage, typeIn: Boolean) {
    val c = LocalArgus.current
    var shown by remember(m.id) { mutableIntStateOf(if (typeIn && MotionTokens.enabled) 0 else Int.MAX_VALUE) }
    LaunchedEffect(m.id, m.text.length) {
        while (shown < m.text.length) { delay(16); shown = minOf(m.text.length, shown + ChatOverlayLogic.step(m.text.length - shown)) }
    }
    val err = m.role == Role.ERROR
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        ArgusAvatar()
        Spacer(Modifier.width(8.dp))
        val solid = LocalComposerSolid.current
        Box(Modifier.weight(1f, fill = false).widthIn(max = 290.dp).clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
            .then(if (solid) Modifier.background(ComposerLook.argusBubble(c)) else Modifier.glass(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp), GlassIntensity.High, shadow = false))
            .then(if (err) Modifier.border(1.dp, Color(0xFFE5484D).copy(alpha = 0.6f), RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (shown >= m.text.length && !err) Column {
                // RC4: the whole reply with its formatting once it has typed in (lists, tables, code, links), like the old chat.
                val body = remember(m.text) { runCatching { com.blazeinchue.argus.agent.ToolLeak.bubbleText(m.text) }.getOrDefault(m.text) }
                com.blazeinchue.argus.ui.AnswerBody(m.id, body)
            }
            else Text(if (shown >= m.text.length) m.text else m.text.take(shown), style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.ink))
        }
    }
    // Pictures, music and files Argus made for this reply (tap to open), as in the old chat.
    if (m.media.isNotEmpty() && shown >= m.text.length) Box(Modifier.padding(start = 38.dp, top = 6.dp)) { com.blazeinchue.argus.ui.MediaStrip(m.media) }
    }
}

/** 0.49.0 (RC5 a): a turn's tool steps as one chip ("3 steps · Searching the web"); tap to list them. Live = still running. */
@Composable
private fun StepsChip(r: ChatOverlayLogic.Row.Steps) {
    val c = LocalArgus.current
    var open by remember(r.firstId) { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.padding(start = 38.dp)) {
        Row(Modifier.clip(RoundedCornerShape(50)).background(ComposerLook.solidChip(c)).clickable { open = !open }
            .semantics { contentDescription = (if (r.live) "Working: " else "Steps: ") + ChatOverlayLogic.stepsLabel(r) }
            .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (r.live) Color(0xFF4FA3FF) else c.sub))
            Spacer(Modifier.width(8.dp))
            Text(ChatOverlayLogic.stepsLabel(r), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 250.dp),
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.ink))
            if (r.lines.size > 1) { Spacer(Modifier.width(4.dp)); Icon(Icons.Rounded.ExpandMore, null, tint = c.sub, modifier = Modifier.size(16.dp)) }
        }
        if (open && r.lines.size > 1) Column(Modifier.padding(start = 12.dp, top = 4.dp)) {
            r.lines.forEach { l -> Text("\u00b7 " + (com.blazeinchue.argus.agent.StepPill.clean(l) ?: "Working on it"), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub)) }
        }
    }
}

/** Three bouncing dots (static with reduce motion). */
@Composable
fun TypingDots() {
    val c = LocalArgus.current
    val solid = LocalComposerSolid.current
    Row(Modifier.clip(RoundedCornerShape(18.dp))
        .then(if (solid) Modifier.background(ComposerLook.argusBubble(c)) else Modifier.glass(RoundedCornerShape(18.dp), GlassIntensity.High, shadow = false))
        .semantics { contentDescription = "${BrandName.NAME} is typing" }.padding(horizontal = 14.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (!MotionTokens.enabled) repeat(3) { Box(Modifier.size(7.dp).clip(CircleShape).background(c.sub)) }
        else {
            val t = rememberInfiniteTransition(label = "dots")
            repeat(3) { i ->
                val y by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, delayMillis = i * 150), RepeatMode.Restart), label = "d$i")
                val lift = if (y < 0.35f) y / 0.35f else if (y < 0.7f) (0.7f - y) / 0.35f else 0f
                Box(Modifier.size(7.dp).graphicsLayer { translationY = -lift * 10f }.clip(CircleShape).background(c.sub))
            }
        }
    }
}
