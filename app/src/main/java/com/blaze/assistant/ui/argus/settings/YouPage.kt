package com.blaze.assistant.ui.argus.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.blaze.assistant.BrandName
import com.blaze.assistant.ui.argus.Argus
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.ArgusType
import com.blaze.assistant.ui.argus.GlassLevel
import com.blaze.assistant.ui.argus.argusGlass
import com.blaze.assistant.ui.argus.kit.ArgusButton
import com.blaze.assistant.ui.argus.kit.ArgusSearch
import com.blaze.assistant.ui.argus.kit.ButtonKind
import com.blaze.assistant.ui.argus.kit.ChipModel
import com.blaze.assistant.ui.argus.kit.EmptyState
import com.blaze.assistant.ui.argus.kit.Segmented
import com.blaze.assistant.ui.argus.kit.SettingsGroup
import com.blaze.assistant.ui.argus.kit.SettingsItem

object YouTags {
    const val PAGE = "you"
    const val NAME = "you-name"
    const val PLAN = "you-plan"
    const val SEARCH = "you-search"
    fun row(id: String) = "you-row-$id"
}

/**
 * P1-SH-06 the You page (04 §4.11), the root of Settings in the ARGUS look: name (tap = Profile) and e-mail · plan, the
 * plan card, the four groups of [ArgusSettings.GROUPS], Sign out, version, and the floating "Search settings" pill that
 * filters the rows in place ([ArgusSettings.search]). [onPage] opens a settings page by id.
 */
@Composable
fun YouPage(
    name: String,
    email: String,
    signedIn: Boolean,
    planLine: String,
    onPage: (String) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    version: String = "",
    pageText: Map<String, String> = emptyMap(),
    query0: String = "",
) {
    val c = Argus.colors
    var query by remember { mutableStateOf(query0) }
    val hits = remember(query, pageText) { ArgusSettings.search(query, pageText) }
    Box(modifier.fillMaxSize().testTag(YouTags.PAGE)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open Profile") { onPage(ArgusSettings.ACCOUNT_PAGE) }
                .testTag(YouTags.NAME).padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(name, style = ArgusType.display, color = c.ink, modifier = Modifier.semantics { heading() })
                Text(listOf(email, planLine).filter { it.isNotBlank() }.joinToString(" \u00b7 "), style = ArgusType.sub, color = c.mute)
            }
            if (query.isBlank()) {
                Row(Modifier.fillMaxWidth().argusGlass(GlassLevel.SURFACE, RoundedCornerShape(ArgusTokens.Radius.CARD.dp))
                    .clickable(role = Role.Button, onClickLabel = "Open Usage and plan") { onPage(ArgusSettings.PLAN_PAGE) }
                    .testTag(YouTags.PLAN).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Plan & usage", style = ArgusType.body, color = c.ink, modifier = Modifier.weight(1f))
                    Text(planLine.ifBlank { "${BrandName.UPPER} Cloud" }, style = ArgusType.serifHello.copy(fontStyle = FontStyle.Italic, fontSize = ArgusType.title2.fontSize),
                        color = c.mute)
                }
                for (g in ArgusSettings.GROUPS) SettingsGroup(g.rows.map { it.item(onPage) }, title = g.title)
                if (signedIn) ArgusButton("Sign out", onSignOut, Modifier.fillMaxWidth(), kind = ButtonKind.GLASS, icon = "log-out")
                if (version.isNotBlank()) Text("${BrandName.UPPER} $version", style = ArgusType.meta, color = c.meta, modifier = Modifier.padding(start = 8.dp))
            } else if (hits.isEmpty()) {
                EmptyState(title = "Nothing called \u201c${query.trim()}\u201d", icon = "search", body = "Try another word, or ask ${BrandName.NAME} to open it.")
            } else {
                SettingsGroup(hits.map { it.item(onPage) }, title = "Results")
            }
        }
        ArgusSearch(query, { query = it }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp).fillMaxWidth().testTag(YouTags.SEARCH), placeholder = "Search settings")
    }
}

private fun ArgusSettings.Row.item(onPage: (String) -> Unit) = SettingsItem(icon, label, value, onClick = { onPage(page) })

/**
 * P1-SH-06 Appearance in the ARGUS look (04 §4.11, 06 row 6): the ARGUS look switch (`argus_look`, off = the 0.52
 * shell), Theme (Match phone / Dark / Light), Ground (Plain / Orbit / Cobalt night), Handedness, Reduced motion. No
 * accent: the ARGUS look has none (the 0.52 page keeps its accent picker). [onTheme] writes through ThemeMode.
 */
@Composable
fun ArgusAppearanceSection(
    lookOn: Boolean,
    onLook: (Boolean) -> Unit,
    themeMode: String,
    onTheme: (String) -> Unit,
    ground: String,
    onGround: (String) -> Unit,
    hand: ArgusSettings.Hand,
    onHand: (ArgusSettings.Hand) -> Unit,
    reduced: Boolean,
    onReduced: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Argus.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsGroup(listOf(SettingsItem("layers", "${BrandName.UPPER} look", if (lookOn) "On" else "Off", chevron = false, onClick = { onLook(!lookOn) })))
        Text("Off brings back the 0.52 screens. The change shows the next time ${BrandName.NAME} opens.", style = ArgusType.sub, color = c.mute)
        Head("Theme")
        Segmented(ArgusSettings.ThemeChoice.entries.map { ChipModel(it.mode, it.label) }, ArgusSettings.ThemeChoice.of(themeMode)?.mode.orEmpty(), onTheme)
        if (ArgusSettings.ThemeChoice.of(themeMode) == null) Text("Auto (dark after sunset) is on. Pick one to change it.", style = ArgusType.sub, color = c.mute)
        Head("Ground")
        Segmented(ArgusSettings.GROUNDS.map { ChipModel(it.first, it.second) }, ground, onGround)
        Head("Handedness")
        Segmented(ArgusSettings.Hand.entries.map { ChipModel(it.value, it.label) }, hand.value, { onHand(ArgusSettings.Hand.of(it)) })
        Text("Puts the main button of sheets and empty states on your thumb's side.", style = ArgusType.sub, color = c.mute)
        SettingsGroup(listOf(SettingsItem("zap", "Reduced motion", if (reduced) "On" else "Off", chevron = false, onClick = { onReduced(!reduced) })))
        Text("Buttons, orbs and progress bars stop animating. On by itself when the phone's animations are off.", style = ArgusType.sub, color = c.mute)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Head(t: String) {
    Text(t.uppercase(), style = ArgusType.section, color = Argus.colors.meta, modifier = Modifier.padding(start = 4.dp).semantics { heading() })
}

/** P1-SH-06 the Appearance prefs the kit reads (Settings-backed; cheap reads, so defaults can call them each composition). */
object ArgusPrefs {
    fun reducedSetting(): Boolean = try { com.blaze.assistant.agent.Settings.bool(ArgusSettings.REDUCED_MOTION_KEY, false) } catch (_: Throwable) { false }
    fun setReduced(on: Boolean) { try { com.blaze.assistant.agent.Settings.setBool(ArgusSettings.REDUCED_MOTION_KEY, on) } catch (_: Throwable) { } }
    fun reducedNow(): Boolean = ArgusSettings.reducedMotion(reducedSetting(),
        try { if (android.animation.ValueAnimator.areAnimatorsEnabled()) 1f else 0f } catch (_: Throwable) { 1f })
    fun hand(): ArgusSettings.Hand = try { ArgusSettings.Hand.of(com.blaze.assistant.agent.Settings.str(ArgusSettings.HAND_KEY, "right")) } catch (_: Throwable) { ArgusSettings.Hand.RIGHT }
    fun setHand(h: ArgusSettings.Hand) { try { com.blaze.assistant.agent.Settings.setStr(ArgusSettings.HAND_KEY, h.value) } catch (_: Throwable) { } }
}

/** The Appearance section wired to the live prefs (LookPage calls it in the ARGUS look). */
@Composable
fun ArgusAppearanceLive() {
    var look by remember { mutableStateOf(Argus.lookOn()) }
    var ground by remember { mutableStateOf(Argus.groundSetting(com.blaze.assistant.ui.argus.Ground.ORBIT).name) }
    var hand by remember { mutableStateOf(ArgusPrefs.hand()) }
    var reduced by remember { mutableStateOf(ArgusPrefs.reducedSetting()) }
    val mode = com.blaze.assistant.ui.ThemeMode.mode.value
    ArgusAppearanceSection(
        lookOn = look, onLook = { look = it; try { com.blaze.assistant.agent.Settings.setBool(com.blaze.assistant.ui.argus.ArgusLookFlag.KEY, it) } catch (_: Throwable) { } },
        themeMode = mode, onTheme = { com.blaze.assistant.ui.ThemeMode.set(it) },
        ground = ground, onGround = { ground = it; try { com.blaze.assistant.agent.Settings.setStr(com.blaze.assistant.ui.argus.ArgusLookFlag.GROUND_KEY, it) } catch (_: Throwable) { } },
        hand = hand, onHand = { hand = it; ArgusPrefs.setHand(it) },
        reduced = reduced, onReduced = { reduced = it; ArgusPrefs.setReduced(it) },
    )
}
