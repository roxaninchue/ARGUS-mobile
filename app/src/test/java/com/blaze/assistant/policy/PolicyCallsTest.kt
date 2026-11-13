package com.blaze.assistant.policy

import com.blaze.assistant.agent.TeamHooks
import com.blaze.assistant.team.Autonomy
import com.blaze.assistant.team.Avatar
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.Job
import com.blaze.assistant.team.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-SF-01: the pure half of the dispatch glue (origins, permissions, where the policy is enforced) and the TeamHooks seam. */
class PolicyCallsTest {
    private fun bot(id: String, kind: BotKind = BotKind.CUSTOM) = Bot(id, kind, id, "", null, Avatar("orb", "#888888"), soulPath = "", toolGroups = emptySet(),
        toolGrants = mapOf("gcal_create" to Rule.ALLOW), createdAt = 0, u = 0)
    private fun job(source: String, confirmed: Boolean = true) = Job("7", "Pay the rent", "atlas", null, source = source, brief = "via momo",
        notes = "monthly", confirmed = confirmed, createdAt = 0, u = 0)

    @Test fun originsFromJobs() {
        val o = PolicyCalls.originOf(job("bot:nova", confirmed = false))
        assertEquals("bot", o.trigger); assertEquals(listOf("nova"), o.chain); assertEquals("7", o.jobId); assertFalse(o.confirmed)
        assertTrue(Policy.risky(o.text).contains("money"))
        assertEquals("routine", PolicyCalls.originOf(job("routine")).trigger)
        assertEquals(emptyList<String>(), PolicyCalls.originOf(job("user")).chain)
        assertEquals(Policy.Trigger.BOT, Policy.Trigger.ofSource(PolicyCalls.originOf(job("bot:nova")).trigger))
    }

    @Test fun managerFollowsTheAppsAutopilot() {
        assertEquals(Autonomy.AUTOPILOT, PolicyCalls.permsOf(bot("manager", BotKind.MANAGER), autopilot = true).autonomy)
        assertEquals(Autonomy.ACT, PolicyCalls.permsOf(bot("manager", BotKind.MANAGER), autopilot = false).autonomy)
        assertEquals(Autonomy.ACT, PolicyCalls.permsOf(bot("atlas"), autopilot = true).autonomy)
        assertEquals(Autonomy.ACT, PolicyCalls.permsOf(null, autopilot = true).autonomy)
        assertEquals(Rule.ALLOW, PolicyCalls.grantOf(bot("atlas"), "gcal_create"))
        assertNull(PolicyCalls.grantOf(bot("atlas"), "send_sms"))
    }

    @Test fun enforcedForBotsAndForWorkTheUserDidntStart() {
        assertFalse(PolicyCalls.enforced("manager", Policy.Trigger.USER))
        assertFalse(PolicyCalls.enforced(null, Policy.Trigger.USER))
        assertTrue(PolicyCalls.enforced("atlas", Policy.Trigger.USER))
        assertTrue(PolicyCalls.enforced("manager", Policy.Trigger.ROUTINE))
        assertTrue(PolicyCalls.enforced("manager", Policy.Trigger.BOT))
    }

    @Test fun userMessagesReachThePolicyBoundOrNot() {
        val seen = ArrayList<Pair<String, String?>>()
        TeamHooks.onUserMessage = { c, b -> seen += c to b }
        try {
            val b = TeamHooks.Binding("atlas", "Atlas", null) {}
            assertEquals("user", b.origin.trigger)
            TeamHooks.bind("team-atlas-g1", b)
            TeamHooks.message("team-atlas-g1", "USER", "actually, don't")
            TeamHooks.message("main", "USER", "hello")
            TeamHooks.message("main", "BLAZE", "hi")
            assertEquals(listOf("team-atlas-g1" to "atlas", "main" to null), seen)
            TeamHooks.unbind("team-atlas-g1")
        } finally { TeamHooks.onUserMessage = null }
    }
}
