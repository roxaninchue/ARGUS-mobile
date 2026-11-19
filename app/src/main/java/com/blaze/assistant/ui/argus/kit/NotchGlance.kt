package com.blaze.assistant.ui.argus.kit

import com.blaze.assistant.i18n.tr
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blaze.assistant.glance.Glance
import com.blaze.assistant.glance.GlanceStore
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Lucide
import com.blaze.assistant.ui.argus.icons.LucideSpec
import com.blaze.assistant.ui.argus.rememberArgusBlur
import com.blaze.assistant.ui.argus.settings.ArgusPrefs
import com.blaze.assistant.stage.StageStore
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import kotlinx.coroutines.delay

/**
 * 2026-11-19 (owner spec, feat/notch-glance): what sits behind the notch's open mode pill. The page under it (the chat
 * sheet, marked as [haze]'s source by ChatScreen while the pill is open) is lightly blurred with a slight scrim; with
 * Reduced motion, a low-RAM phone, API < 31 or the thermal governor's say-so it is a plain scrim instead
 * ([NotchOverlay.backdrop]). On it, the glance layer ([Glance]): the clock, then pins, the next reminder, the task in
 * progress and Stage cards that were taken off the Stage. A card's row puts it back on the Stage; pin and × act on the
 * item. A tap anywhere else is [onClose]. The pill itself is drawn by [NotchSwitcher] above this.
 */
@Composable
fun NotchBackdrop(haze: HazeState, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Argus.colors
    val ctx = LocalContext.current
    val reduced = remember { ArgusPrefs.reducedNow() }
    val look = NotchOverlay.backdrop(rememberArgusBlur(), reduced)
    val fade = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) fade.animateTo(1f, tween(NotchOverlay.fadeMs(reduced))) }
    // the clock ticks on the minute; the rest re-reads with it (all in memory, nothing fetched)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000L - now % 60_000L + 50L); now = System.currentTimeMillis() } }
    var bump by remember { mutableStateOf(0) }
    val prefs by GlanceStore.prefs.collectAsState()
    val shelf by StageStore.shelf.collectAsState()
    val items = remember(now, prefs, shelf, bump) { try { GlanceStore.items(ctx, now) } catch (_: Throwable) { emptyList() } }
    val scrim = Color.Black.copy(alpha = NotchOverlay.scrimAlpha(look))

    Box(modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
        val base = Modifier.fillMaxSize()
        Box(
            (if (look == NotchOverlay.Backdrop.BLUR) base.hazeChild(haze, HazeStyle(backgroundColor = c.bg, tint = HazeTint(scrim),
                blurRadius = NotchOverlay.BLUR_DP.dp, noiseFactor = 0f, fallbackTint = HazeTint(Color.Black.copy(alpha = NotchOverlay.PLAIN_SCRIM))))
            else base.background(scrim))
                .clickable(remember { MutableInteractionSource() }, indication = null) { onClose() }
        )
        Column(
            Modifier.fillMaxWidth().statusBarsPadding().padding(top = (ShellSpec.TOP_BAR_DP + 64).dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.firstOrNull { it.kind == Glance.Kind.CLOCK }?.let { clk ->
                Text(clk.title, style = ArgusType.display, color = c.ink,
                    modifier = Modifier.semantics { contentDescription = clk.title + ", " + clk.sub })
                Text(clk.sub, style = ArgusType.sub, color = c.mute)
            }
            for (g in items.filter { it.kind != Glance.Kind.CLOCK }) GlanceRow(g,
                onOpen = if (g.kind == Glance.Kind.STAGE_CARD) ({ GlanceStore.apply(ctx, Glance.Op.RESTORE, g.id); onClose() }) else null,
                onPin = { GlanceStore.apply(ctx, if (g.pinned) Glance.Op.UNPIN else Glance.Op.PIN, g.id); bump++ },
                onRemove = { GlanceStore.apply(ctx, Glance.Op.REMOVE, g.id); bump++ })
        }
    }
}

@Composable
private fun GlanceRow(item: Glance.Item, onOpen: (() -> Unit)?, onPin: () -> Unit, onRemove: () -> Unit) {
    val c = Argus.colors
    val shape = RoundedCornerShape(ArgusTokens.Radius.PILL.dp)
    Row(
        Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(min = KitSpec.TARGET.dp)
            .argusGlass(GlassLevel.RAISED, shape, shadow = false)
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = tr("Put back on the Stage")) { onOpen() } else Modifier)
            .padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Lucide.icon(item.icon, LucideSpec.Size.BUTTON), null, Modifier.size(LucideSpec.Size.BUTTON.dp), tint = c.mute)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(item.title, style = ArgusType.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.sub.isNotBlank()) Text(item.sub, style = ArgusType.meta, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ArgusIconButton(if (item.pinned) "pin-off" else "pin", if (item.pinned) tr("Unpin") else tr("Pin"), onPin, kind = IconButtonKind.FLAT)
        ArgusIconButton("x", tr("Remove"), onRemove, kind = IconButtonKind.FLAT)
    }
}
