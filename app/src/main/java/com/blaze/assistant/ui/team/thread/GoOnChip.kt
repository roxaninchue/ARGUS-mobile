package com.blaze.assistant.ui.team.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.StopResume

/**
 * P2-SF-06: the stopped state of the WorkChip (04 §WorkChip: "Stopped after 12 steps · Go on", outlined red, 32 dp pill,
 * 13 sp). P1-SH-03 owns `WorkChip.kt` and was not on any overhaul/p1* branch when this landed, so this is the Go-on piece on
 * its own. Merge: WorkChip's stopped/failed branch renders `GoOnChip(steps, onGoOn)` in place of its "Worked for …" pill
 * (or folds this body in and deletes this file); [onGoOn] sends [StopResume.GO_ON] as the user's message, which
 * `StopResume.isResume` picks up and `StopResume.note` turns into "go on with the task that paused after N steps".
 *
 * Android glue, checked by hand (no android.* in the rule; the text comes from StopResume.budgetLine, tested in StopTest).
 */
@Composable
fun GoOnChip(steps: Int, onGoOn: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val line = StopResume.budgetLine(steps)
    val red = MaterialTheme.colorScheme.error
    val pill = RoundedCornerShape(50)
    Row(
        modifier
            .heightIn(min = 32.dp)
            .clip(pill)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .border(1.dp, red, pill)
            .clickable(enabled = enabled, onClick = onGoOn)
            .semantics { role = Role.Button; contentDescription = line }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cut = line.lastIndexOf(StopResume.GO_ON)
        Text(line.substring(0, cut), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(StopResume.GO_ON, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = red)
    }
}
