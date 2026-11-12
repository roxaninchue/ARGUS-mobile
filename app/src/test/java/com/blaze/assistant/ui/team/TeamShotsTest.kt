package com.blaze.assistant.ui.team

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.team.Avatar
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.BotThreads
import com.blaze.assistant.ui.argus.ArgusGround
import com.blaze.assistant.ui.argus.ArgusTheme
import com.blaze.assistant.ui.argus.kit.KitA11y
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P1-TM-03 Team mode on Robolectric. Shots (BLAZE_SHOTS=1): the team in light and dark, and the empty "Build your team"
 * state, to build/shots/TeamShotsTest. Always run: the long-press quick sheet offers Delete on a bot and not on the
 * Manager, and a pin toggled from the sheet reaches [onPin].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-hdpi", application = android.app.Application::class)
class TeamShotsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val zone = ZoneId.of("Africa/Lusaka")
    private val now = ZonedDateTime.of(2026, 11, 12, 11, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun bot(id: String, name: String, label: String, color: String, pinned: Boolean = false, kind: BotKind = BotKind.CUSTOM) =
        Bot(id = id, kind = kind, name = name, label = label, avatar = Avatar("orb", color), soulPath = "", toolGroups = emptySet(), pinned = pinned, createdAt = 0, u = 0)

    private val roster = listOf(
        BotRules.manager(0),
        bot("nova", "Nova", "Research lead", "#22c55e", pinned = true),
        bot("atlas", "Atlas", "Sales ops", "#f59e0b", pinned = true),
        bot("echo", "Echo", "Support", "#ef4444"),
        bot("tempo", "Tempo", "Time & calendar", "#7C5CFF", kind = BotKind.DOMAIN),
    )
    private val threads = listOf(
        BotThreads.Thread("manager", "Argus", 0, null, 1, "Morning. Three things need you today.", now - 5 * 60_000, true, false, 0),
        BotThreads.Thread("nova", "Nova", 0, null, 2, "Competitor pricing scan is ready", now - 60_000, true, true, 0),
        BotThreads.Thread("echo", "Echo", 0, null, 0, "All clear", now - 26 * 3_600_000, false, false, 0),
    )
    private val jobs = JobCounts(today = 4, overdue = 1, unassigned = 2, open = 8, overdueByBot = mapOf("atlas" to (1 to "Send deck")))

    private fun show(dark: Boolean, roster: List<Bot> = this.roster, onPin: (String, Boolean) -> Unit = { _, _ -> }) {
        val s = TeamModel.state("Finn's team", roster, threads, jobs, now, zone)
        rule.setContent {
            ArgusTheme(dark = dark) { ArgusGround { TeamMode(s, roster.associateBy { it.id }, TeamNav(), onPin, {}, {}) } }
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
    }

    private fun save(name: String) {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "TeamShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shotsOnly() = assumeTrue(System.getProperty("blaze.shots") == "1")

    @Test fun teamLight() { shotsOnly(); show(false); save("team-light") }
    @Test fun teamDark() { shotsOnly(); show(true); save("team-dark") }
    @Test fun emptyDark() { shotsOnly(); show(true, roster = listOf(BotRules.manager(0))); save("empty-dark") }
    @Test fun emptyLight() { shotsOnly(); show(false, roster = listOf(BotRules.manager(0))); save("empty-light") }

    private fun rowDesc(id: String): String {
        val s = TeamModel.state("Finn's team", roster, threads, jobs, now, zone)
        return KitA11y.botRow(if (id == BotRules.MANAGER_ID) s.manager!! else s.rows.first { it.id == id })
    }

    @Test fun managerSheetHasNoDelete() {
        show(true)
        rule.onNodeWithContentDescription(rowDesc(BotRules.MANAGER_ID)).performTouchInput { longClick() }
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithText("Edit soul").assertExists()
        rule.onNodeWithText("Delete").assertDoesNotExist()
        rule.onNodeWithText("Pin").assertDoesNotExist()
    }

    @Test fun botSheetHasDeleteAndPins() {
        var pinned: Pair<String, Boolean>? = null
        show(true, onPin = { id, on -> pinned = id to on })
        rule.onNodeWithContentDescription(rowDesc("echo")).performTouchInput { longClick() }
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithText("Delete").assertExists()
        rule.onNodeWithText("Pin").performClick()
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        assertEquals("echo" to true, pinned)
    }
}
