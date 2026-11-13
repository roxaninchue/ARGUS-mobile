package com.blaze.assistant.ui.argus.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.icons.Lucide
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * P1-SH-01 `ArgusTopBar`, Home variant (04 §2.8 #1): `[me]` · the notch handle in the middle · `[search] [bell•] [+]`.
 * [notch] is drawn centred over the bar so its pill can drop over the content.
 */
@Composable
fun ArgusTopBar(
    initial: String?,
    onMe: () -> Unit,
    onMeLong: () -> Unit,
    onSearch: () -> Unit,
    onBell: () -> Unit,
    bellBadge: Boolean,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    notch: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxWidth().height(ShellSpec.TOP_BAR_DP.dp)) {
        Row(Modifier.fillMaxWidth().height(ShellSpec.TOP_BAR_DP.dp).padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            ArgusIconButton("user", KitA11y.Shell.ME, onMe, kind = IconButtonKind.AVATAR, initial = initial?.ifBlank { null } ?: "A", onLongClick = onMeLong)
            Spacer(Modifier.weight(1f))
            ArgusIconButton("search", KitA11y.Shell.SEARCH, onSearch)
            ArgusIconButton("bell", if (bellBadge) KitA11y.Shell.BELL_WAITING else KitA11y.Shell.BELL, onBell, badge = bellBadge)
            ArgusIconButton("plus", KitA11y.Shell.NEW, onPlus)
        }
        Box(Modifier.align(Alignment.Center)) { notch() }
    }
}

/**
 * P1-SH-01 `NotchSwitcher` (04 §2.8 #2, AT notch.js): a wordless 46×22 handle; tap or pull down → the pill drops with
 * Team · Chat · Life · Desk (selected = ink pill). Pull on past [ShellSpec.Notch.VOICE_AT] = [onVoice]. Tap outside,
 * a pick or Back closes it ([open] is the caller's, so Back can read it). TalkBack sees the four modes as tabs even
 * while the pill is closed, so switching never needs the gesture.
 */
@Composable
fun NotchSwitcher(
    mode: String,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    onMode: (String) -> Unit,
    onVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Argus.colors
    val density = LocalDensity.current
    var pulled by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { d -> pulled = (pulled + with(density) { d.toDp().value }).coerceAtLeast(0f) }
    val stretch by animateDpAsState((pulled.coerceAtMost(ShellSpec.Notch.VOICE_AT) / 6f).dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "notch")
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.size(KitSpec.TARGET.dp)
                .draggable(drag, Orientation.Vertical,
                    onDragStarted = { pulled = 0f },
                    onDragStopped = {
                        when (ShellSpec.Notch.pull(pulled)) {
                            ShellSpec.Notch.Pull.VOICE -> { onOpen(false); onVoice() }
                            ShellSpec.Notch.Pull.OPEN -> onOpen(true)
                            ShellSpec.Notch.Pull.NONE -> {}
                        }
                        pulled = 0f
                    })
                .clickable(remember { MutableInteractionSource() }, indication = null) { onOpen(!open) }
                .clearAndSetSemantics { contentDescription = KitA11y.Shell.notch(mode, open); role = Role.Button; onClick { onOpen(!open); true } },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.offset(y = stretch).width(ShellSpec.Notch.HANDLE_W.dp).height(ShellSpec.Notch.HANDLE_H.dp)
                .argusGlass(GlassLevel.CHROME, RoundedCornerShape(ArgusTokens.Radius.PILL.dp), shadow = false), contentAlignment = Alignment.Center) {
                Box(Modifier.width(18.dp).height(4.dp).clip(CircleShape).background(c.mute))
            }
        }
        AnimatedVisibility(open, enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.padding(top = 6.dp)) {
            Row(Modifier.argusGlass(GlassLevel.CHROME, RoundedCornerShape(ArgusTokens.Radius.PILL.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (m in ShellSpec.MODES) {
                    val on = m == mode
                    Box(
                        Modifier.heightIn(min = KitSpec.TARGET.dp).widthIn(min = 64.dp).clip(RoundedCornerShape(ArgusTokens.Radius.PILL.dp))
                            .then(if (on) Modifier.background(c.ink) else Modifier)
                            .clickable(role = Role.Tab) { onOpen(false); onMode(m) }
                            .semantics { selected = on },
                        contentAlignment = Alignment.Center,
                    ) { Text(ShellSpec.label(m), style = ArgusType.chip, color = if (on) c.onInk else c.ink, modifier = Modifier.padding(horizontal = 14.dp)) }
                }
            }
        }
    }
}

/**
 * P1-SH-01 `Composer` (04 §2.8 #3, AT `.cmd`): 58 dp glass pill, `[+]` 42 dp disc, 16 sp field growing to 5 lines then
 * scrolling, and mic → send ↑ → stop ■ ([ShellSpec.Composer.trailing]). Hold the mic = push-to-talk ([onMicHold]),
 * tap = [onMic]. Sits above the keyboard (the caller's column carries imePadding), so the thread never jumps.
 */
@Composable
fun ArgusComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    mode: String,
    streaming: Boolean,
    attachments: Int,
    onPlus: () -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    plusOpen: Boolean = false,
    onMicHold: (() -> Unit)? = null,
) {
    val c = Argus.colors
    val t = ShellSpec.Composer.trailing(value.text, attachments, streaming)
    // A fixed corner (half the one-line height), not PILL: one line still reads as a pill, but a long prompt grows into a
    // rounded box instead of an oval that cuts across the + and send buttons (owner, 13 Nov 2026).
    val shape = RoundedCornerShape(ShellSpec.Composer.CORNER.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = ShellSpec.Composer.SIDE.dp, vertical = 6.dp)
            .heightIn(min = ShellSpec.Composer.HEIGHT.dp).argusGlass(GlassLevel.CHROME, shape).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        ComposerDisc(if (plusOpen) "x" else "plus", if (plusOpen) KitA11y.Shell.PLUS_CLOSE else KitA11y.Shell.PLUS, onPlus, ink = false)
        val placeholder = ShellSpec.Composer.placeholder(mode)
        BasicTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.weight(1f).heightIn(min = ShellSpec.Composer.PLUS.dp).padding(horizontal = 8.dp)
                .semantics { contentDescription = placeholder.trimEnd('\u2026') },
            textStyle = ArgusType.field.copy(color = c.ink),
            cursorBrush = SolidColor(c.ink),
            maxLines = ShellSpec.Composer.MAX_LINES,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorationBox = { inner ->
                Box(Modifier.heightIn(min = ShellSpec.Composer.PLUS.dp).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) Text(placeholder, style = ArgusType.field, color = c.mute, maxLines = 1)
                    inner()
                }
            },
        )
        val act = when (t) { ShellSpec.Composer.Trailing.MIC -> onMic; ShellSpec.Composer.Trailing.SEND -> onSend; ShellSpec.Composer.Trailing.STOP -> onStop }
        ComposerDisc(ShellSpec.Composer.trailingIcon(t), ShellSpec.Composer.trailingLabel(t), act,
            ink = t != ShellSpec.Composer.Trailing.MIC, onLong = if (t == ShellSpec.Composer.Trailing.MIC) onMicHold else null)
    }
}

@Composable
private fun ComposerDisc(icon: String, label: String, onClick: () -> Unit, ink: Boolean, onLong: (() -> Unit)? = null) {
    val c = Argus.colors
    val src = remember { MutableInteractionSource() }
    val d = ShellSpec.Composer.PLUS.dp
    Box(
        Modifier.size(KitSpec.target(ShellSpec.Composer.PLUS).dp).kitCombinedClick(src, onClick, onLong)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button; onClick { onClick(); true } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(d).kitPress(src).then(if (ink) Modifier.clip(CircleShape).background(c.ink) else Modifier.argusGlass(GlassLevel.RAISED, CircleShape, shadow = false)),
            contentAlignment = Alignment.Center) {
            Icon(Lucide.icon(icon, KitSpec.IconButton.ICON), null, Modifier.size(KitSpec.IconButton.ICON.dp), tint = if (ink) c.onInk else c.ink)
        }
    }
}

/** P1-SH-01 `ChipsRow` (04 §2.8 #4): glass chips 10 dp above the composer. */
@Composable
fun ChipsRow(chips: List<ShellSpec.ShellChip>, onChip: (String) -> Unit, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    LazyRow(modifier.fillMaxWidth().padding(bottom = 4.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(KitSpec.Chips.GAP.dp)) {
        items(chips, key = { it.key }) { ch -> KitChip(ChipModel(ch.key, ch.label), on = false) { onChip(ch.key) } }
    }
}

/** P1-SH-01 the shell's toast queue (`.toast`): [show] from anywhere; [ArgusToastHost] draws the newest. */
object ArgusToasts {
    data class Toast(val text: String, val id: Long)
    private val _now = MutableStateFlow<Toast?>(null)
    val now: StateFlow<Toast?> = _now
    private var seq = 0L

    fun show(text: String) { if (text.isNotBlank()) _now.value = Toast(text, ++seq) }
    fun dismiss(id: Long) { if (_now.value?.id == id) _now.value = null }
}

/** P1-SH-01 `ArgusToastHost`: an ink pill above the composer, read by TalkBack (polite live region). */
@Composable
fun ArgusToastHost(modifier: Modifier = Modifier) {
    val c = Argus.colors
    val t by ArgusToasts.now.collectAsState()
    LaunchedEffect(t?.id) { val cur = t ?: return@LaunchedEffect; delay(ShellSpec.Toast.durationMs(cur.text)); ArgusToasts.dismiss(cur.id) }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(t != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
            val text = t?.text.orEmpty()
            Box(Modifier.widthIn(max = maxWidth - 48.dp).padding(bottom = 8.dp).clip(RoundedCornerShape(ArgusTokens.Radius.PILL.dp)).background(c.ink)
                .padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(text, style = ArgusType.sub, color = c.onInk)
            }
        }
    }
}

/** A [TextFieldValue] holding [s] with the cursor at its end (chips and slash commands fill the composer with it). */
fun composerFill(s: String) = TextFieldValue(s, TextRange(s.length))
