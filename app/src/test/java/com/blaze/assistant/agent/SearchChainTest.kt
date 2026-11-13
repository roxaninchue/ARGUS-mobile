package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.53.0: web search always tries the next keyless source, news feeds first for news, then simpler words. */
class SearchChainTest {
    @Test fun newsQueriesAskTheNewsFeedsFirst() {
        assertTrue(SearchChain.newsy("latest news NVIDIA CEO"))
        assertEquals(SearchChain.Source.BING_NEWS, SearchChain.order(true).first())
        assertEquals(SearchChain.Source.DDG, SearchChain.order(false).first())
        assertFalse(SearchChain.newsy("how do binary search trees work"))
    }

    @Test fun everyOrderHasEverySource() {
        for (n in listOf(true, false)) assertEquals(SearchChain.Source.values().toSet(), SearchChain.order(n).toSet())
        assertFalse(SearchChain.Source.DDG in SearchChain.retryOrder(true))
    }

    @Test fun simplerWords() {
        assertEquals("NVIDIA CEO", SearchChain.simpler("latest news NVIDIA CEO"))
        assertEquals("Zambia kwacha exchange rate", SearchChain.simpler("what is the latest Zambia kwacha exchange rate today?"))
        assertNull(SearchChain.simpler("Jensen Huang"))
        assertNull(SearchChain.simpler("latest news"))
    }
}
