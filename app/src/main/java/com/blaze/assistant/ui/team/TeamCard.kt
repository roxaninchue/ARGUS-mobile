package com.blaze.assistant.ui.team

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.blaze.assistant.i18n.tr
import com.blaze.assistant.team.AgentKit
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusType

/**
 * Owner 2026-11-20 (concept image): one card per team in the Teams view. Agents' orbs on the LEFT, overlapping so more
 * fit, at most [TeamCards.FACES]; the team's name, a status pill and what its agents do on the RIGHT. Tapping the card
 * opens the team: every member's row shows under it (the rest beyond the three faces are there). Pure parts in [TeamCards].
 */
object TeamCards {
    /** Most faces on a card; the rest show when the team is opened. */
    const val FACES = 3
    /** How far each orb tucks under the one before, as a share of its size. */
    const val OVERLAP = 0.36f

    /** Everyone in a team, lead first (a room has no lead row of its own). */
    fun members(t: AgentKit.Team) = (if (t.lead.kind == BotKind.ROOM) emptyList() else listOf(t.lead)) + t.members

    /** A room keeps its own name; a lead's team is named after the lead's role, else "<lead>'s team". */
    fun title(t: AgentKit.Team): String = when {
        t.lead.kind == BotKind.ROOM -> t.lead.name
        t.lead.label.isNotBlank() -> t.lead.label.trim().let { if (it.endsWith("s")) it else it + "s" }
        else -> tr("{0}'s team", t.lead.name)
    }

    /** "Research • Writing • Coding": the members' distinct roles, at most three; blank when none has one. */
    fun roles(t: AgentKit.Team): String =
        members(t).map { it.label.trim() }.filter { it.isNotEmpty() }.distinct().take(3).joinToString(" \u2022 ")

    /** Width the overlapping faces take for [n] orbs of [size] dp. */
    fun stackWidth(n: Int, size: Float): Float = if (n <= 0) 0f else size + (n - 1) * size * (1 - OVERLAP)
}

@Composable
fun TeamCard(
    team: AgentKit.Team,
    working: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    avatar: (id: String, name: String, size: Float) -> @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Argus.colors
    val all = TeamCards.members(team)
    val faces = all.take(TeamCards.FACES)
    val size = 46f
    val title = TeamCards.title(team)
    val roles = TeamCards.roles(team)
    val shape = RoundedCornerShape(26.dp)
    val turn by animateFloatAsState(if (open) 90f else 0f, label = "team-chevron")
    val count = tr("{0} agents", all.size)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(shape)
            .background(c.fill(com.blaze.assistant.ui.argus.GlassLevel.RAISED)).border(1.dp, c.glassRim, shape)
            .clickable(onClickLabel = if (open) tr("Close team") else tr("Open team"), onClick = onToggle)
            .semantics { contentDescription = "$title, $count" + if (working) ", working" else "" }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the faces, left, each tucked under the one before (first on top)
        Box(Modifier.width(TeamCards.stackWidth(faces.size, size).dp)) {
            faces.forEachIndexed { i, b ->
                Box(Modifier.offset(x = (i * size * (1 - TeamCards.OVERLAP)).dp).zIndex((faces.size - i).toFloat())
                    .size((size + 4).dp).clip(CircleShape).background(c.bg).padding(2.dp)) { avatar(b.id, b.name, size)() }
            }
        }
        Spacer(Modifier.width(14.dp))
        // name, status and roles, right
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = ArgusType.bodyStrong, color = c.ink, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tone = if (working) c.success else c.mute
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(tone.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(tone))
                    Spacer(Modifier.width(6.dp))
                    Text(if (working) tr("Active") else tr("Idle"), color = if (working) c.successText else c.mute, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(8.dp))
                Text(count, color = c.mute, fontSize = 12.sp)
            }
            if (roles.isNotEmpty()) Text(roles, color = c.mute, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("\u203A", color = c.mute, fontSize = 24.sp, modifier = Modifier.padding(start = 6.dp).rotate(turn))
    }
}
