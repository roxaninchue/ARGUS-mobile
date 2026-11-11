package com.blaze.assistant.connect

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-CN-03/04: per-bot app grants (05 BotAppGrant, team.db bot_apps). */
class AppGrantsTest {
    @Test fun managerSeesEverythingOthersOnlyGrants() {
        val g = emptyMap<String, AppGrants.Grants>()
        assertTrue(AppGrants.allows(g, null, "github")); assertTrue(AppGrants.allows(g, "manager", "github"))
        assertFalse(AppGrants.allows(g, "atlas", "github"))
        val g2 = AppGrants.grant(g, "atlas", "github")
        assertTrue(AppGrants.allows(g2, "atlas", "github")); assertFalse(AppGrants.allows(g2, "atlas", "notion"))
        assertFalse(AppGrants.allows(g2, "atlas", "github", AppGrants.SRC_MCP))   // source matters
    }

    @Test fun restrictedManager() {
        val g = mapOf("manager" to AppGrants.Grants("manager", listOf(AppGrants.Grant("slack")), managerRestricted = true))
        assertTrue(AppGrants.allows(g, null, "slack")); assertFalse(AppGrants.allows(g, null, "github"))
    }

    @Test fun roundTripMatchesBotAppsShape() {
        var g = AppGrants.grant(emptyMap(), "atlas", "github")
        g = AppGrants.setToolRule(g, "atlas", "github", "create_issue", ConnectRisk.Rule.ALLOW)
        g = AppGrants.setToolRule(g, "atlas", "fs", "write_file", ConnectRisk.Rule.ASK, AppGrants.SRC_MCP)
        val text = AppGrants.encode(g)
        val back = AppGrants.parse(text)
        assertEquals(g, back)
        val o = JSONObject(text).getJSONObject("atlas").getJSONArray("apps").getJSONObject(0)
        assertEquals("github", o.getString("app_id")); assertEquals("argus-connect", o.getString("source"))
        assertEquals("always", o.getJSONObject("tools").getString("create_issue"))
        assertEquals(ConnectRisk.Rule.ALLOW, AppGrants.grantFor(back, "atlas", "github")!!.rule("create_issue"))
        assertEquals(ConnectRisk.Rule.ASK, AppGrants.grantFor(back, "atlas", "fs", AppGrants.SRC_MCP)!!.rule("write_file"))
    }

    @Test fun clearingARuleAndRevoking() {
        var g = AppGrants.setToolRule(emptyMap(), "atlas", "github", "x", ConnectRisk.Rule.NEVER)
        g = AppGrants.setToolRule(g, "atlas", "github", "x", null)
        assertNull(AppGrants.grantFor(g, "atlas", "github")!!.rule("x"))
        g = AppGrants.revoke(g, "atlas", "github")
        assertFalse(AppGrants.allows(g, "atlas", "github"))
        assertTrue(AppGrants.dropBot(AppGrants.grant(g, "pulse", "a"), "pulse")["pulse"] == null)
        assertEquals(1, AppGrants.of(AppGrants.grant(AppGrants.grant(g, "a1", "x"), "a1", "x"), "a1").grants.size)   // no duplicates
    }

    @Test fun garbageIsEmptyAndBadRulesAreDropped() {
        assertTrue(AppGrants.parse("not json").isEmpty()); assertTrue(AppGrants.parse("").isEmpty())
        val g = AppGrants.parse("{\"atlas\":{\"apps\":[{\"app_id\":\"github\",\"tools\":{\"a\":\"maybe\",\"b\":\"off\"}},{\"tools\":{}}]}}")
        val gr = AppGrants.of(g, "atlas").grants.single()
        assertEquals(mapOf("b" to "off"), gr.tools); assertEquals(AppGrants.SRC_CONNECT, gr.source)
    }
}
