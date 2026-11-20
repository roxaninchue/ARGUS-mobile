package com.blaze.assistant.agent

import com.blaze.assistant.orchestra.OrchestraRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BotOfferTest {
    private val day = 86_400_000L
    private val now = 1_795_000_000_000L
    private fun jobs(n: Int, area: String = "research", daysAgo: Long = 1) = (1..n).map { BotOffer.Job(now - it * daysAgo * day, area) }

    @Test fun deepResearchBlueprintsAndResearchersAreTheSameArea() {
        assertEquals("research", BotOffer.areaOf(OrchestraRuns.SPECIALIST, OrchestraRuns.encode("How Anthropic was created", "research", "")))
        assertEquals("research", BotOffer.areaOf("researcher", "x"))
        assertEquals("code", BotOffer.areaOf(OrchestraRuns.SPECIALIST, OrchestraRuns.encode("A todo app", "apk", "")))
        assertNull(BotOffer.areaOf("", "plain agent"))
    }

    @Test fun thirdJobInTwoWeeksOffersNova() {
        assertNull(BotOffer.decide("research", jobs(2), emptyMap(), emptyList(), now))
        val p = BotOffer.decide("research", jobs(3), emptyMap(), emptyList(), now)
        assertNotNull(p); p!!
        assertEquals("Nova", p.name); assertEquals("Research", p.label); assertEquals("#8b5cf6", p.color)
        assertEquals("You've asked for deep research 3 times in two weeks.", BotOffer.why(p))
        assertEquals(p, BotOffer.parse(p.meta()))
    }

    @Test fun oldJobsDoNotCount() = assertNull(BotOffer.decide("research", jobs(3, daysAgo = 6), emptyMap(), emptyList(), now))

    @Test fun declinedStaysQuietForThirtyDaysAndAddedForever() {
        assertNull(BotOffer.decide("research", jobs(5), mapOf("research" to BotOffer.Past(BotOffer.TURNED_DOWN, now - 10 * day)), emptyList(), now))
        assertNotNull(BotOffer.decide("research", jobs(5), mapOf("research" to BotOffer.Past(BotOffer.TURNED_DOWN, now - 31 * day)), emptyList(), now))
        assertNull(BotOffer.decide("research", jobs(5), mapOf("research" to BotOffer.Past(BotOffer.TAKEN, now - 300 * day)), emptyList(), now))
        assertNull(BotOffer.decide("research", jobs(5), mapOf("research" to BotOffer.Past(BotOffer.ASKING, now - 2 * day)), emptyList(), now))
    }

    @Test fun aTeamThatCoversItOrIsFullGetsNoCard() {
        assertNull(BotOffer.decide("research", jobs(3), emptyMap(), listOf(BotOffer.Member("Orion", "Research")), now))
        val full = (1..4).map { BotOffer.Member("B$it", "Other $it") }
        assertNull(BotOffer.decide("research", jobs(3), emptyMap(), full, now))
        assertEquals("Vega", BotOffer.decide("research", jobs(3), emptyMap(), listOf(BotOffer.Member("Nova", "Friend")), now)?.name)
    }
}
