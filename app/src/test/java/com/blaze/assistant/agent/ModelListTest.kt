package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model picker list (owner, 13 Nov 2026): one row per model, no provider, search, signal bars, maker logos. */
class ModelListTest {
    private fun m(key: String, name: String, prov: String, ms: Int, success: Double? = null, tools: Boolean = false) =
        LiveModels.Member(key, key, name, prov, ms = ms, success = success, tools = tools)

    @Test fun namesKeepTheirVersionDots() {
        assertEquals("Claude Opus 5.5", LiveModels.prettyName("coarena/claude-opus-5-5"))
        assertEquals("Gemini 3.8 Flash", LiveModels.prettyName("gemini-3-8-flash"))
        assertEquals("Mimo V2.6 Pro", LiveModels.prettyName("mimo-v2-6-pro"))
        assertEquals("Voxtral Small 2507", LiveModels.prettyName("voxtral-small-2507"))
    }

    @Test fun sameModelFromTwoProvidersIsOneRowAndTheHealthierFasterOneWins() {
        val rows = ModelList.rows(listOf(
            m("ap/free/mimo-v2.6-pro", "Mimo V2.6 Pro", "Apinex", 3000),
            m("co/mimo-v2-6-pro", LiveModels.prettyName("mimo-v2-6-pro"), "Coarena", 3400),
            m("ap/free/gemini-3.8-flash", "Gemini 3.8 Flash", "Apinex", 3900),
            m("co/gemini-3-8-flash", LiveModels.prettyName("gemini-3-8-flash"), "Coarena", 1200),
        ))
        assertEquals(2, rows.size)
        assertEquals("ap/free/mimo-v2.6-pro", rows[0].best.key)
        assertEquals(setOf("ap/free/mimo-v2.6-pro", "co/mimo-v2-6-pro"), rows[0].keys)
        assertEquals("co/gemini-3-8-flash", rows[1].best.key)
        assertEquals(rows[1], ModelList.rowOf(rows, "ap/free/gemini-3.8-flash"))
    }

    @Test fun detailNeverNamesTheProvider() {
        val r = ModelList.rows(listOf(m("ap/glm-5.3", "GLM 5.3", "Apinex", 3700, tools = true))).single()
        assertEquals("tools", ModelList.detail(r))
        assertTrue("Apinex" !in ModelList.detail(r))
    }

    @Test fun barsFollowSpeedAndHealth() {
        assertEquals(4, ModelList.bars(800, 1.0))
        assertEquals(3, ModelList.bars(2500, null))
        assertEquals(2, ModelList.bars(4500, null))
        assertEquals(1, ModelList.bars(6200, null))
        assertEquals(3, ModelList.bars(800, 0.85))
        assertEquals(2, ModelList.bars(800, 0.5))
        assertEquals(1, ModelList.bars(6200, 0.5))
        assertEquals(3, ModelList.bars(0, null))
    }

    @Test fun searchMatchesEveryWordInNameOrMaker() {
        val rows = ModelList.rows(listOf(m("a", "Gemini 3.8 Flash", "x", 1), m("b", "Kimi K3", "x", 1), m("c", "Claude Fable 5.2", "x", 1)))
        assertEquals(listOf("Gemini 3.8 Flash"), ModelList.filter(rows, "gem fla").map { it.name })
        assertEquals(listOf("Claude Fable 5.2"), ModelList.filter(rows, "CLAUDE").map { it.name })
        assertEquals(3, ModelList.filter(rows, "  ").size)
        assertTrue(ModelList.filter(rows, "llama").isEmpty())
    }

    @Test fun brandsComeFromTheModelFamily() {
        assertEquals("claude", ModelList.brand("Claude Opus 5.5"))
        assertEquals("gemini", ModelList.brand("Gemini 3.8 Flash"))
        assertEquals("gemma", ModelList.brand("Gemma 3 27b"))
        assertEquals("xiaomimimo", ModelList.brand("Mimo V2.6 Pro"))
        assertEquals("zhipu", ModelList.brand("GLM 5.3"))
        assertEquals("kimi", ModelList.brand("Kimi K3"))
        assertEquals("nvidia", ModelList.brand("Nemotron 3.5 Lightning"))
        assertEquals("mistral", ModelList.brand("Voxtral Small 2507"))
        assertEquals("hunyuan", ModelList.brand("Hy4"))
        assertEquals("openai", ModelList.brand("GPT OSS 120b"))
        assertEquals("qwen", ModelList.brand("Qwen 3.8 MAX"))
        assertNull(ModelList.brand("Mystery Model"))
    }

    @Test fun aPickThatDidNotAnswerHidesGoesAutoAndComesBackOnANewerList() {
        ModelPick.clearDown()
        val a = LiveModels.Member("ap/kimi-k3", "ap/kimi-k3", "Kimi K3", "x", route = "r1", ms = 900)
        val b = LiveModels.Member("co/glm-5.3", "co/glm-5.3", "GLM 5.3", "x", route = "r2", ms = 900)
        val list = listOf(a, b)
        val t0 = 1_000_000L
        ModelPick.served(a.key, "r1", list, t0)            // it answered itself: nothing hidden
        assertTrue(!ModelPick.isDown(a.key, listAt = t0 - 1, now = t0))
        ModelPick.served(a.key, "r2", list, t0)            // another route answered: the pick failed at the gateway
        assertTrue(ModelPick.isDown(a.key, listAt = t0 + 10, now = t0 + 60_000))          // inside the hold, even with a new list
        assertEquals(listOf("GLM 5.3"), ModelList.rows(list) { ModelPick.isDown(it, listAt = t0 - 1, now = t0 + 60_000) }.map { it.name })
        ModelPick.failed(b.key)                             // just now: calls on b go Auto, the chip says so
        assertEquals(ModelPick.Status.DOWN, ModelPick.status(list, c = ModelPick.Choice(b.key)).first)
        assertNull(ModelPick.route(list, c = ModelPick.Choice(b.key)))
        assertEquals("GLM 5.3 · unavailable", ModelPick.label(list, ModelPick.Choice(b.key)))
        assertTrue(ModelPick.isDown(a.key, listAt = t0 - 1, now = t0 + ModelPick.HOLD_MS + 1))   // hold over, but no newer list yet
        assertTrue(!ModelPick.isDown(a.key, listAt = t0 + 5, now = t0 + ModelPick.HOLD_MS + 1)) // a list fetched after the miss: back
        ModelPick.failed(b.key, t0)
        assertTrue(!ModelPick.isDown(b.key, listAt = t0 - 1, now = t0 + ModelPick.MAX_HOLD_MS))  // never hidden past the cap
        ModelPick.clearDown()
    }

    @Test fun anUncomparableServedRouteHidesNothing() {
        ModelPick.clearDown()
        val a = LiveModels.Member("ap/kimi-k3", "ap/kimi-k3", "Kimi K3", "x", ms = 900)   // no route token on the list
        ModelPick.served(a.key, "tokenXYZ", listOf(a), 5L)
        assertTrue(!ModelPick.isDown(a.key, listAt = 0, now = 6L))
        ModelPick.served(a.key, "co/glm-5.3", listOf(a), 5L)                                  // provider/model form compares
        assertTrue(ModelPick.isDown(a.key, listAt = 0, now = 6L))
        ModelPick.clearDown()
    }
}
