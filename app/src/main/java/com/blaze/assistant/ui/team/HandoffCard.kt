package com.blaze.assistant.ui.team

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.team.BotMessaging
import com.blaze.assistant.team.BotMessaging.Handoff
import com.blaze.assistant.team.BotMessaging.HandoffStatus
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ButtonSpec
import com.blaze.assistant.ui.argus.kit.KitOrb
import com.blaze.assistant.ui.argus.kit.KitSize
import com.blaze.assistant.ui.argus.kit.KitText
import com.blaze.assistant.ui.argus.kit.KitType
import com.blaze.assistant.ui.argus.kit.kitLook
import com.blaze.assistant.ui.argus.kit.kitSurface

/** One line of the expanded bot↔bot transcript: who wrote it (badge "from Atlas") and what the other side received. */
data class TalkItem(val badge: String, val text: String, val withheld: Int = 0)

/**
 * P5-TM-01 `HandoffCard` (04 §2.8 #30, §6 "Team talk made visible", NEW-05): "Atlas → Ledger: *Budget left for Dec?*"
 * with both orbs joined by a dotted line (A-team `.edge.lead`) that flows while the exchange is live
 * ([HandoffStatus.live]: asking / working), the status line, the reply preview, and the collapsed
 * "Atlas ↔ Ledger · 3 messages" line that expands to the transcript ([TeamBus.transcript] rows mapped to [TalkItem]s,
 * with [BotMessaging.badge] provenance). "Open conversation" opens the bot↔bot view.
 *
 * Drawn from the ARGUS kit only (kitLook / kitSurface / KitType / KitOrb / ArgusButton). Orbs are slots, like every kit
 * component, so the P0 `BotOrb` drops in without touching this file. TalkBack hears one merged line ([Handoff.spoken])
 * and the status is a polite live region, so "Ledger replied" is announced when it lands.
 */
@Composable
fun HandoffCard(
    h: Handoff,
    fromColor: Color,
    toColor: Color,
    modifier: Modifier = Modifier,
    transcript: List<TalkItem> = emptyList(),
    onOpen: (() -> Unit)? = null,
    fromOrb: @Composable () -> Unit = { KitOrb(fromColor, KitSize.ORB_SM.dp, initial = h.fromName) },
    toOrb: @Composable () -> Unit = { KitOrb(toColor, KitSize.ORB_SM.dp, initial = h.toName) },
) {
    val look = kitLook()
    var open by rememberSaveable(h.thread) { mutableStateOf(false) }
    val statusTint = when (h.status) {
        HandoffStatus.REPLIED, HandoffStatus.DELIVERED -> look.successText
        HandoffStatus.FAILED, HandoffStatus.OUT_OF_BUDGET -> look.dangerText
        HandoffStatus.ASKING, HandoffStatus.WORKING -> look.waitingText
        HandoffStatus.SILENT -> look.mute
    }
    Column(modifier.fillMaxWidth().kitSurface(look).padding(14.dp)) {
        // orbs joined by the dotted lead edge
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = h.spoken }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(KitSize.ORB_SM.dp)) { fromOrb() }
            LeadEdge(live = h.status.live, from = fromColor, to = toColor, idle = look.line2, modifier = Modifier.weight(1f).height(KitSize.ORB_SM.dp).padding(horizontal = 6.dp))
            Box(Modifier.size(KitSize.ORB_SM.dp)) { toOrb() }
        }
        Spacer(Modifier.height(10.dp))
        Text("${h.fromName} \u2192 ${h.toName}", maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = KitType.style(KitText.META, look.mute), modifier = Modifier.clearAndSetSemantics { })
        Spacer(Modifier.height(2.dp))
        Text(h.ask, maxLines = 3, overflow = TextOverflow.Ellipsis,
            style = KitType.style(KitText.BODY_STRONG, look.ink).copy(fontStyle = FontStyle.Italic), modifier = Modifier.clearAndSetSemantics { })
        Spacer(Modifier.height(8.dp))
        Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite; stateDescription = h.statusText }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusTint))
            Spacer(Modifier.width(8.dp))
            Text(h.statusText, maxLines = 1, style = KitType.style(KitText.CHIP_STATUS, statusTint))
            if (h.withheld > 0) {
                Spacer(Modifier.width(8.dp))
                Text("\u00b7 ${h.withheld} private ${if (h.withheld == 1) "note" else "notes"} withheld", maxLines = 1,
                    style = KitType.style(KitText.CHIP_STATUS, look.dim))
            }
        }
        if (h.reply.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("\u2713 ${h.reply}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = KitType.style(KitText.PREVIEW, look.ink))
        }
        // collapsed "A ↔ B · n messages" line, expandable
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = KitSize.TOUCH.dp).clip(CircleShape)
            .clickable(onClickLabel = if (open) "Hide messages" else "Show messages") { open = !open }
            .semantics(mergeDescendants = true) { role = Role.Button; stateDescription = if (open) "expanded" else "collapsed" },
            verticalAlignment = Alignment.CenterVertically) {
            Text(h.talk, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KitType.style(KitText.META, look.mute), modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = look.dim, modifier = Modifier.size(20.dp))
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (transcript.isEmpty()) Text("No messages yet.", style = KitType.style(KitText.META, look.dim))
                transcript.forEach { m ->
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }) {
                        Text(m.badge, style = KitType.style(KitText.PILL, look.mute))
                        Text(m.text, style = KitType.style(KitText.PREVIEW, look.ink))
                    }
                }
                if (onOpen != null) ArgusButton("Open conversation", onClick = onOpen, size = ButtonSpec.Size.S)
            }
        }
    }
}

/**
 * The dotted lead edge between the two orbs: dashes in the two bots' colours that flow from asker to answerer while
 * [live], and rest as a dim hairline once the exchange settles (04 §6 #3: the line animates, then stops).
 */
@Composable
private fun LeadEdge(live: Boolean, from: Color, to: Color, idle: Color, modifier: Modifier = Modifier) {
    val phase = if (live) rememberInfiniteTransition(label = "handoffEdge")
        .animateFloat(0f, 24f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "phase").value else 0f
    Canvas(modifier) {
        val y = size.height / 2f
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()), -phase.dp.toPx())
        val mid = size.width / 2f
        if (live) {
            drawLine(from, Offset(0f, y), Offset(mid, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
            drawLine(to, Offset(mid, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
        } else {
            drawLine(idle, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()), 0f))
        }
    }
}

/** Maps a thread's transcript rows (TeamBus.transcript) to [TalkItem]s with provenance badges. */
fun talkItems(rows: List<com.blaze.assistant.team.Row>, names: Map<String, String>): List<TalkItem> = rows.map { r ->
    TalkItem(BotMessaging.badge(r, names) ?: "", com.blaze.assistant.team.RowBody.text(r),
        com.blaze.assistant.team.RowBody.field(r, "withheld").toIntOrNull() ?: 0)
}
