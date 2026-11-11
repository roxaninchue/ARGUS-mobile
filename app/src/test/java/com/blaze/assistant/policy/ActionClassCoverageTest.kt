package com.blaze.assistant.policy

import com.blaze.assistant.team.ActionClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-SF-01: every tool the app registers has an ActionClass. Scans the main sources for tool definitions the way they
 * are written in this repo (`fn("name", …)`, `fn(CONST, …)`, `.put("name", "x")`, `.put("name", CONST)`, a JSON
 * literal `"name":"x"`) and fails when one is missing from [ActionClasses.TABLE], so a new tool can't ship unclassified.
 */
class ActionClassCoverageTest {
    private fun file(rel: String): File = listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() } ?: File("app/$rel")
    private val main = file("src/main/java/com/blaze/assistant")
    private fun src(rel: String) = File(main, rel).readText()

    /** Strings in the sources that look like tool definitions but are form fields (CredAsk / CallScan secure forms). */
    private val notTools = setOf("email", "password", "pin", "username")

    private fun registered(): Map<String, Set<String>> {
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("sources found at ${main.absolutePath}", files.size > 100)
        val consts = HashMap<String, MutableMap<String, String>>()   // CONST -> file -> value
        val constRe = Regex("""(?:const )?val ([A-Z_][A-Z0-9_]*)\s*=\s*"([a-z][a-z0-9_]*)"""")
        val texts = files.associateWith { it.readText() }
        texts.forEach { (f, t) -> constRe.findAll(t).forEach { m -> consts.getOrPut(m.groupValues[1]) { HashMap() }[f.path] = m.groupValues[2] } }
        fun resolve(c: String, f: File): String? {
            val d = consts[c] ?: return null
            return d[f.path] ?: d.values.toSet().singleOrNull()
        }
        val lit = listOf(Regex("""\bfn\(\s*"([a-z][a-z0-9_]*)"\s*,"""), Regex("""\.put\(\s*"name"\s*,\s*"([a-z][a-z0-9_]*)"\s*\)"""),
            Regex(""""name":"([a-z][a-z0-9_]*)","description""""))
        val con = listOf(Regex("""\bfn\(\s*([A-Z][A-Za-z0-9_.]*)\s*,"""), Regex("""\.put\(\s*"name"\s*,\s*([A-Z_][A-Za-z_.]*)\s*\)"""))
        val out = HashMap<String, MutableSet<String>>()
        texts.forEach { (f, t) ->
            lit.forEach { re -> re.findAll(t).forEach { out.getOrPut(it.groupValues[1]) { HashSet() } += f.name } }
            con.forEach { re -> re.findAll(t).forEach { m -> resolve(m.groupValues[1].substringAfterLast('.'), f)?.let { out.getOrPut(it) { HashSet() } += f.name } } }
        }
        return out.filterKeys { it !in notTools }
    }

    @Test fun everyRegisteredToolHasAClass() {
        val reg = registered()
        assertTrue("found ${reg.size} tools", reg.size >= 200)
        val missing = reg.keys.filter { !ActionClasses.declared(it) }.sorted()
        assertTrue("tools without an ActionClass (add them to ActionClasses.TABLE): " + missing.joinToString { "$it (${reg[it]})" }, missing.isEmpty())
    }

    @Test fun tableNamesOnlyRealTools() {
        val reg = registered().keys
        val stale = ActionClasses.TABLE.keys.filter { it !in reg }.sorted()
        assertTrue("ActionClasses.TABLE names tools nobody registers: $stale", stale.isEmpty())
    }

    @Test fun unknownToolsAreStrict() {
        assertEquals(ActionClass.DELETE, ActionClasses.of("not_a_tool"))
        assertEquals(ActionClass.DELETE, ActionClasses.of("mcp__srv__thing"))
        assertEquals(ActionClass.READ, ActionClasses.of("mcp__srv__list", mcp = ActionClasses.McpInfo(readOnly = true)))
        assertEquals(ActionClass.WRITE_REMOTE, ActionClasses.of("mcp__srv__act", mcp = ActionClasses.McpInfo()))
        assertEquals(ActionClass.DELETE, ActionClasses.of("mcp__srv__rm", mcp = ActionClasses.McpInfo(destructive = true, readOnly = true)))
    }

    /** Modes.planTools are the tools Plan lets through: each one (apart from the action-checked ones) must be READ. */
    @Test fun planToolsAreReads() {
        val t = src("agent/Modes.kt")
        val body = t.substringAfter("val planTools = setOf(").substringBefore("// checked by action below")
        val plan = Regex(""""([a-z][a-z0-9_]*)"""").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue(plan.size > 50)
        val notRead = plan.filter { ActionClasses.of(it) != ActionClass.READ && it != "life_logs" }
        // brand_kit / flashcards / life_logs are in planTools but checked by action there; with a reading action they are READ here too.
        val byAction = setOf("brand_kit", "flashcards")
        assertTrue("Plan tools not classed READ: $notRead", notRead.all { it in byAction })
        byAction.forEach { assertEquals(it, ActionClass.READ, ActionClasses.of(it, mapOf("action" to "get").let { m -> if (it == "flashcards") mapOf("action" to "list") else m })) }
        assertEquals(ActionClass.READ, ActionClasses.of("life_logs", mapOf("action" to "status")))
    }

    /** Every /command Blaze may run itself (CommandRegistry.ACTIONS) has a class. */
    @Test fun commandActionsClassified() {
        val t = src("agent/CommandRegistry.kt")
        val body = t.substringAfter("val ACTIONS = setOf(").substringBefore(")")
        val acts = Regex(""""([a-z]+)"""").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue(acts.size > 10)
        val missing = acts.filter { it !in ActionClasses.COMMANDS }
        assertTrue("CommandRegistry.ACTIONS without a class: $missing", missing.isEmpty())
    }

    /** ConfirmGate's outbound tools (texts, mail, posts) are SEND; deletes are DELETE. */
    @Test fun spotChecks() {
        mapOf("send_sms" to ActionClass.SEND, "gmail_send" to ActionClass.SEND, "slack_post" to ActionClass.SEND, "message_contact" to ActionClass.SEND,
            "notif_reply" to ActionClass.SEND, "file_delete" to ActionClass.DELETE, "memory_delete" to ActionClass.DELETE, "run_ussd" to ActionClass.SPEND,
            "browser_secure_input" to ActionClass.CREDENTIAL, "tap" to ActionClass.PHYSICAL, "web_search" to ActionClass.READ,
            "update_blaze" to ActionClass.SETTINGS, "save_file" to ActionClass.WRITE_LOCAL, "gcal_create" to ActionClass.WRITE_REMOTE)
            .forEach { (t, c) -> assertEquals(t, c, ActionClasses.of(t)) }
        assertEquals(ActionClass.SPEND, ActionClasses.of("start_agent", mapOf("task" to "top up my airtime")))
        assertEquals(listOf("money", "send"), ActionClasses.risky("Pay the ZESCO bill and email the receipt"))
        assertEquals(emptyList<String>(), ActionClasses.risky("Summarize today's lecture"))
    }
}
