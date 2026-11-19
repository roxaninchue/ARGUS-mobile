package com.blaze.assistant.policy

import com.blaze.assistant.policy.NeedsYou.CardSrc
import com.blaze.assistant.policy.NeedsYou.Filter
import com.blaze.assistant.policy.NeedsYou.JobSrc
import com.blaze.assistant.policy.NeedsYou.Kind
import com.blaze.assistant.team.ActionClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-SF-03: the one needs-you query (ordering, filters, bulk / swipe / biometric rules, count, dot and banner from one list). */
class NeedsYouTest {
    private val now = 10_000_000L
    private fun appr(id: String, cls: ActionClass?, bot: String = "nova", at: Long = now - 60_000, exp: Long = 0, kind: String = "approval") =
        CardSrc(id, kind, bot, bot.replaceFirstChar { it.uppercase() }, cls, "t$id", "d$id", at, exp)
    private fun card(id: String, kind: String, bot: String = "atlas", at: Long = now - 60_000) =
        CardSrc(id, kind, bot, bot.replaceFirstChar { it.uppercase() }, null, "t$id", "d$id", at)
    private fun job(id: String, failed: Boolean, at: Long, bot: String = "tempo") =
        JobSrc(id, bot, bot.replaceFirstChar { it.uppercase() }, "j$id", "", failed = failed, suggestion = !failed, at = at)

    private val sample = listOf(
        card("q1", "question", at = now - 5_000), card("q2", "question", at = now - 1_000),
        card("c1", "connect"),
        appr("w1", ActionClass.WRITE_REMOTE, exp = now + 20 * 60_000), appr("w2", ActionClass.READ, exp = now + 3 * 60_000),
        appr("s1", ActionClass.SPEND, exp = now + 24 * 60_000), appr("d1", ActionClass.DELETE, exp = now + 10 * 60_000),
    )
    private val jobs = listOf(job("f1", true, now - 9_000), job("f2", true, now - 2_000), job("g1", false, now - 1_000))

    @Test fun urgencyOrder() {
        val ids = NeedsYou.build(sample, jobs, now).map { it.id }
        assertEquals(listOf("d1", "s1", "w2", "w1", "q2", "q1", "c1", "f2", "f1", "g1"), ids)
    }

    @Test fun expiredAndAnsweredDropOut() {
        val l = NeedsYou.build(listOf(appr("x", ActionClass.READ, exp = now - 1), appr("y", ActionClass.READ).copy(pending = false),
            card("z", "approval").copy(createdAt = now - NeedsYou.EXPIRY_MS - 1)), emptyList(), now)
        assertTrue(l.isEmpty())
    }

    @Test fun approvalWithoutExpiryUsesTwentyFiveMinutes() {
        val i = NeedsYou.build(listOf(appr("a", ActionClass.READ, at = now)), emptyList(), now).single()
        assertEquals(now + 25 * 60_000, i.expiresAt)
        assertEquals(25, NeedsYou.minutesLeft(i, now))
    }

    @Test fun draftIsASendApproval() {
        val i = NeedsYou.build(listOf(appr("dr", null, kind = "draft")), emptyList(), now).single()
        assertEquals(Kind.APPROVAL, i.kind); assertEquals(ActionClass.SEND, i.actionClass)
        assertFalse(NeedsYou.swipeApprove(i)); assertFalse(NeedsYou.alwaysOffered(i))
    }

    @Test fun filtersAndCounts() {
        val l = NeedsYou.build(sample, jobs, now)
        assertEquals(4, NeedsYou.filter(l, Filter.APPROVALS).size)
        assertEquals(2, NeedsYou.filter(l, Filter.QUESTIONS).size)
        assertEquals(1, NeedsYou.filter(l, Filter.CONNECT).size)
        assertEquals(2, NeedsYou.filter(l, Filter.FAILED).size)
        assertEquals(1, NeedsYou.filter(l, Filter.SUGGESTIONS).size)
        assertEquals(l, NeedsYou.filter(l, Filter.ALL))
        val c = NeedsYou.counts(l)
        assertEquals(l.size, c[Filter.ALL])
        assertEquals(l.size, Filter.entries.filter { it != Filter.ALL }.sumOf { c[it]!! })
    }

    @Test fun countDotBannerFromSameList() {
        val l = NeedsYou.build(sample, jobs, now)
        assertEquals(l.size, NeedsYou.count(l))
        assertTrue(NeedsYou.dot(l))
        val b = NeedsYou.banner(l)
        assertEquals(l.size, b.count)
        assertEquals(listOf("Nova", "Atlas", "Tempo"), b.bots)
        assertFalse(b.approvalsOnly)
        val empty = NeedsYou.build(emptyList(), emptyList(), now)
        assertFalse(NeedsYou.dot(empty)); assertEquals(0, NeedsYou.banner(empty).count)
        val onlyAppr = NeedsYou.build(listOf(appr("a", ActionClass.READ)), emptyList(), now)
        assertTrue(NeedsYou.banner(onlyAppr).approvalsOnly)
    }

    @Test fun bulkOnlyOneBotOneKindNoMoneyOrDelete() {
        val l = NeedsYou.build(sample + appr("w3", ActionClass.WRITE_LOCAL, bot = "atlas"), jobs, now).associateBy { it.id }
        assertTrue(NeedsYou.bulkAllowed(listOf(l["w1"]!!, l["w2"]!!)))
        assertFalse(NeedsYou.bulkAllowed(listOf(l["w1"]!!)))
        assertFalse(NeedsYou.bulkAllowed(listOf(l["w1"]!!, l["w3"]!!)))          // two bots
        assertFalse(NeedsYou.bulkAllowed(listOf(l["w1"]!!, l["s1"]!!)))          // SPEND
        assertFalse(NeedsYou.bulkAllowed(listOf(l["w1"]!!, l["d1"]!!)))          // DELETE
        assertFalse(NeedsYou.bulkAllowed(listOf(l["q1"]!!, l["c1"]!!)))          // two kinds
        assertTrue(NeedsYou.bulkAllowed(listOf(l["q1"]!!, l["q2"]!!)))
    }

    @Test fun swipeOnlyLowRisk() {
        for (c in ActionClass.entries) {
            val i = NeedsYou.build(listOf(appr("a", c)), emptyList(), now).single()
            assertEquals(c.name, c == ActionClass.READ || c == ActionClass.WRITE_LOCAL, NeedsYou.swipeApprove(i))
            assertEquals(c.name, c == ActionClass.SPEND || c == ActionClass.DELETE, NeedsYou.needsBiometric(i))
        }
        val q = NeedsYou.build(listOf(card("q", "question")), emptyList(), now).single()
        assertFalse(NeedsYou.swipeApprove(q)); assertFalse(NeedsYou.needsBiometric(q))
    }

    @Test fun riskLevels() {
        assertEquals(NeedsYou.Risk.HIGH, NeedsYou.riskOf(ActionClass.CREDENTIAL))
        assertEquals(NeedsYou.Risk.LOW, NeedsYou.riskOf(ActionClass.WRITE_LOCAL))
        assertEquals(NeedsYou.Risk.MEDIUM, NeedsYou.riskOf(ActionClass.SEND))
    }
}
