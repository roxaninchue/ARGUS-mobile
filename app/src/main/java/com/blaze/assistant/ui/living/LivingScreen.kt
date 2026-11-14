package com.blaze.assistant.ui.living

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
 * Living UI v1 (LivingSpec.VERSION): the main chat's work as a canvas, in Chat's place (the chatbox stays).
 *
 *  - One finger pans, two pinch-zoom (0.25×–3×), a double tap fits everything, a tap on a card zooms in on it and a
 *    second tap goes back.
 *  - The canvas follows new cards until you pan; the pill's Next turns following back on and goes to the active card.
 *  - Performance: only the cards touching the screen are composed (the visible set is a derived state, so a pan only
 *    recomposes when a card enters or leaves); the camera is read in draw and in graphicsLayer, never in composition;
 *    the dot grid thins out when zoomed out; the one animation (the pulse on the active card) runs only while working,
 *    while the app is resumed and without reduced motion. Nothing here runs while Chat shows.
 */
@Composable
fun LivingScreen(working: Boolean, keep: ((ChatMessage) -> Boolean)?, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val dark = LocalBlaze.current.dark
    val p = remember(dark) { LivingSpec.palette(dark) }
    val messages by Agent.messages.collectAsState()
    val chatHere by Agent.chatId.collectAsState()
    val trail by ToolTrail.results.collectAsState()

    // Living reads each step's result, so the trail records while it is open (back to the setting when it closes).
    DisposableEffect(Unit) {
        ToolTrail.on = true
        onDispose { ToolTrail.on = Settings.toolRows }
    }

    val board = remember(messages, trail, chatHere, working, keep) {
        LivingModel.board(messages.asSequence().filter { keep?.invoke(it) != false && it.meta.startsWith(com.blaze.assistant.agent.BgAgents.CARD).not() }
            .mapNotNull { m ->
                val r = when (m.role) { Role.USER -> LivingModel.Role.USER; Role.BLAZE -> LivingModel.Role.BLAZE; Role.STEP -> LivingModel.Role.STEP; else -> null }
                r?.let { LivingModel.Msg(m.id, it, m.text, if (m.role == Role.STEP) m.meta else "", m.at, ack = m.role == Role.BLAZE && m.meta == com.blaze.assistant.agent.Ack.META) }
            }.toList(), working, trail[chatHere].orEmpty(),
            label = { name, running -> StepTool.label(name).let { if (running) it.now else it.done } })
    }
    val plan = remember(board) { LivingLayout.plan(board) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifeState by lifecycle.currentStateAsState()
    val animate = working && !reducedMotion && lifeState.isAtLeast(Lifecycle.State.RESUMED)

    BoxWithConstraints(modifier.fillMaxSize().background(Color(p.canvas))) {
        val density = LocalDensity.current.density
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val pad = 16f * density
        val top = 64f * density      // under the pill
        val bottom = 12f * density

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
            glide(LivingLayout.follow(b, density, vw, vh, pad, top, bottom))
        }

        // follow new cards until the user pans
        LaunchedEffect(plan, following, vw, vh) { if (following) goActive() }

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
                    onDoubleTap = { following = false; zoomedOn = null; glide(LivingLayout.fit(planNow, density, vw, vh, pad, top)) },
                    onTap = { o ->
                        val b = LivingLayout.hit(planNow, cam, density, o.x, o.y)
                        if (b == null || zoomedOn == b.key) {
                            before?.let { glide(it) }; zoomedOn = null; before = null
                        } else {
                            following = false
                            if (zoomedOn == null) before = cam
                            zoomedOn = b.key
                            glide(LivingLayout.focus(b, density, vw, vh, pad, top))
                        }
                    })
            }
            .semantics { contentDescription = tr("Living canvas: what {0} is working on", com.blaze.assistant.BrandName.NAME) }
        ) {
            dots(cam, density, dotColor)
            val stroke = Stroke(width = (1.2f * density).coerceAtLeast(1f), cap = StrokeCap.Round)
            for (l in plan.links) drawPath(linkPath(l, cam, density), linkColor, style = stroke)
        }

        // the cards (only those touching the screen)
        for (b in plan.boxes) {
            if (b.key !in visible) continue
            key(b.key) {
                CardHost(b, { cam }, density, p, active = b.key == board.active && working, pulse = animate)
            }
        }

        if (plan.boxes.isEmpty()) Text(tr("When {0} works, you watch it here: the pages it reads, the files it writes and what it runs.", com.blaze.assistant.BrandName.NAME),
            color = Color(p.mute), fontSize = 14.sp, modifier = Modifier.align(Alignment.Center).padding(32.dp))

        StatusPill(board.status, working, p, pulse = animate, onNext = { following = true; goActive() },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 16.dp, end = 16.dp))
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
private fun CardHost(b: CardBox, cam: () -> Camera, density: Float, p: LivingSpec.Palette, active: Boolean, pulse: Boolean) {
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
                Kind.BROWSER -> BrowserCard(b.card, p)
                Kind.FILE -> FileCard(b.card, p)
                Kind.LOG -> LogCard(b.card, p)
                Kind.ANSWER -> AnswerCard(b.card, p)
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

@Composable
private fun BrowserCard(c: Card, p: LivingSpec.Palette) = Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(p.bar)).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        StateDot(c.state, p)
        Spacer(Modifier.width(8.dp))
        Text(c.url.ifBlank { tr("Search") }, color = Color(p.ink), fontSize = 12.sp, fontFamily = if (c.flag) FontFamily.Default else FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Column(Modifier.padding(horizontal = 14.dp)) {
        Text(c.title, color = Color(p.ink), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(c.text.ifBlank { if (c.state == State.RUNNING) tr("Loading\u2026") else "" }, color = Color(p.mute), fontSize = 12.sp, lineHeight = 16.sp,
            maxLines = 8, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun LogCard(c: Card, p: LivingSpec.Palette) = Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
    Header(tr("Steps"), p)
    Spacer(Modifier.height(6.dp))
    for (r in c.rows) Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        StateDot(r.state, p)
        Spacer(Modifier.width(8.dp))
        Text(r.text, color = Color(if (r.state == State.FAILED) p.fail else p.ink), fontSize = 12.sp,
            fontFamily = if (r.command) FontFamily.Monospace else FontFamily.Default, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AnswerCard(c: Card, p: LivingSpec.Palette) = Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
    Header(tr("Answer"), p)
    Spacer(Modifier.height(4.dp))
    Text(c.text, color = Color(p.ink), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 9, overflow = TextOverflow.Ellipsis)
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
            Text(tr("Next"), color = Color(p.ink), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
