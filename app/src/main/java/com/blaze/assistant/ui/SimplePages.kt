package com.blaze.assistant.ui


import com.blaze.assistant.i18n.tr
import kotlin.math.roundToInt
import com.blaze.assistant.BrandName
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.DoingLogic
import com.blaze.assistant.agent.SimpleMode
import java.util.Calendar

/** Settings: the Simple / Advanced switch at the top of the list, and what Simple keeps out of sight. */
@Composable
fun SimpleAdvancedSwitch() {
    val c = LocalBlaze.current.mono
    val simple by SimpleMode.simpleFlow.collectAsState()
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).glass(RoundedCornerShape(26.dp), GlassIntensity.Low, shadow = false).padding(4.dp)) {
        listOf(true to "Simple", false to "Advanced").forEach { (v, label) ->
            val on = simple == v
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).let { if (on) it.background(c.ink) else it }
                .clickable { SimpleMode.set(v) }.semantics { contentDescription = "$label mode" }, contentAlignment = Alignment.Center) {
                Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (on) c.paper else c.ink))
            }
        }
    }
    Text(if (simple) "Hidden in Advanced: ${SimpleMode.HIDDEN_LINE}. Everything still works, and ${BrandName.NAME} can still open any of it."
        else "Everything is showing, including ${SimpleMode.HIDDEN_LINE}.",
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.sub))
}

/** "Things Blaze is doing": routines and reminders with friendly names and an on/off switch, then "Today I…". */
@Composable
fun DoingPage() {
    val c = LocalBlaze.current.mono
    val routines by com.blaze.assistant.routine.Routines.all.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    Spacer(Modifier.height(10.dp))
    Text(tr("Turn any of these off with one tap."), style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, color = c.sub))
    Spacer(Modifier.height(14.dp))
    Label(tr("Coming up"))
    Spacer(Modifier.height(6.dp))
    if (routines.isEmpty()) Text(tr("Nothing scheduled yet. Ask me, for example \u201cevery weekday at 7, read me the morning brief\u201d."),
        modifier = Modifier.padding(vertical = 10.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.body))
    routines.sortedByDescending { it.enabled }.forEach { r ->
        ToggleRow(DoingLogic.friendlyName(r.name), DoingLogic.whenLine(r.days, r.hour, r.minute, r.at, r.everyMinutes), r.enabled, icon = MenuIcon.TIMER.vector()) {
            com.blaze.assistant.routine.Routines.setEnabled(r.id, !r.enabled)
        }
    }
    Spacer(Modifier.height(22.dp))
    val lines = remember { DoingLogic.today(com.blaze.assistant.agent.Awareness.recent(60)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(tr("Today I\u2026"), modifier = Modifier.weight(1f))
        if (lines.size > 6) ActionText(if (showAll) "Show less" else "See all", modifier = Modifier.clickable { showAll = !showAll }.padding(8.dp))
    }
    Text(DoingLogic.summary(lines), modifier = Modifier.padding(vertical = 8.dp),
        style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, color = c.ink))
    (if (showAll) lines.reversed() else lines.takeLast(6).reversed()).forEach { l ->
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
            Text(l.time, modifier = Modifier.width(56.dp), style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = c.sub))
            Text(l.text, style = TextStyle(fontFamily = Type.Sans, fontSize = 15.sp, lineHeight = 21.sp, color = c.body))
        }
    }
}

/**
 * Settings › System Preferences › Appearance: Light / Dark / Auto, the accent and Stage. 0.52.2: the living backgrounds
 * and the Living Themes are gone. 0.52.3: the Home's tilt parallax left with the 3D Home (the Home follows Light / Dark).
 */
@Composable
fun LookPage() {
    val c = LocalBlaze.current.mono
    val accent by Look.accent.collectAsState()
    Spacer(Modifier.height(10.dp))
    // P1-SH-06: in the ARGUS look, Appearance is the ARGUS section (look switch, Match phone / Dark / Light, ground,
    // handedness, reduced motion; no accent), then Size and Stage below as before.
    val argus = androidx.compose.runtime.remember { com.blaze.assistant.ui.argus.Argus.lookOn() }
    if (argus) androidx.compose.runtime.CompositionLocalProvider(com.blaze.assistant.ui.argus.LocalArgusColors provides com.blaze.assistant.ui.argus.ArgusColors.of(c.dark)) {
        com.blaze.assistant.ui.argus.settings.ArgusAppearanceLive()
    }
    if (!argus) {
    Label(tr("Theme"))
    Spacer(Modifier.height(8.dp))
    val mode = ThemeMode.mode.value
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("light" to "Light", "dark" to "Dark", "auto" to "Auto").forEach { (id, label) ->
            val on = mode == id || (id == "dark" && mode == "system")
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).let { if (on) it.background(c.ink) else it.glass(RoundedCornerShape(22.dp), GlassIntensity.Low, shadow = false) }
                .clickable { ThemeMode.set(id) }, contentAlignment = Alignment.Center) {
                Text(label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = if (on) c.paper else c.ink))
            }
        }
    }
    Text(tr("Auto turns dark after sunset and light in the morning."), modifier = Modifier.padding(vertical = 6.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
    }
    Spacer(Modifier.height(14.dp))
    // 0.52.4: how big the app draws, for people who find the default too large (or too small).
    val scale by Look.scale.collectAsState()
    var idx by remember(scale) { mutableStateOf(LookLogic.scaleIndex(scale).toFloat()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label(tr("Size"))
        Spacer(Modifier.weight(1f))
        Text(LookLogic.scaleLabel(LookLogic.SCALES[idx.roundToInt().coerceIn(0, LookLogic.SCALES.lastIndex)]),
            style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = c.ink))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("A", style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
        androidx.compose.material3.Slider(idx, { idx = it }, valueRange = 0f..LookLogic.SCALES.lastIndex.toFloat(),
            steps = LookLogic.SCALES.size - 2,
            // applied on release, so the page doesn't resize under the finger mid-drag
            onValueChangeFinished = { Look.setScale(LookLogic.SCALES[idx.roundToInt().coerceIn(0, LookLogic.SCALES.lastIndex)]) },
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp).semantics { contentDescription = tr("App size") })
        Text("A", style = TextStyle(fontFamily = Type.Sans, fontSize = 20.sp, color = c.sub))
    }
    Text(tr("Makes everything in {0} smaller or bigger, text included.", BrandName.NAME), modifier = Modifier.padding(vertical = 6.dp),
        style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
    if (!argus) {
    Spacer(Modifier.height(14.dp))
    Label(tr("Accent"))
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LookLogic.ACCENTS.forEach { (hex, name) ->
            val col = LookLogic.parseAccent(hex)?.let { Color(it) }
            Box(Modifier.size(38.dp).clip(CircleShape).background(col ?: c.accent)
                .border(if (accent == hex) 3.dp else 1.dp, if (accent == hex) c.ink else c.hair, CircleShape)
                .clickable { Look.setAccent(hex) }.semantics { contentDescription = tr("Accent: {0}", name) })
        }
    }
    }
    Spacer(Modifier.height(14.dp))
    // 0.46.0 Blaze Stage: timers, music, quizzes and other live cards right on the chat (off = Blaze answers in chat / opens views).
    var stageOn by remember { mutableStateOf(com.blaze.assistant.stage.StageStore.enabled) }
    ToggleRow("${BrandName.NAME} Stage", "Live cards right on the chat: timers, music, quizzes. Drag to move; back or Reset layout restores the chat", stageOn) {
        stageOn = !stageOn; com.blaze.assistant.agent.Settings.setBool("stage_on", stageOn)
        if (!stageOn) com.blaze.assistant.stage.StageStore.dispatch(com.blaze.assistant.stage.StageAction.Reset)
    }
    // Milestone 4: live tool rows in the chat (off = the one-line task pill).
    var toolRowsOn by remember { mutableStateOf(com.blaze.assistant.agent.Settings.toolRows) }
    ToggleRow("Show tool steps", "While ${BrandName.NAME} works, each step opens to what it ran and what came back. Off: one short line", toolRowsOn) {
        toolRowsOn = !toolRowsOn; com.blaze.assistant.agent.Settings.toolRows = toolRowsOn
    }
}
