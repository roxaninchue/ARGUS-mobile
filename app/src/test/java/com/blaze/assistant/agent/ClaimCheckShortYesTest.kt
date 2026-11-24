package com.blaze.assistant.agent

import com.blaze.assistant.agent.ClaimCheck.Done
import com.blaze.assistant.agent.ClaimCheck.Outcome
import com.blaze.assistant.agent.ToolVerify.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.55.14 (phone 24 Nov 02:18): a bare "Yes" to a build watch; "build #131 is running" is a status, not a claim of starting it. */
class ClaimCheckShortYesTest {
    @Test fun aStatusLineOnAYesStands() {
        val reply = "I set up a routine to check build #131 every 30 minutes. Build #131 is running now."
        assertEquals(Outcome.Ok, ClaimCheck.review(reply, listOf(Done("create_routine", Status.VERIFIED)), "Yes", listOf("open_app", "create_routine")))
    }

    @Test fun aFirstPersonStartOnAYesIsStillChecked() {
        val o = ClaimCheck.review("I started the server for you.", listOf(Done("create_routine", Status.VERIFIED)), "Yes")
        assertTrue(o !is Outcome.Ok)
    }
}
