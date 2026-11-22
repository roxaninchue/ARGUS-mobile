package com.blaze.assistant.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * WhatsApp-style reply: grab a message and slide it right. Past the threshold the phone ticks,
 * and on release the message is quoted in the composer; the message springs back either way.
 * Only rightward drags count, and inner horizontal scrollers (tables, code) still get theirs first.
 */
@Composable
fun SwipeToQuote(enabled: Boolean, onQuote: () -> Unit, content: @Composable () -> Unit) {
    val c = LocalBlaze.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    val max = with(density) { 96.dp.toPx() }
    val x = remember { Animatable(0f) }
    val armed = remember { booleanArrayOf(false) }
    val quote by rememberUpdatedState(onQuote)
    val on by rememberUpdatedState(enabled)
    Box(Modifier.fillMaxWidth()) {
        // The reply arrow that appears from the left as the message slides.
        Text("\u21A9", modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
            .alpha((x.value / threshold).coerceIn(0f, 1f)),
            style = TextStyle(fontSize = 18.sp, color = if (x.value >= threshold) c.accent else c.sub))
        Box(Modifier.fillMaxWidth()
            .offset { IntOffset(x.value.roundToInt(), 0) }
            .pointerInput(Unit) {
                // 0.55.9 (Finn: "sometimes the screen stops reading my swipes"): detectHorizontalDragGestures claimed ANY
                // gesture whose sideways movement crossed the touch slop first, leftward or diagonal included, and
                // consumed it, so the chat list never got that swipe. Now the bubble only takes a drag that is clearly
                // rightward (more sideways than down); everything else is left untouched for the list to scroll.
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!on) return@awaitEachGesture
                    var dx = 0f
                    var dy = 0f
                    while (true) {
                        val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!ch.pressed || ch.isConsumed) return@awaitEachGesture   // lifted, or the list / an inner scroller took it
                        val d = ch.positionChange()
                        dx += d.x; dy += d.y
                        if (QuoteSwipe.claims(dx, dy, slop)) { ch.consume(); break }
                        if (QuoteSwipe.notOurs(dx, dy, slop)) return@awaitEachGesture
                    }
                    armed[0] = false
                    val done = horizontalDrag(down.id) { change ->
                        val next = (x.value + change.positionChange().x * 0.6f).coerceIn(0f, max)   // a little resistance, like WhatsApp
                        change.consume()
                        if (next != x.value) {
                            if (!armed[0] && next >= threshold) { armed[0] = true; com.blaze.assistant.device.Haptics.tick(ctx) }
                            else if (armed[0] && next < threshold) armed[0] = false
                            scope.launch { x.snapTo(next) }
                        }
                    }
                    if (done && armed[0] && on) quote()
                    armed[0] = false
                    scope.launch { x.animateTo(0f, spring()) }
                }
            }) { content() }
    }
}

/** 0.55.9: when a swipe on a bubble is a quote swipe (pure, QuoteSwipeTest). [dx] / [dy] = movement since the press. */
object QuoteSwipe {
    /** Not a quote swipe (checked after [claims]): it moved past the slop up or down (the list scrolls), or leftward. */
    fun notOurs(dx: Float, dy: Float, slop: Float): Boolean = kotlin.math.abs(dy) > slop || dx < -slop
    /** The bubble's: rightward past the slop and clearly more sideways than down (1.5 : 1). */
    fun claims(dx: Float, dy: Float, slop: Float): Boolean = dx > slop && dx > kotlin.math.abs(dy) * 1.5f
}
