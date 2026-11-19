package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.53.6 (Finn's screenshot): "just give what you found" was answered with only "I couldn't confirm it: not checked yet."
 * because a stray set_timer in the same turn wasn't read back. An answer stays the model's; the caveat goes under it.
 */
class UnconfirmedKeepTest {
    private val timer = listOf(ClaimCheck.Done("web_search", ToolVerify.Status.VERIFIED), ClaimCheck.Done("set_timer", ToolVerify.Status.UNVERIFIED, "not checked yet"))

    @Test fun anAnswerIsNeverReplaced() {
        val answer = "Here's what I found in Ndola:\n1. Pizza Inn, Broadway\n2. C'est La Vie, Kabelenga Rd\nAll set, enjoy!"
        val out = UnconfirmedEcho.fix(answer, timer)
        assertTrue(out, out.startsWith(answer))
        assertTrue(out, out.endsWith("(I couldn't confirm the timer worked: it wasn't read back on the phone.)"))
        assertTrue(!out.contains("not checked yet"))
    }

    @Test fun aShortReplyAboutSomethingElseKeepsItsWords() {
        val r = "Done: Pizza Inn and C'est La Vie are the best rated."
        assertEquals("$r\n\n(I couldn't confirm the timer worked: it wasn't read back on the phone.)", UnconfirmedEcho.fix(r, timer))
    }

    @Test fun aShortClaimAboutTheActionIsStillMadeHonest() {
        assertEquals("I couldn't confirm it: it wasn't read back on the phone.", UnconfirmedEcho.fix("Done, the timer is set.", timer))
    }
}
