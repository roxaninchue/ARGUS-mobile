package com.blaze.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.BgAgents
import kotlinx.coroutines.delay

/**
 * 0.52.0: a background agent in the chat is ONE work pill, nothing else:
 * "● Working · Researcher · 5 steps · 40s ›" while it runs, "Worked · Researcher · 12 steps · 2m 3s ›" when done.
 * Tap it to see its steps (newest last) and, inside, Message (steer it, or a follow-up once done) and Stop.
 * Its result arrives as Blaze's reply right under it; the pill never shows status words of its own.
 * Replaces the old AgentCard (status line, shimmer, Steer / Stop / Open buttons always on show).
 */
object AgentPillText {
    /** "Working · Researcher · 5 steps · 40s" (pure; AgentPillTest). */
    fun title(bg: BgAgents.Bg, role: String, now: Long = System.currentTimeMillis()): String {
        val head = when (bg.status) {
            BgAgents.QUEUED, BgAgents.RUNNING -> "Working"
            BgAgents.DONE -> "Worked"
            BgAgents.FAILED -> "Stopped early"
            else -> "Stopped"
        }
        val n = bg.steps
        val count = if (n == 1) "1 step" else "$n steps"
        val start = if (bg.started > 0) bg.started else bg.created
        val end = if (bg.ended > 0) bg.ended else now
        val secs = ((end - start) / 1000).coerceAtLeast(0)
        val time = if (secs >= 60) "${secs / 60}m ${secs % 60}s" else "${secs}s"
        return listOf(head, role, if (n > 0) count else "", if (bg.status == BgAgents.QUEUED) "" else time).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
    }

    /** "Researcher" / "Agent" / "Blueprint" — the agent's role, never a status. */
    fun role(bg: BgAgents.Bg): String = when {
        bg.specialist == com.blaze.assistant.orchestra.OrchestraRuns.SPECIALIST -> "Blueprint"
        bg.specialist.isBlank() -> "Agent"
        else -> (try { BgAgents.spec(bg)?.name } catch (_: Throwable) { null } ?: bg.specialist).replaceFirstChar { it.uppercase() }
    }
}

@Composable
fun AgentPill(id: String) {
    val c = LocalBlaze.current
    val all by BgAgents.all.collectAsState()
    val bg = all.firstOrNull { it.id == id } ?: return
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(bg.live) { while (bg.live) { delay(1000); tick = System.currentTimeMillis() } }
    var open by remember(id) { mutableStateOf(false) }
    var steering by remember(id) { mutableStateOf(false) }
    var text by remember(id) { mutableStateOf("") }
    val title = AgentPillText.title(bg, AgentPillText.role(bg), tick)
    val pulse = if (bg.live) rememberInfiniteTransition(label = "ap").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value else 1f
    Column(Modifier.padding(end = 28.dp).widthIn(max = 340.dp).fillMaxWidth()
        .then(if (bg.live) Modifier.glass(RoundedCornerShape(18.dp), GlassIntensity.Medium, shadow = false) else Modifier.chatGlass(c, mine = false, shape = RoundedCornerShape(18.dp)))) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.semantics { contentDescription = "$title. ${bg.title}" }
            .padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).alpha(pulse).clip(CircleShape).background(if (bg.live) c.accent else c.weak))
            Spacer(Modifier.width(10.dp))
            Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = if (bg.live) c.ink else c.sub))
            val rot by animateFloatAsState(if (open) 90f else 0f, label = "chev")
            Text("\u203A", modifier = Modifier.rotate(rot), style = TextStyle(fontFamily = Type.Display, fontSize = 18.sp, color = c.sub))
        }
        AnimatedVisibility(open, enter = MotionTokens.expand(), exit = MotionTokens.collapse()) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                Text(Md.plain(bg.title), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.ink))
                Spacer(Modifier.height(6.dp))
                val lines = bg.log.ifEmpty { listOfNotNull(bg.last.takeIf { it.isNotBlank() }) }
                lines.forEachIndexed { i, l ->
                    val running = bg.live && i == lines.lastIndex
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(7.dp).clip(CircleShape).alpha(if (running) pulse else 1f).background(c.accent.copy(alpha = 0.85f)))
                        Spacer(Modifier.width(9.dp))
                        Text(Md.plain(l), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 17.sp, color = if (running) c.sub else c.body))
                    }
                }
                if (steering) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().hairline(c.hair).padding(horizontal = 10.dp, vertical = 9.dp)) {
                        if (text.isEmpty()) Text(if (bg.live) "Tell it something\u2026" else "Follow-up\u2026", style = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.weak))
                        BasicTextField(text, { text = it }, textStyle = TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, color = c.ink),
                            cursorBrush = SolidColor(c.accent), maxLines = 4, keyboardOptions = ComposerKeys.Message, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (steering) {
                        ActionText("Send", color = c.accent, size = 10.sp, modifier = Modifier.clickable { if (text.isNotBlank()) { BgAgents.steer(bg.id, text); text = ""; steering = false } })
                        ActionText("Cancel", size = 10.sp, modifier = Modifier.clickable { steering = false })
                    } else {
                        ActionText(if (bg.live) "Message" else "Follow up", color = c.accent, size = 10.sp, modifier = Modifier.clickable { steering = true })
                        if (bg.live) ActionText("Stop", size = 10.sp, modifier = Modifier.clickable { BgAgents.cancel(bg.id) })
                    }
                }
            }
        }
    }
}
