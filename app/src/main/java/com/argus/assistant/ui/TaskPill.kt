package com.argus.assistant.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 0.52.1: the ONE task pill (owner: "Hark-style"). A single short line of natural text ([com.argus.assistant.agent.TaskLine])
 * with a soft shimmer running through the words. It never expands, shows no count, timer, list or command, and is
 * removed by its caller when the task is done. Anything Blaze writes during the task sits above it in the chat.
 * Stop lives in the composer's stop button. Replaces WorkCard / AgentPill / StepsChip in the chat surfaces.
 */
object TaskPillTokens { const val TAG = "task_pill" }

@Composable
fun TaskPill(line: String, modifier: Modifier = Modifier) {
    val c = LocalBlaze.current
    val base = c.sub
    val hi = c.ink
    val shimmer = if (MotionTokens.enabled) rememberInfiniteTransition(label = "taskPill")
        .animateFloat(-1f, 2f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "shine").value else -10f
    Box(modifier.padding(start = 2.dp, top = 6.dp, bottom = 2.dp).widthIn(max = 300.dp)
        .glass(RoundedCornerShape(16.dp), GlassIntensity.Medium, shadow = false, sheen = false)
        .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = line }
        .padding(horizontal = 13.dp, vertical = 8.dp)) {
        AnimatedContent(targetState = line, label = "taskLine",
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) }) { t ->
            val w = 420f
            val x = shimmer * w
            val brush = Brush.linearGradient(listOf(base, hi, base), start = Offset(x - 120f, 0f), end = Offset(x + 120f, 0f), tileMode = TileMode.Clamp)
            Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(brush = brush, fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp))
        }
    }
}
