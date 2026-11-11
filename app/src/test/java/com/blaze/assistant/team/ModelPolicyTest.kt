package com.blaze.assistant.team

import com.blaze.assistant.team.ModelPolicies.Depth
import com.blaze.assistant.team.ModelPolicies.Think
import com.blaze.assistant.team.ModelPolicies.Turn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-AG-06: per-bot ModelPolicy (D6: Automatic, Depth, own Gemini key; no model-id picker). */
class ModelPolicyTest {
    private val chat = Turn("chat", heavy = false, hasTools = false, adaptive = Think.QUICK)
    private val act = Turn("agent", heavy = true, hasTools = true, adaptive = Think.NORMAL)
    private val pic = Turn("vision", heavy = false, hasTools = false, adaptive = Think.NORMAL)

    @Test fun automaticChangesNothing() {
        assertTrue(ModelPolicies.isAutomatic(ModelPolicy()))
        for (t in listOf(chat, act, pic)) assertNull(ModelPolicies.route(ModelPolicy(), t, hasKey = true))
        val body = JSONObject().put("model", "blaze-auto").put("blaze_task", "chat").put("messages", "[]")
        val before = body.toString()
        assertEquals(before, ModelPolicies.applyTo(body, ModelPolicies.route(ModelPolicy(), chat)).toString())
        // junk that sanitizes to the default is Automatic too
        assertTrue(ModelPolicies.isAutomatic(ModelPolicy(lane = "nope", tier = "huge", pinned = "openai/gpt-5", effort = "max")))
    }

    @Test fun noFreeTextModelIds() {
        for (bad in listOf("openai/gpt-5", "groq/llama-3.1-8b-instant", "gemini", "gemini-", "gemini:../../etc", "claude-4", " ", "gemini:GEMINI 3 PRO"))
            assertNull(bad, ModelPolicies.sanitize(ModelPolicy(pinned = bad)).pinned)
        assertEquals("gemini:gemini-3.5-flash", ModelPolicies.sanitize(ModelPolicy(pinned = "models/gemini-3.5-flash")).pinned)
        val offered = listOf("gemini-3.5-flash", "gemini-3.5-pro")
        assertEquals("gemini:gemini-3.5-pro", ModelPolicies.withGemini(ModelPolicy(), "gemini-3.5-pro", offered)!!.pinned)
        assertNull("only models from the key's own list", ModelPolicies.withGemini(ModelPolicy(), "gemini-9-ultra", offered))
        assertNull(ModelPolicies.withGemini(ModelPolicy(pinned = "gemini:gemini-3.5-pro"), null, offered)!!.pinned)
    }

    @Test fun depthIsSentAsTheFamilyHint() {
        val p = ModelPolicies.withDepth(ModelPolicy(), Depth.INFERNO)
        assertFalse(ModelPolicies.isAutomatic(p))
        val r = ModelPolicies.route(p, chat)!!
        assertEquals("inferno", r.family); assertEquals(Think.NORMAL, r.think)
        val b = ModelPolicies.applyTo(JSONObject().put("blaze_task", "chat"), r)
        assertEquals("inferno", b.getString("blaze_family")); assertEquals("chat", b.getString("blaze_task")); assertFalse(b.has("blaze_zero_retention"))
        assertEquals(listOf("Automatic", "Insight", "Oracle", "Panoptes"), Depth.values().map { it.label })
        assertEquals(Depth.FLARE, Depth.of(" FLARE ")); assertEquals(Depth.AUTO, Depth.of("oracle"))
    }

    @Test fun lanes() {
        val code = ModelPolicy(lane = "code")
        assertEquals("code", ModelPolicies.route(code, chat)!!.task)
        assertEquals("agent", ModelPolicies.route(code, act)!!.task)       // an action turn with tools always gets the strong lane
        assertEquals("vision", ModelPolicies.route(code, pic)!!.task)      // pictures always vision
        assertEquals("quick", ModelPolicies.route(ModelPolicy(lane = "quick", tier = "spark"), chat)!!.task)
        assertEquals("agent", ModelPolicies.route(ModelPolicy(lane = "quick"), act)!!.task)
        assertEquals("chat", ModelPolicies.route(ModelPolicy(tier = "flare"), chat)!!.task)   // agent lane without tools = what the turn is
        assertEquals("long", ModelPolicies.route(ModelPolicy(tier = "flare"), Turn("long", false, false, Think.NORMAL))!!.task)
    }

    @Test fun effortMovesTheThinkBudget() {
        fun think(e: String, t: Turn, tier: String? = null) = ModelPolicies.route(ModelPolicy(effort = e, tier = tier), t)!!.think
        assertEquals(Think.QUICK, think("low", act))
        assertEquals(Think.NORMAL, think("high", chat))
        assertEquals(Think.DEEP, think("xhigh", chat))
        assertEquals(Think.NORMAL, think("xhigh", chat, tier = "spark"))   // Insight caps at normal
        assertEquals(Think.NORMAL, think("low", chat, tier = "inferno"))    // Panoptes floors at normal
        assertEquals(Think.NORMAL, ModelPolicies.route(ModelPolicy(tier = "flare"), act)!!.think)  // medium = adaptive
    }

    @Test fun ownGeminiKeyAndCoolDown() {
        val p = ModelPolicy(pinned = "gemini:gemini-3.5-flash")
        assertEquals("gemini-3.5-flash", ModelPolicies.route(p, chat, hasKey = true)!!.gemini)
        val noKey = ModelPolicies.route(p, chat, hasKey = false)!!
        assertNull(noKey.gemini); assertTrue(noKey.note!!.contains("No Gemini key"))
        val cd = GeminiCooldown()
        assertTrue(cd.isQuota(429, null)); assertTrue(cd.isQuota(400, "RESOURCE_EXHAUSTED: quota")); assertFalse(cd.isQuota(500, "boom"))
        assertEquals(60_000L, cd.onQuota("atlas", 0, msg = "rate limit"))
        val c = cd.cooling("atlas", 30_000) { "14:05" }
        assertEquals("Gemini hit its limit; resting until 14:05.", c)
        val r = ModelPolicies.route(p, chat, hasKey = true, cooling = c)!!
        assertNull(r.gemini); assertEquals("Gemini hit its limit; resting until 14:05. ARGUS answered instead.", r.note)
        assertNull("another bot's key is not resting", cd.cooling("ledger", 30_000))
        assertNull(cd.cooling("atlas", 60_001))
        assertEquals(3_600_000L, cd.onQuota("atlas", 0, msg = "Quota exceeded: GenerateRequestsPerDay"))
        assertTrue(cd.cooling("atlas", 10) { "x" }!!.contains("daily quota"))
        assertEquals(7_000L, cd.onQuota("atlas", 0, retryAfterSec = 7))
        // zero retention never sends Google data to a non-zero-retention own key
        val zr = ModelPolicies.route(p.copy(zeroRetention = true), chat, hasKey = true)!!
        assertNull(zr.gemini); assertTrue(ModelPolicies.applyTo(JSONObject(), zr).getBoolean("blaze_zero_retention"))
    }

    @Test fun describeNeverNamesAModel() {
        assertEquals("Automatic", ModelPolicies.describe(ModelPolicy(), false))
        val d = ModelPolicies.describe(ModelPolicy(tier = "flare", effort = "high", pinned = "gemini:gemini-3.5-pro"), hasKey = true)
        assertEquals("Oracle · thinks harder · own Gemini key", d)
        assertFalse(d.contains("gemini-3.5"))
        assertEquals("Automatic · private models only", ModelPolicies.describe(ModelPolicy(zeroRetention = true), false))
        assertEquals(ModelPolicy(), ModelPolicies.reset())
    }
}
