package com.blaze.assistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * P1-SH-04: the Manager's chats in Chat mode, as generations of the one main conversation ("one mind", no data move).
 *
 * Ported from A-team `app/www/talks.js` (branch v2.19-chat-drawer, 0788c808; MIT by owner grant): the `S.talks` list
 * `{ v, cur, list: [{ id, c, title?, named?, pin?, unseen?, del? }] }`, `newChat` (stays put while the open chat is
 * empty), `del` / `restore` / `purge` with a 5 s Undo, `rename` (≤ 80 characters, blank = automatic title), `pin`,
 * `replied` (unseen dot unless you were looking at it), `repair` (finish an interrupted delete, keep `cur` valid) and
 * `migrate` (the existing history becomes the first chat).
 *
 * What differs from talks.js (overhaul.md Deviations, P1-SH-04): A-team stamps every message with its chat id (`sid`);
 * here a chat is a time window of the main conversation, from its [Chat.start] to the next chat's start, because the
 * main chat file and Agent.kt stay as they are. ✎ New chat starts a new window (and the caller starts a fresh context
 * on main plus a Manager generation bump in BotThreads). Only the newest chat ([live]) takes new messages; an older one
 * opens read-only. Delete hides the window's messages from Chat mode and the drawer; they stay in the main chat file
 * and in memory until a later purge seam.
 *
 * Pure: state in, state out, JSON for the store. The Android side only reads and writes the text ([ChatStore]).
 */
class ManagerChats private constructor(private val chats: MutableList<Chat>, cur: Int) {

    /** One chat. [g] = its generation (0, 1, 2 …, never reused); [start] = when its window opens (ms, 0 = the beginning). */
    data class Chat(
        val g: Int,
        val start: Long,
        val title: String? = null,
        val named: Boolean = false,
        val pin: Long? = null,
        val unseen: Boolean = false,
        val del: Long? = null,
        val purged: Boolean = false,
        /** Owner 2026-11-14: the title ARGUS gives the chat as it goes on ([Titles]); a name you gave ([named]) wins. */
        val auto: String? = null,
        /** How many of your messages the chat had when [auto] was given (the next retitle counts from here). */
        val autoN: Int = 0,
    ) {
        val gone get() = del != null || purged
    }

    /** The chat on screen (may be an older, read-only one). */
    var cur: Int = cur; private set

    /** Every chat, purged ones included (their windows still bound the chat after them). */
    fun all(): List<Chat> = chats.toList()

    /** The chats the drawer may show (not deleted, not purged). */
    fun visible(): List<Chat> = chats.filter { !it.gone }

    fun get(g: Int): Chat? = chats.firstOrNull { it.g == g }

    /** The newest chat that is not gone: where new messages land. */
    val live: Int get() = chats.lastOrNull { !it.gone }?.g ?: chats.last().g

    /** True when the chat on screen is an older one (the composer sends to [live] instead). */
    val readOnly: Boolean get() = cur != live

    /** The window of chat [g]: [start, end) in ms; end = the next chat's start, or Long.MAX_VALUE for the newest. */
    fun window(g: Int): LongRange? {
        val i = chats.indexOfFirst { it.g == g }.takeIf { it >= 0 } ?: return null
        val end = chats.getOrNull(i + 1)?.start ?: Long.MAX_VALUE
        return chats[i].start until end
    }

    /** The chat a message with time [at] belongs to (0 = saved before times were kept: the first chat). */
    fun chatOf(at: Long): Int {
        var g = chats.first().g
        for (c in chats) if (c.start <= at) g = c.g
        return g
    }

    /** True when [at] falls in a deleted or purged chat (Chat mode hides those messages). */
    fun hidden(at: Long): Boolean = get(chatOf(at))?.gone == true

    // ---------------- actions (talks.js) ----------------

    sealed class New {
        /** The open chat is still empty: nothing changes (talks.js newChat "stays put"). */
        object Stayed : New()
        /** A new chat [g] opens at [start]; the caller starts a fresh context and bumps the Manager's generation. */
        data class Started(val g: Int, val start: Long) : New()
    }

    /**
     * ✎ New chat at [now]. [liveBlank] = the newest chat has no message you or ARGUS wrote. Viewing an older chat while
     * the newest is blank just goes back to the newest.
     */
    fun newChat(now: Long, liveBlank: Boolean): New {
        if (liveBlank) { cur = live; seen(cur); return New.Stayed }
        val start = maxOf(now, (chats.maxOfOrNull { it.start } ?: 0L) + 1)
        val g = (chats.maxOfOrNull { it.g } ?: -1) + 1
        chats += Chat(g, start)
        cur = g
        return New.Started(g, start)
    }

    /** Opens chat [g] (clears its dot). False when it doesn't exist or is gone. */
    fun open(g: Int): Boolean {
        val c = get(g) ?: return false
        if (c.gone) return false
        cur = g; seen(g)
        return true
    }

    fun seen(g: Int) { update(g) { it.copy(unseen = false) } }

    /** A reply finished in chat [g]: a dot unless you were looking at it ([viewing] = Chat mode is on screen). */
    fun replied(g: Int, viewing: Boolean) {
        val c = get(g) ?: return
        if (c.gone) return
        if (g != cur || !viewing) update(g) { it.copy(unseen = true) }
    }

    /** talks.js `anyUnseen`: a dot on the drawer button. */
    fun anyUnseen(): Boolean = chats.any { it.unseen && !it.gone && it.g != cur }

    /** talks.js `rename`: spaces collapsed, ≤ [TITLE_MAX]; blank = back to the automatic title. */
    fun rename(g: Int, title: String) {
        val v = title.replace(Regex("\\s+"), " ").trim().take(TITLE_MAX)
        update(g) { if (v.isEmpty()) it.copy(title = null, named = false) else it.copy(title = v, named = true) }
    }

    /** talks.js `pin`: toggles; pinned chats sort by pin time, newest first. Returns the new state. */
    fun pin(g: Int, now: Long): Boolean {
        var on = false
        update(g) { on = it.pin == null; it.copy(pin = if (on) now else null) }
        return on
    }

    /** What [delete] did, so [restore] can put it back. */
    data class Deleted(val g: Int, val wasCur: Boolean, val wasLive: Boolean, val replacement: Int?, val at: Long)

    /**
     * talks.js `del`: marks chat [g] deleted at [now] (Undo for [UNDO_MS]). Deleting the open chat moves to the newest
     * one left; deleting the newest opens a new empty chat ([Deleted.replacement], the caller starts a fresh context).
     * Null when [g] is unknown or already gone.
     */
    fun delete(g: Int, now: Long): Deleted? {
        val c = get(g) ?: return null
        if (c.gone) return null
        val wasCur = cur == g
        val wasLive = live == g
        update(g) { it.copy(del = now, unseen = false) }
        var repl: Int? = null
        if (wasLive) {
            val n = newChat(now, liveBlank = false) as New.Started
            repl = n.g
        }
        if (wasCur || wasLive) cur = live
        return Deleted(g, wasCur, wasLive, repl, now)
    }

    /**
     * talks.js `restore`: Undo. The chat comes back; when it was the newest and the replacement is still blank
     * ([replacementBlank]), the replacement goes away again and the restored chat is newest (and open, if it was).
     */
    fun restore(d: Deleted, replacementBlank: Boolean) {
        val c = get(d.g) ?: return
        if (c.purged) return
        update(d.g) { it.copy(del = null) }
        if (d.replacement != null && replacementBlank) chats.removeAll { it.g == d.replacement }
        if (d.wasCur && (cur == d.replacement || d.replacement == null)) cur = d.g
        if (get(cur) == null || get(cur)!!.gone) cur = live
    }

    /** talks.js `purge`: the Undo window closed. The chat never shows again (its window stays, so later chats keep theirs). */
    fun purge(g: Int) { update(g) { it.copy(purged = true, pin = null, unseen = false, title = null, named = false, auto = null, autoN = 0) } }

    /** Owner 2026-11-14: ARGUS's title for chat [g] after [userTurns] of your messages. A deleted chat keeps none. */
    fun autoTitle(g: Int, title: String, userTurns: Int) {
        val t = title.split(Regex("\\s+")).joinToString(" ").trim().take(TITLE_MAX)
        if (t.isNotEmpty()) update(g) { if (it.gone) it else it.copy(auto = t, autoN = userTurns) }
    }

    /** talks.js `repair`, once per load: deletes an app close interrupted are finished; `cur` points at a live chat. */
    fun repair(): Boolean {
        var changed = false
        for (c in chats.toList()) if (c.del != null && !c.purged) { purge(c.g); changed = true }
        if (get(cur)?.gone != false) { cur = live; changed = true }
        if (chats.all { it.gone }) { newChat((chats.maxOfOrNull { it.start } ?: 0L) + 1, liveBlank = false); changed = true }
        return changed
    }

    fun toJson(): String = JSONObject().put("v", VERSION).put("cur", cur).put("list", JSONArray().apply {
        for (c in chats) put(JSONObject().put("g", c.g).put("c", c.start).apply {
            if (c.title != null) put("title", c.title)
            if (c.named) put("named", true)
            if (c.pin != null) put("pin", c.pin)
            if (c.unseen) put("unseen", true)
            if (c.del != null) put("del", c.del)
            if (c.purged) put("purged", true)
            if (c.auto != null) put("auto", c.auto).put("autoN", c.autoN)
        })
    }).toString()

    private inline fun update(g: Int, f: (Chat) -> Chat) {
        val i = chats.indexOfFirst { it.g == g }
        if (i >= 0) chats[i] = f(chats[i])
    }

    companion object {
        const val VERSION = 1
        const val TITLE_MAX = 80
        /** talks.js UNDO_MS. */
        const val UNDO_MS = 5_000L
        /** The store's file in the chats folder (no `.json`: never taken for a chat, never listed or synced). */
        const val FILE = ".manager_chats"

        /**
         * Owner 2026-11-14 ("chat naming needs to be procedural as the conversation goes on"): a chat is named after its
         * first exchange, then renamed as it moves on: after 2 more of your messages, then every 3 while it is young (up to
         * 9), then every 5. A long new message (over 600 characters) can retitle a turn sooner (after 2).
         */
        fun titleDue(userTurns: Int, lastAt: Int, hasAuto: Boolean, lastUserChars: Int = 0): Boolean {
            if (userTurns <= 0) return false
            if (!hasAuto) return true
            val since = userTurns - lastAt
            val step = when { lastAt <= 1 -> 2; lastAt < 9 -> 3; else -> 5 }
            return since >= step || (since >= 2 && lastUserChars > 600)
        }

        /** talks.js `migrate`: nothing saved yet, so the whole main conversation so far is the first chat. */
        fun fresh(): ManagerChats = ManagerChats(mutableListOf(Chat(0, 0L)), 0)

        /** Reads [text] (null or broken = [fresh]), then [repair]s it. */
        fun read(text: String?): ManagerChats {
            val m = try {
                val o = JSONObject(text ?: return fresh())
                val a = o.optJSONArray("list") ?: JSONArray()
                val list = ArrayList<Chat>()
                for (i in 0 until a.length()) {
                    val x = a.optJSONObject(i) ?: continue
                    list += Chat(x.optInt("g", i), x.optLong("c"), x.optString("title").ifEmpty { null }, x.optBoolean("named"),
                        if (x.has("pin")) x.optLong("pin") else null, x.optBoolean("unseen"),
                        if (x.has("del")) x.optLong("del") else null, x.optBoolean("purged"),
                        x.optString("auto").ifEmpty { null }, x.optInt("autoN"))
                }
                if (list.isEmpty()) return fresh()
                list.sortWith(compareBy<Chat>({ it.start }, { it.g }))
                ManagerChats(list.distinctBy { it.g }.toMutableList(), o.optInt("cur", list.last().g))
            } catch (_: Exception) { return fresh() }
            m.repair()
            return m
        }
    }
}
