package com.blaze.assistant.notify

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Owner 16 Nov 2026: "make an actual inbox page for notifications Argus sends (basically their own chats also which I can
 * reply from the inbox) and things that aren't working can go there too."
 *
 * The Inbox keeps what Argus sent you while you were away ([Kind.MESSAGE], your replies as [Kind.YOU], per chat) and what
 * isn't working right now ([Kind.PROBLEM], one per [Item.key], e.g. "phone_control"; it is marked fixed when it recovers).
 * Every Argus notification lands here too, even when Android notifications are off.
 *
 * Pure rules (add / resolve / read / unread / JSON) are tested in InboxTest; the store is one small file (inbox.json).
 */
object Inbox {
    enum class Kind { MESSAGE, YOU, PROBLEM }

    /** [chatId] = the chat a message belongs to ("" = the main chat); [key] = what a problem is about; [fixed] = it recovered. */
    data class Item(val id: Long, val at: Long, val kind: Kind, val text: String, val chatId: String = "", val key: String = "",
                    val read: Boolean = false, val fixed: Boolean = false) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("at", at).put("kind", kind.name).put("text", text)
            .put("chat", chatId).put("key", key).put("read", read).put("fixed", fixed)
        companion object {
            fun from(o: JSONObject): Item? {
                val kind = try { Kind.valueOf(o.optString("kind")) } catch (_: Exception) { return null }
                val text = o.optString("text").takeIf { it.isNotBlank() } ?: return null
                return Item(o.optLong("id"), o.optLong("at"), kind, text, o.optString("chat"), o.optString("key"), o.optBoolean("read"), o.optBoolean("fixed"))
            }
        }
    }

    const val MAX = 300
    const val TEXT_MAX = 4000

    // ---------------- pure rules ----------------

    /**
     * [list] with [item] added, newest last, at most [MAX] items. A problem replaces the open (not fixed) problem with the
     * same key instead of piling up; your own replies arrive read.
     */
    fun add(list: List<Item>, item: Item): List<Item> {
        val i = item.copy(text = item.text.take(TEXT_MAX), read = item.read || item.kind == Kind.YOU)
        val kept = if (i.kind == Kind.PROBLEM && i.key.isNotEmpty()) list.filterNot { it.kind == Kind.PROBLEM && it.key == i.key && !it.fixed } else list
        return (kept + i).takeLast(MAX)
    }

    /** The open problem with [key] is marked fixed (and read: nothing to do any more). */
    fun resolve(list: List<Item>, key: String): List<Item> =
        list.map { if (it.kind == Kind.PROBLEM && it.key == key && !it.fixed) it.copy(fixed = true, read = true) else it }

    /** Everything read (opening the Inbox). */
    fun readAll(list: List<Item>): List<Item> = list.map { if (it.read) it else it.copy(read = true) }

    /** The bell's count: unread messages plus open problems. */
    fun unread(list: List<Item>): Int = list.count { (it.kind == Kind.MESSAGE && !it.read) || (it.kind == Kind.PROBLEM && !it.fixed && !it.read) }

    /** Open problems, newest first. */
    fun problems(list: List<Item>): List<Item> = list.filter { it.kind == Kind.PROBLEM && !it.fixed }.reversed()

    /** One thread per chat (messages and your replies), newest thread first, each oldest line first. */
    fun threads(list: List<Item>): List<Pair<String, List<Item>>> =
        list.filter { it.kind != Kind.PROBLEM }.groupBy { it.chatId }.toList().sortedByDescending { (_, l) -> l.maxOf { it.at } }

    fun encode(list: List<Item>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    fun decode(text: String?): List<Item> = try {
        val a = JSONArray(text ?: return emptyList())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> Item.from(o) } }.takeLast(MAX)
    } catch (_: Exception) { emptyList() }

    // ---------------- the store ----------------

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items
    @Volatile private var file: File? = null
    private var seq = 0L

    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "inbox.json")
        file = f
        _items.value = try { if (f.exists()) decode(f.readText()) else emptyList() } catch (_: Exception) { emptyList() }
    }

    private fun nextId(): Long = synchronized(this) { val now = System.currentTimeMillis(); seq = if (now > seq) now else seq + 1; seq }

    private fun change(f: (List<Item>) -> List<Item>) {
        val next = synchronized(this) { f(_items.value).also { _items.value = it } }
        val out = file ?: return
        try { out.writeText(encode(next)) } catch (_: Exception) { }
    }

    /** Argus said [text] (a notification line); [fromUser] = your reply from the Inbox or the notification. */
    fun message(text: String, chatId: String?, fromUser: Boolean = false) {
        if (text.isBlank()) return
        change { add(it, Item(nextId(), System.currentTimeMillis(), if (fromUser) Kind.YOU else Kind.MESSAGE, text, chatId.orEmpty())) }
    }

    /** Something isn't working ([key] names it, so it's listed once). */
    fun problem(key: String, text: String) = change { add(it, Item(nextId(), System.currentTimeMillis(), Kind.PROBLEM, text, key = key)) }

    /** [key] works again. */
    fun resolved(key: String) { if (_items.value.any { it.kind == Kind.PROBLEM && it.key == key && !it.fixed }) change { resolve(it, key) } }

    fun markAllRead() { if (unread(_items.value) > 0) change(::readAll) }

    fun clear() = change { emptyList() }
}
