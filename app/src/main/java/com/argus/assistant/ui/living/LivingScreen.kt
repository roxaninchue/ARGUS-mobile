package com.blaze.assistant.ui.living

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Role
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.agent.StepTool
import com.blaze.assistant.agent.ToolTrail
import com.blaze.assistant.i18n.tr
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.living.LivingLayout.Box as CardBox
import com.blaze.assistant.ui.living.LivingLayout.Camera
import com.blaze.assistant.ui.living.LivingModel.Card
import com.blaze.assistant.ui.living.LivingModel.Kind
import com.blaze.assistant.ui.living.LivingModel.State
import kotlinx.coroutines.launch

/**
 * Living UI (LivingSpec.VERSION): the main chat's work as a canvas, in Chat's place (the chatbox stays).
 *
 *  - One finger pans, two pinch-zoom (0.25×–3×), a double tap fits everything, a tap on a card zooms in on it and a
 *    second tap goes back.
 *  - The canvas follows new cards until you pan; the pill's Next turns following back on and goes to the active card.
 *  - Performance: only the cards touching the screen are composed (the visible set is a derived state, so a pan only
 *    recomposes when a card enters or leaves); the camera is read in draw and in graphicsLayer, never in composition;
 *    the dot grid thins out when zoomed out; the one animation (the pulse on the active card) runs only while working,
 *    while the app is resumed and without reduced motion. Nothing here runs while Chat shows.
 *  - 0.2.1 landscape ([wide], Finn's landscape mockup): four columns (LivingLayout.plan wide), one header line (the chat's
 *    title, [title] or the drawer's name for it, with a dropdown that opens the history ([onTitle]) at the top left, the
 *    earlier-chat note under it ([note] or ChatModeCopy.READ_ONLY), the status
 *    pill centred), the canvas under the header and the floating composer, which follow keeps clear
 *    (LivingLayout.followWide). Turning the phone keeps the card you were looking at in the middle (LivingLayout.reanchor).
 */
@Composable
fun LivingScreen(working: Boolean, keep: ((ChatMessage) -> Boolean)?, reducedMotion: Boolean, modifier: Modifier = Modifier,
                 wide: Boolean = false, title: String? = null, note: String? = null, onTitle: (() -> Unit)? = null) {
    val dark = LocalBlaze.current.dark
    val p = remember(dark) { LivingSpec.palette(dark) }
    val messages by Agent.messages.collectAsState()
    val chatHere by Agent.chatId.collectAsState()
    val trail by ToolTrail.results.collectAsState()

    // Living reads each step's result, so the trail records while it is open (back to the setting when it closes).
    DisposableEffect(Unit) {
        ToolTrail.on = true
        onDispose { ToolTrail.on = true }
    }

    val board = remember(messages, trail, chatHere, working, keep) {
        LivingModel.board(messages.asSequence().filter { keep?.invoke(it) != false && it.meta.startsWith(com.blaze.assistant.agent.BgAgents.CARD).not() }
            .mapNotNull { m ->
                val r = when (m.role) { Role.USER -> LivingModel.Role.USER; Role.BLAZE -> LivingModel.Role.BLAZE; Role.STEP -> LivingModel.Role.STEP; else -> null }
                r?.let { LivingModel.Msg(m.id, it, m.text, if (m.role == Role.STEP) m.meta else "", m.at, ack = m.role == Role.BLAZE && m.meta == com.blaze.assistant.agent.Ack.META) }
            }.toList(), working, trail[chatHere].orEmpty(),
            label = { name, running -> StepTool.label(name).let { if (running) it.now else it.done } })
    }
    val plan = remember(board, wide) { LivingLayout.plan(board, wide) }
    // 0.2.1 landscape header: the open chat's title as the history drawer names it, and the earlier-chat note
    val chatsVersion by com.blaze.assistant.ui.team.ChatModeRuntime.version.collectAsState()
    val privateOn by Agent.privateChat.collectAsState()
    val head = remember(wide, title, note, chatsVersion, messages.size, privateOn) {
        when {
            !wide -> null
            title != null -> title to note
            privateOn -> tr("Private chat") to null
            else -> {
                val chats = com.blaze.assistant.ui.team.ChatModeRuntime.chats()
                val t = com.blaze.assistant.ui.team.ChatDrawerModel.state(chats, messages.map(com.blaze.assistant.ui.team.ChatModeRuntime::msg), emptyList(),
                    System.currentTimeMillis(), java.time.ZoneId.systemDefault()).entries.firstOrNull { it.current }?.title
                (t ?: com.blaze.assistant.ui.team.ChatDrawerModel.NEW_TITLE) to (if (chats.readOnly) com.blaze.assistant.ui.team.ChatModeCopy.READ_ONLY else null)
            }
        }
    }
    // 0.2.0: page pictures for the browser cards; a card's window ends at the next page card or the next prompt.
    // 0.2.4: a card of ARGUS's own browser stands for its tab for the whole turn, so its window runs to the next prompt
    // (it keeps updating while the turn works, then stops); LivingPage.pick prefers the picture on the card's address.
    val shots by LivingShots.shots.collectAsState()
    val until = remember(board) {
        val all = board.cards
        all.withIndex().filter { it.value.kind == Kind.BROWSER }.associate { (i, c) ->
            c.key to all.drop(i + 1).firstOrNull { it.kind == Kind.PROMPT || (!c.live && it.kind == Kind.BROWSER) }?.at
        }
    }
    val glass by LivingSpec.glass.collectAsState()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifeState by lifecycle.currentStateAsState()
    val animate = working && !reducedMotion && lifeState.isAtLeast(Lifecycle.State.RESUMED)

    // 0.2.0: clipped, so cards panned or zoomed past the edge never draw over the top bar or the Chat | Living switch
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds().background(Color(p.canvas))) {
        val density = LocalDensity.current.density
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val pad = 16f * density
        val top = (if (wide) LivingSpec.WIDE_TOP_DP else 64f) * density      // under the pill (0.2.1: the header line)
        val bottom = (if (wide) LivingSpec.WIDE_BOTTOM_DP else 24f) * density   // air above the switch (0.2.1: the floating composer)

        var cam by remember { mutableStateOf(Camera(pad, top + pad, 1f)) }
        var following by remember { mutableStateOf(true) }
        var zoomedOn by remember { mutableStateOf<String?>(null) }
        var before by remember { mutableStateOf<Camera?>(null) }
        val scope = rememberCoroutineScope()
        val planNow by rememberUpdatedState(plan)

        fun glide(to: Camera) {
            val from = cam
            scope.launch {
                if (reducedMotion) { cam = to; return@launch }
                animate(0f, 1f, animationSpec = tween(320)) { t, _ ->
                    cam = Camera(from.ox + (to.ox - from.ox) * t, from.oy + (to.oy - from.oy) * t, from.scale + (to.scale - from.scale) * t)
                }
            }
        }
        fun goActive() {
            val b = planNow.box(board.active) ?: planNow.boxes.lastOrNull() ?: return
            zoomedOn = null
            glide(if (wide) LivingLayout.followWide(b, planNow, density, vw, vh, pad, top, bottom) else LivingLayout.follow(b, density, vw, vh, pad, top, bottom))
        }

        // follow new cards until the user pans
        LaunchedEffect(plan, following, vw, vh) { if (following) goActive() }
        // 0.2.1: the phone turned (or two columns became four): keep the card in the middle where it was
        val seen = remember { Seen() }
        LaunchedEffect(plan, vw, vh, wide) {
            val old = seen.plan
            if (old != null && !following && (seen.vw != vw || seen.vh != vh || seen.wide != wide)) {
                zoomedOn = null; before = null
                val moved = LivingLayout.reanchor(old, cam, seen.vw, seen.vh, plan, vw, vh, density)
                if (moved != null) cam = moved else following = true
            }
            seen.plan = plan; seen.vw = vw; seen.vh = vh; seen.wide = wide
        }
        // 0.2.1: in landscape the floating composer covers the bottom, so fit and focus keep clear of it
        val fitH = if (wide) vh - bottom else vh

        val visible by remember(plan, vw, vh) { derivedStateOf { LivingLayout.visibleKeys(plan, cam, density, vw, vh, 80f * density) } }

        val linkColor = Color(p.link)
        val dotColor = Color(p.dot)
        Canvas(Modifier.fillMaxSize()
            .pointerInput(vw, vh) {
                detectTransformGestures { centroid, panBy, zoomBy, _ ->
                    following = false; zoomedOn = null
                    var c = LivingLayout.pan(cam, panBy.x, panBy.y)
                    if (zoomBy != 1f) c = LivingLayout.zoom(c, zoomBy, centroid.x, centroid.y)
                    cam = c
                }
            }
            .pointerInput(vw, vh) {
                detectTapGestures(
                    onDoubleTap = { following = false; zoomedOn = null; glide(LivingLayout.fit(planNow, density, vw, fitH, pad, top)) },
                    onTap = { o ->
                        val b = LivingLayout.hit(planNow, cam, density, o.x, o.y)
                        // 0.2.4: a tap on a browser card opens the glass view (watch the agent's page, view only)
                        if (b != null && b.card.kind == Kind.BROWSER && b.card.live) LivingSpec.openGlass(b.card.url)
                        else if (b == null || zoomedOn == b.key) {
                            before?.let { glide(it) }; zoomedOn = null; before = null
                        } else {
                            following = false
                            if (zoomedOn == null) before = cam
                            zoomedOn = b.key
                            glide(LivingLayout.focus(b, density, vw, fitH, pad, top))
                        }
                    })
            }
            .semantics { contentDescription = tr("Living canvas: what {0} is working on", com.blaze.assistant.BrandName.NAME) }
        ) {
            dots(cam, density, dotColor)
            val stroke = Stroke(width = (1.2f * density).coerceAtLeast(1f), cap = StrokeCap.Round)
            for (l in plan.links) {
                drawPath(linkPath(l, cam, density), linkColor, style = stroke)
                if (l.arrow) arrowHead(l, cam, density, linkColor, stroke.width)
            }
        }

        // the cards (only those touching the screen)
        for (b in plan.boxes) {
            if (b.key !in visible) continue
            key(b.key) {
                CardHost(b, { cam }, density, p, active = b.key == board.active && working, pulse = animate,
                    shots = if (b.card.kind == Kind.BROWSER) shots else emptyList(), until = until[b.key])
            }
        }

        if (plan.boxes.isEmpty()) Text(tr("When {0} works, you watch it here: the pages it reads, the files it writes and what it runs.", com.blaze.assistant.BrandName.NAME),
            color = Color(p.mute), fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(32.dp))

        // 0.2.1 landscape: the chat's title (and the earlier-chat note) at the top left, on the pill's line
        if (head != null) WideTitle(head.first, head.second, p, onTitle,
            Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 10.dp).widthIn(max = (vw / density * 0.27f).dp))
        StatusPill(board.status, working, p, pulse = animate, onNext = { following = true; goActive() },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 16.dp, end = 16.dp)
                .then(if (wide) Modifier.widthIn(max = (vw / density * 0.44f).dp) else Modifier))

        // 0.2.4: the glass view over everything on the canvas (closed by its Close or Back)
        glass?.let { g -> GlassPanel(g, working, p, now = board.cards.lastOrNull { it.live && (g.isBlank() || it.url == g) }?.now ?: board.cards.lastOrNull { it.live }?.now.orEmpty()) }
    }
}

/** What the canvas last laid out for (0.2.1, to keep the view steady when the phone turns). */
private class Seen(var plan: LivingLayout.Plan? = null, var vw: Float = 0f, var vh: Float = 0f, var wide: Boolean = false)

/** 0.2.1 landscape header: the chat's title with a small dropdown mark (opens the history), [note] under it. */
@Composable
private fun WideTitle(title: String, note: String?, p: LivingSpec.Palette, onTitle: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
        .then(if (onTitle != null) Modifier.clickable(onClickLabel = tr("Chat history")) { onTitle() } else Modifier)
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, color = Color(p.ink), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!note.isNullOrBlank()) Text(note, color = Color(p.mute), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onTitle != null) {
            Spacer(Modifier.width(6.dp))
            val ink = Color(p.mute)
            Canvas(Modifier.size(12.dp)) {
                val w = size.width
                val path = Path().apply { moveTo(w * 0.2f, w * 0.38f); lineTo(w * 0.5f, w * 0.66f); lineTo(w * 0.8f, w * 0.38f) }
                drawPath(path, ink, style = Stroke(1.6f * density, cap = StrokeCap.Round))
            }
        }
    }
}

// ---------------------------------------------------------------------- the canvas

private fun DrawScope.dots(cam: Camera, density: Float, color: Color) {
    val step = LivingSpec.gridStepPx(size.width, size.height, density, cam.scale)
    val r = LivingSpec.DOT_DP * density
    var x = ((cam.ox % step) + step) % step
    while (x <= size.width) {
        var y = ((cam.oy % step) + step) % step
        while (y <= size.height) { drawCircle(color, r, Offset(x, y)); y += step }
        x += step
    }
}

/** A small open arrowhead at a connector's end, pointing along its last segment. */
private fun DrawScope.arrowHead(l: LivingLayout.Link, cam: Camera, density: Float, color: Color, width: Float) {
    val n = l.pts.size / 2
    if (n < 2) return
    val x1 = cam.sx(l.pts[2 * n - 2], density); val y1 = cam.sy(l.pts[2 * n - 1], density)
    val x0 = cam.sx(l.pts[2 * n - 4], density); val y0 = cam.sy(l.pts[2 * n - 3], density)
    val d = kotlin.math.hypot(x1 - x0, y1 - y0)
    if (d < 0.5f) return
    val ux = (x1 - x0) / d; val uy = (y1 - y0) / d
    val len = 6f * density * cam.scale
    for (side in floatArrayOf(1f, -1f)) {
        val ex = x1 - ux * len - uy * len * 0.6f * side
        val ey = y1 - uy * len + ux * len * 0.6f * side
        drawLine(color, Offset(ex, ey), Offset(x1, y1), width, StrokeCap.Round)
    }
}

/** A connector on screen, its elbows rounded. */
private fun linkPath(l: LivingLayout.Link, cam: Camera, density: Float): Path {
    val path = Path()
    val pts = l.pts
    val n = pts.size / 2
    if (n < 2) return path
    fun px(i: Int) = cam.sx(pts[2 * i], density)
    fun py(i: Int) = cam.sy(pts[2 * i + 1], density)
    val r = LivingLayout.ELBOW * density * cam.scale
    path.moveTo(px(0), py(0))
    for (i in 1 until n - 1) {
        val ax = px(i - 1); val ay = py(i - 1); val bx = px(i); val by = py(i); val cx = px(i + 1); val cy = py(i + 1)
        val d1 = kotlin.math.hypot(bx - ax, by - ay); val d2 = kotlin.math.hypot(cx - bx, cy - by)
        val rr = minOf(r, d1 / 2f, d2 / 2f)
        val inX = bx - (bx - ax) / d1.coerceAtLeast(0.001f) * rr; val inY = by - (by - ay) / d1.coerceAtLeast(0.001f) * rr
        val outX = bx + (cx - bx) / d2.coerceAtLeast(0.001f) * rr; val outY = by + (cy - by) / d2.coerceAtLeast(0.001f) * rr
        path.lineTo(inX, inY)
        path.quadraticBezierTo(bx, by, outX, outY)
    }
    path.lineTo(px(n - 1), py(n - 1))
    return path
}

// ---------------------------------------------------------------------- cards

@Composable
private fun CardHost(b: CardBox, cam: () -> Camera, density: Float, p: LivingSpec.Palette, active: Boolean, pulse: Boolean,
                     shots: List<LivingShots.Shot> = emptyList(), until: Long? = null) {
    val shape = RoundedCornerShape(if (b.card.kind == Kind.PROMPT) 18.dp else 14.dp)
    Box(Modifier
        .graphicsLayer {
            val c = cam()
            transformOrigin = TransformOrigin(0f, 0f)
            translationX = c.sx(b.x, density); translationY = c.sy(b.y, density)
            scaleX = c.scale; scaleY = c.scale
        }
        .wrapContentSize(Alignment.TopStart, unbounded = true)
        .requiredSize(b.w.dp, b.h.dp)) {
        Box(Modifier.fillMaxSize().shadow(if (LocalBlaze.current.dark) 0.dp else 6.dp, shape, clip = false, ambientColor = Color(p.shadow), spotColor = Color(p.shadow))
            .clip(shape).background(Color(p.card)).border(1.dp, Color(p.cardEdge), shape)) {
            when (b.card.kind) {
                Kind.PROMPT -> PromptCard(b.card, p)
                Kind.BROWSER -> BrowserCard(b.card, p, shots, until, live = active, pulse = pulse)
                Kind.FILE -> FileCard(b.card, p)
                Kind.LOG -> LogCard(b.card, p)
                Kind.ANSWER -> AnswerCard(b.card, p)
                Kind.RESULT -> b.card.result?.let { ResultCardView(it, p) }
            }
        }
        if (active) AgentMark(p, pulse, Modifier.align(Alignment.TopEnd).padding(top = 0.dp).graphicsLayer { translationX = 6.dp.toPx(); translationY = -6.dp.toPx() })
    }
}

/** Placeholder agent mark (Finn designs the bots): a small dot with a soft glow, pulsing while it works. */
@Composable
private fun AgentMark(p: LivingSpec.Palette, pulse: Boolean, modifier: Modifier = Modifier) {
    val glow = if (pulse) {
        val t = rememberInfiniteTransition(label = "living-pulse")
        t.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "glow").value
    } else 0.6f
    val orb = Color(p.orb)
    Canvas(modifier.size(16.dp)) {
        drawCircle(orb.copy(alpha = 0.25f * glow), size.minDimension / 2f)
        drawCircle(orb.copy(alpha = 0.5f + 0.5f * glow), size.minDimension / 4.2f)
    }
}

@Composable
private fun Header(text: String, p: LivingSpec.Palette) =
    Text(text, color = Color(p.mute), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
private fun PromptCard(c: Card, p: LivingSpec.Palette) = Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
    Header(tr("You asked"), p)
    Spacer(Modifier.height(4.dp))
    Text(c.text, color = Color(p.ink), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 5, overflow = TextOverflow.Ellipsis)
}

/**
 * 0.2.0, like Finn's mockup: a "Browsing" header with a live dot, then the page in a small browser frame (tab with the
 * site's icon and the page title, an address bar with the site) showing the page's picture when one was taken while
 * this was the open page ([LivingPage.pick]), else a masthead: the site's icon and name, the title and the lead text.
 * Never a raw URL or the tool's own words.
 */
@Composable
private fun BrowserCard(c: Card, p: LivingSpec.Palette, shots: List<LivingShots.Shot>, until: Long?, live: Boolean, pulse: Boolean) {
    val host = if (c.flag) "" else LivingModel.host(c.url)
    val site = if (c.flag) tr("Web search") else LivingPage.site(c.url).ifBlank { tr("Page") }
    val shot = remember(shots, c.url, c.at, until, c.flag) {
        if (c.flag) null else LivingPage.pick(LivingShots.refs(shots), c.url, c.at, until)?.let { shots.getOrNull(it) }
    }
    val icon by produceState<ImageBitmap?>(null, host) { value = if (host.isBlank()) null else LivingShots.icon(host) }
    val running = c.state == State.RUNNING
    val title = c.title.ifBlank { shot?.title.orEmpty() }.ifBlank { site }
    Column(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running || live) AgentMark(p, pulse) else StateDot(c.state, p)
            Spacer(Modifier.width(8.dp))
            // 0.2.4: the agent's own browser names the site it is on, big enough to read at a glance
            val head = when {
                c.flag -> if (c.steps > 1) tr("Searching") + " \u00b7 ${c.steps}" else tr("Searching")
                c.live -> host.ifBlank { tr("Browser") }
                else -> tr("Browsing")
            }
            Text(head, color = Color(p.ink), fontSize = 14.sp, fontWeight = if (c.live) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (c.live) Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(p.bar)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(tr("Watch"), color = Color(p.mute), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            } else if (running) Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(p.bar)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(tr("Reading\u2026"), color = Color(p.mute), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        // 0.2.4: what the agent is doing in the page now (or did last), e.g. "Typing “restaurants” on google.com"
        if (c.live && c.now.isNotBlank()) Text(c.now, color = Color(if (running) p.ink else p.mute), fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().height(18.dp).padding(start = 24.dp))
        Spacer(Modifier.height(6.dp))
        val frame = RoundedCornerShape(10.dp)
        Column(Modifier.fillMaxSize().clip(frame).background(Color(p.bar)).border(1.dp, Color(p.cardEdge), frame)) {
            // tab: icon + page title
            Row(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SiteIcon(icon, site, p, 12)
                Spacer(Modifier.width(6.dp))
                Text(title, color = Color(p.ink), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // address bar: the site only
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(Color(p.card))
                .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(Color(p.lineNo), size.minDimension / 2f, style = Stroke(1.2f * density)) }
                Spacer(Modifier.width(6.dp))
                Text(if (c.flag) c.url else host.ifBlank { site }, color = Color(p.mute), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxSize().background(Color(p.card))) {
                if (shot != null) Image(shot.image, contentDescription = title, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize())
                else Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    // the masthead: the site's icon and name, then the title and the lead
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SiteIcon(icon, site, p, 16)
                        Spacer(Modifier.width(6.dp))
                        Text(site, color = Color(p.mute), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(title, color = Color(p.ink), fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    val lead = if (c.flag) c.text else LivingPage.lead(c.text)
                    Text(lead.ifBlank { if (running) tr("Loading\u2026") else "" }, color = Color(p.mute), fontSize = 11.sp, lineHeight = 15.sp,
                        maxLines = if (c.flag) 4 else 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The site's icon, or its first letter in a soft circle while the icon loads (or when there is none). */
@Composable
private fun SiteIcon(icon: ImageBitmap?, site: String, p: LivingSpec.Palette, sizeDp: Int) {
    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(3.dp)))
    else Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(Color(p.link)), contentAlignment = Alignment.Center) {
        Text(site.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(), color = Color(p.ink), fontSize = (sizeDp * 0.6f).sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun FileCard(c: Card, p: LivingSpec.Palette) = Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(c.title, color = Color(p.ink), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Header(if (c.flag) tr("wrote") else tr("read"), p)
        Spacer(Modifier.width(6.dp))
        StateDot(c.state, p)
    }
    Spacer(Modifier.height(8.dp))
    val lines = c.lines.ifEmpty { listOf("") }
    for ((i, l) in lines.withIndex()) Row(Modifier.height(17.dp)) {
        Text("${i + 1}", color = Color(p.lineNo), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(22.dp))
        Text(l, color = Color(p.code), fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** 0.2.0: the steps as a checklist, like a terminal: `$ command` lines with their first result line under a green check. */
@Composable
private fun LogCard(c: Card, p: LivingSpec.Palette) = Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
    Header(tr("Steps"), p)
    Spacer(Modifier.height(6.dp))
    for (r in c.rows) {
        val res = LivingLayout.result(r)
        Row(Modifier.height(LivingLayout.ROW_H.dp), verticalAlignment = Alignment.CenterVertically) {
            if (r.command) Text("$", color = Color(p.mute), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(14.dp))
            else { StateDot(r.state, p); Spacer(Modifier.width(8.dp)) }
            Text(if (r.command) r.text.removePrefix("$ ") else r.text, color = Color(if (r.state == State.FAILED && !r.command) p.fail else p.ink), fontSize = 12.sp,
                fontFamily = if (r.command) FontFamily.Monospace else FontFamily.Default, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            if (r.command && res == null) { Spacer(Modifier.width(6.dp)); StateDot(r.state, p) }
        }
        if (res != null) Row(Modifier.height(LivingLayout.RESULT_H.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            StateDot(r.state, p)
            Spacer(Modifier.width(6.dp))
            Text(res, color = Color(if (r.state == State.FAILED) p.fail else p.code), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AnswerCard(c: Card, p: LivingSpec.Palette) = Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
    val (head, rest) = remember(c.text) { LivingLayout.headline(c.text) }
    Header(tr("Answer"), p)
    Spacer(Modifier.height(4.dp))
    Text(head, color = Color(p.ink), fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold,
        maxLines = LivingLayout.HEAD_LINES, overflow = TextOverflow.Ellipsis)
    if (rest.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(rest, color = Color(p.mute), fontSize = 13.sp, lineHeight = 18.sp, maxLines = LivingLayout.BODY_LINES, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------- 0.2.2 result cards ("show, don't tell")

/** A result card (ResultCards): the thing itself, drawn at LivingLayout.resultHeight. */
@Composable
private fun ResultCardView(r: ResultCard, p: LivingSpec.Palette) = Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
    when (r) {
        is ResultCard.Weather -> WeatherResult(r, p)
        is ResultCard.Place -> PlaceResult(r, p)
        is ResultCard.Alarm -> {
            Header(if (r.timer) tr("Timer set") else tr("Alarm set"), p)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClockMark(p)
                Spacer(Modifier.width(10.dp))
                Text(r.time, color = Color(p.ink), fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            if (r.label.isNotBlank()) Text(r.label, color = Color(p.mute), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        is ResultCard.Event -> {
            Header(tr("Added to your calendar"), p)
            Spacer(Modifier.height(6.dp))
            Text(r.title, color = Color(p.ink), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (r.end.isBlank()) r.start else "${r.start} – ${r.end.substringAfter(' ')}", color = Color(p.mute), fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (r.location.isNotBlank()) { Spacer(Modifier.height(4.dp)); Chip(r.location, p) }
        }
        is ResultCard.Image -> {
            Header(tr("Picture"), p)
            Spacer(Modifier.height(6.dp))
            val img by produceState<ImageBitmap?>(null, r.file) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        com.blaze.assistant.media.Media.find(r.file)?.let { f ->
                            val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                            android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)?.asImageBitmap()
                        }
                    } catch (_: Throwable) { null }
                }
            }
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color(p.bar)), contentAlignment = Alignment.Center) {
                img?.let { Image(it, contentDescription = r.prompt.ifBlank { r.file }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Text(r.prompt.ifBlank { r.file }, color = Color(p.mute), fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(12.dp))
            }
        }
        is ResultCard.Table -> {
            Header(tr("Table"), p)
            Spacer(Modifier.height(6.dp))
            for ((i, row) in (listOf(r.head) + r.rows).withIndex()) Row(Modifier.fillMaxWidth().height(LivingLayout.TABLE_ROW_H.dp)
                .then(if (i == 0) Modifier.clip(RoundedCornerShape(6.dp)).background(Color(p.bar)) else Modifier).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                for (cell in row) Text(cell, color = Color(if (i == 0) p.mute else p.ink), fontSize = 11.sp,
                    fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 4.dp))
            }
            if (r.more > 0) Text(tr("+{0} more rows", r.more.toString()), color = Color(p.mute), fontSize = 11.sp, modifier = Modifier.height(18.dp))
        }
    }
}

/** The weather itself: big temperature, the sky drawn, condition and place, feels-like / humidity / wind, source and time. */
@Composable
private fun WeatherResult(w: ResultCard.Weather, p: LivingSpec.Palette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Header(tr("Weather now"), p)
        Spacer(Modifier.weight(1f))
        if (w.source.isNotBlank()) Header(if (w.time.isBlank()) w.source else "${w.source} · ${w.time}", p)
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text("${w.temp}", color = Color(p.ink), fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(w.unit, color = Color(p.mute), fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
            Text(listOf(w.condition, w.place).filter { it.isNotBlank() }.joinToString(" · "), color = Color(p.ink), fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SkyMark(ResultCards.sky(w.condition), w.night, p)
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        w.feels?.let { Chip(tr("Feels {0}", "$it°"), p) }
        w.humidity?.let { Chip("$it%", p) }
        w.wind?.let { Chip(it, p) }
    }
}

/** Where the phone is: a drawn street grid with a pin (no tiles), the locality, the address and the coordinates. */
@Composable
private fun PlaceResult(r: ResultCard.Place, p: LivingSpec.Palette) {
    Header(tr("Your location"), p)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val line = Color(p.link); val pin = Color(p.orb); val ground = Color(p.bar)
        Canvas(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(ground)) {
            val w = size.width; val sw = 1.2f * density
            for (k in 1..3) { drawLine(line, Offset(0f, w * k / 4f), Offset(w, w * k / 4f - w * 0.08f), sw); drawLine(line, Offset(w * k / 4f, 0f), Offset(w * k / 4f + w * 0.06f, w), sw) }
            drawCircle(pin.copy(alpha = 0.25f), w * 0.2f, Offset(w / 2f, w / 2f))
            drawCircle(pin, w * 0.08f, Offset(w / 2f, w / 2f))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.locality.ifBlank { tr("Here") }, color = Color(p.ink), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.address.isNotBlank()) Text(r.address, color = Color(p.mute), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("%.4f, %.4f".format(java.util.Locale.US, r.lat, r.lon) + (if (r.accuracy.isNotBlank()) "  ${r.accuracy}" else ""),
                color = Color(p.lineNo), fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

@Composable
private fun Chip(text: String, p: LivingSpec.Palette) =
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(p.bar)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(text, color = Color(p.ink), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

/** The sky drawn (no glyphs): sun or moon, a cloud, rain, snow, a bolt or fog lines. */
@Composable
private fun SkyMark(sky: ResultCards.Sky, night: Boolean, p: LivingSpec.Palette) {
    val sun = Color(if (night) p.mute else 0xFFF5B73B); val cloud = Color(p.mute); val wet = Color(p.orb)
    Canvas(Modifier.size(56.dp).semantics { contentDescription = sky.name.lowercase() }) {
        val w = size.width; val sw = 2f * density
        val showSun = sky == ResultCards.Sky.CLEAR || sky == ResultCards.Sky.PARTLY
        if (showSun) {
            val c = if (sky == ResultCards.Sky.CLEAR) Offset(w / 2f, w / 2f) else Offset(w * 0.36f, w * 0.34f)
            val rr = if (sky == ResultCards.Sky.CLEAR) w * 0.22f else w * 0.16f
            drawCircle(sun, rr, c)
            if (night) drawCircle(Color(p.card), rr * 0.8f, Offset(c.x + rr * 0.45f, c.y - rr * 0.3f))
            else for (i in 0 until 8) {
                val a = Math.PI * i / 4
                val x0 = c.x + (rr * 1.35f) * kotlin.math.cos(a).toFloat(); val y0 = c.y + (rr * 1.35f) * kotlin.math.sin(a).toFloat()
                val x1 = c.x + (rr * 1.75f) * kotlin.math.cos(a).toFloat(); val y1 = c.y + (rr * 1.75f) * kotlin.math.sin(a).toFloat()
                drawLine(sun, Offset(x0, y0), Offset(x1, y1), sw, StrokeCap.Round)
            }
        }
        if (sky != ResultCards.Sky.CLEAR) {
            val base = if (sky == ResultCards.Sky.PARTLY) w * 0.66f else w * 0.56f
            drawCircle(cloud, w * 0.16f, Offset(w * 0.36f, base))
            drawCircle(cloud, w * 0.21f, Offset(w * 0.56f, base - w * 0.08f))
            drawCircle(cloud, w * 0.14f, Offset(w * 0.74f, base + w * 0.02f))
            drawRect(cloud, Offset(w * 0.36f, base), androidx.compose.ui.geometry.Size(w * 0.38f, w * 0.16f))
        }
        val low = w * 0.84f
        when (sky) {
            ResultCards.Sky.RAIN -> for (i in 0 until 3) drawLine(wet, Offset(w * (0.38f + 0.14f * i), low - w * 0.06f), Offset(w * (0.34f + 0.14f * i), low + w * 0.06f), sw, StrokeCap.Round)
            ResultCards.Sky.SNOW -> for (i in 0 until 3) drawCircle(wet, w * 0.03f, Offset(w * (0.38f + 0.14f * i), low))
            ResultCards.Sky.STORM -> {
                val path = Path().apply { moveTo(w * 0.56f, low - w * 0.1f); lineTo(w * 0.48f, low + w * 0.02f); lineTo(w * 0.56f, low + w * 0.02f); lineTo(w * 0.5f, low + w * 0.14f) }
                drawPath(path, Color(0xFFF5B73B), style = Stroke(sw, cap = StrokeCap.Round))
            }
            ResultCards.Sky.FOG -> for (i in 0 until 2) drawLine(cloud, Offset(w * 0.28f, low - w * 0.04f + i * w * 0.1f), Offset(w * 0.8f, low - w * 0.04f + i * w * 0.1f), sw, StrokeCap.Round)
            else -> Unit
        }
    }
}

/** A small clock face for alarms and timers. */
@Composable
private fun ClockMark(p: LivingSpec.Palette) {
    val ink = Color(p.orb)
    Canvas(Modifier.size(28.dp)) {
        val w = size.width; val sw = 1.8f * density
        drawCircle(ink, w * 0.42f, Offset(w / 2f, w / 2f), style = Stroke(sw))
        drawLine(ink, Offset(w / 2f, w / 2f), Offset(w / 2f, w * 0.24f), sw, StrokeCap.Round)
        drawLine(ink, Offset(w / 2f, w / 2f), Offset(w * 0.7f, w * 0.58f), sw, StrokeCap.Round)
    }
}

/** Done = a check, failed = a cross, running = a ring (drawn, no glyphs). */
@Composable
private fun StateDot(s: State, p: LivingSpec.Palette) {
    val col = Color(when (s) { State.DONE -> p.ok; State.FAILED -> p.fail; State.RUNNING -> p.orb })
    Canvas(Modifier.size(12.dp).semantics { contentDescription = when (s) { State.DONE -> "Done"; State.FAILED -> "Failed"; State.RUNNING -> "Running" } }) {
        val w = size.width; val sw = 1.6f * density
        when (s) {
            State.DONE -> {
                val path = Path().apply { moveTo(w * 0.15f, w * 0.55f); lineTo(w * 0.42f, w * 0.8f); lineTo(w * 0.88f, w * 0.22f) }
                drawPath(path, col, style = Stroke(sw, cap = StrokeCap.Round))
            }
            State.FAILED -> {
                drawLine(col, Offset(w * 0.2f, w * 0.2f), Offset(w * 0.8f, w * 0.8f), sw, StrokeCap.Round)
                drawLine(col, Offset(w * 0.8f, w * 0.2f), Offset(w * 0.2f, w * 0.8f), sw, StrokeCap.Round)
            }
            State.RUNNING -> drawCircle(col, w * 0.36f, style = Stroke(sw))
        }
    }
}

// ---------------------------------------------------------------------- pill and switch

@Composable
private fun StatusPill(status: String, working: Boolean, p: LivingSpec.Palette, pulse: Boolean, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Row(modifier.heightIn(min = 44.dp).shadow(8.dp, shape, ambientColor = Color(p.shadow), spotColor = Color(p.shadow)).clip(shape)
        .background(Color(p.pill)).border(1.dp, Color(p.pillEdge), shape).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.alpha(if (working) 1f else 0.6f)) { AgentMark(p, pulse) }
        Text(status, color = Color(p.ink), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = tr("Go to the newest step")) { onNext() }
            .background(Color(p.bar)).padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(tr("Next") + " \u2192", color = Color(p.ink), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The `Chat | Living` switch centred over the chatbox (Chat mode, main chat). */
@Composable
fun LivingSwitch(modifier: Modifier = Modifier) {
    val view by LivingSpec.view.collectAsState()
    val dark = LocalBlaze.current.dark
    val p = remember(dark) { LivingSpec.palette(dark) }
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(shape).background(Color(p.pill)).border(1.dp, Color(p.pillEdge), shape).padding(3.dp)) {
            for (v in LivingSpec.View.values()) {
                val on = v == view
                val label = if (v == LivingSpec.View.CHAT) tr("Chat") else tr("Living")
                Box(Modifier.clip(RoundedCornerShape(19.dp)).background(if (on) Color(p.card) else Color.Transparent)
                    .clickable(onClickLabel = label) { LivingSpec.show(v) }
                    .semantics { contentDescription = if (on) "$label, selected" else label }
                    .padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Text(label, color = Color(if (on) p.ink else p.mute), fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
