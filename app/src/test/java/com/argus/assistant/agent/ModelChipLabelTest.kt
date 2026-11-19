package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 2026-11-14: back in the app the chip read "unavailable" until the picker was opened and given a moment. */
class ModelChipLabelTest {
    @Test fun noListYetIsNotUnavailable() {
        ModelPick.clearDown()
        val c = ModelPick.Choice("ap/free/kimi-k3")
        val plain = ModelPick.label(emptyList(), c)
        assertFalse(plain, plain.contains("unavailable"))
        assertEquals(LiveModels.prettyName("free/kimi-k3".substringAfter('/')), plain)
        ModelPick.failed(c.key)                                  // a real miss this session still says so
        assertTrue(ModelPick.label(emptyList(), c).endsWith("· unavailable"))
        ModelPick.clearDown()
        assertEquals("Auto", ModelPick.label(emptyList(), ModelPick.Choice("")))
    }

    @Test fun theCacheTicksOnEveryFetchSoTheChipRereads() {
        var t = 1_000_000L
        val cache = LiveModels.Cache(now = { t })
        val before = cache.changes.value
        assertFalse(cache.known())
        assertTrue(cache.refresh { null })                       // a failed fetch still ticks (the chip stops waiting)
        assertTrue(cache.changes.value > before)
        assertFalse(cache.known())
        t += LiveModels.TTL_MS
        val mid = cache.changes.value
        cache.refresh { "{\"data\":[]}" }
        assertTrue(cache.changes.value > mid)
    }
}
