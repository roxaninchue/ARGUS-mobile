package com.blaze.assistant.ui.team

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.i18n.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owner 2026-11-20: the Team page's `Teams | Agents` pill, the twin of the chat's `Chat | Living`. Agents is the page as
 * it was (every agent on its own row); Teams groups agents under their lead ([com.blaze.assistant.team.AgentKit.teams]),
 * and an agent in no team keeps its own row with just its name. The choice is kept for the session.
 */
object TeamView {
    enum class View(val label: String) { TEAMS("Teams"), AGENTS("Agents") }
    private val _view = MutableStateFlow(View.AGENTS)
    val view: StateFlow<View> = _view
    fun show(v: View) { _view.value = v }
}

/** The pill, centred over the Team composer. */
@Composable
fun TeamSwitch(modifier: Modifier = Modifier) {
    val view by TeamView.view.collectAsState()
    val dark = com.blaze.assistant.ui.LocalBlaze.current.dark
    val p = remember(dark) { com.blaze.assistant.ui.living.LivingSpec.palette(dark) }
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(shape).background(Color(p.pill)).border(1.dp, Color(p.pillEdge), shape).padding(3.dp)) {
            for (v in TeamView.View.values()) {
                val on = v == view
                val label = tr(v.label)
                Box(Modifier.clip(RoundedCornerShape(19.dp)).background(if (on) Color(p.card) else Color.Transparent)
                    .clickable(onClickLabel = label) { TeamView.show(v) }
                    .semantics { contentDescription = if (on) "$label, selected" else label }
                    .padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Text(label, color = Color(if (on) p.ink else p.mute), fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}
