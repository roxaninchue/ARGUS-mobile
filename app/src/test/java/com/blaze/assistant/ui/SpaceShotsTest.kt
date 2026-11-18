package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.ChatMeta
import com.blaze.assistant.agent.Project
import com.blaze.assistant.setup.SetupPlan
import com.blaze.assistant.ui.space.SpaceAnswer
import com.blaze.assistant.ui.space.SpaceBackdrop
import com.blaze.assistant.ui.space.SpaceGreeting
import com.blaze.assistant.ui.space.SpaceOnboarding
import com.blaze.assistant.ui.space.SpaceOnboardingScreen
import com.blaze.assistant.ui.space.SpaceProfile
import com.blaze.assistant.ui.space.SpaceProfileScreen
import com.blaze.assistant.ui.space.SpaceProjectsScreen
import com.blaze.assistant.ui.space.SpaceSetupScreen
import com.blaze.assistant.ui.space.SpaceTabs
import com.blaze.assistant.ui.space.SpaceTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.52.3 "ARGUS x Hark" screenshots (shots job, BLAZE_SHOTS=1) at 360 x 800 dp, 1.5x: the Home in dark (the space view)
 * and light, the profile page (the root of Settings), Projects, the welcome and the one setup screen mid-download, and the
 * top bar's pill on a narrow screen with a large font. The hour is pinned so the frames never depend on when CI runs.
 * PNGs go to build/shots/SpaceShotsTest (or blaze.shotsDir). Replaces ArgusHomeShotsTest (the 3D Home is gone).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-hdpi", application = android.app.Application::class)
class SpaceShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @org.junit.Before fun initSettings() { try { com.blaze.assistant.agent.Settings.init(androidx.test.core.app.ApplicationProvider.getApplicationContext()) } catch (_: Throwable) { } }

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit): Bitmap {
        rule.mainClock.autoAdvance = false
        rule.setContent(content)
        // the space image decodes off the main thread: give it a moment, then draw
        repeat(4) { rule.mainClock.advanceTimeBy(100); rule.waitForIdle(); Thread.sleep(250) }
        rule.mainClock.advanceTimeBy(400); rule.waitForIdle()
        return save(name)
    }

    private fun save(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "SpaceShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun px(b: Bitmap, xDp: Float, yDp: Float): Int { val d = b.width / 360f; return b.getPixel((xDp * d).toInt().coerceIn(0, b.width - 1), (yDp * d).toInt().coerceIn(0, b.height - 1)) }
    private fun lum(p: Int) = ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)

    /** The shell as ChatScreen draws it: the space view, the top bar, then the tab's content. */
    @androidx.compose.runtime.Composable
    private fun Shell(dark: Boolean, tab: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        CompositionLocalProvider(LocalBlaze provides if (dark) DarkColors else LightColors) {
            SpaceBackdrop {
                Column(Modifier.statusBarsPadding()) { content() }
            }
        }
    }

    // ARGUS-only (16 Nov 2026): the 0.52 Home and top bar shots left with those screens (SpaceHomeScreen, SpaceTopBar).

    @Test fun profileDark() {
        var opened = ""
        capture("space-profile-dark") {
            CompositionLocalProvider(LocalBlaze provides DarkColors) {
                SpaceBackdrop {
                    Column(Modifier.statusBarsPadding()) {
                        SpaceProfileScreen("Gaby Alarcon", "gaby@example.com", signedIn = true, planLine = "Pro plan",
                            onPage = { opened = it }, onSignOut = {}, onSearch = {}, version = "0.52.3")
                    }
                }
            }
        }
        for (r in SpaceProfile.GROUPS.flatten()) rule.onNodeWithTag(SpaceTags.profileRow(r.id), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Gaby Alarcon").assertIsDisplayed()
        rule.onNodeWithTag(SpaceTags.PROFILE_SEARCH, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(SpaceTags.LEFT_BUTTON, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag(SpaceTags.profileRow("profile")).performClick()
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        assertEquals("Profile opens the account page", "account", opened)
    }

    @Test fun profileLight() {
        capture("space-profile-light") {
            CompositionLocalProvider(LocalBlaze provides LightColors) {
                SpaceBackdrop {
                    // the caller (SettingsSheet) passes SpaceProfile.subtitle(...) as the line under the name, as here
                    SpaceProfileScreen("Gaby Alarcon", SpaceProfile.subtitle("gaby@example.com", false), signedIn = false, planLine = "", onPage = {}, onSignOut = {}, onSearch = {})
                }
            }
        }
        rule.onNodeWithText(SpaceProfile.subtitle("gaby@example.com", false)).assertIsDisplayed()
    }

    @Test fun projects() {
        val chats = listOf(ChatMeta("c1", "Store copy", 0L, false, ""), ChatMeta("c2", "Price list", 0L, false, ""))
        capture("space-projects", {
            Shell(dark = true, tab = SpaceTabs.PROJECTS) {
                SpaceProjectsScreen(listOf(Project("p1", "ARGUS mobile", chats = listOf("c1")), Project("p2", "Valera market", chats = listOf("c2"))),
                    chats, busy = setOf("c1"), needsYou = emptySet(), openId = "",
                    onOpen = {}, onBack = {}, onCreate = {}, onChat = {}, onNewChat = {}, onInstructions = { _, _ -> }, onDelete = {})
            }
        })
        rule.onNodeWithTag(SpaceTags.PROJECTS, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(SpaceTags.projectRow("p1"), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Working").assertIsDisplayed()
    }

    // owner 2026-11-18: the language step is one pill on the space view with a round continue button apart from it
    @Test fun language() {
        val b = capture("space-onboarding-language") {
            SpaceOnboardingScreen(SpaceOnboarding.Step.LANGUAGE, signedIn = false, onNext = {}, accountCard = {})
        }
        rule.onNodeWithTag(SpaceTags.LANGUAGE_PILL, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(SpaceTags.LANGUAGE_NEXT, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(SpaceTags.LANGUAGE_LIST, useUnmergedTree = true).assertDoesNotExist()
        assertTrue("the language step is on the space view", lum(px(b, 4f, 4f)) < 3 * 60)
    }

    @Test fun setupMidDownload() {
        val st = mapOf("linux" to SetupPlan.Status(SetupPlan.Phase.DONE), "tools" to SetupPlan.Status(SetupPlan.Phase.RUNNING, 40),
            "skills" to SetupPlan.Status(SetupPlan.Phase.DONE), "brain" to SetupPlan.Status(SetupPlan.Phase.WAITING_WIFI),
            "voice" to SetupPlan.Status(SetupPlan.Phase.RUNNING, 10), "quick" to SetupPlan.Status(SetupPlan.Phase.SKIPPED, note = "Not available on this phone"))
        capture("space-setup") {
            SpaceBackdrop(forceDark = true) { SpaceSetupScreen(st, onContinue = {}, onRetry = {}) }
        }
        rule.onNodeWithTag(SpaceTags.SETUP_PROGRESS, useUnmergedTree = true).assertExists()
        for (i in SetupPlan.ITEMS) rule.onNodeWithTag(SpaceTags.setupRow(i.id), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(SpaceTags.SETUP_CONTINUE, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(SpaceOnboarding.setupButton(false)).assertIsDisplayed()
    }
}
