package com.blaze.assistant.ui.team.thread

import android.graphics.Bitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.team.Row
import com.blaze.assistant.team.RowBodies
import com.blaze.assistant.team.RowKind
import com.blaze.assistant.team.TeamJson
import com.blaze.assistant.ui.argus.ArgusGround
import com.blaze.assistant.ui.argus.ArgusTheme
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
 * P1-SH-03 thread components on Robolectric. Shots (BLAZE_SHOTS=1): a finished turn and a live one, light and dark, to
 * build/shots/ThreadShotsTest. Always run: the ActivityLine is a polite live region; no raw tool name anywhere in the
 * semantics tree or the text; a ToolCard's Undo reaches [ThreadActions.undo].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-hdpi", application = android.app.Application::class)
class ThreadShotsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var seq = 0L
    private fun row(kind: String, body: String, at: Long) = Row("atlas", 0, ++seq, seq, kind, body, at)
    private fun tool(id: String, name: String, state: String, at: Long, undoable: Boolean = false) =
        row(RowKind.TOOL_CALL, TeamJson.write(linkedMapOf("callId" to id, "name" to name, "state" to state, "undoable" to (if (undoable) true else null))), at)
    private val names = listOf("web_search", "fetch_page", "save_file", "bank_balance")

    private fun rows(live: Boolean): List<Row> {
        seq = 0
        val base = listOf(
            row(RowKind.CARD, RowBodies.card("jobs", mapOf("jobs" to listOf(mapOf("id" to "j1", "title" to "Find flights LUN\u2192CPT", "due" to "Fri"),
                mapOf("id" to "j2", "title" to "Hotel near V&A", "due" to "Fri")))), 0),
            row(RowKind.MESSAGE, RowBodies.message("user", "Find flights for Jan 5, window seat, under \$400"), 0),
            tool("c1", "web_search", "done", 1_000), tool("c2", "fetch_page", "done", 30_000), tool("c3", "save_file", "done", 60_000, undoable = true),
            row(RowKind.MESSAGE, RowBodies.message("atlas", "Found 3 options. The best is Proflight 07:10, \$356, window 12A."), 131_000),
            row(RowKind.CARD, RowBodies.card("handoff", mapOf("from" to "atlas", "fromName" to "Atlas", "to" to "ledger", "toName" to "Ledger",
                "text" to "Is \$356 within the travel budget?", "reply" to "Yes, \$500 left")), 132_000),
        )
        return if (!live) base else base + listOf(row(RowKind.MESSAGE, RowBodies.message("user", "And a hotel?"), 140_000),
            tool("c4", "web_search", "done", 141_000), tool("c5", "bank_balance", "running", 150_000))
    }

    private fun show(dark: Boolean, live: Boolean, actions: ThreadActions = ThreadActions()) {
        val items = ThreadModel.items(rows(live), "Atlas", running = live)
        rule.setContent { ArgusTheme(dark = dark) { ArgusGround { ThreadList(items, actions) } } }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
    }

    private fun save(name: String) {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "ThreadShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shotsOnly() = assumeTrue(System.getProperty("blaze.shots") == "1")

    @Test fun doneLight() { shotsOnly(); show(false, live = false); save("done-light") }
    @Test fun doneDark() { shotsOnly(); show(true, live = false); save("done-dark") }
    @Test fun liveLight() { shotsOnly(); show(false, live = true); save("live-light") }
    @Test fun liveDark() { shotsOnly(); show(true, live = true); save("live-dark") }

    @Test fun activityLineIsAPoliteLiveRegion() {
        show(false, live = true)
        val polite = SemanticsMatcher("polite live region") { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite }
        assertEquals(1, rule.onAllNodes(polite).fetchSemanticsNodes().size)
    }

    @Test fun noRawToolNameIsEverShown() {
        show(false, live = true)
        val all = rule.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        val words = all.flatMap { n ->
            (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) +
                (n.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList()) +
                listOfNotNull(n.config.getOrNull(SemanticsProperties.StateDescription))
        }
        assertTrue(words.isNotEmpty())
        for (n in names) assertTrue("'$n' shown", words.none { it.contains(n) })
    }

    @Test fun undoReachesTheActions() {
        var undone = ""
        show(false, live = false, actions = ThreadActions(undo = { undone = it }))
        rule.onNodeWithText("Worked for 2m 10s \u00b7 3 steps", substring = true).performClick()
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.onNodeWithText("Undo").performClick()
        assertEquals("c3", undone)
    }
}
