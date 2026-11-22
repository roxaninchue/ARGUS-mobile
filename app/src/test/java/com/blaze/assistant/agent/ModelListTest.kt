package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("Voxtral Small", LiveModels.prettyName("voxtral-small-2507"))
        // owner 21:10 screenshot: "Deepseek V4 1 Flash 1017"
        assertEquals("DeepSeek V4.1 Flash", LiveModels.prettyName("deepseek/deepseek-v4-1-flash-1017"))
        assertEquals("GPT 4o", LiveModels.prettyName("gpt-4o-2024-08-06"))
        assertEquals("Qwen3 235b A22b", LiveModels.prettyName("qwen3-235b-a22b-20250701"))
        assertEquals("GPT OSS 120b", LiveModels.prettyName("openai/gpt-oss-120b:free"))
        // Owner 16 Nov 2026: no "Free" in a model's name.
        assertFalse(LiveModels.prettyName("deepseek-v4-1-flash-free").contains("Free"))
        assertFalse(LiveModels.prettyName("free-gemma-7b").contains("Free"))
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

    // ---------------- 0.55.7 grouped list ----------------

    private fun mm(key: String, name: String, ms: Int, tools: Boolean = false, vision: Boolean = false, success: Double? = null) =
        LiveModels.Member(key, key, name, "x", ms = ms, success = success, tools = tools, vision = vision)

    private val sample = listOf(
        mm("a/gpt-oss-120b", "GPT OSS 120b", 900, tools = true),
        mm("a/gpt-oss-20b", "GPT OSS 20b", 600, tools = true),
        mm("b/nemotron-super", "Nemotron 3.5 Super VL 120b", 1800, vision = true),
        mm("b/nemotron-bolt", "Nemotron 3.5 Bolt E4b", 500),
        mm("c/deepseek-flash", "DeepSeek Flash", 400),
        mm("d/mystery", "Phonellm Mini Alpha 2", 0, tools = true),
        mm("e/codestral", "Codestral Latest", 800),
        mm("f/gemma", "Gemma 3 27b", 700), mm("g/gemini", "Gemini 3.8 Flash", 1200),
    )

    @Test fun oneCardPerMakerAToZWithOtherLast() {
        val secs = ModelList.sections(ModelList.rows(sample))
        assertEquals(listOf("DeepSeek", "Google", "Mistral", "NVIDIA", "OpenAI", ModelList.OTHER), secs.map { it.title })
        assertEquals("openai", secs.first { it.title == "OpenAI" }.brand)
        assertNull(secs.last().brand)
        assertEquals(2, secs.first { it.title == "Google" }.items.size)   // Gemini + Gemma share Google's card
    }

    @Test fun insideACardFastestFirstAndAFailingOneLastButStillShown() {
        val rows = ModelList.rows(sample)
        val secs = ModelList.sections(rows, down = { it.name == "GPT OSS 20b" })
        val openai = secs.first { it.title == "OpenAI" }.items
        assertEquals(listOf("GPT OSS 120b", "GPT OSS 20b"), openai.map { it.row.name })
        assertEquals(ModelList.Tone.DOWN, openai.last().tone)
        assertEquals(listOf("Nemotron 3.5 Bolt E4b", "Nemotron 3.5 Super VL 120b"), secs.first { it.title == "NVIDIA" }.items.map { it.row.name })
        assertEquals(ModelList.Tone.SLOW, secs.first { it.title == "NVIDIA" }.items.last().tone)
    }

    @Test fun filtersNarrowTheCardsAndDropEmptyOnes() {
        val rows = ModelList.rows(sample)
        assertEquals(listOf("OpenAI", ModelList.OTHER), ModelList.sections(rows, ModelList.Filter.TOOLS).map { it.title })
        assertEquals(listOf("NVIDIA"), ModelList.sections(rows, ModelList.Filter.VISION).map { it.title })
        val fast = ModelList.sections(rows, ModelList.Filter.FAST).flatMap { s -> s.items.map { it.row.name } }
        assertTrue("Nemotron 3.5 Super VL 120b" !in fast)   // 1.8 s
        assertTrue("Phonellm Mini Alpha 2" !in fast)        // unmeasured is not fast
        assertTrue("Gemini 3.8 Flash" in fast)              // 1.2 s
        assertEquals(listOf("OpenAI"), ModelList.sections(rows, ModelList.Filter.TOOLS, "gpt 120").map { it.title })
    }

    @Test fun speedAndSize() {
        assertEquals("0.9s", ModelList.speed(900))
        assertEquals("1.8s", ModelList.speed(1800))
        assertEquals("", ModelList.speed(0))
        assertEquals("12s", ModelList.speed(12_400))
        fun size(n: String) = ModelList.sizeRange(n)?.let { n.substring(it) }
        assertEquals("120b", size("GPT OSS 120b"))
        assertEquals("27B", size("Qwen3.8 27B"))
        assertEquals("1.5b", size("Tiny 1.5b Chat"))
        assertNull(size("Nemotron 3.5 Bolt E4b"))
        assertNull(size("MiniCPM V 4.7 1Bx"))
        assertNull(size("DeepSeek Flash"))
    }

    @Test fun recentKeepsTheLastTwoPicksAndSkipsGoneOrFailing() {
        var s = ""
        s = ModelList.pushRecent(s, "a/gpt-oss-120b")
        s = ModelList.pushRecent(s, "b/nemotron-bolt")
        s = ModelList.pushRecent(s, "a/gpt-oss-120b")
        s = ModelList.pushRecent(s, "c/deepseek-flash")
        assertEquals("c/deepseek-flash\na/gpt-oss-120b", s)
        val rows = ModelList.rows(sample)
        assertEquals(listOf("DeepSeek Flash", "GPT OSS 120b"), ModelList.recent(rows, s).map { it.name })
        assertEquals(listOf("GPT OSS 120b"), ModelList.recent(rows, s) { it.name == "DeepSeek Flash" }.map { it.name })
        assertEquals(listOf("GPT OSS 120b"), ModelList.recent(rows, "gone/model\na/gpt-oss-120b").map { it.name })
    }
}
