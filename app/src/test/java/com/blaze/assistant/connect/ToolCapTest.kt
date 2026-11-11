package com.blaze.assistant.connect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-CN-03: argus-connect tools in the prompt: per-bot filter, the 64-schema cap and its order, connectors_list. */
class ToolCapTest {
    private fun def(n: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", n).put("description", "d $n").put("parameters", JSONObject()))
    private fun app(id: String, n: Int, connected: Boolean = true, none: Boolean = false, readOnlyEvery: Int = 2) = ArgusConnectApi.AppInfo(
        id = id, name = id.replaceFirstChar { it.uppercase() }, category = "Cat", description = "", setup = "", free = true,
        auth = ArgusConnectApi.Auth(none, if (none) null else "Key", false, emptyList(), null, false), test = null,
        tools = (0 until n).map { ArgusConnectApi.ToolInfo("t$it", "${id}_t$it", "tool $it", it % readOnlyEvery == 0) },
        enabled = true, disabledTools = emptySet(), connected = connected, via = null, tokenHint = null, updatedAt = null)
    private fun names(l: List<JSONObject>) = l.map { it.getJSONObject("function").getString("name") }

    @Test fun capIsSixtyFourAndTheRestIsCounted() {
        val apps = listOf(app("github", 40), app("notion", 40), app("slack", 40))
        val meta = ConnectToolsCore.metaOf(apps)
        val server = apps.flatMap { a -> a.tools.map { def(it.full) } }
        val (kept, left) = ConnectToolsCore.select(server, meta, { true })
        assertEquals(64, kept.size); assertEquals(120 - 64, left)
        assertTrue(names(kept).all { it.startsWith("app__") })
        assertEquals(64, names(kept).toSet().size)
    }

    @Test fun prefixKeepsSchemasAndDoesNotTouchTheInput() {
        val apps = listOf(app("github", 1))
        val server = listOf(def("github_t0"))
        val (kept, _) = ConnectToolsCore.select(server, ConnectToolsCore.metaOf(apps), { true })
        assertEquals("app__github_t0", names(kept).single())
        assertEquals("d github_t0", kept.single().getJSONObject("function").getString("description"))
        assertEquals("github_t0", server.single().getJSONObject("function").getString("name"))
        assertEquals("github_t0", ConnectToolsCore.serverName("app__github_t0"))
        assertTrue(ConnectToolsCore.handles("app__github_t0")); assertTrue(ConnectToolsCore.handles("connectors_list"))
        assertFalse(ConnectToolsCore.handles("github_list_repos")); assertFalse(ConnectToolsCore.handles("mcp__x__y"))
    }

    @Test fun botWithoutTheGrantSeesNothingOfThatApp() {
        val apps = listOf(app("github", 3), app("notion", 3))
        val meta = ConnectToolsCore.metaOf(apps)
        val server = apps.flatMap { a -> a.tools.map { def(it.full) } }
        val grants = AppGrants.grant(emptyMap(), "atlas", "notion")
        val (kept, _) = ConnectToolsCore.select(server, meta, { AppGrants.allows(grants, "atlas", it) })
        assertTrue(names(kept).all { it.startsWith("app__notion_") }); assertEquals(3, kept.size)
        val (none, _) = ConnectToolsCore.select(server, meta, { AppGrants.allows(grants, "pulse", it) })
        assertTrue(none.isEmpty())
        val (mgr, _) = ConnectToolsCore.select(server, meta, { AppGrants.allows(grants, null, it) })
        assertEquals(6, mgr.size)
    }

    @Test fun orderLoadedThenCuedThenConnectedThenReadsFirst() {
        val apps = listOf(app("aaa", 40, connected = false), app("bbb", 40), app("ccc", 40))
        val meta = ConnectToolsCore.metaOf(apps)
        val server = apps.flatMap { a -> a.tools.map { def(it.full) } }
        // nothing named: connected apps first (bbb, ccc), reads before writes inside the rank
        val (k1, _) = ConnectToolsCore.select(server, meta, { true })
        assertTrue(names(k1).none { it.startsWith("app__aaa_") })
        assertEquals("app__bbb_t0", names(k1).first())
        // the request names ccc → ccc first
        val (k2, _) = ConnectToolsCore.select(server, meta, { true }, cued = setOf("ccc"))
        assertTrue(names(k2).take(40).all { it.startsWith("app__ccc_") })
        // the model loaded aaa → aaa first even though it isn't connected
        val (k3, _) = ConnectToolsCore.select(server, meta, { true }, cued = setOf("ccc"), loaded = setOf("aaa"))
        assertTrue(names(k3).take(40).all { it.startsWith("app__aaa_") })
    }

    @Test fun unknownToolsArePlacedByLongestAppPrefix() {
        val apps = listOf(app("google", 1), app("google_drive", 1))
        val meta = ConnectToolsCore.metaOf(apps)
        assertEquals("google_drive", ConnectToolsCore.appOf("google_drive_upload", meta, setOf("google", "google_drive")))
        assertEquals("google", ConnectToolsCore.appOf("google_x", meta, setOf("google", "google_drive")))
        assertEquals(null, ConnectToolsCore.appOf("zzz_x", meta, setOf("google")))
        // a server tool no app claims is never sent
        val (kept, _) = ConnectToolsCore.select(listOf(def("zzz_x")), meta, { true })
        assertTrue(kept.isEmpty())
    }

    @Test fun cuedAppsByWord() {
        val apps = listOf(app("github", 1), app("google_calendar", 1).copy(name = "Google Calendar"), app("x", 1))
        assertEquals(setOf("github"), ConnectToolsCore.cuedApps("open an issue on GitHub please", apps))
        assertEquals(setOf("google_calendar"), ConnectToolsCore.cuedApps("what's on my google calendar", apps))
        assertTrue(ConnectToolsCore.cuedApps("githubby", apps).isEmpty())
        assertTrue(ConnectToolsCore.cuedApps("x marks the spot", apps).isEmpty())   // ids under 3 letters never cue
    }

    @Test fun connectorsListText() {
        val apps = listOf(app("github", 2), app("notion", 2, connected = false), app("weather", 1, connected = false, none = true))
        val all = ConnectToolsCore.listText(apps, { it != "notion" }, setOf("github"), null)
        assertTrue(all.contains("✓ Github")); assertTrue(all.contains("tools loaded"))
        assertTrue(all.contains("○ Notion")); assertTrue(all.contains("not allowed for this bot"))
        assertTrue(all.contains("✓ Weather"))
        val one = ConnectToolsCore.listText(apps, { true }, emptySet(), "github")
        assertTrue(one.contains("app__github_t0")); assertTrue(one.contains("(read)"))
        assertTrue(ConnectToolsCore.listText(apps, { true }, emptySet(), "notion").contains("isn't connected"))
        assertTrue(ConnectToolsCore.listText(apps, { false }, emptySet(), "github").contains("isn't allowed"))
        assertTrue(ConnectToolsCore.listText(emptyList(), { true }, emptySet(), null).contains("Settings › Apps & connectors"))
    }

    @Test fun promptForbidsShellAndTokens() {
        val p = ConnectToolsCore.PROMPT.lowercase()
        listOf("shell", "curl", "environment", "token").forEach { assertTrue(it, p.contains(it)) }
    }
}
