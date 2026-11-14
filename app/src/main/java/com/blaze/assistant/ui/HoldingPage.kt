package com.blaze.assistant.ui

import com.blaze.assistant.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.Holding
import com.blaze.assistant.agent.PinnedStore
import com.blaze.assistant.ui.space.SpaceGroup
import com.blaze.assistant.ui.space.SpaceHairline
import com.blaze.assistant.ui.space.SpaceInsets
import com.blaze.assistant.ui.space.SpaceSectionLabel
import com.blaze.assistant.ui.space.SpaceType
import com.blaze.assistant.ui.space.spaceLook

/**
 * Unreleased round after 0.52.3, "What Argus is holding" (settings page id "holding": the profile row, /holding, open
 * by name). Lists the pinned facts with Unpin, then this chat's LongChat summary, the GoalPin task and the skills of the
 * last request ([Holding.sections]). Read-only apart from Unpin. Drawn with the shell's groups (ui/space/SpaceRows).
 */
@Composable
fun HoldingPage() {
    val look = spaceLook()
    val pinned by PinnedStore.facts.collectAsState()
    // Agent.holding() also loads the pinned list on first open (the flow above then carries it and every Unpin).
    val snap = remember(pinned) { Agent.holding() }
    val facts = if (pinned.isEmpty()) snap.pinned else pinned
    Text(Holding.INTRO, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = SpaceType.meta(look.sub))
    SpaceSectionLabel(Holding.pinnedTitle(facts.size))
    SpaceGroup(tag = "holding_pinned") {
        if (facts.isEmpty()) HoldingLine(Holding.PINNED_EMPTY, dim = true)
        facts.forEachIndexed { i, f ->
            if (i > 0) SpaceHairline()
            Row(Modifier.fillMaxWidth().padding(start = SpaceInsets.CARD_PAD_DP.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(f.text, modifier = Modifier.weight(1f).padding(vertical = 8.dp), style = SpaceType.bodyText(look.ink))
                Spacer(Modifier.width(8.dp))
                Text(tr("Unpin"), modifier = Modifier.testTag("holding_unpin_${f.id}")
                    .clickable(onClickLabel = tr("Unpin {0}", f.text.take(40))) { PinnedStore.unpin(f.id) }.padding(12.dp),
                    style = SpaceType.meta(look.accent))
            }
        }
    }
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
