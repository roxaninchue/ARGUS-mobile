package com.blaze.assistant.ui.argus.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.agent.LiveModels
import com.blaze.assistant.agent.LiveModelsFeed
import com.blaze.assistant.agent.ModelPick
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Lucide

/**
 * 0.53.0 manual model + effort picker (owner, 13 Nov 2026). A chip over the composer shows the pick ("Auto",
 * "Kimi K3 · Medium", "… · unavailable"); a tap opens [ModelSheet]: Auto, then every model the gateway lists as healthy for
 * this account (GET /v1/models/live, plan-filtered), then the picked model's efforts when it has any. Nothing else is offered.
 */
@Composable
fun ModelChip(modifier: Modifier = Modifier, force: List<LiveModels.Member>? = null) {
    val c = Argus.colors
    val choice by ModelPick.current.collectAsState()
    var open by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf(force ?: LiveModels.shared.list()) }
    LaunchedEffect(choice, open) { if (force == null) { LiveModelsFeed.touchNow(); kotlinx.coroutines.delay(1_500); list = LiveModels.shared.list() } }
    val label = ModelPick.label(list, choice)
    Box(modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { open = true }
        .clearAndSetSemantics { contentDescription = "Model: $label. Change model"; role = Role.Button; onClick { open = true; true } },
        contentAlignment = Alignment.CenterStart) {
        Row(Modifier.heightIn(min = 30.dp).argusGlass(GlassLevel.RAISED, RoundedCornerShape(ArgusTokens.Radius.PILL.dp), shadow = false)
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Lucide.icon("cpu", 14), null, Modifier.size(14.dp), tint = c.mute)
            Text(label, style = ArgusType.statusChip, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Lucide.icon("chevron-down", 14), null, Modifier.size(14.dp), tint = c.mute)
        }
    }
    if (open) ModelSheet(list, onDismiss = { open = false })
}

@Composable
fun ModelSheet(list: List<LiveModels.Member>, onDismiss: () -> Unit) {
    val c = Argus.colors
    val choice by ModelPick.current.collectAsState()
    ArgusSheet(onDismiss = onDismiss, title = "Model") {
        val picked = list.firstOrNull { it.key == choice.key }
        if (picked != null && picked.efforts.isNotEmpty()) {
            Text("Effort", style = ArgusType.meta, color = c.mute, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val now = ModelPick.effort(list, choice)
                for (e in picked.efforts) ArgusButton(e.replaceFirstChar { it.uppercaseChar() }, { ModelPick.set(picked.key, e) }, Modifier.weight(1f),
                    kind = if (e == now) ButtonKind.PRIMARY else ButtonKind.GLASS, size = ButtonSize.S)
            }
        }
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            MenuRow("sparkles", "Auto", { ModelPick.auto(); onDismiss() }, sub = "The gateway picks the fastest healthy model",
                trailing = if (choice.auto) ({ Icon(Lucide.icon("check", 18), null, Modifier.size(18.dp), tint = c.ink) }) else null)
            if (!choice.auto && picked == null) MenuRow("triangle-alert", LiveModels.prettyName(choice.key.substringAfter('/')), { ModelPick.auto(); onDismiss() },
                sub = "Not healthy right now, so messages go Auto. Tap to switch to Auto")
            if (list.isEmpty()) Text("No healthy models listed yet. Auto still works.", style = ArgusType.meta, color = c.mute,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            for (m in list) MenuRow(if (m.tools) "wrench" else "message-circle", m.name, {
                    ModelPick.set(m.key, if (choice.key == m.key) choice.effort else ""); if (m.efforts.isEmpty()) onDismiss() },
                sub = ModelPick.detail(m),
                trailing = if (m.key == choice.key) ({ Icon(Lucide.icon("check", 18), null, Modifier.size(18.dp), tint = c.ink) }) else null)
        }
    }
}
