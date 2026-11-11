package com.blaze.assistant.connect

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4-CN-06: MCP servers per bot, the stricter of server and bot tool modes, taint, stdio inside proot. */
class McpGrantTest {
    private val fs = McpConfig.Server("Files", url = "https://mcp.test/mcp", toolModes = mapOf("read_file" to McpConfig.ALWAYS, "rm" to McpConfig.OFF, "write" to McpConfig.ASK))

    @Test fun managerSeesEveryServerOthersOnlyGrants() {
        val g = emptyMap<String, AppGrants.Grants>()
        assertTrue(McpGrants.serverAllowed(g, null, fs)); assertTrue(McpGrants.serverAllowed(g, "manager", fs))
        assertFalse(McpGrants.serverAllowed(g, "forge", fs))
        val g2 = AppGrants.grant(g, "forge", fs.id, AppGrants.SRC_MCP)
        assertTrue(McpGrants.serverAllowed(g2, "forge", fs))
        // an argus-connect grant with the same id is not an MCP grant
        assertFalse(McpGrants.serverAllowed(AppGrants.grant(g, "forge", fs.id), "forge", fs))
    }

    @Test fun stricterWins() {
        assertEquals(McpConfig.OFF, McpGrants.stricter(McpConfig.ALWAYS, McpConfig.OFF))
        assertEquals(McpConfig.OFF, McpGrants.stricter(McpConfig.OFF, null))
        assertEquals(McpConfig.ASK, McpGrants.stricter(McpConfig.ALWAYS, McpConfig.ASK))
        assertEquals(McpConfig.ALWAYS, McpGrants.stricter(null, McpConfig.ALWAYS))
        assertNull(McpGrants.stricter(null, null))
    }

    @Test fun botRulesNarrowOrLift() {
        var g = AppGrants.grant(emptyMap(), "forge", fs.id, AppGrants.SRC_MCP)
        g = AppGrants.setToolRule(g, "forge", fs.id, "read_file", ConnectRisk.Rule.NEVER, AppGrants.SRC_MCP)
        g = AppGrants.setToolRule(g, "forge", fs.id, "list", ConnectRisk.Rule.ALLOW, AppGrants.SRC_MCP)
        g = AppGrants.setToolRule(g, "forge", fs.id, "write", ConnectRisk.Rule.ALLOW, AppGrants.SRC_MCP)
        assertEquals(McpConfig.OFF, McpGrants.override(g, "forge", fs, "read_file"))   // bot says off
        assertEquals(McpConfig.ALWAYS, McpGrants.override(g, "forge", fs, "list"))     // server default, bot Always
        assertEquals(McpConfig.ASK, McpGrants.override(g, "forge", fs, "write"))       // server Ask beats bot Always
        assertEquals(McpConfig.OFF, McpGrants.override(g, "forge", fs, "rm"))
        // the Manager keeps the server's own modes
        assertEquals(McpConfig.ALWAYS, McpGrants.override(g, null, fs, "read_file"))
        assertNull(McpGrants.override(g, null, fs, "list"))
    }

    @Test fun taintTurnsWritesIntoCards() {
        assertTrue(McpGrants.taintAsks(McpConfig.Risk.ACTION, "an MCP tool's answer"))
        assertTrue(McpGrants.taintAsks(McpConfig.Risk.WRITE, "a web page"))
        assertFalse(McpGrants.taintAsks(McpConfig.Risk.READ, "a web page"))
        assertFalse(McpGrants.taintAsks(McpConfig.Risk.ACTION, null))
        // with Always set, needsApproval says no card; taint brings it back
        assertFalse(McpConfig.needsApproval(McpConfig.ALWAYS, McpConfig.Risk.ACTION, false))
    }

    @Test fun stdioLine() {
        val raw = JSONObject().put("command", "npx").put("args", JSONArray().put("-y").put("@modelcontextprotocol/server-filesystem").put("/root/my files"))
            .put("env", JSONObject().put("TOKEN_X", "a'b").put("bad-key", "x")).put("cwd", "/root")
        assertEquals("cd /root && env TOKEN_X='a'\\''b' exec npx -y @modelcontextprotocol/server-filesystem '/root/my files'", McpGrants.stdioLine(raw))
        assertNull(McpGrants.stdioLine(JSONObject().put("url", "https://x")))
        assertEquals("exec uvx mcp-server-time", McpGrants.stdioLine(JSONObject().put("command", "uvx").put("args", JSONArray().put("mcp-server-time"))))
        assertEquals("''", McpGrants.q(""))
        assertEquals("'a;rm -rf /'", McpGrants.q("a;rm -rf /"))
    }

    @Test fun distro() {
        assertEquals("ubuntu", McpGrants.distroFor(setOf("alpine", "ubuntu")))
        assertEquals("alpine", McpGrants.distroFor(setOf("alpine")))
    }
}
