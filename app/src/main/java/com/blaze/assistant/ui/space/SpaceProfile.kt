package com.blaze.assistant.ui.space

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blaze.assistant.BrandName
import com.blaze.assistant.ui.SearchGlyph

fun SpaceProfile.Icon.vector(): ImageVector = when (this) {
    // 0.52.4: Rounded, like every other icon in the app; these were Outlined
    SpaceProfile.Icon.PROFILE -> Icons.Rounded.Person
    SpaceProfile.Icon.SCHEDULE -> Icons.Rounded.Schedule
    SpaceProfile.Icon.ACCOUNTS -> Icons.Rounded.Link
    SpaceProfile.Icon.WALLET -> Icons.Rounded.CreditCard
    SpaceProfile.Icon.SECURITY -> Icons.Rounded.Shield
    SpaceProfile.Icon.SYSTEM -> Icons.Rounded.Settings
    SpaceProfile.Icon.FEEDBACK -> Icons.Rounded.ChatBubbleOutline
    SpaceProfile.Icon.ABOUT -> Icons.Rounded.Info
    SpaceProfile.Icon.PLAN -> Icons.Rounded.WorkspacePremium
    SpaceProfile.Icon.SIGN_OUT -> Icons.AutoMirrored.Rounded.Logout
}

/** System Preferences' rows (SpaceSettings.SYSTEM_ROWS ids) get their own icons. */
internal fun systemRowIcon(page: String): ImageVector = when (page) {
    "look" -> Icons.Rounded.Palette
    "language" -> Icons.Rounded.Language
    "voice" -> Icons.Rounded.RecordVoiceOver
    "permissions" -> Icons.Rounded.PrivacyTip
    "updates" -> Icons.Rounded.SystemUpdate
    else -> Icons.Rounded.Settings
}

/**
 * 0.52.3 profile page (Hark's settings page in the ARGUS theme), the root of Settings: a big name with the e-mail under
 * it, the plan card ("ARGUS Cloud" in the serif, Usage and plan), two grouped cards of icon + label + chevron rows with
 * hairlines between them (Profile, Scheduled Tasks, Accounts, Wallet; Security, System Preferences, Submit Feedback,
 * About), Sign out when signed in, and the floating Search pill at the bottom. [onPage] opens an existing settings page.
 */
@Composable
fun SpaceProfileScreen(
    name: String,
    email: String,
    signedIn: Boolean,
    planLine: String,
    onPage: (String) -> Unit,
    onSignOut: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    version: String = "",
) {
    val look = spaceLook()
    Box(modifier.fillMaxSize().testTag(SpaceTags.PROFILE)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = SpaceInsets.GUTTER_DP.dp, end = SpaceInsets.GUTTER_DP.dp, bottom = 110.dp)) {
            // Unification 6/9: the name is the page headline (serif display step), name and e-mail at the gutter like the cards
            Text(name, modifier = Modifier.padding(top = 6.dp).semantics { heading() }, style = SpaceType.display(look.ink))
            Text(email, modifier = Modifier.padding(top = 4.dp), style = SpaceType.bodyText(look.sub))
            Spacer(Modifier.height(18.dp))
            // the plan card
            SpaceGroup {
                Row(Modifier.fillMaxWidth().padding(horizontal = SpaceInsets.CARD_PAD_DP.dp, vertical = SpaceInsets.CARD_PAD_V_DP.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(BrandName.UPPER, style = SpaceType.title(look.ink))
                    Spacer(Modifier.width(8.dp))
                    Text("cloud", style = SpaceType.title(look.sub).copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                    Spacer(Modifier.weight(1f))
                    Text(planLine, style = SpaceType.meta(look.sub))
                }
                SpaceHairline()
                ProfileRow(SpaceProfile.PLAN, showIcon = false) { onPage(SpaceProfile.PLAN.page) }
            }
            for (group in SpaceProfile.GROUPS) {
                Spacer(Modifier.height(16.dp))
                SpaceGroup {
                    group.forEachIndexed { i, r ->
                        if (i > 0) SpaceHairline()
                        ProfileRow(r) { onPage(r.page) }
                    }
                }
            }
            if (signedIn) {
                Spacer(Modifier.height(16.dp))
                SpaceGroup { ProfileRow(SpaceProfile.Row("signout", "Sign out", SpaceProfile.Icon.SIGN_OUT, ""), chevron = false) { onSignOut() } }
            }
            if (version.isNotBlank()) Text("${BrandName.UPPER} $version", modifier = Modifier.padding(top = 18.dp), style = SpaceType.caption(look.sub))
        }
        // the floating Search pill; Unification 6/9: at the gutter, the composer's height
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = SpaceInsets.GUTTER_DP.dp, vertical = SpaceInsets.COMPOSER_BOTTOM_DP.dp).fillMaxWidth().heightIn(min = SpaceInsets.COMPOSER_MIN_DP.dp)
            .clip(SpaceShapes.pill).background(look.track).border(1.dp, look.hair, SpaceShapes.pill)
            .testTag(SpaceTags.PROFILE_SEARCH).clickable(onClickLabel = "Search settings", onClick = onSearch)
            .semantics { role = Role.Button }.padding(horizontal = SpaceInsets.CARD_PAD_DP.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchGlyph(look.ink, SpaceIcons.ROW_ICON_DP.dp)
            Spacer(Modifier.width(SpaceIcons.GAP_DP.dp))
            Text("Search", style = SpaceType.bodyText(look.sub))
        }
    }
}

/**
 * A profile row: [SpaceRow] at the large size with its tag, icon and chevron. Unification 5/9: the group, the row and
 * the hairline are the shared ones in SpaceRows.kt (this page is where they came from).
 */
@Composable
private fun ProfileRow(r: SpaceProfile.Row, showIcon: Boolean = true, chevron: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    SpaceRow(r.label, icon = if (showIcon) icon ?: r.icon.vector() else null, size = SpaceRowSize.LARGE, chevron = chevron,
        tag = SpaceTags.profileRow(r.id), onClickLabel = r.label, onClick = onClick)
}

/** System Preferences: one grouped card of the pages it holds (appearance, language, voice, permissions, updates). */
@Composable
fun SpaceSystemPage(onPage: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        SpaceGroup {
            SpaceSettings.SYSTEM_ROWS.forEachIndexed { i, (page, label) ->
                if (i > 0) SpaceHairline()
                ProfileRow(SpaceProfile.Row(page, label, SpaceProfile.Icon.SYSTEM, page), icon = systemRowIcon(page)) { onPage(page) }
            }
        }
    }
}

/**
 * Submit Feedback: a text box and Send, which hands the note (plus the version and the phone model, nothing else) to
 * the app the user picks (mail, a chat app). Nothing is sent by ARGUS itself.
 */
@Composable
fun SpaceFeedbackPage(version: String) {
    val look = spaceLook()
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("Tell the ${BrandName.NAME} team what works, what doesn't, or what you'd love next.", style = SpaceType.bodyText(look.sub))
        Spacer(Modifier.height(14.dp))
        // Unification 5/9: the shared text field, from SpaceRows.kt
        SpaceField(text, { text = it }, "Your feedback\u2026", singleLine = false, minHeight = 160.dp, desc = "Your feedback")
        Spacer(Modifier.height(16.dp))
        SpacePillButton("Send feedback", primary = true, enabled = text.isNotBlank(), onClick = {
            val body = SpaceFeedback.body(text, version, android.os.Build.MODEL ?: "", android.os.Build.VERSION.SDK_INT)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, SpaceFeedback.SUBJECT).putExtra(Intent.EXTRA_TEXT, body)
            try { ctx.startActivity(Intent.createChooser(send, "Send feedback").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (t: Throwable) { com.blaze.assistant.device.CrashLog.quiet("feedback share", t) }
        })
    }
}
