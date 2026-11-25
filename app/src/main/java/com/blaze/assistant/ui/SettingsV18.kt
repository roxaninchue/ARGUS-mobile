package com.blaze.assistant.ui


import com.blaze.assistant.i18n.tr
import com.blaze.assistant.BrandName
import com.blaze.assistant.proactive.ProactiveDial
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.IntentCache
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.device.CrashLog
import com.blaze.assistant.device.DeviceStatus
import com.blaze.assistant.device.SelfCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

@Composable
internal fun V18Note(text: String, top: Int = 0) {
    val c = LocalBlaze.current.mono
    Text(text, modifier = Modifier.padding(top = top.dp, bottom = 10.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub))
}

@Composable
internal fun V18Button(text: String, primary: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val c = LocalBlaze.current.mono
    Box(modifier.height(44.dp).then(if (primary) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) { ButtonText(text, color = if (primary) c.paper else c.ink, size = 13.sp) }
}

@Composable
internal fun V18Section(title: String) { Spacer(Modifier.height(24.dp)); Label(title, modifier = Modifier.padding(bottom = 8.dp)) }

@Composable
internal fun Mono(text: String) {
    val c = LocalBlaze.current.mono
    Text(text, modifier = Modifier.fillMaxWidth().hairline(c.hair).padding(12.dp),
        style = TextStyle(fontFamily = Type.Mono, fontSize = 12.sp, lineHeight = 18.sp, color = c.ink))
}

// ---------------- Dashboard ----------------

@Composable
fun DashboardPage() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val running by com.blaze.assistant.dashboard.Dashboard.running.collectAsState()
    var lan by remember { mutableStateOf(com.blaze.assistant.dashboard.Dashboard.lan) }
    var auto by remember { mutableStateOf(Settings.bool("dash_auto", false)) }
    V18Note("A web page served by ${BrandName.NAME} on your phone: chat with ${BrandName.NAME} (it can still use your phone), see tokens and cost per day, phone health, memories, skills and jobs.")
    V18Button(if (running) "Open dashboard" else "Start & open dashboard") { com.blaze.assistant.dashboard.Dashboard.open(ctx) }
    Spacer(Modifier.height(8.dp))
    if (running) V18Button("Stop server", primary = false) { com.blaze.assistant.dashboard.Dashboard.stop() }
    Spacer(Modifier.height(10.dp))
    ToggleRow("Share on Wi-Fi", "Open it from a laptop on the same network (link has a secret token)", lan) {
        lan = !lan; com.blaze.assistant.dashboard.Dashboard.lan = lan; if (running) com.blaze.assistant.dashboard.Dashboard.restart(ctx) }
    ToggleRow("Start with ${BrandName.NAME}", "Keep the dashboard running in the background", auto) { auto = !auto; Settings.setBool("dash_auto", auto) }
    var gen by remember { mutableStateOf(0) }
    if (running) {
        V18Section("Links")
        key(gen) { Mono(com.blaze.assistant.dashboard.Dashboard.localUrl() + (if (lan) "\n" + (com.blaze.assistant.dashboard.Dashboard.lanUrl() ?: "(not on Wi-Fi)") else "")) }
        Spacer(Modifier.height(8.dp))
        // v0.45.0: old links (and any copy seen on the network) stop working at once.
        V18Button("Make a new link", primary = false) { com.blaze.assistant.dashboard.Dashboard.rotate(); gen++ }
        V18Note("Wi-Fi links expire when the server stops or sharing is switched off.")
        if (lan) com.blaze.assistant.dashboard.Dashboard.lanUrl()?.let { u ->
            Spacer(Modifier.height(8.dp))
            V18Button("Copy Wi-Fi link", primary = false) {
                ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("${BrandName.NAME} dashboard", u)) }
            // v0.33.0 Desktop Bridge: scan with the laptop / tablet camera to open Argus on the big screen.
            val qr = remember(u) { com.blaze.assistant.dashboard.Bridge.qr(u)?.let { it.asImageBitmap() } }
            if (qr != null) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.Image(qr, "QR code for the Desktop Bridge link", modifier = Modifier.size(200.dp)
                    .background(androidx.compose.ui.graphics.Color.White).padding(8.dp))
                V18Note("Desktop Bridge: on your laptop, open this link (or scan the code). You get your chats, the phone's clipboard both ways, ${BrandName.NAME}'s files and uploads into Download/Blaze.", top = 6)
            }
        }
    }
}

// ---------------- Proactive ----------------

@Composable
fun ProactivePage() {
    val c = LocalBlaze.current.mono
    val ctx = LocalContext.current
    val P = com.blaze.assistant.proactive.Proactive
    var on by remember { mutableStateOf(P.enabled) }
    var speak by remember { mutableStateOf(P.speak) }
    var every by remember { mutableStateOf(P.everyMin) }
    V18Note("${BrandName.NAME} watches what's new (notifications, calendar, phone health) and speaks up on its own when something matters: an urgent message, a meeting soon, a phone problem. Most checks stay silent. Urgent-looking messages are checked right away.")
    // Owner 2026-11-19: one switch pauses every message ARGUS starts on its own; switching it back brings them all back.
    var paused by remember { mutableStateOf(P.paused) }
    ToggleRow("Pause heads-ups", ProactiveDial.pausedNote(paused), paused) { paused = !paused; P.paused = paused }
    ToggleRow("Speak up on my own", "Heads-ups with a one-tap action", on) { on = !on; P.enabled = on }
    ToggleRow("Say it out loud", "Read heads-ups aloud when the ringer is on (urgent ones always)", speak) { speak = !speak; P.speak = speak }
    Spacer(Modifier.height(12.dp))
    Label(tr("Check every"), modifier = Modifier.padding(bottom = 8.dp))
    Row(Modifier.fillMaxWidth()) {
        listOf(15, 30, 45, 90, 180).forEach { m ->
            Box(Modifier.weight(1f).height(40.dp).then(if (every == m) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f)).clickable { every = m; P.everyMin = m },
                contentAlignment = Alignment.Center) { ButtonText(if (m < 60) "${m}m" else "${m / 60}h", color = if (every == m) c.paper else c.ink, size = 13.sp) }
        }
    }
    var qf by remember { mutableStateOf(P.quietFrom) }; var qt by remember { mutableStateOf(P.quietTo) }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label(tr("Quiet hours"), modifier = Modifier.weight(1f))
        V18Button("%02d:00".format(qf), primary = false, modifier = Modifier.width(84.dp)) { qf = (qf + 1) % 24; P.quietFrom = qf }
        Label("  to  ", size = 10.sp)
        V18Button("%02d:00".format(qt), primary = false, modifier = Modifier.width(84.dp)) { qt = (qt + 1) % 24; P.quietTo = qt }
    }
    V18Note("Only urgent things get through in quiet hours. Tap a time to change it.", top = 6)
    // Unreleased after 0.52.3: the proactivity dial, next to quiet hours (ProactiveDial; applied where Proactive.say drops).
    var dial by remember { mutableStateOf(P.level) }
    Spacer(Modifier.height(12.dp))
    Label(tr("How proactive"), modifier = Modifier.padding(bottom = 8.dp))
    com.blaze.assistant.ui.space.SpaceSegmented(ProactiveDial.Level.values().map { it.id to it.label }, dial.id,
        Modifier.fillMaxWidth(), tag = "proactive_level") { id -> dial = ProactiveDial.Level.of(id); P.level = dial }
    V18Note(ProactiveDial.note(dial) + " Every heads-up says why it came.", top = 6)
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    if (pm?.isIgnoringBatteryOptimizations(ctx.packageName) != true) {
        Spacer(Modifier.height(8.dp))
        V18Note("Samsung and others put apps to sleep. Let ${BrandName.NAME} run in the background so it can speak up and keep the wake word alive.")
        V18Button("Allow background running", primary = false) {
            try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }

    V18Section("Jarvis mode")
    val J = com.blaze.assistant.proactive.Jarvis
    var lvl by remember { mutableStateOf(J.level) }
    var looks by remember { mutableStateOf(J.cameraLooks) }
    V18Note("${BrandName.NAME} reaches out on its own: a question now and then to get to know you (reply right in the notification), help offered in the moment, and routines it sets up for itself. Jarvis sets them up without asking; the others offer first.")
    Row(Modifier.fillMaxWidth()) {
        com.blaze.assistant.proactive.JarvisLogic.Level.values().forEach { l ->
            Box(Modifier.weight(1f).height(40.dp).then(if (lvl == l) Modifier.background(c.ink, PageGlass.row).hairline(c.ink, PageGlass.row) else Modifier.pageGlass(c, PageGlass.row, alpha = 0.06f)).clickable { lvl = l; J.level = l },
                contentAlignment = Alignment.Center) { ButtonText(l.label, color = if (lvl == l) c.paper else c.ink, size = 13.sp) }
        }
    }
    Spacer(Modifier.height(8.dp))
    ToggleRow("Look through the camera", "One front-camera frame while ${BrandName.NAME} is open; never saved. Android shows its camera dot", looks) { looks = !looks; J.cameraLooks = looks }
    if (looks && androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
        V18Note("Camera looks need the camera permission: open ${BrandName.NAME} Camera once and allow it.", top = 4)

    V18Section("Triggers")
    val triggers by com.blaze.assistant.proactive.Triggers.all.collectAsState()
    V18Note("\u201cWhen X happens, do Y.\u201d Ask ${BrandName.NAME} in chat, e.g. \u201cwhen battery drops below 20, turn on saver\u201d.")
    if (triggers.isNotEmpty()) Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        triggers.forEach { t ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { com.blaze.assistant.proactive.Triggers.toggle(t.id) }) {
                    Text(t.name, style = TextStyle(fontFamily = Type.Display, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (t.enabled) c.ink else c.weak))
                    Text("When ${t.event.replace('_', ' ')}${if (t.match.isNotBlank()) " · ${t.match}" else ""} → ${t.prompt}", maxLines = 2,
                        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                }
                ActionText("×", color = c.sub, size = 14.sp, modifier = Modifier.clickable { com.blaze.assistant.proactive.Triggers.remove(t.id) }.padding(8.dp))
            }
        }
    }

    V18Section("Recent heads-ups")
    val recent by P.recent.collectAsState()
    if (recent.isEmpty()) V18Note("Nothing yet.")
    else Column(Modifier.fillMaxWidth().hairline(c.hair)) {
        val f = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
        recent.asReversed().take(10).forEach { h ->
            Row(Modifier.fillMaxWidth().hairlineBottom(c.hair).padding(12.dp)) {
                Label(f.format(java.util.Date(h.time)), color = if (h.urgent) c.accent else c.weak, size = 9.sp, modifier = Modifier.width(70.dp))
                Text(h.text, style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.ink))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    V18Button("Check now", primary = false) { P.tick(force = true) }
}

@Composable
internal fun V18Field(hint: String, value: String, onChange: (String) -> Unit) {
    val c = LocalBlaze.current.mono
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp).hairline(c.hair).padding(12.dp)) {
        if (value.isEmpty()) Text(hint, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.weak))
        androidx.compose.foundation.text.BasicTextField(value, onChange, textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.ink),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun VoiceExtras() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var loop by remember { mutableStateOf(Settings.bool("voice_loop", true)) }
    V18Section("Hands-free")
    var conf by remember { mutableStateOf(Settings.str("wake_conf", "0.88")) }
    Label(tr("\u201cHey {0}\u201d sensitivity", BrandName.NAME), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        listOf("0.94" to "Strict", "0.88" to "Normal", "0.78" to "Easy").forEach { (v, l) ->
            Box(Modifier.weight(1f).height(40.dp).background(if (conf == v) LocalBlaze.current.ink else LocalBlaze.current.paper, BlazeShapes.medium).hairline(LocalBlaze.current.hair)
                .clickable { conf = v; Settings.setStr("wake_conf", v) }, contentAlignment = Alignment.Center) {
                Label(l, color = if (conf == v) LocalBlaze.current.paper else LocalBlaze.current.ink, size = 10.sp) }
        }
    }
    V18Note("Strict wakes only on a clear \u201cHey ${BrandName.NAME}\u201d. Use Easy if it misses you.")
    var gate by remember { mutableStateOf(Settings.bool("wake_gate", true)) }
    var off by remember { mutableStateOf(Settings.bool("wake_screen_off", false)) }
    var chg by remember { mutableStateOf(Settings.bool("wake_charging", true)) }
    var pk by remember { mutableStateOf(Settings.bool("wake_pocket", true)) }
    V18Section("Battery saver")
    ToggleRow("Sound gate", "Only process audio when someone's speaking. Saves battery in quiet rooms", gate) { gate = !gate; Settings.setBool("wake_gate", gate); com.blaze.assistant.voice.WakeWordService.start(ctx) }
    ToggleRow("Listen with screen off", "Off: the mic switches off when the screen does", off) { off = !off; Settings.setBool("wake_screen_off", off); com.blaze.assistant.voice.WakeWordService.start(ctx) }
    if (!off) ToggleRow("\u2026but listen while charging", "Keep listening with the screen off while plugged in", chg) { chg = !chg; Settings.setBool("wake_charging", chg); com.blaze.assistant.voice.WakeWordService.start(ctx) }
    ToggleRow("Pause in pocket", "Mic off while the phone is face down or in a pocket", pk) { pk = !pk; Settings.setBool("wake_pocket", pk); com.blaze.assistant.voice.WakeWordService.start(ctx) }
    ToggleRow("Conversation mode", "After ${BrandName.NAME} answers out loud, it listens again so you can keep talking", loop) { loop = !loop; Settings.setBool("voice_loop", loop) }
}

@Composable
fun CostCap() {
    var cap by remember { mutableStateOf(Settings.str("daily_cap", "")) }
    V18Section("Daily spend cap")
    V18Note("When today's estimated cost passes this (in US dollars), ${BrandName.NAME} switches to the free Auto chain until tomorrow. Leave blank for no cap.")
    V18Field("e.g. 0.50", cap) { v -> cap = v.filter { it.isDigit() || it == '.' }; Settings.setStr("daily_cap", cap) }
}
