package com.blaze.assistant.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blaze.assistant.team.Router
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.icons.Lucide
import com.blaze.assistant.ui.argus.kit.ArgusSheet
import com.blaze.assistant.ui.argus.kit.KitSpec
import com.blaze.assistant.ui.argus.kit.kc

/**
 * P1-TM-06: the "Here's what I'll do" sheet on [ArgusSheet] (A-team planSheet): the words and where they were read,
 * one row per change, the preview switch, Cancel / Apply. Stateless: [instant] and [onInstant] are the setting.
 */
@Composable
fun PlanSheet(plan: Router.Plan, instant: Boolean, onInstant: (Boolean) -> Unit, onApply: () -> Unit, onCancel: () -> Unit) {
    val c = Argus.colors
    ArgusSheet(onDismiss = onCancel, title = PlanSheetModel.TITLE, primary = PlanSheetModel.button(plan), onPrimary = onApply,
        primaryEnabled = PlanSheetModel.enabled(plan)) {
        Text(PlanSheetModel.sub(plan), style = ArgusType.preview, color = c.mute, modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp))
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            for (l in PlanSheetModel.lines(plan)) Row(
                Modifier.fillMaxWidth().heightIn(min = KitSpec.TARGET.dp).padding(horizontal = 12.dp, vertical = 6.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val tint = if (l.miss) kc(KitSpec.dangerText(c.palette)) else c.ink
                Icon(Lucide.icon(l.icon, 18), null, Modifier.size(18.dp), tint = tint)
                Text(l.text, style = ArgusType.body, color = tint, modifier = Modifier.weight(1f))
            }
        }
        if (PlanSheetModel.showSwitch(plan)) Row(
            Modifier.fillMaxWidth().heightIn(min = KitSpec.TARGET.dp).padding(horizontal = 12.dp, vertical = 4.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(PlanSheetModel.SWITCH, style = ArgusType.preview, color = c.mute, modifier = Modifier.weight(1f))
            Switch(checked = instant, onCheckedChange = onInstant)
        }
    }
}
