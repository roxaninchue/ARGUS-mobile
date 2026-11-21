package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.49.0 (RC5 a): the owner's screenshot texts. */
class ActionPromiseTest {
    @Test fun goAheadsAreConsent() {
        listOf("okay, do it!", "okay!", "yes please", "go ahead", "sure, go for it", "Yes", "ok do it now").forEach { assertTrue(it, ActionPromise.consent(it)) }
        listOf("no", "no thanks", "what is accessibility?", "okay but first tell me what it does and why you need it").forEach { assertFalse(it, ActionPromise.consent(it)) }
    }

    @Test fun offersAreRecognised() {
        assertTrue(ActionPromise.offers("I can set that up. Want me to open the accessibility settings?"))
        assertTrue(ActionPromise.offers("I'll open the accessibility settings now."))
        assertFalse(ActionPromise.offers("The capital of Zambia is Lusaka."))
    }

    @Test fun nowPromisesAreCaught() {
        assertEquals(1, ActionPromise.promises("Opening accessibility settings for you now.").size)
        assertEquals(1, ActionPromise.promises("Sure! I'll open the accessibility settings now.").size)
        assertTrue(ActionPromise.promises("Want me to open the accessibility settings?").isEmpty())
        assertTrue(ActionPromise.promises("Once you open settings, tap Accessibility.").isEmpty())
        assertTrue(ActionPromise.promises("Lusaka is sunny today.").isEmpty())
    }

    @Test fun honestLineReplacesThePromise() {
        val r = "Opening accessibility settings for you now."
        val out = ActionPromise.honest(r, ActionPromise.promises(r))
        assertEquals(ActionPromise.HONEST, out)
        assertTrue(ActionPromise.nudge("Opening accessibility settings").contains("blaze_self action=open target=accessibility"))
    }

    /** Unreleased after 0.54.1 (owner's phone, 21 Nov 08:12): the turn ended on "Now let me write…" twice and no PDF came. */
    @Test fun aReplyEndingOnItsNextStepIsCaught() {
        listOf("reportlab is ready. Now let me write the full PDF-building script with all the data",
            "I've got sources tied to every figure now. Building the cited PDF with reportlab next.",
            "I'll build the PDF now with reportlab, citing every figure.").forEach { assertTrue(it, ActionPromise.dangling(it) != null) }
        listOf("It's still running. I'll wait for it to finish, then compile the PDF.", "Here's your PDF. Want me to make a shorter version?",
            "Done, the report is saved to Downloads.", "I'll build it if you want.").forEach { assertEquals(it, null, ActionPromise.dangling(it)) }
        assertTrue(ActionPromise.nextNudge("Now let me write the script").contains("Do it now"))
    }
}
