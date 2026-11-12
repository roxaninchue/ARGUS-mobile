// P1-QA-01 signature stubs (shots.sh only, never the jvmtest run) so ui/team/ChatDrawer.kt and the shots compile locally.
package com.blaze.assistant.agent
import kotlinx.coroutines.flow.MutableStateFlow
enum class Role { USER, BLAZE, SYSTEM }
enum class Mood { IDLE, THINKING }
data class ChatMessage(val id: Long, val role: Role, val text: String, val meta: String = "", val at: Long = 0L)
data class Project(val id: String, val name: String)
object Agent {
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val mood = MutableStateFlow(Mood.IDLE)
    val chats = MutableStateFlow<List<ChatMeta>>(emptyList())
    fun clear(x: Boolean) {}
    fun pinChat(id: String, on: Boolean) {}
    fun deleteChat(id: String) {}
    fun renameChat(id: String, t: String) {}
}
object Projects { val all = MutableStateFlow<List<Project>>(emptyList()); fun of(chatId: String): Project? = null }
object ChatStore { fun managerChatsText(): String? = null; fun saveManagerChats(t: String) {} }
object BgAgents { const val CARD = "card:" }
object ReplyChips { fun isFreshStart(text: String, meta: String): Boolean = false }
