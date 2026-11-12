package com.blaze.assistant.ui.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.ui.argus.ArgusGround
import com.blaze.assistant.ui.argus.ArgusTheme
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.BotOrb
import com.blaze.assistant.ui.argus.ManagerOrb
import com.blaze.assistant.ui.argus.OrbFace
import com.blaze.assistant.ui.argus.OrbSize
import com.blaze.assistant.ui.argus.kit.ArgusComposer
import com.blaze.assistant.ui.argus.kit.ArgusTopBar
import com.blaze.assistant.ui.argus.kit.ChipsRow
import com.blaze.assistant.ui.argus.kit.KitSpec
import com.blaze.assistant.ui.argus.kit.NotchSwitcher
import com.blaze.assistant.ui.argus.kit.ShellSpec
import com.blaze.assistant.ui.argus.settings.ATeamBackupSection
import com.blaze.assistant.ui.argus.settings.ATeamStage
import com.blaze.assistant.ui.argus.settings.ArgusAppearanceSection
import com.blaze.assistant.ui.argus.settings.ArgusSettings
import com.blaze.assistant.ui.argus.settings.YouPage
import com.blaze.assistant.ui.team.BotEditorSheet
import com.blaze.assistant.ui.team.ChatDrawerSheet
import com.blaze.assistant.ui.team.ChatModeEmpty
import com.blaze.assistant.ui.team.DeskModel
import com.blaze.assistant.ui.team.DeskNav
import com.blaze.assistant.ui.team.DeskPage
import com.blaze.assistant.ui.team.DeskState
import com.blaze.assistant.ui.team.JobSheet
import com.blaze.assistant.ui.team.JobsPage
import com.blaze.assistant.ui.team.LifeMode
import com.blaze.assistant.ui.team.LifeNav
import com.blaze.assistant.ui.team.NewBotSheet
import com.blaze.assistant.ui.team.NewJobSheet
import com.blaze.assistant.ui.team.PlanSheet
import com.blaze.assistant.ui.team.TeamMode
import com.blaze.assistant.ui.team.TeamNav
import com.blaze.assistant.ui.team.thread.ThreadActions
import com.blaze.assistant.ui.team.thread.ThreadList

/**
 * P1-QA-01 fixture composables: every P1 screen on [P1Fixtures], one function per [ShotMatrix.SCREENS] key, each in a
 * [P1Frame] for one matrix cell (theme, ground, font scale). P1ShotsTest draws these; they are also the screens to
 * open by hand in a debug harness. All callbacks are no-ops: these are pictures, the behaviour checks live in each
 * screen's own *ShotsTest / *ModelTest.
 *
 * Not annotated `@Preview`: the app has no `ui-tooling-preview` dependency and Android Studio doesn't render previews
 * from the unit-test source set. To get Studio previews, add `debugImplementation("androidx.compose.ui:ui-tooling")`
 * + `ui-tooling-preview` and wrap [Screen] in `@Preview` functions under `src/debug` (see overhaul.md Deviations).
 */
@Composable
fun P1Frame(cell: ShotMatrix.Cell, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, cell.fontScale)) {
        ArgusTheme(dark = cell.dark, ground = cell.ground) { ArgusGround { content() } }
    }
}

/** The screen for [key] in [cell]. Unknown keys throw, so a screen added to the matrix without a picture fails loudly. */
@Composable
fun P1Screen(key: String, cell: ShotMatrix.Cell) {
    P1Frame(cell) {
        when (key) {
            "shell" -> ShellPreview()
            "team" -> TeamPreview(empty = false)
            "team-empty" -> TeamPreview(empty = true)
            "thread" -> ThreadPreview()
            "chat" -> ChatModePreview()
            "chat-drawer" -> ChatDrawerPreview()
            "life" -> LifePreview()
            "settings-you" -> YouPreview()
            "settings-appearance" -> AppearancePreview(cell)
            "new-bot" -> NewBotSheet(onChoice = {}, onDismiss = {})
            "bot-editor" -> BotEditorSheet(P1Fixtures.editorState(), P1Fixtures.roster, onChange = {}, onSave = {}, onDelete = {}, onDismiss = {})
            "plan" -> PlanSheet(P1Fixtures.plan(), instant = false, onInstant = {}, onApply = {}, onCancel = {})
            "desk" -> DeskPreview()
            "jobs" -> JobsPreview()
            "job-sheet" -> JobSheet(P1Fixtures.jobSheet(), onDismiss = {}, onRetry = {}, onDone = {}, onCancel = {})
            "backup-import" -> BackupImportPreview()
            "new-job" -> NewJobSheet(P1Fixtures.roster, P1Fixtures.today, initialBot = "atlas", onDismiss = {}, onAdd = {})
            else -> throw IllegalArgumentException("no P1 preview for screen $key")
        }
    }
}

/** P1-SH-01: the shell as the app shows it on Team: top bar with the notch pill open, the Team page, chips, composer. */
@Composable
fun ShellPreview() {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) {
                ArgusTopBar(initial = "F", onMe = {}, onMeLong = {}, onSearch = {}, onBell = {}, bellBadge = true, onPlus = {})
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TeamMode(P1Fixtures.teamState(), P1Fixtures.bots, TeamNav(), { _, _ -> }, {}, {})
                }
            }
            // the notch is drawn over the content below, centred in the bar (ChatScreen's overlay)
            Box(Modifier.fillMaxWidth().padding(top = ((ShellSpec.TOP_BAR_DP - KitSpec.TARGET) / 2).dp), contentAlignment = Alignment.TopCenter) {
                NotchSwitcher(mode = "team", open = true, onOpen = {}, onMode = {}, onVoice = {})
            }
        }
        ChipsRow(ShellSpec.Chips.of("team"), {})
        ArgusComposer(TextFieldValue(""), {}, mode = "team", streaming = false, attachments = 0, onPlus = {}, onSend = {}, onMic = {}, onStop = {})
    }
}

/** P1-TM-03 Team mode, busy or with only the Manager. */
@Composable
fun TeamPreview(empty: Boolean) {
    val s = if (empty) P1Fixtures.emptyTeamState() else P1Fixtures.teamState()
    val bots = if (empty) mapOf(BotRules.MANAGER_ID to P1Fixtures.manager) else P1Fixtures.bots
    TeamMode(s, bots, TeamNav(), { _, _ -> }, {}, {})
}

/** P1-SH-03 Atlas's thread with a live turn. */
@Composable
fun ThreadPreview() { ThreadList(P1Fixtures.threadItems(live = true), ThreadActions(), Modifier.fillMaxSize()) }

/** P1-SH-04 Chat mode, empty: the greeting at 11:00. */
@Composable
fun ChatModePreview() { ChatModeEmpty(P1Fixtures.USER_NAME, privateOn = false, onAsk = {}, hour = P1Fixtures.nowTime.hour) }

/** P1-SH-04 the history drawer on the fixture chats. */
@Composable
fun ChatDrawerPreview() { ChatDrawerSheet(P1Fixtures.drawerState(), "", {}, onOpen = {}, onMenu = {}, onNew = {}) }

/** P1-SH-05 Life mode on the fixture Thursday. */
@Composable
fun LifePreview() { LifeMode(P1Fixtures.lifeState(), LifeNav()) }

/** P1-SH-06 the You page. */
@Composable
fun YouPreview() {
    YouPage(P1Fixtures.USER_NAME, P1Fixtures.USER_EMAIL, signedIn = true, planLine = P1Fixtures.PLAN_LINE, onPage = {}, onSignOut = {},
        version = P1Fixtures.VERSION)
}

/** P1-SH-06 Appearance, showing the cell's own theme and ground as picked. */
@Composable
fun AppearancePreview(cell: ShotMatrix.Cell) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp)) {
        ArgusAppearanceSection(lookOn = true, onLook = {}, themeMode = if (cell.dark) "dark" else "light", onTheme = {},
            ground = cell.ground.name, onGround = {}, hand = ArgusSettings.Hand.RIGHT, onHand = {}, reduced = false, onReduced = {})
    }
}

/** P1-TM-07 Desk on Routines (Jobs is shot on its own as "jobs"; the Desk page reads the live team for its Jobs count). */
@Composable
fun DeskPreview() {
    remember { DeskState.show(DeskModel.Segment.ROUTINES); true }
    DeskPage(DeskNav(), inboxWaiting = P1Fixtures.INBOX_WAITING, projectsCount = P1Fixtures.PROJECTS, projects = {})
}

/** P1-TM-07 Desk → Jobs on Needs you. */
@Composable
fun JobsPreview() {
    JobsPage(P1Fixtures.jobsState(), "", {}, {}, {}, {}, {}, {}, avatar = ::jobAvatar)
}

private fun jobAvatar(id: String?): (@Composable () -> Unit)? {
    val b = id?.let { P1Fixtures.bots[it] }
    return when {
        id == BotRules.MANAGER_ID -> ({ ManagerOrb(OrbSize.XS.dp, contentDescription = null) })
        b != null -> ({
            BotOrb(b.avatar.face?.let { OrbFace.of(it) } ?: OrbFace.forName(b.name),
                ArgusTokens.botColor(b.avatar.color) ?: ArgusTokens.BOT_COLORS.getValue("silver"), OrbSize.XS.dp)
        })
        else -> null
    }
}

/** P1-MG-01 Settings › Backup: the A-team import previewing the Demo team, theme switch on. */
@Composable
fun BackupImportPreview() {
    val stage = remember { ATeamStage.Preview(P1Fixtures.backupFile(), P1Fixtures.backupPreview()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp)) {
        ATeamBackupSection(stage, onPick = {}, paste = null, onPaste = {}, onRead = {}, useTheme = true, onUseTheme = {}, onImport = {}, onCancel = {})
    }
}
