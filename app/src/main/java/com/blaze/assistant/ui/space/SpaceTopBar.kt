package com.blaze.assistant.ui.space

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blaze.assistant.ui.MotionTokens
import com.blaze.assistant.ui.SearchGlyph

/**
 * Hark's top bar: a round button on the left (+ on Home, search in Chat, the menu in Projects), the centred segmented
 * pill Home | Chat | Projects (the selected segment a white pill with dark text), and the round avatar with the initial
 * on the right (tap = the profile page, long-press = light / dark). [privateChat]: the pill shows "Private" instead
 * (tap = leave). On narrow screens or large fonts the pill's labels shrink ([SpacePillFit]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpaceTopBar(
    tab: String,
    onTab: (String) -> Unit,
    onLeft: () -> Unit,
    initial: String,
    onAvatar: () -> Unit,
    modifier: Modifier = Modifier,
    onAvatarLong: () -> Unit = {},
    privateChat: Boolean = false,
    onLeavePrivate: () -> Unit = {},
    showLeft: Boolean = true,
) {
    val look = spaceLook()
    Row(modifier.fillMaxWidth().testTag(SpaceTags.TOP_BAR).padding(horizontal = SpaceInsets.EDGE_DP.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        val left = SpaceTabs.leftButton(tab)
        if (showLeft) SpaceRoundButton(SpaceTabs.leftLabel(left), onLeft, tag = SpaceTags.LEFT_BUTTON, size = SpaceInsets.ROUND_DP.dp) {
            when (left) {
                SpaceTabs.Left.PLUS -> PlusMark(look.ink, 20.dp)
                SpaceTabs.Left.SEARCH -> SearchGlyph(look.ink, 19.dp)
                SpaceTabs.Left.MENU -> MenuMark(look.ink, 20.dp)
            }
        } else Spacer(Modifier.size(SpaceInsets.ROUND_DP.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (privateChat) PrivatePill(onLeavePrivate) else SegmentedPill(tab, onTab)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(SpaceInsets.ROUND_DP.dp).clip(CircleShape).background(Color(SpacePalette.ARGUS_BLUE)).testTag(SpaceTags.AVATAR)
            .combinedClickable(onClickLabel = "Profile and settings", onClick = onAvatar, onLongClick = onAvatarLong,
                onLongClickLabel = if (look.dark) "Switch to light mode" else "Switch to dark mode")
            .semantics { contentDescription = "Profile and settings. Long-press to switch light or dark"; role = Role.Button },
            contentAlignment = Alignment.Center) {
            Text(initial.take(1), style = SpaceType.body(18.sp, Color.White, FontWeight.Medium))
        }
    }
}

@Composable
private fun SegmentedPill(tab: String, onTab: (String) -> Unit) {
    val look = spaceLook()
    BoxWithConstraints {
        val fit = SpacePillFit.pick(maxWidth.value, LocalDensity.current.fontScale)
        Row(Modifier.clip(SpaceShapes.pill).background(look.track).testTag(SpaceTags.PILL).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((id, label) in SpaceTabs.ALL) {
                val on = id == tab
                val fill by animateColorAsState(if (on) look.selectedFill else Color.Transparent, MotionTokens.medium(), label = "segment")
                val ink by animateColorAsState(if (on) look.selectedInk else look.sub, MotionTokens.medium(), label = "segmentInk")
                Box(Modifier.clip(SpaceShapes.pill).background(fill).testTag(SpaceTags.tab(id))
                    .clickable(onClickLabel = "Show $label") { onTab(id) }
                    .semantics { selected = on; role = Role.Tab }
                    .heightIn(min = SpaceInsets.SEGMENT_DP.dp).padding(horizontal = fit.padDp.dp), contentAlignment = Alignment.Center) {
                    Text(label, maxLines = 1, softWrap = false, style = SpaceType.body(fit.fontSp.sp, ink, FontWeight.Medium))
                }
            }
        }
    }
}

@Composable
private fun PrivatePill(onLeave: () -> Unit) {
    val look = spaceLook()
    Box(Modifier.clip(SpaceShapes.pill).background(look.selectedFill).clickable(onClickLabel = "Leave private chat", onClick = onLeave)
        .heightIn(min = SpaceInsets.SEGMENT_DP.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text("Private chat", style = SpaceType.body(15.sp, look.selectedInk, FontWeight.Medium))
    }
}
