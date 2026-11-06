package com.blaze.assistant.ui.argus

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.BrandName
import com.blaze.assistant.composer.SpotlightGroups
import com.blaze.assistant.composer.SpotlightRecents
import com.blaze.assistant.composer.SpotlightSearch
import com.blaze.assistant.ui.ArgusBrand
import com.blaze.assistant.ui.DarkColors
import com.blaze.assistant.ui.LocalBlaze
import com.blaze.assistant.ui.MotionTokens
import com.blaze.assistant.ui.Type
import com.blaze.assistant.ui.composer.ComposerState
import com.blaze.assistant.ui.composer.MeAvatar
import com.blaze.assistant.ui.composer.rememberSpotlightActions
import com.blaze.assistant.ui.drawArgusMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The ARGUS Home's colours (docs/BRAND.md, the owner's board): obsidian, Argus Blue, Electric Blue, Steel Gray, white. */
object ArgusHomeTokens {
    val Obsidian = Color(0xFF000000)
    val ArgusBlue = Color(0xFF0056FF)
    val Electric = Color(0xFF1286FF)
    /** Electric Blue for text and icons on obsidian and the glass (8.5:1 on black, 7.7:1 on [Glass]; Electric itself is 5.9:1). */
    val ElectricText = Color(0xFF5AA9FF)
    val Steel = Color(0xFF9AA0B3)
    val Glass = Color(0xE00A1020)
    /** Where the orb sits, as a share of the screen's height. */
    const val ORB_AT = 0.40f
}

/**
 * 0.52.2: is the ARGUS Home in front? The chat shell (ChatScreen) draws the Home over everything else while this is true
 * (settings pages, menus, dialogs, setup and the gate still draw over it); Back from the chat, "home" and /home bring it
 * back, and asking for the full chat (a question that needs it, "Chat" in Spotlight, the up chevron) puts it away.
 */
object ArgusHomeBus {
    val visible = mutableStateOf(true)
    fun show() { visible.value = true }
    fun hide() { visible.value = false }
}

/** What the phone can do, read once (GLES version, RAM, Android's low-RAM flag). */
data class ArgusDevice(val gles3: Boolean, val totalMb: Long, val lowRamFlag: Boolean) {
    companion object {
        fun read(ctx: Context): ArgusDevice = try {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            ArgusDevice(ArgusFps.gles3(am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0), mi.totalMem / (1024L * 1024L), am?.isLowRamDevice == true)
        } catch (_: Throwable) { ArgusDevice(false, 0L, false) }
    }
}

/**
 * The ARGUS living 3D Home (portrait take on the owner's reference): the orb on its pedestal in a dark scene, the
 * ARGUS mark top left and the profile avatar top right (account / settings), the greeting bubble, the floating notch at
 * the top centre that pulls Spotlight down, the result card that floats over the pedestal after an ask, and the prompt
 * bar (mic, "Try saying …", send chevron) wired to the chat's own send path. No left rail.
 *
 * [state] / [level] drive the orb (idle, listening with the mic level, thinking, speaking). [covered]: something is
 * drawn over the Home, so the scene stops drawing. [forceMode] pins the renderer (screenshot tests use the Canvas orb);
 * [hour] pins the greeting's and Spotlight's local hour (tests), else the phone's clock is read and re-read on the hour
 * ([ArgusGreeting.msToNextHour]). Every part carries an [ArgusHomeTags] test tag.
 */
@Composable
fun ArgusHomeScreen(
    state: ArgusScene.State,
    level: Float,
    name: String?,
    composer: ComposerState,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onOpenChat: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
    covered: Boolean = false,
    answer: ArgusAnswer.Card? = null,
    onDismissAnswer: () -> Unit = {},
    apps: () -> List<SpotlightGroups.App> = { emptyList() },
    said: () -> List<SpotlightGroups.Said> = { emptyList() },
    avatar: @Composable (onClick: () -> Unit) -> Unit = { click -> MeAvatar(40, click) },
    forceMode: ArgusFps.Mode? = null,
    hour: Int? = null,
    resultSlot: (@Composable () -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val device = remember { ArgusDevice.read(ctx) }
    val powerSave by produceState(false) {
        while (isActive) { value = try { ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true } catch (_: Throwable) { false }; delay(10_000) }
    }
    val mode = forceMode ?: ArgusFps.mode(device.gles3, device.totalMb, device.lowRamFlag, MotionTokens.enabled)
    val fps = ArgusFps.cap(device.totalMb, device.lowRamFlag, powerSave)
    // the greeting's hour: pinned by [hour], else the clock, re-read at every full hour while the Home is composed
    var clockHour by remember { mutableIntStateOf(ArgusGreeting.hourNow()) }
    LaunchedEffect(hour) {
        if (hour == null) while (isActive) { delay(ArgusGreeting.msToNextHour(System.currentTimeMillis())); clockHour = ArgusGreeting.hourNow() }
    }
    val greetHour = hour ?: clockHour

    // ---- pull-down Spotlight: progress 0 (closed) .. 1 (open), a spring between, a rubber band past 1
    val pull = remember { Animatable(if (composer.spotlightOpen) 1f else 0f) }
    var sheetPx by remember { mutableStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var dragPx by remember { mutableStateOf(0f) }
    var wasOpen by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val pullSpring = spring<Float>(dampingRatio = ArgusPull.Spring().dampingRatio, stiffness = ArgusPull.Spring().stiffness)
    LaunchedEffect(composer.spotlightOpen) {
        if (dragging) return@LaunchedEffect
        val open = composer.spotlightOpen
        if (MotionTokens.enabled) pull.animateTo(if (open) 1f else 0f, pullSpring) else pull.snapTo(if (open) 1f else 0f)
        if (open) { runCatching { focus.requestFocus() }; keyboard?.show() } else keyboard?.hide()
    }
    val pullDrag = rememberDraggableState { d ->
        if (!dragging) { dragging = true; wasOpen = composer.spotlightOpen; dragPx = if (wasOpen) sheetPx else 0f }
        dragPx += d
        scope.launch { pull.snapTo(ArgusPull.progress(dragPx, sheetPx)) }
    }
    fun release(velocityPx: Float) {
        val open = ArgusPull.settleOpen(pull.value, velocityPx / density, wasOpen)
        dragging = false
        if (open) composer.openSpotlight() else composer.closeSpotlight()
        scope.launch {
            pull.animateTo(if (open) 1f else 0f, pullSpring, initialVelocity = velocityPx / sheetPx.coerceAtLeast(1f))
            if (open) { runCatching { focus.requestFocus() }; keyboard?.show() } else keyboard?.hide()
        }
    }
    BackHandler(enabled = composer.spotlightOpen) { composer.closeSpotlight() }

    // ---- the Spotlight's data: phone apps and chat lines, read off the main thread when it opens
    val appList by produceState(emptyList<SpotlightGroups.App>(), composer.spotlightOpen) {
        if (composer.spotlightOpen) value = withContext(Dispatchers.IO) { runCatching { apps() }.getOrDefault(emptyList()) }
    }
    val saidList by produceState(emptyList<SpotlightGroups.Said>(), composer.spotlightOpen) {
        if (composer.spotlightOpen) value = withContext(Dispatchers.Default) { runCatching { said() }.getOrDefault(emptyList()) }
    }
    var recent by remember { mutableStateOf(readRecent()) }
    val actions = rememberSpotlightActions(composer, onLeaveHome = {}, onAttach = {})
    fun runRow(r: SpotlightSearch.Row) {
        if (composer.query.isNotBlank() && r.act !is SpotlightSearch.Act.Complete) { recent = SpotlightRecents.push(recent, composer.query); saveRecent(recent) }
        val pkg = SpotlightGroups.appPackage(r.act)
        if (pkg != null) { composer.closeSpotlight(); com.blaze.assistant.phone.InstalledApps.launch(ctx, pkg); return }
        when (val a = r.act) {
            is SpotlightSearch.Act.Complete -> composer.query = a.text
            // the Home sheet has no inline previews: a preview opens the real view
            is SpotlightSearch.Act.Preview -> actions.run(SpotlightSearch.Act.OpenView(a.view, a.arg))
            is SpotlightSearch.Act.Browse -> { composer.closeSpotlight(); com.blaze.assistant.ui.SelectionDo.openInBlazeBrowser(ctx, a.query.ifBlank { "https://www.google.com" }) }
            is SpotlightSearch.Act.RunCommand -> actions.run(SpotlightSearch.Act.AskBlaze("Run this in the terminal (${a.distro}): ${a.command}"))
            else -> actions.run(a)
        }
    }

    // ---- optional tilt parallax (Settings › Look & feel), never with reduce motion or battery saver
    val parallaxOn = ArgusParallax.enabled(remember { readBool("argus_parallax", false) }, MotionTokens.enabled, powerSave) && mode == ArgusFps.Mode.GL && !covered
    var tilt by remember { mutableStateOf(0f to 0f) }
    if (parallaxOn) DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val rot = FloatArray(9); val ori = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values); SensorManager.getOrientation(rot, ori)
                val (x, y) = ArgusParallax.offset(ori[1], ori[2])
                tilt = ArgusParallax.smooth(tilt.first, x) to ArgusParallax.smooth(tilt.second, y)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm?.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(l); tilt = 0f to 0f }
    }

    CompositionLocalProvider(LocalBlaze provides DarkColors) {
        // the Home takes every touch on it, so nothing under it (the chat shell) is hit through the scene
        BoxWithConstraints(modifier.fillMaxSize().testTag(ArgusHomeTags.ROOT).background(ArgusHomeTokens.Obsidian)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
            val h = maxHeight
            // 1. the scene (stops drawing when covered or when Spotlight is all the way down)
            ArgusSceneView(state, level, mode, fps, visible = !covered && pull.value < 0.98f, orbAt = ArgusHomeTokens.ORB_AT, parallax = tilt,
                modifier = Modifier.fillMaxSize().testTag(ArgusHomeTags.SCENE))

            // 2. top: the mark + wordmark (left), the avatar (right)
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 14.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(26.dp).testTag(ArgusHomeTags.MARK).semantics { contentDescription = BrandName.UPPER }) { drawArgusMark(Offset(size.width / 2f, size.height / 2f), size.width) }
                Spacer(Modifier.width(8.dp))
                Text(BrandName.UPPER, style = ArgusBrand.wordmark(13.sp, Color.White))
                Spacer(Modifier.weight(1f))
                Box(Modifier.testTag(ArgusHomeTags.AVATAR)) { avatar(onProfile) }
            }

            // 3. the notch: pull it down (spring) or tap it for Spotlight
            Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 10.dp).size(width = 88.dp, height = 30.dp).testTag(ArgusHomeTags.NOTCH)
                .clip(RoundedCornerShape(15.dp))
                .draggable(pullDrag, Orientation.Vertical, onDragStopped = { v -> release(v) })
                .clickable(onClickLabel = "Open Spotlight") { composer.openSpotlight() }
                .semantics { contentDescription = "Spotlight search. Pull down or double-tap to open"; role = Role.Button },
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 56.dp, height = 20.dp).clip(RoundedCornerShape(10.dp)).background(ArgusHomeTokens.Glass)
                    .border(1.dp, ArgusHomeTokens.Electric.copy(alpha = 0.45f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 26.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.7f)))
                }
            }

            // 4. the greeting bubble beside the orb
            val hello = remember(greetHour, name) { ArgusGreeting.hello(greetHour, name) }
            Row(Modifier.align(Alignment.TopEnd).padding(top = h * 0.15f, end = 18.dp).widthIn(max = 240.dp).testTag(ArgusHomeTags.GREETING)
                .clip(RoundedCornerShape(18.dp)).background(ArgusHomeTokens.Glass)
                .border(1.dp, ArgusHomeTokens.Electric.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(18.dp)) { drawArgusMark(Offset(size.width / 2f, size.height / 2f), size.width * 1.2f) }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(hello, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Color.White))
                    Text(ArgusGreeting.ask(state), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = ArgusHomeTokens.Steel))
                }
            }

            // 5. the result card over the pedestal (or a custom card in [resultSlot])
            Box(Modifier.align(Alignment.TopCenter).padding(top = h * 0.60f, start = 20.dp, end = 20.dp).fillMaxWidth()) {
                AnimatedVisibility(resultSlot != null || answer != null, enter = fadeIn() + slideInVertically { it / 4 }, exit = fadeOut() + slideOutVertically { it / 4 }) {
                    if (resultSlot != null) resultSlot() else if (answer != null) AnswerCard(answer, onOpenChat, onDismissAnswer)
                }
            }

            // 6. the prompt bar
            PromptBar(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 14.dp).testTag(ArgusHomeTags.PROMPT),
                listening = state == ArgusScene.State.LISTENING, onSend = onSend, onMic = onMic, onOpenChat = onOpenChat)

            // 7. Spotlight, pulled down from the notch
            val p = pull.value
            if (p > 0.001f || composer.spotlightOpen) {
                Box(Modifier.fillMaxSize().testTag(ArgusHomeTags.SCRIM).background(Color.Black.copy(alpha = ArgusPull.scrim(p)))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = "Close Spotlight") { composer.closeSpotlight() })
                ArgusSpotlightPanel(composer, appList, saidList, recent, greetHour, focus, pullDrag, onDragStopped = { v -> release(v) },
                    onRow = { r -> runRow(r) }, onClose = { composer.closeSpotlight() },
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp).testTag(ArgusHomeTags.SPOTLIGHT)
                        .onSizeChanged { sheetPx = it.height.toFloat().coerceAtLeast(1f) }
                        .graphicsLayer {
                            translationY = (p.coerceAtMost(1f) - 1f) * (sheetPx + 48f * density) + (p - 1f).coerceAtLeast(0f) * sheetPx * 0.5f
                            alpha = (p * 1.6f).coerceIn(0f, 1f)
                        },
                    maxListHeight = (h * 0.55f))
            }
        }
    }
}

@Composable
private fun AnswerCard(card: ArgusAnswer.Card, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().testTag(ArgusHomeTags.ANSWER).clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xE6081A40), Color(0xF0050A16))))
        .border(1.dp, ArgusHomeTokens.Electric.copy(alpha = 0.55f), shape)
        .clickable(onClickLabel = "Open the answer in the chat") { onOpen() }
        .padding(16.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(card.ask, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = ArgusHomeTokens.Steel))
            Box(Modifier.size(32.dp).testTag(ArgusHomeTags.ANSWER_DISMISS).clip(CircleShape).clickable(onClick = onDismiss).semantics { contentDescription = "Dismiss the answer"; role = Role.Button },
                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, null, tint = ArgusHomeTokens.Steel, modifier = Modifier.size(16.dp)) }
        }
        Spacer(Modifier.height(4.dp))
        Text(if (card.text.isBlank()) ArgusGreeting.ask(ArgusScene.State.THINKING) else card.text, maxLines = 5, overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, color = Color.White))
        Spacer(Modifier.height(8.dp))
        Text("Open in chat \u203a", style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = ArgusHomeTokens.ElectricText))
    }
}

@Composable
private fun PromptBar(modifier: Modifier, listening: Boolean, onSend: (String) -> Unit, onMic: () -> Unit, onOpenChat: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var hint by remember { mutableIntStateOf(0) }
    LaunchedEffect(text.isEmpty()) { while (text.isEmpty() && MotionTokens.enabled) { delay(ArgusGreeting.HINT_MS); hint++ } }
    fun send() { val t = text.trim(); if (t.isNotEmpty()) { text = ""; onSend(t) } }
    val shape = RoundedCornerShape(28.dp)
    Row(modifier.fillMaxWidth().height(56.dp).clip(shape).background(ArgusHomeTokens.Glass)
        .border(1.dp, Brush.horizontalGradient(listOf(ArgusHomeTokens.ArgusBlue.copy(alpha = 0.9f), ArgusHomeTokens.Electric.copy(alpha = 0.35f))), shape)
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).testTag(ArgusHomeTags.MIC).clip(CircleShape)
            .background(if (listening) Brush.linearGradient(listOf(ArgusHomeTokens.ArgusBlue, ArgusHomeTokens.Electric)) else SolidColor(Color.White.copy(alpha = 0.08f)))
            .clickable(onClick = onMic).semantics { contentDescription = if (listening) "Stop listening" else "Talk"; role = Role.Button },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Mic, null, tint = if (listening) Color.White else ArgusHomeTokens.ElectricText, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(ArgusGreeting.hint(hint), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = ArgusHomeTokens.Steel))
            BasicTextField(text, { text = it }, singleLine = true,
                textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = Color.White),
                cursorBrush = SolidColor(ArgusHomeTokens.Electric),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth().testTag(ArgusHomeTags.PROMPT_FIELD).semantics { contentDescription = "Message ${BrandName.NAME}" })
        }
        Spacer(Modifier.width(6.dp))
        val canSend = text.isNotBlank()
        Box(Modifier.size(40.dp).testTag(ArgusHomeTags.SEND).clip(CircleShape)
            .background(if (canSend) Brush.linearGradient(listOf(ArgusHomeTokens.ArgusBlue, ArgusHomeTokens.Electric)) else SolidColor(Color.Transparent))
            .clickable { if (canSend) send() else onOpenChat() }
            .semantics { contentDescription = if (canSend) "Send" else "Open the chat"; role = Role.Button },
            contentAlignment = Alignment.Center) {
            Icon(if (canSend) Icons.Rounded.ChevronRight else Icons.Rounded.KeyboardArrowUp, null,
                tint = if (canSend) Color.White else ArgusHomeTokens.ElectricText, modifier = Modifier.size(22.dp))
        }
    }
}

private fun readBool(key: String, def: Boolean): Boolean = try { com.blaze.assistant.agent.Settings.bool(key, def) } catch (_: Throwable) { def }

private fun readRecent(): List<String> = try { SpotlightRecents.decode(com.blaze.assistant.agent.Settings.str(SpotlightRecents.PREF, "")) } catch (_: Throwable) { emptyList() }

private fun saveRecent(list: List<String>) { try { com.blaze.assistant.agent.Settings.setStr(SpotlightRecents.PREF, SpotlightRecents.encode(list)) } catch (_: Throwable) { } }
