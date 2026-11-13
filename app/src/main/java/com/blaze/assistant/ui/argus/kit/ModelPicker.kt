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
import com.blaze.assistant.agent.ModelList
import com.blaze.assistant.agent.ModelPick
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Brands
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
            val logo = if (choice.auto) null else Brands.icon(ModelList.brand(label, choice.key), c.ink)
            if (logo != null) androidx.compose.foundation.Image(logo, null, Modifier.size(14.dp))
            else Icon(Lucide.icon(if (choice.auto) "sparkles" else "cpu", 14), null, Modifier.size(14.dp), tint = c.mute)
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
    var query by remember { mutableStateOf("") }
    val rows = remember(list, choice) { ModelList.rows(list) { ModelPick.isDown(it) } }
    val shown = remember(rows, query) { ModelList.filter(rows, query) }
    ArgusSheet(onDismiss = onDismiss, title = "Model") {
        val picked = list.firstOrNull { it.key == choice.key }
        val pickedRow = if (choice.auto) null else ModelList.rowOf(rows, choice.key)
        val pickedDown = !choice.auto && ModelPick.isDown(choice.key)
        if (picked != null && picked.efforts.isNotEmpty()) {
            Text("Effort", style = ArgusType.meta, color = c.mute, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val now = ModelPick.effort(list, choice)
                for (e in picked.efforts) ArgusButton(e.replaceFirstChar { it.uppercaseChar() }, { ModelPick.set(picked.key, e) }, Modifier.weight(1f),
                    kind = if (e == now) ButtonKind.PRIMARY else ButtonKind.GLASS, size = ButtonSize.S)
            }
        }
        if (rows.size > 6) ArgusSearch(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), placeholder = "Search models")
        androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (query.isBlank()) MenuRow("sparkles", "Auto", { ModelPick.auto(); onDismiss() }, sub = "The gateway picks the fastest healthy model",
                trailing = if (choice.auto) ({ Icon(Lucide.icon("check", 18), null, Modifier.size(18.dp), tint = c.ink) }) else null)
            if (pickedDown && query.isBlank()) MenuRow("triangle-alert", picked?.name ?: LiveModels.prettyName(choice.key.substringAfter('/')), { ModelPick.auto(); onDismiss() },
                sub = "Didn't answer, so messages go Auto until it's healthy again. Tap to stay on Auto")
            else if (!choice.auto && picked == null && query.isBlank()) MenuRow("triangle-alert", LiveModels.prettyName(choice.key.substringAfter('/')), { ModelPick.auto(); onDismiss() },
                sub = "Not healthy right now, so messages go Auto. Tap to switch to Auto")
            if (list.isEmpty()) Text("No healthy models listed yet. Auto still works.", style = ArgusType.meta, color = c.mute,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            else if (shown.isEmpty()) Text("No model matches \u201c${query.trim()}\u201d.", style = ArgusType.meta, color = c.mute,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            for (r in shown) ModelRow(r, on = r == pickedRow) {
                val m = r.best
                ModelPick.set(m.key, if (pickedRow == r) choice.effort else ""); if (m.efforts.isEmpty()) onDismiss()
            }
        }
    }
}

/** One model: the maker's logo, the name, what it can do, then signal bars and the tick on the far right. No provider name. */
@Composable
private fun ModelRow(r: ModelList.Row, on: Boolean, onClick: () -> Unit) {
    val c = Argus.colors
    val detail = ModelList.detail(r)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
        .clearAndSetSemantics { contentDescription = r.name + (if (detail.isEmpty()) "" else ", $detail") + ", signal ${r.bars} of 4" + (if (on) ", selected" else ""); role = Role.Button; onClick { onClick(); true } }
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).argusGlass(GlassLevel.RAISED, androidx.compose.foundation.shape.CircleShape, shadow = false), contentAlignment = Alignment.Center) {
            val logo = Brands.icon(r.brand, c.ink)
            if (logo != null) androidx.compose.foundation.Image(logo, null, Modifier.size(22.dp))
            else Icon(Lucide.icon(if (r.tools) "wrench" else "message-circle", 20), null, Modifier.size(20.dp), tint = c.ink)
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(r.name, style = ArgusType.field, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = ArgusType.meta, color = c.mute, maxLines = 1)
        }
        SignalBars(r.bars)
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (on) Icon(Lucide.icon("check", 18), null, Modifier.size(18.dp), tint = c.ink)
        }
    }
}

/** Four rising bars like a phone's signal: 4 = fast and healthy; 2 goes amber and 1 red as replies get slow or fail. */
@Composable
fun SignalBars(bars: Int, modifier: Modifier = Modifier) {
    val c = Argus.colors
    val lit = when { bars <= 1 -> c.danger; bars == 2 -> c.waiting; else -> c.ink }
    val off = c.mute.copy(alpha = 0.35f)
    androidx.compose.foundation.Canvas(modifier.size(width = 18.dp, height = 14.dp)) {
        val w = size.width / 4f
        val gap = w * 0.28f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRoundRect(if (i < bars) lit else off, topLeft = androidx.compose.ui.geometry.Offset(i * w, size.height - h),
                size = androidx.compose.ui.geometry.Size(w - gap, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.2f))
        }
    }
}
