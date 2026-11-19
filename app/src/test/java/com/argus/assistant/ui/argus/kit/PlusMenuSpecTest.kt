package com.argus.assistant.ui.argus.kit

import com.argus.assistant.ui.argus.icons.LucidePaths
import com.argus.assistant.ui.argus.icons.LucideSpec
import com.argus.assistant.ui.living.LivingSpec
import com.argus.assistant.ui.plusItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner 2026-11-15: the + menu grows out of the chat box ([PlusMenuSpec], [ComposerPlusMenu] inside [ArgusComposer]).
 * Pure checks: every tile fits on a 320 / 360 / 411 dp phone and in landscape without clipping or ellipsis, the labels
 * are short, every icon is a Lucide icon we ship, and the height cap; plus source scans that the composer owns the menu
 * (no sheet over it), the + turns into ×, the chip and the Chat | Living switch fold away, and Back / tap outside close it.
 */
class PlusMenuSpecTest {
    private val all = plusItems("plan", "autopilot", "", hasClipImage = true, inProject = true, hasCamera = true)
    private val tiles = all.filter { it.tile }

    /** A generous label width estimate (Inter 12.5 sp medium averages ~0.55 em; 0.6 em leaves headroom). */
    private fun labelDp(s: String, fontScale: Float = 1f) = s.length * 12.5f * 0.6f * fontScale

    @Test fun tileLabelsAreShortAndScanReplacesArgusCamera() {
        assertEquals(listOf("camera", "blazecam", "photos", "files", "call"), tiles.map { it.id })
        val labels = tiles.map { PlusMenuSpec.label(it.id, it.label) }
        assertEquals(listOf("Camera", "Scan", "Photos", "Files", "Call"), labels)
        for (l in labels) assertTrue("$l is short", l.length <= PlusMenuSpec.LABEL_MAX_CHARS)
        assertFalse(labels.any { it.contains("\u2026") || it.contains("...") })
        assertEquals("Scan", all.first { it.id == "blazecam" }.label)
    }

    @Test fun everyTileFitsWithoutClippingOnPhonesAndLandscape() {
        val widest = tiles.maxOf { labelDp(PlusMenuSpec.label(it.id, it.label)) }
        for (screen in listOf(320, 360, 375, 411, 640, 800, 915)) for (scale in listOf(1f, 1.3f, 2f)) {
            val w = PlusMenuSpec.innerWidthDp(screen, LivingSpec.FLOAT_COMPOSER_MAX_DP).toFloat()
            val min = PlusMenuSpec.minTile(tiles.maxOf { labelDp(PlusMenuSpec.label(it.id, it.label), scale) })
            val cols = PlusMenuSpec.columns(w, tiles.size, min)
            val tw = PlusMenuSpec.tileWidth(w, cols)
            assertTrue("$screen dp @${scale}x: $cols × $tw dp in $w dp", cols * tw + (cols - 1) * PlusMenuSpec.GAP <= w + 0.01f)
            assertTrue("$screen dp @${scale}x: tile $tw holds the label", tw - 2 * PlusMenuSpec.TILE_LABEL_PAD >= widest * scale - 0.01f || cols == 1)
            assertTrue(cols in 1..tiles.size)
        }
        // the 360 dp phone of the screenshot: all five on one row
        assertEquals(320, PlusMenuSpec.innerWidthDp(360))
        assertEquals(5, PlusMenuSpec.columns(320f, 5, PlusMenuSpec.minTile(widest)))
        // too narrow for five: even rows (3 + 2), never 4 + 1 and never a sideways scroll
        assertEquals(3, PlusMenuSpec.columns(280f, 5, 56))
        assertEquals(1, PlusMenuSpec.columns(40f, 5, 56))
        assertEquals(1, PlusMenuSpec.columns(300f, 0))
    }

    @Test fun heightIsCappedForLandscape() {
        assertEquals(400, PlusMenuSpec.maxHeightDp(800))
        assertEquals(PlusMenuSpec.MAX_HEIGHT, PlusMenuSpec.maxHeightDp(1400))
        assertEquals(PlusMenuSpec.MIN_HEIGHT, PlusMenuSpec.maxHeightDp(280))
        assertEquals(180, PlusMenuSpec.maxHeightDp(360))   // a phone on its side
    }

    @Test fun everyEntryHasALucideIconWeShip() {
        for (it in all + plusItems("build", "autopilot", "", false)) {
            val icon = PlusMenuSpec.icon(it.id, it.on)
            assertFalse("${it.id} has its own icon", icon == "plus")
            assertTrue("$icon is in LucidePaths", LucidePaths.ICONS.containsKey(LucideSpec.resolve(icon)))
        }
        assertEquals("lightbulb", PlusMenuSpec.icon("plan", on = true))
        assertTrue(PlusMenuSpec.opensPage("apps") && PlusMenuSpec.opensPage("skills") && !PlusMenuSpec.opensPage("camera"))
    }

    private fun src(rel: String): String {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        return File(root, "com/argus/assistant/$rel").readText()
    }

    @Test fun theMenuIsPartOfTheComposer() {
        val kit = src("ui/argus/kit/KitShell.kt")
        val composer = kit.substringAfter("fun ArgusComposer(").substringBefore("\n}\n")
        assertTrue("the menu is a slot of the composer", "menu: (@Composable () -> Unit)? = null" in composer)
        assertTrue("it grows in the same glass, with MotionTokens", "argusGlass(GlassLevel.CHROME, shape)" in composer &&
            "AnimatedVisibility(menuOpen, enter = com.argus.assistant.ui.MotionTokens.expand(), exit = com.argus.assistant.ui.MotionTokens.collapse())" in composer)
        assertTrue("the model chip folds away while it is open", "AnimatedVisibility(!menuOpen" in composer)
        assertTrue("the + turns into ×", "PlusMenuSpec.PLUS_TURN" in composer && ".rotate(rotation)" in kit)
        assertTrue("typing closes it", "if (it.isFocused && menuOpen) onPlus()" in composer)
        val menu = src("ui/argus/kit/KitPlusMenu.kt")
        assertFalse("not a sheet", "ArgusSheet(" in menu || "horizontalScroll" in menu)
        assertFalse("labels never ellipsize", "TextOverflow.Ellipsis" in menu)
        assertTrue("Lucide icons", "Lucide.icon(PlusMenuSpec.icon(" in menu)

        val screen = src("ui/ChatScreen.kt")
        assertTrue("the ARGUS composer gets the menu", "menu = { com.argus.assistant.ui.argus.kit.ComposerPlusMenu(plusEntries(), onPick = { id -> plusOpen = false; plusPick(id) }) }" in screen)
        assertTrue("no sheet over the ARGUS composer", "PlusMenu(plusEntries(), onDismiss" !in screen)
        assertTrue("Back closes it", "if (plusOpen) BackHandler { plusOpen = false }" in screen)
        assertTrue("a tap outside closes it", "PlusMenuSpec.SCRIM_TAG" in screen && "indication = null) { plusOpen = false }" in screen)
        assertTrue("the Chat | Living switch folds away", "AnimatedVisibility(!plusOpen, enter = MotionTokens.expand(), exit = MotionTokens.collapse()) { com.argus.assistant.ui.living.LivingSwitch() }" in screen)
        // every action still runs from the one handler
        val pick = screen.substringAfter("fun plusPick(id: String) {").substringBefore("\n    }\n")
        for (id in listOf("camera", "blazecam", "photos", "files", "atfile", "paste", "call", "apps", "skills", "plan", "new"))
            assertTrue("$id is handled", "\"$id\" ->" in pick)
    }
}
