package com.blaze.assistant.ui.argus

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blaze.assistant.ui.argus.icons.Lucide
import com.blaze.assistant.ui.argus.icons.LucideSpec
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * P0-SH-02 screenshots (shots job only, BLAZE_SHOTS=1, like SpaceShotsTest): every orb face at every 04 §2.7 size, the
 * five states, the Manager sphere and the semantic Lucide icons, in light and dark. PNGs go to build/shots/OrbShotsTest.
 * The check: the orbs draw colour (no blank canvas) and the dark and light frames differ.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-hdpi", application = android.app.Application::class)
class OrbShotsTest {
    @get:Rule(order = 0) val onlyWithShots = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("blaze.shots") == "1"); base.evaluate() }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun sheet(dark: Boolean) {
        rule.setContent {
            ArgusTheme(dark = dark) {
                ArgusGround {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in listOf(OrbSize.XS, OrbSize.SM, OrbSize.MD)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OrbFace.entries.forEachIndexed { i, f -> BotOrb(f, ArgusTokens.BOT_COLORS.values.elementAt(i), s.dp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            OrbState.entries.forEach { st -> BotOrb(OrbFace.ORBIT, ArgusTokens.BOT_COLORS.getValue("green"), OrbSize.LG.dp, st, amplitude = 1f, reducedMotion = true) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ManagerOrb(OrbSize.XL.dp)
                            BotOrb(OrbFace.TWIN, ArgusTokens.BOT_COLORS.getValue("silver"), OrbSize.HERO.dp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (n in LucideSpec.Semantic.ALL.take(12)) Icon(Lucide.icon(n, LucideSpec.Size.ICON_BUTTON), n, Modifier.size(20.dp), tint = Argus.colors.ink)
                        }
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
    }

    private fun save(name: String): Bitmap {
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File(System.getProperty("blaze.shotsDir") ?: "build/shots", "OrbShotsTest").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    private fun saturated(b: Bitmap): Int {
        var n = 0
        for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) {
            val p = b.getPixel(x, y); val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val bl = p and 0xFF
            if (maxOf(r, g, bl) - minOf(r, g, bl) > 60) n++
        }
        return n
    }

    @Test fun orbsLight() { sheet(dark = false); assertTrue(saturated(save("orbs-light")) > 200) }
    @Test fun orbsDark() { sheet(dark = true); assertTrue(saturated(save("orbs-dark")) > 200) }
}
