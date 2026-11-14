package com.blaze.assistant.ui.team

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.DrawerValue
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.BrandName
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.BgAgents
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.ChatStore
import com.blaze.assistant.agent.ManagerChats
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Projects
import com.blaze.assistant.agent.ReplyChips
import com.blaze.assistant.agent.Role
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.ManagerOrb
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ArgusField
import com.blaze.assistant.ui.argus.kit.ArgusIconButton
import com.blaze.assistant.ui.argus.kit.ArgusSearch
import com.blaze.assistant.ui.argus.kit.ArgusSheet
import com.blaze.assistant.ui.argus.kit.ArgusToasts
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.ButtonSize
import com.blaze.assistant.ui.argus.kit.IconButtonKind
import com.blaze.assistant.ui.argus.kit.MenuRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * P1-SH-04 Chat mode's runtime: the Manager's chats ([ManagerChats], rules in ChatDrawerModel) read from and saved to
 * the chats folder, ✎ New chat = a new window + a fresh context on the main chat ([Agent.clear]) + the Manager's
 * generation bump ([com.blaze.assistant.team.BotThreads.fresh]). Thin Android glue; the rules are pure and tested
 * (ManagerChatsTest, ChatDrawerTest). Port of A-team `app/www/talks.js` (v2.19-chat-drawer, 0788c808).
 */
object ChatModeRuntime {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var m: ManagerChats? = null
    private val _version = MutableStateFlow(0)
    /** Bumps on every change (the frame redraws on it). */
    val version: StateFlow<Int> = _version
    /** A new chat started while ARGUS was still replying: its fresh context waits for the reply to end. */
    @Volatile private var pendingFresh = false
    private val _deleted = MutableStateFlow<ManagerChats.Deleted?>(null)
    /** The chat whose Undo is open. */
    val deleted: StateFlow<ManagerChats.Deleted?> = _deleted
    private var undoJob: Job? = null

    @Synchronized fun chats(): ManagerChats = m ?: ManagerChats.read(ChatStore.managerChatsText()).also { m = it; save(it) }

    private fun save(c: ManagerChats) { val t = c.toJson(); io.launch { ChatStore.saveManagerChats(t) } }
    private fun changed() { save(chats()); _version.value++ }

    /** What the drawer rules need from a chat message. Step rows, agent cards and the silent fresh-start note don't count. */
    fun msg(x: ChatMessage) = ChatDrawerModel.Msg(x.role == Role.USER, x.text, x.at,
        listable = (x.role == Role.USER || x.role == Role.BLAZE) && !ReplyChips.isFreshStart(x.text, x.meta) && !x.meta.startsWith(BgAgents.CARD))

    /** Chat mode shows only the open chat's window, never a deleted one. */
    fun keep(c: ManagerChats, x: ChatMessage): Boolean = c.chatOf(x.at) == c.cur && !c.hidden(x.at)

    /** ✎ New chat. Stays put while the newest chat is still empty (talks.js). */
    fun newChat(ctx: Context) {
        val c = chats()
        val r = c.newChat(System.currentTimeMillis(), ChatDrawerModel.blank(c, c.live, Agent.messages.value.map(::msg)))
        if (r is ManagerChats.New.Started) startFresh(ctx)
        changed()
    }

    private fun startFresh(ctx: Context) {
        if (Agent.mood.value == Mood.IDLE) Agent.clear(false) else pendingFresh = true
        val app = ctx.applicationContext
        io.launch { try { com.blaze.assistant.team.TeamGraph.get(app).threads.fresh(BotRules.MANAGER_ID) } catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("ChatMode.fresh", t) } }
    }

    /** Before a send from Chat mode: an older chat is read-only, so the message goes to the newest one. */
    fun beforeSend() {
        val c = chats()
        if (c.readOnly) { c.open(c.live); changed(); ArgusToasts.show("Sent in your newest chat") }
        if (pendingFresh && Agent.mood.value == Mood.IDLE) { pendingFresh = false; Agent.clear(false) }
    }

    /** A turn on the main chat ended: start a waiting fresh context; a dot on the newest chat unless you watched it. */
    fun onIdle(viewing: Boolean) {
        if (pendingFresh) { pendingFresh = false; Agent.clear(false) }
        val c = chats()
        c.replied(c.live, viewing)
        changed()
        autoTitle(c, c.live)
    }

    /**
     * Owner 2026-11-14: the chat's title follows the conversation ([ManagerChats.titleDue]): one small background call
     * ([Titles.ask]) names it from what most of it is about. Never for a chat you named; a failed call tries next turn.
     */
    private fun autoTitle(c: ManagerChats, g: Int) {
        if (!com.blaze.assistant.agent.Settings.autoTitles) return
        val ch = c.get(g) ?: return
        if (ch.named || ch.gone) return
        val w = c.window(g) ?: return
        val lines = Agent.messages.value.filter { it.at in w }.map(::msg).filter { it.listable }.map { it.user to it.text }
        if (lines.lastOrNull()?.first != false) return   // only once the chat ends with ARGUS's answer
        val n = lines.count { it.first }
        if (!ManagerChats.titleDue(n, ch.autoN, ch.auto != null, lines.last { it.first }.second.length)) return
        val current = ch.auto ?: ChatDrawerModel.clean(lines.first { it.first }.second)
        io.launch {
            val r = try { com.blaze.assistant.agent.Titles.ask("manager-$g", current, lines) } catch (t: Throwable) { null } ?: return@launch
            synchronized(this@ChatModeRuntime) { chats().autoTitle(g, r.title, n) }
            changed()
        }
    }

    fun open(g: Int) { if (chats().open(g)) changed() }
    fun rename(g: Int, t: String) { chats().rename(g, t); changed() }
    fun pin(g: Int) { val on = chats().pin(g, System.currentTimeMillis()); changed(); ArgusToasts.show(if (on) "Pinned" else "Unpinned") }

    /** Delete with Undo (5 s); the window closing purges it. Deleting the newest starts a new one. */
    fun delete(ctx: Context, g: Int) {
        finishUndo(true)
        val d = chats().delete(g, System.currentTimeMillis()) ?: return
        if (d.replacement != null) startFresh(ctx)
        _deleted.value = d
        changed()
        undoJob = io.launch { delay(ManagerChats.UNDO_MS); finishUndo(true) }
    }

    @Synchronized private fun finishUndo(purge: Boolean) {
        val d = _deleted.value ?: return
        _deleted.value = null; undoJob?.cancel(); undoJob = null
        if (purge) { chats().purge(d.g); changed() }
    }

    fun restore() {
        val d = _deleted.value ?: return
        finishUndo(false)
        val c = chats()
        val blank = d.replacement?.let { ChatDrawerModel.blank(c, it, Agent.messages.value.map(::msg)) } ?: true
        c.restore(d, blank)
        changed()
        ArgusToasts.show(ChatDrawerModel.Undo.RESTORED)
    }
}

/**
 * P1-SH-04 Chat mode (04 §4.13): the Manager's main conversation on the new look. Top row: history drawer button
 * (dot = a new reply in another chat), private chat (ghost) toggle, ✎ new chat. Empty: Manager sphere, serif greeting,
 * suggested asks. [content] draws the messages and gets the filter for the open chat's window.
 *
 * The drawer (talks.js): search, Pinned, Today / Yesterday / Previous 7 days / Older, the Manager's chats and the
 * project chats, long-press Rename / Pin / Copy / Delete with Undo, New chat at the bottom. It opens from the button or
 * a swipe from the left edge (a 24 dp strip, excluded from the system back gesture, so the mode pager keeps every
 * other swipe); swipe left, the scrim or Back close it.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ChatModeFrame(
    name: String?,
    privateOn: Boolean,
    onPrivate: () -> Unit,
    onAsk: (String) -> Unit,
    onOpenProjectChat: (String) -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    content: @Composable (keep: ((ChatMessage) -> Boolean)?) -> Unit,
) {
    val c = Argus.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val version by ChatModeRuntime.version.collectAsState()
    val messages by Agent.messages.collectAsState()
    val mood by Agent.mood.collectAsState()
    val allChats by Agent.chats.collectAsState()
    val projects by Projects.all.collectAsState()
    val deleted by ChatModeRuntime.deleted.collectAsState()
    var menuFor by ChatDrawerUi.menuFor
    var renaming by remember { mutableStateOf<ChatDrawerModel.Entry?>(null) }
    val chats = remember(version) { ChatModeRuntime.chats() }
    val msgs = remember(messages) { messages.map(ChatModeRuntime::msg) }
    val hasHere = remember(msgs, version, privateOn) { if (privateOn) msgs.isNotEmpty() else !ChatDrawerModel.blank(chats, chats.cur, msgs) }
    // one filter per state of the chats, so the message list only rebuilds when the open chat changes
    val keep: ((ChatMessage) -> Boolean)? = remember(version, privateOn) { if (privateOn) null else { x: ChatMessage -> ChatModeRuntime.keep(chats, x) } }
    // a turn on the main chat ended: dot it unless Chat mode was showing the newest chat
    var wasBusy by remember { mutableStateOf(false) }
    LaunchedEffect(mood) {
        if (mood != Mood.IDLE) wasBusy = true
        else if (wasBusy) { wasBusy = false; if (!privateOn) ChatModeRuntime.onIdle(viewing = !drawer.isOpen && !chats.readOnly) }
    }
    // Owner 2026-11-14: the drawer is drawn over the whole screen by [ChatDrawerLayer] (top bar to the composer, one
    // surface, no seams), so the frame only publishes its drawer and what a pick does.
    DisposableEffect(drawer) {
        ChatDrawerUi.drawer.value = drawer
        onDispose { if (ChatDrawerUi.drawer.value === drawer) ChatDrawerUi.drawer.value = null }
    }
    ChatDrawerUi.onOpenProjectChat = onOpenProjectChat
    run {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArgusIconButton(com.blaze.assistant.ui.argus.icons.ArgusGlyphs.MENU_TAPER, "Chat history", { scope.launch { drawer.open() } }, kind = IconButtonKind.FLAT, badge = chats.anyUnseen())
                    Spacer(Modifier.weight(1f))
                    ArgusIconButton("lock", if (privateOn) "Private chat on" else "Private chat", onPrivate, kind = if (privateOn) IconButtonKind.GLASS else IconButtonKind.FLAT)
                    ArgusIconButton("square-pen", "New chat", { ChatModeRuntime.newChat(ctx) }, kind = IconButtonKind.FLAT)
                }
                if (chats.readOnly) Text(ChatModeCopy.READ_ONLY, style = ArgusType.meta, color = c.mute,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), textAlign = TextAlign.Center)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (!hasHere) ChatModeEmpty(if (privateOn) null else name, privateOn, onAsk)
                    else key(chats.cur, privateOn) { content(keep) }
                }
                if (deleted != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(c.ink)
                    .padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ChatDrawerModel.Undo.DELETED, style = ArgusType.sub, color = c.onInk, modifier = Modifier.weight(1f))
                    ArgusButton("Undo", { ChatModeRuntime.restore() }, kind = ButtonKind.PRIMARY, size = ButtonSize.S)
                }
            }
            // the left edge opens the drawer; Android's back gesture is kept off this strip (≤ 200 dp, the system's cap)
            val edge = rememberDraggableState { d -> if (d > EDGE_PULL_PX && !drawer.isOpen && !drawer.isAnimationRunning) scope.launch { drawer.open() } }
            Box(Modifier.align(Alignment.CenterStart).width(EDGE_W_DP.dp).height(EDGE_H_DP.dp)
                .systemGestureExclusion { lc -> Rect(0f, 0f, lc.size.width.toFloat(), lc.size.height.toFloat()) }
                .draggable(edge, Orientation.Horizontal)
                .clearAndSetSemantics { })
        }
    }

    menuFor?.let { e ->
        val k = e.key
        ArgusSheet(onDismiss = { menuFor = null }, title = e.title) {
            MenuRow("pencil", "Rename", { menuFor = null; renaming = e })
            MenuRow(if (e.pinnedAt != null) "pin-off" else "pin", if (e.pinnedAt != null) "Unpin" else "Pin", {
                menuFor = null
                when (k) { is ChatDrawerModel.Key.Manager -> ChatModeRuntime.pin(k.g); is ChatDrawerModel.Key.Project -> Agent.pinChat(k.chatId, e.pinnedAt == null) }
            })
            if (k is ChatDrawerModel.Key.Manager) MenuRow("copy", "Copy", {
                menuFor = null
                val t = ChatDrawerModel.transcript(ChatDrawerModel.messagesOf(chats, k.g, msgs), name?.ifBlank { null } ?: "You", BrandName.NAME)
                val ok = try {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Chat", t)); true
                } catch (_: Exception) { false }
                ArgusToasts.show(if (ok) "Chat copied" else "Couldn\u2019t copy this chat")
            })
            MenuRow("trash-2", "Delete", {
                menuFor = null
                when (k) {
                    is ChatDrawerModel.Key.Manager -> ChatModeRuntime.delete(ctx, k.g)
                    // a project chat is the project's: deleted for good, as Projects does
                    is ChatDrawerModel.Key.Project -> { Agent.deleteChat(k.chatId); ArgusToasts.show("Chat deleted") }
                }
            }, danger = true)
        }
    }
    renaming?.let { e ->
        var t by remember(e.key) { mutableStateOf(e.title) }
        val save = {
            when (val k = e.key) { is ChatDrawerModel.Key.Manager -> ChatModeRuntime.rename(k.g, t); is ChatDrawerModel.Key.Project -> Agent.renameChat(k.chatId, t) }
            renaming = null
        }
        ArgusSheet(onDismiss = { renaming = null }, title = "Rename chat", primary = "Save", onPrimary = save) {
            ArgusField(t, { v -> t = v.take(ManagerChats.TITLE_MAX) }, label = "Name", onIme = save)
        }
    }
}

/** The chat drawer's state, shared by [ChatModeFrame] (which owns it) and [ChatDrawerLayer] (which draws it). */
object ChatDrawerUi {
    val drawer = mutableStateOf<DrawerState?>(null)
    val menuFor = mutableStateOf<ChatDrawerModel.Entry?>(null)
    var onOpenProjectChat: (String) -> Unit = {}
    /** Open or opening: the keyboard is for the drawer's search, so the composer stays where it is. */
    fun showing(): Boolean = drawer.value?.let { it.isOpen || it.targetValue == DrawerValue.Open } ?: false
}

/**
 * The history drawer over the whole screen (owner 2026-11-14: the top bar, notch, chatbox and model chip must not read as
 * separate parts with their own edges around it). Draw it last at the screen's root; it is empty while Chat mode is not
 * showing, and while closed it lets every touch through.
 */
@Composable
fun ChatDrawerLayer(modifier: Modifier = Modifier, zone: ZoneId = ZoneId.systemDefault()) {
    val drawer = ChatDrawerUi.drawer.value ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by ChatModeRuntime.version.collectAsState()
    val messages by Agent.messages.collectAsState()
    val mood by Agent.mood.collectAsState()
    val allChats by Agent.chats.collectAsState()
    val projects by Projects.all.collectAsState()
    var query by remember { mutableStateOf("") }
    val chats = remember(version) { ChatModeRuntime.chats() }
    val msgs = remember(messages) { messages.map(ChatModeRuntime::msg) }
    if (drawer.isOpen) BackHandler { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        modifier = modifier.fillMaxSize(),
        drawerContent = {
            val state = remember(version, msgs, allChats, projects, query, mood) {
                val pc = allChats.mapNotNull { meta -> Projects.of(meta.id)?.let { p -> ChatDrawerModel.ProjectChat(meta, p.name) } }
                ChatDrawerModel.state(chats, msgs, pc, System.currentTimeMillis(), zone, query,
                    replying = if (mood != Mood.IDLE) chats.live else null)
            }
            ChatDrawerSheet(state, query, { query = it },
                onOpen = { e ->
                    when (val k = e.key) {
                        is ChatDrawerModel.Key.Manager -> ChatModeRuntime.open(k.g)
                        is ChatDrawerModel.Key.Project -> ChatDrawerUi.onOpenProjectChat(k.chatId)
                    }
                    scope.launch { drawer.close() }
                },
                onMenu = { e -> ChatDrawerUi.menuFor.value = e },
                onNew = { ChatModeRuntime.newChat(ctx); scope.launch { drawer.close() } })
        },
    ) { Box(Modifier.fillMaxSize()) }
}

/**
 * The history drawer's sheet, stateless (P1-QA-01 split it out of [ChatModeFrame] so the shots draw it on fixtures):
 * search, the sections of [state] (Pinned / Today / Yesterday / Previous 7 days / Older), and New chat.
 */
@Composable
fun ChatDrawerSheet(
    state: ChatDrawerModel.State,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (ChatDrawerModel.Entry) -> Unit,
    onMenu: (ChatDrawerModel.Entry) -> Unit,
    onNew: () -> Unit,
) {
    val c = Argus.colors
    ModalDrawerSheet(drawerShape = RectangleShape, drawerContainerColor = c.bg, drawerContentColor = c.ink) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(12.dp))
            ArgusSearch(query, onQuery, placeholder = "Search chats")
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                if (state.empty != null) item { Text(state.empty, style = ArgusType.sub, color = c.mute, modifier = Modifier.padding(16.dp)) }
                for (s in state.sections) {
                    item(key = "h-" + s.name) {
                        Text(s.name, style = ArgusType.meta, color = c.mute, modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp).semantics { heading() })
                    }
                    items(s.entries, key = { e -> e.key.toString() }) { e -> DrawerRow(e, onOpen = { onOpen(e) }, onMenu = { onMenu(e) }) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.End) {
                ArgusButton("New chat", onNew, icon = "square-pen")
            }
        }
    }
}

/** talks.js EDGE_W / EDGE_H: the strip that opens the drawer (200 dp is also Android's gesture-exclusion cap per edge). */
private const val EDGE_W_DP = 24
private const val EDGE_H_DP = 200
private const val EDGE_PULL_PX = 4f

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerRow(e: ChatDrawerModel.Entry, onOpen: () -> Unit, onMenu: () -> Unit) {
    val c = Argus.colors
    val src = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
        .then(if (e.current) Modifier.background(c.glassFill2) else Modifier)
        .combinedClickable(src, indication = null, onClick = onOpen, onLongClick = onMenu)
        .clearAndSetSemantics { contentDescription = e.spoken }
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.title, style = ArgusType.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = e.snippet ?: e.project
            if (sub != null) Text(sub, style = ArgusType.meta, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (e.live) Box(Modifier.size(8.dp).clip(CircleShape).background(c.waiting))
        else if (e.unseen) Box(Modifier.size(8.dp).clip(CircleShape).background(c.unread))
    }
}

/** 04 §4.13 the empty Chat mode: Manager sphere, serif greeting, suggested asks. [hour] = the clock's (the shots fix it). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatModeEmpty(name: String?, privateOn: Boolean, onAsk: (String) -> Unit, hour: Int? = null) {
    val c = Argus.colors
    val clock = remember { java.time.LocalTime.now().hour }
    val hr = hour ?: clock
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ManagerOrb(size = 72.dp, contentDescription = null)
        Spacer(Modifier.height(18.dp))
        Text(ChatModeCopy.greeting(hr, name), style = ArgusType.serifHello, color = c.ink, textAlign = TextAlign.Center)
        if (privateOn) Text(ChatModeCopy.PRIVATE_ON, style = ArgusType.meta, color = c.mute, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in ChatModeCopy.ASKS) ArgusButton(a, { onAsk(a) }, size = ButtonSize.S)
        }
    }
}
