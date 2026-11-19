package com.argus.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** fix/chat-flat-ui: the folded "Thinking" section above a reply. */
class ThoughtFoldTest {
    @Test fun headerStates() {
        assertEquals("Thinking", ThoughtFold.header(live = true, answerStarted = false, ms = 0))
        assertEquals("Thought for 4s", ThoughtFold.header(live = true, answerStarted = true, ms = 4_200))
        assertEquals("Thought for 1s", ThoughtFold.header(live = false, answerStarted = false, ms = 300))
        assertEquals("Thought for 1m 5s", ThoughtFold.header(live = false, answerStarted = true, ms = 65_000))
        assertEquals("Thought for 2m", ThoughtFold.header(live = false, answerStarted = true, ms = 120_000))
        assertEquals("Thoughts", ThoughtFold.header(live = false, answerStarted = true, ms = 0))
    }

    @Test fun openWhileThinkingThenFoldedUnlessTapped() {
        assertTrue(ThoughtFold.open(null, live = true, answerStarted = false))
        assertFalse(ThoughtFold.open(null, live = true, answerStarted = true))
        assertFalse(ThoughtFold.open(null, live = false, answerStarted = true))
        assertTrue(ThoughtFold.open(true, live = false, answerStarted = true))
        assertFalse(ThoughtFold.open(false, live = true, answerStarted = false))
    }

    /** Owner, 15 Nov 2026: "instead of showing thinking I want the thought to stream in". */
    @Test fun whileItThinksTheWordsReplaceTheHeader() {
        assertFalse(ThoughtFold.headerShown(open = true, live = true, answerStarted = false))
        assertTrue("folded by a tap: the header says Thinking", ThoughtFold.headerShown(open = false, live = true, answerStarted = false))
        assertTrue("answer started: Thought for Ns", ThoughtFold.headerShown(open = false, live = true, answerStarted = true))
        assertTrue(ThoughtFold.headerShown(open = true, live = false, answerStarted = true))
        val long = (1..200).joinToString(" ") { "word$it" }
        val tail = ThoughtFold.liveTail(long)
        assertTrue(tail.startsWith("\u2026word")); assertTrue(tail.endsWith("word200")); assertTrue(tail.length <= ThoughtFold.LIVE_TAIL + 1)
        assertEquals("short", ThoughtFold.liveTail("short"))
    }

    @Test fun liveThoughtStreamsInTheChatAndTheWordStaysSmall() {
        val chat = src("ui/ChatScreen.kt")
        assertTrue(chat.contains("val thinkingNow = working && streamId < 0 && ThoughtFold.shows(live, Settings.showReasoning)"))
        assertTrue(chat.contains("if (thinkingNow) ThoughtSection(-1L - (lastUserId ?: 0L), live, live = true, answerStarted = false, ms = 0L)"))
        val ww = chat.substringAfter("private fun WorkingWord()").substringBefore("\n}\n")
        assertFalse("not the big sub style any more", ww.contains("ArgusType.sub"))
        assertTrue(ww.contains("fontSize = 13.sp"))
        val sec = src("ui/ThoughtSection.kt")
        assertTrue(sec.contains("ThoughtFold.headerShown(open, live, answerStarted)"))
        assertTrue(sec.contains("ThoughtFold.liveTail(body)"))
    }

    @Test fun bodyIsCleaned() {
        assertEquals("a\n\nb", ThoughtFold.body("<think>\r\na\r\n\r\n\r\n\r\nb</think>  "))
        assertFalse(ThoughtFold.shows("  <think></think> ", true))
        assertFalse(ThoughtFold.shows("real thought", false))
        assertTrue(ThoughtFold.shows("real thought", true))
    }

    @Test fun pickPrefersSavedThinking() {
        assertEquals("saved", ThoughtFold.pick("saved", streamingHere = true, liveTurn = "live"))
        assertEquals("live", ThoughtFold.pick("", streamingHere = true, liveTurn = "live"))
        assertEquals("", ThoughtFold.pick("", streamingHere = false, liveTurn = "live"))
    }

    @Test fun timerMeasuresUntilTheAnswerStarts() {
        val t = ThoughtFold.Timer()
        t.mark(false, 100); assertEquals(0L, t.ms())          // never seen thinking
        t.mark(true, 1_000); t.mark(true, 2_000); assertEquals(0L, t.ms())
        t.mark(false, 4_500); assertEquals(3_500L, t.ms())
        t.mark(false, 9_000); assertEquals(3_500L, t.ms())    // the end sticks
    }

    private fun src(rel: String): String =
        listOf(File("src/main/java/com/argus/assistant/$rel"), File("app/src/main/java/com/argus/assistant/$rel")).first { it.exists() }.readText()

    /** Owner, 14 Nov 2026: replies are flat text on the page; your prompts keep their bubble. */
    @Test fun repliesAreFlatPromptsKeepTheirBubble() {
        val b = src("ui/Bubbles.kt")
        val reply = b.substringAfter("fun ArgusReply(").substringBefore("\n}\n")
        assertTrue(reply.contains("FlatReply("))
        assertFalse("no bubble around a reply", reply.contains("ArgusBubble(") || reply.contains("ChatBubble("))
        val flat = b.substringAfter("fun FlatReply(").substringBefore("\n}\n")
        assertFalse("no fill, border or clip", flat.contains(".background(") || flat.contains(".border(") || flat.contains(".clip("))
        assertTrue("long-press menu kept", flat.contains("bubbleLongPress(menu)"))
        assertTrue(b.substringAfter("fun UserBubble(").substringBefore("\n}\n").contains("ChatBubble(Side.ME"))
    }

    @Test fun chatShowsTheFoldedThinking() {
        val chat = src("ui/ChatScreen.kt")
        assertTrue(chat.contains("ThoughtFold.pick(m.thinking, streamHere, liveThought)"))
        assertTrue(chat.contains("ThoughtSection(m.id, thought, live = streamHere, answerStarted = m.text.isNotBlank(), ms = m.thinkMs)"))
        assertTrue(chat.contains("streamHere = item.m.id == streamId,"))
        // 0.53.3: tool rows render inside the turn flow (TurnFlow.kt), the live turn included
        assertTrue(chat.contains("com.argus.assistant.ui.team.thread.TurnFlowView(liveFlow)"))
        val llm = src("agent/LlmClient.kt")
        assertTrue(llm.contains("val chatThink = if (lane == Lane.MAIN) onThink else null"))
        assertTrue(llm.contains("PromptEcho.note(messages)"))
    }

    @Test fun glitchHasShaderFallbackAndStillMode() {
        val g = src("ui/GlitchText.kt")
        assertTrue(g.contains("Build.VERSION.SDK_INT >= 33") && g.contains("RuntimeShader(GLITCH_AGSL)"))
        assertTrue(g.contains("Build.VERSION.SDK_INT < 33") && g.contains("glitchDraw("))
        assertTrue(g.contains("val motion = MotionTokens.enabled"))
    }
}
