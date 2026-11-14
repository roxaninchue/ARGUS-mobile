package com.blaze.assistant.ui

import com.blaze.assistant.i18n.tr
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.agent.Delivery
import com.blaze.assistant.agent.DeliveryState

/**
 * 0.45.20 delivery label (user spec B, contract §1): under your LATEST bubble only, right-aligned, small and muted but
 * readable on every theme (a soft capsule, AA over any ground: [BubblePalette.capsule]). Animated dots + "Sending",
 * then "Sent", then "Read". "Not sent, tap to retry" when the gateway refused it. No ticks. TalkBack hears each change
 * politely (a polite live region with the spoken words from [Delivery.spoken]).
 */
object DeliveryView {
    const val TAG = "delivery_label"

    /**
     * 0.52.1 (owner, screenshot 1000172533): the receipt is back under your latest message, "•••• Read" style: the four
     * dots animate while it is sending / being read, then stay still beside "Read". (0.46.6 had hidden Read.)
     */
    fun shown(@Suppress("UNUSED_PARAMETER") state: DeliveryState): Boolean = true

    /** The word beside the dots: none while the dots animate (sending / sent), "Read" once read, the retry line when refused. */
    fun word(state: DeliveryState): String = when (state) {
        DeliveryState.SENDING, DeliveryState.SENT -> ""
        else -> Delivery.label(state)
    }

    /** Dots animate until it is read (or refused). */
    fun animates(state: DeliveryState): Boolean = state == DeliveryState.SENDING || state == DeliveryState.SENT

    /**
     * What the label shows for the latest user bubble. [tracked] = the tracker's state (null: never tracked, e.g. older
     * chats or a local-only answer); [answered] = a real Blaze reply (not the ack) came after it; [busy] = a turn is
     * running in this chat now. Read only ever comes from a real event or a real reply, never from time passing.
     */
    fun effective(tracked: DeliveryState?, answered: Boolean, busy: Boolean): DeliveryState = when {
        answered -> DeliveryState.READ
        tracked == DeliveryState.NOT_SENT -> DeliveryState.NOT_SENT
        // A send left at Sending with nothing running (the app was closed meanwhile, or Stop): honestly not sent.
        tracked == DeliveryState.SENDING && !busy -> DeliveryState.NOT_SENT
        tracked != null -> tracked
        busy -> DeliveryState.SENDING
        else -> DeliveryState.SENT
    }
}

@Composable
fun DeliveryLabel(state: DeliveryState, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val c = LocalBlaze.current
    val ink = BubblePalette.capsuleInk(c)
    val failed = state == DeliveryState.NOT_SENT
    Row(modifier.fillMaxWidth().padding(top = 4.dp, end = 6.dp), horizontalArrangement = Arrangement.End) {
        Row(Modifier
            .let { m -> if (failed) m.clip(RoundedCornerShape(10.dp)).background(BubblePalette.capsule(c), RoundedCornerShape(10.dp)) else m }
            .let { m -> if (failed && onRetry != null) m.clickable(role = Role.Button, onClickLabel = tr("Retry sending")) { onRetry() } else m }
            .heightIn(min = if (failed) 32.dp else 0.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; contentDescription = Delivery.spoken(state) }
            .padding(horizontal = if (failed) 8.dp else 2.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!failed) ReceiptDots(ink, animate = DeliveryView.animates(state))
            val word = DeliveryView.word(state)
            if (word.isNotEmpty()) {
                if (!failed) Spacer(Modifier.width(6.dp))
                Text(word, style = TextStyle(fontFamily = Type.Sans, fontSize = 12.sp, lineHeight = 14.sp,
                    fontWeight = if (failed) FontWeight.Medium else FontWeight.Normal, color = if (failed) c.ink else ink))
            }
        }
    }
}

/**
 * "••••": four dots growing left to right (the reference receipt). While [animate], a soft wave runs through them
 * (each dot brightens and swells in turn); still with Remove animations on.
 */
@Composable
private fun ReceiptDots(ink: androidx.compose.ui.graphics.Color, animate: Boolean) {
    val sizes = listOf(2.2f, 2.8f, 3.4f, 4.0f)
    val phase = if (animate && MotionTokens.enabled) rememberInfiniteTransition(label = "receipt")
        .animateFloat(0f, 4f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "receiptDots").value else -1f
    Row(horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        sizes.forEachIndexed { i, d ->
            val near = if (phase < 0f) 0f else (1f - kotlin.math.abs(phase - i - 0.5f).coerceAtMost(1f))
            val a = if (animate) 0.35f + 0.65f * near else 0.55f + 0.45f * (i / 3f)
            Box(Modifier.size((d + if (animate) 0.8f * near else 0f).dp).alpha(a).clip(CircleShape).background(ink))
        }
    }
}
