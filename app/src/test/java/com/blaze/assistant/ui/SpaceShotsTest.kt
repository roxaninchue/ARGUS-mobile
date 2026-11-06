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
import com.blaze.assistant.ui.space.SpaceHomeScreen
import com.blaze.assistant.ui.space.SpaceHomeState
import com.blaze.assistant.ui.space.SpaceOnboarding
import com.blaze.assistant.ui.space.SpaceOnboardingScreen
import com.blaze.assistant.ui.space.SpaceProfile
import com.blaze.assistant.ui.space.SpaceProfileScreen
import com.blaze.assistant.ui.space.SpaceProjectsScreen
import com.blaze.assistant.ui.space.SpaceTabs
import com.blaze.assistant.ui.space.SpaceTags
import com.blaze.assistant.ui.space.SpaceTopBar
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

    private val sample = SpaceHomeState(name = "Gaby", hour = 19, signedIn = true,
        answer = SpaceAnswer.Card("What's on today?", "Two meetings this afternoon and a parcel arriving before six.", false),
        projects = listOf("p1" to "ARGUS mobile", "p2" to "Valera market"), setupLeft = 2, setupPercent = 64)

    /** The shell as ChatScreen draws it: the space view, the top bar, then the tab's content. */
    @androidx.compose.runtime.Composable
    private fun Shell(dark: Boolean, tab: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        CompositionLocalProvider(LocalBlaze provides if (dark) DarkColors else LightColors) {
            SpaceBackdrop {
                Column(Modifier.statusBarsPadding()) {
                    SpaceTopBar(tab = tab, onTab = {}, onLeft = {}, initial = SpaceTabs.initial("Gaby", null), onAvatar = {})
                    content()
                }
            }
        }
    }

    private fun home(dark: Boolean, state: SpaceHomeState = sample): @androidx.compose.runtime.Composable () -> Unit = {
        Shell(dark, SpaceTabs.HOME) {
            SpaceHomeScreen(state, onAsk = {}, onOpenChat = {}, onOpenProject = {}, onOpenProjects = {}, onSignIn = {}, onSetup = {})
        }
    }

    @Test fun homeDark() {
        val b = capture("space-home-dark", home(dark = true))
        rule.onNodeWithText(SpaceGreeting.hello(19, "Gaby")).assertIsDisplayed()
        for (t in listOf(SpaceTags.BACKDROP, SpaceTags.TOP_BAR, SpaceTags.LEFT_BUTTON, SpaceTags.PILL, SpaceTags.AVATAR, SpaceTags.HOME, SpaceTags.HERO))
            rule.onNodeWithTag(t, useUnmergedTree = true).assertExists()
        for (id in listOf(SpaceTabs.HOME, SpaceTabs.CHAT, SpaceTabs.PROJECTS)) rule.onNodeWithTag(SpaceTags.tab(id), useUnmergedTree = true).assertExists()
        // the space view: a dark sky at the top edge
        assertTrue("dark sky (${Integer.toHexString(px(b, 4f, 4f))})", lum(px(b, 4f, 4f)) < 3 * 60)
    }

    @Test fun homeLight() {
        val b = capture("space-home-light", home(dark = false))
        rule.onNodeWithText(SpaceGreeting.hello(19, "Gaby")).assertIsDisplayed()
        assertTrue("light ground (${Integer.toHexString(px(b, 4f, 4f))})", lum(px(b, 4f, 4f)) > 3 * 200)
    }

    @Test fun homeSignedOutWithTheSetupCard() {
        capture("space-home-signed-out", home(dark = true, sample.copy(signedIn = false, answer = null, projects = emptyList())))
        rule.onNodeWithTag(SpaceTags.SIGN_IN, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(SpaceTags.SETUP_CARD, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(SpaceTags.ANSWER, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theGreetingFollowsThePinnedHour() {
        capture("space-home-morning", home(dark = true, sample.copy(hour = 8)))
        rule.onNodeWithText("Good morning, Gaby").assertIsDisplayed()
    }

    @Test fun profileDark() {
        var opened = ""
        capture("space-profile-dark") {
            CompositionLocalProvider(LocalBlaze provides DarkColors) {
                SpaceBackdrop {
                    Column(Modifier.statusBarsPadding()) {
                        SpaceTopBar(tab = "", onTab = {}, onLeft = {}, showLeft = false, initial = "G", onAvatar = {})
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
                    SpaceProfileScreen("Gaby Alarcon", "gaby@example.com", signedIn = false, planLine = "", onPage = {}, onSignOut = {}, onSearch = {})
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

    @Test fun welcome() {
        val b = capture("space-onboarding-welcome") {
            SpaceOnboardingScreen(SpaceOnboarding.Step.WELCOME, signedIn = false, setup = emptyMap(), onNext = {}, onRetry = {}, accountCard = {})
        }
        rule.onNodeWithText(SpaceOnboarding.TAGLINE).assertIsDisplayed()
        rule.onNodeWithTag(SpaceTags.ONBOARDING_START, useUnmergedTree = true).assertExists()
        assertTrue("the welcome is on the space view", lum(px(b, 4f, 4f)) < 3 * 60)
    }

    @Test fun setupMidDownload() {
        val st = mapOf("linux" to SetupPlan.Status(SetupPlan.Phase.DONE), "tools" to SetupPlan.Status(SetupPlan.Phase.RUNNING, 40),
            "skills" to SetupPlan.Status(SetupPlan.Phase.DONE), "brain" to SetupPlan.Status(SetupPlan.Phase.WAITING_WIFI),
            "voice" to SetupPlan.Status(SetupPlan.Phase.RUNNING, 10), "quick" to SetupPlan.Status(SetupPlan.Phase.SKIPPED, note = "Not available on this phone"))
        capture("space-setup") {
            SpaceOnboardingScreen(SpaceOnboarding.Step.SETUP, signedIn = true, setup = st, onNext = {}, onRetry = {}, accountCard = {})
        }
        rule.onNodeWithTag(SpaceTags.SETUP_PROGRESS, useUnmergedTree = true).assertExists()
        for (i in SetupPlan.ITEMS) rule.onNodeWithTag(SpaceTags.setupRow(i.id), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(SpaceTags.SETUP_CONTINUE, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(SpaceOnboarding.setupButton(false)).assertIsDisplayed()
    }

    @Test fun narrowScreenLargeFontPillStillFits() {
        capture("space-topbar-narrow") {
            val d = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(LocalBlaze provides DarkColors,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale = 1.3f)) {
                SpaceBackdrop {
                    Column(Modifier.width(300.dp)) {
                        SpaceTopBar(tab = SpaceTabs.CHAT, onTab = {}, onLeft = {}, initial = "G", onAvatar = {})
                        Spacer(Modifier.height(8.dp))
                        Text("narrow")
                    }
                }
            }
        }
        for (id in listOf(SpaceTabs.HOME, SpaceTabs.CHAT, SpaceTabs.PROJECTS)) rule.onNodeWithTag(SpaceTags.tab(id), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Projects").assertIsDisplayed()
    }
}
