package com.argus.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.45.24: quick replies never mirror the user's message, and near-identical chips collapse to one. */
class QuickRepliesTest {
    private val ask = "plan a trip to Livingstone this weekend"

    @Test fun echoOfTheAskIsDropped() {
        assertTrue(QuickReplies.echoes("Plan a trip to Livingstone", ask))
        assertTrue(QuickReplies.echoes("Livingstone trip", ask))
        assertTrue(QuickReplies.echoes("Plan my Livingstone trips this weekend", ask))
    }

    @Test fun realNextStepsStay() {
        assertFalse(QuickReplies.echoes("Price a lodge", ask))
        assertFalse(QuickReplies.echoes("Yes, book it", ask))
        assertFalse(QuickReplies.echoes("Saturday morning", ask))
        assertEquals(listOf("Price a lodge", "Yes, book it"),
            QuickReplies.filter(listOf("Plan a trip to Livingstone", "Price a lodge", "Yes, book it"), ask))
    }

    @Test fun twinsCollapse() {
        assertEquals(listOf("Book the lodge"), QuickReplies.filter(listOf("Book the lodge", "book the lodge!", "Book lodges"), "what's there to do"))
    }

    @Test fun allEchoesMeansNoChips() {
        assertTrue(QuickReplies.filter(listOf("Plan a trip to Livingstone", "Livingstone trip"), ask).isEmpty())
    }

    @Test fun appChipsAlwaysStay() {
        assertEquals(listOf(NudgePolicy.TRY_AGAIN), QuickReplies.filter(listOf(NudgePolicy.TRY_AGAIN), "try again"))
    }

    @Test fun jaccardBasics() {
        assertEquals(1.0, QuickReplies.jaccard(setOf("a"), setOf("a")), 0.0)
        assertEquals(0.5, QuickReplies.jaccard(setOf("a", "b"), setOf("a")), 1e-9)
        assertEquals(setOf("lodge", "book"), QuickReplies.tokens("Book the lodges!"))
    }

    // 2026-11-19 (Finn's screenshot): "<<follow-offs: …>>" leaked as raw text at the end of the reply.
    private val leaked = "Two hours tonight works.\n<<follow-offs: Turn on calendar access | Make tonight 2 hours | Start a 25-min timer now>>"
    private val three = listOf("Turn on calendar access", "Make tonight 2 hours", "Start a 25-min timer now")

    @Test fun followOffsBecomesChips() {
        val r = FollowUpMarker.split(leaked, final = true)
        assertEquals("Two hours tonight works.", r.text.trim())
        assertEquals(three, r.chips)
        val p = ReplyParts.split(leaked, final = true)
        assertEquals("Two hours tonight works.", p.text)
        assertEquals(three, p.followups)
    }

    @Test fun missingCloseAtTheEnd() {
        val raw = "Two hours tonight works.\n<<follow-ups: Turn on calendar access | Make tonight 2 hours | Start a 25-min timer now"
        assertEquals(three, ReplyParts.split(raw, final = true).followups)
        assertEquals("Two hours tonight works.", ReplyParts.split(raw, final = true).text)
        // While streaming the unclosed marker is hidden but gives no chips yet.
        val s = ReplyParts.split(raw, final = false)
        assertEquals("Two hours tonight works.", s.text)
        assertTrue(s.followups.isEmpty())
        assertEquals(listOf("Yes"), FollowUpMarker.split("Ok <<follow-offs: Yes", final = true).chips)
    }

    @Test fun nearMissKeysAndSeparators() {
        for (m in listOf("<<follow-ups: a | b | c>>", "<<followups:a|b|c>>", "<<Follow Ups : a | b | c>>", "<< follow-up: a | b | c >>",
                "<</follow-ups: a | b | c>>", "<follow ups: a|b|c>", "<<FOLLOW-OFFS: a | b | c>>", "<<suggestions: a; b; c>>",
                "<<Suggested replies: a | b | c>>", "<<quick replies: a | b | c>>", "<<chips: a | b | c>>", "<<follow_ups: a ; b ; c>>")) {
            val p = ReplyParts.split("Done.\n$m", final = true)
            assertEquals(m, "Done.", p.text)
            assertEquals(m, listOf("a", "b", "c"), p.followups)
        }
    }

    @Test fun noControlMarkerIsEverShown() {
        assertEquals("Done.", ReplyParts.split("Done. <<status: ok>>", final = true).text)
        assertEquals("Done.", ReplyParts.split("Done.\n<<next_steps>>", final = true).text)
        assertEquals("Done.", ReplyParts.split("Done.\n<<mood: calm", final = true).text)
        assertEquals("Done. More.", ReplyParts.split("Done. <<note: x>>More.", final = true).text)
        // Every streamed prefix of the leaked reply shows no marker at all.
        for (n in 1..leaked.length) {
            val shown = ReplyParts.split(leaked.substring(0, n), final = false).text
            assertFalse("prefix $n: $shown", shown.contains("<") || shown.contains("follow", ignoreCase = true))
        }
        for (v in listOf("<<sugg", "<<quick re", "<<chi", "<<follow-o", "<<anything_el", "<", "<<", "<</")) {
            assertEquals(v, "Hi", ReplyParts.split("Hi $v", final = false).text)
        }
    }

    @Test fun ordinaryTextAndCodeStay() {
        for (s in listOf("Use a <div> and 3 < 5.", "a << b and c >> d", "Use List<List<String>> here.", "x <y",
                "```bash\ncat <<EOF>>\n```", "```cpp\ncout <<x>> y;\n```"))
            assertEquals(s, s, ReplyParts.split(s, final = true).text)
        assertTrue(QuickReplies.RULE.contains(FollowUpMarker.MARK))
        assertEquals(1, Regex("<<").findAll(QuickReplies.RULE).count())
    }
}
