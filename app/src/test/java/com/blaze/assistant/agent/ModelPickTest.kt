package com.blaze.assistant.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.53.0 manual model + effort picker (ModelPick) on a gateway 0.9.11 live list. */
class ModelPickTest {
    private val body = """{"object":"list","data":[
      {"id":"ap/free/kimi-k3","provider":"ap","model":"free/kimi-k3","route":"r_0000000000000001","latency_ms":2353,"success":1,"lanes":["agent","chat"],"tools":true,"vision":false,"verified":["tools"],"efforts":["low","medium","high"],"default_effort":"medium"},
      {"id":"ap/free/gemini-3.8-flash","provider":"ap","model":"free/gemini-3.8-flash","route":"r_0000000000000002","latency_ms":980,"success":1,"lanes":["vision"],"tools":false,"vision":true,"verified":["vision"],"efforts":[],"default_effort":null},
      {"id":"groq/x","provider":"groq","model":"x","efforts":["low","turbo"],"default_effort":"turbo"}
    ]}"""
    private val list = LiveModels.parseLive(body)

    @After fun reset() = ModelPick.auto()

    @Test fun parsesEffortsAndDropsUnknownOnes() {
        assertEquals(listOf("low", "medium", "high"), list[0].efforts)
        assertEquals("medium", list[0].defaultEffort)
        assertEquals(emptyList<String>(), list[1].efforts)
        assertEquals(listOf("low"), list[2].efforts)
        assertNull(list[2].defaultEffort)
    }

    @Test fun autoByDefaultSendsNothing() {
        assertEquals(ModelPick.Status.AUTO, ModelPick.status(list).first)
        assertNull(ModelPick.route(list, tools = true))
        assertNull(ModelPick.effort(list))
        assertEquals("Auto", ModelPick.label(list))
    }

    @Test fun pickRoutesEveryFittingCallWithItsEffort() {
        ModelPick.set("ap/free/kimi-k3", "high")
        assertEquals("ap/free/kimi-k3", ModelPick.route(list, tools = true))
        assertEquals("high", ModelPick.effort(list))
        assertEquals("Kimi K3 · High", ModelPick.label(list))
        assertEquals(ModelPick.Choice("ap/free/kimi-k3", "high"), ModelPick.current.value)   // Settings needs a Context on Gradle
    }

    @Test fun effortFallsBackToTheModelDefaultAndNeverToOneItLacks() {
        ModelPick.set("ap/free/kimi-k3")
        assertEquals("medium", ModelPick.effort(list))
        ModelPick.set("ap/free/kimi-k3", "turbo")
        assertEquals("medium", ModelPick.effort(list))
        ModelPick.set("ap/free/gemini-3.8-flash", "high")
        assertNull(ModelPick.effort(list))
        assertEquals("Gemini 3.8 Flash", ModelPick.label(list))
    }

    @Test fun aPickThatDoesNotFitOrLeftTheListGoesAuto() {
        ModelPick.set("ap/free/gemini-3.8-flash")
        assertEquals(ModelPick.Status.NO_TOOLS, ModelPick.status(list, tools = true).first)
        assertNull(ModelPick.route(list, tools = true))
        assertEquals("ap/free/gemini-3.8-flash", ModelPick.route(list, vision = true))
        ModelPick.set("ap/free/kimi-k3")
        assertEquals(ModelPick.Status.NO_VISION, ModelPick.status(list, vision = true).first)
        ModelPick.set("ap/claude-opus-5.5", "high")
        assertEquals(ModelPick.Status.OFF_LIST, ModelPick.status(list).first)
        assertNull(ModelPick.route(list))
        assertNull(ModelPick.effort(list))
        assertEquals("Claude Opus 5.5 · unavailable", ModelPick.label(list))
    }

    @Test fun detailLine() {
        assertEquals("Apinex · 2.4 s · tools", ModelPick.detail(list[0]))
        assertEquals("Apinex · 980 ms · vision", ModelPick.detail(list[1]))
    }
}
