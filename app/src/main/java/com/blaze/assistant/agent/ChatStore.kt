package com.blaze.assistant.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMeta(val id: String, val title: String, val updated: Long, val pinned: Boolean, val preview: String)

/** Saved conversations, one JSON file each in filesDir/chats. Survives app updates. */
object ChatStore {
    private lateinit var dir: File

    @Synchronized fun init(ctx: Context) { if (::index.isInitialized) return; dir = File(ctx.filesDir, "chats").apply { mkdirs() }; index = ChatIndex(dir) }

    private fun file(id: String) = File(dir, "$id.json")

    private lateinit var index: ChatIndex

    /**
     * v0.45.0 (#1): headers come from the chat index; only changed files are read (see ChatIndex).
     * P1-TM-02: the user's chats only; bot threads' chats (`team-…`, BotThreads) are kept here too but never listed.
     */
    fun list(): List<ChatMeta> = if (::index.isInitialized) com.blaze.assistant.team.BotThreads.userChats(index.list()) else emptyList()

    /**
     * P1-TM-02: the ARGUS thread's read-only view of these chats ([current] = the chat ARGUS is in now). No data move:
     * BotThreads reads the chat files as they are; nothing here writes.
     */
    fun argus(current: () -> String?): com.blaze.assistant.team.ArgusChats =
        com.blaze.assistant.team.ArgusChats.of(::list, { id -> file(id).takeIf { it.isFile }?.readText() }, current)

    fun search(q: String): List<ChatMeta> {
        val needle = q.trim().lowercase()
        if (needle.isEmpty()) return list()
        return list().filter { m ->
            m.title.lowercase().contains(needle) || try {
                val msgs = JSONObject(file(m.id).readText()).optJSONArray("messages") ?: JSONArray()
                (0 until msgs.length()).any { msgs.getJSONObject(it).optString("text").lowercase().contains(needle) }
            } catch (_: Exception) { false }
        }
    }

    /** v0.45.0 (#2): the chat file's stamp (lastModified:length), "" when missing. */
    fun stamp(id: String): String = file(id).let { f -> if (f.isFile) "${f.lastModified()}:${f.length()}" else "" }

    fun load(id: String): JSONObject? = try { JSONObject(file(id).readText()) } catch (_: Exception) { null }

    /**
     * Called once per failed save (v0.45.0 #7) with a plain reason, so the chat can say it wasn't saved instead of losing
     * it silently. Set by Agent.
     */
    @Volatile var onSaveFailed: ((id: String, reason: String) -> Unit)? = null

    /** Writes the chat atomically. v0.45.0 (#7): returns false (and reports it) when the write failed. */
    fun save(o: JSONObject): Boolean = saveText(o, null)

    /** v0.45.0 (#11): [text] = the whole file made by ChatJson ([head] = its header fields); null = [head].toString(). */
    fun saveText(head: JSONObject, text: String?): Boolean {
        val o = head
        if (PrivateChat.isPrivateId(o.optString("id"))) return true   // v0.24.4: private chats are never written
        val ok = try { if (text == null) index.save(o) else index.saveText(o, text) } catch (e: Exception) { com.blaze.assistant.device.CrashLog.error("chat save", e); false }
        if (!ok) {
            val reason = saveFailReason(dir)
            com.blaze.assistant.device.CrashLog.write("ERROR chat save failed (${o.optString("id")}): $reason")
            try { onSaveFailed?.invoke(o.optString("id"), reason) } catch (_: Throwable) { }
            return false
        }
        com.blaze.assistant.connect.CloudSync.localChange(com.blaze.assistant.connect.SyncKinds.CHATS)   // v0.40.0 (off by default)
        return true
    }

    /** A plain reason for a failed save: storage full (under 20 MB free) or a generic one. */
    fun saveFailReason(d: File): String = try {
        if (d.usableSpace in 0 until 20_000_000L) "phone storage is full" else "the phone wouldn't write the file"
    } catch (_: Exception) { "the phone wouldn't write the file" }

    /** P1-SH-04: Chat mode's Manager chats ([ManagerChats]), kept beside the chats; null before the first save or when unreadable. */
    fun managerChatsText(): String? = try { if (::dir.isInitialized) File(dir, ManagerChats.FILE).takeIf { it.isFile }?.readText() else null } catch (_: Exception) { null }

    /** P1-SH-04: writes Chat mode's Manager chats atomically (best effort: a failed write keeps the previous file). */
    fun saveManagerChats(text: String): Boolean = ::dir.isInitialized && ChatIndex.write(dir, File(dir, ManagerChats.FILE), text)

    fun rename(id: String, title: String) = load(id)?.let { save(it.put("title", title.trim().ifEmpty { "Chat" })) }
    fun setPinned(id: String, pinned: Boolean) = load(id)?.let { save(it.put("pinned", pinned)) }
    fun delete(id: String) { file(id).delete(); index.forget(id); com.blaze.assistant.connect.CloudSync.localChange(com.blaze.assistant.connect.SyncKinds.CHATS) }
    fun deleteAll() { dir.listFiles()?.forEach { it.delete() }; index.clear() }
}
