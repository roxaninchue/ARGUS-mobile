package com.argus.assistant.ui.argus.kit

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.argus.assistant.i18n.tr

/**
 * 2026-11-19 (owner, round 2): the glance page's own top row, laid out exactly like [ArgusTopBar] (same height, same
 * 7 dp edges, the same [ArgusIconButton]s): on the left the user badge (the round avatar with the initial; tap = Settings,
 * like the top bar's), on the right an X in the top bar's round glass button (the "+", search and bell style) that closes
 * the overlay and goes to the regular Chat. The notch's pill is drawn over the middle by ChatScreen.
 */
@Composable
fun GlanceBar(initial: String?, onMe: () -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().statusBarsPadding().height(ShellSpec.TOP_BAR_DP.dp).padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ArgusIconButton("user", KitA11y.Shell.ME, onMe, kind = IconButtonKind.AVATAR, initial = initial?.ifBlank { null } ?: "A")
        Spacer(Modifier.weight(1f))
        ArgusIconButton("x", tr("Close"), onExit)
    }
}
