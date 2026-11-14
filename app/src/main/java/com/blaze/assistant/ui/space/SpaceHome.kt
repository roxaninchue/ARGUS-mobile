package com.blaze.assistant.ui.space

import com.blaze.assistant.i18n.tr
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
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
import com.blaze.assistant.BrandName
import com.blaze.assistant.ui.BlazeIconStyle
import com.blaze.assistant.ui.IconTile

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
 * Unification 6/9: the cards sit at the shell gutter (the round button's and the avatar's edge) and every text takes a
 * step of the SpaceType scale.
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
    LazyColumn(modifier.fillMaxSize().testTag(SpaceTags.HOME), contentPadding = PaddingValues(start = SpaceInsets.GUTTER_DP.dp, end = SpaceInsets.GUTTER_DP.dp, top = 8.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (card in cards) item(key = card.name) {
            when (card) {
                SpaceHomeCards.Card.HERO -> SpaceCard(tag = SpaceTags.HERO) {
                    SpaceHeadline(SpaceGreeting.hello(state.hour, state.name))
                    Spacer(Modifier.height(8.dp))
                    Text(SpaceGreeting.sub(state.working), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = SpaceType.bodyText(look.sub))
                }
                SpaceHomeCards.Card.SIGN_IN -> SpaceCard(tag = SpaceTags.SIGN_IN) {
                    SpaceHeadline(tr("{0} can do more", BrandName.UPPER), size = SpaceTypeScale.TITLE.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(tr("Sign in to your {0} account and it starts working for you right away. No keys, no settings.", BrandName.NAME),
                        style = SpaceType.bodyText(look.ink))
                    Spacer(Modifier.height(16.dp))
                    SpacePillButton(tr("Sign in"), onSignIn, primary = true, icon = Icons.AutoMirrored.Rounded.Login)
                }
                SpaceHomeCards.Card.SETUP -> SpaceCard(tag = SpaceTags.SETUP_CARD, onClick = onSetup, onClickLabel = tr("Show setup")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Rounded.Downloading, tint = look.accent)
                        Spacer(Modifier.width(BlazeIconStyle.gap))
                        Text(tr("Finishing setup"), modifier = Modifier.weight(1f), style = SpaceType.rowTitle(look.ink))
                        Text("${state.setupPercent}%", style = SpaceType.rowTitle(look.accent))
                    }
                    Spacer(Modifier.height(12.dp))
                    SpaceProgressBar(state.setupPercent / 100f)
                    Spacer(Modifier.height(10.dp))
                    Text(if (state.setupLeft == 1) "1 item left. It keeps going in the background." else "${state.setupLeft} items left. They keep going in the background.",
                        style = SpaceType.meta(look.sub))
                }
                SpaceHomeCards.Card.ANSWER -> state.answer?.let { a ->
                    SpaceCard(tag = SpaceTags.ANSWER, onClick = onOpenChat, onClickLabel = tr("Open the answer in the chat")) {
                        Text(a.ask, maxLines = 1, overflow = TextOverflow.Ellipsis, style = SpaceType.meta(look.sub))
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().clip(SpaceShapes.tile).background(look.tile).padding(16.dp)) {
                            Text(if (a.text.isBlank()) "Working on it\u2026" else a.text, maxLines = 6, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = SpaceType.bodyText(look.ink))
                        }
                        Spacer(Modifier.height(14.dp))
                        SpaceChip("Open in chat", onOpenChat, icon = Icons.Rounded.ChatBubbleOutline)
                    }
                }
                SpaceHomeCards.Card.PROJECTS -> SpaceCard(tag = SpaceTags.PROJECTS_CARD) {
                    SpaceHeadline(tr("Projects"), size = SpaceTypeScale.TITLE.sp)
                    Spacer(Modifier.height(10.dp))
                    state.projects.take(SpaceHomeCards.MAX_PROJECTS).forEachIndexed { i, (id, name) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(look.hair))
                        Row(Modifier.fillMaxWidth().clickable(onClickLabel = tr("Open {0}", name)) { onOpenProject(id) }.padding(vertical = SpaceInsets.ROW_PAD_V_DP.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            // 0.52.4: a folder tile instead of a dot, in the shared icon-tile style
                            IconTile(Icons.Rounded.Folder, tint = look.accent)
                            Spacer(Modifier.width(BlazeIconStyle.gap))
                            Text(name.ifBlank { "Untitled project" }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = SpaceType.rowTitle(look.ink))
                            ChevronMark(look.sub)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(tr("All projects \u203a"), modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenProjects).padding(vertical = 6.dp),
                        style = SpaceType.body(SpaceTypeScale.META.sp, look.accent, FontWeight.Medium))
                }
                SpaceHomeCards.Card.SUGGESTIONS -> SpaceCard(tag = SpaceTags.SUGGESTIONS) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = look.sub, modifier = Modifier.size(BlazeIconStyle.buttonIcon))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Try asking"), style = SpaceType.meta(look.sub))
                    }
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SpaceGreeting.SUGGESTIONS.forEach { s -> SpaceChip(s, { onAsk(s) }) }
                    }
                }
                SpaceHomeCards.Card.NEW_CHAT -> SpacePillButton(tr("New project"), onOpenProjects, plus = true, tag = SpaceTags.NEW_CHAT)
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
