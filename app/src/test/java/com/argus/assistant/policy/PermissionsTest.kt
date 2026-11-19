package com.blaze.assistant.policy

import com.blaze.assistant.policy.Policy.Decision
import com.blaze.assistant.team.ActionClass
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.Permissions
import com.blaze.assistant.team.Rule
import com.blaze.assistant.team.SendScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-SF-04: the Approvals & permissions page model (standing allows, Ask again, per-bot settings, Autopilot exceptions). */
class PermissionsTest {
    private val rows = listOf(
        Triple("gmail_send", "mail", Rule.ALLOW), Triple("gcal_create", "mail", Rule.ALLOW),
        Triple("web_search", "*", Rule.ALLOW), Triple("run_shell", "manager", Rule.ALLOW),
        Triple("file_delete", "mail", Rule.NEVER), Triple("add_contact", "a-bot", Rule.ALLOW))
    private val names = mapOf("mail" to "Mailer", "manager" to "Argus", "a-bot" to "Zed")

    @Test fun standingAllowsGroupedByBotThenTool() {
        val g = PermissionsModel.standing(rows, { names[it] ?: it })
        assertEquals(listOf("*", "manager", "mail", "a-bot"), g.map { it.botId })
        assertEquals("Every bot", g[0].botName)
        assertEquals(listOf("gcal_create", "gmail_send"), g[2].rows.map { it.tool })   // NEVER rows aren't standing allows
    }

    @Test fun askAgainRemovesTheStandingAllow() {
        val after = PermissionsModel.revoke(rows, "gmail_send", "mail")
        assertEquals(rows.size - 1, after.size)
        assertTrue(PermissionsModel.standing(after, { it }).flatMap { it.rows }.none { it.tool == "gmail_send" })
        // and the policy asks again: standing = what the table holds now (null)
        val standing = after.firstOrNull { it.first == "gcal_create" && it.second == "mail" }?.third
        assertTrue(Policy.decide(Policy.Call("gcal_create", ActionClass.WRITE_REMOTE), Policy.Ctx("mail", standing = standing)) is Decision.Allow)
        val gone = PermissionsModel.revoke(after, "gcal_create", "mail").firstOrNull { it.first == "gcal_create" && it.second == "mail" }?.third
        assertNull(gone)
        assertTrue(Policy.decide(Policy.Call("gcal_create", ActionClass.WRITE_REMOTE), Policy.Ctx("mail", standing = gone)) is Decision.Ask)
        // another bot's row with the same tool stays
        assertEquals(rows.size, PermissionsModel.revoke(rows, "gmail_send", "other").size)
    }

    @Test fun capZeroEverySpendAsks() {
        val p = Permissions(autonomy = Autonomy.AUTOPILOT, actions = ActionClass.values().associateWith { Rule.ALLOW })
        assertEquals(0L, p.spendCapMonthlyMinor)
        for (amount in listOf(1L, 100L, 10_000_00L)) {
            assertTrue(SpendGuard.decide(SpendGuard.Check(amount, 0), Policy.spendLimits(p)).asks)
            assertTrue(Policy.decide(Policy.Call("run_ussd", ActionClass.SPEND), Policy.Ctx(perms = p, standing = Rule.ALLOW, spend = SpendGuard.Check(amount, 0))) is Decision.Ask)
        }
        assertEquals("No budget: every payment asks", PermissionsModel.capLine(PermissionsModel.of("m", "Argus", p)))
    }

    @Test fun perBotSettingsRoundTrip() {
        val p = Permissions()
        val b = PermissionsModel.of("m", "Argus", p).copy(autonomy = Autonomy.AUTOPILOT, sendScope = SendScope.KNOWN_CONTACTS,
            capMonthlyMinor = 50_000, perTxMaxMinor = -5)
        val q = PermissionsModel.apply(p, b)
        assertEquals(Autonomy.AUTOPILOT, q.autonomy); assertEquals(SendScope.KNOWN_CONTACTS, q.sendScope)
        assertEquals(50_000L, q.spendCapMonthlyMinor); assertEquals(0L, q.spendPerTxMaxMinor)
        assertEquals(p.actions, q.actions); assertEquals(p.maxHops, q.maxHops)
        assertEquals(Autonomy.values().toList(), PermissionsModel.AUTONOMY.map { it.value })
        assertEquals(SendScope.values().toList(), PermissionsModel.SEND_SCOPE.map { it.value })
        assertEquals("ZMW 1.50 of ZMW 500.00 this month", PermissionsModel.capLine(PermissionsModel.of("m", "A", q, spent = 150)))
    }

    @Test fun parseMinor() {
        assertEquals(1250L, PermissionsModel.parseMinor("12.5")); assertEquals(1205L, PermissionsModel.parseMinor("12.05"))
        assertEquals(0L, PermissionsModel.parseMinor(" ")); assertEquals(100_000L, PermissionsModel.parseMinor("1,000"))
        assertNull(PermissionsModel.parseMinor("abc")); assertNull(PermissionsModel.parseMinor("-3")); assertNull(PermissionsModel.parseMinor("1.234"))
    }

    @Test fun autopilotExceptionsEqualPolicysOwn() {
        val listed = PermissionsModel.autopilotExceptions().mapNotNull { it.cls }
        assertEquals(Policy.AUTOPILOT_ALWAYS_ASK, listed)
        // and Policy's list is what decide() really does on Autopilot with every class allowed
        val open = Permissions(Autonomy.AUTOPILOT, ActionClass.values().associateWith { Rule.ALLOW }, sendScope = SendScope.ANY)
        val asks = ActionClass.values().filter { Policy.decide(Policy.Call("x", it), Policy.Ctx(perms = open)) !is Decision.Allow }
        assertEquals(asks, listed)
        assertEquals(setOf(ActionClass.SEND, ActionClass.SPEND, ActionClass.DELETE, ActionClass.CREDENTIAL, ActionClass.COMPUTE_PAID), listed.toSet())
    }

    @Test fun toolLabels() {
        assertEquals("Gmail send", PermissionsModel.toolLabel("gmail_send"))
        assertEquals("Github: create issue", PermissionsModel.toolLabel("mcp__github__create_issue"))
    }
}
