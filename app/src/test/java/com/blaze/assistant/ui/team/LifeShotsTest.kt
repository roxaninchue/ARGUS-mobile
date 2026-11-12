package com.blaze.assistant.ui.team

import android.graphics.Bitmap
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.agent.ScheduleDay
import com.blaze.assistant.ui.argus.ArgusGround
import com.blaze.assistant.ui.argus.ArgusTheme
import com.blaze.assistant.ui.space.SpaceAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * P1-SH-05 Life mode on Robolectric. Shots (BLAZE_SHOTS=1): a busy Thursday in light and dark, an empty day signed out
 * with setup running, to build/shots/LifeShotsTest. Always run: a domain tile asks to set up the life team for that
 * domain, the Today strip opens Schedule, a suggested ask is sent, the answer opens Chat, and tiles keep 48 dp targets.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-hdpi", application = android.app.Application::class)
class LifeShotsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val thu = LocalDate.of(2026, 11, 12)
    private val items = listOf(
        ScheduleDay.Item("Mum's birthday", "event", null),
        ScheduleDay.Item("Gym", "reminder", LocalTime.of(7, 0)),
        ScheduleDay.Item("Call Mum", "event", LocalTime.of(13, 30), LocalTime.of(14, 0)),
        ScheduleDay.Item("Pay ZESCO bill", "reminder", LocalTime.of(18, 0)),
    )
    private val busy = LifeModel.state(thu, LocalTime.of(11, 0), items,
        projects = listOf("p1" to "Cape Town trip", "p2" to "ARGUS overhaul"),
        weather = LifeModel.Weather("24\u00b0C", "Clear \u00b7 Lusaka"),
        answer = SpaceAnswer.Card("What's on today?", "Three things: the gym at 7, a call with Mum at 13:30 and the ZESCO bill at 18:00.", false))
    private val empty = LifeModel.state(thu, LocalTime.of(11, 0), emptyList(), signedIn = false, setupLeft = 2, setupPercent = 60)

    private fun show(state: LifeModel.LifeState, dark: Boolean, nav: LifeNav = LifeNav()) {
        rule.setContent { ArgusTheme(dark = dark) { ArgusGround { LifeMode(state, nav) } } }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
    }

    private fun save(name: String) {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "LifeShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shotsOnly() = assumeTrue(System.getProperty("blaze.shots") == "1")

    @Test fun busyLight() { shotsOnly(); show(busy, false); save("busy-light") }
    @Test fun busyDark() { shotsOnly(); show(busy, true); save("busy-dark") }
    @Test fun emptyDark() { shotsOnly(); show(empty, true); save("empty-dark") }
    @Test fun emptyLight() { shotsOnly(); show(empty, false); save("empty-light") }

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag(LifeTags.PAGE).performScrollToNode(hasTestTag(tag))
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
    }

    @Test fun dayLineIsFirst() {
        show(busy, true)
        rule.onNodeWithText("Thursday \u2014 three things today, two still ahead.").assertExists()
    }

    @Test fun domainTileAsksToSetUpThatDomain() {
        var asked: LifeModel.DomainTile? = null
        var any = 0
        show(busy, true, LifeNav(onSetUpLife = { d -> if (d == null) any++ else asked = d }))
        scrollTo(LifeTags.domain("money"))
        rule.onNodeWithTag(LifeTags.domain("money")).performClick()
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        assertEquals("money", asked?.key)
        assertEquals("Set up my life team, starting with Money", LifeModel.setUpAsk(asked))
        val b = rule.onNodeWithTag(LifeTags.domain("money")).getUnclippedBoundsInRoot()
        assertTrue("tile is a 48 dp target", b.bottom - b.top >= 48.dp)
        assertEquals(0, any)
    }

    @Test fun todayStripOpensSchedule() {
        var opened = 0
        show(busy, true, LifeNav(onOpenSchedule = { opened++ }))
        rule.onNodeWithText("Schedule \u203a").performClick()
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        assertEquals(1, opened)
    }

    @Test fun askAndAnswerGoToChat() {
        val sent = ArrayList<String>()
        var chat = 0
        show(busy, true, LifeNav(onAsk = { sent += it }, onOpenChat = { chat++ }))
        rule.onNodeWithTag(LifeTags.PAGE).performScrollToNode(hasText(busy.asks.first()))
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        rule.onNodeWithText(busy.asks.first()).performClick()
        rule.onNodeWithText(busy.answer!!.text).performClick()
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        assertEquals(listOf(busy.asks.first()), sent)
        assertEquals(1, chat)
    }

    @Test fun signedOutShowsSignInAndSetup() {
        var signIn = 0
        show(empty, true, LifeNav(onSignIn = { signIn++ }))
        rule.onNodeWithTag(LifeTags.SETUP).assertExists()
        rule.onNodeWithText("Sign in").performClick()
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        assertEquals(1, signIn)
        rule.onNodeWithText("Thursday \u2014 nothing on today.").assertExists()
    }
}
