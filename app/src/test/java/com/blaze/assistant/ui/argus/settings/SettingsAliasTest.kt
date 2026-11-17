package com.blaze.assistant.ui.argus.settings

import com.blaze.assistant.ui.LookLogic
import com.blaze.assistant.ui.settingsPageByName
import com.blaze.assistant.ui.settingsPages
import com.blaze.assistant.ui.settingsStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1-SH-06 Settings re-tree. 06 row 6 acceptance: every pre-0.53 id opens a page, and open-by-name from chat works.
 * [PRE_053] is the frozen list of ids Settings knew at 0.52.4 / ticket 5 (the 42 settingsPages ids, the old aliases,
 * the 0.52.3 profile's row ids): each must still land on a page SettingsScreen draws, or on the root for the aliases
 * that always meant the root. Plus: the You page's tree, Back, search, and the Appearance rules.
 */
class SettingsAliasTest {
    private val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
    private val sheet by lazy { File(root, "com/blaze/assistant/ui/SettingsSheet.kt").readText() }

    private val PRE_053 = listOf("system", "feedback", "look", "language", "doing", "bank", "voice", "permissions", "account", "updates",
        "about", "holding", "connections", "desktop", "control", "persona", "feel", "privacy", "watches", "secrets", "logins",
        "phonebrowser", "proactive", "orb", "logs", "replies", "usage", "backup", "ai", "memory",
        "routines", "skills", "learn", "appstore", "seller", "dashboard", "plan", "sync",
        // old aliases (0.52.3 declutter)
        "router", "backup_brain",
        // the 0.52.3 profile's row ids
        "profile", "scheduled", "accounts", "wallet", "security")
    // 16 Nov 2026 (Finn): Team & bots and Jobs left Settings; their old ids open the root.
    private val TO_ROOT = setOf("advanced", "settings", "", "specialists", "terminal", "device", "commands")

    /** SettingsScreen draws [page]: a `"page" ->` branch, or the System / Feedback constants. */
    private fun drawn(page: String) = "\"$page\" ->" in sheet ||
        (page == "system" && "SpaceProfile.SYSTEM_PAGE -> " in sheet) || (page == "feedback" && "SpaceProfile.FEEDBACK_PAGE -> " in sheet)

    @Test fun everyPre053IdOpensAPage() {
        assertEquals(38, settingsPages.size)
        assertTrue(settingsPages.none { it.id == "specialists" || it.id == "terminal" || it.id == "device" })
        for (id in PRE_053) {
            val p = settingsStart(id)
            assertTrue("$id opens nothing", p != null)
            assertTrue("$id → $p is not a page", settingsPages.any { it.id == p })
            assertTrue("$id → $p has no branch in SettingsScreen", drawn(p!!))
        }
        for (id in TO_ROOT) assertNull("$id is the root", settingsStart(id))
        assertEquals("account", settingsStart("profile"))
        assertEquals("routines", settingsStart("scheduled"))
        assertEquals("ai", settingsStart("backup_brain"))
        // ids live in the pure file; SettingsSheet no longer declares them
        assertFalse("val settingsPages" in sheet || "internal val settingsAliases" in sheet)
    }

    @Test fun theYouPageReachesRealPagesOnly() {
        val ids = settingsPages.map { it.id }.toSet()
        for (r in ArgusSettings.ROWS) { assertTrue("${r.id} → ${r.page}", r.page in ids); assertTrue("no branch for ${r.page}", drawn(r.page)) }
        assertTrue(ArgusSettings.ACCOUNT_PAGE in ids && ArgusSettings.PLAN_PAGE in ids)
        // the pages nobody may remove are on it (0.52.3 rule)
        assertTrue(ArgusSettings.TREE.containsAll(com.blaze.assistant.ui.space.SpaceSettings.REQUIRED))
        assertEquals(listOf(null, "Team", "You & phone", "System"), ArgusSettings.GROUPS.map { it.title })
        assertEquals(ArgusSettings.ROWS.size, ArgusSettings.ROWS.map { it.id }.toSet().size)
        assertEquals("rows open different pages", ArgusSettings.ROWS.size, ArgusSettings.ROWS.map { it.page }.toSet().size)
        assertTrue("no Models & engines row: always ARGUS Cloud", ArgusSettings.ROWS.none { it.page == "ai" || it.id == "models" })
        assertTrue("no Team & bots or Jobs row", ArgusSettings.ROWS.none { it.page == "specialists" || it.page == "terminal" })
        for (r in ArgusSettings.ROWS) assertTrue("${r.icon} is a Lucide icon", r.icon in com.blaze.assistant.ui.argus.icons.LucidePaths.ICONS)
    }

    @Test fun backGoesToTheYouPage() {
        assertEquals("secrets", ArgusSettings.parentOf("logins"))
        for (p in listOf("look", "voice", "permissions", "updates", "plan", "sync", "about")) assertNull(p, ArgusSettings.parentOf(p))
        assertNull(ArgusSettings.parentOf(null))
        assertTrue("Back uses the ARGUS tree", "else page = com.blaze.assistant.ui.argus.settings.ArgusSettings.parentOf(p)" in sheet)
        assertTrue("the You page is the only root (no 0.52.3 profile)", "com.blaze.assistant.ui.space.SpaceProfileScreen(" !in sheet && "com.blaze.assistant.ui.argus.settings.YouPage(" in sheet)
        assertTrue("no look switch left in Settings", "Argus.lookOn()" !in sheet)
        assertTrue("Back from a page keeps the You page's scroll", "val youScroll = rememberScrollState()" in sheet && "scroll = youScroll)" in sheet)
    }

    @Test fun openByNameFromChat() {
        val cases = mapOf("voice" to "voice", "settings voice" to "voice", 
            "approvals" to "privacy", "appearance" to "look", "handedness" to "look", "reduced motion" to "look",
            "open notifications settings" to "proactive", "apps & connectors" to "connections",
            "sync & devices" to "sync", "wallet" to "secrets",
            "lock" to "privacy", "submit feedback" to "feedback")
        for ((said, page) in cases) assertEquals(said, page, settingsPageByName(said))
        assertEquals("", settingsPageByName("the"))
        assertEquals("", settingsPageByName("zzzz"))
        for (gone in listOf("team and bots", "Team & bots", "computers", "linux", "jobs", "diagnostics", "device & diagnostics")) assertEquals(gone, "", settingsPageByName(gone))
        // agent.settingsPageFor (QuickRules, open_settings) is the same function
        val self = File(root, "com/blaze/assistant/agent/SelfSetup.kt").readText()
        assertTrue("fun settingsPageFor(raw: String): String = com.blaze.assistant.ui.settingsPageByName(raw)" in self)
    }

    @Test fun searchFindsRowsByWordsAndPageText() {
        val text = settingsPages.associate { it.id to (it.title + " " + it.sub) }
        assertEquals(listOf("look"), ArgusSettings.search("dark", text).map { it.id })
        assertEquals(listOf("approvals"), ArgusSettings.search("Bank mode", text).map { it.id })
        assertEquals(listOf("voice"), ArgusSettings.search("hey argus", text).map { it.id })
        assertTrue(ArgusSettings.search("google", text).any { it.id == "apps" })
        assertTrue(ArgusSettings.search("   ", text).isEmpty())
        assertTrue(ArgusSettings.search("zzz", text).isEmpty())
        // page text counts: "Tokens and estimated cost" is not a row, but "licences" is About's sub
        assertTrue(ArgusSettings.search("licences", text).any { it.id == "about" })
    }

    @Test fun appearanceRules() {
        assertEquals(ArgusSettings.ThemeChoice.MATCH_PHONE, ArgusSettings.ThemeChoice.of("phone"))
        assertEquals(ArgusSettings.ThemeChoice.LIGHT, ArgusSettings.ThemeChoice.of("light"))
        assertEquals("the old default 'system' was always dark", ArgusSettings.ThemeChoice.DARK, ArgusSettings.ThemeChoice.of("system"))
        assertEquals(ArgusSettings.ThemeChoice.DARK, ArgusSettings.ThemeChoice.of(""))
        assertNull(ArgusSettings.ThemeChoice.of("auto"))
        // "phone" follows the phone (LookLogic's else branch with the real system value)
        assertFalse(LookLogic.darkFor("phone", 12, systemDark = false))
        assertTrue(LookLogic.darkFor("phone", 12, systemDark = true))
        for (f in listOf("ui/Theme.kt", "ui/AppTheme.kt")) assertTrue(f, "ThemeMode.mode.value == \"phone\"" in File(root, "com/blaze/assistant/$f").readText())
        assertEquals(ArgusSettings.Hand.LEFT, ArgusSettings.Hand.of("left"))
        assertEquals(ArgusSettings.Hand.RIGHT, ArgusSettings.Hand.of(null))
        assertEquals(listOf("cancel", "ok"), ArgusSettings.buttonOrder("ok", "cancel", ArgusSettings.Hand.RIGHT))
        assertEquals(listOf("ok", "cancel"), ArgusSettings.buttonOrder("ok", "cancel", ArgusSettings.Hand.LEFT))
        assertTrue(ArgusSettings.reducedMotion(true, 1f))
        assertTrue("phone animations off", ArgusSettings.reducedMotion(false, 0f))
        assertFalse(ArgusSettings.reducedMotion(false, 1f))
        assertEquals(com.blaze.assistant.ui.argus.Ground.entries.map { it.name }, ArgusSettings.GROUNDS.map { it.first })
        // ARGUS is the only look (16 Nov 2026): no look switch and no accent on Appearance
        val look = File(root, "com/blaze/assistant/ui/SimplePages.kt").readText()
        assertTrue("com.blaze.assistant.ui.argus.settings.ArgusAppearanceLive()" in look)
        val you = File(root, "com/blaze/assistant/ui/argus/settings/YouPage.kt").readText()
        val section = you.substringAfter("fun ArgusAppearanceSection(")
        assertTrue("no look switch", "ArgusLookFlag.KEY" !in you && "onLook" !in section)
        assertTrue("no accent", "Accent" !in section)
    }
}
