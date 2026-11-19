package com.blaze.assistant.ui.team.cards

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.policy.Card
import com.blaze.assistant.policy.CardAnswer
import com.blaze.assistant.policy.Cards
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.ui.argus.ArgusGround
import com.blaze.assistant.ui.argus.ArgusTheme
import com.blaze.assistant.ui.team.thread.ThreadModel.CardModel
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * P2-SF-02 cards on Robolectric. Shots (BLAZE_SHOTS=1): every live card kind and the closed thread rows, light and dark,
 * to build/shots/CardsShotsTest. Always run: each card shows its words and its taps reach [CardAnswer]s (Send, Don't
 * allow, a choice, Connect → I've connected it).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h1600dp-hdpi", application = android.app.Application::class)
class CardsShotsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val approval = Card("ap-1", Cards.Kind.APPROVAL, "job-1", "ledger", "Ledger", "Ledger wants to spend money", "buy: Proflight PF701, \$356, card ··42",
        tool = "buy", cls = ActionClass.SPEND, note = "Requested after reading a web page.")
    private val always = Card("ap-2", Cards.Kind.APPROVAL, "job-2", "tempo", "Tempo", "Tempo wants to change something in your accounts", "gcal update: Dentist → Tue 10:00",
        tool = "gcal_update", cls = ActionClass.WRITE_REMOTE, always = true)
    private val draft = Card("ap-3", Cards.Kind.DRAFT, "c", "inbox", "Inbox", "Inbox wants to send a message", "", tool = "gmail_send", cls = ActionClass.SEND,
        draft = Cards.Draft("gmail_send", listOf("nova@hitch.io", "cc: ops@hitch.io"), "Today", "Hi Nova,\n\nRunning 10 minutes late, sorry!\n\nFinn"))
    private val question = Card("ap-4", Cards.Kind.QUESTION, "c", "atlas", "Atlas", "Atlas asks", "Which day suits you?",
        question = Cards.Question("Which day suits you?", listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat")))
    private val connect = Card("ap-5", Cards.Kind.CONNECT, "c", "atlas", "Atlas", "Atlas needs Gmail", "to read the booking mail",
        connect = Cards.Connect("Gmail", "to read the booking mail"))

    private val got = mutableListOf<CardAnswer>()
    private var connected = ""

    private fun show(dark: Boolean, cards: List<Card> = listOf(approval, always, draft, question, connect), rows: List<CardModel.Decision> = emptyList()) {
        rule.setContent {
            ArgusTheme(dark = dark) { ArgusGround { Column {
                for (c in cards) CardView(c, { got += it }, onConnect = { connected = it })
                for (r in rows) DecisionThreadCard(r, null, { _, _ -> })
            } } }
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
    }

    private val closed = listOf(
        CardModel.Decision("ap-1", "approval", "Ledger wants to spend money", "buy: PF701", null, null, "approved"),
        CardModel.Decision("ap-3", "draft", "Inbox wants to send a message", "Running late", "nova@hitch.io", null, "skipped"),
        CardModel.Decision("ap-4", "question", "Atlas asks", "Which day?", null, null, "expired"),
        CardModel.Decision("ap-5", "connect", "Atlas needs Gmail", null, null, "Gmail", "denied"),
    )

    private fun save(name: String) {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "CardsShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shotsOnly() = assumeTrue(System.getProperty("blaze.shots") == "1")

    @Test fun liveLight() { shotsOnly(); show(false); save("live-light") }
    @Test fun liveDark() { shotsOnly(); show(true); save("live-dark") }
    @Test fun closedLight() { shotsOnly(); show(false, emptyList(), closed); save("closed-light") }
    @Test fun closedDark() { shotsOnly(); show(true, emptyList(), closed); save("closed-dark") }

    @Test fun approvalTaps() {
        show(false, listOf(approval))
        rule.onNodeWithText("Requested after reading a web page.").assertExists()
        rule.onNodeWithText("Always allow").assertDoesNotExist()
        rule.onNodeWithText("Don't allow").performClick()
        assertEquals(listOf<CardAnswer>(CardAnswer.Deny), got)
    }

    @Test fun draftShowsRecipientsAndSends() {
        show(false, listOf(draft))
        rule.onNodeWithText("To: nova@hitch.io, cc: ops@hitch.io").assertExists()
        rule.onNodeWithText("Running 10 minutes late", substring = true).assertExists()
        rule.onNodeWithText("Send").performClick()
        assertEquals(listOf<CardAnswer>(CardAnswer.Send), got)
    }

    @Test fun questionChoiceAndConnect() {
        show(false, listOf(question, connect))
        rule.onNodeWithText("Thu").performClick()
        rule.onNodeWithText("Connect Gmail").performClick()
        rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        assertEquals("Gmail", connected)
        rule.onNodeWithText("I've connected it").performClick()
        assertEquals(listOf(CardAnswer.Reply("Thu"), CardAnswer.Connected), got)
    }

    @Test fun closedRowsSayHowTheyEnded() {
        show(false, emptyList(), closed)
        for (l in listOf("Approved", "Skipped", "Expired", "Not connected")) rule.onNodeWithText(l).assertExists()
    }
}
