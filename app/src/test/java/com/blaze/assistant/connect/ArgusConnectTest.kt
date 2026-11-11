package com.blaze.assistant.connect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P4-CN-01: the argus-connect user API against a mock server. Shapes from argus-connect 0.2.0 (src/user.ts handleMe,
 * src/admin.ts appSummary, src/mcp.ts openaiTools / openaiExecute; HANDOFF-connectors.md).
 */
class ArgusConnectTest {
    private class Call(val method: String, val url: String, val body: String?, val headers: Map<String, String>)

    private class Mock {
        val calls = mutableListOf<Call>()
        var token = "bz_user_a"
        var now = 1_000_000L
        var responder: (Call) -> Pair<Int, String> = { 200 to "{}" }
        val api = ArgusConnectApi(base = { "https://connect.test/" }, token = { token },
            transport = { m, u, b, h -> Call(m, u, b, h).also { calls += it }.let(responder) }, now = { now })
    }

    private val appsJson = JSONObject().put("apps", JSONArray()
        .put(JSONObject().put("id", "github").put("name", "GitHub").put("category", "Developer").put("description", "Repos and issues")
            .put("setup", "Create a token").put("free", true)
            .put("auth", JSONObject().put("oauth", JSONObject().put("provider", "github").put("scopes", JSONArray()).put("ready", true))
                .put("token", JSONObject().put("label", "Personal access token").put("optional", false))
                .put("fields", JSONArray()).put("none", false))
            .put("test", "list_repos")
            .put("tools", JSONArray()
                .put(JSONObject().put("name", "list_repos").put("full", "github_list_repos").put("description", "List repos").put("readOnly", true))
                .put(JSONObject().put("name", "create_issue").put("full", "github_create_issue").put("description", "Open an issue").put("readOnly", false)))
            .put("enabled", true).put("disabled_tools", JSONArray().put("create_issue")).put("connected", true)
            .put("connection", JSONObject().put("mode", "token").put("meta", JSONObject().put("via", "pasted").put("token_hint", "…ab12")).put("updated_at", 1700)))
        .put(JSONObject().put("id", "weather").put("name", "Weather").put("category", "Public")
            .put("auth", JSONObject().put("oauth", JSONObject.NULL).put("token", JSONObject.NULL).put("fields", JSONArray()).put("none", true))
            .put("tools", JSONArray().put(JSONObject().put("name", "forecast").put("full", "weather_forecast").put("readOnly", true)))
            .put("enabled", true).put("disabled_tools", JSONArray()).put("connected", false).put("connection", JSONObject.NULL)))

    @Test fun everyCallCarriesOnlyTheSessionBearer() {
        val m = Mock(); m.responder = { 200 to appsJson.toString() }
        m.api.apps()
        val c = m.calls.single()
        assertEquals("GET", c.method)
        assertEquals("https://connect.test/v1/me/apps", c.url)
        assertEquals("Bearer bz_user_a", c.headers["Authorization"])
        assertEquals(setOf("Authorization", "Accept"), c.headers.keys)
    }

    @Test fun signedOutMakesNoCallAndNoTools() {
        val m = Mock(); m.token = ""
        assertTrue(m.api.tools().isEmpty())
        try { m.api.apps(); fail() } catch (e: ArgusConnectApi.ConnectError) { assertEquals("signed_out", e.code) }
        assertTrue(m.calls.isEmpty())
        assertFalse(m.api.signedIn())
    }

    @Test fun parsesAppsWithoutSecrets() {
        val apps = ArgusConnectApi.parseApps(appsJson)
        assertEquals(2, apps.size)
        val gh = apps[0]
        assertEquals("GitHub", gh.name); assertEquals("Developer", gh.category)
        assertTrue(gh.auth.oauth); assertTrue(gh.auth.oauthReady); assertTrue(gh.auth.byKey)
        assertEquals("Personal access token", gh.auth.tokenLabel)
        assertEquals(setOf("create_issue"), gh.disabledTools)
        assertTrue(gh.connected); assertTrue(gh.ready)
        assertEquals("pasted", gh.via); assertEquals("…ab12", gh.tokenHint)
        assertEquals(listOf("github_list_repos", "github_create_issue"), gh.tools.map { it.full })
        assertTrue(gh.tools[0].readOnly); assertFalse(gh.tools[1].readOnly)
        val w = apps[1]
        assertTrue(w.auth.none); assertFalse(w.connected); assertTrue(w.ready); assertFalse(w.needsConnect)
        assertNull(w.auth.oauthProvider); assertNull(w.auth.tokenLabel)
    }

    @Test fun toolsAreCachedFiveMinutesPerToken() {
        val m = Mock()
        m.responder = { 200 to JSONObject().put("tools", JSONArray().put(JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", "github_list_repos").put("parameters", JSONObject())))).toString() }
        assertEquals(1, m.api.tools().size)
        m.now += ArgusConnectApi.CACHE_MS - 1
        assertEquals(1, m.api.tools().size)
        assertEquals(1, m.calls.size)
        assertEquals(1, m.api.cachedTools()?.size)
        m.now += 2
        assertNull(m.api.cachedTools())
        m.api.tools()
        assertEquals(2, m.calls.size)
        // another account never sees the last account's cache
        m.token = "bz_user_b"
        assertNull(m.api.cachedTools())
        m.api.tools()
        assertEquals(3, m.calls.size)
        assertEquals("Bearer bz_user_b", m.calls.last().headers["Authorization"])
        // force skips the cache
        m.api.tools(force = true); assertEquals(4, m.calls.size)
    }

    @Test fun changesDropTheToolCache() {
        val m = Mock()
        m.responder = { c -> 200 to (if (c.url.endsWith("/tools")) JSONObject().put("tools", JSONArray()).toString() else "{\"ok\":true}") }
        m.api.tools(); m.api.setApp("github", enabled = false)
        assertNull(m.api.cachedTools())
        val put = m.calls.last()
        assertEquals("PUT", put.method); assertEquals("https://connect.test/v1/me/apps/github", put.url)
        assertEquals(false, JSONObject(put.body!!).getBoolean("enabled"))
        m.api.tools(); m.api.connectWithKey("github", " ghp_x ", mapOf("org" to " acme ", "blank" to " "))
        val body = JSONObject(m.calls.last().body!!)
        assertEquals("ghp_x", body.getString("token")); assertEquals("acme", body.getJSONObject("fields").getString("org"))
        assertFalse(body.getJSONObject("fields").has("blank"))
        assertNull(m.api.cachedTools())
        m.api.tools(); m.api.disconnect("github")
        assertEquals("DELETE", m.calls.last().method); assertTrue(m.calls.last().url.endsWith("/v1/me/apps/github/connection"))
        assertNull(m.api.cachedTools())
    }

    @Test fun oauthStartSendsTheLoopbackReturn() {
        val m = Mock(); m.responder = { 200 to "{\"url\":\"https://github.com/login/oauth/authorize?x=1\"}" }
        assertEquals("https://github.com/login/oauth/authorize?x=1", m.api.oauthStart("github", "http://127.0.0.1:43118/connected"))
        assertEquals("http://127.0.0.1:43118/connected", JSONObject(m.calls.single().body!!).getString("return_to"))
        assertTrue(m.calls.single().url.endsWith("/v1/me/apps/github/oauth/start"))
    }

    @Test fun executeCapsOutputAt12k() {
        val m = Mock(); val big = "x".repeat(20_000)
        m.responder = { 200 to JSONObject().put("ok", true).put("data", big).toString() }
        val r = m.api.execute("github_list_repos", JSONObject().put("per_page", 5))
        assertTrue(r.ok)
        assertTrue(r.text.startsWith("x".repeat(ArgusConnectApi.MAX_OUTPUT)))
        assertTrue(r.text.length < ArgusConnectApi.MAX_OUTPUT + 200)
        assertTrue(r.text.contains("cut"))
        val sent = JSONObject(m.calls.single().body!!)
        assertEquals("github_list_repos", sent.getString("name")); assertEquals(5, sent.getJSONObject("arguments").getInt("per_page"))
    }

    @Test fun executeErrorsComeBackInPlainWords() {
        val m = Mock()
        m.responder = { 401 to JSONObject().put("ok", false).put("error", JSONObject().put("code", "not_connected").put("message", "GitHub is not connected")).toString() }
        val r = m.api.execute("github_list_repos", JSONObject())
        assertFalse(r.ok); assertEquals("not_connected", r.code)
        assertTrue(r.text.contains("GitHub is not connected")); assertTrue(r.text.contains("Apps & connectors"))
        m.responder = { 200 to JSONObject().put("ok", false).put("error", JSONObject().put("code", "upstream").put("message", "rate limited")).toString() }
        assertEquals("rate limited", m.api.execute("github_list_repos", JSONObject()).text)
        m.responder = { 200 to "{\"ok\":true,\"data\":{\"n\":1}}" }
        assertEquals("{\"n\":1}", m.api.execute("github_list_repos", JSONObject()).text)
    }

    @Test fun unreachableIsAConnectError() {
        val m = Mock(); m.responder = { throw java.io.IOException("timeout") }
        try { m.api.apps(); fail() } catch (e: ArgusConnectApi.ConnectError) { assertEquals(0, e.status); assertEquals("unreachable", e.code) }
        assertFalse(m.api.execute("a_b", JSONObject()).ok)
    }

    @Test fun serverErrorsKeepTheirCode() {
        val m = Mock(); m.responder = { 404 to "{\"error\":{\"code\":\"not_found\",\"message\":\"This app is not offered\"}}" }
        try { m.api.setApp("nope", enabled = true); fail() } catch (e: ArgusConnectApi.ConnectError) {
            assertEquals(404, e.status); assertEquals("not_found", e.code); assertEquals("This app is not offered", e.message) }
    }

    @Test fun sessionEndClearsTheCache() {
        val m = Mock()
        m.responder = { 200 to "{\"tools\":[]}" }; m.api.tools()
        m.responder = { 401 to "{\"error\":\"unauthorized\"}" }
        try { m.api.apps(); fail() } catch (e: ArgusConnectApi.ConnectError) { assertEquals(401, e.status); assertEquals("unauthorized", e.code) }
        assertNull(m.api.cachedTools())
    }

    @Test fun skillsMineAndOriginals() {
        val m = Mock()
        m.responder = { 200 to JSONObject()
            .put("mine", JSONArray().put(JSONObject().put("slug", "standup").put("name", "Standup").put("description", "d").put("content", "# c")
                .put("apps", JSONArray().put("slack")).put("enabled", false).put("updated_at", 5)))
            .put("originals", JSONArray().put(JSONObject().put("slug", "morning-brief").put("name", "Morning brief").put("content", "x"))).toString() }
        val (mine, orig) = m.api.skills()
        assertEquals("standup", mine.single().slug); assertFalse(mine.single().enabled); assertEquals(listOf("slack"), mine.single().apps)
        assertFalse(mine.single().original)
        assertTrue(orig.single().original); assertTrue(orig.single().enabled)
        m.responder = { 200 to "{\"ok\":true}" }
        m.api.putSkill(mine.single().copy(enabled = true))
        val put = m.calls.last(); assertEquals("PUT", put.method); assertTrue(put.url.endsWith("/v1/me/skills/standup"))
        assertTrue(JSONObject(put.body!!).getBoolean("enabled"))
        try { m.api.putSkill(mine.single().copy(content = "x".repeat(100_001))); fail() } catch (_: IllegalArgumentException) { }
        m.responder = { 200 to "{\"name\":\"Standup\",\"description\":\"Daily standup\",\"content\":\"## Steps\"}" }
        val p = m.api.polish("standup", "", "do standup")
        assertEquals("Daily standup", p.description); assertEquals("## Steps", p.content)
    }

    @Test fun pathSegmentsAreEncoded() {
        val m = Mock(); m.responder = { 200 to "{}" }
        m.api.deleteSkill("a b")
        assertTrue(m.calls.single().url.endsWith("/v1/me/skills/a%20b"))
    }
}
