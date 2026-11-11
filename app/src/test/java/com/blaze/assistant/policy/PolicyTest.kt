package com.blaze.assistant.policy

import com.blaze.assistant.policy.ActionClasses.McpInfo
import com.blaze.assistant.policy.Policy.Call
import com.blaze.assistant.policy.Policy.CardKind
import com.blaze.assistant.policy.Policy.Ctx
import com.blaze.assistant.policy.Policy.Decision
import com.blaze.assistant.policy.Policy.Step
import com.blaze.assistant.policy.Policy.Subject
import com.blaze.assistant.policy.Policy.Trigger
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.Avatar
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotAppGrant
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.Permissions
import com.blaze.assistant.team.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-SF-01: Policy.decide as one decision table (05 §7.1 order, §7.2 proactive + chains). Each row: who calls, what, in
 * which situation, and the expected outcome (allow / undo / card / draft / refuse) plus, where it matters, the step.
 * Permission maps are spelled out (Policy.CLASS_DEFAULTS) so the table doesn't depend on P0's DEFAULT_RULES values.
 */
class PolicyTest {
    private fun bot(id: String = "nova", autonomy: Autonomy = Autonomy.ACT, actions: Map<ActionClass, Rule> = emptyMap(),
                    grants: Map<String, Rule> = emptyMap(), apps: List<BotAppGrant> = emptyList(), cap: Long = 0) =
        Subject(id, Permissions(autonomy = autonomy, actions = Policy.CLASS_DEFAULTS + actions, spendCapMonthlyMinor = cap), grants, apps)

    private val act = bot()
    private val auto = bot(autonomy = Autonomy.AUTOPILOT)
    private val assist = bot(autonomy = Autonomy.ASSIST)
    private fun store(vararg rows: Triple<String, String, Rule>) = InMemoryPolicyStore().apply { rows.forEach { (t, b, r) -> set(t, b, r, "user", 1L) } }
    private fun c(tool: String, vararg args: Pair<String, String>, mcp: McpInfo? = null, cls: ActionClass? = null) = Call(tool, mapOf(*args), mcp = mcp, cls = cls)

    private data class Row(val name: String, val who: Subject, val call: Call, val ctx: Ctx, val want: String, val step: Step? = null)

    private fun outcome(d: Decision): String = when (d) {
        is Decision.Allow -> "allow"
        is Decision.AllowWithUndo -> "undo"
        is Decision.Card -> if (d.kind == CardKind.DRAFT) "draft" else "card"
        is Decision.Refuse -> "refuse"
    }

    private val web = "a web page"
    private val parentAct = bot("manager")
    private val parentAuto = bot("manager", Autonomy.AUTOPILOT)

    private val rows = listOf(
        // --- defaults per class (05 §7.1) ---
        Row("read runs", act, c("web_search", "query" to "zambia news"), Ctx(), "allow", Step.READ),
        Row("read mail runs", act, c("gmail_read"), Ctx(), "allow", Step.READ),
        Row("local write runs", act, c("save_file"), Ctx(), "allow", Step.STANDING),
        Row("memory save in autopilot", auto, c("memory_save"), Ctx(), "allow"),
        Row("remote write asks in Act", act, c("gcal_create"), Ctx(), "card", Step.DEFAULT),
        Row("remote write only affecting the user", act, c("gcal_create"), Ctx(onlyUserAffected = true), "allow", Step.STANDING),
        Row("self-only tool (spotify)", act, c("spotify_play"), Ctx(), "allow", Step.STANDING),
        Row("remote write in autopilot", auto, c("gcal_create"), Ctx(), "allow", Step.AUTOPILOT),
        Row("autopilot undoable → undo bar", auto, c("gcal_create"), Ctx(undoable = true), "undo", Step.AUTOPILOT),
        Row("settings ask in Act", act, c("update_blaze"), Ctx(), "card"),
        Row("settings in autopilot", auto, c("update_blaze"), Ctx(), "allow"),
        Row("settings with standing allow", act, c("update_blaze"), Ctx(store = store(Triple("update_blaze", "nova", Rule.ALLOW))), "allow", Step.STANDING),
        Row("taps run in task scope", act, c("tap", "element" to "3"), Ctx(), "allow"),
        // --- SEND: always a draft card ---
        Row("send asks (draft)", act, c("send_sms", "to" to "Mary", "text" to "hi"), Ctx(), "draft", Step.ALWAYS_ASK),
        Row("send in autopilot still asks", auto, c("send_sms"), Ctx(), "draft", Step.ALWAYS_ASK),
        Row("gmail send in autopilot", auto, c("gmail_send"), Ctx(), "draft"),
        Row("tainted send (autopilot)", auto, c("send_sms"), Ctx(taint = web), "draft", Step.ALWAYS_ASK),
        Row("tainted send (Act)", act, c("gmail_send"), Ctx(taint = "your email"), "draft"),
        Row("send with standing send rule", auto, c("send_sms"), Ctx(sendPreApproved = true), "allow", Step.STANDING),
        Row("send rule ignored when tainted", auto, c("send_sms"), Ctx(sendPreApproved = true, taint = web), "draft"),
        Row("send rule ignored for a routine", auto, c("send_sms"), Ctx(sendPreApproved = true, trigger = Trigger.ROUTINE), "draft"),
        Row("send can't be standing-allowed per tool", auto, c("send_sms"), Ctx(store = store(Triple("send_sms", "*", Rule.ALLOW))), "draft"),
        Row("share is a send", act, c("share_to_app"), Ctx(), "draft"),
        Row("call in user's name", auto, c("call_contact"), Ctx(), "draft"),
        // --- SPEND ---
        Row("spend asks", act, c("run_ussd", "code" to "*115#"), Ctx(), "card", Step.ALWAYS_ASK),
        Row("spend within SpendGuard cap, user present", auto, c("run_ussd"), Ctx(spendPreApproved = true), "allow", Step.STANDING),
        Row("proactive spend never auto (routine)", auto, c("run_ussd"), Ctx(spendPreApproved = true, trigger = Trigger.ROUTINE), "card", Step.ALWAYS_ASK),
        Row("proactive spend never auto (bot)", auto, c("run_ussd"), Ctx(spendPreApproved = true, trigger = Trigger.BOT), "card"),
        Row("proactive spend never auto (suggestion)", auto, c("run_ussd"), Ctx(spendPreApproved = true, trigger = Trigger.SUGGESTION), "card"),
        Row("proactive spend never auto (check-in)", auto, c("run_ussd"), Ctx(spendPreApproved = true, trigger = Trigger.CHECKIN), "card"),
        Row("proactive spend never auto (mcp)", auto, c("run_ussd"), Ctx(spendPreApproved = true, trigger = Trigger.MCP), "card"),
        Row("spend ignores a standing allow", auto, c("run_ussd"), Ctx(store = store(Triple("run_ussd", "nova", Rule.ALLOW))), "card"),
        Row("tainted spend within cap still asks", auto, c("run_ussd"), Ctx(spendPreApproved = true, taint = web), "card"),
        Row("browser pay button is spend", auto, c("browser_click", "target" to "Pay now"), Ctx(), "card"),
        Row("browser next button is fine", auto, c("browser_click", "target" to "Next page"), Ctx(), "allow"),
        // --- DELETE ---
        Row("delete asks", auto, c("file_delete", "path" to "a.txt"), Ctx(), "card", Step.ALWAYS_ASK),
        Row("delete ignores a standing allow", auto, c("file_delete"), Ctx(store = store(Triple("file_delete", "nova", Rule.ALLOW))), "card"),
        Row("forget asks", act, c("forget"), Ctx(), "card"),
        Row("uninstall asks", auto, c("uninstall_app"), Ctx(), "card"),
        Row("gallery delete action", auto, c("gallery", "action" to "delete"), Ctx(), "card"),
        Row("gallery list action reads", act, c("gallery", "action" to "list"), Ctx(), "allow", Step.READ),
        Row("flashcards delete_deck", auto, c("flashcards", "action" to "delete_deck"), Ctx(), "card"),
        // --- taint (ConfirmGate) ---
        Row("tainted remote write", auto, c("gcal_create"), Ctx(taint = web), "card", Step.TAINT),
        Row("tainted settings", auto, c("update_blaze"), Ctx(taint = web), "card", Step.TAINT),
        Row("tainted tap", auto, c("tap"), Ctx(taint = "notifications"), "card", Step.TAINT),
        Row("tainted local write still runs", auto, c("save_file"), Ctx(taint = web), "allow"),
        Row("tainted read still runs", auto, c("web_search"), Ctx(taint = web), "allow", Step.READ),
        Row("tainted standing allow loses", act, c("update_blaze"), Ctx(taint = web, store = store(Triple("update_blaze", "nova", Rule.ALLOW))), "card", Step.TAINT),
        // --- credentials, paid compute ---
        Row("credential asks", auto, c("browser_secure_input"), Ctx(), "card", Step.ALWAYS_ASK),
        Row("credential can't be standing-allowed", auto, c("browser_secure_input"), Ctx(store = store(Triple("browser_secure_input", "*", Rule.ALLOW))), "card"),
        Row("paid compute asks each session", auto, c("cloud_start", cls = ActionClass.COMPUTE_PAID), Ctx(), "card"),
        Row("paid compute allowed this session", auto, c("cloud_start", cls = ActionClass.COMPUTE_PAID), Ctx(grants = setOf("cloud_start")), "allow"),
        // --- Plan ---
        Row("plan refuses local write", auto, c("save_file"), Ctx(plan = true), "refuse", Step.PLAN),
        Row("plan refuses send", auto, c("send_sms"), Ctx(plan = true), "refuse", Step.PLAN),
        Row("plan allows reads", auto, c("web_search"), Ctx(plan = true), "allow"),
        Row("plan allows read-only git", act, c("git", "action" to "status"), Ctx(plan = true), "allow", Step.READ),
        Row("plan refuses git commit", act, c("git", "action" to "commit"), Ctx(plan = true), "refuse", Step.PLAN),
        // --- NEVER ---
        Row("store NEVER refuses even a read", act, c("web_search"), Ctx(store = store(Triple("web_search", "nova", Rule.NEVER))), "refuse", Step.NEVER),
        Row("NEVER beats plan", act, c("web_search"), Ctx(plan = true, store = store(Triple("web_search", "*", Rule.NEVER))), "refuse", Step.NEVER),
        Row("class NEVER for send", bot(actions = mapOf(ActionClass.SEND to Rule.NEVER)), c("send_sms"), Ctx(), "refuse", Step.NEVER),
        Row("tool grant NEVER", bot(grants = mapOf("gcal_create" to Rule.NEVER)), c("gcal_create"), Ctx(), "refuse"),
        Row("app grant NEVER", bot(apps = listOf(BotAppGrant("gmail", "argus-connect", mapOf("gmail_send" to Rule.NEVER)))), c("gmail_send"), Ctx(), "refuse"),
        Row("NEVER beats an approval", act, c("gcal_create"), Ctx(approved = true, store = store(Triple("gcal_create", "nova", Rule.NEVER))), "refuse"),
        // --- tool grants / pinned Ask ---
        Row("tool grant ALLOW", bot(grants = mapOf("gcal_create" to Rule.ALLOW)), c("gcal_create"), Ctx(), "allow", Step.STANDING),
        Row("tool pinned to Ask beats autopilot", bot(autonomy = Autonomy.AUTOPILOT, grants = mapOf("gcal_create" to Rule.ASK)), c("gcal_create"), Ctx(), "card"),
        Row("policies row Ask beats autopilot", auto, c("update_blaze"), Ctx(store = store(Triple("update_blaze", "nova", Rule.ASK))), "card"),
        Row("bot row beats * row", act, c("update_blaze"), Ctx(store = store(Triple("update_blaze", "*", Rule.ALLOW), Triple("update_blaze", "nova", Rule.ASK))), "card"),
        Row("* row applies to every bot", act, c("update_blaze"), Ctx(store = store(Triple("update_blaze", "*", Rule.ALLOW))), "allow"),
        Row("approved on the batch card", act, c("gcal_create"), Ctx(approved = true), "allow", Step.APPROVED),
        // --- MCP Ask / Always / Off ---
        Row("mcp off refuses", auto, c("mcp__gh__x", mcp = McpInfo(mode = "off", readOnly = true)), Ctx(), "refuse", Step.NEVER),
        Row("mcp read-only runs", act, c("mcp__gh__list", mcp = McpInfo(readOnly = true)), Ctx(), "allow"),
        Row("mcp action asks in Act", act, c("mcp__gh__comment", mcp = McpInfo()), Ctx(), "card"),
        Row("mcp action runs in autopilot", auto, c("mcp__gh__comment", mcp = McpInfo()), Ctx(), "allow"),
        Row("mcp set to Ask keeps asking in autopilot", auto, c("mcp__gh__comment", mcp = McpInfo(mode = "ask")), Ctx(), "card"),
        Row("mcp Always runs in Act", act, c("mcp__gh__comment", mcp = McpInfo(mode = "always")), Ctx(), "allow", Step.STANDING),
        Row("mcp destructive Always still asks", auto, c("mcp__gh__delete", mcp = McpInfo(mode = "always", destructive = true)), Ctx(), "card"),
        Row("mcp without hints is unknown → strict", auto, c("mcp__x__y"), Ctx(), "card"),
        Row("unknown tool → strict", auto, c("frobnicate"), Ctx(), "card"),
        Row("mcp Always loses after taint", auto, c("mcp__gh__comment", mcp = McpInfo(mode = "always")), Ctx(taint = "an MCP tool's answer"), "card", Step.TAINT),
        // --- CommandPolicy (shell / git) ---
        Row("safe shell runs", act, c("run_shell", "command" to "ls -la && cat README.md"), Ctx(), "allow"),
        Row("network shell asks in Act", act, c("run_shell", "command" to "curl https://example.com"), Ctx(), "card"),
        Row("granted command runs", act, c("run_shell", "command" to "curl https://example.com"), Ctx(grants = setOf("curl")), "allow"),
        Row("granted command loses after taint", act, c("run_shell", "command" to "curl https://example.com"), Ctx(grants = setOf("curl"), taint = web), "card", Step.TAINT),
        Row("risky shell always asks", auto, c("run_shell", "command" to "rm -rf build"), Ctx(), "card", Step.ALWAYS_ASK),
        Row("sudo always asks", auto, c("run_shell", "command" to "sudo apt install vim"), Ctx(grants = setOf("sudo")), "card"),
        Row("git status runs", act, c("git", "action" to "status"), Ctx(), "allow"),
        Row("git push asks in Act", act, c("git", "action" to "push"), Ctx(), "card"),
        Row("git force push always asks", auto, c("git", "action" to "raw", "args" to "push --force origin main"), Ctx(), "card", Step.ALWAYS_ASK),
        Row("http GET reads", auto, c("http_request", "method" to "GET", "url" to "https://x"), Ctx(taint = web), "allow"),
        Row("http POST after taint asks", auto, c("http_request", "method" to "POST", "url" to "https://x"), Ctx(taint = web), "card"),
        // --- A-team RISKY in free-text tasks / routines ---
        Row("harmless agent task", auto, c("start_agent", "task" to "summarize my lecture notes"), Ctx(), "allow"),
        Row("agent task that buys", auto, c("start_agent", "task" to "buy the networking textbook"), Ctx(), "card", Step.ALWAYS_ASK),
        Row("routine that emails is an approval, not a draft", auto, c("create_routine", "prompt" to "email Mary the report every Monday"), Ctx(), "card"),
        Row("trigger that deletes", auto, c("create_trigger", "prompt" to "delete old screenshots when storage is low"), Ctx(), "card"),
        Row("unconfirmed proactive job that spends", auto, c("save_file"), Ctx(trigger = Trigger.ROUTINE, jobConfirmed = false, task = "Order the textbook online"), "card", Step.UNCONFIRMED_JOB),
        Row("unconfirmed proactive job may read", auto, c("web_search"), Ctx(trigger = Trigger.SUGGESTION, jobConfirmed = false, task = "Order the textbook online"), "allow"),
        Row("confirmed routine in autopilot", auto, c("gcal_create"), Ctx(trigger = Trigger.ROUTINE), "allow"),
        Row("routine delete still asks", auto, c("file_delete"), Ctx(trigger = Trigger.ROUTINE), "card"),
        // --- bot chains: the lowest permission along the chain ---
        Row("chain: Act parent drags autopilot worker down", auto, c("gcal_create"), Ctx(chain = listOf(parentAct)), "card"),
        Row("chain: autopilot all the way", auto, c("gcal_create"), Ctx(chain = listOf(parentAuto)), "allow"),
        Row("chain: parent can't spend → refuse", auto, c("run_ussd"), Ctx(chain = listOf(bot("money", actions = mapOf(ActionClass.SPEND to Rule.NEVER))), spendPreApproved = true), "refuse", Step.NEVER),
        Row("chain: worker grant alone isn't enough", bot(grants = mapOf("gcal_create" to Rule.ALLOW)), c("gcal_create"), Ctx(chain = listOf(parentAct)), "card"),
        Row("chain: grant on every bot", bot(grants = mapOf("gcal_create" to Rule.ALLOW)), c("gcal_create"), Ctx(chain = listOf(bot("manager", grants = mapOf("gcal_create" to Rule.ALLOW)))), "allow"),
        Row("chain: assist parent", auto, c("save_file"), Ctx(chain = listOf(bot("manager", Autonomy.ASSIST))), "card", Step.ASSIST),
        Row("chain: parent store NEVER", auto, c("gcal_create"), Ctx(chain = listOf(parentAuto), store = store(Triple("gcal_create", "manager", Rule.NEVER))), "refuse"),
        Row("chain: three deep, lowest wins", auto, c("update_blaze"), Ctx(chain = listOf(parentAuto, bot("root", Autonomy.ACT))), "card"),
        // --- Assist ---
        Row("assist asks for local writes", assist, c("save_file"), Ctx(), "card", Step.ASSIST),
        Row("assist reads freely", assist, c("web_search"), Ctx(), "allow"),
        // --- /commands and action-routed tools ---
        Row("/music opens a view", act, c("blaze_command", "command" to "/music"), Ctx(), "allow", Step.READ),
        Row("/forget deletes", auto, c("blaze_command", "command" to "forget"), Ctx(), "card"),
        Row("/plan changes a setting", act, c("blaze_command", "command" to "/plan"), Ctx(), "card"),
        Row("safe walk start texts later", auto, c("safe_walk", "action" to "start"), Ctx(), "draft"),
        Row("safe walk status reads", act, c("safe_walk", "action" to "status"), Ctx(), "allow"),
        Row("legacy single agent (autopilot)", Policy.LEGACY, c("gcal_create"), Ctx(), "allow"),
        Row("legacy single agent still drafts sends", Policy.LEGACY, c("message_contact"), Ctx(), "draft"),
    )

    @Test fun decisionTable() {
        assertTrue("≥60 table cases (${rows.size})", rows.size >= 60)
        val fails = rows.mapNotNull { r ->
            val d = Policy.decide(r.who, r.call, r.ctx)
            val got = outcome(d)
            if (got != r.want || (r.step != null && d.step != r.step)) "${r.name}: want ${r.want}/${r.step} got $got/${d.step} ($d)" else null
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun taintedCardSaysWhatWasRead() {
        val d = Policy.decide(auto, c("gcal_create"), Ctx(taint = "a web page")) as Decision.Card
        assertTrue(d.reason, d.reason.startsWith("Requested after reading a web page."))
        assertEquals("a web page", d.taintSource)
        assertFalse(d.canAlwaysAllow)
    }

    @Test fun alwaysAllowOfferedOnlyWhereItCanApply() {
        assertTrue((Policy.decide(act, c("gcal_create"), Ctx()) as Decision.Card).canAlwaysAllow)
        assertFalse((Policy.decide(act, c("send_sms"), Ctx()) as Decision.Card).canAlwaysAllow)
        assertFalse((Policy.decide(auto, c("file_delete"), Ctx()) as Decision.Card).canAlwaysAllow)
        assertFalse((Policy.decide(auto, c("run_shell", "command" to "rm -rf x"), Ctx()) as Decision.Card).canAlwaysAllow)
        assertFalse((Policy.decide(act, c("gcal_create"), Ctx(trigger = Trigger.ROUTINE)) as Decision.Card).canAlwaysAllow)
    }

    @Test fun refusalsTellTheModelNotToRetry() {
        val p = Policy.decide(auto, c("save_file"), Ctx(plan = true)) as Decision.Refuse
        assertTrue(p.reason, p.reason.contains("PLAN") && p.reason.contains("Don't retry"))
        val n = Policy.decide(auto, c("run_ussd"), Ctx(chain = listOf(bot("money", actions = mapOf(ActionClass.SPEND to Rule.NEVER))))) as Decision.Refuse
        assertTrue(n.reason, n.reason.contains("money") && n.reason.contains("Don't retry"))
        assertEquals(ActionClass.SPEND, n.cls)
    }

    @Test fun unconfirmedJobNamesTheRisk() {
        val d = Policy.decide(auto, c("gcal_create"), Ctx(trigger = Trigger.ROUTINE, jobConfirmed = false, task = "Order the textbook and email Mary")) as Decision.Card
        assertTrue(d.reason, d.reason.contains("money") && d.reason.contains("send"))
    }

    @Test fun teamBotOverload() {
        val b = Bot(id = "scout", kind = BotKind.DOMAIN, name = "Scout", label = "Research", avatar = Avatar("circle", "#0D28FF"),
            soulPath = "bots/scout/SOUL.md", toolGroups = setOf("web"), toolGrants = mapOf("gcal_create" to Rule.NEVER),
            permissions = Permissions(autonomy = Autonomy.AUTOPILOT, actions = Policy.CLASS_DEFAULTS), createdAt = 0, u = 0)
        assertTrue(Policy.decide(b, c("web_search")).allowed)
        assertTrue(Policy.decide(b, c("gcal_create")) is Decision.Refuse)
        assertEquals("draft", outcome(Policy.decide(b, c("send_sms"))))
    }

    @Test fun chainHelpers() {
        val chain = listOf(bot("a", Autonomy.AUTOPILOT, cap = 50_000), bot("b", Autonomy.ACT, cap = 10_000), bot("c", Autonomy.AUTOPILOT, cap = 0))
        assertEquals(Autonomy.ACT, Policy.chainAutonomy(chain))
        assertEquals(0L, Policy.chainSpendCap(chain))
    }

    @Test fun rememberWritesAlwaysAllowOnlyForPreApprovable() {
        val s = InMemoryPolicyStore()
        fun ap(tool: String, cls: ActionClass, status: String) = com.blaze.assistant.team.Approval("a1", "nova", null, "c1", tool, cls, "t", "d", "{}",
            false, status, false, 0, 1, "user")
        assertTrue(Policy.remember(s, ap("gcal_create", ActionClass.WRITE_REMOTE, "always"), 5))
        assertEquals(Rule.ALLOW, s.rule("gcal_create", "nova"))
        assertFalse(Policy.remember(s, ap("send_sms", ActionClass.SEND, "always"), 5))
        assertFalse(Policy.remember(s, ap("file_delete", ActionClass.DELETE, "always"), 5))
        assertFalse(Policy.remember(s, ap("update_blaze", ActionClass.SETTINGS, "allowed"), 5))
        assertEquals(null, s.rule("send_sms", "nova"))
        // The remembered rule now passes the next call.
        assertEquals("allow", outcome(Policy.decide(act, c("gcal_create"), Ctx(store = s))))
    }
}
