package com.blazeinchue.argus.ui


import com.blazeinchue.argus.BrandName
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blazeinchue.argus.agent.Mood

/**
 * 0.52.1 (owner, screenshot sc-5ce1629eddc5): a small pill under the top bar with the Argus mark (and the project name
 * when you are in a project). Tapping it opens an overlay of options; for now the options are the views that exist:
 * Chat, Projects, Home, Agents (background tasks) and Terminal.
 */
object ArgusTopPillModel {
    data class Option(val id: String, val label: String, val sub: String)

    /** The overlay's options, in order. Home only where Home is a tab (not in Simple mode's swipe layout). */
    fun options(homeTab: Boolean = true): List<Option> = buildList {
        add(Option("chat", "Chat", "Your conversation with ${BrandName.NAME}"))
        add(Option("projects", "Projects", "Ongoing efforts with their own threads"))
        if (homeTab) add(Option("home", "Home", "Your dashboard cards"))
        add(Option("agents", "Agents", "Tasks ${BrandName.NAME} is working on in the background"))
        add(Option("terminal", "Terminal", "${BrandName.NAME} Terminal"))
    }

    /** The pill's label: the project's name inside a project, else "Argus". */
    fun label(project: String): String = project.trim().ifBlank { "${BrandName.NAME}" }.let { if (it.length > 28) it.take(27).trimEnd() + "\u2026" else it }

    fun icon(id: String): ImageVector = when (id) {
        "projects" -> Icons.Rounded.Folder
        "home" -> Icons.Rounded.Home
        "agents" -> Icons.Rounded.Workspaces
        "terminal" -> Icons.Rounded.Terminal
        else -> Icons.Rounded.ChatBubbleOutline
    }
}

@Composable
fun ArgusTopPill(label: String, mood: Mood = Mood.IDLE, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalArgus.current
    Box(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 240.dp).glass(RoundedCornerShape(18.dp), GlassIntensity.Medium, shadow = false)
            .clickable(role = Role.Button, onClickLabel = "Open views", onClick = onClick)
            .semantics { contentDescription = "$label. Tap for views" }
            .padding(start = 8.dp, end = 14.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(mood, 20.dp)
            Spacer(Modifier.width(7.dp))
            // 0.52.1 Argus: outside a project the pill carries the wide-tracked ARGUS wordmark.
            if (label == BrandName.NAME) Text(BrandName.UPPER, maxLines = 1, style = ArgusBrand.wordmark(12.sp, c.ink))
            else Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = c.ink))
        }
    }
}

/** The overlay: a scrim and a glass card of options under the pill. Tapping outside closes it. */
@Composable
fun ArgusTopOverlay(open: Boolean, current: String, options: List<ArgusTopPillModel.Option>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = LocalArgus.current
    AnimatedVisibility(open, enter = MotionTokens.enterUp(), exit = MotionTokens.fadeAway()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
            .semantics { contentDescription = "Close views" }) {
            Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 104.dp, start = 24.dp, end = 24.dp)
                .widthIn(max = 360.dp).fillMaxWidth()
                .glass(RoundedCornerShape(24.dp), GlassIntensity.High, shadow = false)
                .background(GlassTokens.bg.copy(alpha = if (c.dark) 0.55f else 0f))
                .clickable(remember { MutableInteractionSource() }, null) { }
                .padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { o ->
                    val on = o.id == current
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (on) c.faint else Color.Transparent)
                        .clickable(role = Role.Button, onClickLabel = "Open ${o.label}") { onPick(o.id) }
                        .padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(c.faint), contentAlignment = Alignment.Center) {
                            Icon(ArgusTopPillModel.icon(o.id), contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(o.label, style = TextStyle(fontFamily = Type.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = c.ink))
                            Text(o.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, color = c.sub))
                        }
                        if (on) Box(Modifier.size(7.dp).clip(CircleShape).background(c.ink))
                    }
                }
            }
        }
    }
}
