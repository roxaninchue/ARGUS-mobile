package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-SF-07: unbacked command-result claims and unran ```bash fences (ClaimGuard additions). Named after the A team case:
 * `v29` / `v211` = A team evals/test/v2NN.test.mjs, then the case's position in that file.
 */
class ClaimGuardRunTest {
    private val claimed = listOf(
        "I ran the tests and all 12 passed.",
        "I've installed jq and it works now.",
        "The command returned 3 files: a.txt, b.txt and c.txt.",
        "The build succeeded.",
        "Here is the output:\n```\ntotal 8\ndrwxr-xr-x 2 root root 4096 Nov  9 a\n```",
        "Done. Exit code 0.",
        "You have 3 files in /root.",
        "Tests passed, ship it.",
        "root@phone:~# ls\na.txt",
        "I created the file /root/notes.md with your list.",
    )
    private val honest = listOf(
        "Let me run the tests.",
        "If you run `pytest -q`, all tests should pass.",
        "I haven't run it yet. Want me to?",
        "You can install jq with apt-get install -y jq.",
        "To check, run:\n```bash\nls -la\n```\nThe output will list your files.",
        "Expected output looks like this:\n```\ntotal 8\n```",
        "Python lists are ordered and mutable; tuples are immutable.",
        "I can't run commands on this device.",
        "Once the build finishes, the tests run automatically.",
        "Sure, I will write the script and test it.",
    )

    /** v29-01 'unbackedClaims flags results claimed without a tool run' */
    @Test fun v29_01_unbackedClaimsFlagsResultsClaimedWithoutAToolRun() { for (t in claimed) assertTrue(t, ClaimGuard.unbackedRun(t).flag) }

    /** v29-02 'unbackedClaims leaves hypotheticals, offers, negations and plain answers alone' */
    @Test fun v29_02_unbackedClaimsLeavesHypotheticalsOffersNegationsAlone() { for (t in honest) assertFalse(t, ClaimGuard.unbackedRun(t).flag) }

    /** v29-03 'unbackedClaims never flags when a tool ran in the turn' */
    @Test fun v29_03_unbackedClaimsNeverFlagsWhenAToolRan() { for (t in claimed) assertFalse(t, ClaimGuard.unbackedRun(t, ran = true).flag) }

    /** v29-04 'unbackedClaims returns the reason and a quote' */
    @Test fun v29_04_unbackedClaimsReturnsTheReasonAndAQuote() {
        val r = ClaimGuard.unbackedRun("Good news. I ran the migration and it finished cleanly.")
        assertTrue(r.reasons.contains("ran")); assertTrue(r.quote, r.quote.contains("I ran the migration"))
        assertTrue(ClaimGuard.RUN_CLAIM_MSG.contains("no tool ran in this turn"))
    }

    /** v29-14 'guard in the eval loop: a claimed result with no tool run gets one check, a repeat is flagged' */
    @Test fun v29_14_aClaimGetsOneCheckARepeatIsFlagged() {
        val t = ClaimGuard.RunClaimTurn()
        assertEquals(ClaimGuard.RunClaimTurn.Action.BOUNCE, t.onFinal("I ran the tests and all 4 passed.", ran = false))
        assertEquals(ClaimGuard.RunClaimTurn.Action.OK, t.onFinal("I haven't run them yet.", ran = false)); assertFalse(t.flagged)
        val t2 = ClaimGuard.RunClaimTurn()
        assertEquals(ClaimGuard.RunClaimTurn.Action.BOUNCE, t2.onFinal("The tests passed.", ran = false))
        assertEquals(ClaimGuard.RunClaimTurn.Action.FLAG, t2.onFinal("The tests passed.", ran = false)); assertTrue(t2.flagged)
    }

    /** v211-08 'unranShellFence: flags a bash fence announced as run or with stated output, not an example for the user' */
    @Test fun v211_08_unranShellFenceFlagsAnnouncedRunsNotExamples() {
        assertTrue(ClaimGuard.unranShellFence("Running a quick test shows it works:\n```bash\npython3 t.py\n```\nOutput:\n```\nok\n```").flag)
        assertTrue(ClaimGuard.unranShellFence("Let me run the tests.\n```bash\npytest -q\n```").flag)
        assertFalse(ClaimGuard.unranShellFence("To try it, you can run:\n```bash\npython3 app.py\n```").flag)
        assertFalse(ClaimGuard.unranShellFence("Config:\n```\nkey=1\n```").flag)
        assertFalse(ClaimGuard.unranShellFence("Done.\n```run\nls\n```").flag)
    }

    /** SF-07 extra: the view / send checks are unchanged by the additions. */
    @Test fun sf07_existingSendCheckUnchanged() {
        assertTrue(ClaimGuard.unbackedSend("I've sent it to Mary.", emptyList()) != null)
        assertEquals(null, ClaimGuard.unbackedSend("I've sent it to Mary.", listOf(ClaimGuard.Call("send_sms"))))
    }
}
