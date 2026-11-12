package com.blaze.assistant.ui.team

import com.blaze.assistant.agent.ChatMeta
import com.blaze.assistant.agent.ManagerChats
import java.time.Instant
import java.time.ZoneId

/**
 * P1-SH-04: what the Chat mode history drawer shows. Pure (ChatDrawerTest).
 *
 * Ported from A-team `app/www/talks.js` (branch v2.19-chat-drawer, 0788c808; MIT by owner grant): `index` (one pass
 * over the Manager's messages: first user line, last activity, searchable text up to 8,000 characters), `listable`,
 * `clean` + `title` (60 characters, cut at a word after 36, "New chat" when there is nothing), `listHTML` (search over
 * titles, then message text with a snippet 24 characters before the match and 48 after; Pinned first by pin time, then
 * Today / Yesterday / Previous 7 days / Older by last activity; "No chats match." / "No chats yet…") and `rowHTML`
 * (Replying dot over the unseen dot, never on the open chat).
 *
 * Beyond talks.js: the user's project chats (ChatStore) join the same list, marked with their project's name, because
 * 06 asks for one drawer over the Manager's chats and Projects.
 */
object ChatDrawerModel {
    const val TITLE_MAX = 60
    const val HAY_MAX = 8_000
    const val EMPTY = "No chats yet. Say something to start one."
    const val NO_MATCH = "No chats match."
    const val NEW_TITLE = "New chat"

    /** One message of the main conversation, as the drawer needs it. */
    data class Msg(val user: Boolean, val text: String, val at: Long, val listable: Boolean = true)

    /** A project chat ([meta] from ChatStore) and its message texts for search. */
    data class ProjectChat(val meta: ChatMeta, val project: String, val texts: List<String> = emptyList())

    sealed class Key {
        data class Manager(val g: Int) : Key()
        data class Project(val chatId: String) : Key()
    }

    data class Entry(
        val key: Key,
        val title: String,
        val at: Long,
        val pinnedAt: Long?,
        val current: Boolean,
        val unseen: Boolean,
        val live: Boolean,
        val project: String? = null,
        val snippet: String? = null,
    ) {
        /** TalkBack: one merged line per row. */
        val spoken: String get() = listOfNotNull(title, project?.let { "in $it" }, snippet,
            if (live) "replying" else if (unseen) "new reply" else null, if (current) "open" else null).joinToString(", ")
    }

    data class Section(val name: String, val entries: List<Entry>)

    data class State(val sections: List<Section>, val empty: String?) {
        val entries get() = sections.flatMap { it.entries }
    }

    /** talks.js `clean`: no code, no markdown marks, one line, ≤ 60 characters cut at a word. */
    fun clean(t: String?): String {
        var s = (t ?: "").replace(Regex("```[\\s\\S]*?```"), " ").replace(Regex("[#*_`>\\[\\]]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (s.length <= TITLE_MAX) return s
        val cut = s.substring(0, TITLE_MAX)
        val sp = cut.lastIndexOf(' ')
        s = (if (sp > 36) cut.substring(0, sp) else cut).replace(Regex("[\\s,.;:-]+$"), "")
        return "$s\u2026"
    }

    /** The drawer's sections for [chats] over the main conversation's [messages], plus [projects]. */
    fun state(
        chats: ManagerChats,
        messages: List<Msg>,
        projects: List<ProjectChat>,
        now: Long,
        zone: ZoneId,
        query: String = "",
        replying: Int? = null,
        currentProjectChat: String? = null,
    ): State {
        data class Acc(val c: ManagerChats.Chat, var first: String = "", var firstA: String = "", var at: Long, var n: Int = 0,
                       val hay: MutableList<String> = ArrayList(), var hl: Int = 0)
        val by = LinkedHashMap<Int, Acc>()
        for (c in chats.visible()) by[c.g] = Acc(c, at = c.start)
        for (m in messages) {
            val a = by[chats.chatOf(m.at)] ?: continue
            if (m.at > a.at) a.at = m.at
            if (!m.listable || m.text.isBlank()) continue
            a.n++
            if (m.user) { if (a.first.isEmpty()) a.first = m.text } else if (a.firstA.isEmpty()) a.firstA = m.text
            if (a.hl < HAY_MAX) { a.hay += m.text; a.hl += m.text.length }
        }
        val onManager = currentProjectChat == null
        val raw = ArrayList<Pair<Entry, List<String>>>()
        for (a in by.values) {
            if (a.n == 0) continue   // talks.js: only chats with something in them are listed
            val title = (if (a.c.named) a.c.title else null) ?: clean(a.first.ifEmpty { a.firstA }).ifEmpty { NEW_TITLE }
            val cur = onManager && a.c.g == chats.cur
            raw += Entry(ChatDrawerModel.Key.Manager(a.c.g), title, a.at, a.c.pin, cur, a.c.unseen && !cur, replying == a.c.g) to a.hay
        }
        for (p in projects) {
            val cur = p.meta.id == currentProjectChat
            raw += Entry(ChatDrawerModel.Key.Project(p.meta.id), clean(p.meta.title).ifEmpty { NEW_TITLE }, p.meta.updated,
                if (p.meta.pinned) p.meta.updated else null, cur, false, false, p.project) to (listOf(p.meta.preview) + p.texts)
        }
        val q = query.trim()
        val shown = if (q.isEmpty()) raw.map { it.first } else raw.mapNotNull { (e, hay) -> match(e, hay, q) }
        if (shown.isEmpty()) return State(emptyList(), if (q.isEmpty()) EMPTY else NO_MATCH)
        val pins = shown.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }
        val rest = shown.filter { it.pinnedAt == null }.sortedByDescending { it.at }
        val groups = LinkedHashMap<String, MutableList<Entry>>().apply { GROUPS.forEach { put(it, ArrayList()) } }
        for (e in rest) groups[group(e.at, now, zone)]!! += e
        val out = ArrayList<Section>()
        if (pins.isNotEmpty()) out += Section(PINNED, pins)
        for ((name, l) in groups) if (l.isNotEmpty()) out += Section(name, l)
        return State(out, null)
    }

    const val PINNED = "Pinned"
    val GROUPS = listOf("Today", "Yesterday", "Previous 7 days", "Older")

    /** talks.js grouping: local midnight today, the day before, the 7 days before that, then the rest. */
    fun group(at: Long, now: Long, zone: ZoneId): String {
        val t0 = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone)
        val today = t0.toInstant().toEpochMilli()
        val yesterday = t0.minusDays(1).toInstant().toEpochMilli()
        val week = t0.minusDays(7).toInstant().toEpochMilli()
        return when { at >= today -> GROUPS[0]; at >= yesterday -> GROUPS[1]; at >= week -> GROUPS[2]; else -> GROUPS[3] }
    }

    /** talks.js search: a title match shows no snippet; else the first text that matches gives one. Literal, any case. */
    fun match(e: Entry, hay: List<String>, q: String): Entry? {
        if (e.title.contains(q, ignoreCase = true)) return e.copy(snippet = null)
        for (tx in hay) {
            val one = tx.replace(Regex("\\s+"), " ")
            val i = one.indexOf(q, ignoreCase = true)
            if (i < 0) continue
            val snip = (if (i > 24) "\u2026" else "") + one.substring(maxOf(0, i - 24), minOf(one.length, i + q.length + 48)).trim()
            return e.copy(snippet = snip)
        }
        return null
    }

    /** talks.js `transcript` (Copy): "You: …" / "ARGUS: …" lines, blank line between. */
    fun transcript(messages: List<Msg>, me: String, bot: String): String =
        messages.filter { it.listable && it.text.isNotBlank() }.joinToString("\n\n") { (if (it.user) me else bot) + ": " + it.text.trim() }

    /** The messages of Manager chat [g] (for Copy and for the chat on screen). */
    fun messagesOf(chats: ManagerChats, g: Int, messages: List<Msg>): List<Msg> = messages.filter { chats.chatOf(it.at) == g }

    /** The newest chat has nothing you or ARGUS wrote yet (talks.js `blank`). */
    fun blank(chats: ManagerChats, g: Int, messages: List<Msg>): Boolean =
        messages.none { chats.chatOf(it.at) == g && it.listable && it.text.isNotBlank() }

    /** Undo: the toast's words and whether the window is still open at [now]. */
    object Undo {
        const val DELETED = "Chat deleted"
        const val RESTORED = "Chat restored"
        fun open(d: ManagerChats.Deleted?, now: Long): Boolean = d != null && now - d.at < ManagerChats.UNDO_MS
    }
}

/**
 * P1-SH-04: the words of the empty Chat mode (04 §4.13): the serif greeting by the hour, the suggested asks (AM's
 * empty-chat asks plus 04's three) and the read-only line on an older chat.
 */
object ChatModeCopy {
    fun part(hour: Int): String = when (hour) { in 5..11 -> "Morning"; in 12..16 -> "Afternoon"; in 17..21 -> "Evening"; else -> "Late one" }

    /** "Evening. What's on your mind?" ([name] = the user's, when set: "Evening, Finn."). */
    fun greeting(hour: Int, name: String? = null): String =
        part(hour) + (name?.trim()?.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: "") + ". What's on your mind?"

    val ASKS = listOf("Plan my week", "What did I miss?", "Set an alarm for 6:30", "Draw a lion wearing sunglasses")

    const val READ_ONLY = "An earlier chat. What you send goes to your newest chat."
    const val PRIVATE_ON = "Private chat: nothing here is saved or learned"
}
