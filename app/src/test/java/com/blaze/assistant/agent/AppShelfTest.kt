package com.blaze.assistant.agent

import com.blaze.assistant.connect.ArgusAppsModel.App
import com.blaze.assistant.team.ActionClass
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-21 app shelf (docs/APP-SHELF.md): the account's connected apps, one line each, every turn; the model uses them. */
class AppShelfTest {

    private fun app(id: String, name: String, desc: String, connected: Boolean = false, none: Boolean = false, enabled: Boolean = true) =
        App(id = id, name = name, category = "Productivity", description = desc, none = none, enabled = enabled, connected = connected)

    private val apps = listOf(
        app("notion", "Notion", "Search, read and write Notion pages, blocks and databases.", connected = true),
        app("google_calendar", "Google Calendar", "Events on your Google calendars. Create, move and delete events.", connected = true),
        app("trello", "Trello", "Trello boards, lists and cards."),
        app("hackernews", "Hacker News", "Top stories and comments.", none = true),
        app("wikipedia", "Wikipedia", "Search and read Wikipedia.", none = true),
        app("todoist", "Todoist", "Todoist projects and tasks.", connected = true, enabled = false),
    )

    @Test fun connectedAppsGetOneLineEachSortedAndStable() {
        val shelf = AppShelf.promptBlock(apps, signedIn = true)
        assertTrue(shelf.startsWith(AppShelf.HEAD))
        val lines = shelf.lines().filter { it.startsWith("- ") }
        assertEquals(listOf("- google_calendar \u2014 Events on your Google calendars", "- notion \u2014 Search, read and write Notion pages, blocks and databases"), lines)
        assertTrue("keyless apps are named on one line", shelf.contains("Keyless, always on: hackernews, wikipedia.\n"))
        assertFalse("an app the user switched off isn't on the shelf", shelf.contains("todoist"))
        assertFalse("an app that isn't connected isn't on the shelf", shelf.contains("trello"))
        assertTrue(shelf.endsWith(AppShelf.TAIL))
        assertEquals("same list, same bytes (cached prefix)", shelf, AppShelf.promptBlock(apps.reversed(), signedIn = true))
    }

    @Test fun signedOutHasNoShelfAndNothingConnectedSaysSo() {
        assertEquals("", AppShelf.promptBlock(apps, signedIn = false))
        val none = AppShelf.promptBlock(apps.filter { !it.connected }, signedIn = true)
        assertTrue(none.startsWith(AppShelf.NONE))
        assertTrue("the model can still offer to connect one", none.contains("request_app_connection"))
    }

    @Test fun theShelfStaysSmallWithManyApps() {
        val many = (1..80).map { app("app$it", "App $it", "A very long description of what app number $it does, with plenty of words to cut. Second sentence.", connected = true) }
        val shelf = AppShelf.promptBlock(many, signedIn = true)
        assertTrue("${shelf.length}", shelf.length <= AppShelf.BUDGET_CHARS)
        assertEquals(AppShelf.MAX_APPS + 1, shelf.lines().count { it.startsWith("- ") })
        assertTrue(shelf.lines().filter { it.startsWith("- app") }.all { it.length <= AppShelf.LINE_CHARS + 20 })
    }

    @Test fun theShelfNeverReadsTheMessage() {
        // the shelf is a fact about the account; nothing here looks at what the user typed
        val root = listOf(java.io.File("."), java.io.File("..")).first { java.io.File(it, "docs").isDirectory && java.io.File(it, "app").isDirectory }
        val src = java.io.File(root, "app/src/main/java/" + AppShelf::class.java.name.replace('.', '/') + ".kt").readText()
        assertFalse(src.contains("userText"))
    }

    @Test fun twoToolsWithPlainSchemas() {
        val defs = AppShelf.toolDefs().map { it.getJSONObject("function") }
        assertEquals(listOf(AppShelf.LIST, AppShelf.RUN), defs.map { it.getString("name") })
        assertEquals("tool", defs[1].getJSONObject("parameters").getJSONArray("required").getString(0))
        for (d in defs) assertFalse("no shouting in tool text", Regex("\\b(ALWAYS|NEVER|MUST)\\b").containsMatchIn(d.getString("description")))
        AppShelf.live = false
        assertTrue(AppShelf.turnNames().isEmpty())
        AppShelf.live = true
        assertEquals(AppShelf.NAMES, AppShelf.turnNames())
        AppShelf.live = false
    }

    @Test fun actionsFromMcpToolsListAreRenderedWithArgsAndReadsOrChanges() {
        val result = JSONObject("""{"tools":[
            {"name":"notion_search","description":"[Notion] Search pages and databases.","inputSchema":{"type":"object","properties":{"query":{"type":"string","description":"Words to find"},"page_size":{"type":"integer"}},"required":["query"]},"annotations":{"readOnlyHint":true}},
            {"name":"notion_create_page","description":"[Notion] Create a page.","inputSchema":{"type":"object","properties":{"parent":{"type":"object"},"kind":{"type":"string","enum":["page","database"]}},"required":["parent"]},"annotations":{"readOnlyHint":false}},
            {"name":"argus_list_skills","description":"skills","inputSchema":{"type":"object"}}
        ]}""")
        val list = AppShelf.parseActions(result)
        assertEquals(listOf("notion_search", "notion_create_page"), list.map { it.name })
        assertEquals("Search pages and databases.", list[0].description)
        val text = AppShelf.renderActions("notion", list)
        assertTrue(text, text.contains("notion_search [reads] Search pages and databases"))
        assertTrue(text, text.contains("  query*: string \u2014 Words to find"))
        assertTrue(text, text.contains("  page_size: integer"))
        assertTrue(text, text.contains("notion_create_page [changes]"))
        assertTrue(text, text.contains("  kind: one of page, database"))
    }

    @Test fun theActionClassComesFromTheCatalogueThenTheActionsOwnName() {
        assertEquals(ActionClass.READ, AppShelf.classOf("gmail_list_messages", true, "gmail"))
        assertEquals(ActionClass.SEND, AppShelf.classOf("gmail_send_draft", false, "gmail"))
        assertEquals(ActionClass.SEND, AppShelf.classOf("slack_post_message", false, "slack"))
        assertEquals(ActionClass.DELETE, AppShelf.classOf("google_calendar_delete_event", false, "google_calendar"))
        assertEquals(ActionClass.DELETE, AppShelf.classOf("gmail_trash_message", false, "gmail"))
        assertEquals(ActionClass.WRITE_REMOTE, AppShelf.classOf("notion_create_page", false, "notion"))
        assertEquals("a modify isn't a send", ActionClass.WRITE_REMOTE, AppShelf.classOf("gmail_modify_message", false, "gmail"))
        assertEquals("not read yet: by its name", ActionClass.SEND, AppShelf.classOf("discord_send_message", null, "discord"))
        assertEquals("the app's own id never counts as the verb", ActionClass.WRITE_REMOTE, AppShelf.classOf("share_x_create", false, "share_x"))
    }

    @Test fun appOfPicksTheLongestPrefix() {
        val ids = listOf("google", "google_calendar", "gmail")
        assertEquals("google_calendar", AppShelf.appOf("google_calendar_list_events", ids))
        assertEquals("gmail", AppShelf.appOf("gmail_send_draft", ids))
        assertNull(AppShelf.appOf("notion_search", ids))
        assertNull(AppShelf.appOf("gmail_", ids))
    }

    @Test fun resultsAreFactsTheModelCanActOn() {
        assertEquals("{\"id\": \"p1\"}".replace(" ", ""), AppShelf.renderResult("notion_create_page", "notion", 200, """{"ok":true,"data":{"id":"p1"}}""").replace(Regex("\\s"), ""))
        assertTrue(AppShelf.renderResult("notion_search", "notion", 401, """{"ok":false,"error":{"code":"not_connected","message":"x"}}""").contains("request_app_connection"))
        assertTrue(AppShelf.renderResult("notion_fly", "notion", 404, """{"ok":false,"error":{"code":"unknown_tool","message":"x"}}""").contains("app_tools notion"))
        assertTrue(AppShelf.renderResult("x_y", null, 400, """{"ok":false,"error":{"code":"upstream","message":"rate limited"}}""").endsWith("rate limited"))
        val big = "{\"ok\":true,\"data\":\"" + "a".repeat(20_000) + "\"}"
        assertTrue(AppShelf.renderResult("x_y", null, 200, big).length < AppShelf.RESULT_CHARS + 200)
    }

    @Test fun theCatalogueListsReadyAppsFirst() {
        val text = AppShelf.renderCatalogue(apps)
        assertTrue(text, text.indexOf("Ready to use") < text.indexOf("Not connected"))
        assertTrue(text.contains("- trello (Trello, Productivity)"))
        assertTrue(text.contains("- hackernews \u2014"))
    }
}
