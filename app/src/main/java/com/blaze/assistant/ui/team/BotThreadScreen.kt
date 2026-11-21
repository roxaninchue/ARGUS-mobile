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
import androidx.compose.runtime.collectAsState
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
import com.blaze.assistant.notify.Notifier
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
 * Bot chat redesign 2026-11-20: the thread is drawn by the main chat's own parts (MessageList, Living, the model /
 * effort row, attachments, the mic, Continue on a cut reply) over the bot's hidden chat, through the ChatFeed seam
 * (BotChatFeed in ui/ChatFeed.kt); nothing is copied. Every send still goes through [BotThreads.send], so a
 * turn keeps the bot's identity, tools and rows. The team's record of the bot (jobs, cards, hand-offs: its team.db
 * rows) is the Activity view (the list icon).
 *
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

    /** How often the open thread copies the bot session's live state into its feed (BotChatFeed.pump). */
    const val PUMP_MS = 50L

    /** The + menu entries a bot thread offers: the attachments (the main chat's own entries, filtered). */
    val PLUS = setOf("camera", "photos", "files", "paste")
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    var bot by remember(botId) { mutableStateOf<Bot?>(null) }
    var rows by remember(botId) { mutableStateOf<List<TeamRow>>(emptyList()) }
    var running by remember(botId) { mutableStateOf(false) }
    var chat by remember(botId) { mutableStateOf<String?>(null) }
    var input by remember(botId) { mutableStateOf(TextFieldValue("")) }
    // the team's record (jobs, cards, hand-offs) instead of the conversation
    var activity by remember(botId) { mutableStateOf(false) }
    var plusOpen by remember(botId) { mutableStateOf(false) }
    var pending by remember(botId) { mutableStateOf(listOf<com.blaze.assistant.agent.Attachment>()) }
    var loadingFiles by remember(botId) { mutableStateOf(false) }

    suspend fun reload() {
        val (b, r, live) = withContext(Dispatchers.IO) { Triple(store.get(botId), threads.rows(botId), threads.isRunning(botId)) }
        val ch = withContext(Dispatchers.IO) { threads.thread(botId)?.chatId }
        bot = b; rows = r; running = live; chat = ch
    }
    LaunchedEffect(botId) {
        withContext(Dispatchers.IO) { threads.open(botId) }
        reload()
        if (bot == null) { ArgusToasts.show("That bot isn't on the team any more."); onClose(); return@LaunchedEffect }
        threads.changes.collect { ch -> if (ch.botId == botId) reload() }
    }
    DisposableEffect(botId) { onDispose { BotThreadUi.turns.launch { runCatching { threads.open(null) } } } }
    // Unreleased after 0.54.1: while this thread is on screen its answers don't notify (owner: "they notify even when I'm in their chat").
    DisposableEffect(chat) { val c = chat; Notifier.watching = c; onDispose { if (Notifier.watching == c) Notifier.watching = null } }

    fun sendText(text: String, byVoice: Boolean) {
        val b = bot ?: return
        val t = text.trim()
        val files = pending
        if (t.isEmpty() && files.isEmpty()) return
        pending = emptyList()
        // attachments ride this turn (staged for the bot's own chat; the driver takes them for the user's message)
        chat?.let { AgentSessionDriver.files.stage(it, files) }
        BotThreadUi.turns.launch {
            runCatching {
                threads.send(botId, Trigger.User(t.ifEmpty { "Take a look at this." }, byVoice = byVoice)).collect { e -> if (e is TurnEvent.Failed) ArgusToasts.show(e.error) }
            }.onFailure { ArgusToasts.show(it.message ?: "${b.name} couldn't answer.") }
        }
    }
    val sendNow = androidx.compose.runtime.rememberUpdatedState<(String, Boolean) -> Unit> { t, v -> sendText(t, v) }
    val ch = chat
    val feed = remember(ch) { ch?.let { com.blaze.assistant.ui.BotChatFeed(it) { t -> sendNow.value(t, false) } } }
    LaunchedEffect(feed) { val f = feed ?: return@LaunchedEffect; while (true) { f.pump(); kotlinx.coroutines.delay(BotThreadUi.PUMP_MS) } }

    // attachments: the main chat's pickers and loader
    fun addUris(uris: List<android.net.Uri>, mimeHint: String = "") {
        if (uris.isEmpty()) return
        loadingFiles = true
        uiScope.launch {
            val loaded = withContext(Dispatchers.IO) { uris.take(5).mapNotNull { com.blaze.assistant.agent.Attachments.load(ctx, it, mimeHint) } }
            if (loaded.size < uris.take(5).size) ArgusToasts.show("Some files couldn't be attached (512 MB at most).")
            pending = pending + loaded
            loadingFiles = false
        }
    }
    val filePick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { addUris(it) }
    val photoPick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(5)) { addUris(it) }
    val photoOnly = androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)
    val camera = com.blaze.assistant.ui.rememberCameraCapture(onPhoto = { addUris(listOf(it), "image/jpeg") }, onPickPhoto = { photoPick.launch(photoOnly) })
    camera.dialogs()
    val hasCamera = remember { com.blaze.assistant.device.CameraSupport.hasCamera(ctx) }
    fun pasteImage() {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        val clip = try { cm?.primaryClip } catch (_: Exception) { null } ?: return
        val hint = (0 until clip.description.mimeTypeCount).map { clip.description.getMimeType(it) }.firstOrNull { it.startsWith("image/") }.orEmpty()
        addUris((0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }, hint)
    }
    // the mic: the main chat's voice input; what was heard goes to this bot
    val voiceState by com.blaze.assistant.voice.Voice.state.collectAsState()
    fun startVoice() = com.blaze.assistant.voice.Voice.listen(onText = { if (it.isNotBlank()) sendNow.value(it, true) }, onError = { ArgusToasts.show(it) })
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startVoice() else ArgusToasts.show("${com.blaze.assistant.BrandName.NAME} needs the microphone to hear you.")
    }
    fun onMic() = when {
        voiceState == com.blaze.assistant.voice.Voice.State.LISTENING -> com.blaze.assistant.voice.Voice.stopByTap()
        voiceState == com.blaze.assistant.voice.Voice.State.SPEAKING -> com.blaze.assistant.voice.Voice.stopSpeaking()
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED -> startVoice()
        else -> micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    var reducedMotion by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { reducedMotion = com.blaze.assistant.ui.ComposerHintsSource.reducedMotion(ctx) }

    val b = bot
    ArgusGround(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // top bar: back, the bot's own shape, name and what it's doing, then New conversation, Activity and Edit
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                ArgusIconButton("arrow-left", tr("Back"), { if (activity) activity = false else onClose() }, kind = IconButtonKind.FLAT)
                if (b != null) {
                    Spacer(Modifier.width(4.dp))
                    BotOrb(b.avatar.face?.let { OrbFace.of(it) } ?: OrbFace.forName(b.name), ArgusTokens.botColor(b.avatar.color) ?: 0xFFCBD5E1L,
                        OrbSize.SM.dp, state = if (running) OrbState.WORKING else OrbState.IDLE)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.name, style = ArgusType.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() })
                        Text(tr(if (activity) "Activity" else BotThreadUi.subtitle(b, running)), style = ArgusType.meta, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ArgusIconButton("square-pen", tr("New conversation"), {
                        BotThreadUi.turns.launch {
                            when (val f = threads.fresh(botId)) {
                                is BotThreads.Fresh.Refused -> ArgusToasts.show(f.why)
                                is BotThreads.Fresh.Ok -> ArgusToasts.show("New conversation with ${b.name}")
                            }
                        }
                    }, kind = IconButtonKind.FLAT)
                    ArgusIconButton("list-checks", tr(if (activity) "Conversation" else "Activity"), { activity = !activity }, kind = IconButtonKind.FLAT)
                    ArgusIconButton("settings", tr("Edit {0}", b.name), { onEditSoul(botId) }, kind = IconButtonKind.FLAT)
                } else Spacer(Modifier.weight(1f))
            }
            if (activity) androidx.activity.compose.BackHandler { activity = false }
            val f = feed
            val noMsgs = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.blaze.assistant.agent.ChatMessage>()) }
            val noMood = remember { kotlinx.coroutines.flow.MutableStateFlow(com.blaze.assistant.agent.Mood.IDLE) }
            val msgs by (f?.feed?.messages ?: noMsgs).collectAsState()
            val mood by (f?.feed?.mood ?: noMood).collectAsState()
            val working = running || mood != com.blaze.assistant.agent.Mood.IDLE
            val living by com.blaze.assistant.ui.living.LivingSpec.view.collectAsState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (b != null) {
                    if (activity || f == null) {
                        val items = remember(rows, running, b) { ThreadModel.items(rows, b.name, running, ThreadModel.intro(b)) }
                        ThreadList(items, ThreadActions(openJob = onOpenJob), Modifier.fillMaxSize())
                    } else androidx.compose.runtime.CompositionLocalProvider(com.blaze.assistant.ui.LocalChatFeed provides f.feed) {
                        when {
                            msgs.isEmpty() && !working -> ThreadList(listOf(ThreadModel.intro(b)), ThreadActions(), Modifier.fillMaxSize())
                            living == com.blaze.assistant.ui.living.LivingSpec.View.LIVING -> {
                                androidx.activity.compose.BackHandler { com.blaze.assistant.ui.living.LivingSpec.back() }
                                com.blaze.assistant.ui.living.LivingScreen(working, null, reducedMotion)
                            }
                            else -> com.blaze.assistant.ui.MessageList(mood, onQuote = { q -> input = com.blaze.assistant.ui.argus.kit.composerFill("\u201c$q\u201d ") },
                                onFollowup = { sendNow.value(it, false) })
                        }
                    }
                }
            }
            if (b != null) {
                if (!activity && msgs.isNotEmpty())
                    androidx.compose.animation.AnimatedVisibility(!plusOpen) { com.blaze.assistant.ui.living.LivingSwitch() }
                if (pending.isNotEmpty() || loadingFiles) com.blaze.assistant.ui.AttachmentThumbs(pending, loadingFiles) { a -> pending = pending - a }
                if (plusOpen) androidx.activity.compose.BackHandler { plusOpen = false }
                ArgusComposer(input, { input = it }, mode = "bot:${b.id}", streaming = working, attachments = pending.size,
                    onPlus = { plusOpen = !plusOpen }, plusOpen = plusOpen,
                    menu = {
                        val entries = remember(hasCamera) { com.blaze.assistant.ui.plusItems("", "", "", hasClipImage = true, hasCamera = hasCamera).filter { it.id in BotThreadUi.PLUS } }
                        com.blaze.assistant.ui.argus.kit.ComposerPlusMenu(entries, onPick = { id ->
                            plusOpen = false
                            when (id) {
                                "camera" -> camera.open()
                                "photos" -> photoPick.launch(photoOnly)
                                "files" -> filePick.launch(arrayOf("*/*"))
                                "paste" -> pasteImage()
                            }
                        })
                    },
                    onSend = { val t = input.text; input = TextFieldValue(""); sendNow.value(t, false) },
                    onMic = { onMic() },
                    onStop = { BotThreadUi.turns.launch { threads.thread(botId)?.chatId?.let { runCatching { AgentSessionDriver.cancel(it) } } } },
                    hint = BotThreadUi.hint(b),
                    // the main chat's model · effort row (the same picks: they are app-wide)
                    top = { com.blaze.assistant.ui.argus.kit.ComposerTopRow() })
            }
        }
    }
}
