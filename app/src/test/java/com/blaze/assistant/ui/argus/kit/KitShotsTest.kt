package com.blaze.assistant.ui.argus.kit

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.ui.argus.ArgusGround
import com.blaze.assistant.ui.argus.ArgusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * P1-SH-02 kit on Robolectric. Shots (BLAZE_SHOTS=1 only, like OrbShotsTest): one sheet of every kit component in light
 * and dark at font 100 % and 200 %, to build/shots/KitShotsTest. Always run (debug unit suite): every clickable node's
 * touch bounds are at least 48 × 48 dp (the sheet is 4000 dp tall so nothing is clipped), and rows / pins / chips / the banner are single merged TalkBack nodes with the KitA11y text.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h4000dp-hdpi", application = android.app.Application::class)
class KitShotsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val nova = BotRowModel("nova", "Nova", "Sent the deck to Alex", "10:42", unread = 2, status = "Working")
    private val argus = BotRowModel("manager", "ARGUS", "All clear", "9:10", manager = true, tone = PreviewTone.CLEAR, pinned = true)
    private val atlas = BotRowModel("atlas", "Atlas", "2 overdue · Send deck", "Tue", tone = PreviewTone.OVERDUE)
    private val pins = listOf(PinModel("manager", "ARGUS", manager = true), PinModel("nova", "Nova"), PinModel("atlas", "Atlas", overdue = true), PinModel("ledger", "Ledger"))
    private val chips = listOf(ChipModel("all", "All"), ChipModel("health", "Health", 3), ChipModel("money", "Money"), ChipModel("work", "Work", 12))

    @Composable
    private fun Sheet() {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChips(chips, "all", {})
            NeedYouBanner(3, listOf("Nova", "Atlas"), {})
            NeedYouBanner(1, listOf("Ledger"), {}, approvalsOnly = true)
            PinStrip(pins, {}, onReorder = {})
            BotChatRow(argus, {}, onLongClick = {}, onPin = {}, onMute = {})
            BotChatRow(nova, {}, onLongClick = {}, onPin = {}, onMute = {})
            BotChatRow(atlas, {}, onLongClick = {})
            MenuRow("bot", "New bot", {}, sub = "Blank, from a template, or describe it")
            MenuRow("trash", "Delete bot", {}, danger = true)
            SettingsGroup(listOf(SettingsItem("palette", "Appearance", "Match phone") {}, SettingsItem("shield-check", "Permissions") {}), title = "You & phone")
            Segmented(listOf(ChipModel("inbox", "Inbox", 2), ChipModel("jobs", "Jobs"), ChipModel("routines", "Routines")), "jobs", {})
            androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArgusButton("Cancel", {}); ArgusButton("Approve", {}, kind = ButtonKind.PRIMARY); ArgusButton("Delete", {}, kind = ButtonKind.DANGER, size = ButtonSize.S)
                ArgusIconButton("search", "Search", {}); ArgusIconButton("bell", "Inbox", {}, badge = true); ArgusIconButton("user", "You", {}, kind = IconButtonKind.AVATAR, initial = "F")
            }
            ArgusSearch("", {}, Modifier.padding(horizontal = 12.dp))
            ArgusField("Weekly review", {}, "Job title", Modifier.padding(horizontal = 12.dp))
            MentionPopup(listOf(Mention.Item(Mention.Kind.BOT, "nova", "Nova", "Research"), Mention.Item(Mention.Kind.COMMAND, "fresh", "fresh", "New conversation")), {})
            AttachmentBar(listOf(AttachmentModel("1", "quarterly-report-final-v3.pdf"), AttachmentModel("2", "photo.jpg", image = true)), {})
            ProgressLine("Voice model", 0.42f)
            ProgressLine("Workspace", null, reducedMotion = true)
            CloudChip(running = true, costPerHour = 0.055, onClick = {}, force = true)
            EmptyState("Build your team", icon = "bot", body = "Bots that each own a part of your life.", primary = "New bot" to {}, secondary = "Demo team" to {}, hero = true)
        }
    }

    private fun show(dark: Boolean, fontScale: Float) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                ArgusTheme(dark = dark) { ArgusGround { Sheet() } }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
    }

    private fun save(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "KitShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun checksum(b: Bitmap): Long { var s = 0L; for (y in 0 until b.height step 8) for (x in 0 until b.width step 8) s = s * 31 + b.getPixel(x, y); return s }

    private fun shotsOnly() = assumeTrue(System.getProperty("blaze.shots") == "1")

    @Test fun kitLight100() { shotsOnly(); show(false, 1f); save("kit-light-100") }
    @Test fun kitDark100() { shotsOnly(); show(true, 1f); save("kit-dark-100") }
    @Test fun kitLight200() { shotsOnly(); show(false, 2f); save("kit-light-200") }
    @Test fun kitDark200() { shotsOnly(); show(true, 2f); save("kit-dark-200") }
    @Test fun darkAndLightDiffer() { shotsOnly(); show(true, 1f); val a = checksum(save("diff-dark")); show(false, 1f); assertNotEquals(a, checksum(save("diff-light"))) }

    // ---------------- always run ----------------

    @Test fun everyClickableIs48dp() {
        for (scale in listOf(1f, 2f)) {
            show(dark = true, fontScale = scale)
            val min = 48 * rule.density.density - 0.5f
            val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            assertTrue("found clickables", nodes.size > 20)
            for (n in nodes) {
                val b = n.touchBoundsInRoot
                val name = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString()
                assertTrue("'$name' at font $scale is ${b.width / rule.density.density}×${b.height / rule.density.density} dp", b.width >= min && b.height >= min)
            }
        }
    }

    @Test fun rowsAreOneTalkBackNode() {
        show(dark = false, fontScale = 1f)
        for (m in listOf(nova, argus, atlas)) {
            val n = rule.onNodeWithContentDescription(KitA11y.botRow(m)).fetchSemanticsNode()
            assertEquals("${m.name} row has no separate children", 0, n.children.size)
            assertTrue(n.config.contains(SemanticsActions.OnClick))
            assertTrue(n.config.contains(SemanticsActions.OnLongClick))
        }
        val novaActions = rule.onNodeWithContentDescription(KitA11y.botRow(nova)).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Pin", "Mute"), novaActions)
        val argusActions = rule.onNodeWithContentDescription(KitA11y.botRow(argus)).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Unpin", "Mute"), argusActions)
        for (p in pins) assertEquals(0, rule.onNodeWithContentDescription(KitA11y.pin(p)).fetchSemanticsNode().children.size)
        assertEquals(0, rule.onNodeWithContentDescription(KitA11y.banner(3, listOf("Nova", "Atlas"))).fetchSemanticsNode().children.size)
        val sel = rule.onNodeWithContentDescription(KitA11y.chip(chips[0], true)).fetchSemanticsNode()
        assertEquals(true, sel.config[SemanticsProperties.Selected])
        rule.onNodeWithContentDescription(KitA11y.removeAttachment(AttachmentModel("1", "quarterly-report-final-v3.pdf"))).assertExists()
        rule.onNodeWithContentDescription(KitA11y.progress("Voice model", 0.42f)).assertExists()
    }

    @Test fun hiddenParts() {
        rule.setContent { ArgusTheme(dark = true) { Column {
            NeedYouBanner(0, listOf("Nova"), {})
            CloudChip(running = true, costPerHour = 0.055, onClick = {})   // hidden until P7
            AttachmentBar(emptyList(), {})
            MentionPopup(emptyList(), {})
        } } }
        assertEquals(0, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }
}
