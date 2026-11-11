package com.blaze.assistant.agent.guards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-SF-07: interrogation bounce, narration and filler. `v215` = A team evals/test/v215.test.mjs (case position);
 * `at_v27_4` = the no-filler check of A team app/tests/v27.test.js §4
 * (Playwright there; the pure rule here).
 */
class ReplyShapeTest {
    /** v215-04 'interrogates: bulleted question lists are flagged, one question is fine' */
    @Test fun v215_04_interrogatesFlagsBulletedQuestionLists() {
        assertTrue(ReplyShape.interrogates("I need context:\n- Which city?\n- Which zone?").flag)
        assertFalse(ReplyShape.interrogates("It is 00:32 in Lusaka. Want me to remember that?").flag)
        assertTrue(ReplyShape.INTERROG_MSG.contains("at most one short question"))
    }

    /** v215-12 'narration: short intent lines are recognised; the prompt asks for them; questions are not narration' */
    @Test fun v215_12_narrationIsRecognisedQuestionsAreNot() {
        assertTrue(ReplyShape.isNarration("Let me first check the workspace and see what's there."))
        assertFalse(ReplyShape.isNarration("Which port do you want?"))
        assertTrue(ReplyShape.NARRATE_PROMPT.contains("one short sentence")); assertTrue(ReplyShape.NARRATE_PROMPT.contains("final answer last"))
    }

    /** at-v27-4 'no filler: a trailing stock closing is dropped' */
    @Test fun at_v27_4_trailingStockClosingIsDropped() {
        assertEquals("Saved: the buyer is Mwila.", ReplyShape.dropFiller("Saved: the buyer is Mwila.\n\nLet me know if you need anything else!"))
        assertEquals("Here is the plan.", ReplyShape.dropFiller("Here is the plan.\n\nHope this helps!\n\nDone."))
        assertEquals("Got it.", ReplyShape.dropFiller("Got it."))                      // nothing before it: kept
        assertEquals("", ReplyShape.dropFiller("Sure! Let me know if you need anything else.", before = true))   // a filler-only step after real work
        assertTrue(ReplyShape.onlyFiller("Okay, feel free to ask more."))
        assertFalse(ReplyShape.onlyFiller("The tests pass: 12 of 12."))
        assertEquals("Port 8080 is free.\n\nWant me to start the server?", ReplyShape.dropFiller("Port 8080 is free.\n\nWant me to start the server?"))
    }

    /** sf07 asks-instead-of-looking (A team guard.js asksInsteadOfLooking, v2.16c) */
    @Test fun sf07_asksInsteadOfLooking() {
        assertTrue(ReplyShape.asksInsteadOfLooking("I can't find that file. Which folder did you save it in?"))
        assertTrue(ReplyShape.asksInsteadOfLooking("Which project do you mean?"))
        assertFalse(ReplyShape.asksInsteadOfLooking("I can't see images in this chat."))
        assertFalse(ReplyShape.asksInsteadOfLooking("Found it in /root/site/index.html."))
    }
}
