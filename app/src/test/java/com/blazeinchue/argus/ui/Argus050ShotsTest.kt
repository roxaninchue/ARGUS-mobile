package com.blazeinchue.argus.ui


import com.blazeinchue.argus.BrandName
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.50.0 screenshots (ARGUS_SHOTS=1, x86 / qemu): the Teamily-style composer card (expand arrows, model pill, Skills, voice
 * chat, / and the big mic / send / stop) in both themes, the "/" command list over it, and the edge particles.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h780dp-xhdpi", application = android.app.Application::class)
class Argus050ShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("argus.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @org.junit.Before fun initSettings() { try { com.blazeinchue.argus.agent.Settings.init(androidx.test.core.app.ApplicationProvider.getApplicationContext()) } catch (_: Throwable) { } }

    private fun capture(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("argus.shotsDir") ?: "build/shots", "Argus050ShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }
    private fun distinct(b: Bitmap): Int { val s = HashSet<Int>(); var y = 0
        while (y < b.height) { var x = 0; while (x < b.width) { s += b.getPixel(x, y); x += 7 }; y += 7 }; return s.size }

    @Composable
    private fun Card(text: String, busy: Boolean = false, expanded: Boolean = false) {
        Composer(TextFieldValue(text), {}, busy = busy, canSend = text.isNotEmpty(), plusOpen = false, chip = "", agents = 0,
            listening = false, speaking = false, onPlus = {}, onMic = {}, onSend = {}, onStop = {}, hints = listOf("Message ${BrandName.NAME}"),
            animateHints = false, onExpand = {}, expanded = expanded, onSkills = {}, onCall = {}, onSlash = {})
    }

    private fun frame(name: String, dark: Boolean, expect: String, body: @Composable () -> Unit) {
        MotionTokens.setEnabledForTests(false)
        rule.setContent {
            CompositionLocalProvider(LocalArgus provides (if (dark) DarkColors else LightColors)) {
                val c = LocalArgus.current
                Column(Modifier.fillMaxSize().background(c.paper)) {
                    Box(Modifier.weight(1f).fillMaxWidth())
                    body()
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(expect, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(400)
        assertTrue("$name frame is blank", distinct(capture(name)) > 12)
        MotionTokens.setEnabledForTests(true)
    }

    @Test fun composerCardDark() {
        frame("composer-card-dark", dark = true, expect = "Message ${BrandName.NAME}") { Card("") }
        // 0.52.3: no model / brain picker in the composer any more (ARGUS picks automatically)
        assertTrue(rule.onAllNodes(hasContentDescription("Model", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasText("Auto")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasContentDescription("Attach and tools")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasContentDescription("Maximise the chat")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasContentDescription("Talk")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasContentDescription("Skills")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasContentDescription("Commands")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun composerCardLightTyping() {
        frame("composer-card-light-typing", dark = false, expect = "Plan my Saturday") { Card("Plan my Saturday") }
        assertTrue(rule.onAllNodes(hasContentDescription("Send")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun composerCardWorkingExpanded() {
        frame("composer-card-working-expanded", dark = true, expect = "Message ${BrandName.NAME}") { Card("", busy = true, expanded = true) }
        assertTrue(rule.onAllNodes(hasContentDescription("Stop")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasContentDescription("Restore the chat")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun slashMenuFiltered() {
        frame("slash-menu", dark = true, expect = "/translate") {
            SuggestionList(CommandRunner.suggest("/tr")) {}
            Card("/tr")
        }
    }

    @Test fun slashMenuAll() {
        frame("slash-menu-all", dark = true, expect = "/clear") {
            SuggestionList(CommandRunner.suggest("/")) {}
            Card("/")
        }
    }

    @Test fun edgeParticles() {
        MotionTokens.setEnabledForTests(false)
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF101114))) {
                androidx.compose.material3.Text("Some app", color = Color.White)
                ScreenWorkGlow(visible = true)
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Some app")).fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(600)
        assertTrue("edge particles frame is blank", distinct(capture("edge-particles")) > 12)
        MotionTokens.setEnabledForTests(true)
    }
}
