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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.GlitchSpans
import com.blaze.assistant.ui.argus.icons.Lucide
import com.blaze.assistant.agent.PromptEcho

/**
 * fix/chat-flat-ui (owner, 14 Nov 2026: "we'll show collapsible and expandable thinking process"): the model's reasoning,
 * folded above the reply. A header ("Thinking" with a pulsing dot while it reasons, "Thought for 4s" after) and a chevron;
 * tap to open or close. Open, the thought is muted, a size smaller, behind a thin accent line on the left. Parts that name
 * the assistant or echo its own instructions are drawn glitched ([GlitchSpans], [GlitchText]). Rules in [ThoughtFold].
 *
 * [key] is the row's id (the open / closed choice is kept per reply). [ms] is the saved reasoning time (0 = not saved yet:
 * the section times itself while it is on screen).
 */
@Composable
fun ThoughtSection(key: Long, thinking: String, live: Boolean, answerStarted: Boolean, ms: Long, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    val body = remember(thinking) { ThoughtFold.body(thinking) }
    if (body.isEmpty()) return
    val now = ThoughtFold.thinkingNow(live, answerStarted)
    val timer = remember(key) { ThoughtFold.Timer() }
    var timed by remember(key) { mutableLongStateOf(0L) }
    LaunchedEffect(key, now) { timer.mark(now, System.currentTimeMillis()); timed = timer.ms() }
    var choice by rememberSaveable(key) { mutableStateOf<Boolean?>(null) }
    val open = ThoughtFold.open(choice, live, answerStarted)
    val header = ThoughtFold.header(live, answerStarted, if (ms > 0) ms else timed)
    Column(modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.clip(RoundedCornerShape(10.dp))
            .clickable(onClickLabel = if (open) "Hide the thinking" else "Show the thinking", role = Role.Button) { choice = !open }
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .padding(start = 2.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Owner 2026-11-14: a brain icon for thoughts. It glows in the accent and breathes slowly while thinking.
            val brainAlpha = if (now) {
                val tr = rememberInfiniteTransition(label = "thoughtDot")
                val pulse by tr.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "thoughtDotA")
                if (MotionTokens.enabled) pulse else 1f
            } else 1f
            Icon(Lucide.icon(ThoughtFold.ICON, 16), null, Modifier.size(16.dp), tint = (if (now) c.accent else c.sub).copy(alpha = brainAlpha))
            Spacer(Modifier.width(6.dp))
            Text(header, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, color = c.sub))
            val rot by animateFloatAsState(if (open) 180f else 0f, MotionTokens.fast(), label = "thoughtChev")
            Text("\u2304", modifier = Modifier.padding(start = 6.dp).rotate(rot), style = TextStyle(fontSize = 13.sp, color = c.sub))
        }
        AnimatedVisibility(open, enter = MotionTokens.expand(), exit = MotionTokens.collapse()) {
            val accent = c.accent.copy(alpha = 0.45f)
            Box(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 6.dp)
                .drawBehind { drawRect(accent, Offset.Zero, Size(2.dp.toPx(), size.height)) }
                .padding(start = 12.dp, end = 4.dp)) {
                // Glitched parts: the assistant's name, self-reference, and runs that echo the active system prompt.
                val spans = remember(body) { try { GlitchSpans.find(body, PromptEcho.index()) } catch (_: Throwable) { emptyList() } }
                SelectionContainer {
                    GlitchText(body, spans, TextStyle(fontFamily = Type.Sans, fontSize = 13.sp, lineHeight = 19.sp, color = c.sub))
                }
            }
        }
    }
}
