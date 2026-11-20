package com.blaze.assistant.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.agent.ChatMessage
import com.blaze.assistant.agent.Mood
import com.blaze.assistant.agent.Reaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bot chat redesign (owner 2026-11-20): the one seam between the main chat's composables (MessageList, Entry and its
 * actions, LivingScreen) and the chat they draw. Those composables used to read the chat on screen straight from
 * [Agent]'s flows; they now read [LocalChatFeed], whose default is [main] (exactly those flows, so the main Chat tab is
 * unchanged), and a bot's thread provides its own feed ([BotChatFeed]) for the bot's hidden chat. Nothing is copied:
 * the same bubbles, tool rows, Living cards, Continue chip and actions draw both, and the main Chat never shows a bot
 * thread (a bot's chat is never [Agent]'s chat on screen).
 */
class ChatFeed(
    val chatId: StateFlow<String>,
    val messages: StateFlow<List<ChatMessage>>,
    val mood: StateFlow<Mood>,
    val streamingId: StateFlow<Long>,
    val thinking: StateFlow<String>,
    val status: StateFlow<String>,
    val taskChip: StateFlow<String>,
    val duoLive: StateFlow<Set<Long>>,
    val sideLive: StateFlow<Set<Long>>,
    val split: StateFlow<Boolean>,
    val meter: StateFlow<Agent.Meter?>,
    val privateChat: StateFlow<Boolean>,
    /** Retry the reply [Long] (the main chat re-runs it in place; a bot thread sends the question again as a new turn). */
    val retry: (Long) -> Unit,
    /** Toggle a reaction; false = nothing changed. */
    val react: (Long, String) -> Boolean,
) {
    companion object {
        /** The main Chat tab: the chat on screen, as [Agent] mirrors it. */
        val main: ChatFeed by lazy {
            ChatFeed(Agent.chatId, Agent.messages, Agent.mood, Agent.streamingId, Agent.thinking, Agent.status, Agent.taskChip,
                Agent.duoLive, Agent.sideLive, Agent.split, Agent.meter, Agent.privateChat,
                retry = { Agent.retry(it) }, react = { id, e -> Agent.react(id, e) })
        }
    }
}

/** What the main chat's composables draw: [ChatFeed.main] unless a bot thread provides its own. */
val LocalChatFeed = staticCompositionLocalOf { ChatFeed.main }

/**
 * A bot's thread as a [ChatFeed]: the bot's hidden `team-<bot>-g<n>` chat (an ordinary Agent.Session, run by
 * LocalEngine through the bot's binding). The session only mirrors itself into [Agent]'s flows while it is the chat on
 * screen, which a bot's chat never is, so the open thread [pump]s the session's state into this feed (the screen calls
 * it every frame-ish tick while it shows; unchanged values cost a reference compare).
 *
 * [resend] runs a turn through the bot (BotThreads.send), so Retry and Continue keep the bot's identity and tools.
 */
class BotChatFeed(val chat: String, private val resend: (String) -> Unit) {
    private val chatId = MutableStateFlow(chat)
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val mood = MutableStateFlow(Mood.IDLE)
    private val streamingId = MutableStateFlow(-1L)
    private val thinking = MutableStateFlow("")
    private val status = MutableStateFlow("")
    private val taskChip = MutableStateFlow("")
    private val duoLive = MutableStateFlow<Set<Long>>(emptySet())
    private val sideLive = MutableStateFlow<Set<Long>>(emptySet())
    private val split = MutableStateFlow(false)
    private val meter = MutableStateFlow<Agent.Meter?>(null)

    val feed = ChatFeed(chatId, messages, mood, streamingId, thinking, status, taskChip, duoLive, sideLive, split, meter,
        MutableStateFlow(false),
        retry = { id -> BotChatRules.retryText(messages.value, id)?.let(resend) },
        react = { id, e -> react(id, e) })

    /** Copies the bot session's live state into the feed (main thread: the session is written there). */
    fun pump() {
        val s = try { Agent.teamSession(chat) } catch (_: Throwable) { return }
        messages.value = s.msgs; mood.value = s.mood; streamingId.value = s.streamId; thinking.value = s.thinking
        status.value = s.status; taskChip.value = s.taskChip; duoLive.value = s.duoLive; sideLive.value = s.sideLive
        split.value = s.split; meter.value = s.meter
    }

    private fun react(id: Long, emoji: String): Boolean {
        val s = try { Agent.teamSession(chat) } catch (_: Throwable) { return false }
        val m = s.msgs.firstOrNull { it.id == id } ?: return false
        if (m.role == com.blaze.assistant.agent.Role.STEP) return false
        val next = Reaction.toggle(m.reactions, emoji, "user")
        s.msgs = s.msgs.map { if (it.id == id) it.copy(reactions = next) else it }
        runCatching { s.persist() }
        pump()
        return true
    }
}

/** Pure rules of a bot's chat thread (BotChatTest). */
object BotChatRules {
    /** Retry in a bot thread: the text of the user message that reply [replyId] answered, sent again as a new turn. */
    fun retryText(msgs: List<ChatMessage>, replyId: Long): String? {
        val i = msgs.indexOfFirst { it.id == replyId }
        if (i < 0) return null
        return (i downTo 0).asSequence().map { msgs[it] }.firstOrNull { it.role == com.blaze.assistant.agent.Role.USER }?.text?.takeIf { it.isNotBlank() }
    }

    /** The chat a bot's thread draws: its own `team-<bot>-g<n>`, never the main chat or another bot's. */
    fun chatFor(botId: String, generation: Int): String = com.blaze.assistant.team.BotThreads.teamChatId(botId, generation)

    /** Whether the main Chat tab may draw [chatId] (bot threads' chats never). */
    fun mainShows(chatId: String?): Boolean = chatId != null && !com.blaze.assistant.team.BotThreads.isTeamChat(chatId)
}
