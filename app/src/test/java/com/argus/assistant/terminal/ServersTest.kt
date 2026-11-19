package com.argus.assistant.terminal


import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.53.7 (owner 17 Nov 19:07): servers live in a named, saved place the agent can always read back. */
class ServersTest {
    @Test fun namesAreShortAndSafe() {
        assertEquals("music", Servers.clean(" Music "))
        assertEquals("my-music-server", Servers.clean("My Music Server!"))
        assertEquals("", Servers.clean("  !! "))
    }

    @Test fun entryRoundTrips() {
        val e = Servers.Entry("music", "python3 server.py", 8080, true, "/root/music", true, 42L)
        val back = Servers.fromJson(JSONObject(Servers.toJson(e).toString()))!!
        assertEquals(e.name, back.name); assertEquals(e.cmd, back.cmd); assertEquals(8080, back.port)
        assertTrue(back.linux); assertEquals("/root/music", back.cwd); assertTrue(back.keep)
        assertNull(Servers.fromJson(JSONObject().put("name", "x")))
    }

    @Test fun noPortIsNeverListening() { assertFalse(Servers.listening(0)) }

    @Test fun toolIsOfferedWithTheTerminal() {
        val names = Terminal.shellTools().map { it.getJSONObject("function").getString("name") }
        assertTrue(names.toString(), Servers.NAME in names)
        assertTrue(Servers.NAME in Terminal.names)
        val shell = Terminal.shellTools().first { it.getJSONObject("function").getString("name") == "run_shell" }
        assertTrue(shell.toString().contains("servers tool"))
    }

    @Test fun nothingKeptMeansNoPromptBlock() { assertEquals("", Servers.promptBlock()) }
}
