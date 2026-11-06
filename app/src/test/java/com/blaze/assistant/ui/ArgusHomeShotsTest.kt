package com.blaze.assistant.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.ui.argus.ArgusAnswer
import com.blaze.assistant.ui.argus.ArgusFps
import com.blaze.assistant.ui.argus.ArgusGreeting
import com.blaze.assistant.ui.argus.ArgusHomeScreen
import com.blaze.assistant.ui.argus.ArgusHomeTags
import com.blaze.assistant.ui.argus.ArgusScene
import com.blaze.assistant.ui.composer.ComposerState
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
 * 0.52.2 the ARGUS living 3D Home, screenshots (shots job, BLAZE_SHOTS=1) at 360 x 800 dp, 1.5x, in CANVAS mode (Robolectric
 * has no GL; the Canvas orb is the same composition the GL scene draws, and what low-RAM / no-GLES3 phones get): idle,
 * listening, thinking, speaking, the result card over the pedestal, the pull-down Spotlight open, and the reduce-motion
 * still frame. The greeting's hour is pinned so the frames never depend on when CI runs. PNGs go to
 * build/shots/ArgusHomeShotsTest (or blaze.shotsDir).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-hdpi", application = android.app.Application::class)
class ArgusHomeShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @org.junit.Before fun initSettings() { try { com.blaze.assistant.agent.Settings.init(androidx.test.core.app.ApplicationProvider.getApplicationContext()) } catch (_: Throwable) { } }

    /** A plain avatar (the real one reads the account). */
    private val avatar: @androidx.compose.runtime.Composable (() -> Unit) -> Unit = { click ->
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF1286FF)).clickable { click() })
    }

    private fun home(state: ArgusScene.State = ArgusScene.State.IDLE, level: Float = 0f, composer: ComposerState = ComposerState(),
                     answer: ArgusAnswer.Card? = null, mode: ArgusFps.Mode = ArgusFps.Mode.CANVAS, hour: Int = 19,
                     onOpenChat: () -> Unit = {}): @androidx.compose.runtime.Composable () -> Unit = {
        ArgusHomeScreen(state, level, "Gaby", composer, onSend = {}, onMic = {}, onOpenChat = onOpenChat, onProfile = {},
            answer = answer, avatar = avatar, forceMode = mode, hour = hour)
    }

    private fun capture(name: String, content: @androidx.compose.runtime.Composable () -> Unit): Bitmap {
        rule.mainClock.autoAdvance = false
        rule.setContent(content)
        repeat(3) { rule.mainClock.advanceTimeBy(100); rule.waitForIdle(); Thread.sleep(250) }
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        return save(name)
    }

    private fun save(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "ArgusHomeShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    // pixel helpers (positions in dp of the 360 dp wide frame; the orb sits at ArgusHomeTokens.ORB_AT of the height)
    private fun px(b: Bitmap, xDp: Float, yDp: Float): Int { val d = b.width / 360f; return b.getPixel((xDp * d).toInt().coerceIn(0, b.width - 1), (yDp * d).toInt().coerceIn(0, b.height - 1)) }
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun bl(p: Int) = p and 0xFF
    private fun lum(p: Int) = r(p) + g(p) + bl(p)
    private fun heightDp(b: Bitmap) = b.height / (b.width / 360f)
    /** Under the orb, in the beam between the orb and the pedestal (r = 0.205 w, so 1.6 r below the centre). */
    private fun beam(b: Bitmap) = px(b, 180f, heightDp(b) * 0.40f + 0.205f * 360f * 1.6f)

    /** The scene: an obsidian ground with the blue light of the orb; never a blank or white frame. */
    private fun assertScene(b: Bitmap, label: String) {
        val edge = px(b, 3f, heightDp(b) * 0.30f)
        assertTrue("$label: the ground at the left edge is obsidian (${Integer.toHexString(edge)})", lum(edge) < 3 * 45)
        val beam = beam(b)
        assertTrue("$label: blue light under the orb (${Integer.toHexString(beam)})", bl(beam) > 35 && bl(beam) > r(beam) + 25)
    }

    @Test fun idle() {
        val b = capture("argus-home-idle", home())
        assertScene(b, "idle")
        rule.onNodeWithText(ArgusGreeting.hello(19, "Gaby")).assertIsDisplayed()
        rule.onNodeWithText(ArgusGreeting.ask(ArgusScene.State.IDLE)).assertIsDisplayed()
        for (t in listOf(ArgusHomeTags.ROOT, ArgusHomeTags.SCENE, ArgusHomeTags.MARK, ArgusHomeTags.AVATAR, ArgusHomeTags.NOTCH,
                ArgusHomeTags.GREETING, ArgusHomeTags.PROMPT, ArgusHomeTags.MIC, ArgusHomeTags.SEND, ArgusHomeTags.PROMPT_FIELD))
            rule.onNodeWithTag(t, useUnmergedTree = true).assertExists()
        // Spotlight and the answer card are not there until asked for
        rule.onNodeWithTag(ArgusHomeTags.SPOTLIGHT).assertDoesNotExist()
        rule.onNodeWithTag(ArgusHomeTags.ANSWER).assertDoesNotExist()
    }

    @Test fun theGreetingFollowsThePinnedHour() {
        capture("argus-home-morning", home(hour = 8))
        rule.onNodeWithText("Good morning, Gaby.").assertIsDisplayed()
    }

    @Test fun listeningThinkingSpeakingGlowMoreThanIdle() {
        // one composition (the rule's setContent runs once per test); the state is swapped in, as the chat drives it
        swap.value = home()
        val idle = capture("argus-home-idle-ref") { swap.value() }
        val idleBlue = bl(beam(idle))
        for ((s, lvl) in listOf(ArgusScene.State.LISTENING to 0.7f, ArgusScene.State.THINKING to 0f, ArgusScene.State.SPEAKING to 0f)) {
            val b = renderAgain("argus-home-${s.name.lowercase()}", home(s, lvl))
            assertScene(b, s.name)
            assertTrue("${s.name} glows at least as much as idle (${bl(beam(b))} vs $idleBlue)", bl(beam(b)) >= idleBlue - 2)
            rule.onNodeWithText(ArgusGreeting.ask(s)).assertIsDisplayed()
        }
    }

    /** Swaps what the one composition shows, lets the orb ease into the new state (the look eases, tau 0.35 s), saves the frame. */
    private fun renderAgain(name: String, content: @androidx.compose.runtime.Composable () -> Unit): Bitmap {
        rule.runOnUiThread { swap.value = content }
        repeat(8) { rule.mainClock.advanceTimeBy(100); rule.waitForIdle(); Thread.sleep(100) }
        return save(name)
    }
    private val swap = androidx.compose.runtime.mutableStateOf<@androidx.compose.runtime.Composable () -> Unit>({})

    @Test fun answerCardOverThePedestal() {
        var opened = 0
        capture("argus-home-answer", home(ArgusScene.State.SPEAKING,
            answer = ArgusAnswer.Card("What's the latest tech news?", "Here are the top headlines for you: a faster way to compile, a self-driving excavator, and a new handheld.", false),
            onOpenChat = { opened++ }))
        rule.onNodeWithTag(ArgusHomeTags.ANSWER).assertIsDisplayed()
        rule.onNodeWithText("Open in chat \u203a").assertIsDisplayed()
        rule.onNodeWithTag(ArgusHomeTags.ANSWER).performClick()
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        assertEquals("the card opens the chat", 1, opened)
    }

    @Test fun answerCardWhileWorking() {
        capture("argus-home-answer-working", home(ArgusScene.State.THINKING, answer = ArgusAnswer.Card("Plan my day", "", true)))
        rule.onNodeWithTag(ArgusHomeTags.ANSWER).assertIsDisplayed()
        // "Working on it…" shows in the greeting and on the card until the reply comes
        assertTrue(rule.onAllNodesWithText(ArgusGreeting.ask(ArgusScene.State.THINKING)).fetchSemanticsNodes().size >= 2)
    }

    @Test fun spotlightPulledDown() {
        val c = ComposerState().apply { openSpotlight() }
        capture("argus-home-spotlight", home(composer = c))
        rule.onNodeWithTag(ArgusHomeTags.SPOTLIGHT).assertIsDisplayed()
        rule.onNodeWithTag(ArgusHomeTags.SPOTLIGHT_FIELD, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(ArgusHomeTags.SPOTLIGHT_HANDLE, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("SUGGESTED").assertExists()
        rule.onNodeWithTag(ArgusHomeTags.SCRIM).assertExists()
        // typing groups the results
        rule.runOnUiThread { c.query = "set" }
        repeat(4) { rule.mainClock.advanceTimeBy(100); rule.waitForIdle(); Thread.sleep(200) }
        save("argus-home-spotlight-query")
        rule.onNodeWithText("SETTINGS").assertExists()
        // the handle closes it
        rule.onNodeWithTag(ArgusHomeTags.SPOTLIGHT_HANDLE, useUnmergedTree = true).performClick()
        repeat(6) { rule.mainClock.advanceTimeBy(100); rule.waitForIdle() }
        assertTrue(!c.spotlightOpen)
    }

    @Test fun tappingTheNotchOpensSpotlight() {
        val c = ComposerState()
        capture("argus-home-before-notch", home(composer = c))
        rule.onNodeWithTag(ArgusHomeTags.NOTCH).performClick()
        repeat(6) { rule.mainClock.advanceTimeBy(100); rule.waitForIdle(); Thread.sleep(100) }
        assertTrue("the notch opens Spotlight", c.spotlightOpen)
        save("argus-home-after-notch")
        rule.onNodeWithTag(ArgusHomeTags.SPOTLIGHT).assertExists()
    }

    @Test fun reduceMotionDrawsOneStillFrame() {
        val a = capture("argus-home-static", home(mode = ArgusFps.Mode.STATIC))
        assertScene(a, "static")
        repeat(10) { rule.mainClock.advanceTimeBy(100) }
        rule.waitForIdle(); Thread.sleep(200)
        val b = save("argus-home-static-later")
        // nothing moves: the scene around the orb is the same a second later
        for (y in listOf(0.30f, 0.40f, 0.50f)) for (x in listOf(60f, 180f, 300f))
            assertEquals("still at ($x, ${y}h)", px(a, x, heightDp(a) * y), px(b, x, heightDp(b) * y))
    }
}
