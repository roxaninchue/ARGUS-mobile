package com.blaze.assistant.ui.argus.kit

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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * `.need` (04 §2.8 #9): the full-width glossy ink banner "3 need you · Nova, Atlas  Review ›"; amber when only
 * approvals wait; nothing at all when [banner] is null. Static until P2 wires the Inbox (P1 passes null).
 */
@Composable
fun NeedYouBanner(banner: NeedYou.Banner?, onReview: () -> Unit, modifier: Modifier = Modifier) {
    if (banner == null) return
    val look = kitLook()
    val fill = if (banner.amber) look.waiting else look.ink
    val ink = if (banner.amber) Color(0xFF1A1300) else look.onInk
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clip(KitShapes.banner).background(fill)
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.20f), 0.6f to Color.Transparent))
        .clickable(onClickLabel = "Review", onClick = onReview)
        .clearAndSetSemantics { contentDescription = banner.spoken; role = Role.Button }
        .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Flag, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(banner.title, style = KitType.style(KitText.SUB, ink).copy(fontWeight = FontWeight.SemiBold))
        if (banner.names.isNotBlank()) {
            Text(" \u00B7 " + banner.names, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KitType.style(KitText.SUB, ink.copy(alpha = 0.75f)), modifier = Modifier.weight(1f))
        } else Spacer(Modifier.weight(1f))
        Text("Review", style = KitType.style(KitText.SUB, ink).copy(fontWeight = FontWeight.SemiBold))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
    }
}

/**
 * `.empty` / `.hello` (04 §2.8 #36): big icon at 55 %, title, body, one or two buttons. [serif] = the first-run hero
 * ("Build your team") in the serif accent.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = null,
    serif: Boolean = false,
    primary: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
    art: (@Composable () -> Unit)? = null,
) {
    val look = kitLook()
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (art != null) { art(); Spacer(Modifier.height(16.dp)) }
        else if (icon != null) { Icon(icon, contentDescription = null, tint = look.ink.copy(alpha = 0.55f), modifier = Modifier.size(30.dp)); Spacer(Modifier.height(12.dp)) }
        Text(title, textAlign = TextAlign.Center,
            style = if (serif) KitType.style(KitText.SERIF_HELLO, look.ink, KitType.Serif) else KitType.style(KitText.TOP_TITLE, look.ink),
            modifier = Modifier.semantics { heading() })
        if (!body.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(body, textAlign = TextAlign.Center, style = KitType.style(KitText.SUB, look.mute))
        }
        if (primary != null || secondary != null) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                secondary?.let { (t, f) -> ArgusButton(t, f) }
                primary?.let { (t, f) -> ArgusButton(t, f, kind = ButtonSpec.Kind.PRIMARY) }
            }
        }
    }
}

/**
 * `.wsline` (04 §2.8 #37): a 44 dp one-line status with a 44 x 4 bar ([fraction] null = indeterminate). The text is a
 * polite live region so TalkBack hears progress without being flooded (the caller changes [text] per step).
 */
@Composable
fun ProgressLine(text: String, fraction: Float?, modifier: Modifier = Modifier) {
    val look = kitLook()
    Row(modifier.fillMaxWidth().heightIn(min = KitSize.PROGRESS_LINE.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KitType.style(KitText.TOOL, look.ink, tnum = true),
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(Modifier.width(10.dp))
        if (fraction != null) Text(Progress.percent(fraction), style = KitType.style(KitText.META, look.mute, tnum = true))
        Spacer(Modifier.width(8.dp))
        val bar = Modifier.width(KitSize.PROGRESS_BAR_W.dp).height(KitSize.PROGRESS_BAR_H.dp).clip(CircleShape)
        if (fraction == null) LinearProgressIndicator(modifier = bar, color = look.ink, trackColor = look.rim2, strokeCap = StrokeCap.Round)
        else LinearProgressIndicator(progress = { fraction }, modifier = bar, color = look.ink, trackColor = look.rim2, strokeCap = StrokeCap.Round, drawStopIndicator = {})
    }
}

/** `.cloud-chip` (04 §2.8 #35): "● Cloud on · $0.055/h" under the top bar. Draws nothing until [CloudChipRule] allows it (P7). */
@Composable
fun CloudChip(running: Boolean, perHourUsd: Double?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = CloudChipRule.ENABLED) {
    if (!CloudChipRule.visible(running, enabled)) return
    val look = kitLook()
    val label = CloudChipRule.label(perHourUsd)
    Row(modifier.heightIn(min = 32.dp).kitChrome(look, KitShapes.pill).clickable(onClickLabel = "Computers", onClick = onClick)
        .clearAndSetSemantics { contentDescription = label; role = Role.Button }
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(look.success))
        Spacer(Modifier.width(6.dp))
        Text(label, style = KitType.style(KitText.CHIP_STATUS, look.ink, tnum = true))
    }
}
