package com.blaze.assistant.ui.argus.apps

import com.blaze.assistant.agent.ChatNav
import com.blaze.assistant.ui.BackRule
import com.blaze.assistant.ui.Views
import com.blaze.assistant.ui.argus.icons.ArgusGlyphs
import com.blaze.assistant.ui.argus.icons.LucideSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2026-11-18: Life › Apps is a full page of ARGUS's apps (from the Views registry) plus the site apps installed from the browser. */
class AppsPageModelTest {
    private val ids get() = AppsPageModel.viewTiles().map { it.id }

    @Test fun tilesAreTheViewsMarkedAsAppsInRegistryOrder() {
        assertEquals(Views.all.filter { it.app }.map { it.id }, ids)
        assertTrue(ids.containsAll(listOf("music", "video", "browser", "phonebrowser", "gallery", "design", "terminal", "projects", "apps")))
    }

    @Test fun settingsChatAndHomeAreNotApps() {
        for (id in listOf("settings", "home", "memory", "accounts", "brain", "router", "skills", "voice", "control", "logins",
            "secrets", "proactive", "usage", "account", "sync", "look", "doing", "dashboard", "desktop")) assertFalse(id, id in ids)
    }

    @Test fun everyAppHasARealIcon() {
        for (t in AppsPageModel.viewTiles()) assertTrue(t.id + " → " + t.icon, t.icon in ArgusGlyphs.PATHS || LucideSpec.resolve(t.icon) != "square")
    }

    @Test fun fourToARowAndNothingLost() {
        val tiles = AppsPageModel.viewTiles() + AppsPageModel.siteTiles(listOf(Triple("a", "Gateway", "https://x.dev/")))
        val rows = AppsPageModel.rows(tiles)
        assertTrue(rows.all { it.size <= AppsPageModel.COLUMNS })
        assertEquals(tiles, rows.flatten())
    }

    @Test fun searchFiltersByLabelAliasesAndSiteUrl() {
        val sites = AppsPageModel.siteTiles(listOf(Triple("gw", "ARGUS Gateway · admin", "https://blaze-gateway.blaze-app.workers.dev/admin")))
        val all = AppsPageModel.viewTiles() + sites
        assertEquals(listOf("music"), AppsPageModel.filter(all, "playlist").map { it.id })
        assertEquals(listOf("video"), AppsPageModel.filter(all, "  MOVIES ").map { it.id })
        assertEquals(listOf("gw"), AppsPageModel.filter(all, "workers.dev").map { it.id })
        assertEquals(all, AppsPageModel.filter(all, ""))
        assertTrue(AppsPageModel.filter(all, "zzzz-nothing").isEmpty())
    }

    @Test fun lifeAppsAndTheViewsShortcutOpenThePage() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val chat = File(root, "com/blaze/assistant/ui/ChatScreen.kt").readText()
        assertTrue("onApps = { appsOpen = true }" in chat)
        assertTrue("\"views\" -> { appsOpen = true; true }" in chat)
        assertTrue("AppsPage(" in chat && "onProfile = { settingsPage = \"\" }" in chat)
        assertFalse("AppsSheet(" in chat)
    }

    @Test fun installingASiteAddsItToAppsAndHomeIsAnOption() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val panel = File(root, "com/blaze/assistant/browser/SiteAppsPanel.kt").readText()
        assertTrue("\"Add to Apps\"" in panel && "Also add to Home screen" in panel && "home = toHome" in panel)
        val inst = File(root, "com/blaze/assistant/siteapps/SiteInstaller.kt").readText()
        assertTrue("home: Boolean" in inst && "fun pinToHome(" in inst)
    }

    @Test fun backClosesTheAppsPageInTheArgusShell() {
        val s = BackRule.Shell(tab = ChatNav.LIFE, appsPage = true, homeRoot = true, argus = true)
        assertEquals("apps-page", BackRule.front(s))
        assertEquals(BackRule.Action.TO_CHAT, BackRule.action(s))
        // even over Team (the root), an open Apps page is what Back leaves
        assertEquals(BackRule.Action.TO_CHAT, BackRule.action(s.copy(tab = ChatNav.TEAM)))
        // Settings opened from the page's avatar is in front of it
        assertEquals("page:settings", BackRule.front(s.copy(settingsPage = "")))
    }
}
