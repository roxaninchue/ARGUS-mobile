package com.blaze.assistant.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-AG-01: the TeamHooks seam: unbound chats are untouched, bound chats get identity, a tool subset and a row sink. */
class TeamHooksTest {
    private val chat = "c-hooks"
    @After fun clean() { TeamHooks.unbind(chat); TeamHooks.unbind("other") }

    private fun bind(identity: String = "Ledger, the money bot", allow: ((String) -> Boolean)? = { it != "run_shell" }, sink: (TeamHooks.Event) -> Unit = {}) =
        TeamHooks.Binding("ledger", identity, allow, sink).also { TeamHooks.bind(chat, it) }

    @Test fun unboundChatIsUntouched() {
        val got = mutableListOf<TeamHooks.Event>()
        TeamHooks.bind("other", TeamHooks.Binding("x", "X", { false }) { got += it })
        assertNull(TeamHooks.identityBlock(chat))
        assertTrue(TeamHooks.allows(chat, "run_shell"))
        assertNull(TeamHooks.refuse(chat, "run_shell"))
        TeamHooks.message(chat, "USER", "hi"); TeamHooks.call(chat, "web_search", "{}"); TeamHooks.result(chat, "web_search", "ok"); TeamHooks.end(chat, "DONE")
        assertTrue(got.isEmpty())
    }

    @Test fun identityBlockWrapsTheBotText() {
        bind()
        val b = TeamHooks.identityBlock(chat)!!
        assertTrue(b.startsWith("[Team bot]"))
        assertTrue(b.endsWith("Ledger, the money bot"))
        assertTrue(b.contains("override"))
        bind(identity = "   ")
        assertNull(TeamHooks.identityBlock(chat))
    }

    @Test fun toolSubsetRefusesAndReportsIt() {
        val got = mutableListOf<TeamHooks.Event>()
        bind(sink = { got += it })
        assertTrue(TeamHooks.allows(chat, "web_search"))
        assertNull(TeamHooks.refuse(chat, "web_search"))
        val r = TeamHooks.refuse(chat, "run_shell")!!
        assertTrue(r.startsWith("Error:") && r.contains("run_shell"))
        val e = got.single() as TeamHooks.Event.ToolResult
        assertEquals("run_shell", e.name); assertTrue(e.refused)
    }

    @Test fun nullAllowMeansEveryTool() {
        bind(allow = null)
        assertTrue(TeamHooks.allows(chat, "run_shell"))
    }

    @Test fun throwingAllowOrSinkNeverBreaksTheLoop() {
        bind(allow = { error("boom") }, sink = { error("sink down") })
        assertFalse(TeamHooks.allows(chat, "anything"))   // a broken rule denies
        TeamHooks.message(chat, "BLAZE", "still fine")
        TeamHooks.end(chat, "DONE")
    }

    @Test fun callsAndResultsPairByToolEvenInParallel() {
        val got = mutableListOf<TeamHooks.Event>()
        bind(sink = { got += it })
        TeamHooks.call(chat, "web_search", "{\"q\":\"a\"}")
        TeamHooks.call(chat, "get_weather", "{}")
        TeamHooks.result(chat, "get_weather", "sunny")
        TeamHooks.result(chat, "web_search", "found")
        val calls = got.filterIsInstance<TeamHooks.Event.ToolCall>().associate { it.name to it.callId }
        val results = got.filterIsInstance<TeamHooks.Event.ToolResult>().associate { it.name to it.callId }
        assertEquals(calls, results)
        assertEquals(2, calls.values.toSet().size)
    }

    @Test fun blankMessagesAreDroppedAndLongTextClipped() {
        val got = mutableListOf<TeamHooks.Event>()
        bind(sink = { got += it })
        TeamHooks.message(chat, "STEP", "  ")
        TeamHooks.call(chat, "file_write", "x".repeat(TeamHooks.ARGS_MAX + 50))
        val c = got.single() as TeamHooks.Event.ToolCall
        assertTrue(c.args.endsWith("… [50 more chars]"))
        assertEquals("abc", TeamHooks.clip("abc", 3))
    }

    @Test fun unbindOnlyReleasesItsOwnBinding() {
        val old = bind()
        val newer = TeamHooks.Binding("atlas", "Atlas", null) {}
        TeamHooks.bind(chat, newer)
        TeamHooks.unbind(chat, old)
        assertTrue(TeamHooks.binding(chat) === newer)
        TeamHooks.unbind(chat, newer)
        assertFalse(TeamHooks.isBound(chat))
    }
}
