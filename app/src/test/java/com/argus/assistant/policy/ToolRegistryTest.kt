package com.blaze.assistant.policy

import com.blaze.assistant.team.ActionClass
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-SF-01: every tool has a declared ActionClass. The app builds its tool list at run time from ~60 places (some need
 * an Android Context), so this test reads the tool definitions from the source the same way for all of them: every
 * `fn("name", …)` / `def("name", …)` / `put("name", "…")` / `"name":"…"` / `put("name", CONST)` in app/src/main/java.
 * A new tool without a declaration in ToolRegistry fails here. (ToolRegistryCatalogTest checks the tool list the agent
 * really builds, under Gradle.)
 */
class ToolRegistryTest {
    private val src = listOf(File("app/src/main/java"), File("src/main/java")).first { it.isDirectory }.resolve("com/blaze/assistant")

    /** Strings the patterns catch that are not agent tools: form-field names (CallScan / CredAsk) and Needle / text-call examples. */
    private val notTools = setOf("email", "password", "username", "pin", "add_agent", "add_task", "complete_task", "reschedule_task",
        "clear", "remove", "search", "x")

    /** Declared names that no source defines on purpose: FastLane's on-device "toggle_setting" alias of device_setting. */
    private val aliases = setOf("toggle_setting")

    private fun scan(): Set<String> {
        val files = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
        val consts = HashMap<String, MutableSet<String>>()
        for (s in files) for (m in Regex("""const val (\w+)\s*=\s*"([a-z][a-z0-9_]*)"""").findAll(s)) consts.getOrPut(m.groupValues[1]) { HashSet() } += m.groupValues[2]
        val out = HashSet<String>()
        for (s in files) {
            Regex("""\b(?:fn|def)\(\s*"([a-z][a-z0-9_]*)"\s*,""").findAll(s).forEach { out += it.groupValues[1] }
            Regex("""put\("name",\s*"([a-z][a-z0-9_]*)"\)""").findAll(s).forEach { out += it.groupValues[1] }
            Regex(""""name"\s*:\s*"([a-z][a-z0-9_]*)"""").findAll(s).forEach { out += it.groupValues[1] }
            (Regex("""put\("name",\s*(?:\w+\.)?([A-Z][A-Z0-9_]*)\)""").findAll(s) + Regex("""\b(?:fn|def)\(\s*(?:\w+\.)?([A-Z][A-Z0-9_]*)\s*,""").findAll(s))
                .forEach { m -> consts[m.groupValues[1]]?.let { out += it } }
        }
        return out - notTools
    }

    @Test fun everyToolDefinedInTheAppHasAClass() {
        val found = scan()
        assertTrue("the scan finds the tools (${found.size})", found.size > 200)
        for (known in listOf("send_sms", "run_shell", "web_search", "get_weather", "request_secret", "agent_delete", "gmail_send", "tap"))
            assertTrue("scan misses $known", known in found)
        val missing = found.filter { !ToolRegistry.declared(it) }.sorted()
        assertTrue("tools without an ActionClass (declare them in policy/ActionClass.kt ToolRegistry): $missing", missing.isEmpty())
    }

    @Test fun noStaleDeclarations() {
        val stale = (ToolRegistry.names - scan() - aliases).sorted()
        assertTrue("declared but not defined anywhere: $stale", stale.isEmpty())
    }

    @Test fun planModeToolsAreReads() {
        // Modes.planTools (read from the source: Modes needs Settings): what Plan allows must be a READ for the policy too,
        // or its read-only action is. Plan refusing at dispatch then means the same thing in both places.
        val modes = src.resolve("agent/Modes.kt").readText()
        val block = modes.substringAfter("val planTools = setOf(").substringBefore("\n    )")
        val names = Regex(""""([a-z][a-z0-9_]*)"""").findAll(block).map { it.groupValues[1] }.toSet()
        assertTrue(names.size > 60)
        val readOnlyActions = mapOf("clipboard" to "get", "call_control" to "status", "blaze_self" to "status", "process" to "details",
            "gallery" to "list", "brand_kit" to "get", "flashcards" to "list", "life_logs" to "status", "git" to "status")
        val notRead = names.filter { n ->
            val action = readOnlyActions[n]
            ToolRegistry.classOf(n) { k -> if (k == "action" && action != null) action else "" } != ActionClass.READ
        }
        assertTrue("Plan allows these but they aren't READ: $notRead", notRead.isEmpty())
    }

    @Test fun obviousClasses() {
        fun c(n: String, vararg a: Pair<String, String>) = ToolRegistry.classOf(n) { k -> a.toMap()[k].orEmpty() }
        assertEquals(ActionClass.SEND, c("send_sms"))
        assertEquals(ActionClass.SEND, c("gmail_send"))
        assertEquals(ActionClass.SPEND, c("run_ussd"))
        assertEquals(ActionClass.DELETE, c("file_delete"))
        assertEquals(ActionClass.DELETE, c("agent_delete"))
        assertEquals(ActionClass.DELETE, c("gallery", "action" to "delete"))
        assertEquals(ActionClass.READ, c("gallery", "action" to "list"))
        assertEquals(ActionClass.SEND, c("gallery", "action" to "share"))
        assertEquals(ActionClass.WRITE_LOCAL, c("gallery"))
        assertEquals(ActionClass.READ, c("http_request", "method" to "GET"))
        assertEquals(ActionClass.WRITE_REMOTE, c("http_request", "method" to "POST"))
        assertEquals(ActionClass.SETTINGS, c("device_setting"))
        assertEquals(ActionClass.CREDENTIAL, c("standing_permission"))
        assertEquals(ActionClass.PHYSICAL, c("tap"))
        assertEquals(ActionClass.WRITE_REMOTE, c("gcal_create"))
        assertEquals(ActionClass.READ, c("web_search"))
        assertTrue(ToolRegistry.selfCard("request_secret") && ToolRegistry.selfCard("ask_user"))
        assertFalse(ToolRegistry.selfCard("send_sms"))
        assertNull(ToolRegistry.classOf("never_heard_of_it"))
        assertEquals(ActionClass.WRITE_REMOTE, ToolRegistry.UNDECLARED)
        assertTrue(ToolRegistry.declared("mcp__github__create_issue"))
    }

    @Test fun mcpToolsAreClassedByTheirHints() {
        assertEquals(ActionClass.READ, ActionClasses.mcpClass(readOnly = true, destructive = false))
        assertEquals(ActionClass.WRITE_REMOTE, ActionClasses.mcpClass(readOnly = false, destructive = false))
        assertEquals(ActionClass.DELETE, ActionClasses.mcpClass(readOnly = true, destructive = true))
        assertEquals(ActionClass.READ, PolicyCalls.classify("mcp__notion__search", "{}", true to false).call.cls)
        assertEquals(ActionClass.WRITE_REMOTE, PolicyCalls.classify("mcp__unknown__thing", "{}", null).call.cls)
    }

    @Test fun commandsAreClassedPerCommand() {
        val rm = PolicyCalls.classify("run_shell", JSONObject().put("command", "rm -rf /root/old").toString())
        assertEquals(ActionClass.DELETE, rm.call.cls)
        assertEquals(com.blaze.assistant.agent.CommandPolicy.Level.RISKY, rm.command)
        val ls = PolicyCalls.classify("run_shell", JSONObject().put("command", "ls -la").toString())
        assertEquals(ActionClass.READ, ls.call.cls)
        assertEquals(com.blaze.assistant.agent.CommandPolicy.Level.SAFE, ls.command)
        assertEquals(ActionClass.WRITE_REMOTE, PolicyCalls.classify("git", JSONObject().put("action", "push").toString()).call.cls)
        assertEquals(ActionClass.READ, PolicyCalls.classify("git", JSONObject().put("action", "raw").put("args", "log -3").toString()).call.cls)
        assertEquals(ActionClass.WRITE_LOCAL, PolicyCalls.classify("run_shell", "not json").call.cls)
    }
}
