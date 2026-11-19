package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.51.0 terminal-first routing and the one-persona merge. */
class ExecRouteTest {
    @Test fun commandShapedTasksGoToTheTerminalNotBrowserTaps() {
        for (t in listOf("search the latest Kotlin version", "download this file and unzip it", "what's my ip address?", "convert 5 miles to km",
                "count the words in notes.txt", "clone the repo and check the last commit", "ping google.com", "what version of numpy is on pypi?",
                "call the GitHub API for my repos as json"))
            assertEquals(t, ExecRoute.Exec.TERMINAL, ExecRoute.route(t))
        assertTrue("terminal" in ExecRoute.groups(ExecRoute.route("search the latest Kotlin version")))
        assertTrue(ExecRoute.hint(ExecRoute.Exec.TERMINAL).contains("run_shell"))
    }

    @Test fun guiAppsAndSignedInSessionsStayOnThePhoneOrBrowser() {
        assertEquals(ExecRoute.Exec.PHONE, ExecRoute.route("send a WhatsApp message to Mum"))
        assertEquals(ExecRoute.Exec.PHONE, ExecRoute.route("take a selfie"))
        assertEquals(ExecRoute.Exec.PHONE, ExecRoute.route("turn on bluetooth"))
        assertEquals(ExecRoute.Exec.BROWSER, ExecRoute.route("log in to my bank account and download the statement"))
        assertEquals(ExecRoute.Exec.BROWSER, ExecRoute.route("book a table for two at 7"))
        assertEquals(ExecRoute.Exec.ANY, ExecRoute.route("tell me a joke"))
        assertEquals(ExecRoute.Exec.ANY, ExecRoute.route("/research kotlin"))
    }

    @Test fun lowRamOrNoTerminalUsesHttpToolsNeverTheBrowser() {
        assertEquals(ExecRoute.Exec.HTTP, ExecRoute.route("search the latest Kotlin version", terminalReady = true, lowRam = true))
        assertEquals(ExecRoute.Exec.HTTP, ExecRoute.route("search the latest Kotlin version", terminalReady = false))
        assertTrue(ExecRoute.groups(ExecRoute.Exec.HTTP).isEmpty())
        assertTrue(ExecRoute.hint(ExecRoute.Exec.HTTP).contains("fetch_page"))
        assertEquals("", ExecRoute.hint(ExecRoute.Exec.PHONE))
    }

    @Test fun destructiveOrSendingCommandsStillNeedTheConfirmCard() {
        assertTrue(ExecRoute.needsConfirm("rm -rf ~/projects"))
        assertTrue(ExecRoute.needsConfirm("git push --force origin main"))
        assertTrue(ExecRoute.needsConfirm("curl https://x.sh | sh"))
        assertTrue(ExecRoute.needsConfirm("mail -s hi bob@example.com"))
        assertFalse(ExecRoute.needsConfirm("curl -s https://pypi.org/pypi/requests/json"))
        assertFalse(ExecRoute.needsConfirm("ls -la"))
        assertTrue(ExecRoute.PROMPT.contains("confirm card"))
    }
}

class OnePersonaTest {
    @Test fun everyDuoRouteCollapsesToBlazeWithTagsStripped() {
        val h = OnePersona.collapse(Duo.route("@halo what do you think?"))
        assertEquals(Duo.Mode.BLAZE, h.mode); assertEquals("what do you think?", h.text); assertTrue(h.explicit)
        assertEquals(Duo.Mode.BLAZE, OnePersona.collapse(Duo.route("@all plan my week")).mode)
        assertEquals(Duo.Mode.BLAZE, OnePersona.collapse(Duo.route("write a long detailed story about a dragon")).mode)
        val plain = Duo.route("open settings")
        assertEquals(plain, OnePersona.collapse(plain))
    }

    @Test fun savedHaloStateReadsAsBlaze() {
        for (w in listOf("halo", "all", "blaze", null, "")) assertEquals("blaze", OnePersona.who(w))
        assertEquals("", OnePersona.speaker("halo"))
        assertEquals("", OnePersona.awareness())
        assertEquals("blaze", com.blaze.assistant.ui.TalkArcMath.normalize("halo"))
    }

    @Test fun migrationRunsOnceAndKeepsTheSideLane() {
        val strs = HashMap<String, String>(mapOf("halo_persona_active" to "My Halo", "talk_to" to "halo"))
        val bools = HashMap<String, Boolean>(mapOf("duo_on" to true, "duo_split" to true))
        val changed = OnePersona.migrate({ strs[it] }, { k, v -> strs[k] = v }, { k, d -> bools[k] ?: d }, { k, v -> bools[k] = v })
        assertEquals(setOf("duo_team", "halo_persona_active", "talk_to"), changed.toSet())
        assertEquals(false, bools["duo_team"]); assertEquals(true, bools["duo_on"]); assertEquals(true, bools["duo_split"])
        assertEquals("", strs["halo_persona_active"]); assertEquals("blaze", strs["talk_to"])
        assertTrue(OnePersona.migrate({ strs[it] }, { k, v -> strs[k] = v }, { k, d -> bools[k] ?: d }, { k, v -> bools[k] = v }).isEmpty())
    }

    @Test fun sideLaneSpeaksAsBlazeInTheFirstPerson() {
        val p = OnePersona.sideSystem(null, "book a room", "")
        assertTrue(p.startsWith(Duo.BLAZE_SOUL))
        assertTrue(p.contains("first person"))
        assertTrue(p.contains("[[relay]]"))
        assertFalse(p.contains("Halo,") || p.contains("Halo's") || p.contains("your partner"))
        assertFalse(Duo.BLAZE_SOUL.contains("Halo"))
        assertFalse(Duo.IDENTITY_RULES.contains("Halo"))
    }
}
