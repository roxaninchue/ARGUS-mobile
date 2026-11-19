package com.blaze.assistant.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Holding
import com.blaze.assistant.ui.space.SpaceGroup
import com.blaze.assistant.ui.space.SpaceHairline
import com.blaze.assistant.ui.space.SpaceInsets
import com.blaze.assistant.ui.space.SpaceSectionLabel
import com.blaze.assistant.ui.space.SpaceType
import com.blaze.assistant.ui.space.spaceLook

/**
 * Unreleased round after 0.52.3, "What Argus is holding" (settings page id "holding": the profile row, /holding, open
 * by name). Lists this chat's LongChat summary, the GoalPin task and the skills of the last request ([Holding.sections]).
 * Read-only (the pinned facts and their unpin button were removed, owner 2026-11-15). Drawn with the shell's groups (ui/space/SpaceRows).
 */
@Composable
fun HoldingPage() {
    val look = spaceLook()
    val snap = remember { Agent.holding() }
    Text(Holding.INTRO, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = SpaceType.meta(look.sub))
    for (s in Holding.sections(snap)) {
        SpaceSectionLabel(s.title)
        SpaceGroup {
            if (s.lines.isEmpty()) HoldingLine(s.empty, dim = true)
            s.lines.forEachIndexed { i, line ->
                if (i > 0) SpaceHairline()
                HoldingLine(line)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun HoldingLine(text: String, dim: Boolean = false) {
    val look = spaceLook()
    Text(text, modifier = Modifier.fillMaxWidth().padding(horizontal = SpaceInsets.CARD_PAD_DP.dp, vertical = SpaceInsets.ROW_PAD_V_DP.dp),
        style = if (dim) SpaceType.meta(look.sub) else SpaceType.bodyText(look.ink))
}
