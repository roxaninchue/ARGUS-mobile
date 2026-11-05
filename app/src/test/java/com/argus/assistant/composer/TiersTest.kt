package com.argus.assistant.composer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.52.0: Spark / Flare / Inferno map to real gateway route ids, and agent roles pick a tier only on Auto. */
class TiersTest {
    @Test fun tiersLeadWithTheAgreedModels() {
        assertEquals("groq/qwen/qwen3.8-27b", ModelChoice.routes(ModelChoice.Family.SPARK).first())
        assertEquals("yeta/deepseek-v4.1-flash", ModelChoice.routes(ModelChoice.Family.FLARE).first())
        assertEquals("yeta/glm-5.3-flash", ModelChoice.routes(ModelChoice.Family.INFERNO).first())
        assertTrue(ModelChoice.routes(ModelChoice.Family.AUTO).isEmpty())
    }

    @Test fun everyTierIdIsInTheLiveRoutes() {
        // the live gateway 0.8.60 task routes (provider/model), as in work/out/gw-routes-quality.json
        val live = setOf("yeta/glm-5.3-flash", "yeta/deepseek-v4.1-flash", "deepseek/deepseek-flash", "gemini/gemini-3.5-flash",
            "tokenharbor/deepseek-v4.1-flash:free", "groq/qwen/qwen3.8-27b", "nous/reflection/beam-1.0-501b-a23b:free", "cerebras/gpt-oss-120b",
            "gemini/gemini-3.5-flash-lite", "groq/openai/gpt-oss-20b")
        ModelChoice.Family.values().forEach { f -> ModelChoice.routes(f).forEach { assertTrue("$f $it", it in live) } }
    }

    @Test fun rolesPickATierOnlyOnAuto() {
        assertEquals(ModelChoice.Family.INFERNO, ModelChoice.forRole("planner", ModelChoice.Family.AUTO))
        assertEquals(ModelChoice.Family.SPARK, ModelChoice.forRole("researcher", ModelChoice.Family.AUTO))
        assertEquals(ModelChoice.Family.FLARE, ModelChoice.forRole("coder", ModelChoice.Family.AUTO))
        assertEquals(ModelChoice.Family.SPARK, ModelChoice.forRole("planner", ModelChoice.Family.SPARK))
        assertEquals("inferno", ModelChoice.wire(ModelChoice.forRole("reviewer", ModelChoice.Family.AUTO)))
        assertTrue(JSONObject().put("blaze_family", "flare").has("blaze_family"))
    }

    /** 0.52.1 Argus rename: new labels, same ids on the wire, and saved picks keep their tier. */
    @Test fun argusLabelsKeepIdsAndMigrateSavedPicks() {
        assertEquals(listOf("Insight", "Oracle", "Panoptes"), listOf(ModelChoice.Family.SPARK, ModelChoice.Family.FLARE, ModelChoice.Family.INFERNO).map { it.label })
        assertEquals(listOf("spark", "flare", "inferno"), listOf(ModelChoice.Family.SPARK, ModelChoice.Family.FLARE, ModelChoice.Family.INFERNO).map { ModelChoice.wire(it) })
        assertEquals(null, ModelChoice.migrate("spark")); assertEquals(null, ModelChoice.migrate("auto"))
        assertEquals("spark", ModelChoice.migrate("Spark")); assertEquals("flare", ModelChoice.migrate("Oracle"))
        assertEquals("inferno", ModelChoice.migrate("Panoptes")); assertEquals("inferno", ModelChoice.migrate(" Inferno "))
        assertEquals("auto", ModelChoice.migrate("Blaze Max"))
        assertEquals(ModelChoice.Family.INFERNO, ModelChoice.of("panoptes")); assertEquals(ModelChoice.Family.SPARK, ModelChoice.of("Insight"))
    }
}
