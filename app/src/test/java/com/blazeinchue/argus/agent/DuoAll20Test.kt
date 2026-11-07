package com.blazeinchue.argus.agent


import com.blazeinchue.argus.BrandName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.45.20 feature batch items 9 (@all: Argus does, Halo brief or quiet) and 10 (no voice filler). Strict 5/5. */
class DuoAll20Test {
    private fun five(f: () -> Unit) = repeat(5) { f() }

    @Test fun halo_quietOrBrief_neverRepeatsArgus() = five {
        val argus = "The Eiffel Tower is 330 metres tall and was finished in 1889 for the World's Fair."
        assertEquals("", DuoAll.haloLine("[[quiet]]", argus))
        assertEquals("", DuoAll.haloLine("  ", argus))
        assertEquals("a rephrase is a repeat", "", DuoAll.haloLine("It is 330 metres tall, finished in 1889 for the World's Fair.", argus))
        val add = DuoAll.haloLine("Lifts to the top close at 22:30 in winter. Book ahead. Also bring a jacket. And water.", argus)
        assertEquals("Lifts to the top close at 22:30 in winter. Book ahead.", add)
        assertTrue(DuoAll.haloLine("x ".repeat(400) + "unique words here", "").length <= DuoAll.HALO_CHARS)
    }

    @Test fun prompts_argusDoes_haloAddsOnlyWhatsMissing() = five {
        val b = DuoAll.argusSystem("SOUL")
        assertTrue(b.startsWith("SOUL") && b.contains("You are the doer") && b.contains("don't hand anything to Halo"))
        val h = DuoAll.haloSystem("SOUL", "${BrandName.NAME} said this.")
        assertTrue(h.contains("${BrandName.NAME} said this.") && h.contains(DuoAll.QUIET) && h.contains("Never repeat") && h.contains("never use tools"))
    }

    @Test fun agent_allChatRunsArgusFirstThenHalo() = five {
        val src = listOf(File("src/main/java/com/blazeinchue/argus/agent/Agent.kt"), File("app/src/main/java/com/blazeinchue/argus/agent/Agent.kt")).first { it.exists() }.readText()
        assertTrue(src.contains("if (kind == Duo.Kind.CHAT) { duoDoer(name, facts)"))
        val body = src.substringAfter("private suspend fun duoDoer(").substringBefore("/** After both agreed")
        assertTrue("${BrandName.NAME} before Halo", body.indexOf("ask(Duo.Who.ARGUS") in 0 until body.indexOf("DuoAll.haloSystem"))
        assertTrue("Halo isn't streamed (a dropped repeat never flashes up)", body.contains("DuoAll.haloSystem(base(Duo.Who.HALO), argus), stream = false"))
        assertFalse("no tools for the chat duo", body.contains("roomTools("))
    }

    @Test fun voice_noFillerOnGreetingsOrSimpleChat() = five {
        assertEquals("Hey Sam, good to hear from you!", VoiceFiller.spoken("On it! Hey Sam, good to hear from you!", toolsRan = false))
        assertEquals("Hi! What's up?", VoiceFiller.spoken("Sure, one sec. Hi! What's up?", toolsRan = false))
        assertEquals("It's 14:05.", VoiceFiller.spoken("Okay, let me check. It's 14:05.", toolsRan = false))
        assertEquals("unchanged without filler", "Hello there.", VoiceFiller.spoken("Hello there.", toolsRan = false))
        assertEquals("a real tool task may keep its line", "On it. Setting the alarm.", VoiceFiller.spoken("On it. Setting the alarm.", toolsRan = true))
        assertEquals("filler alone is left (nothing else to say)", "On it.", VoiceFiller.spoken("On it.", toolsRan = false))
        assertTrue(VoiceFiller.onlyFiller("Sure thing! One sec."))
        assertFalse(VoiceFiller.onlyFiller("Sure, it's sunny."))
        assertTrue(VoiceFiller.hasFiller("Got it, the meeting is at 3."))
        assertTrue(VoiceFiller.RULE.contains("greeting gets a greeting back"))
    }

    @Test fun voice_ruleAndHooksAreWired() = five {
        val src = listOf(File("src/main/java/com/blazeinchue/argus/agent/Agent.kt"), File("app/src/main/java/com/blazeinchue/argus/agent/Agent.kt")).first { it.exists() }.readText()
        assertTrue(src.contains("s(\"voice\", VoiceFiller.RULE)"))
        assertTrue(src.contains("VoiceFiller.spoken(last.text"))
        assertTrue(src.contains("VoiceFiller.onlyFiller(part)"))
    }
}
