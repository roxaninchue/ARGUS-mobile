package com.blaze.assistant.team

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1-TM-02: Bot HQ row semantics (upsert by seq, updatedSeq, generation bump), bot threads, and the ARGUS bridge. */
class RowStoreTest {
    private var now = 5_000L
    private val port = MemoryTeamPort()
    private val team = TeamStore(port) { now }.also { it.ensure() }
    private val rows = Rows(port) { now }

    @Test fun appendTakesTheNextSeqAsBothSeqAndUpdatedSeq() {
        val a = rows.append("tempo", RowKind.MESSAGE, RowBody.message("user", "hi"))
        val b = rows.append("tempo", RowKind.MESSAGE, RowBody.message("assistant", "hello", "tempo"))
        assertEquals(1L, a.seq); assertEquals(1L, a.updatedSeq); assertEquals(2L, b.seq); assertEquals(2L, b.updatedSeq)
        // other bots count on their own
        assertEquals(1L, rows.append("inbox", RowKind.EVENT, RowBody.event("x", "y")).seq)
    }

    @Test fun updateKeepsSeqAndBumpsUpdatedSeqSoSinceSeesIt() {
        val a = rows.append("tempo", RowKind.MESSAGE, RowBody.message("assistant", "Wor", "tempo", status = "streaming"))
        rows.append("tempo", RowKind.TOOL_CALL, RowBody.toolCall("set_alarm", "Setting an alarm", "running"))
        val seen = rows.since("tempo").maxOf { it.updatedSeq }
        val u = rows.update("tempo", a.seq, RowBody.message("assistant", "Working on it", "tempo"))!!
        assertEquals(a.seq, u.seq); assertEquals(3L, u.updatedSeq)
        val delta = rows.since("tempo", seen)
        assertEquals(listOf(a.seq), delta.map { it.seq })
        assertEquals("Working on it", RowBody.text(delta[0]))
        assertNull(rows.update("tempo", 99, "{}"))
        // same body: no new updatedSeq
        assertEquals(3L, rows.update("tempo", a.seq, u.body)!!.updatedSeq)
    }

    @Test fun upsertBySeqKeepsTheNewest() {
        val r = Row("inbox", 0, 7, 7, RowKind.MESSAGE, RowBody.message("assistant", "v1", "inbox"), 1)
        rows.upsert(r)
        rows.upsert(r.copy(updatedSeq = 9, body = RowBody.message("assistant", "v2", "inbox")))
        rows.upsert(r.copy(updatedSeq = 8, body = RowBody.message("assistant", "old", "inbox")))
        assertEquals("v2", RowBody.text(rows.all("inbox").single()))
        assertEquals(10L, rows.append("inbox", RowKind.NOTICE, RowBody.notice("n")).seq)
    }

    @Test fun newGenerationIsAFreshContextAndKeepsTheOldRows() {
        rows.append("tempo", RowKind.MESSAGE, RowBody.message("user", "first chat"))
        val g = rows.newGeneration("tempo")
        assertEquals(1, g)
        assertTrue(rows.since("tempo").isEmpty())
        val r = rows.append("tempo", RowKind.MESSAGE, RowBody.message("user", "second chat"))
        assertEquals(1, r.generation); assertEquals(2L, r.seq)   // seq keeps counting across generations
        assertEquals(listOf(1, 0), rows.generations("tempo"))
        assertEquals("first chat", RowBody.text(rows.all("tempo", 0).single()))
    }

    @Test fun unknownKindsAreRefused() {
        try { rows.append("tempo", "weird", "{}"); throw AssertionError("accepted") } catch (_: IllegalArgumentException) { }
    }

    private fun threads(chat: JSONObject? = null) = BotThreads(port, rows, team, { chat }) { now }

    @Test fun threadsTrackUnreadPreviewAndActivity() {
        val t = threads()
        now = 10; t.post("tempo", "user", "Remind me at 7")
        now = 20; t.post("tempo", "assistant", "Set for 7:00 tomorrow.")
        now = 30; t.card("tempo", "handoff", mapOf("title" to "Atlas is on it", "to" to "atlas"))
        val s = t.summary(team.get("tempo")!!)
        assertEquals(2, s.unread); assertEquals("Atlas is on it", s.preview); assertEquals(30L, s.lastActivityAt)
        t.markRead("tempo"); assertEquals(0, t.summary(team.get("tempo")!!).unread)
        // watching the thread: no unread
        t.post("tempo", "assistant", "Anything else?", viewing = true)
        assertEquals(0, t.summary(team.get("tempo")!!).unread)
        // a user message clears unread and previews as "You: …"
        t.post("inbox", "assistant", "3 new mails"); t.post("inbox", "user", "Summarise them")
        assertEquals(0, t.state("inbox").unread); assertEquals("You: Summarise them", t.state("inbox").lastPreview)
    }

    @Test fun listPutsArgusFirstThenPinnedThenRecent() {
        val t = threads()
        team.create(Bot(id = "", kind = BotKind.CUSTOM, name = "Atlas", label = "Travel", avatar = Bots.avatar(2, Bots.COLORS[1]), soulPath = "",
            toolGroups = setOf("web"), createdAt = 1, u = 1))
        now = 100; t.post("inbox", "assistant", "old")
        now = 200; t.post("atlas", "assistant", "new")
        assertEquals(listOf("manager", "atlas", "inbox", "tempo"), t.list().map { it.bot.id })
        team.setPinned("tempo", true)
        assertEquals(listOf("manager", "tempo", "atlas", "inbox"), t.list().map { it.bot.id })
        assertEquals(2, t.totalUnread())
    }

    @Test fun newChatBumpsTheGenerationAndClearsUnread() {
        val t = threads(); t.post("inbox", "assistant", "x")
        assertEquals(1, t.newChat("inbox")); assertEquals(0, t.state("inbox").unread); assertTrue(t.timeline("inbox").isEmpty())
    }

    // ---- ARGUS bridges the AM main chat: read only, history intact

    private fun amChat(): JSONObject {
        val ms = JSONArray()
            .put(JSONObject().put("id", 1).put("role", "USER").put("text", "What's on today?").put("files", JSONArray()).put("at", 100).put("gid", "g1"))
            .put(JSONObject().put("id", 2).put("role", "STEP").put("text", "Checked the calendar").put("files", JSONArray()).put("at", 101).put("gid", "g2"))
            .put(JSONObject().put("id", 3).put("role", "BLAZE").put("text", "Two meetings, first at 9.").put("files", JSONArray()).put("at", 102).put("gid", "g3"))
            .put(JSONObject().put("id", 4).put("role", "ERROR").put("text", "Couldn't reach the internet").put("files", JSONArray()).put("gid", "g4"))
        return JSONObject().put("id", "c1").put("title", "Today").put("updated", 150).put("pinned", false).put("messages", ms).put("history", JSONArray())
    }

    @Test fun argusThreadReadsTheAmChatWithoutChangingIt() {
        val chat = amChat(); val before = chat.toString()
        val r = ArgusBridge.rows(chat)
        assertEquals(listOf(RowKind.MESSAGE, RowKind.EVENT, RowKind.MESSAGE, RowKind.NOTICE), r.map { it.kind })
        assertEquals(listOf("user", "", "assistant", ""), r.map { RowBody.role(it) })
        assertEquals("Two meetings, first at 9.", RowBody.text(r[2]))
        assertEquals(150L, r[3].createdAt)   // no "at": the chat's updated time
        assertTrue(r.all { it.botId == "manager" && it.generation == ArgusBridge.GENERATION })
        assertEquals(before, chat.toString())   // nothing written back: AM history intact
        assertEquals("Two meetings, first at 9." to 102L, ArgusBridge.last(chat))
    }

    @Test fun argusTimelineMergesTheAmChatWithTeamRowsByTime() {
        val t = threads(amChat())
        now = 101; t.card("manager", "handoff", mapOf("title" to "Inbox is on it", "to" to "inbox"))
        now = 200; t.post("manager", "assistant", "Inbox finished: 3 replies drafted.", from = "inbox", jobId = "j1")
        val tl = t.timeline("manager")
        assertEquals(6, tl.size)
        assertEquals("What's on today?", RowBody.text(tl[0]))
        assertEquals(RowKind.CARD, tl[2].kind)   // the 101 card sorts after the 101 AM step
        assertEquals("Inbox finished: 3 replies drafted.", RowBody.text(tl.last()))
    }

    @Test fun argusSavedUpdatesPreviewAndUnread() {
        val t = threads()
        t.argusSaved(amChat(), viewing = false)
        assertEquals("Two meetings, first at 9.", t.state("manager").lastPreview); assertEquals(1, t.state("manager").unread)
        t.argusSaved(amChat(), viewing = false)   // same save again: no double count
        assertEquals(1, t.state("manager").unread)
        t.argusSaved(amChat().put("messages", amChat().getJSONArray("messages").put(JSONObject().put("id", 5).put("role", "USER").put("text", "thanks").put("at", 300))), viewing = false)
        assertEquals(0, t.state("manager").unread); assertEquals("You: thanks", t.state("manager").lastPreview)
    }
}
