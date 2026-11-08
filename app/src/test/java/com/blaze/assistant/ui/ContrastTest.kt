package com.blaze.assistant.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.45.7 readability (0.52.2: the theme scenes and their veil went with the Living Themes; 0.52.3: the space palette):
 * the shell's text keeps WCAG AA on its grounds in both modes, chips are opaque with a 3:1 border, the sheet veil fades out instead of ending in a hard band.
 *
 * What this does NOT cover: real rendered pixels (the shots jobs do), and the space photo itself (the dark checks lay the cards over white under the scrim, the worst case).
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

    /**
     * 0.52.3 (ARGUS x Hark): the space palette in both modes. Dark: white, Steel and the lifted Electric text on obsidian
     * and on every card / tile / track / profile group, laid over black and over the brightest the scrimmed space view gets
     * (white under the scrim); Electric (rings, cursor) keeps 3:1. Light: ink, the deepened Steel and Argus Blue on the
     * light ground, cards, tiles and the track. The selected segment and the primary buttons read in both modes. Unification:
     * the solid card and the menu surface too.
     */
    @Test fun theSpacePaletteReadsInBothModes() {
        val p = com.blaze.assistant.ui.space.SpacePalette
        val black = Color(p.OBSIDIAN)
        val brightest = Contrast.over(Color.Black.copy(alpha = p.scrimAlpha(true)), Color.White)
        assertTrue("the dark scrim darkens the space view", p.scrimAlpha(true) >= 0.5f)
        val surfaces = listOf(Color(p.CARD_DARK), Color(p.TILE_DARK), Color(p.TRACK_DARK), Color(p.CARD_SOLID_DARK), Color(p.MENU_DARK))
        val darkGrounds = listOf(black) + surfaces.flatMap { listOf(Contrast.over(it, black), Contrast.over(it, brightest)) }
        for (g in darkGrounds) {
            assertTrue("white on $g", Contrast.ratio(Color(p.WHITE), g) >= Contrast.AA_TEXT)
            assertTrue("steel on $g", Contrast.ratio(Color(p.STEEL), g) >= Contrast.AA_TEXT)
            assertTrue("electric text on $g", Contrast.ratio(Color(p.ELECTRIC_TEXT), g) >= Contrast.AA_TEXT)
            assertTrue("electric (rings, cursor) on $g", Contrast.ratio(Color(p.ELECTRIC), g) >= Contrast.AA_UI)
        }
        val lightGrounds = listOf(Color(p.GROUND_LIGHT), Color(p.CARD_LIGHT), Color(p.TILE_LIGHT), Color(p.TRACK_LIGHT), Color(p.CARD_SOLID_LIGHT), Color(p.MENU_LIGHT))
        for (g in lightGrounds) {
            assertEquals("light grounds are opaque", 1f, g.alpha, 0f)
            assertTrue("ink on $g", Contrast.ratio(Color(p.INK_LIGHT), g) >= Contrast.AA_TEXT)
            assertTrue("sub on $g", Contrast.ratio(Color(p.SUB_LIGHT), g) >= Contrast.AA_TEXT)
            assertTrue("argus blue on $g", Contrast.ratio(Color(p.BLUE_LIGHT), g) >= Contrast.AA_TEXT)
        }
        for (dark in listOf(true, false)) {
            val t = com.blaze.assistant.ui.space.SpaceTabs
            assertTrue("selected segment ($dark)", Contrast.ratio(Color(t.selectedInk(dark)), Color(t.selectedFill(dark))) >= Contrast.AA_TEXT)
            val look = com.blaze.assistant.ui.space.SpaceLook.of(dark)
            assertEquals(dark, look.dark)
            assertTrue("primary button ($dark)", Contrast.ratio(look.primaryInk, look.primaryFill) >= Contrast.AA_TEXT)
            assertTrue("ink on the card ($dark)", Contrast.ratio(look.ink, Contrast.over(look.card, look.ground)) >= Contrast.AA_TEXT)
            assertTrue("sub on the card ($dark)", Contrast.ratio(look.sub, Contrast.over(look.card, look.ground)) >= Contrast.AA_TEXT)
            assertTrue("accent on the card ($dark)", Contrast.ratio(look.accent, Contrast.over(look.card, look.ground)) >= Contrast.AA_TEXT)
            for ((name, s) in listOf("solid card" to look.cardSolid, "menu" to look.menu)) {
                val g = Contrast.over(s, look.ground)
                assertTrue("ink on the $name ($dark)", Contrast.ratio(look.ink, g) >= Contrast.AA_TEXT)
                assertTrue("sub on the $name ($dark)", Contrast.ratio(look.sub, g) >= Contrast.AA_TEXT)
                assertTrue("accent on the $name ($dark)", Contrast.ratio(look.accent, g) >= Contrast.AA_TEXT)
            }
        }
        // the brand colours are the palette's (docs/BRAND.md)
        assertEquals(listOf(0xFF000000L, 0xFF0056FFL, 0xFF1286FFL, 0xFF9AA0B3L, 0xFFFFFFFFL), listOf(p.OBSIDIAN, p.ARGUS_BLUE, p.ELECTRIC, p.STEEL, p.WHITE))
    }

    /**
     * Unification: one palette. The legacy tokens (ArgusBrand, GlassTokens, Light / DarkColors, the Material scheme) are
     * the shell's SpacePalette colours, the default accent is the same in both systems, and the shell takes an accent
     * picked in Look and feel the way the legacy screens do. The solid primary keeps white text at AA.
     */
    @Test fun oneBrandPaletteEverywhere() {
        val p = com.blaze.assistant.ui.space.SpacePalette
        val look = com.blaze.assistant.ui.space.SpaceLook
        assertEquals(Color(p.OBSIDIAN), ArgusBrand.Black)
        assertEquals(Color(p.ARGUS_BLUE), ArgusBrand.Cobalt)
        assertEquals(Color(p.ELECTRIC), ArgusBrand.Electric)
        assertEquals(Color(p.ELECTRIC_TEXT), ArgusBrand.ElectricText)
        assertEquals(Color(p.STEEL), ArgusBrand.Silver)
        assertEquals(Color(p.GROUND_LIGHT), ArgusBrand.Paper)
        assertEquals(Color(p.OBSIDIAN), GlassTokens.bg)
        assertEquals(Color(p.ELECTRIC_TEXT), GlassTokens.accent)
        assertEquals(Color(p.GROUND_LIGHT), LightColors.paper)
        assertEquals(Color(p.OBSIDIAN), DarkColors.paper)
        assertEquals("one default accent (light)", look.Light.accent, LightColors.accent)
        assertEquals("one default accent (dark)", look.Dark.accent, DarkColors.accent)
        assertEquals(LightColors.accent, blazeScheme(false).primary)
        assertEquals(DarkColors.accent, blazeScheme(true).primary)
        assertTrue("the primary is solid", ArgusBrand.primary is androidx.compose.ui.graphics.SolidColor)
        assertTrue("white on the primary", Contrast.ratio(Color.White, ArgusBrand.Cobalt) >= Contrast.AA_TEXT)
        // the accent picker reaches the shell: same colour as the legacy screens get, the rest of the look unchanged
        for (dark in listOf(true, false)) {
            val picked = blazePalette(dark, 0xFF14B8A6).accent
            val l = look.of(dark, picked)
            assertEquals(picked, l.accent)
            assertEquals(picked, blazeScheme(dark, picked).primary)
            assertEquals(look.of(dark).copy(accent = picked), l)
            assertEquals("nothing picked = the shell's own", look.of(dark), look.of(dark, blazePalette(dark).accent))
            assertEquals(look.of(dark), look.of(dark, null))
        }
    }

    /**
     * Unification: dialogs and menus follow light / dark (they were always dark, so light mode got black slabs). Each
     * mode's dialog colours are the shell's solid card, ink and sub; the title, body, ghost-button text, selected tint and
     * warm text keep AA on the dialog; white keeps AA on the warm button; menus are the shell's menu surface; and the one
     * primary button style (the shell's) keeps AA in both modes.
     */
    @Test fun dialogsAndMenusFollowTheMode() {
        val look = com.blaze.assistant.ui.space.SpaceLook
        assertFalse("light dialogs are not the dark slab", DialogColors.Light.surface == DialogColors.Dark.surface)
        for ((dark, c) in listOf(false to LightColors, true to DarkColors)) {
            val d = DialogColors.of(dark)
            val l = look.of(dark)
            assertEquals(l.cardSolid, d.surface)
            assertEquals(l.ink, d.title)
            assertEquals(l.sub, d.body)
            assertEquals(l.menu, c.menuSurface)
            val g = Contrast.over(d.surface, l.ground)
            for ((name, col) in listOf("title" to d.title, "body" to d.body, "ghost" to d.ghost, "selected tint" to d.primary, "warm text" to d.primaryWarm))
                assertTrue("$name on the dialog ($dark)", Contrast.ratio(Contrast.over(col, g), g) >= Contrast.AA_TEXT)
            assertTrue("white on the warm button ($dark)", Contrast.ratio(d.onPrimary, Contrast.over(d.warmFill, g)) >= Contrast.AA_TEXT)
            assertTrue("the dialog has an edge ($dark)", d.border.alpha > 0f)
            assertTrue("primary button ($dark)", Contrast.ratio(l.primaryInk, Contrast.over(l.primaryFill, g)) >= Contrast.AA_TEXT)
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
