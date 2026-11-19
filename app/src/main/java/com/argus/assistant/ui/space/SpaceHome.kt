package com.argus.assistant.ui.space

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

// ARGUS-only (16 Nov 2026): the 0.52.3 Home screen (SpaceHomeScreen, SpaceHomeState) is gone; Life (team/LifeMode.kt) is
// the Home. What other screens still use from this file stays.

/**
 * The shell's tab requests from outside the composition (MainActivity: a notification, a secure form or a shared text
 * wants the chat). ChatScreen switches to the Chat tab when [chatRequests] changes; Home is shown through homeRequests.
 */
object SpaceNav {
    val chatRequests = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun showChat() { chatRequests.value = chatRequests.value + 1 }
}

/** A thin rounded progress bar in Electric Blue on the card's tile colour (onboarding's setup). */
@Composable
fun SpaceProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val look = spaceLook()
    Box(modifier.fillMaxWidth().height(8.dp).clip(SpaceShapes.pill).background(look.tile)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(8.dp).clip(SpaceShapes.pill)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color(SpacePalette.ARGUS_BLUE), androidx.compose.ui.graphics.Color(SpacePalette.ELECTRIC)))))
    }
}
