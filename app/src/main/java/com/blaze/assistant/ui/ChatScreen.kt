package com.blaze.assistant.ui


import com.blaze.assistant.BrandName
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Attachment
import com.blaze.assistant.agent.Attachments
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.ChatNav
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Role
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.phone.BlazeAccessibilityService
import com.blaze.assistant.voice.Voice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

/** What the message list draws: a message, or a folded run of steps. [pos] = its place in a run from one side (v0.24.0). */
internal sealed class Item(val key: String) {
    var pos: GroupPos = GroupPos.SINGLE
    abstract val side: Side
    abstract val at: Long
    /** 0.45.19: who wrote it on Blaze's side ("halo" or "" for Blaze), so Halo's and Blaze's messages group apart. */
    open val who: String get() = ""
    class Msg(val m: ChatMessage) : Item("m${m.id}") {
        override val side: Side get() = if (m.role == Role.USER) Side.ME else Side.BLAZE
        override val at: Long get() = m.at
        override val who: String get() = if (m.role == Role.BLAZE && com.blaze.assistant.agent.Duo.Who.of(m.speaker) == com.blaze.assistant.agent.Duo.Who.HALO) "halo" else ""
    }
    class Steps(val steps: List<ChatMessage>, val live: Boolean, val endAt: Long, val startAt: Long) : Item("s${steps.first().id}") {
        override val side: Side get() = Side.BLAZE
        override val at: Long get() = steps.first().at
    }
}

internal fun buildItems(messages: List<ChatMessage>, working: Boolean, hideDoneSteps: Boolean = false): List<Item> {
    val out = mutableListOf<Item>()
    var run = mutableListOf<ChatMessage>()
    var prevAt = 0L
    // v0.28.4: the card's start is the turn's real start and a finished card's end is frozen (WorkTime).
    fun flush(live: Boolean, next: ChatMessage?) {
        if (run.isEmpty()) return
        // v0.41.0 Simple mode: a finished turn shows only its answer, no "Done · Details" card.
        if (!live && hideDoneSteps) { run = mutableListOf(); return }
        val end = WorkTime.end(run, live, next?.at ?: 0L, next != null && next.role != Role.USER, 0L)
        out += Item.Steps(run, live, end, WorkTime.start(run, prevAt)); run = mutableListOf()
    }
    messages.forEach { m -> if (m.role == Role.STEP) run += m else { flush(false, m); out += Item.Msg(m); prevAt = m.at } }
    flush(working, null)
    Bubbles.positions(out.map { it.side }, out.map { it.at }, whos = out.map { it.who }).forEachIndexed { i, p -> out[i].pos = p }
    return out
}

// v0.45.0 (#10): stabilize() is folded into ItemBuilder (only the rebuilt tail is compared with the rows before).

@Composable
fun ChatScreen() {
    val c = LocalBlaze.current
    // Only "is the chat empty?" lives up here, so streamed words don't redraw the whole screen.
    val hasMessages by remember { Agent.messages.map { it.isNotEmpty() }.distinctUntilChanged() }
        .collectAsState(Agent.messages.value.isNotEmpty())
    val mood by Agent.mood.collectAsState()
    // v0.45.8: the first frame that shows the working state, timed from the Send tap (StartTrace "Send → working").
    LaunchedEffect(mood) { if (mood != Mood.IDLE) androidx.compose.runtime.withFrameNanos { com.blaze.assistant.agent.StartTrace.visible() } }
    val ctx = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current   // v0.45.2: long-press light / dark
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    // 0.48.0 RC4: the new Blaze look is the only chat surface: chat happens over the blurred Home (the composer), the old
    // full chat screen (Chat / Projects pills, themed scenes, Hold to talk / Type / Photo, draft cards) is never drawn.
    val composer = com.blaze.assistant.ui.composer.rememberComposerState()

    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    // 0.52.3 first run (space/SpaceOnboarding): logo + tagline, sign-in when needed, then the one setup screen. People who
    // already set ARGUS up never see it.
    var onboard by rememberSaveable { mutableStateOf(com.blaze.assistant.ui.space.SpaceOnboarding.show(Settings.bool("onboarded", false), Settings.ready, hasMessages)) }
    var obStep by rememberSaveable { mutableStateOf(com.blaze.assistant.ui.space.SpaceOnboarding.first().name) }
    val galleryOpen by GalleryUi.open.collectAsState()
    LaunchedEffect(Unit) { if (!onboard) Settings.setBool("onboarded", true) }
    // 0.52.3: no dependency gate any more; what has to be downloaded is set up by itself (setup/SetupRunner) and the
    // Home's "Finishing setup" card shows what is left.
    val setupState by com.blaze.assistant.setup.SetupRunner.state.collectAsState()
    var setupSheet by rememberSaveable { mutableStateOf(false) }
    // Blaze opening one of its own settings pages (setup help).
    val askedPage by com.blaze.assistant.agent.SelfSetup.openPage.collectAsState()
    LaunchedEffect(askedPage) { askedPage?.let { settingsPage = it; com.blaze.assistant.agent.SelfSetup.openPage.value = null } }
    // v0.24.0 /clear: when the last clear happened (0 = no undo bar).
    var clearedAt by remember { mutableStateOf(0L) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    // v0.39.0 Simple mode: who the next message goes to (@blaze / @halo / @all), typed or spoken.
    var talkTo by rememberSaveable { mutableStateOf("blaze") }
    // v0.45.28: "Halo x Blaze" is no longer a pick (it joins in by itself): a restored "all" becomes Blaze.
    if (talkTo != TalkArcMath.normalize(talkTo)) talkTo = TalkArcMath.normalize(talkTo)
    // v0.43.0: the Blaze / Halo / All arc, opened from the header orb (replaces the "Talk to" pills, in both modes).
    var arcOpen by remember { mutableStateOf(false) }
    var quote by rememberSaveable { mutableStateOf("") }
    // v0.45.28: a link tapped in a reply opens in Home's glass window (GlassWindow), over the chat.
    var chatGlassUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val openLink: (String) -> Unit = { u ->
        if (ChatLinks.inGlass(u)) chatGlassUrl = u
        else try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
    }
    // v0.33.3: a card inside a reply (an app tile's long-press) can fill the quote box.
    val cardQuote by ChatQuote.request.collectAsState()
    LaunchedEffect(cardQuote) { if (cardQuote.isNotBlank()) { quote = cardQuote; ChatQuote.request.value = "" } }

    var pending by remember { mutableStateOf(listOf<Attachment>()) }
    var loadingFiles by remember { mutableStateOf(false) }
    fun addUris(uris: List<Uri>, mimeHint: String = "") {
        if (uris.isEmpty()) return
        loadingFiles = true
        scope.launch {
            val loaded = uris.take(5).mapNotNull { Attachments.load(ctx, it, mimeHint) }
            if (loaded.size < uris.take(5).size) Toast.makeText(ctx, "Some files couldn't be read (photos, PDFs and text work).", Toast.LENGTH_SHORT).show()
            pending = pending + loaded
            loadingFiles = false
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addUris(it) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { addUris(it) }
    // v0.28.x camera-fix: permission → camera app (explicit grants) → Blaze's own camera → photo picker (ui/CameraCapture.kt).
    val cameraCapture = rememberCameraCapture(onPhoto = { addUris(listOf(it), "image/jpeg") },
        onPickPhoto = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
    val hasCamera = remember { com.blaze.assistant.device.CameraSupport.hasCamera(ctx) }
    cameraCapture.dialogs()

    // An image on the clipboard shows a "Paste image" chip.
    val sysClip = remember { ctx.getSystemService(android.content.ClipboardManager::class.java) }
    var clipHasImage by remember { mutableStateOf(false) }
    fun checkClip() {
        clipHasImage = try { sysClip.primaryClipDescription?.let { d -> (0 until d.mimeTypeCount).any { d.getMimeType(it).startsWith("image/") } } == true } catch (_: Exception) { false }
    }
    DisposableEffect(Unit) {
        val l = android.content.ClipboardManager.OnPrimaryClipChangedListener { checkClip() }
        sysClip.addPrimaryClipChangedListener(l); checkClip()
        onDispose { sysClip.removePrimaryClipChangedListener(l) }
    }
    fun pasteImage() {
        clipHasImage = false
        val clipData = try { sysClip.primaryClip } catch (_: Exception) { null }
        if (clipData == null) { Toast.makeText(ctx, "Android didn't let ${BrandName.NAME} read the clipboard. Copy the image again, then paste.", Toast.LENGTH_SHORT).show(); return }
        val d = clipData.description
        // Some keyboards and the Samsung clipboard don't say the file type when asked, so pass on what the clip declared.
        val hint = (0 until d.mimeTypeCount).map { d.getMimeType(it) }.firstOrNull { it.startsWith("image/") }.orEmpty()
        val uris = (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).uri }
        if (uris.isEmpty()) { Toast.makeText(ctx, "That image can't be pasted here.", Toast.LENGTH_SHORT).show(); return }
        addUris(uris, hint)
    }

    var a11yOn by remember { mutableStateOf(BlazeAccessibilityService.isEnabled(ctx)) }
    var control by remember { mutableStateOf(com.blaze.assistant.phone.PhoneControl.state(ctx)) }
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                a11yOn = BlazeAccessibilityService.isEnabled(ctx)
                control = com.blaze.assistant.phone.PhoneControl.state(ctx).also { com.blaze.assistant.phone.PhoneControl.note(it) }
                delay(2500)
            }
        }
    }

    // v0.24.1: the composer's rotating placeholder, rebuilt from on-device data each time Blaze comes to the front.
    var hints by remember { mutableStateOf(emptyList<String>()) }
    var reducedMotion by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            reducedMotion = ComposerHintsSource.reducedMotion(ctx)
            hints = try { ComposerHintsSource.load(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
        }
    }

    // Shared from another app: lands in the message box for the user to send, never runs by itself
    // (any app can share text, so it could carry instructions the user never wrote).
    val shared by Agent.sharedDraft.collectAsState()
    LaunchedEffect(shared) {
        val d = shared ?: return@LaunchedEffect
        Agent.sharedDraft.value = null
        com.blaze.assistant.ui.space.SpaceNav.showChat()   // 0.52.3: it waits in the chat's message box (the shell switches to Chat)
        if (d.text.isNotBlank()) input = TextFieldValue(d.text, androidx.compose.ui.text.TextRange(d.text.length))
        d.attachment?.let { pending = pending + it }
    }

    val voiceState by Voice.state.collectAsState()
    val listenReq by Voice.listenRequests.collectAsState()
    fun startVoice() {
        Voice.listen(onText = { if (callWords.matches(it.trim())) Voice.callRequests.value++ else if (it.isNotBlank())
            Agent.send(com.blaze.assistant.agent.SimpleHome.tagged(it, talkTo), byVoice = true) },
            onError = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() })
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startVoice() else Toast.makeText(ctx, "${BrandName.NAME} needs the microphone to hear you.", Toast.LENGTH_SHORT).show()
    }
    fun onMic() {
        when {
            // v0.45.28: a tap while listening stops and sends what was heard (it used to throw it away).
            voiceState == Voice.State.LISTENING -> Voice.stopByTap()
            voiceState == Voice.State.SPEAKING -> Voice.stopSpeaking()
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startVoice()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // 0.52.4: never on its own at start. Only a NEW listen request (tile, notification, wake word, "voice" action) opens
    // the mic; a count left over from earlier in the process is marked handled and ignored.
    LaunchedEffect(listenReq) {
        val fresh = listenReq > Voice.listenHandled
        Voice.listenHandled = listenReq
        if (fresh && listenReq > 0 && mood == Mood.IDLE) onMic()
    }

    // Call Blaze: full-screen voice call.
    var inCall by remember { mutableStateOf(false) }
    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) inCall = true else Toast.makeText(ctx, "${BrandName.NAME} needs the microphone for calls.", Toast.LENGTH_SHORT).show()
    }
    fun startCall() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) inCall = true
        else callPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val callReq by Voice.callRequests.collectAsState()
    LaunchedEffect(callReq) { if (callReq > 0) startCall() }

    // ---- 0.12 shell state ----
    // v0.28.3: Chat | Projects and where you are in Projects (list → project → project chat), as one ChatNav.
    // Main Chat always shows the main thread; project chats open inside Projects and never replace it.
    // v0.29.0: Home joins the pill; the last tab (Home or Chat) is remembered, Chat on first run.
    // 0.52.3 (Hark design): the app opens on Home (Home | Chat | Projects in the top bar's pill).
    var navS by rememberSaveable { mutableStateOf(ChatNav(tab = ChatNav.HOME).encode()) }
    val nav = ChatNav.decode(navS)
    fun navNow(): ChatNav = ChatNav.decode(navS)
    fun go(n: ChatNav) { navS = n.encode() }
    val shellMode = nav.tab
    LaunchedEffect(nav.tab) { Settings.setStr("shell_tab", nav.tab) }
    var showViews by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    // v0.39.0 Simple mode: who the next message goes to (@blaze / @halo / @all) and whether the keyboard composer is out.
    val simpleMode by com.blaze.assistant.agent.SimpleMode.simpleFlow.collectAsState()
    // 0.52.3: Home (space/SpaceHome.kt) is the root tab; the old Home under a swipe-up chat sheet is
    // gone, so the Swipe setting no longer applies (HomeRoute keeps its rule for the RC2 redesign behind HomeRedesign).
    val swipeMode = HomeRoute.swipe(false, HomeRedesign.enabled)
    var typing by rememberSaveable { mutableStateOf(false) }
    var avatarMenu by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }
    val showing by Views.showing.collectAsState()
    val designTarget by Views.designTarget.collectAsState()
    val viewOffer by Views.offer.collectAsState()
    val work by com.blaze.assistant.agent.Modes.work.collectAsState()
    val approval by com.blaze.assistant.agent.Modes.approval.collectAsState()
    val bgAgents by com.blaze.assistant.agent.BgAgents.all.collectAsState()
    val account by com.blaze.assistant.connect.BlazeAccount.view.collectAsState()
    val projects by com.blaze.assistant.agent.Projects.all.collectAsState()
    val allChats by Agent.chats.collectAsState()
    val busyChats by Agent.busyChats.collectAsState()
    val question by Agent.pendingQuestion.collectAsState()
    val currentChat by Agent.chatId.collectAsState()
    val liveStatus by Agent.status.collectAsState()
    val routerState by com.blaze.assistant.agent.BlazeRouter.state.collectAsState()

    fun exitView() { Views.showing.value = "" }
    fun openView(v: BlazeView, target: String = "") {
        showViews = false; showSearch = false; plusOpen = false; avatarMenu = false
        when (v.special) {
            "gallery" -> GalleryUi.open.value = true
            "home" -> homeRequests.value++
            "projects" -> go(navNow().projectsList())
            "browser" -> com.blaze.assistant.browser.BrowserTools.show(ctx)
            "phonebrowser" -> com.blaze.assistant.browser.phone.PhoneBrowserActivity.open(ctx, control = false)
            "terminal" -> com.blaze.assistant.terminal.BlazeTerminalActivity.open(ctx)
            "music" -> com.blaze.assistant.mediaapps.MusicActivity.open(ctx, target)
            "video" -> com.blaze.assistant.mediaapps.VideoActivity.open(ctx, target)
            "design" -> {
                if (target.isNotBlank()) Views.designTarget.value = target
                else if (Views.designTarget.value.isBlank()) try { com.blaze.assistant.agent.Designs.current()?.path?.let { Views.designTarget.value = it } } catch (_: Exception) { }
                go(navNow().switchTab(ChatNav.CHAT)); Views.showing.value = "design"
            }
            else -> settingsPage = v.page ?: ""
        }
    }
    val viewReq by Views.request.collectAsState()
    LaunchedEffect(viewReq) {
        val r = viewReq ?: return@LaunchedEffect
        Views.request.value = null
        Views.find(r.id)?.let { openView(it, r.target) }
    }

    fun clearScreen() {
        when (Agent.clearScreen()) {
            com.blaze.assistant.agent.ClearScreen.Result.BUSY -> Toast.makeText(ctx, "${BrandName.NAME} is working. Stop it first, then /clear.", Toast.LENGTH_SHORT).show()
            com.blaze.assistant.agent.ClearScreen.Result.EMPTY -> {}
            com.blaze.assistant.agent.ClearScreen.Result.CLEARED -> { quote = ""; exitView(); clearedAt = System.currentTimeMillis() }
        }
    }
    fun send(raw: String) {
        // v0.45.8: the reply's start is timed from this tap (StartTrace, monotonic).
        com.blaze.assistant.agent.StartTrace.tap(force = true)
        val text = raw.trim()
        if (com.blaze.assistant.agent.ClearScreen.isCommand(text)) { input = TextFieldValue(""); clearScreen(); return }
        if (callWords.matches(text)) { input = TextFieldValue(""); startCall(); return }
        val cmdName = text.removePrefix("/").substringBefore(' ').lowercase()
        if (text.startsWith("/") && !text.startsWith("//")) {
            if (cmdName == "views") { input = TextFieldValue(""); showViews = true; return }
            if (cmdName == "search") { input = TextFieldValue(""); showSearch = true; return }
            if (cmdName == "projects") { input = TextFieldValue(""); go(navNow().projectsList()); return }
            if (cmdName in setOf("jobs", "schedule", "accounts", "doctor", "design", "router", "skills", "gallery", "browser", "terminal", "music", "video", "doing", "look", "home", "sync") && !text.contains(' ')) {
                input = TextFieldValue(""); Views.find(cmdName)?.let { openView(it) }; return
            }
        }
        val cmd = CommandRunner.run(ctx, text, openSettings = { settingsPage = it }, listen = { onMic() })
        if (cmd == "") { input = TextFieldValue(""); return }
        val body = if (cmd == null) com.blaze.assistant.agent.SimpleHome.tagged(text, talkTo) else cmd
        val full = if (quote.isNotBlank()) quote.lines().joinToString("\n") { "> $it" } + "\n\n" + body else body
        if (full.isBlank() && pending.isEmpty()) return
        // v0.30.6 rapid messages: no "busy" check; a message sent while Blaze works joins the turn (Agent.send).
        clearedAt = 0L
        // v0.28.3: a new project chat joins its project with its first message (only from its own screen in Projects).
        val n = navNow()
        if (n.inProjectChat && Agent.chatId.value == n.chat && com.blaze.assistant.agent.Projects.of(n.chat) == null) com.blaze.assistant.agent.Projects.assign(n.chat, n.project)
        Agent.send(full, attachments = pending)
        pending = emptyList(); quote = ""; input = TextFieldValue("")
    }
    // Selection toolbar state.
    var tbRect by remember { mutableStateOf<Rect?>(null) }
    var tbCopy by remember { mutableStateOf<(() -> Unit)?>(null) }
    var tbAll by remember { mutableStateOf<(() -> Unit)?>(null) }
    val toolbar = remember { BlazeTextToolbar({ r, cp, all -> tbRect = r; tbCopy = cp; tbAll = all }, { tbRect = null }) }
    // v0.27.2: selected words reach Search / Ask / Share without overwriting the phone's clipboard.
    val capClip = remember(clip) { CaptureClipboard(clip) }
    // v0.27.2: what each selection-pill action does in the chat. Every action but Select all ends the selection.
    fun askNow(prompt: String) {
        input = TextFieldValue(prompt, TextRange(prompt.length))
        send(prompt)   // v0.30.6: joins the turn when Blaze is working
    }
    fun onSelection(a: SelectionActions.Action) {
        val copy = tbCopy
        when (a) {
            SelectionActions.Action.COPY -> { copy?.invoke(); toolbar.hide() }
            SelectionActions.Action.SELECT_ALL -> tbAll?.invoke()
            else -> {
                val t = capClip.capture(copy).trim()
                toolbar.hide()
                if (t.isBlank()) return
                when (SelectionActions.resolve(a, t)) {
                    SelectionActions.Action.QUOTE -> quote = t
                    SelectionActions.Action.SEARCH, SelectionActions.Action.OPEN_LINK -> SelectionDo.openInBlazeBrowser(ctx, t)
                    SelectionActions.Action.ASK -> askNow(SelectionActions.askPrompt(t))
                    SelectionActions.Action.TRANSLATE -> askNow(SelectionActions.translatePrompt(t, SelectionDo.phoneLanguage()))
                    SelectionActions.Action.DEFINE -> askNow(SelectionActions.definePrompt(t))
                    SelectionActions.Action.READ_ALOUD -> SelectionDo.readAloud(ctx, t)
                    SelectionActions.Action.SHARE -> SelectionDo.share(ctx, t)
                    else -> {}
                }
            }
        }
    }
    // v0.28.3: project chats open inside Projects (a project chat screen), never in main Chat.
    fun newProjectChat(projectId: String) {
        if (projectId.isBlank()) return
        quote = ""; plusOpen = false
        go(navNow().openChat(projectId, Agent.newChatId()))
    }
    fun openProjectChat(id: String) {
        // One mind: only the main thread and project threads open (v0.24.0: no chat list or archive screen).
        val p = com.blaze.assistant.agent.Projects.of(id)?.id ?: return
        quote = ""; showSearch = false
        go(navNow().openChat(p, id))
    }
    fun deleteProjectChat(id: String) {
        go(navNow().chatDeleted(id))
        Agent.deleteChat(id)
    }
    // The shell drives which thread the agent shows (main on Chat, the open project chat in Projects)…
    LaunchedEffect(nav.visibleChat) {
        Agent.showChat(nav.visibleChat)
        com.blaze.assistant.agent.Projects.current.value = if (nav.inProjectChat) nav.project else ""
    }
    // …and follows when something else shows a chat (a notification, the secure form card): a project chat opens in Projects.
    LaunchedEffect(currentChat) {
        val n = navNow()
        val f = n.follow(currentChat) { com.blaze.assistant.agent.Projects.of(it)?.id }
        if (f != n) go(f)
    }


    if (inCall) { SideEffect { BackRule.shellNow = { BackRule.Shell(inCall = true) } }; CallScreen(onHangUp = { inCall = false }); return }
    val me = account.user
    // v0.24.4 private chat: confirm before leaving (the chat is thrown away).
    val isPrivate by Agent.privateChat.collectAsState()
    var confirmLeavePrivate by remember { mutableStateOf(false) }
    // ---- 0.48.0 RC4: the composer over Home does everything the old chat screen did ----
    // A message from the bar takes the old screen's send path (slash commands, /clear, call words, quotes, project chats).
    composer.sender = { t, files -> pending = files; send(t) }
    composer.privateChat = isPrivate
    composer.onClosePrivate = { confirmLeavePrivate = true }
    composer.title = if (nav.inProjectChat) listOfNotNull(projects.firstOrNull { it.id == nav.project }?.name?.ifBlank { null },
        allChats.firstOrNull { it.id == nav.chat }?.title?.ifBlank { null }).joinToString(" \u00b7 ").ifBlank { "Project chat" } else ""
    // The old + menu and top bar, from Spotlight rows (SelfMap actions) and the profile avatar.
    composer.hostAction = { id ->
        when (id) {
            "camera" -> { cameraCapture.open(); true }
            "blazecam" -> { com.blaze.assistant.device.BlazeCamera.open(ctx, null, com.blaze.assistant.device.CameraModes.Origin.CHAT_PLUS, Agent.chatId.value); true }
            "photos" -> { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)); true }
            "files" -> { files.launch(arrayOf("image/*", "application/pdf", "text/*")); true }
            "paste" -> { pasteImage(); true }
            "voice" -> { onMic(); true }
            "call" -> { startCall(); true }
            "plan" -> { com.blaze.assistant.agent.Modes.toggleWork()
                Toast.makeText(ctx, if (com.blaze.assistant.agent.Modes.plan) "Plan mode on: ${BrandName.NAME} plans before it acts" else "Plan mode off", Toast.LENGTH_SHORT).show(); true }
            "approval" -> { com.blaze.assistant.agent.Modes.toggleApproval()
                Toast.makeText(ctx, if (com.blaze.assistant.agent.Modes.autopilot) "Autopilot: ${BrandName.NAME} acts, you can undo" else "${BrandName.NAME} asks before it acts", Toast.LENGTH_SHORT).show(); true }
            "clear" -> { clearScreen(); true }
            "views" -> { composer.openSpotlight(); composer.query = "@"; true }
            "profile" -> { settingsPage = ""; true }
            else -> false
        }
    }
    // 0.52.3: asking for the chat (Spotlight's Chat / history / an ask) or a private chat shows the Chat tab.
    LaunchedEffect(composer.chatOpen) { if (composer.chatOpen) { composer.dismissChat(); if (!navNow().inProjectChat) go(navNow().switchTab(ChatNav.CHAT)) } }
    LaunchedEffect(isPrivate) { if (isPrivate && !navNow().inProjectChat) go(navNow().switchTab(ChatNav.CHAT)) }
    val chatAsk by com.blaze.assistant.ui.space.SpaceNav.chatRequests.collectAsState()
    LaunchedEffect(chatAsk) { if (chatAsk > 0 && !navNow().inProjectChat) go(navNow().switchTab(ChatNav.CHAT)) }
    // v0.28.3: the thread + composer show on main Chat and on a project chat screen; views (Design…) belong to main Chat.
    val chatBody = nav.tab == ChatNav.CHAT || nav.inProjectChat
    // 0.52.3: the floating composer is on Home too (Hark's "Message ARGUS"); a message from Home opens the Chat tab.
    val composerShown = chatBody || nav.tab == ChatNav.HOME
    // 0.46.0 Blaze Stage: the main chat is one playground (heroes, widgets, a movable chat, top / reply bars that can hide).
    val stage by com.blaze.assistant.stage.StageStore.state.collectAsState()
    val stageActive = com.blaze.assistant.stage.StageStore.enabled && nav.tab == ChatNav.CHAT && !nav.inProjectChat && showing.isEmpty()
    val stageLast by remember { Agent.messages.map { l -> l.lastOrNull { it.role == Role.BLAZE || it.role == Role.USER }?.text.orEmpty().take(140) }.distinctUntilChanged() }
        .collectAsState("")
    val viewShowing = if (nav.inProjectChat) "" else showing
    // ---- v0.39.0 Swipe navigation (Settings › Navigation, independent of Simple / Advanced): Home underneath, Chat slides up over it (SwipeNav). ----
    val density = LocalDensity.current.density
    var sheetH by remember { mutableFloatStateOf(1f) }
    var sheetP by rememberSaveable { mutableFloatStateOf(if (com.blaze.assistant.agent.SwipeNav.startSurface(swipeMode, !onboard && Settings.bool("onboarded", false)) == com.blaze.assistant.agent.SwipeNav.HOME) 0f else 1f) }
    val sheetJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    var dragFrom by remember { mutableFloatStateOf(1f) }
    fun settleTo(t0: Float, velocity: Float = 0f) {
        val t = t0
        sheetJob[0]?.cancel()
        sheetJob[0] = scope.launch {
            androidx.compose.animation.core.animate(sheetP, t, velocity, androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 380f)) { x, _ -> sheetP = x }
        }
    }
    val sheetDrag = androidx.compose.foundation.gestures.rememberDraggableState { d -> sheetJob[0]?.cancel(); sheetP = com.blaze.assistant.agent.SwipeNav.drag(sheetP, d, sheetH) }
    val onSheetStart: suspend kotlinx.coroutines.CoroutineScope.(Offset) -> Unit = { dragFrom = if (sheetP >= 0.5f) 1f else 0f }
    val onSheetStop: suspend kotlinx.coroutines.CoroutineScope.(Float) -> Unit = { v ->
        settleTo(com.blaze.assistant.agent.SwipeNav.settle(sheetP, v / density, dragFrom), -v / sheetH.coerceAtLeast(1f))
    }
    val p = if (swipeMode) sheetP else 1f
    // Swipe has no Home tab: a remembered Home tab from Classic tabs becomes the Home surface.
    LaunchedEffect(swipeMode) { if (swipeMode && navNow().tab == ChatNav.HOME) { go(navNow().switchTab(ChatNav.CHAT)); settleTo(0f) } }
    // Things that need the chat bring it up: a question, an approval, a fix card, the computer card, a shared text, the mic.
    val pendingConfirmNow by Agent.pendingConfirm.collectAsState()
    val fixNow by com.blaze.assistant.agent.FixCards.current.collectAsState()
    val formNow by Agent.pendingForm.collectAsState()
    val secretNow by com.blaze.assistant.agent.SecretDesk.pending.collectAsState()
    LaunchedEffect(question, pendingConfirmNow, fixNow, listenReq, formNow, secretNow) {
        if (swipeMode && (question != null || pendingConfirmNow != null || fixNow != null || listenReq > 0) && sheetP < 1f) settleTo(1f)
        // 0.52.3: what ARGUS waits on is answered in the chat, so Home (or Projects) gives way to it
        if ((question != null || pendingConfirmNow != null || fixNow != null || formNow != null || secretNow != null) && !navNow().inProjectChat && navNow().tab != ChatNav.CHAT)
            go(navNow().switchTab(ChatNav.CHAT))
    }
    val homeReq by homeRequests.collectAsState()
    LaunchedEffect(homeReq) {
        if (homeReq > 0) {
            // 0.52.3: every route home (open_view home, /home, the Home tab, Back out of Music / Video / Browser...) shows Home
            settingsPage = null; showViews = false; plusOpen = false; avatarMenu = false; showSearch = false; Views.showing.value = ""
            composer.closeSpotlight()
            if (!swipeMode) go(navNow().switchTab(ChatNav.HOME)) else {
                // RC2: every route home (open_view home, /home, Back out of Music / Video / Browser...) lands on the new Home, views closed
                settingsPage = null; showViews = false; plusOpen = false; arcOpen = false; avatarMenu = false; showSearch = false
                GalleryUi.open.value = false; Views.showing.value = ""
                val n = navNow(); if (n.tab != ChatNav.CHAT || n.inProjectChat) go(n.switchTab(ChatNav.CHAT))
                composer.closeSpotlight()
                settleTo(0f)
            }
        }
    }
    // Awareness: which surface is in front (Home or Chat).
    val surface by remember(swipeMode) { derivedStateOf { if (!swipeMode) (if (navNow().tab == ChatNav.HOME) "home" else "chat") else com.blaze.assistant.agent.SwipeNav.surfaceOf(sheetP) } }
    LaunchedEffect(surface) { try { com.blaze.assistant.agent.Awareness.set(com.blaze.assistant.agent.Awareness.Now("surface", "Showing", com.blaze.assistant.agent.SwipeNav.awarenessLine(surface), "${BrandName.NAME}")) } catch (_: Throwable) { } }
    // 0.45.19 fix 7: Back returns to the chat from everything (BackRule); the chat is the root, so Back from Chat no
    // longer goes down to Home (that made Home and Chat a loop Back could never leave). The shell publishes what is
    // in front and how to close it; BackRule's window interceptor acts on it before any nested BackHandler.
    fun backToChat() {
        // 0.52.3: Home is the root. Back closes what is over the current tab (a page, a sheet, Spotlight, a view); with
        // nothing over it, a project chat goes to its project, Projects to its list, and Chat / Projects to Home.
        val over = settingsPage != null || showViews || plusOpen || arcOpen || avatarMenu || showSearch || GalleryUi.open.value ||
            Agent.roomOpen.value >= 0L || Views.showing.value.isNotEmpty() || composer.spotlightOpen
        composer.closeSpotlight()
        settingsPage = null; showViews = false; plusOpen = false; arcOpen = false; avatarMenu = false; showSearch = false
        GalleryUi.open.value = false; Agent.roomOpen.value = -1L
        Views.showing.value = ""
        if (over) return
        val n = navNow()
        val up = n.back()
        if ((n.inProjectChat || n.tab == ChatNav.PROJECTS) && up != null && up.tab == ChatNav.PROJECTS) go(up)
        else if (!Agent.privateChat.value) go(n.switchTab(ChatNav.HOME))
    }
    SideEffect {
        // Read when Back is pressed (State reads, never a stale snapshot of this composition).
        BackRule.shellNow = {
            val n = navNow(); val swipe = HomeRoute.swipe(false, HomeRedesign.enabled)
            BackRule.Shell(onboarding = onboard, gate = setupSheet, inCall = false,
                tab = n.tab, inProjectChat = n.inProjectChat, swipeHome = swipe && sheetP < 0.5f,
                settingsPage = settingsPage, view = if (n.inProjectChat) "" else Views.showing.value,
                viewsSheet = showViews, plusMenu = plusOpen || composer.spotlightOpen, arc = arcOpen, avatarMenu = avatarMenu, search = showSearch,
                gallery = GalleryUi.open.value, room = Agent.roomOpen.value >= 0,
                // 0.52.3: Home is where Back stops (a private chat asks before leaving itself)
                homeRoot = !Agent.privateChat.value)
        }
        BackRule.toChat = { backToChat() }
    }
    BackHandler(enabled = isPrivate && !confirmLeavePrivate) { confirmLeavePrivate = true }
    // v0.28.3: back inside Projects: project chat → project page → projects list (then the system's back).
    BackHandler(enabled = !isPrivate && nav.back() != null) { navNow().back()?.let { go(it) } }
    Box(Modifier.fillMaxSize().testTag(com.blaze.assistant.ui.space.SpaceTags.ROOT).onSizeChanged { sheetH = it.height.toFloat() }) {
        // 0.52.3: the space view (dark) or the light ARGUS ground behind every tab. Not while the first-run onboarding is up:
        // it draws its own opaque space view over everything, so a second full-screen backdrop (and image draw) under it
        // only added first-frame work (CI fix, low-RAM smoke cold start).
        if (!onboard) com.blaze.assistant.ui.space.SpaceBackdrop()
        // 0.52.3 CI fix (low-RAM smoke): while the first-run onboarding is up it covers everything with its own opaque space
        // view, so the shell under it (Home, top bar, chat, Projects, Spotlight) is not composed at all. Composing it anyway
        // cost ~636 ms of first-frame layout on the low-RAM emulator. It composes the moment onboarding ends.
        if (!onboard) {
        // v0.39.0 Simple mode: Home (the Home cards) underneath, with the grab handle; Chat slides over it.
        if (swipeMode && p < 1f) {
            CompositionLocalProvider(LocalOpenLink provides openLink) {
            HomeSurface(progress = p, onAsk = { t -> Agent.send(t); settleTo(1f) }, onQuote = { t -> quote = t; settleTo(1f) },
                onOpen = { settleTo(1f) }, drag = sheetDrag, onDragStart = onSheetStart, onDragStop = onSheetStop,
                composer = composer, onProfile = { avatarMenu = true }, swipeToChat = true)
            }
        }
        // The full chat sheet: top pills, thread, Hold to talk / Type / Photo.
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = (1f - p) * sheetH
                }
            .then(if (swipeMode && p < 1f) Modifier.background(c.paper).glassBackground(c.dark) else Modifier)) {
        // v0.45.8: Design is its own full-screen view: no chat top bar over its canvas.
        val designFull = DesignNav.fullScreen(showing, nav)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // v0.39.0 Simple mode: pull down from the top (the bar above the header, or the header itself) for Home.
            // 0.50.0: the chat box's expand arrows maximise the chat (no top bar, no pull handle); Back restores first.
            val maxed = ChatExpand.on && chatBody
            if (maxed) BackHandler { ChatExpand.restore() }
            if (swipeMode && !designFull && !maxed) Box(Modifier.fillMaxWidth().height(14.dp).draggable(sheetDrag, Orientation.Vertical, onDragStarted = onSheetStart, onDragStopped = onSheetStop)
                .semantics { contentDescription = "Pull down for Home" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.hair))
            }
            if (!designFull && !maxed && (stage.topBar || !stageActive)) Box(if (swipeMode) Modifier.draggable(sheetDrag, Orientation.Vertical, onDragStarted = onSheetStart, onDragStopped = onSheetStop) else Modifier) {
            // 0.52.3 (Hark design): round button (+ on Home, search in Chat, the menu in Projects), the Home | Chat | Projects
            // pill, the avatar (profile and settings; long-press = light / dark). A view (Gallery, Design…) shows an Exit chip.
            com.blaze.assistant.ui.space.SpaceTopBar(tab = shellMode,
                onTab = { m -> plusOpen = false; showViews = false; exitView(); composer.closeSpotlight(); go(navNow().switchTab(m)) },
                onLeft = {
                    when (com.blaze.assistant.ui.space.SpaceTabs.leftButton(shellMode)) {
                        com.blaze.assistant.ui.space.SpaceTabs.Left.MENU -> go(navNow().projectsList())
                        else -> { composer.query = ""; composer.openSpotlight() }
                    }
                },
                initial = com.blaze.assistant.ui.space.SpaceTabs.initial(me?.name ?: Settings.userName, me?.email),
                onAvatar = { settingsPage = "" },
                // v0.45.2: long-press the profile icon = light / dark, with a haptic tick and a short note.
                onAvatarLong = {
                    val (m, note) = LightDark.toggle(c.dark)
                    ThemeMode.set(m)
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    Toast.makeText(ctx, note, Toast.LENGTH_SHORT).show()
                },
                privateChat = isPrivate, onLeavePrivate = { confirmLeavePrivate = true })
            }
            if (!designFull && !maxed && showing.isNotEmpty() && nav.tab == ChatNav.CHAT) com.blaze.assistant.ui.space.SpaceChip(
                (Views.find(showing)?.name ?: showing) + (if (showing == "design") " \u00b7 $DESIGN_BETA" else "") + "  \u2715", { exitView() },
                modifier = Modifier.padding(start = com.blaze.assistant.ui.space.SpaceInsets.GUTTER_DP.dp, bottom = 6.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // v0.27.0: Chat and Projects cross-fade when you switch tabs.
                // v0.28.3: three panes: main Chat, the Projects list / a project page, and a project chat screen.
                val pane = when { nav.inProjectChat -> "pchat"; nav.tab == ChatNav.PROJECTS -> "projects"; nav.tab == ChatNav.HOME -> "home"; else -> "chat" }
                androidx.compose.animation.Crossfade(pane, modifier = Modifier.fillMaxSize(),
                    animationSpec = MotionTokens.medium(), label = "tab") { paneNow ->
                if (paneNow == "home") {
                    // 0.52.3: the Hark-style Home (space/SpaceHome.kt): cards over the space view.
                    SpaceHomeLayer(name = me?.name?.ifBlank { null } ?: Settings.userName.ifBlank { null }, signedIn = me != null || Settings.ready,
                        mood = mood, projects = projects, setup = setupState,
                        onAsk = { t -> send(t); go(navNow().switchTab(ChatNav.CHAT)) },
                        onOpenChat = { go(navNow().switchTab(ChatNav.CHAT)) },
                        onOpenProject = { go(navNow().openProject(it)) },
                        onOpenProjects = { go(navNow().projectsList()) },
                        onSignIn = { settingsPage = "account" },
                        onSetup = { setupSheet = true })
                } else if (paneNow == "projects") {
                    val needs = remember(question, allChats) { question?.let { q -> allChats.filter { it.title == q.chat }.map { it.id }.toSet() } ?: emptySet() }
                    com.blaze.assistant.ui.space.SpaceProjectsScreen(projects, allChats, busyChats, needs, nav.project,
                        onOpen = { go(navNow().openProject(it)) }, onBack = { navNow().back()?.let { go(it) } },
                        onCreate = { go(navNow().openProject(com.blaze.assistant.agent.Projects.create(it).id)) },
                        onChat = { openProjectChat(it) }, onNewChat = { newProjectChat(it) },
                        onInstructions = { id, t -> com.blaze.assistant.agent.Projects.setInstructions(id, t) },
                        onBrief = { id, t -> com.blaze.assistant.agent.Projects.setBrief(id, t) },
                        onDelete = { com.blaze.assistant.agent.Projects.delete(it); go(navNow().projectDeleted(it)) },
                        onRenameChat = { id, t -> Agent.renameChat(id, t) },
                        onDeleteChat = { deleteProjectChat(it) })
                    LaunchedEffect(Unit) { Agent.refreshChats() }
                } else if (paneNow == "pchat") {
                    val n = nav
                    val proj = projects.firstOrNull { it.id == n.project }
                    val meta = allChats.firstOrNull { it.id == n.chat }
                    Column(Modifier.fillMaxSize()) {
                        ProjectChatHeader(proj?.name.orEmpty(), meta?.title.orEmpty(),
                            onBack = { navNow().back()?.let { go(it) } },
                            onRename = { t -> Agent.renameChat(n.chat, t) },
                            onDelete = { deleteProjectChat(n.chat) },
                            onNewChat = { newProjectChat(n.project) },
                            canEdit = meta != null)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // Only this chat's own thread is ever drawn here (never main's while the screens cross-fade).
                            if (n.inProjectChat && currentChat == n.chat) {
                                if (!hasMessages) ProjectChatEmpty(proj?.name.orEmpty(), hasInstructions = proj?.let { it.instructions.isNotBlank() || it.brief.isNotBlank() } == true)
                                else {
                                    val dens = LocalDensity.current
                                    CompositionLocalProvider(LocalTextToolbar provides toolbar, LocalClipboardManager provides capClip, LocalOpenLink provides openLink,
                                        LocalDensity provides androidx.compose.ui.unit.Density(dens.density, dens.fontScale * Settings.textScale)) {
                                        MessageList(mood, onQuote = { quote = it }, onFollowup = { send(it) })
                                    }
                                }
                            }
                        }
                    }
                } else if (nav.chat.isNotBlank() && currentChat == nav.chat) {
                    // Main Chat never draws a project chat's thread (the agent is switching back to main).
                    Box(Modifier.fillMaxSize())
                } else if (showing == "design") {
                    DesignView(designTarget, busy = mood != Mood.IDLE, status = liveStatus,
                        onAsk = { send(it) }, onOpenInBrowser = { u ->
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            catch (_: Exception) { com.blaze.assistant.browser.BrowserTools.show(ctx) } },
                        home = try { com.blaze.assistant.terminal.Terminal.home() } catch (_: Exception) { null },
                        onBack = { Views.showing.value = DesignNav.back(Views.showing.value) })
                    BackHandler { Views.showing.value = DesignNav.back(Views.showing.value) }
                } else if (!hasMessages && !(stageActive && stage.items.isNotEmpty())) {
                    // 0.52.3: the empty chat in the Hark look (greeting card + suggested asks)
                    Welcome(needsSetup = !Settings.ready, onSetup = { settingsPage = "account" }) { send(it) }
                } else {
                    val dens = LocalDensity.current
                    CompositionLocalProvider(LocalTextToolbar provides toolbar, LocalClipboardManager provides capClip, LocalOpenLink provides openLink,
                        LocalDensity provides androidx.compose.ui.unit.Density(dens.density, dens.fontScale * Settings.textScale)) {
                        if (stageActive && !stage.isEmpty) {
                            // 0.46.0: back steps toward the normal chat (hero -> widget, then the bars and chat come back).
                            if (!stage.isNormal) BackHandler { com.blaze.assistant.stage.StageStore.back() }
                            StageHost(stageLast) { m -> Box(m) { if (hasMessages) MessageList(mood, onQuote = { quote = it }, onFollowup = { send(it) }) } }
                        } else MessageList(mood,
                            onQuote = { quote = it },
                            onFollowup = { send(it) })
                    }
                }
                }
                if (clearedAt > 0L && chatBody) {
                    LaunchedEffect(clearedAt) { delay(com.blaze.assistant.agent.ClearScreen.UNDO_MS - 3_000); clearedAt = 0L }
                    UndoBar("Chat cleared \u00b7 nothing is forgotten", onUndo = { Agent.undoClear(); clearedAt = 0L }, onDismiss = { clearedAt = 0L })
                }
                // v0.39.0: the 10-second Undo bar for what Blaze just did on Autopilot.
                if (chatBody && clearedAt == 0L) UndoCountdownBar(onUndone = { line -> Agent.note(line) })
                // Dim the chat while a sheet (+ menu, /views) is open, so half-cut lines at its edge read as background.
                if (chatBody && viewShowing.isEmpty() && (plusOpen || showViews || arcOpen)) SheetScrim { plusOpen = false; showViews = false; arcOpen = false }
            }
            if (chatBody && viewShowing.isEmpty()) {
                if (voiceState == Voice.State.LISTENING) ListeningStrip(onStop = { Voice.stopByTap() })
                LaunchedEffect(a11yOn) { if (a11yOn) Settings.setBool("a11y_seen", true) }
                LaunchedEffect(control) { if (control == com.blaze.assistant.phone.PhoneControl.State.CONNECTED) Settings.setBool("a11y_seen", true) }
                // v0.20.0: say which way it dropped and fix it in one tap (Blaze's own switch, not the whole list).
                if (control != com.blaze.assistant.phone.PhoneControl.State.CONNECTED && Settings.phoneControl && Settings.bool("a11y_seen", false))
                    PhoneControlBanner(control) {
                        ComeBack.watch(ctx) { BlazeAccessibilityService.instance != null }
                        try { ctx.startActivity(com.blaze.assistant.phone.PhoneControl.fixIntent(ctx)) }
                        catch (_: Exception) { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                // 0.46.3: the on-phone brain's one-time question before its setup uses mobile data
                val askData by com.blaze.assistant.agent.BackupBrain.askMobile.collectAsState()
                if (askData) BlazeDialog(com.blaze.assistant.agent.BackupNudge.mobileDataQuestion(com.blaze.assistant.agent.BackupBrain.DOWNLOAD_BYTES),
                    "Until then, ${BrandName.NAME} answers online. On Wi-Fi this finishes by itself.",
                    confirm = "Use mobile data", onConfirm = { com.blaze.assistant.agent.BackupBrain.answerMobile(true) },
                    dismiss = "Wait for Wi-Fi", onDismiss = { com.blaze.assistant.agent.BackupBrain.answerMobile(false) })
                // 0.46.3: the one-time phone control explainer (a phone task needed control and it was off).
                val explain by com.blaze.assistant.phone.flash.PhoneExplainer.requested.collectAsState()
                if (explain) PhoneExplainerDialog(onOpenSettings = {
                    ComeBack.watch(ctx) { BlazeAccessibilityService.instance != null }
                    try { ctx.startActivity(com.blaze.assistant.phone.PhoneControl.fixIntent(ctx)) }
                    catch (_: Exception) { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, onClose = { com.blaze.assistant.phone.flash.PhoneExplainer.requested.value = false; Settings.setBool("a11y_seen", true) })
                // v0.45.4: "Blaze X.Y.Z is available" (once a day on app open, dismissible, silent offline).
                if (!ChatExpand.on) UpdateBanner()
            }
            ConfirmCard()
            QuestionCard()
            FixCard()
            SecureFormCard()
            // 0.46.6 Secure form: request_secret's card, and the "looks like a secret" hold before a message is sent.
            SecretRequestCard()
            SecretPasteCard()
            // 0.45.19: Blaze Phone's live tile (frame, site, step, Take control) while Blaze works on its own phone.
            PhoneLiveCard()
            // v0.24.6: "Open in Design?" instead of switching views on its own.
            if (nav.tab == ChatNav.CHAT) viewOffer?.let { o -> if (showing != o.id) ViewOfferChip(Views.offerLabel(o.id),
                onOpen = { Views.offer.value = null; Views.find(o.id)?.let { openView(it, o.target) } }, onDismiss = { Views.offer.value = null }) }
            if (composerShown && viewShowing.isEmpty()) {
                val sugg = remember(input.text) { fileSuggestions(input.text).ifEmpty { CommandRunner.suggest(input.text) } }
                if (sugg.isNotEmpty() && !showViews) SuggestionList(sugg) { s ->
                    if (s.runNow) send(s.fill) else input = TextFieldValue(s.fill, TextRange(s.fill.length))
                }
                if (quote.isNotBlank()) QuoteStrip(quote) { quote = "" }
                if (pending.isNotEmpty() || loadingFiles) AttachmentThumbs(pending, loadingFiles) { a -> pending = pending - a }
                if (clipHasImage && pending.isEmpty() && !plusOpen) Row(Modifier.fillMaxWidth()
                    .padding(start = com.blaze.assistant.ui.space.SpaceInsets.COMPOSER_EDGE_DP.dp, end = com.blaze.assistant.ui.space.SpaceInsets.COMPOSER_EDGE_DP.dp - 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("Image on your clipboard", size = 10.sp, modifier = Modifier.weight(1f))
                    ActionText("Paste image", color = c.accent, size = 10.sp, modifier = Modifier.clickable { pasteImage() }.padding(8.dp))
                    ActionText("×", size = 12.sp, modifier = Modifier.clickable { clipHasImage = false }.padding(8.dp))
                }
                // v0.27.0: sheets slide open and closed (MotionTokens; instant with Remove animations on).
                AnimatedVisibility(showViews, enter = MotionTokens.expand(), exit = MotionTokens.collapse()) {
                    val badges = mapOf("agents" to bgAgents.count { it.live }.let { if (it > 0) "$it LIVE" else "" })
                    ViewsSheet(badges, onPick = { openView(it) }, onDismiss = { showViews = false })
                }
                if (showViews) BackHandler { showViews = false }
                if (plusOpen) BackHandler { plusOpen = false }
                AnimatedVisibility(plusOpen, enter = MotionTokens.expand(), exit = MotionTokens.collapse()) {
                    val model = ""
                    PlusMenu(plusItems(work, approval, model, clipHasImage, nav.inProjectChat, hasCamera).filter { com.blaze.assistant.agent.SimpleMode.plusVisible(it.id, simpleMode) }) { id ->
                        plusOpen = false
                        when (id) {
                            "camera" -> cameraCapture.open()
                            "blazecam" -> if (!com.blaze.assistant.device.BlazeCamera.open(ctx, null, com.blaze.assistant.device.CameraModes.Origin.CHAT_PLUS, Agent.chatId.value))
                                Toast.makeText(ctx, "${BrandName.NAME} Camera couldn't open on this phone. Pick a photo instead.", Toast.LENGTH_SHORT).show() else Unit
                            "photos" -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            "files" -> files.launch(arrayOf("image/*", "application/pdf", "text/*"))
                            "atfile" -> { val t = input.text.let { if (it.isEmpty() || it.endsWith(" ")) it else "$it " } + "@"; input = TextFieldValue(t, TextRange(t.length)) }
                            "paste" -> pasteImage()
                            "voice" -> onMic()
                            "call" -> startCall()
                            "plan" -> com.blaze.assistant.agent.Modes.toggleWork()
                            "approval" -> com.blaze.assistant.agent.Modes.toggleApproval()
                            "model" -> settingsPage = "ai"
                            "views" -> showViews = true
                            "new" -> newProjectChat(navNow().project)
                            "clear" -> clearScreen()
                        }
                    }
                }
                // v0.24.6: pictures straight from the keyboard (Samsung clipboard, GIFs, stickers) attach like picked photos.
                // 0.52.3: always the composer pill (Simple mode's big hold-to-talk bar is gone with the old chat look).
                // v0.43.0: who the next message goes to (@blaze / @halo / @all), picked on the arc from the header orb.
                // 0.51.0 one persona: the Blaze / Halo arc is gone (there is only Blaze).
                if (arcOpen) BackHandler { arcOpen = false }
                // 0.46.0: the reply bar folded into a floating pill by the Stage (never gone): tap for the full bar.
                if (stageActive && stage.reply == com.blaze.assistant.stage.ReplyBar.PILL) Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    StageReplyPill { com.blaze.assistant.stage.StageStore.dispatch(com.blaze.assistant.stage.StageAction.SetChrome(reply = com.blaze.assistant.stage.ReplyBar.FULL)) }
                }
                else AcceptImages(onImage = { uri, mime -> addUris(listOf(uri), mime) }) {
                Composer(input, { input = it; Agent.typing(it.text.isNotEmpty()) }, busy = mood != Mood.IDLE, canSend = input.text.isNotBlank() || pending.isNotEmpty(),
                    plusOpen = plusOpen, chip = modeChip(work, approval), agents = bgAgents.count { it.live },
                    listening = voiceState == Voice.State.LISTENING, speaking = voiceState == Voice.State.SPEAKING,
                    onPlus = { plusOpen = !plusOpen; showViews = false }, onMic = { onMic() },
                    onSend = { val t = input.text; send(t); if (navNow().tab == ChatNav.HOME && t.isNotBlank()) go(navNow().switchTab(ChatNav.CHAT)) },
                    onStop = { Agent.stop() },
                    // 0.52.3 Hark pill: + and the mic / send / stop only (no model picker, no extra icons)
                    onChip = { plusOpen = true }, hints = hints, animateHints = !reducedMotion)
                }
            }
        }
        // 0.52.3: no particles over the thread (the space view is the backdrop now).
        }   // v0.39.0: end of the Chat sheet

        // v0.45.28: the same glass window Home opens links in, for links tapped in chat replies.
        chatGlassUrl?.let { u -> GlassWindow(u, onClose = { chatGlassUrl = null }, onOpenOutside = { openHomeLink(ctx, it) },
            onAsk = { t -> chatGlassUrl = null; send(t) }, onQuote = { t -> chatGlassUrl = null; quote = t }) }

        // v0.27.2: the selection pill, swipe sideways for Search, Ask Blaze, Translate, Define, Read aloud, Share.
        tbRect?.let { r ->
            val d = LocalDensity.current
            val short = with(d) { SelectionActions.likelyShort(r.width / density, r.height / density) }
            val acts = remember(tbAll != null, short) { SelectionActions.forUnknown(short, allowQuote = true, allowSelectAll = tbAll != null) }
            SelectionPill(r, acts, ink = c.ink, paper = c.paper) { a -> onSelection(a) }
        }

        if (showSearch) {
            SearchScreen(onView = { openView(it) }, onAsk = { showSearch = false; send(it) }, onDismiss = { showSearch = false })
            BackHandler { showSearch = false }
        }
        if (galleryOpen) GalleryScreen(onDismiss = { GalleryUi.open.value = false })
        // v0.32.0: the meeting room, over the chat.
        val roomOpen by Agent.roomOpen.collectAsState()
        if (roomOpen >= 0) {
            MeetingRoomScreen(roomOpen, onDismiss = { Agent.roomOpen.value = -1L })
            BackHandler { Agent.roomOpen.value = -1L }
        }
        if (confirmLeavePrivate) BlazeDialog(
            title = com.blaze.assistant.agent.PrivateChat.LEAVE_TITLE, body = com.blaze.assistant.agent.PrivateChat.LEAVE_TEXT,
            confirm = "Leave", onConfirm = { confirmLeavePrivate = false; Agent.leavePrivate(); composer.dismissChat() }, warm = true,
            dismiss = "Stay", onDismiss = { confirmLeavePrivate = false })
        settingsPage?.let { start ->
            androidx.compose.runtime.key(start) {
                SettingsScreen(start, a11yOn,
                    onOpenAccessibility = { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onDismiss = { settingsPage = null },
                    onTab = { t -> settingsPage = null; exitView(); go(navNow().switchTab(t)) },
                    onSearch = { settingsPage = null; composer.query = ""; composer.openSpotlight() })
            }
        }
        // 0.52.3: what is left of setup (the Home's "Finishing setup" card), as the same one-screen progress view.
        if (setupSheet && !onboard) {
            CompositionLocalProvider(LocalBlaze provides DarkColors) {
                com.blaze.assistant.ui.space.SpaceBackdrop(forceDark = true) {
                    com.blaze.assistant.ui.space.SpaceSetupScreen(setupState, onContinue = { setupSheet = false },
                        onRetry = { com.blaze.assistant.setup.SetupRunner.retry(ctx) })
                }
            }
            BackHandler { setupSheet = false }
        }
        // 0.52.3: Spotlight, from the top bar's round button (+ / search) or the profile's Search pill, over everything.
        com.blaze.assistant.ui.space.SpaceSpotlightLayer(composer, hour = com.blaze.assistant.ui.space.SpaceGreeting.hourNow(),
            apps = { com.blaze.assistant.phone.InstalledApps.all(ctx).map { com.blaze.assistant.composer.SpotlightGroups.App(it.label, it.pkg) } },
            said = { Agent.messages.value.takeLast(300).filter { it.role == Role.USER || it.role == Role.BLAZE }
                .map { com.blaze.assistant.composer.SpotlightGroups.Said(it.id, com.blaze.assistant.agent.ToolLeak.bubbleText(it.text), it.role == Role.USER) } })
        } // !onboard
        // 0.52.3 first run: logo + tagline, sign-in when needed, then the one setup screen (everything downloads by itself).
        if (onboard) {
            val step = com.blaze.assistant.ui.space.SpaceOnboarding.Step.valueOf(obStep)
            LaunchedEffect(step) { if (step == com.blaze.assistant.ui.space.SpaceOnboarding.Step.SETUP) com.blaze.assistant.setup.SetupRunner.start(ctx) }
            com.blaze.assistant.ui.space.SpaceOnboardingScreen(step, signedIn = me != null || Settings.ready, setup = setupState,
                onNext = {
                    val nx = com.blaze.assistant.ui.space.SpaceOnboarding.next(step, me != null || Settings.ready)
                    if (nx != null) obStep = nx.name
                    else { Settings.setBool(com.blaze.assistant.ui.space.SpaceOnboarding.PREF_ONBOARDED, true); onboard = false; go(navNow().switchTab(ChatNav.HOME)) }
                },
                onRetry = { com.blaze.assistant.setup.SetupRunner.retry(ctx) },
                accountCard = { AccountPage() })
            BackHandler(enabled = step != com.blaze.assistant.ui.space.SpaceOnboarding.Step.WELCOME) {
                obStep = if (step == com.blaze.assistant.ui.space.SpaceOnboarding.Step.SETUP && me == null && !Settings.ready)
                    com.blaze.assistant.ui.space.SpaceOnboarding.Step.SIGN_IN.name else com.blaze.assistant.ui.space.SpaceOnboarding.Step.WELCOME.name
            }
        }
    }
}

/** "@ind" → files in Blaze's home whose path matches, to mention in the message. */
private fun fileSuggestions(text: String): List<Suggestion> {
    val tok = text.substringAfterLast(' ').substringAfterLast('\n')
    if (!tok.startsWith("@") || text.startsWith("/")) return emptyList()
    val q = tok.removePrefix("@").lowercase()
    val home = try { com.blaze.assistant.terminal.Terminal.home() } catch (_: Exception) { return emptyList() }
    val found = home.walkTopDown().maxDepth(3).onEnter { !it.name.startsWith(".") || it == home }
        .filter { it.isFile && !it.name.startsWith(".") }
        .map { it.relativeTo(home).path }
        .filter { q.isEmpty() || it.lowercase().contains(q) }
        .take(12).toList()
    val head = text.removeSuffix(tok)
    return found.map { p -> Suggestion("@$p", "file in ~/", "$head@~/$p ", runNow = false) }
}

@Composable
private fun MessageList(mood: Mood, onQuote: (String) -> Unit, onFollowup: (String) -> Unit) {
    val c = LocalBlaze.current
    val messages by Agent.messages.collectAsState()
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // v0.26.2: the old "Fresh start" bubble stays hidden in saved chats too (compaction and /new are silent).
    val itemBuilder = remember { ItemBuilder() }
    val simpleUi by com.blaze.assistant.agent.SimpleMode.simpleFlow.collectAsState()
    val hideDoneSteps = com.blaze.assistant.agent.SimpleMode.work(simpleUi, Settings.showSteps, live = false) == com.blaze.assistant.agent.SimpleMode.Work.NONE
    // v0.45.0 (#10): built incrementally: a streamed frame only rebuilds the rows after the last unchanged message.
    // 0.52.1 (owner): no step rows and no agent cards in the chat: the task's work is ONE line in the TaskPill at the end.
    val items = remember(messages, mood, hideDoneSteps) { itemBuilder.build(messages.filterNot { com.blaze.assistant.agent.ReplyChips.isFreshStart(it.text, it.meta) ||
        it.role == Role.STEP || it.meta.startsWith(com.blaze.assistant.agent.BgAgents.CARD) }, mood != Mood.IDLE, hideDoneSteps) }
    // The running turn's step texts (after your newest message), for the pill's line.
    val turnSteps = remember(messages) { val u = messages.indexOfLast { it.role == Role.USER }
        messages.drop(u + 1).filter { it.role == Role.STEP }.takeLast(12).map { it.text } }
    val agentsAll by com.blaze.assistant.agent.BgAgents.all.collectAsState()
    val chatHere by Agent.chatId.collectAsState()
    val agentLogs = remember(agentsAll, chatHere) { agentsAll.filter { it.live && it.parent == chatHere }.sortedBy { it.started }
        .map { b -> b.log.ifEmpty { listOfNotNull(b.last.takeIf { l -> l.isNotBlank() }) } } }
    val total = items.size + 1 // + working row
    val atBottom by remember { derivedStateOf { !state.canScrollForward } }
    var follow by remember { mutableStateOf(true) }
    // 0.52.4: only YOUR drag lets go of the end. isScrollInProgress is also true for the list's own follow scrolls, so
    // a thinking / steps bubble growing mid-scroll used to drop follow and flash the jump arrow.
    val dragging by state.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) follow = atBottom }
    LaunchedEffect(atBottom) { if (atBottom) follow = true }

    suspend fun toEnd(animated: Boolean) {
        if (animated) state.animateScrollToItem(total - 1) else state.scrollToItem(total - 1)
        state.scrollBy(100_000f)
    }
    // Open at the latest message, and jump back there whenever you come back to Blaze.
    LaunchedEffect(Unit) { toEnd(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) scope.launch { delay(50); toEnd(false); follow = true } }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    // New messages keep you at the bottom unless you scrolled up to read.
    LaunchedEffect(messages.size, mood) { if (follow) toEnd(false) }
    // v0.31.1 jank fix: streamed words used to run scrollToItem + scrollBy (two full list layouts, effect restarted) on every
    // frame. Now: one relative scrollBy, only while you follow the end and the list can actually scroll further.
    val lastLen = rememberUpdatedState(messages.lastOrNull()?.text?.length ?: 0)
    LaunchedEffect(state) {
        // 0.52.4: also when a row grows or shrinks without new text (thinking / steps bubbles animating), so the end stays put.
        snapshotFlow { lastLen.value to state.canScrollForward }.collect { (_, more) -> if (follow && more && !dragging) state.scrollBy(100_000f) }
    }
    // Your own new message always brings you down.
    val lastUser = messages.lastOrNull { it.role == Role.USER }?.id
    LaunchedEffect(lastUser) { if (lastUser != null) { follow = true; toEnd(true) } }

    val lastBlazeId = messages.lastOrNull()?.takeIf { it.role == Role.BLAZE }?.id
    // 0.45.20 delivery label: only under your latest bubble (Sending > Sent > Read, ui/DeliveryStatus.kt).
    val deliveries by com.blaze.assistant.agent.Delivery.tracker().states.collectAsState()
    val lastUserId = messages.lastOrNull { it.role == Role.USER }?.id
    val answeredLast = lastUserId != null && messages.dropWhile { it.id != lastUserId }.drop(1)
        .any { (it.role == Role.BLAZE && it.meta != com.blaze.assistant.agent.Ack.META) || it.role == Role.ERROR }
    // v0.31.0: the reply streaming in right now (the droplet rides it); -1 = none.
    val streamId by Agent.streamingId.collectAsState()
    // v0.31.2 Halo x Blaze: replies streaming side by side (@all, parallel team phases).
    val duoLive by Agent.duoLive.collectAsState()
    // v0.32.0 split: Halo talking with you while Blaze works (her replies stream on their own; the drop stays on the work).
    val sideLive by Agent.sideLive.collectAsState()
    val split by Agent.split.collectAsState()
    // 0.45.24: the live line beside the drop shows until the reply's first words appear (WaitRow).
    val replyAtEnd = (items.lastOrNull() as? Item.Msg)?.m?.let { it.role == Role.BLAZE && it.meta != com.blaze.assistant.agent.Ack.META && (lastUserId == null || it.id > lastUserId) } == true
    val waitLineOn = WaitRow.showLine(mood != Mood.IDLE, streamId >= 0, replyAtEnd)
    // v0.31.2: the newest reply keeps the joined drop + halo, dimmed and waiting, while the chat is idle.
    val waitingId = if (mood == Mood.IDLE) messages.lastOrNull { it.role == Role.BLAZE }?.id ?: -1L else -1L
    // v0.30.6 rapid messages: quote chips on replies, the glow on what Blaze is working on, tap a chip to jump (ReplyQuote.kt).
    val replyRefs = rememberReplyRefs(messages)
    // v0.31.1: one stable lambda (it captured the whole item list, so every row's params changed on every streamed frame).
    val itemsNow = rememberUpdatedState(items)
    val jumpTo: (Long) -> Unit = remember { { id -> itemsNow.value.indexOfFirst { it.key == "m$id" }.takeIf { it >= 0 }?.let { i -> scope.launch { state.animateScrollToItem(i); follow = false } } } }
    val meter by Agent.meter.collectAsState()
    val lastStepsKey = items.lastOrNull { it is Item.Steps }?.key
    // v0.27.0: messages that arrive while the chat is open fade in with a slight rise, once (not the history).
    val appear = remember { AppearOnce(items.map { it.key }) }
    Box(Modifier.fillMaxSize()) {
        // v0.24.0: a messaging thread. No uniform gaps: runs from one side sit 3dp apart, groups 16dp.
        // v0.26.1: choice buttons and forms in replies send through the same path as the follow-up chips (only when idle).
        CompositionLocalProvider(RichSend.Local provides (if (mood == Mood.IDLE) onFollowup else null)) {
        // Unification 6/9: the thread sits at the shell gutter, under the top bar's round button and avatar
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = com.blaze.assistant.ui.space.SpaceInsets.GUTTER_DP.dp, vertical = 14.dp)) {
            itemsIndexed(items, key = { _, it -> it.key }, contentType = { _, it -> if (it is Item.Steps) 1 else 0 }) { i, item ->
                val fresh = remember(item.key) { appear.fresh(item.key) }
                // 0.45.20: "Today" / "Yesterday" chips between days (ui/ChatBubbles.kt).
                val prevAt = if (i > 0) items[i - 1].at else 0L
                Column(Modifier.fillMaxWidth()) {
                if (item is Item.Msg && BubbleDays.startsDay(prevAt, item.at)) DayChip(remember(item.at) { BubbleDays.label(item.at) })
                Box(Modifier.fillMaxWidth().appearIn(fresh && i >= items.size - 3).padding(top = Bubbles.gapAbove(item.pos, i == 0))) {
                    when (item) {
                        is Item.Steps -> {
                            val st0 by Agent.status.collectAsState()
                            val chipOn by Agent.taskChip.collectAsState()
                            val st = if (chipOn.isNotBlank()) "" else st0   // no status words behind an ack
                            // v0.24.4: the token meter is a technical line; shown only with Show technical details on.
                            val m = if (Settings.longChat && com.blaze.assistant.agent.ReplyChips.showTech() && (item.live || item.key == lastStepsKey)) meter else null
                            when (com.blaze.assistant.agent.SimpleMode.work(simpleUi, Settings.showSteps, item.live)) {
                                com.blaze.assistant.agent.SimpleMode.Work.CARD ->
                                    WorkCard(item.steps, item.live, item.startAt, item.endAt, status = "", exact = Settings.showSteps,
                                        meter = m?.text.orEmpty(), meterHigh = m?.high == true, simple = false)
                                // v0.41.0 Simple mode: one small quiet line while working, nothing once done.
                                // 0.52.0: Simple mode shows the same work pill (no quiet status line any more)
                                com.blaze.assistant.agent.SimpleMode.Work.HINT -> WorkCard(item.steps, item.live, item.startAt, item.endAt, status = "", exact = false)
                                com.blaze.assistant.agent.SimpleMode.Work.NONE -> Unit
                            }
                        }
                        is Item.Msg -> ReplyThread(item.m, replyRefs, jumpTo) { Entry(item.m, item.pos, canAct = mood == Mood.IDLE, onQuote = onQuote,
                            delivery = if (item.m.id == lastUserId) DeliveryView.effective(deliveries[item.m.gid], answeredLast, mood != Mood.IDLE) else null,
                            showActs = item.m.id == lastBlazeId && mood == Mood.IDLE,
                            live = item.m.id == streamId || item.m.id in duoLive || item.m.id in sideLive, latest = item.m.id == waitingId,
                            followups = if (item.m.id == lastBlazeId && mood == Mood.IDLE && Settings.followups) item.m.followups else emptyList(),
                            onFollowup = onFollowup, askChips = item.m.id == lastBlazeId && mood == Mood.IDLE && com.blaze.assistant.agent.SimpleMode.simple) }
                    }
                }
                }
            }
            item(key = "working") { Column(Modifier.fillMaxWidth()) {
                val live by Agent.thinking.collectAsState()
                val status by Agent.status.collectAsState()
                // One live card per turn: before the first step it's an empty "Working · 3s" card.
                val hasLiveSteps = (items.lastOrNull() as? Item.Steps)?.live == true
                // v0.31.0: no status words ("Working · 3s", "Thinking it through…"): the water droplet waits here until the
                // reply starts streaming, then rides the reply itself. (A turn with steps keeps its work card above.)
                // v0.31.1: the waiting drop sits where the reply's first line will start (same size and centre as the reply's
                // droplet layer), and hands over without a fade: the reply's drop appears at this spot and glides to the text end.
                val chip by Agent.taskChip.collectAsState()
                // 0.45.20 step pill: the newest step under the pending reply (agent/StepPill.kt), this chat only.
                val pill by com.blaze.assistant.agent.StepPills.current.collectAsState()
                val chatNow by Agent.chatId.collectAsState()
                // 0.45.24: the live line sits beside the drop (same row, centred) and goes as soon as the reply starts.
                val working = mood != Mood.IDLE
                val lineOn = waitLineOn
                // 0.52.1: the waiting drop is replaced by the one TaskPill below (no second "working" indicator).
                @Suppress("UNUSED_VARIABLE") val dropWas = WaitRow.showDrop(working, streamId >= 0, chip.isNotBlank(), duoLive.isNotEmpty())
                val dropOn = false
                // The step pill first; otherwise what Blaze is doing (the status line that used to sit above in the work card).
                val liveSteps = (items.lastOrNull() as? Item.Steps)?.takeIf { it.live }
                val pillText = WaitRow.line(pill?.takeIf { it.chat == chatNow }?.text, if (chip.isBlank()) status else "",
                    liveSteps?.steps?.lastOrNull()?.text.orEmpty(), simple = simpleUi && liveSteps != null, on = lineOn)
                // The row moves up with the drop so the drop keeps its spot on the reply's first line.
                Row(Modifier.fillMaxWidth().padding(top = if (dropOn && !(hasLiveSteps || items.lastOrNull()?.side == Side.BLAZE)) 13.dp else 0.dp)
                    .offset(y = if (dropOn) OrbAnchor.thinkingOffsetY else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(dropOn, enter = MotionTokens.enterUp(), exit = androidx.compose.animation.ExitTransition.None) {
                    Box(Modifier.padding(top = 3.dp)) {
                        val label = com.blaze.assistant.agent.ThinkingLabel.label(live = true, hasThought = live.isNotBlank(), show = Settings.showReasoning)
                            ?: status.ifBlank { "${BrandName.NAME} is thinking" }
                        // v0.32.0 split: while Halo talks with you, the drop works alone (its halo went with her).
                        val ring by androidx.compose.animation.core.animateFloatAsState(if (split) 0f else 1f, androidx.compose.animation.core.tween(520), label = "ring")
                        ThinkingOrb(if (split) "${BrandName.NAME} is working" else label, size = OrbAnchor.SIZE, ring = ring)
                    }
                }
                // Ack-and-background: an acknowledged task shows its small chip instead of the drop.
                // 0.52.0: no task chip and no status line beside the drop: only replies and work pills in the chat.
                if (ChatPolicy.STATUS_LINES) { if (chip.isNotBlank() && working && streamId < 0) TaskChip(chip)
                StepPillRow(pillText, pill?.specialist == true, Modifier.weight(1f, fill = false), inline = true) }
                }
                // 0.52.1 (owner): ONE pill per task, natural text that changes as the work moves on, gone when done.
                val pillLine = com.blaze.assistant.agent.TaskLine.pick(if (working) turnSteps else null, agentLogs)
                AnimatedVisibility(pillLine != null, enter = MotionTokens.enterUp(), exit = MotionTokens.fadeAway()) {
                    TaskPill(pillLine ?: "")
                }
                // v0.26.0: no "this chat is getting long" banner; long chats compact themselves (AutoCompact).
            } }
        }
        }
        // 0.52.4: the arrow only when you scrolled up yourself, never for a bubble bouncing at the end.
        AnimatedVisibility(!follow && !atBottom, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            enter = MotionTokens.enterUp(), exit = MotionTokens.fadeAway()) {
            Box(Modifier.size(44.dp).glass(CircleShape, GlassIntensity.High).clickable { scope.launch { follow = true; toEnd(true) } }
                .semantics { contentDescription = "Jump to the newest message" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(14.dp)) {
                    val u = size.width / 7f
                    // v0.24.8: a smooth rounded down-arrow (was pixel squares)
                    val sw = 1.8.dp.toPx(); val cap = androidx.compose.ui.graphics.StrokeCap.Round
                    drawLine(c.ink, Offset(3.5f * u, 0.5f * u), Offset(3.5f * u, 6.3f * u), sw, cap)
                    drawLine(c.ink, Offset(0.8f * u, 3.6f * u), Offset(3.5f * u, 6.3f * u), sw, cap)
                    drawLine(c.ink, Offset(6.2f * u, 3.6f * u), Offset(3.5f * u, 6.3f * u), sw, cap)
                }
            }
        }
    }
}

/** "14 steps" folded into one line. Tap to see them all. While working, the newest step shows. */
@Composable
private fun StepsGroup(steps: List<ChatMessage>, live: Boolean) {
    val c = LocalBlaze.current
    var open by rememberSaveable(steps.first().id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (live) c.accent else c.weak, BlazeShapes.pill))
            Spacer(Modifier.width(10.dp))
            Label(if (steps.size == 1) "1 step" else "${steps.size} steps", color = c.sub, size = 10.sp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(14.dp).height(1.dp).background(c.hair))
            Spacer(Modifier.width(10.dp))
            Label(if (open) "Hide" else steps.last().text.take(38), color = if (live) c.accent else c.weak, size = 10.sp, modifier = Modifier.weight(1f))
            val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
            Text("⌄", modifier = Modifier.padding(start = 6.dp).graphicsLayerRotation(rot), style = TextStyle(fontSize = 14.sp, color = c.sub))
        }
        AnimatedVisibility(open, enter = MotionTokens.expand(), exit = MotionTokens.collapse()) {
            Column(Modifier.padding(start = 3.dp).hairlineStart(c.hair).padding(start = 13.dp, top = 2.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.Top) {
                        Label("%02d".format(i + 1), color = c.weak, size = 10.sp)
                        Spacer(Modifier.width(10.dp))
                        Label(s.text, color = c.sub, size = 10.5.sp)
                    }
                }
            }
        }
    }
}

private fun Modifier.graphicsLayerRotation(deg: Float) = this.rotate(deg)
private fun Modifier.hairlineStart(color: Color) = this.drawBehind {
    drawRect(color, Offset.Zero, Size(1.dp.toPx(), size.height))
}

/**
 * v0.26.1: "Thinking it through…" (a softly pulsing blue dot) while the model reasons, then a small quiet
 * "Thought it through · 4 s" above the reply. Not tappable: the reasoning text is never shown.
 */
@Composable
private fun ThinkingLine(text: String, live: Boolean) {
    val c = LocalBlaze.current
    val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "think")
    val pulse = tr.animateFloat(0.3f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900),
        androidx.compose.animation.core.RepeatMode.Reverse), label = "thinkA")
    val a = if (MotionTokens.enabled) pulse.value else 1f   // v0.27.0: a still dot with Remove animations on
    Row(Modifier.padding(top = 6.dp, bottom = 4.dp).semantics { contentDescription = text }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (live) BlazeBrush.blue.copy(alpha = a) else c.weak))
        Spacer(Modifier.width(8.dp))
        Text(text, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.5.sp, color = if (live) c.body else c.sub))
    }
}

/**
 * One message in the thread (v0.24.0): yours on the right in an ink bubble, Blaze's on the left in a soft bubble,
 * errors in Blaze's bubble with an accent wash. Only the last message of a group gets the quiet time + actions line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Entry(m: ChatMessage, pos: GroupPos, canAct: Boolean, onQuote: (String) -> Unit, followups: List<String>, onFollowup: (String) -> Unit,
                  live: Boolean = false, latest: Boolean = false, askChips: Boolean = false,
                  delivery: com.blaze.assistant.agent.DeliveryState? = null, showActs: Boolean = false) {
    val entryCtx = LocalContext.current
    // 0.45.20 (spec A): the time sits inside the last bubble of a run; actions are a long-press menu (BubbleMenu), and
    // only the latest Blaze reply keeps its small action row.
    val menu = remember(m.id) { mutableStateOf(false) }
    val entryClip = LocalClipboardManager.current
    val bubbleTime = remember(m.at, pos) { if (pos.ends) BubbleDays.time(m.at) else "" }
    CompositionLocalProvider(LocalBubbleTime provides bubbleTime,
        LocalBubbleMenu provides (if (m.role == Role.USER || m.role == Role.BLAZE || m.role == Role.ERROR) ({ menu.value = true }) else null)) {
    BubbleMenu(menu.value, buildList<Pair<String, (() -> Unit)?>> {
        add("Copy" to { entryClip.setText(AnnotatedString(if (m.role == Role.BLAZE) Md.plain(RichKit.readable(com.blaze.assistant.agent.ToolLeak.bubbleText(m.text))) else Md.plain(splitQuote(m.text).second))); Toast.makeText(entryCtx, "Copied", Toast.LENGTH_SHORT).show() })
        if (m.role != Role.ERROR) add("Quote" to { onQuote(if (m.role == Role.BLAZE) com.blaze.assistant.agent.ToolLeak.forDisplay(m.text) else splitQuote(m.text).second) })
        add("Retry" to (if (canAct) ({ Agent.retry(m.id) }) else null))
        if (m.role == Role.BLAZE && !live) add("React \uD83D\uDC4D" to { reactNow(entryCtx, m, Reactions.DOUBLE_TAP) })
    }) { menu.value = false }
    when (m.role) {
        Role.USER -> SwipeToQuote(true, onQuote = { onQuote(splitQuote(m.text).second) }) { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            val (quoted, body) = splitQuote(m.text)
            // v0.24.6: pictures inside your bubble (tap to preview), other files as icon chips.
            SentBubble(body, quoted, rememberPresent(m.media), m.files, pos)
            if (delivery != null && DeliveryView.shown(delivery)) DeliveryLabel(delivery, onRetry = if (canAct) ({
                com.blaze.assistant.agent.Delivery.tracker().apply(listOf(m.gid), com.blaze.assistant.agent.Delivery.Ev.USER_RETRY); Agent.retry(m.id) }) else null)
        } }
        Role.BLAZE -> if (m.meta == com.blaze.assistant.update.Updater.CARD) UpdateCard()
        else if (m.meta.startsWith(com.blaze.assistant.agent.BgAgents.CARD)) Unit   // 0.52.1: agents show only in the one TaskPill
        else if (m.meta.startsWith(com.blaze.assistant.agent.Duo.ROOM_CARD)) RoomCard(m)   // v0.32.0 meeting room
        else SwipeToQuote(true, onQuote = { onQuote(com.blaze.assistant.agent.ToolLeak.forDisplay(m.text)) }) { Column(Modifier.fillMaxWidth()
            // v0.45.0 reactions: double-tap a finished reply for 👍 (taps, long-press and swipes still reach the bubble).
            .doubleTapToReact(!live) { reactNow(entryCtx, m, Reactions.DOUBLE_TAP) }) {
            // v0.31.0: no "Thought it through · 4 s" line; the droplet was the status while it thought.
            // v0.28.3: old saved replies may hold raw leaked tool syntax; it's hidden when drawn too.
            val shown = remember(m.text, live) { ParseMemo.memo(ParseMemo.bubble, m.text, live) { com.blaze.assistant.agent.ToolLeak.bubbleText(m.text) } }
            val tracker = remember(m.id) { OrbTracker() }
            // v0.31.2 Halo x Blaze: who wrote it (the name over Halo's replies and in a duo, the shimmer colour, the orb half that travels).
            val who = com.blaze.assistant.agent.Duo.Who.of(m.speaker)
            if (m.speaker.isNotEmpty()) SpeakerName(who)
            // v0.31.2 ("make sure the orb finishes and doesn't stay at the end of the words"): a reply counts as streaming
            // only while words keep arriving. If the stream flag is left on with no new text for STREAM_STALE_MS, the
            // reply settles anyway: the newest words lose their fade / shimmer and the orb leaves the last word.
            var fresh by remember(m.id) { mutableStateOf(live) }
            LaunchedEffect(m.text, live) {
                fresh = live
                if (live) { kotlinx.coroutines.delay(STREAM_STALE_MS); fresh = false }
            }
            // Only replies that streamed while on screen carry a droplet layer (history costs nothing).
            var hadLive by remember(m.id) { mutableStateOf(live) }
            LaunchedEffect(live) { if (live) hadLive = true }
            // 0.46.2 immersive replies: the pages' pictures above the text, the source pills under it (from the turn's real tool results).
            val found = remember(m.sources) { com.blaze.assistant.agent.ReplySources.decode(m.sources) }
            if (!live && found.images.isNotEmpty()) { val pc = LocalContext.current
                ReplyImages(found, low = remember { StageDevice.tier(pc) == com.blaze.assistant.stage.PerfTier.LOW }) }
            Box(Modifier.fillMaxWidth().onGloballyPositioned { tracker.root = it }) {
                CompositionLocalProvider(LocalOrb provides tracker, LocalSpeaker provides who) { BlazeReply(m.id, shown, pos, live = fresh) }
                if (hadLive) OrbLayer(tracker, fresh, speaker = who, latest = latest, alone = who == com.blaze.assistant.agent.Duo.Who.HALO && Agent.split.collectAsState().value)
            }
            if (m.media.isNotEmpty()) MediaStrip(m.media)
            if (!live && found.sources.isNotEmpty()) SourcePills(found)
            if (showActs || followups.isNotEmpty()) Actions(m, Side.BLAZE, "", canAct, onQuote, allowQuote = true,
                orbSlot = if (hadLive) ({ co -> tracker.aimRest(co) }) else null, reactable = !live)
            else {
                if (!live && m.reactions.isNotEmpty()) { val rc = LocalContext.current; ReactionChips(m.reactions, mine = false) { e -> reactNow(rc, m, e) } }
                if (com.blaze.assistant.agent.ReplyChips.shown(m.meta).isNotBlank()) Actions(m, Side.BLAZE, "", canAct, onQuote, allowQuote = true, chipOnly = true)
            }
            if (followups.isNotEmpty()) Followups(followups, onFollowup)
            // v0.39.0 Simple mode: Yes / No / Later / Do it for me under a question from Blaze.
            if (askChips && !live) {
                val chips = remember(m.text) { com.blaze.assistant.agent.SimpleHome.replyChips(com.blaze.assistant.agent.ToolLeak.bubbleText(m.text)) }
                ReplyChipRow(chips, onFollowup)
            }
        } }
        // v0.45.4: signed out of Blaze Cloud: a Sign in card; the message above it is sent by itself once signed in.
        Role.ERROR -> if (m.meta == com.blaze.assistant.agent.SignInGate.CARD) Column(Modifier.fillMaxWidth()) {
            ErrorBubble(m.text, pos)
            FollowupRow(listOf(com.blaze.assistant.agent.SignInGate.BUTTON)) { com.blaze.assistant.agent.SelfSetup.openPage.value = "account" }
        } else Column(Modifier.fillMaxWidth()) {
            ErrorBubble(m.text, pos)
            Actions(m, Side.BLAZE, "", canAct, onQuote, allowQuote = false)
            // Pick up where it broke instead of starting over: the history keeps the steps done so far.
            // (v0.25.0: no brain switcher on old "used up" errors; there is nothing to pick.)
            if (canAct) Followups(listOf(com.blaze.assistant.agent.StopResume.CHIP), onFollowup)
        }
        Role.STEP -> {}
    }
    }
}

private fun splitQuote(t: String): Pair<String, String> {
    val lines = t.lines()
    val q = lines.takeWhile { it.startsWith(">") }
    if (q.isEmpty()) return "" to t
    return q.joinToString("\n") { it.removePrefix(">").trim() } to lines.drop(q.size).joinToString("\n").trim()
}

@Composable
private fun Followups(list: List<String>, onPick: (String) -> Unit) {
    // v0.26.2: one horizontal swipe row of compact chips, not stacked full-width pills.
    FollowupRow(list, onPick = onPick)
}

/** Time, then Copy / Quote / Retry / What I did / React as small icons under the last message of a group. Long-press words to copy or quote just those. */
@Composable
private fun Actions(m: ChatMessage, side: Side, time: String, canAct: Boolean, onQuote: (String) -> Unit, allowQuote: Boolean, chipOnly: Boolean = false,
                    orbSlot: ((androidx.compose.ui.layout.LayoutCoordinates) -> Unit)? = null, reactable: Boolean = false) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val showReceipt = remember(m.id) { mutableStateOf(false) }
    val showTrace = remember(m.id) { mutableStateOf(false) }
    val picker = remember(m.id) { mutableStateOf(false) }
    // v0.45.0 reactions: under the reply, then "React" in the footer opens the small picker.
    if (reactable && !chipOnly && m.reactions.isNotEmpty()) ReactionChips(m.reactions, mine = side == Side.ME) { e -> reactNow(ctx, m, e) }
    val usedUp = m.meta.startsWith("usedup:")
    // v0.24.4: technical parts of the chip (timings, data, model ids) are hidden unless Show technical details is on.
    val chip = if (usedUp) "" else com.blaze.assistant.agent.ReplyChips.shown(m.meta)
    if (chipOnly && chip.isBlank()) return
    if (chipOnly) { MetaLine(side, "", emptyList(), chip, chip.startsWith("⚡")); HeadsUpWhy(m.meta); return }
    val acts = buildList<Pair<String, (() -> Unit)?>> {
        add("Copy" to { clip.setText(AnnotatedString(if (m.role == Role.BLAZE) Md.plain(RichKit.readable(com.blaze.assistant.agent.ToolLeak.bubbleText(m.text))) else Md.plain(splitQuote(m.text).second))); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() })
        if (allowQuote) add("Quote" to { onQuote(m.text) })
        add("Retry" to (if (canAct) ({ Agent.retry(m.id) }) else null))
        // v0.45.0 "What I did": the turn's action receipts, stored with the reply.
        if (m.role == Role.BLAZE && m.receipt.isNotBlank()) add("What I did" to { showReceipt.value = true })
        // v0.45.5 (#1): "What happened": model, route, tools, timings, errors, request id. 0.45.19: never shown to users
        // (which model replied is never shown); developers only, behind DevFlags.modelInfo (off by default, debug builds).
        if (m.role == Role.BLAZE && m.trace.isNotBlank() && com.blaze.assistant.agent.DevFlags.modelInfo()) add("What happened" to { showTrace.value = true })
        if (reactable && m.role == Role.BLAZE) add("React" to { picker.value = true })
    }
    MetaLine(side, time, acts, chip, chip.startsWith("⚡"), orbSlot = orbSlot)
    HeadsUpWhy(m.meta)
    if (showReceipt.value) ReceiptDialog(m.receipt) { showReceipt.value = false }
    if (showTrace.value && com.blaze.assistant.agent.DevFlags.modelInfo()) TraceDialog(m.trace) { showTrace.value = false }
    if (picker.value) ReactionPicker(m.reactions, onPick = { e -> picker.value = false; reactNow(ctx, m, e) }, onDismiss = { picker.value = false })
}

/** Unreleased after 0.52.3: the small "Why?" under a heads-up; a tap shows what set it off (ProactiveDial.whyOf). */
@Composable
private fun HeadsUpWhy(meta: String) {
    val why = remember(meta) { com.blaze.assistant.proactive.ProactiveDial.whyOf(meta) }
    if (why.isBlank()) return
    var open by remember(meta) { mutableStateOf(false) }
    val c = LocalBlaze.current
    Text(if (open) "Why: $why" else "Why?", maxLines = 2,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp).clickable(onClickLabel = "Why you got this") { open = !open },
        style = com.blaze.assistant.ui.space.SpaceType.meta(c.body))
}

/** v0.45.0 reactions: toggles one; in a private chat it says once that reactions there aren't kept. */
private var privateReactNoted = false
private fun reactNow(ctx: android.content.Context, m: ChatMessage, emoji: String) {
    if (!Agent.react(m.id, emoji)) return
    try { com.blaze.assistant.device.Haptics.tick(ctx) } catch (_: Throwable) { }
    if (Agent.privateChat.value && !privateReactNoted) {
        privateReactNoted = true
        Toast.makeText(ctx, "Reactions in a private chat aren't saved", Toast.LENGTH_SHORT).show()
    }
}

/** v0.45.5 (#1): "What happened": what served this reply, its tools, timings and errors, and a copyable request id. */
@Composable
private fun TraceDialog(json: String, onClose: () -> Unit) {
    val t = remember(json) { com.blaze.assistant.agent.TurnTrace.fromJson(json) }
    val c = LocalBlaze.current
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    androidx.compose.material3.AlertDialog(onDismissRequest = onClose,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = { if (t != null) androidx.compose.material3.TextButton(onClick = {
            clip.setText(AnnotatedString(com.blaze.assistant.agent.TurnTrace.copyText(t))); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() }) { Text("Copy") } },
        title = { Text("What happened") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (t == null) { Text("Nothing was recorded for this reply."); return@Column }
                com.blaze.assistant.agent.TurnTrace.rows(t).forEach { (k, v) ->
                    Text(k, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.body))
                    Text(v, style = TextStyle(fontFamily = if (k == "Request id" || k == "Server-Timing") Type.Mono else Type.Sans, fontSize = 14.sp, color = c.ink),
                        modifier = if (k == "Request id") Modifier.clickable { clip.setText(AnnotatedString(v)); Toast.makeText(ctx, "Request id copied", Toast.LENGTH_SHORT).show() } else Modifier)
                    Spacer(Modifier.height(8.dp))
                }
                if (t.tools.isNotEmpty()) {
                    Text("Tools", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.body))
                    t.tools.forEach { x ->
                        Text("${if (x.ok) com.blaze.assistant.agent.Receipt.OK else com.blaze.assistant.agent.Receipt.BAD} ${x.name}(${x.args})", style = TextStyle(fontFamily = Type.Mono, fontSize = 11.sp, color = c.ink))
                        if (x.result.isNotBlank()) Text(x.result, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 16.sp, color = c.body))
                        Spacer(Modifier.height(6.dp))
                    }
                }
                if (t.errors.isNotEmpty()) {
                    Text("Errors and retries", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.body))
                    t.errors.forEach { Text(it, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.ink)) }
                }
            }
        })
}

/** v0.45.0: "What I did": each action Blaze took for this reply, what came back and what was checked. */
@Composable
private fun ReceiptDialog(json: String, onClose: () -> Unit) {
    val log = remember(json) { com.blaze.assistant.agent.Receipt.fromJson(json) }
    val c = LocalBlaze.current
    androidx.compose.material3.AlertDialog(onDismissRequest = onClose,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("What I did") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (log.isEmpty()) Text("Nothing was recorded for this reply.")
                log.forEach { e ->
                    val mark = when (e.status) {
                        com.blaze.assistant.agent.ToolVerify.Status.VERIFIED -> com.blaze.assistant.agent.Receipt.OK
                        com.blaze.assistant.agent.ToolVerify.Status.UNVERIFIED -> com.blaze.assistant.agent.Receipt.WARN
                        com.blaze.assistant.agent.ToolVerify.Status.FAILED -> com.blaze.assistant.agent.Receipt.BAD
                    }
                    Text("$mark ${e.label.ifBlank { e.tool }}", style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink))
                    Text(e.result, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 16.sp, color = c.body))
                    if (e.evidence.isNotBlank()) Text("Checked: ${e.evidence}", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.body))
                    if (!com.blaze.assistant.agent.SimpleMode.simple) Text("${e.tool}(${e.args})", style = TextStyle(fontFamily = Type.Mono, fontSize = 10.sp, color = c.body))
                    Spacer(Modifier.height(10.dp))
                }
            }
        })
}

@Composable
private fun QuoteStrip(q: String, onClear: () -> Unit) {
    val c = LocalBlaze.current
    // Unification 6/9: a floating panel at the composer's inset (composerPanel), not a 12 dp glass band
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).composerPanel()
        .padding(start = 14.dp, end = 2.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Label("Replying to", color = c.accent, size = 9.sp)
            Text(q, maxLines = 2, style = com.blaze.assistant.ui.space.SpaceType.meta(c.body))
        }
        ActionText("×", size = 14.sp, modifier = Modifier.clip(CircleShape).clickable(onClick = onClear).semantics { contentDescription = "Cancel reply" }.padding(12.dp))
    }
}

@Composable
internal fun SuggestionList(list: List<Suggestion>, onPick: (Suggestion) -> Unit) {
    val c = LocalBlaze.current
    // Unification 6/9: the slash list floats at the composer's inset on the menu colour; it sat 10 dp in, radius 18, glass
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).composerPanel(fill = com.blaze.assistant.ui.space.spaceLook().menu)
        .heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        list.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, maxLines = 1, style = TextStyle(fontFamily = Type.Mono, fontSize = 13.sp, color = c.ink))
                Spacer(Modifier.width(12.dp))
                Text(s.sub, maxLines = 1, modifier = Modifier.weight(1f), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
            }
        }
    }
}

/**
 * The empty chat (0.52.3, the Hark look): a card with the serif greeting and a line on what ARGUS does, a sign-in card
 * while not signed in, and suggested asks as white pill chips (tapped = sent).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun Welcome(needsSetup: Boolean, onSetup: () -> Unit, onPick: (String) -> Unit) {
    val look = com.blaze.assistant.ui.space.spaceLook()
    val hour = remember { com.blaze.assistant.ui.space.SpaceGreeting.hourNow() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = com.blaze.assistant.ui.space.SpaceInsets.GUTTER_DP.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        com.blaze.assistant.ui.space.SpaceCard {
            com.blaze.assistant.ui.space.SpaceHeadline(com.blaze.assistant.ui.space.SpaceGreeting.hello(hour, Settings.userName.ifBlank { null }))
            Spacer(Modifier.height(10.dp))
            Text("Ask anything. ${BrandName.NAME} answers, opens apps, taps and types for you, and checks with you before anything that matters.",
                style = com.blaze.assistant.ui.space.SpaceType.bodyText(look.sub))
        }
        if (needsSetup) com.blaze.assistant.ui.space.SpaceCard {
            com.blaze.assistant.ui.space.SpaceHeadline("${BrandName.UPPER} can do more", size = com.blaze.assistant.ui.space.SpaceTypeScale.TITLE.sp)
            Spacer(Modifier.height(8.dp))
            Text("Sign in to your ${BrandName.NAME} account for the best answers, synced across your devices.",
                style = com.blaze.assistant.ui.space.SpaceType.bodyText(look.ink))
            Spacer(Modifier.height(14.dp))
            com.blaze.assistant.ui.space.SpacePillButton("Sign in", onSetup, primary = true)
        }
        com.blaze.assistant.ui.space.SpaceCard {
            Text("Try", style = com.blaze.assistant.ui.space.SpaceType.meta(look.sub))
            Spacer(Modifier.height(12.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("Set an alarm for 6:30", "Draw a lion wearing sunglasses", "Make me an afro beat", "Open YouTube and play lo-fi music")
                    .forEach { s -> com.blaze.assistant.ui.space.SpaceChip(s, { onPick(s) }) }
            }
        }
    }
}

@Composable
private fun BlinkLabel(text: String) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(500); on = !on } }
    Label(text + if (on) " _" else "  ", color = LocalBlaze.current.sub)
}

@Composable
private fun ListeningStrip(onStop: () -> Unit) {
    val c = LocalBlaze.current
    val partial by Voice.partial.collectAsState()
    val level by Voice.level.collectAsState()
    val w by animateFloatAsState(0.15f + level * 0.85f, label = "lvl")
    // v0.45.28: tap the strip to stop listening and send.
    // Unification 6/9: a floating panel at the composer's inset; it was a full-width paper band with a top hairline
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).composerPanel().clickable(onClickLabel = "Stop listening and send") { onStop() }
        .padding(horizontal = com.blaze.assistant.ui.space.SpaceInsets.CARD_PAD_DP.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BlinkLabel("Listening")
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).height(2.dp).clip(BlazeShapes.pill).background(c.faint)) {
                Box(Modifier.fillMaxWidth(w).height(2.dp).clip(BlazeShapes.pill).background(c.accent))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(partial.ifBlank { "Say \"Open YouTube\" or ask anything" },
            style = com.blaze.assistant.ui.space.SpaceType.section(if (partial.isBlank()) c.weak else c.ink))
    }
}

@Composable
private fun AttachmentStrip(files: List<com.blaze.assistant.agent.Attachment>, loading: Boolean, onRemove: (com.blaze.assistant.agent.Attachment) -> Unit) {
    val c = LocalBlaze.current
    Row(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        files.forEach { f ->
            Row(Modifier.clip(RoundedCornerShape(16.dp)).background(c.bubble).clickable { onRemove(f) }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(f.name.take(18), style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.body))
                Spacer(Modifier.width(6.dp)); Label("×", size = 12.sp)
            }
        }
        if (loading) Label("Reading…", color = c.accent, size = 10.sp)
    }
}

private val callWords = Regex("""^(?:(?:hey |ok )?(?:(?:argus|blaze)[, ]+)?)(?:(?:can we|could we|can you|let['’]?s|lets|i want to|i wanna|wanna|please)\s+)?(?:(?:get|hop|jump|be)\s+on\s+(?:a\s+)?(?:voice\s+)?call|(?:start|have|do|make)\s+a\s+(?:voice\s+)?call|call(?:\s+(?:(?:argus|blaze)|me))?|talk(?:\s+to\s+me)?|voice\s+(?:call|chat|mode))(?:\s+(?:with\s+(?:me|(?:argus|blaze)|you)|(?:argus|blaze)|now|please))*[.!?]*$""", RegexOption.IGNORE_CASE)


/** v0.31.2 Halo x Blaze: a small coloured name over a reply when it isn't plain Blaze talking alone. */
@Composable
internal fun SpeakerName(who: com.blaze.assistant.agent.Duo.Who) {
    Row(Modifier.padding(start = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(7.dp)) {
            if (who == com.blaze.assistant.agent.Duo.Who.HALO) drawCircle(DuoColors.haloGold, radius = size.minDimension / 2f - 0.6.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()))
            else drawCircle(DuoColors.blazeBlue)
        }
        Text("  " + who.display, style = androidx.compose.ui.text.TextStyle(fontFamily = Type.Sans, fontSize = 11.5.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = DuoColors.main(who).copy(alpha = 0.95f)))
    }
}

/** v0.31.2: how long a "streaming" reply may go without new words before it settles (orb home, last word solid). */
const val STREAM_STALE_MS = 2200L

/**
 * 0.48.0 RC4: what Blaze is waiting on, under the chat over Home (it lived at the bottom of the old chat screen): the
 * listening strip, approvals and drafts (Send / Not now), questions, fix cards, secure forms and secret requests, Blaze
 * Phone's live tile, "Open in Design?", the update banner, and the Undo bars.
 */
@Composable
private fun WaitingCards(listening: Boolean, clearedAt: Long, onUndoClear: () -> Unit, onClearedGone: () -> Unit,
                         control: com.blaze.assistant.phone.PhoneControl.State, viewOffer: Views.Offer?, showing: String,
                         onOpenOffer: (Views.Offer) -> Unit) {
    val ctx = LocalContext.current
    if (listening) ListeningStrip(onStop = { Voice.stopByTap() })
    if (control != com.blaze.assistant.phone.PhoneControl.State.CONNECTED && Settings.phoneControl && Settings.bool("a11y_seen", false))
        PhoneControlBanner(control) {
            ComeBack.watch(ctx) { BlazeAccessibilityService.instance != null }
            try { ctx.startActivity(com.blaze.assistant.phone.PhoneControl.fixIntent(ctx)) }
            catch (_: Exception) { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    UpdateBanner()
    ConfirmCard()
    QuestionCard()
    FixCard()
    SecureFormCard()
    SecretRequestCard()
    SecretPasteCard()
    PhoneLiveCard()
    viewOffer?.let { o -> if (showing != o.id) ViewOfferChip(Views.offerLabel(o.id), onOpen = { onOpenOffer(o) }, onDismiss = { Views.offer.value = null }) }
    if (clearedAt > 0L) {
        LaunchedEffect(clearedAt) { delay(com.blaze.assistant.agent.ClearScreen.UNDO_MS - 3_000); onClearedGone() }
        Box(Modifier.fillMaxWidth().height(72.dp)) { UndoBar("Chat cleared \u00b7 nothing is forgotten", onUndo = onUndoClear, onDismiss = onClearedGone) }
    }
    Box(Modifier.fillMaxWidth()) { UndoCountdownBar(onUndone = { line -> Agent.note(line) }) }
}

/**
 * 0.52.3: the Home as the chat shell shows it (space/SpaceHome.kt). The chat's messages are collected here, not in
 * ChatScreen, so a streaming reply only recomposes the Home's answer card.
 */
@Composable
private fun SpaceHomeLayer(name: String?, signedIn: Boolean, mood: Mood, projects: List<com.blaze.assistant.agent.Project>,
                           setup: Map<String, com.blaze.assistant.setup.SetupPlan.Status>,
                           onAsk: (String) -> Unit, onOpenChat: () -> Unit, onOpenProject: (String) -> Unit, onOpenProjects: () -> Unit,
                           onSignIn: () -> Unit, onSetup: () -> Unit) {
    val messages by Agent.messages.collectAsState()
    // the hour, re-read at every full hour while Home is composed
    var hour by remember { androidx.compose.runtime.mutableIntStateOf(com.blaze.assistant.ui.space.SpaceGreeting.hourNow()) }
    LaunchedEffect(Unit) { while (true) { delay(com.blaze.assistant.ui.space.SpaceGreeting.msToNextHour(System.currentTimeMillis())); hour = com.blaze.assistant.ui.space.SpaceGreeting.hourNow() } }
    val card = remember(messages, mood) {
        com.blaze.assistant.ui.space.SpaceAnswer.card(0L, messages.takeLast(40).filter { it.role == Role.USER || it.role == Role.BLAZE }
            .map { com.blaze.assistant.ui.space.SpaceAnswer.Msg(it.id, it.role == Role.BLAZE, com.blaze.assistant.agent.ToolLeak.bubbleText(it.text)) }, mood != Mood.IDLE)
    }
    val state = com.blaze.assistant.ui.space.SpaceHomeState(name = name, hour = hour, signedIn = signedIn, answer = card, working = mood != Mood.IDLE,
        projects = projects.sortedByDescending { it.updated }.take(com.blaze.assistant.ui.space.SpaceHomeCards.MAX_PROJECTS).map { it.id to it.name },
        setupLeft = if (com.blaze.assistant.setup.SetupRunner.started()) com.blaze.assistant.setup.SetupPlan.left(setup).size else 0,
        setupPercent = com.blaze.assistant.setup.SetupPlan.percent(setup))
    com.blaze.assistant.ui.space.SpaceHomeScreen(state, onAsk = onAsk, onOpenChat = onOpenChat, onOpenProject = onOpenProject,
        onOpenProjects = onOpenProjects, onSignIn = onSignIn, onSetup = onSetup, bottomPad = 16.dp)
}
