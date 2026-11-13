package com.blaze.assistant.policy

import com.blaze.assistant.agent.CommandPolicy
import com.blaze.assistant.policy.Policy.Card
import com.blaze.assistant.policy.Policy.Decision
import com.blaze.assistant.policy.Policy.Reason
import com.blaze.assistant.policy.Policy.Trigger
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.ActionClass.COMPUTE_PAID
import com.blaze.assistant.team.ActionClass.CREDENTIAL
import com.blaze.assistant.team.ActionClass.DELETE
import com.blaze.assistant.team.ActionClass.PHYSICAL
import com.blaze.assistant.team.ActionClass.READ
import com.blaze.assistant.team.ActionClass.SEND
import com.blaze.assistant.team.ActionClass.SETTINGS
import com.blaze.assistant.team.ActionClass.SPEND
import com.blaze.assistant.team.ActionClass.WRITE_LOCAL
import com.blaze.assistant.team.ActionClass.WRITE_REMOTE
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.DEFAULT_RULES
import com.blaze.assistant.team.Permissions
import com.blaze.assistant.team.Rule
import com.blaze.assistant.team.SendScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-SF-01: Policy.decide as a decision table (05 §7.1 order), plus the cases the ticket names: a tainted SEND, a
 * proactive SPEND that is never automatic, and a bot chain decided on its lowest permission.
 */
class PolicyTest {
    private val act = Permissions()
    private val auto = Permissions(autonomy = Autonomy.AUTOPILOT)
    private val assist = Permissions(autonomy = Autonomy.ASSIST)
    private fun rules(vararg r: Pair<ActionClass, Rule>) = DEFAULT_RULES + mapOf(*r)

    /** One row: a name, the call's class, the context, and what must come out ([want] = Allow / Ask / Refuse + reason). */
    private data class Row(val name: String, val cls: ActionClass, val ctx: Policy.Ctx, val want: String, val reason: Reason,
                           val card: Card? = null, val selfCard: Boolean = false, val undo: Boolean = false)

    private fun c(perms: Permissions = act, chain: List<Permissions> = emptyList(), trigger: Trigger = Trigger.USER, text: String = "",
                  confirmed: Boolean = true, plan: Boolean = false, tainted: String? = null, standing: Rule? = null, grant: Rule? = null,
                  mcp: Policy.Mcp? = null, command: CommandPolicy.Level? = null, allowed: Boolean = false) =
        Policy.Ctx("bot", perms, chain, trigger, text, confirmed, plan, tainted, standing, grant, mcp, command, allowed)

    private val web = "a web page"

    private val table = listOf(
        // class defaults (Act)
        Row("read", READ, c(), "allow", Reason.RULE),
        Row("local write", WRITE_LOCAL, c(), "allow", Reason.RULE),
        Row("phone tap", PHYSICAL, c(), "allow", Reason.RULE),
        Row("remote write asks on Act", WRITE_REMOTE, c(), "ask", Reason.ASK),
        Row("settings ask on Act", SETTINGS, c(), "ask", Reason.ASK),
        Row("remote write on Autopilot", WRITE_REMOTE, c(auto), "allow", Reason.AUTOPILOT),
        Row("settings on Autopilot", SETTINGS, c(auto), "allow", Reason.AUTOPILOT),
        Row("autopilot offers undo", WRITE_REMOTE, c(auto), "allow", Reason.AUTOPILOT, undo = true),
        // SEND
        Row("send = draft card", SEND, c(), "ask", Reason.SEND, Card.DRAFT),
        Row("send on Autopilot still asks", SEND, c(auto), "ask", Reason.SEND, Card.DRAFT),
        Row("send standing, draft-only scope", SEND, c(standing = Rule.ALLOW), "ask", Reason.SEND, Card.DRAFT),
        Row("send standing, known contacts", SEND, c(act.copy(sendScope = SendScope.KNOWN_CONTACTS), standing = Rule.ALLOW), "allow", Reason.STANDING),
        Row("send standing, any", SEND, c(act.copy(sendScope = SendScope.ANY), standing = Rule.ALLOW), "allow", Reason.STANDING),
        Row("tainted send ignores standing", SEND, c(act.copy(sendScope = SendScope.ANY), tainted = web, standing = Rule.ALLOW), "ask", Reason.SEND, Card.DRAFT),
        Row("tainted send on Autopilot", SEND, c(auto, tainted = web), "ask", Reason.SEND, Card.DRAFT),
        Row("routine send ignores standing", SEND, c(act.copy(sendScope = SendScope.KNOWN_CONTACTS), trigger = Trigger.ROUTINE, standing = Rule.ALLOW), "ask", Reason.SEND, Card.DRAFT),
        Row("send scope none refuses", SEND, c(act.copy(sendScope = SendScope.NONE)), "refuse", Reason.SEND_SCOPE),
        // SPEND / DELETE / CREDENTIAL / COMPUTE_PAID
        Row("spend asks", SPEND, c(), "ask", Reason.SPEND),
        Row("spend on Autopilot asks", SPEND, c(auto), "ask", Reason.SPEND),
        Row("spend with a standing allow asks", SPEND, c(auto, standing = Rule.ALLOW), "ask", Reason.SPEND),
        Row("spend with a tool grant asks", SPEND, c(auto, grant = Rule.ALLOW), "ask", Reason.SPEND),
        Row("spend rule ALLOW still asks", SPEND, c(auto.copy(actions = rules(SPEND to Rule.ALLOW))), "ask", Reason.SPEND),
        Row("delete asks", DELETE, c(), "ask", Reason.DELETE),
        Row("delete on Autopilot + standing asks", DELETE, c(auto, standing = Rule.ALLOW), "ask", Reason.DELETE),
        Row("credential asks", CREDENTIAL, c(auto), "ask", Reason.CREDENTIAL),
        Row("credential standing asks", CREDENTIAL, c(auto, standing = Rule.ALLOW), "ask", Reason.CREDENTIAL),
        Row("paid compute asks on Autopilot", COMPUTE_PAID, c(auto), "ask", Reason.COMPUTE_PAID),
        Row("paid compute standing asks", COMPUTE_PAID, c(auto, standing = Rule.ALLOW), "ask", Reason.COMPUTE_PAID),
        // Plan, Assist
        Row("plan reads", READ, c(plan = true), "allow", Reason.RULE),
        Row("plan refuses a local write", WRITE_LOCAL, c(plan = true), "refuse", Reason.PLAN),
        Row("plan refuses a send", SEND, c(auto, plan = true), "refuse", Reason.PLAN),
        Row("plan refuses a tap", PHYSICAL, c(auto, plan = true), "refuse", Reason.PLAN),
        Row("plan refuses a self card that isn't a read", CREDENTIAL, c(plan = true), "refuse", Reason.PLAN, selfCard = true),
        Row("assist reads", READ, c(assist), "allow", Reason.RULE),
        Row("assist refuses a local write", WRITE_LOCAL, c(assist), "refuse", Reason.ASSIST),
        // NEVER
        Row("never rule for send", SEND, c(act.copy(actions = rules(SEND to Rule.NEVER))), "refuse", Reason.NEVER),
        Row("never rule for reads", READ, c(act.copy(actions = rules(READ to Rule.NEVER))), "refuse", Reason.NEVER),
        Row("standing never", WRITE_LOCAL, c(auto, standing = Rule.NEVER), "refuse", Reason.NEVER),
        Row("tool grant never", PHYSICAL, c(auto, grant = Rule.NEVER), "refuse", Reason.NEVER),
        Row("never beats plan", SEND, c(act.copy(actions = rules(SEND to Rule.NEVER)), plan = true), "refuse", Reason.NEVER),
        // self cards
        Row("request_secret is its own card", CREDENTIAL, c(), "allow", Reason.SELF_CARD, selfCard = true),
        Row("ask_user is its own card", READ, c(tainted = web), "allow", Reason.SELF_CARD, selfCard = true),
        // MCP Ask / Always / Off
        Row("mcp off refuses", READ, c(auto, mcp = Policy.Mcp("off", true)), "refuse", Reason.MCP_OFF),
        Row("mcp always runs a remote write on Act", WRITE_REMOTE, c(mcp = Policy.Mcp("always", false)), "allow", Reason.MCP_ALWAYS),
        Row("mcp ask asks on Autopilot", WRITE_REMOTE, c(auto, mcp = Policy.Mcp("ask", true)), "ask", Reason.MCP_ASK),
        Row("mcp default action tool on Autopilot runs", WRITE_REMOTE, c(auto, mcp = Policy.Mcp("", false)), "allow", Reason.AUTOPILOT),
        Row("mcp default write tool asks", WRITE_REMOTE, c(auto, mcp = Policy.Mcp("", true)), "ask", Reason.MCP_ASK),
        Row("mcp always after taint asks", WRITE_REMOTE, c(auto, tainted = "an MCP tool's answer", mcp = Policy.Mcp("always", false)), "ask", Reason.TAINTED),
        Row("mcp always destructive still asks", DELETE, c(auto, mcp = Policy.Mcp("always", false)), "ask", Reason.DELETE),
        Row("mcp read tool runs", READ, c(mcp = Policy.Mcp("", false)), "allow", Reason.RULE),
        // taint (ConfirmGate)
        Row("tainted remote write on Autopilot asks", WRITE_REMOTE, c(auto, tainted = web), "ask", Reason.TAINTED),
        Row("tainted settings with standing asks", SETTINGS, c(auto, tainted = web, standing = Rule.ALLOW), "ask", Reason.TAINTED),
        Row("tainted tap asks", PHYSICAL, c(auto, tainted = "your messages"), "ask", Reason.TAINTED),
        Row("tainted read runs", READ, c(auto, tainted = web), "allow", Reason.RULE),
        Row("tainted local write runs", WRITE_LOCAL, c(auto, tainted = web), "allow", Reason.RULE),
        Row("tainted delete asks as delete", DELETE, c(auto, tainted = web), "ask", Reason.DELETE),
        // CommandPolicy
        Row("risky command on Autopilot asks", WRITE_LOCAL, c(auto, command = CommandPolicy.Level.RISKY), "ask", Reason.RISKY_COMMAND),
        Row("risky command with an allowance asks", WRITE_LOCAL, c(auto, command = CommandPolicy.Level.RISKY, allowed = true), "ask", Reason.RISKY_COMMAND),
        Row("ask command on Act asks", WRITE_LOCAL, c(command = CommandPolicy.Level.ASK), "ask", Reason.ASK),
        Row("ask command with an allowance runs", WRITE_LOCAL, c(command = CommandPolicy.Level.ASK, allowed = true), "allow", Reason.COMMAND_ALLOWED),
        Row("ask command allowance void after taint", WRITE_LOCAL, c(command = CommandPolicy.Level.ASK, allowed = true, tainted = web), "ask", Reason.ASK),
        Row("ask command on Autopilot runs", WRITE_LOCAL, c(auto, command = CommandPolicy.Level.ASK), "allow", Reason.AUTOPILOT),
        Row("safe command runs", WRITE_LOCAL, c(command = CommandPolicy.Level.SAFE), "allow", Reason.RULE),
        Row("read-only command runs", READ, c(command = CommandPolicy.Level.SAFE), "allow", Reason.RULE),
        // standing allows and grants
        Row("standing allow remote write", WRITE_REMOTE, c(standing = Rule.ALLOW), "allow", Reason.STANDING),
        Row("tool grant settings", SETTINGS, c(grant = Rule.ALLOW), "allow", Reason.GRANT),
        Row("routine remote write with a standing allow", WRITE_REMOTE, c(trigger = Trigger.ROUTINE, standing = Rule.ALLOW), "allow", Reason.STANDING),
        // proactive (A-team jobAllowed / RISKY)
        Row("unconfirmed risky routine write asks", WRITE_REMOTE, c(auto, trigger = Trigger.ROUTINE, text = "Pay the ZESCO bill", confirmed = false), "ask", Reason.PROACTIVE),
        Row("unconfirmed risky suggestion tap asks", PHYSICAL, c(auto, trigger = Trigger.SUGGESTION, text = "Text Mum happy birthday", confirmed = false), "ask", Reason.PROACTIVE),
        Row("confirmed risky routine write runs", WRITE_REMOTE, c(auto, trigger = Trigger.ROUTINE, text = "Pay the ZESCO bill", confirmed = true), "allow", Reason.AUTOPILOT),
        Row("unconfirmed harmless routine runs", WRITE_REMOTE, c(auto, trigger = Trigger.ROUTINE, text = "Summarise the news", confirmed = false), "allow", Reason.AUTOPILOT),
        Row("unconfirmed risky local write runs", WRITE_LOCAL, c(auto, trigger = Trigger.ROUTINE, text = "Delete old notes", confirmed = false), "allow", Reason.RULE),
        Row("bot-started send ignores standing", SEND, c(auto.copy(sendScope = SendScope.ANY), trigger = Trigger.BOT, standing = Rule.ALLOW), "ask", Reason.SEND, Card.DRAFT),
        Row("suggestion delete on Autopilot", DELETE, c(auto, trigger = Trigger.SUGGESTION, standing = Rule.ALLOW), "ask", Reason.DELETE),
        Row("remote app spend", SPEND, c(auto, trigger = Trigger.REMOTE), "ask", Reason.SPEND),
        // chains (lowest permission)
        Row("chain: requester never sends", SEND, c(auto, chain = listOf(act.copy(actions = rules(SEND to Rule.NEVER)))), "refuse", Reason.NEVER),
        Row("chain: requester on Assist", WRITE_LOCAL, c(auto, chain = listOf(assist)), "refuse", Reason.ASSIST),
        Row("chain: requester on Act, worker on Autopilot", WRITE_REMOTE, c(auto, chain = listOf(act)), "ask", Reason.ASK),
        Row("chain: requester can't send", SEND, c(auto.copy(sendScope = SendScope.ANY), chain = listOf(act.copy(sendScope = SendScope.NONE))), "refuse", Reason.SEND_SCOPE),
        Row("chain: worker's standing allow doesn't cover the chain", WRITE_REMOTE, c(act, chain = listOf(act), standing = Rule.ALLOW), "ask", Reason.ASK),
        Row("chain: both on Autopilot", WRITE_REMOTE, c(auto, chain = listOf(auto)), "allow", Reason.AUTOPILOT),
        Row("chain: strictest rule wins", WRITE_REMOTE, c(act.copy(actions = rules(WRITE_REMOTE to Rule.ALLOW)), chain = listOf(act)), "ask", Reason.ASK),
        Row("chain: requester allows, worker never", WRITE_REMOTE, c(act.copy(actions = rules(WRITE_REMOTE to Rule.NEVER)), chain = listOf(auto.copy(actions = rules(WRITE_REMOTE to Rule.ALLOW)))), "refuse", Reason.NEVER),
        Row("chain: three bots, the last on Assist", PHYSICAL, c(auto, chain = listOf(auto, assist)), "refuse", Reason.ASSIST),
        Row("chain: grant doesn't cover the chain", SETTINGS, c(act, chain = listOf(act), grant = Rule.ALLOW), "ask", Reason.ASK),
    )

    private fun kind(d: Decision) = when (d) { is Decision.Allow -> "allow"; is Decision.Ask -> "ask"; is Decision.Refuse -> "refuse" }

    @Test fun decisionTable() {
        assertTrue("≥ 60 table cases (${table.size})", table.size >= 60)
        val failed = ArrayList<String>()
        for (r in table) {
            val d = Policy.decide(Policy.Call("tool_x", r.cls, r.selfCard, undoable = r.undo), r.ctx)
            val ok = kind(d) == r.want && d.reason == r.reason && (r.card == null || (d as? Decision.Ask)?.card == r.card) &&
                (!r.undo || (d as? Decision.Allow)?.undo == true)
            if (!ok) failed += "${r.name}: want ${r.want}/${r.reason}${r.card?.let { "/$it" } ?: ""}, got $d"
        }
        assertTrue(failed.joinToString("\n"), failed.isEmpty())
    }

    @Test fun taintedSendShowsTheDraftWithWhereItCameFrom() {
        for (p in listOf(act, auto, auto.copy(sendScope = SendScope.ANY))) for (standing in listOf(null, Rule.ALLOW)) {
            val d = Policy.decide(Policy.Call("send_sms", SEND), c(p, tainted = "your email", standing = standing))
            assertTrue("$p $standing → $d", d is Decision.Ask && d.card == Card.DRAFT && d.note == "Requested after reading your email." && !d.always)
        }
    }

    @Test fun proactiveSpendIsNeverAutomatic() {
        for (t in Trigger.values()) for (p in listOf(act, auto, auto.copy(actions = rules(SPEND to Rule.ALLOW), spendCapMonthlyMinor = 1_000_00)))
            for (standing in listOf(null, Rule.ALLOW)) for (grant in listOf(null, Rule.ALLOW)) for (confirmed in listOf(true, false)) {
                val d = Policy.decide(Policy.Call("run_ussd", SPEND), c(p, trigger = t, standing = standing, grant = grant, confirmed = confirmed, text = "top up airtime"))
                assertTrue("$t $p → $d", d is Decision.Ask && d.reason == Reason.SPEND && !d.always)
            }
    }

    @Test fun guardedClassesNeverRunUnconfirmedFromProactiveTriggers() {
        for (t in Trigger.values().filter { it.proactive }) for (cls in listOf(SEND, SPEND, DELETE)) {
            val d = Policy.decide(Policy.Call("x", cls), c(auto.copy(sendScope = SendScope.ANY), trigger = t, standing = Rule.ALLOW, grant = Rule.ALLOW))
            assertTrue("$t $cls → $d", d !is Decision.Allow)
        }
    }

    @Test fun botChainUsesTheLowestPermission() {
        val worker = Permissions(Autonomy.AUTOPILOT, rules(WRITE_REMOTE to Rule.ALLOW), 5_000_00, "ZMW", SendScope.ANY, maxHops = 3)
        val parent = Permissions(Autonomy.ACT, rules(WRITE_REMOTE to Rule.ASK, SEND to Rule.NEVER), 0, "ZMW", SendScope.DRAFT_ONLY, maxHops = 2)
        val e = Policy.effective(worker, listOf(parent))
        assertEquals(Autonomy.ACT, e.autonomy)
        assertEquals(Rule.ASK, e.actions[WRITE_REMOTE])
        assertEquals(Rule.NEVER, e.actions[SEND])
        assertEquals(0L, e.spendCapMonthlyMinor)
        assertEquals(SendScope.DRAFT_ONLY, e.sendScope)
        assertEquals(2, e.maxHops)
        assertEquals(worker, Policy.effective(worker, emptyList()))
        // a worker can't do what its parent can't
        assertTrue(Policy.decide(Policy.Call("gmail_send", SEND), c(worker, chain = listOf(parent))) is Decision.Refuse)
        assertTrue(Policy.decide(Policy.Call("gcal_create", WRITE_REMOTE), c(worker)) is Decision.Allow)
        assertTrue(Policy.decide(Policy.Call("gcal_create", WRITE_REMOTE), c(worker, chain = listOf(parent))) is Decision.Ask)
    }

    @Test fun alwaysAllowIsOfferedOnlyWhereAStandingRuleCouldApply() {
        assertTrue((Policy.decide(Policy.Call("gcal_create", WRITE_REMOTE), c()) as Decision.Ask).always)
        assertFalse((Policy.decide(Policy.Call("gcal_create", WRITE_REMOTE), c(chain = listOf(act))) as Decision.Ask).always)
        assertFalse((Policy.decide(Policy.Call("gcal_create", WRITE_REMOTE), c(trigger = Trigger.ROUTINE)) as Decision.Ask).always)
        assertFalse((Policy.decide(Policy.Call("x", DELETE), c()) as Decision.Ask).always)
        assertFalse((Policy.decide(Policy.Call("x", SEND), c()) as Decision.Ask).always)
    }

    @Test fun refusalsTellTheBotNotToRetry() {
        for (d in listOf(Policy.decide(Policy.Call("x", SEND), c(plan = true)), Policy.decide(Policy.Call("x", READ), c(mcp = Policy.Mcp("off", true))),
            Policy.decide(Policy.Call("x", SEND), c(act.copy(sendScope = SendScope.NONE))))) {
            assertTrue(d is Decision.Refuse && d.message.contains("Don't retry"))
        }
    }

    @Test fun ateamRiskyRegex() {
        assertEquals(listOf("money"), Policy.risky("Buy the cinema tickets"))
        assertEquals(listOf("money"), Policy.risky("book a flight to Lusaka"))
        assertEquals(listOf("send"), Policy.risky("Email the landlord"))
        assertEquals(listOf("delete"), Policy.risky("Wipe the old photos"))
        assertEquals(listOf("money", "send", "delete"), Policy.risky("pay, then text Ana and remove the draft"))
        assertEquals(emptyList<String>(), Policy.risky("Summarise today's news"))
        assertEquals(emptyList<String>(), Policy.risky("Read the bookshelf list"))   // "book" alone isn't money
    }

    @Test fun triggersFromJobSources() {
        assertEquals(Trigger.USER, Trigger.ofSource("user"))
        assertEquals(Trigger.USER, Trigger.ofSource(null))
        assertEquals(Trigger.USER, Trigger.ofSource("a-team"))
        assertEquals(Trigger.ROUTINE, Trigger.ofSource("routine"))
        assertEquals(Trigger.SUGGESTION, Trigger.ofSource("suggestion"))
        assertEquals(Trigger.BOT, Trigger.ofSource("bot:atlas"))
        assertEquals(Trigger.BOT, Trigger.ofSource("manager"))
        assertEquals(Trigger.REMOTE, Trigger.ofSource("mcp"))
        assertEquals(Trigger.SYSTEM, Trigger.ofSource("something-new"))
        assertTrue(Trigger.SYSTEM.proactive); assertFalse(Trigger.USER.proactive)
    }

    @Test fun commandClasses() {
        fun sh(s: String) = CommandPolicy.actionClass(CommandPolicy.Kind.SHELL, s)
        assertEquals(READ, sh("ls -la && cat README.md | grep x"))
        assertEquals(WRITE_LOCAL, sh("./gradlew test"))
        assertEquals(WRITE_LOCAL, sh("echo hi > notes.txt"))
        assertEquals(DELETE, sh("rm -rf build"))
        assertEquals(DELETE, sh("find . -name '*.tmp' -delete"))
        assertEquals(DELETE, sh("ls; git push --force origin main"))
        assertEquals(SEND, sh("termux-sms-send -n 0977 hello"))
        assertEquals(SETTINGS, sh("sudo apt install vim"))
        assertEquals(SETTINGS, sh("pip install requests"))
        assertEquals(WRITE_REMOTE, sh("git push origin main"))
        assertEquals(WRITE_REMOTE, sh("curl -X POST https://example.com/api -d x=1"))
        assertEquals(READ, sh("curl https://example.com"))
        assertEquals(WRITE_LOCAL, sh("curl -o page.html https://example.com"))
        assertEquals(WRITE_REMOTE, sh("scp a.txt host:/tmp"))
        assertEquals(DELETE, sh("bash -c 'rm x'"))
        fun git(s: String) = CommandPolicy.actionClass(CommandPolicy.Kind.GIT, s)
        assertEquals(READ, git("status"))
        assertEquals(READ, git("git log --oneline"))
        assertEquals(WRITE_LOCAL, git("commit -m x"))
        assertEquals(WRITE_REMOTE, git("push"))
        assertEquals(DELETE, git("reset --hard HEAD~1"))
        assertEquals(DELETE, git("branch -D old"))
    }
}
