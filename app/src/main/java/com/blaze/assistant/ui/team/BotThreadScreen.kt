package com.blaze.assistant.ui.team

import com.blaze.assistant.i18n.tr
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.agent.engines.AgentSessionDriver
import com.blaze.assistant.agent.engines.TurnEvent
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.BotThreads
import com.blaze.assistant.team.Row as TeamRow
import com.blaze.assistant.team.TeamGraph
import com.blaze.assistant.team.Trigger
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusGround
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.BotOrb
import com.blaze.assistant.ui.argus.OrbFace
import com.blaze.assistant.ui.argus.OrbSize
import com.blaze.assistant.ui.argus.OrbState
import com.blaze.assistant.ui.argus.kit.ArgusComposer
import com.blaze.assistant.ui.argus.kit.ArgusIconButton
import com.blaze.assistant.ui.argus.kit.ArgusToasts
import com.blaze.assistant.ui.argus.kit.IconButtonKind
import com.blaze.assistant.ui.team.thread.ThreadActions
import com.blaze.assistant.ui.team.thread.ThreadList
import com.blaze.assistant.ui.team.thread.ThreadModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owner 2026-11-17: tapping a bot on Team opens its own chat (it used to toast "That comes in the next update"). One
 * thread per bot (D1, [BotThreads]); its turns run on the phone's agent loop ([AgentSessionDriver.engine], wired in
 * [TeamGraph]) in the bot's hidden chat, with its soul and tool set. The Manager's thread stays the main Chat.
 */
object BotThreadUi {
    /** Turns outlive the screen: leaving the thread doesn't stop a bot that is working (its rows keep landing). */
    val turns = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Pure: what the top bar's second line says. */
    fun subtitle(b: Bot, running: Boolean): String = when {
        running -> "Working\u2026"
        !b.enabled -> "Switched off"
        else -> b.label.ifBlank { "Teammate" }
    }

    /** Pure: the composer's hint. */
    fun hint(b: Bot): String = "Message ${b.name}\u2026"
}

/** Opens team.db off the main thread, then draws [botId]'s thread over the shell; null draws nothing. */
@Composable
fun BotThreadHost(botId: String?, onClose: () -> Unit, onEditSoul: (String) -> Unit, onOpenJob: (String) -> Unit) {
    if (botId == null) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var graph by remember { mutableStateOf<TeamGraph.Graph?>(null) }
    LaunchedEffect(Unit) {
        try { graph = withContext(Dispatchers.IO) { TeamGraph.get(ctx.applicationContext) } }
        catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("BotThreadHost.open", t); ArgusToasts.show("Team couldn't open."); onClose() }
    }
    val g = graph ?: return
    BotThreadScreen(botId, g.store, g.threads, onClose, onEditSoul, onOpenJob)
}

@Composable
fun BotThreadScreen(botId: String, store: com.blaze.assistant.team.TeamStore, threads: BotThreads, onClose: () -> Unit,
                    onEditSoul: (String) -> Unit, onOpenJob: (String) -> Unit) {
    val c = Argus.colors
    var bot by remember(botId) { mutableStateOf<Bot?>(null) }
    var rows by remember(botId) { mutableStateOf<List<TeamRow>>(emptyList()) }
    var running by remember(botId) { mutableStateOf(false) }
    var input by remember(botId) { mutableStateOf(TextFieldValue("")) }

    suspend fun reload() {
        val (b, r, live) = withContext(Dispatchers.IO) { Triple(store.get(botId), threads.rows(botId), threads.isRunning(botId)) }
        bot = b; rows = r; running = live
    }
    LaunchedEffect(botId) {
        withContext(Dispatchers.IO) { threads.open(botId) }
        reload()
        if (bot == null) { ArgusToasts.show("That bot isn't on the team any more."); onClose(); return@LaunchedEffect }
        threads.changes.collect { ch -> if (ch.botId == botId) reload() }
    }
    DisposableEffect(botId) { onDispose { BotThreadUi.turns.launch { runCatching { threads.open(null) } } } }

    val b = bot
    ArgusGround(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // top bar: back, the bot's own shape, name and what it's doing, then Edit
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                ArgusIconButton("arrow-left", tr("Back"), onClose, kind = IconButtonKind.FLAT)
                if (b != null) {
                    Spacer(Modifier.width(4.dp))
                    BotOrb(b.avatar.face?.let { OrbFace.of(it) } ?: OrbFace.forName(b.name), ArgusTokens.botColor(b.avatar.color) ?: 0xFFCBD5E1L,
                        OrbSize.SM.dp, state = if (running) OrbState.WORKING else OrbState.IDLE)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.name, style = ArgusType.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() })
                        Text(tr(BotThreadUi.subtitle(b, running)), style = ArgusType.meta, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ArgusIconButton("square-pen", tr("New conversation"), {
                        BotThreadUi.turns.launch {
                            when (val f = threads.fresh(botId)) {
                                is BotThreads.Fresh.Refused -> ArgusToasts.show(f.why)
                                is BotThreads.Fresh.Ok -> ArgusToasts.show("New conversation with ${b.name}")
                            }
                        }
                    }, kind = IconButtonKind.FLAT)
                    ArgusIconButton("settings", tr("Edit {0}", b.name), { onEditSoul(botId) }, kind = IconButtonKind.FLAT)
                } else Spacer(Modifier.weight(1f))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (b != null) {
                    val items = remember(rows, running, b) { ThreadModel.items(rows, b.name, running, ThreadModel.intro(b)) }
                    ThreadList(items, ThreadActions(openJob = onOpenJob), Modifier.fillMaxSize())
                }
            }
            if (b != null) {
                fun sendNow() {
                    val text = input.text.trim()
                    if (text.isEmpty()) return
                    input = TextFieldValue("")
                    BotThreadUi.turns.launch {
                        runCatching {
                            threads.send(botId, Trigger.User(text)).collect { e -> if (e is TurnEvent.Failed) ArgusToasts.show(e.error) }
                        }.onFailure { ArgusToasts.show(it.message ?: "${b.name} couldn't answer.") }
                    }
                }
                ArgusComposer(input, { input = it }, mode = "bot:${b.id}", streaming = running, attachments = 0,
                    onPlus = {}, onSend = { sendNow() }, onMic = { sendNow() },
                    onStop = { BotThreadUi.turns.launch { threads.thread(botId)?.chatId?.let { runCatching { AgentSessionDriver.cancel(it) } } } },
                    hint = BotThreadUi.hint(b))
            }
        }
    }
}
