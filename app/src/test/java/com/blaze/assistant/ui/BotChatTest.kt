package com.blaze.assistant.ui

import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Role
import com.blaze.assistant.agent.engines.BotFiles
import com.blaze.assistant.team.BotThreads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Bot chat redesign 2026-11-20: each bot keeps its own thread, drawn by the main chat's own composables through the
 * ChatFeed seam, and the main Chat tab never mixes with bot threads.
 */
class BotChatTest {
    private fun src(path: String) = File(listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }, "com/blaze/assistant/$path").readText()

    @Test fun everyBotHasItsOwnChatAndTheMainChatNeverShowsOne() {
        val vega = BotChatRules.chatFor("vega", 0); val tempo = BotChatRules.chatFor("tempo", 0)
        assertNotEquals(vega, tempo)
        assertNotEquals(vega, BotChatRules.chatFor("vega", 1))   // a new conversation is a new chat
        assertTrue(BotThreads.isTeamChat(vega) && BotThreads.isTeamChat(tempo))
        assertFalse(BotChatRules.mainShows(vega)); assertFalse(BotChatRules.mainShows(tempo))
        assertTrue(BotChatRules.mainShows("main")); assertFalse(BotChatRules.mainShows(null))
    }

    @Test fun filesStagedForOneBotNeverRideAnotherChat() {
        val f = BotFiles<String>()
        val vega = BotChatRules.chatFor("vega", 0)
        f.stage(vega, listOf("photo.jpg"))
        assertEquals(emptyList<String>(), f.take(BotChatRules.chatFor("tempo", 0)))
        assertEquals(emptyList<String>(), f.take("main"))
        assertEquals(1, f.waiting(vega))
        assertEquals(listOf("photo.jpg"), f.take(vega))
        assertEquals(emptyList<String>(), f.take(vega))   // taken once
        f.stage(vega, listOf("a")); f.stage(vega, emptyList()); assertEquals(0, f.waiting(vega))
    }

    @Test fun retryInABotThreadResendsTheQuestionItAnswered() {
        val msgs = listOf(ChatMessage(1, Role.USER, "plan my week"), ChatMessage(2, Role.STEP, "Checking"), ChatMessage(3, Role.BLAZE, "Hey…"),
            ChatMessage(4, Role.USER, "and leg day?"), ChatMessage(5, Role.BLAZE, "Thursday."))
        assertEquals("plan my week", BotChatRules.retryText(msgs, 3))
        assertEquals("and leg day?", BotChatRules.retryText(msgs, 5))
        assertNull(BotChatRules.retryText(msgs, 99))
        assertNull(BotChatRules.retryText(listOf(ChatMessage(7, Role.BLAZE, "Hi")), 7))
    }

    /** The main chat's composables read the chat through the seam, not Agent's on-screen flows, so a bot thread reuses them. */
    @Test fun mainChatPartsReadTheFeedAndTheBotThreadReusesThem() {
        val chat = src("ui/ChatScreen.kt")
        val list = chat.substring(chat.indexOf("internal fun MessageList("), chat.indexOf("private fun TraceDialog("))
        for (f in listOf("messages", "streamingId", "chatId", "thinking", "status", "taskChip", "duoLive", "sideLive", "split", "meter"))
            assertFalse("MessageList still reads Agent.$f", list.contains("Agent.$f.collectAsState"))
        assertFalse(list.contains("Agent.retry(") || list.contains("Agent.react("))
        val living = src("ui/living/LivingScreen.kt")
        assertFalse(living.contains("Agent.messages") || living.contains("Agent.chatId"))
        val thread = src("ui/team/BotThreadScreen.kt")
        for (part in listOf("LocalChatFeed provides", "ui.MessageList(", "LivingScreen(", "LivingSwitch()", "ComposerTopRow()", "AttachmentThumbs(", "Voice.listen(", "threads.send(botId"))
            assertTrue("the bot thread should use $part", thread.contains(part))
        // never a copy: the bot thread doesn't define its own bubbles or list
        assertFalse(thread.contains("fun MessageList") || thread.contains("fun Entry("))
        // the bot thread never puts its chat on the main screen
        assertFalse(thread.contains("Agent.open(") || thread.contains("Agent.send("))
    }
}
