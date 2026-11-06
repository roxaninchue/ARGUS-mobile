package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.45.7 readability (0.52.2: the theme scenes and their veil went with the Living Themes): the ARGUS Home's text keeps
 * WCAG AA on its grounds, chips are opaque with a 3:1 border, the sheet veil fades out instead of ending in a hard band.
 *
 * What this does NOT cover: real rendered pixels (the shots jobs do), and the live GL scene behind the Home's glass.
 */
class ContrastTest {
    private val palettes = listOf("light" to LightColors, "dark" to DarkColors)

    @Test fun mathMatchesWcag() {
        assertEquals(21.0, Contrast.ratio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, Contrast.ratio(Color.Red, Color.Red), 0.0001)
        assertEquals(4.54, Contrast.ratio(Color(0xFF767676), Color.White), 0.02)
        val half = Contrast.over(Color.Black.copy(alpha = 0.5f), Color.White)
        assertEquals(0.5f, half.red, 0.01f); assertEquals(1f, half.alpha, 0f)
    }

    /** 0.52.2: the ARGUS Home's text on its own grounds (obsidian, the glass bubbles / bar / sheet, the answer card). */
    @Test fun theArgusHomeTextReadsOnItsGrounds() {
        val t = com.blaze.assistant.ui.argus.ArgusHomeTokens
        val grounds = listOf(t.Obsidian, Contrast.over(t.Glass, Color.Black), Contrast.over(Color(0xF0050A16), Color.Black),
            Contrast.over(Color(0xE6081A40), Color.Black), Contrast.over(Color(0xF2101830), Color.Black), Color(0xFF00133F))
        for (g in grounds) {
            assertTrue("white on $g", Contrast.ratio(Color.White, g) >= Contrast.AA_TEXT)
            assertTrue("steel on $g", Contrast.ratio(t.Steel, g) >= Contrast.AA_TEXT)
            assertTrue("electric text on $g", Contrast.ratio(t.ElectricText, g) >= Contrast.AA_TEXT)
            assertTrue("electric (rings, cursor, borders) on $g", Contrast.ratio(t.Electric, g) >= Contrast.AA_UI)
        }
    }

    @Test fun chipsAreSolidWithAVisibleBorder() {
        for ((name, c) in palettes) {
            val fill = ChipStyle.fill(c)
            assertEquals("$name fill opaque", 1f, fill.alpha, 0f)
            assertTrue("$name chip text", Contrast.ratio(Contrast.over(c.accent, fill), fill) >= Contrast.AA_TEXT)
            assertTrue("$name chip border", Contrast.ratio(ChipStyle.edge(c), fill) >= Contrast.AA_UI)
        }
    }

    @Test fun sheetVeilHasNoHardEdge() {
        val stops = SheetVeil.stops(Color.Black)
        assertEquals(0f, stops.last().second.alpha, 0f)
        assertEquals(1f, stops.last().first, 0f)
        assertTrue(stops.first().second.alpha <= 0.12f)
        assertTrue(stops[1].first <= 0.75f)
    }

    @Test fun secretFieldsAndSyncSwitch() {
        assertTrue(SecretField.masked(secret = true, reveal = false))
        assertFalse(SecretField.masked(secret = true, reveal = true))
        assertFalse(SecretField.masked(secret = false, reveal = false))
        assertEquals("Show password", SecretField.label(false)); assertEquals("Hide password", SecretField.label(true))
        // Before a passphrase is set: the switch is off while the label says off (it used to show ON).
        val pending = CloudSyncSwitch.view(on = false, setupOpen = true, needsPassphrase = false, statusLine = "")
        assertFalse(pending.checked); assertTrue(pending.label.startsWith("Off"))
        assertEquals(CloudSyncSwitch.View(false, CloudSyncSwitch.OFF), CloudSyncSwitch.view(false, false, false, ""))
        assertEquals(CloudSyncSwitch.View(true, "Synced 2 min ago"), CloudSyncSwitch.view(true, false, false, "Synced 2 min ago"))
        assertEquals(CloudSyncSwitch.View(true, "Enter your sync passphrase to keep syncing."), CloudSyncSwitch.view(true, false, true, "Enter your sync passphrase to keep syncing."))
    }

    @Test fun everyMaskedFieldHasARevealToggle() {
        // Source scan: each PasswordVisualTransformation() is behind a reveal / show state (no field without an eye).
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (l.contains("PasswordVisualTransformation()")) "${f.name}:${i + 1}: $l" else null }
        }.toList()
        assertTrue(hits.size >= 4)
        for (h in hits) assertTrue("no reveal toggle: $h", Regex("reveal|masked\\)|SecretField\\.masked").containsMatchIn(h))
    }
}
