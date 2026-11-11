package com.blaze.assistant.connect

import org.json.JSONObject

/**
 * P4-CN-06: MCP servers per bot. Pure (McpGrantTest).
 *
 * - A server is an app with source `mcp` and app id = the server's slug ([McpConfig.slug]) in [AppGrants]: the Manager
 *   and the plain chat use every enabled server; any other bot only the servers granted to it.
 * - The per-tool mode is the stricter of the server's own Ask / Always / Off (Settings › MCP servers) and the bot's
 *   grant rule: Off anywhere is Off; Ask anywhere beats Always; Always only where neither side says Ask.
 *   An Always on the grant lifts a tool the server left on its default (Ask) for that bot.
 * - MCP output is untrusted (ConfirmGate.sourceOf `mcp__`); after the turn read untrusted content, anything but a
 *   read-only tool shows its card even when set to Always ([taintAsks], 05 §7 / K6).
 */
object McpGrants {
    fun serverAllowed(all: Map<String, AppGrants.Grants>, botId: String?, server: McpConfig.Server) =
        AppGrants.allows(all, botId, server.id, AppGrants.SRC_MCP)

    /** The override to use for [tool] on [server] for [botId] (null = the risk's default, like an unset server mode). */
    fun override(all: Map<String, AppGrants.Grants>, botId: String?, server: McpConfig.Server, tool: String): String? {
        val serverMode = server.toolModes[tool]?.takeIf { it == McpConfig.ASK || it == McpConfig.ALWAYS || it == McpConfig.OFF }
        val rule = AppGrants.grantFor(all, botId, server.id, AppGrants.SRC_MCP)?.rule(tool)?.wire()
        return stricter(serverMode, rule)
    }

    fun stricter(a: String?, b: String?): String? = when {
        a == McpConfig.OFF || b == McpConfig.OFF -> McpConfig.OFF
        a == McpConfig.ASK || b == McpConfig.ASK -> McpConfig.ASK
        a == McpConfig.ALWAYS || b == McpConfig.ALWAYS -> McpConfig.ALWAYS
        else -> null
    }

    /** True when a tainted turn must show the card although the mode said Always. */
    fun taintAsks(risk: McpConfig.Risk, taintSource: String?) = taintSource != null && risk != McpConfig.Risk.READ

    // ---------------------------------------------------------------- stdio inside proot (optional)

    /** Setting that lets stdio MCP servers run inside the phone's Linux (proot). Off by default. */
    const val PREF_STDIO = "mcp_stdio_proot"

    /**
     * The shell line that starts a stdio server from its mcp.json entry (`command`, `args`, `env`, `cwd`), quoted for
     * /bin/sh -lc inside proot. Null when the entry has no command. Env keys must be shell names.
     */
    fun stdioLine(raw: JSONObject): String? {
        val cmd = raw.optString("command").trim().ifBlank { return null }
        val args = raw.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val env = raw.optJSONObject("env")?.let { e -> e.keys().asSequence().filter { Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(it) }.map { it to e.optString(it) }.toList() }.orEmpty()
        val cwd = raw.optString("cwd").trim()
        val sb = StringBuilder()
        if (cwd.isNotBlank()) sb.append("cd ").append(q(cwd)).append(" && ")
        if (env.isNotEmpty()) sb.append("env ").append(env.joinToString(" ") { (k, v) -> "$k=" + q(v) }).append(' ')
        sb.append("exec ").append(q(cmd))
        args.forEach { sb.append(' ').append(q(it)) }
        return sb.toString()
    }

    /** POSIX single-quote. */
    fun q(s: String): String = if (s.isNotEmpty() && Regex("^[A-Za-z0-9_@%+=:,./-]+$").matches(s)) s else "'" + s.replace("'", "'\\''") + "'"

    /** Which Linux runs it: Ubuntu when installed (Node/Python live there), else Alpine. */
    fun distroFor(installed: Set<String>): String = if ("ubuntu" in installed) "ubuntu" else "alpine"
}
