package com.blaze.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 2026-11-13 double-text fix: every message is answered, a quick double text gets one answer ([ChatBurst]). */
class ChatBurstTest {
    private val quick = ChatBurst.Facts(sinceTurnMs = 3_000, toolsRun = 0, replyChars = 0, turnUsers = 1, restarts = 0, voice = false, plainHead = true)

    @Test fun quickDoubleTextRestartsThePlainReply() {
        // "Hey hey!!" then "Let's chat!" three seconds later, while "Hey!" is still on its way.
        assertEquals(ChatBurst.Lane.RESTART, ChatBurst.lane(quick))
        assertEquals(ChatBurst.Lane.RESTART, ChatBurst.lane(quick.copy(replyChars = 4)))
        assertEquals(ChatBurst.Lane.RESTART, ChatBurst.lane(quick.copy(sinceTurnMs = ChatBurst.QUICK_MS, replyChars = ChatBurst.SHORT_REPLY_CHARS)))
    }

    @Test fun everythingElseGoesToTheMainLaneNeverTheSide() {
        listOf(
            quick.copy(sinceTurnMs = ChatBurst.QUICK_MS + 1),          // late: answered right after this reply
            quick.copy(replyChars = ChatBurst.SHORT_REPLY_CHARS + 1),   // reply already well along
            quick.copy(toolsRun = 1),                                  // a task: joins at its next step
            quick.copy(turnUsers = 2),                                 // the turn already answers a burst
            quick.copy(restarts = ChatBurst.MAX_RESTARTS),             // a stream of texts can't stall the reply forever
            quick.copy(voice = true),                                  // the reply may be playing aloud
            quick.copy(plainHead = false),                             // the first message can't be taken back cleanly
            quick.copy(sinceTurnMs = -1),
        ).forEach { assertEquals(it.toString(), ChatBurst.Lane.MAIN, ChatBurst.lane(it)) }
        // there are only two lanes: no outcome sends a busy-time message to the side lane
        assertEquals(listOf("RESTART", "MAIN"), ChatBurst.Lane.values().map { it.name })
    }

    @Test fun burstChainResets() {
        assertEquals(0, ChatBurst.chain(1, 0L, 10_000))
        assertEquals(1, ChatBurst.chain(1, 10_000, 10_000 + ChatBurst.CHAIN_MS))
        assertEquals(0, ChatBurst.chain(2, 10_000, 10_001 + ChatBurst.CHAIN_MS))
    }

    @Test fun plainHeadIsTheTurnsOwnLine() {
        assertTrue(ChatBurst.plainHead("user", "[m7] Hey hey!!", 7))
        assertFalse(ChatBurst.plainHead("user", "[m7 · sent while you were working] hi", 7))
        assertFalse(ChatBurst.plainHead("user", "[m17] hi", 7))
        assertFalse(ChatBurst.plainHead("assistant", "[m7] hi", 7))
        assertFalse(ChatBurst.plainHead("user", org.json.JSONArray(), 7))   // a message with a picture
        assertFalse(ChatBurst.plainHead("user", "[m0] hi", 0))
        assertFalse(ChatBurst.plainHead(null, null, 7))
    }

    @Test fun emptyOrFailedSideReplyIsNeverDropped() {
        assertTrue(ChatBurst.dropped(""))
        assertTrue(ChatBurst.dropped("  \n"))
        assertFalse(ChatBurst.dropped("Sure, let's chat!"))
    }

    @Test fun staleTakesOnlyTheCutReplysLines() {
        val ids = listOf(5L to true, 6L to false, 7L to true, 8L to false, 9L to false)
        assertEquals(setOf(8L, 9L), ChatBurst.stale(ids, 7, Long.MAX_VALUE))
        assertEquals(setOf(6L, 8L), ChatBurst.stale(ids, 5, 8))
    }

    @Test fun promptAsksForEveryMessageInShortBubbles() {
        assertTrue(RapidMessages.PROMPT_RULE.endsWith(ChatBurst.RULE))
        assertTrue(ChatBurst.RULE.contains("Answer EVERY user message"))
        assertTrue(ChatBurst.RULE.contains("never more bubbles than messages"))
        assertEquals(2, ChatBurst.trailingUsers(listOf("user", "assistant", "user", "user")))
        assertEquals(0, ChatBurst.trailingUsers(listOf("user", "assistant")))
        assertEquals("", ChatBurst.laneLine(1))
        assertTrue(ChatBurst.laneLine(2).contains("answer every one of them"))
    }

    /** Owner rule: the logic lives here; Agent.kt only calls it, from send() and the side lane's safety net. */
    @Test fun agentRoutesBusyFollowUpsThroughChatBurst() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }.resolve("com/blaze/assistant/agent/Agent.kt").readText()
        val send = src.substring(src.indexOf("        fun send(text: String, byVoice: Boolean = false"))
        val side = send.lines().first { "BusyRoute.Kind.SIDE ->" in it }
        assertTrue(side, side.contains("ChatBurstLive.follow(this, shown, byVoice, untrusted); return"))
        assertFalse(side, side.contains("sideSend"))
        assertTrue(src.contains("if (relay || ChatBurst.dropped(shownText)) {"))
        assertFalse(src.contains("lost the connection.\"); return }"))
        val lane = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }.resolve("com/blaze/assistant/agent/ChatLane.kt").readText()
        assertTrue(lane.contains("ChatBurst.laneLine("))
    }
}
