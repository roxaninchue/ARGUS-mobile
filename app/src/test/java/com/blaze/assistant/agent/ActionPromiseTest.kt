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
}
