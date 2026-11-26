package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** 26 Nov 2026 (Finn's phone, DeepSeek V4.1 Flash): after a failed command the turn ended on a promise, no tool call. */
    @Test fun aReplyEndingOnAPromiseToActIsCaught() {
        val finn = "I guessed the repo name wrong. The task says \"Argus-cli\". Let me try that exact name"
        assertEquals("Let me try that exact name", ActionPromise.dangling(finn))
        listOf("Let me try that exact name.", "Let me try that exact name:", "Let me try that exact name\u2026",
            "Let me check the logs.", "Let me check:", "I'll try the other branch.", "I\u2019ll run it again.", "I'll install ripgrep first.",
            "Now I'll clone it.", "Next, I'll run the tests.", "Trying Argus-cli now.", "Trying Argus-cli now", "Checking the build log\u2026",
            "Retrying with sudo...", "Okay, let me retry with the full path.", "That failed. I'll use the HTTPS URL instead.",
            "Oops, typo. Let me rerun it.").forEach { assertTrue(it, ActionPromise.dangling(it) != null) }
        listOf("Want me to try again?", "Let me know if you want any changes.", "Let me know if it still fails.",
            "Done, the repo is cloned. I'll keep an eye on it.", "I'll check back tomorrow.", "I'll try to keep replies shorter next time.",
            "Looking good!", "Testing passed on all four shards.", "Trying it was worth it.", "I'll run it once you send the key.",
            "Should I run the tests now?", "All set: the build is green.").forEach { assertEquals(it, null, ActionPromise.dangling(it)) }
    }

    /** 26 Nov 2026: the loop re-asks up to MAX_NUDGES times, then the reply gets the honest line in place of the promise. */
    @Test fun aPromiseThatIsNeverKeptEndsHonestly() {
        assertEquals(2, ActionPromise.MAX_NUDGES)
        val r = "I guessed the repo name wrong. The task says \"Argus-cli\". Let me try that exact name"
        val out = ActionPromise.honest(r, listOfNotNull(ActionPromise.dangling(r)))
        assertTrue(out, out.startsWith("I guessed the repo name wrong. The task says \"Argus-cli\".") && out.endsWith(ActionPromise.HONEST))
        assertFalse(out, out.contains("Let me try"))
        val src = java.io.File(listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory },
            ActionPromise::class.java.`package`.name.replace('.', '/') + "/Agent.kt").readText()
        assertTrue(src.contains("promiseNudges < ActionPromise.MAX_NUDGES"))
        assertTrue(src.contains("textOut = ActionPromise.honest(textOut, promised)"))
    }

    /** Unreleased after 0.54.1: a turn asked for a PDF / Word / PowerPoint file can't end without it. */
    @Test fun aTurnOwesTheFileItWasAskedFor() {
        assertEquals("pdf", ActionPromise.owedFile("Send PDF", setOf("run_code"), null, "reportlab is ready. Now let me write the script."))
        assertNull(ActionPromise.owedFile("Send PDF", setOf("make_document"), "Downloads/Argus/kwacha.pdf", "Here it is."))
        assertNull(ActionPromise.owedFile("make it a pdf", setOf("run_code"), null, "Saved to /root/phone/kwacha.pdf."))
        assertNull(ActionPromise.owedFile("research the kwacha and send a pdf", setOf("orchestrate"), null, "On it in the background."))
        assertNull(ActionPromise.owedFile("make slides", emptySet(), null, "How many slides do you want?"))
        assertEquals("pptx", ActionPromise.owedFile("make a deck from this", emptySet(), null, "Here's the outline."))
        assertTrue(ActionPromise.nextNudge(ActionPromise.OWED + "pptx").contains("make_document(format=\"pptx\")"))
    }
}

