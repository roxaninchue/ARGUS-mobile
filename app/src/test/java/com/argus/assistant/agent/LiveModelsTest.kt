package com.blaze.assistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-API-01: LiveModels, ported from A-team evals/test/v29.test.mjs (models.js cases) and v212.test.mjs (wire names). */
class LiveModelsTest {
    private val live = JSONObject("""{ "object": "list", "updated_at": "Tue 2026-11-10 10:40 PM CAT (UTC+02:00)", "count": 4, "data": [
      { "id": "groq/openai/gpt-oss-120b", "provider": "groq", "model": "openai/gpt-oss-120b", "route": "r_aaaaaaaaaaaaaaaa", "latency_ms": 380, "success": 0.98, "lanes": ["chat", "quick", "code", "agent"], "tools": true, "vision": false, "verified": ["tools"] },
      { "id": "gemini/gemini-2.5-flash", "provider": "gemini", "model": "gemini-2.5-flash", "route": "r_bbbbbbbbbbbbbbbb", "latency_ms": 520, "success": 0.9, "lanes": ["chat", "vision", "long"], "tools": true, "vision": true, "verified": ["vision"] },
      { "id": "openrouter/meta-llama/llama-4-scout:free", "provider": "openrouter", "model": "meta-llama/llama-4-scout:free", "route": "r_cccccccccccccccc", "latency_ms": 900, "success": 0.8, "lanes": ["chat", "agent"], "tools": true, "vision": false, "verified": { "tools": true } },
      { "id": "bad-entry" } ] }""")
    private val L = LiveModels.parseLive(live)

    @Test fun wireKeepsTheDeployedGatewayContract() {   // v212
        val w = LiveModels.Wire
        assertEquals(listOf("X-Blaze-Prefer", "blaze-auto", "x-blaze-", "X-Blaze-Avoid", "/v1/models/live", "x-blaze-prefer-status", "x-blaze-route", "x-blaze-fallback"),
            listOf(w.PREFER, w.AUTO, w.RESP_PREFIX, w.AVOID, w.LIVE, w.STATUS, w.ROUTE, w.FALLBACK))
        assertEquals("https://ai.blaze-app.workers.dev/v1/models/live", LiveModels.liveUrl("https://ai.blaze-app.workers.dev/v1/"))
    }

    @Test fun autoIsTheOnlyFixedMember() {   // v29 "models.js is the single source"
        assertEquals(listOf<String?>(null), LiveModels.CHAIN.map { it.pref })
    }

    @Test fun fallbackRules() {   // v29
        val f = LiveModels::onFail
        assertEquals("http_503" to true, f(503, false, "", false).let { it.kind to it.next })
        assertEquals("model_not_in_plan", f(403, false, """{"error":{"code":"model_not_in_plan"}}""", false).kind)
        assertEquals(LiveModels.SKIP_PLAN_MS, f(403, false, "", false).skipMs)
        assertEquals("unreachable", f(0, false, "", false).kind)
        assertEquals("empty", f(0, true, "", false).kind)
        assertTrue(f(401, false, "", false).fatal)
        assertTrue(f(400, false, "tools are not supported", true).retryNoTools)
    }

    @Test fun liveListParsedInGatewayOrder() {   // v29 v2.13 live list
        assertEquals(listOf("groq/openai/gpt-oss-120b", "gemini/gemini-2.5-flash", "openrouter/meta-llama/llama-4-scout:free"), L.map { it.key })
        assertEquals(listOf("GPT OSS 120b", "Groq", "openai/gpt-oss-120b", "r_aaaaaaaaaaaaaaaa"), L[0].let { listOf(it.name, it.prov, it.model, it.route) })
        assertEquals("meta-llama/llama-4-scout:free", L[2].model); assertEquals("Llama 4 Scout", L[2].name)
        assertTrue(LiveModels.parseLive(JSONObject("{}")).isEmpty()); assertTrue(LiveModels.parseLive(null as JSONObject?).isEmpty())
        assertTrue(LiveModels.parseLive("not json").isEmpty())
        assertEquals(380, L[0].ms); assertEquals(0.98, L[0].success!!, 1e-9)
    }

    @Test fun pickFitsTheTask() {   // v29 v2.13 pick
        assertEquals("groq/openai/gpt-oss-120b", LiveModels.pick(L)!!.key)
        assertEquals("groq/openai/gpt-oss-120b", LiveModels.pick(L, LiveModels.Need(tools = true))!!.key)
        assertEquals("gemini has tools but not verified, so it is not picked for agent runs", "openrouter/meta-llama/llama-4-scout:free",
            LiveModels.pick(L, LiveModels.Need(tools = true, avoid = listOf("groq/openai/gpt-oss-120b")))!!.key)
        assertEquals("gemini/gemini-2.5-flash", LiveModels.pick(L, LiveModels.Need(vision = true))!!.key)
        assertNull("nothing verified for both", LiveModels.pick(L, LiveModels.Need(vision = true, tools = true)))
        assertNull(LiveModels.pick(emptyList()))
        // an avoided route token counts too (gateway 0.8.45 takes tokens back)
        assertEquals("gemini/gemini-2.5-flash", LiveModels.pick(L, LiveModels.Need(avoid = listOf("r_aaaaaaaaaaaaaaaa")))!!.key)
    }

    @Test fun planKeepsTheTurnsPick() {   // v29 v2.13 plan
        assertEquals(listOf("groq/openai/gpt-oss-120b", "auto"), LiveModels.plan(L).map { it.key })
        assertEquals("kept pick wins", listOf("openrouter/meta-llama/llama-4-scout:free", "auto"), LiveModels.plan(L, LiveModels.Need(), L[2]).map { it.key })
        assertEquals("a kept pick that left the list is replaced", listOf("groq/openai/gpt-oss-120b", "auto"), LiveModels.plan(L.take(2), LiveModels.Need(), L[2]).map { it.key })
        assertEquals(listOf("auto"), LiveModels.plan(emptyList(), LiveModels.Need(tools = true)).map { it.key })
        assertFalse(LiveModels.plan(L).any { "infron" in it.key })
    }

    @Test fun answeredReadsTheHeaders() {   // v29 v2.13 answered
        val a = LiveModels.answered(mapOf("X-Blaze-Prefer-Status" to "fallback", "X-Blaze-Route" to "r_bbbbbbbbbbbbbbbb", "X-Blaze-Fallback" to "1"), L)
        assertEquals(listOf<Any?>("fallback", true, "gemini/gemini-2.5-flash"), listOf(a.status, a.fallback, a.model?.key))
        // HttpURLConnection.headerFields shape: lists, a null key for the status line
        val b = LiveModels.answered(mapOf(null to listOf("HTTP/1.1 200 OK"), "x-blaze-prefer-status" to listOf("used"), "x-blaze-route" to listOf("r_aaaaaaaaaaaaaaaa"), "x-blaze-fallback" to listOf("0")), L)
        assertEquals(listOf<Any?>(false, "groq/openai/gpt-oss-120b"), listOf(b.fallback, b.model?.key))
        val none = LiveModels.answered(emptyMap(), L)
        assertEquals(listOf<Any?>(false, null), listOf(none.fallback, none.model))
        assertEquals("a/1, b/2", LiveModels.avoidHeader(listOf("a/1", "b/2", "a/1")))
        assertEquals(8, LiveModels.avoidHeader((0 until 12).map { "p/$it" }).split(", ").size)
    }

    @Test fun cacheRefreshesEveryFiveMinutesAndExpiresAfterSixHours() {
        var t = 1_000_000L
        val c = LiveModels.Cache(now = { t })
        var calls = 0
        assertTrue(c.refresh { calls++; live.toString() }); assertEquals(3, c.list().size)
        t += 60_000; assertFalse(c.refresh { calls++; null }); assertEquals(1, calls)
        t += LiveModels.TTL_MS; assertTrue(c.refresh { calls++; null }); assertEquals("a failed fetch keeps the last good list", 3, c.list().size)
        t += LiveModels.MAX_AGE_MS; assertTrue(c.list().isEmpty())
        assertTrue(c.refresh { throw RuntimeException("offline") }); assertTrue(c.list().isEmpty())
    }

    @Test fun modelRoutePrefersTheLivePick() {
        val agent = ModelRoute.task("chat", heavy = true, hasTools = true)
        assertEquals("groq/openai/gpt-oss-120b", ModelRoute.livePrefer(L, agent, hasTools = true))
        assertEquals("openrouter/meta-llama/llama-4-scout:free", ModelRoute.livePrefer(L, agent, hasTools = true, failed = listOf("groq/openai/gpt-oss-120b")))
        assertEquals("gemini/gemini-2.5-flash", ModelRoute.livePrefer(L, ModelRoute.task("vision", false, true), hasTools = false))
        assertEquals("groq/openai/gpt-oss-120b", ModelRoute.livePrefer(L, ModelRoute.task("quick", false, false), hasTools = false))
        assertNull(ModelRoute.livePrefer(emptyList(), agent, hasTools = true))
    }
}
