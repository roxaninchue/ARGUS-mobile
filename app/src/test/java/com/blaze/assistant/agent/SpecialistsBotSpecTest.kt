package com.blaze.assistant.agent

import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.Workers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-TM-01: Specialists.Spec carries the Bot-spec fields; old saved JSON reads and writes exactly as before. */
class SpecialistsBotSpecTest {
    @Test fun oldJsonIsUnchanged() {
        val old = """[{"id":"farm","name":"Farming advisor","prompt":"p","groups":"web,memory","made_by":"user"}]"""
        val l = Specialists.parse(old)
        assertEquals("", l[0].soul); assertEquals("", l[0].modelPref)
        val back = Specialists.toJson(l)
        assertFalse(back.contains("soul")); assertFalse(back.contains("model_pref"))
        assertEquals(Specialists.parse(back), l)
    }

    @Test fun newFieldsRoundTrip() {
        val s = Specialists.Spec("farm", "Farming advisor", "p", setOf("web"), true, soul = "# Who you are\nFarmer", avatar = "Tilt", color = "#22c55e",
            memoryScope = "team", workspace = "ubuntu", voice = "v1", managedBy = "manager", modelPref = "flare", madeBy = "user")
        val back = Specialists.parse(Specialists.toJson(listOf(s)))[0]
        assertEquals(s.copy(custom = true), back)
    }

    @Test fun builtInsBecomeHiddenWorkerTemplates() {
        val w = Specialists.builtIn.map { Workers.of(it, 1L) }
        assertEquals(10, w.size)
        assertTrue(w.all { it.kind == BotKind.WORKER && it.hidden && it.id.startsWith("worker:") })
        assertEquals(setOf("web", "memory"), w.first { it.id == "worker:researcher" }.toolGroups)
        val tiered = Workers.of(Specialists.builtIn[0].copy(modelPref = "inferno", avatar = "polar"), 1L)
        assertEquals("inferno", tiered.engine.model.tier); assertEquals(5, tiered.avatar.face)
    }
}
