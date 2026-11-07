package com.blazeinchue.argus.ui.space

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blazeinchue.argus.BrandName

/**
 * The shell's tab requests from outside the composition (MainActivity: a notification, a secure form or a shared text
 * wants the chat). ChatScreen switches to the Chat tab when [chatRequests] changes; Home is shown through homeRequests.
 */
object SpaceNav {
    val chatRequests = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun showChat() { chatRequests.value = chatRequests.value + 1 }
}

/** What the Home shows; built by the shell from the live app (ChatScreen) or by tests with sample data. */
@Immutable
data class SpaceHomeState(
    val name: String? = null,
    val hour: Int = 9,
    val signedIn: Boolean = true,
    val answer: SpaceAnswer.Card? = null,
    val working: Boolean = false,
    /** (id, name) of the most recent projects. */
    val projects: List<Pair<String, String>> = emptyList(),
    val setupLeft: Int = 0,
    val setupPercent: Int = 100,
)

/**
 * 0.52.3 Home (the Hark Home in the ARGUS theme): a vertical stack of large rounded cards over the space view. A hero
 * card with the serif greeting, then what needs you (sign in, the rest of setup), the latest answer, recent projects,
 * suggested asks as white pill chips, and a "+ New project" pill. The top bar and the floating composer belong to the
 * shell (they are shared with Chat and Projects); [bottomPad] keeps the last card clear of the composer.
 * Replaces the orb Home (0.52.2), which is gone with its GL scene.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpaceHomeScreen(
    state: SpaceHomeState,
    onAsk: (String) -> Unit,
    onOpenChat: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenProjects: () -> Unit,
    onSignIn: () -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPad: Dp = 96.dp,
) {
    val look = spaceLook()
    val cards = SpaceHomeCards.plan(SpaceHomeCards.Input(state.signedIn, state.answer != null, state.projects.size, state.setupLeft))
    LazyColumn(modifier.fillMaxSize().testTag(SpaceTags.HOME), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (card in cards) item(key = card.name) {
            when (card) {
                SpaceHomeCards.Card.HERO -> SpaceCard(tag = SpaceTags.HERO) {
                    SpaceHeadline(SpaceGreeting.hello(state.hour, state.name), size = 32.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(SpaceGreeting.sub(state.working), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = SpaceType.body(17.sp, look.sub))
                }
                SpaceHomeCards.Card.SIGN_IN -> SpaceCard(tag = SpaceTags.SIGN_IN) {
                    SpaceHeadline("${BrandName.UPPER} can do more", size = 28.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Sign in to your ${BrandName.NAME} account and it starts working for you right away. No keys, no settings.",
                        style = SpaceType.body(17.sp, look.ink))
                    Spacer(Modifier.height(16.dp))
                    SpacePillButton("Sign in", onSignIn, primary = true)
                }
                SpaceHomeCards.Card.SETUP -> SpaceCard(tag = SpaceTags.SETUP_CARD, onClick = onSetup, onClickLabel = "Show setup") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Finishing setup", modifier = Modifier.weight(1f), style = SpaceType.body(18.sp, look.ink, FontWeight.Medium))
                        Text("${state.setupPercent}%", style = SpaceType.body(16.sp, look.accent, FontWeight.Medium))
                    }
                    Spacer(Modifier.height(12.dp))
                    SpaceProgressBar(state.setupPercent / 100f)
                    Spacer(Modifier.height(10.dp))
                    Text(if (state.setupLeft == 1) "1 item left. It keeps going in the background." else "${state.setupLeft} items left. They keep going in the background.",
                        style = SpaceType.body(15.sp, look.sub))
                }
                SpaceHomeCards.Card.ANSWER -> state.answer?.let { a ->
                    SpaceCard(tag = SpaceTags.ANSWER, onClick = onOpenChat, onClickLabel = "Open the answer in the chat") {
                        Text(a.ask, maxLines = 1, overflow = TextOverflow.Ellipsis, style = SpaceType.body(14.sp, look.sub))
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().clip(SpaceShapes.tile).background(look.tile).padding(16.dp)) {
                            Text(if (a.text.isBlank()) "Working on it\u2026" else a.text, maxLines = 6, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = SpaceType.body(17.sp, look.ink))
                        }
                        Spacer(Modifier.height(14.dp))
                        SpaceChip("Open in chat", onOpenChat)
                    }
                }
                SpaceHomeCards.Card.PROJECTS -> SpaceCard(tag = SpaceTags.PROJECTS_CARD) {
                    SpaceHeadline("Projects", size = 24.sp)
                    Spacer(Modifier.height(10.dp))
                    state.projects.take(SpaceHomeCards.MAX_PROJECTS).forEachIndexed { i, (id, name) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(look.hair))
                        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open $name") { onOpenProject(id) }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(SpacePalette.ELECTRIC)))
                            Spacer(Modifier.width(14.dp))
                            Text(name.ifBlank { "Untitled project" }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = SpaceType.body(17.sp, look.ink))
                            ChevronMark(look.sub)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("All projects \u203a", modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenProjects).padding(vertical = 6.dp),
                        style = SpaceType.body(15.sp, look.accent, FontWeight.Medium))
                }
                SpaceHomeCards.Card.SUGGESTIONS -> SpaceCard(tag = SpaceTags.SUGGESTIONS) {
                    Text("Try asking", style = SpaceType.body(15.sp, look.sub))
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SpaceGreeting.SUGGESTIONS.forEach { s -> SpaceChip(s, { onAsk(s) }) }
                    }
                }
                SpaceHomeCards.Card.NEW_CHAT -> SpacePillButton("New project", onOpenProjects, plus = true, tag = SpaceTags.NEW_CHAT)
            }
        }
    }
}

/** A thin rounded progress bar in Electric Blue on the card's tile colour. */
@Composable
fun SpaceProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val look = spaceLook()
    Box(modifier.fillMaxWidth().height(8.dp).clip(SpaceShapes.pill).background(look.tile)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(8.dp).clip(SpaceShapes.pill)
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color(SpacePalette.ARGUS_BLUE), androidx.compose.ui.graphics.Color(SpacePalette.ELECTRIC)))))
    }
}
