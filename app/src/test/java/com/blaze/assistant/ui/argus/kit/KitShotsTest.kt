package com.blaze.assistant.ui.argus.kit

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * P1-SH-02: the kit in light / dark x font 100 / 200 % (shots job, BLAZE_SHOTS=1), plus the always-on checks: 48 dp
 * targets on icon buttons, buttons and chips, and one merged TalkBack line per chat row. PNGs go to
 * build/shots/KitShotsTest (or blaze.shotsDir).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-hdpi", application = android.app.Application::class)
class KitShotsTest {
    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Composable
    private fun Sample(dark: Boolean, fontScale: Float) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalKitDark provides dark, LocalDensity provides Density(d.density, fontScale)) {
            val look = kitLook()
            Column(Modifier.fillMaxSize().background(look.bg).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ArgusIconButton(null, "Profile and settings", {}, kind = IconButtonKind.AVATAR) { androidx.compose.material3.Text("F", color = look.onInk) }
                    ArgusIconButton(Icons.Rounded.Notifications, "Inbox", {}, badge = true)
                    ArgusIconButton(Icons.Rounded.Add, "New", {})
                }
                NeedYouBanner(NeedYou.of(2, 1, listOf("Ledger", "Atlas")), {})
                PinStrip(listOf(PinItem("vita", "Vita"), PinItem("ledger", "Ledger", overdue = true), PinItem("atlas", "Atlas"), PinItem("forge", "Forge")), {}) {
                    KitOrb(Color(0xFF22C55E), 56.dp)
                }
                BotChatRow("ARGUS", RowSpec.preview("Booked Vita's check-in for 9am."), "9:41", {}, role = "Manager", lead = true) { KitOrb(look.cobalt, KitSize.ORB_ROW.dp, initial = "A") }
                BotChatRow("Ledger", RowSpec.preview("", overdue = 1, firstOverdue = "Pay ZESCO bill"), "9:12", {}, role = "Money", unread = true) { KitOrb(Color(0xFFF59E0B), KitSize.ORB_ROW.dp) }
                FilterChips(listOf(ChipItem("all", "All"), ChipItem("inbox", "Inbox", 3), ChipItem("jobs", "Jobs")), "inbox", {})
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArgusButton("Deny", {})
                    ArgusButton("Approve", {}, kind = ButtonSpec.Kind.PRIMARY)
                    ArgusButton("Delete", {}, kind = ButtonSpec.Kind.DANGER, size = ButtonSpec.Size.S)
                }
                ArgusChip("Add job", {}, icon = Icons.Rounded.Add)
                SettingsGroup(title = "Team") {
                    SettingsRow(Icons.Rounded.Settings, "Models & engines", {}, value = "Automatic", divider = false)
                    SettingsRow(Icons.Rounded.Settings, "Computers & workspace", {}, value = "Cloud off")
                }
                MenuRow(Icons.Rounded.Add, "New bot", {}, sub = "Blank, from a template, or describe it")
                ArgusSearch("", {}, "Search settings", floating = true)
                ArgusField("", {}, "Name", label = "Bot name")
                ProgressLine("Downloading workspace", 0.42f)
                AttachmentBar(listOf(AttachmentItem("1", "holiday-photo-from-the-beach.jpeg")), {})
                EmptyState("Build your team", body = "Start with ARGUS and two helpers.", serif = true, primary = "New bot" to {}, secondary = "Demo team" to {})
            }
        }
    }

    @Test fun targetsAndSemantics() {
        rule.setContent { Sample(dark = true, fontScale = 1f) }
        for (d in listOf("Inbox", "New", "Profile and settings")) rule.onNodeWithContentDescription(d).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Ledger, Money, 1 overdue: Pay ZESCO bill, 9:12, unread").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("ARGUS, Manager, lead, Booked Vita's check-in for 9am., 9:41").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("3 need you: Ledger, Atlas. Review").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Add job").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Inbox, 3, selected").assertHeightIsAtLeast(48.dp)
    }

    @Test fun shots() {
        assumeTrue(System.getProperty("blaze.shots") == "1")
        // one setContent per test: the variant is state the loop changes
        val variant = androidx.compose.runtime.mutableStateOf(false to 1f)
        rule.setContent { Sample(variant.value.first, variant.value.second) }
        for (dark in listOf(false, true)) for (scale in listOf(1f, 2f)) {
            variant.value = dark to scale
            rule.waitForIdle()
            save("kit-${if (dark) "dark" else "light"}-${(scale * 100).toInt()}")
        }
    }

    private fun save(name: String) {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "KitShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
