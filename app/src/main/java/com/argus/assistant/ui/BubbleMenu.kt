package com.blaze.assistant.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier

/**
 * 0.45.20 (spec A): a bubble's actions on long-press, so only the latest Blaze reply keeps a small action row.
 * [items] = label to action (null action = shown disabled).
 */
@Composable
fun BubbleMenu(open: Boolean, items: List<Pair<String, (() -> Unit)?>>, onDismiss: () -> Unit) {
    val c = LocalBlaze.current
    DropdownMenu(open, onDismissRequest = onDismiss) {
        items.forEach { (label, act) ->
            // 0.52.4: a leading icon from the label (Copy, Quote, Retry, React…), as in every other menu
            val icon = MenuIcons.forLabel(label)
            DropdownMenuItem(text = { Text(label, style = TextStyle(fontFamily = Type.Sans, fontSize = 14.sp, color = if (act != null) c.ink else c.weak)) },
                leadingIcon = if (icon != null) { { LeadIcon(icon.vector(), if (act != null) c.ink else c.weak) } } else null,
                enabled = act != null, onClick = { onDismiss(); act?.invoke() }, modifier = Modifier.semantics { contentDescription = label })
        }
    }
}
