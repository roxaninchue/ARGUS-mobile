package com.blazeinchue.argus.ui

import androidx.compose.ui.graphics.Color
import com.blazeinchue.argus.home.StackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One look everywhere. v0.45.8 made one picked Living Theme show on every screen; 0.52.2 removed the Living Themes with
 * the ARGUS living 3D Home, so the rule is now: light / dark ([ThemeMode]) is the only switch, every screen follows it, every
 * switcher writes through it, nothing reads a picked theme any more, and the theme scene pictures are gone from the APK.
 *
 * What this does NOT cover: rendered pixels (the shots jobs do) and two activities on screen at once.
 */
class ThemeEverywhereTest {
    private val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory }
    private fun src(path: String) = java.io.File(root, "com/blazeinchue/argus/$path").readText()
    private val assets = listOf(java.io.File("src/main/assets"), java.io.File("app/src/main/assets")).first { it.isDirectory }

    /** What the Living Themes were made of: none of it may come back. */
    private val gone = listOf("LivingThemes", "LivingLogic", "LivingBackground", "ThemeTint", "ThemeScenes", "WeatherLook", "ThemePicker",
        "ThemeSceneImages", "SceneVeil", "themeOn()", "ThemedScreen(", "screenGround", "Look.theme", "Look.bg", "drawTheme(", "rememberScene(")

    @Test fun theLivingThemesAreGone() {
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            val t = f.readText(); gone.filter { it in t }.map { "${f.name}: $it" }
        }.toList()
        assertTrue("Living Themes still referenced: $hits", hits.isEmpty())
        for (f in listOf("LivingThemes.kt", "LivingLogic.kt", "LivingBackground.kt", "LivingThemesDraw.kt", "ThemePicker.kt", "ThemeSceneImages.kt"))
            assertFalse("ui/$f still exists", java.io.File(root, "com/blazeinchue/argus/ui/$f").exists())
        assertFalse("assets/theme-scenes still ships (~880 KB)", java.io.File(assets, "theme-scenes").exists())
        // The avatar menu has no Themes item; Look & feel has no living backgrounds.
        assertFalse("MenuItem(\"themes\"" in src("ui/ChatScreen.kt"))
        assertFalse("Living backgrounds" in src("ui/SimplePages.kt") || "Move with music" in src("ui/SimplePages.kt"))
    }

    @Test fun everyScreenHasTheLightDarkSwitch() {
        assertEquals(listOf("home", "projects", "chat", "settings", "sheets", "music", "video", "design", "terminal"), AppTheme.SCREENS)
        val switchers = mapOf(
            "home" to "ui/HomeBoardUi.kt", "projects" to "ui/space/SpaceProjects.kt", "settings" to "ui/SettingsSheet.kt",
            "music" to "mediaapps/MusicActivity.kt", "video" to "mediaapps/VideoActivity.kt", "design" to "ui/DesignView.kt")
        for ((s, f) in switchers) assertTrue("$s has no ThemeSwitch", "ThemeSwitch(" in src(f))
        // 0.52.3 Chat: the avatar opens the profile, whose System Preferences row lists Appearance (light / dark); a
        // long-press on the avatar switches light / dark at once. Terminal: ⋮ › Light or dark.
        val sys = com.blazeinchue.argus.ui.space.SpaceProfile.row("system")!!
        assertEquals("System Preferences", sys.label)
        assertEquals(com.blazeinchue.argus.ui.space.SpaceProfile.SYSTEM_PAGE, sys.page)
        assertEquals("Appearance", com.blazeinchue.argus.ui.space.SpaceSettings.SYSTEM_ROWS.toMap()["look"])
        assertEquals(com.blazeinchue.argus.ui.space.SpaceProfile.SYSTEM_PAGE, com.blazeinchue.argus.ui.space.SpaceSettings.parentOf("look"))
        assertTrue("\"look\" -> LookPage()" in src("ui/SettingsSheet.kt"))
        assertTrue("onAvatar = { settingsPage = \"\" }" in src("ui/ChatScreen.kt"))
        assertTrue("LightDark.toggle(c.dark)" in src("ui/ChatScreen.kt") && "onAvatarLong" in src("ui/space/SpaceTopBar.kt"))
        assertTrue("themeMenu()" in src("terminal/ArgusTerminalActivity.kt"))
        // Every switcher writes through ThemeMode.
        assertTrue("AppTheme.toggle()" in src("ui/AppTheme.kt") && "ThemeMode.set(m)" in src("ui/AppTheme.kt"))
        assertTrue("ThemeMode.set(item.id)" in src("terminal/ArgusTerminalActivity.kt"))
        // The Terminal repaints when light / dark changes anywhere.
        assertTrue("snapshotFlow { com.blazeinchue.argus.ui.ThemeMode.mode.value }" in src("terminal/ArgusTerminalActivity.kt"))
        assertTrue("TermPalette.of(com.blazeinchue.argus.ui.AppTheme.darkNow())" in src("terminal/ArgusTerminalActivity.kt"))
    }

    @Test fun aSwitchAnywhereShowsEverywhere() {
        val before = ThemeMode.mode.value
        try {
            ThemeMode.mode.value = "dark"
            assertTrue(AppTheme.darkNow())
            assertEquals("light", AppTheme.toggle())
            assertEquals("light", ThemeMode.mode.value)
            assertFalse(AppTheme.darkNow())
            assertFalse(TermPalette.of(AppTheme.darkNow()).dark)
            assertEquals("dark", AppTheme.toggle())
            assertTrue(TermPalette.of(AppTheme.darkNow()).dark)
            // Auto follows the clock (dark from 19:30 to 05:00); "system" is dark-first.
            ThemeMode.mode.value = "auto"
            assertTrue(AppTheme.darkNow(22)); assertFalse(AppTheme.darkNow(12)); assertTrue(AppTheme.darkNow(3))
            ThemeMode.mode.value = "system"
            assertTrue(AppTheme.darkNow(12))
        } finally { ThemeMode.mode.value = before }
    }

    @Test fun terminalPaletteKeepsAa() {
        var checked = 0
        for (dark in listOf(true, false)) {
            val p = TermPalette.of(dark)
            val tag = if (dark) "dark" else "light"
            assertTrue("$tag text ${TermPalette.ratio(p.fg, p.bg)}", TermPalette.ratio(p.fg, p.bg) >= 7.0)
            for ((name, col) in listOf("cursor" to p.cursor, "accent" to p.accent, "dim" to p.dim))
                assertTrue("$tag $name ${TermPalette.ratio(col, p.bg)}", TermPalette.ratio(col, p.bg) >= TermPalette.MIN)
            for (i in 1 until 16) assertTrue("$tag ANSI $i ${TermPalette.ratio(p.ansi[i], p.bg)}", TermPalette.ratio(p.ansi[i], p.bg) >= TermPalette.MIN)
            // ANSI 0 is a background slot: text on it reads.
            assertTrue("$tag text on ANSI 0", TermPalette.ratio(TermPalette.onAnsi0(p), p.ansi[0]) >= TermPalette.MIN)
            // Key labels on key caps and on the bars; the selected tab's label (the ground colour) on the accent.
            assertTrue("$tag keys", TermPalette.ratio(p.fg, p.keyBg) >= TermPalette.MIN)
            assertTrue("$tag bar", TermPalette.ratio(p.fg, p.barBg) >= TermPalette.MIN)
            assertTrue("$tag selected tab", TermPalette.ratio(p.bg, p.accent) >= TermPalette.MIN)
            assertTrue("$tag sheet rows", TermPalette.ratio(p.fg, p.row) >= TermPalette.MIN && TermPalette.ratio(p.dim, p.row) >= 3.0)
            assertEquals(dark, p.dark)
            checked++
        }
        assertEquals(2, checked)
        // The near-black terminal it always had in dark; a soft paper in light.
        assertEquals(Color(0xFF0B0B0D), Color(TermPalette.of(true).bg))
        assertEquals(Color(0xFFF7F7F8), Color(TermPalette.of(false).bg))
    }

    @Test fun ghostStackShowsOnlyStrips() {
        assertTrue(StackState.strips(1).isEmpty())
        val s = StackState.strips(5)
        assertEquals(2, s.size)
        assertEquals(8 * StackState.SHIFT, s[0].shiftDp, 0.001f); assertEquals(s[0].shiftDp, s[0].visibleDp, 0.001f)
        assertEquals(16 * StackState.SHIFT, s[1].shiftDp, 0.001f); assertEquals(s[1].shiftDp - s[0].shiftDp, s[1].visibleDp, 0.001f)
        // The strips never overlap the main card or each other, and fit the 26 dp gap under a collapsed stack.
        assertTrue(s.sumOf { it.visibleDp.toDouble() } <= 26.0)
        assertTrue("drawWithContent" in src("ui/HomeBoardUi.kt") && "st.visibleDp" in src("ui/HomeBoardUi.kt"))
    }
}
