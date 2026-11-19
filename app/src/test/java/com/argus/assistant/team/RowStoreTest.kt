package com.blaze.assistant.team

import com.blaze.assistant.store.JdbcSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * P1-TM-02: the Bot HQ row model on a real SQLite (sqlite-jdbc, team.db's own [TeamSchema]). Ports Bot HQ
 * `server/src/store.ts` appendRow / updateRow / listRows({beforeSeq, limit, afterRev}) / clearConversation, `toBot`'s
 * preview, loop.ts's unread rule and `triggerText`: upsert by seq (newer revision wins, stale and repeated deliveries
 * ignored), a generation bump starts the thread over at seq 1 and keeps the old rows.
 */
class RowStoreTest {
    private val sql = JdbcSql()
    private var now = 1_731_400_000_000L
    private val team = TeamStore(sql) { now }
    private val rows = RowStore(sql) { now }

    init {
        sql.exec("PRAGMA foreign_keys = ON")
        sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
        team.ensure()
    }

    @After fun close() = sql.close()

    private fun msg(text: String, from: String = "tempo", streaming: Boolean = false) = RowBodies.message(from, text, streaming)
    private fun row(seq: Long, upd: Long, text: String, g: Int = 0, kind: String = RowKind.MESSAGE) = Row("tempo", g, seq, upd, kind, msg(text), now)

    @Test fun appendNumbersFromOneAndUpdateKeepsSeqWithHigherRevision() {
        assertEquals(1L, rows.nextSeq("tempo", 0))
        val a = rows.append("tempo", RowKind.MESSAGE, msg("hi", "user"))!!
        val b = rows.append("tempo", RowKind.MESSAGE, msg("Hel", streaming = true))!!
        assertEquals(1L to 1L, a.seq to a.updatedSeq); assertEquals(2L to 2L, b.seq to b.updatedSeq)
        val b2 = rows.update("tempo", 2, msg("Hello"))!!
        assertEquals(2L, b2.seq); assertEquals(3L, b2.updatedSeq)
        val c = rows.append("tempo", RowKind.EVENT, RowBodies.event("routine-run", "Morning brief ran"))!!
        assertEquals("a new seq never meets an older revision", 4L, c.seq)
        assertEquals(listOf(1L, 2L, 4L), rows.list("tempo", 0).map { it.seq })
        assertEquals("Hello", RowBodies.read(rows.get("tempo", 0, 2)!!.body)["text"])
        assertNull(rows.update("tempo", 99, msg("x")))
        assertNull("no such bot", rows.append("ghost", RowKind.MESSAGE, msg("x")))
    }

    @Test fun upsertBySeqNewerRevisionWinsStaleAndRepeatsIgnored() {
        assertEquals(RowStore.Upsert.INSERTED, rows.upsert(row(1, 1, "one")))
        assertEquals(RowStore.Upsert.INSERTED, rows.upsert(row(2, 2, "Wor")))
        assertEquals(RowStore.Upsert.UPDATED, rows.upsert(row(2, 5, "Working")))
        assertEquals("a late, older revision never rolls the row back", RowStore.Upsert.STALE, rows.upsert(row(2, 3, "Wo")))
        assertEquals("the same delivery twice", RowStore.Upsert.STALE, rows.upsert(row(2, 5, "Working")))
        assertEquals("Working", RowBodies.read(rows.get("tempo", 0, 2)!!.body)["text"])
        assertEquals(2L, sql.query("SELECT count(*) FROM rows") { it.long(0) }.first())
        assertEquals(6L, rows.nextSeq("tempo", 0))
        // a kind change on a newer revision is taken (a tool call that became a card)
        assertEquals(RowStore.Upsert.UPDATED, rows.upsert(row(1, 6, "one", kind = RowKind.CARD)))
        assertEquals(RowKind.CARD, rows.get("tempo", 0, 1)!!.kind)
    }

    @Test fun badRowsRefused() {
        for (bad in listOf(row(1, 1, "x", kind = "bubble"), row(0, 0, "x"), row(3, 2, "x"))) {
            try { rows.upsert(bad); fail("took $bad") } catch (_: IllegalArgumentException) { }
        }
        try { rows.append("tempo", "card-ish", "{}"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0L, sql.query("SELECT count(*) FROM rows") { it.long(0) }.first())
    }

    @Test fun pagingAndSince() {
        for (i in 1..12) rows.append("tempo", RowKind.MESSAGE, msg("m$i"))
        assertEquals((8L..12L).toList(), rows.list("tempo", 0, limit = 5).map { it.seq })
        assertEquals((3L..7L).toList(), rows.list("tempo", 0, beforeSeq = 8, limit = 5).map { it.seq })
        assertEquals(listOf(1L, 2L), rows.list("tempo", 0, beforeSeq = 3, limit = 5).map { it.seq })
        assertEquals(12, rows.list("tempo", 0, limit = 10_000).size)
        rows.update("tempo", 4, msg("m4 edited"))   // revision 13
        assertEquals("new and changed rows after revision 11, in seq order", listOf(4L, 12L), rows.since("tempo", 0, 11).map { it.seq })
        assertEquals(listOf(4L), rows.since("tempo", 0, 12).map { it.seq })
        assertTrue(rows.since("tempo", 0, 13).isEmpty())
    }

    @Test fun generationBumpStartsOverAndKeepsOldRows() {
        rows.append("tempo", RowKind.MESSAGE, msg("old 1")); rows.append("tempo", RowKind.MESSAGE, msg("old 2"))
        sql.exec("UPDATE bots SET unread=2, last_preview='old 2', last_activity_at=5 WHERE id='tempo'")
        assertEquals(1, rows.fresh("tempo"))
        assertEquals(1, rows.generation("tempo"))
        val r = rows.append("tempo", RowKind.MESSAGE, msg("new"))!!
        assertEquals(1, r.generation); assertEquals("the new conversation starts at seq 1", 1L, r.seq)
        assertEquals(listOf("new"), rows.list("tempo", 1).map { RowBodies.read(it.body)["text"] })
        assertEquals("old rows stay for the history drawer", 2, rows.list("tempo", 0).size)
        val (unread, preview, last) = sql.query("SELECT unread, coalesce(last_preview,'-'), last_activity_at FROM bots WHERE id='tempo'") { Triple(it.long(0), it.text(1), it.long(2)) }.first()
        assertEquals(0L, unread); assertEquals("-", preview); assertEquals("last activity kept", 5L, last)
        // a turn that began before the bump still lands in its own generation
        assertEquals(RowStore.Upsert.INSERTED, rows.upsert(row(3, 3, "late reply", g = 0)))
        assertEquals(3, rows.list("tempo", 0).size); assertEquals(1, rows.list("tempo", 1).size)
        assertNull(rows.fresh("ghost"))
    }

    @Test fun teamStoreWritesKeepChatCounters() {
        sql.exec("UPDATE bots SET generation=3, unread=4, last_preview='hey', last_activity_at=9 WHERE id='tempo'")
        val t = team.get("tempo")!!
        assertTrue(team.update(t.copy(label = "Calendar")) is TeamStore.Result.Ok)
        assertEquals(listOf(3L, 4L), sql.query("SELECT generation, unread FROM bots WHERE id='tempo'") { listOf(it.long(0), it.long(1)) }.first())
    }

    @Test fun previewPortsBotHqToBot() {
        assertEquals("Hello there", RowBodies.preview(RowKind.MESSAGE, msg("  Hello there ")))
        assertEquals("x".repeat(140), RowBodies.preview(RowKind.MESSAGE, msg("x".repeat(400))))
        assertEquals("Waiting for you: Send the invoice", RowBodies.preview(RowKind.CARD, RowBodies.card("approval", mapOf("title" to "Send the invoice"))))
        assertEquals("Which day?", RowBodies.preview(RowKind.CARD, RowBodies.card("question", mapOf("question" to "Which day?"))))
        assertEquals("Connect Gmail", RowBodies.preview(RowKind.CARD, RowBodies.card("connect", mapOf("name" to "Gmail"))))
        assertEquals("Draft", RowBodies.preview(RowKind.CARD, RowBodies.card("draft", emptyMap())))
        assertEquals("Morning brief ran", RowBodies.preview(RowKind.EVENT, RowBodies.event("routine-run", "Morning brief ran")))
        assertNull("notices are never the preview", RowBodies.preview(RowKind.NOTICE, RowBodies.notice("Network down", "error")))
        assertNull(RowBodies.preview(RowKind.TOOL_CALL, TeamJson.write(mapOf("name" to "web_search", "state" to "running"))))
        assertNull(RowBodies.preview(RowKind.MESSAGE, "not json"))
        assertNull(RowBodies.preview(RowKind.MESSAGE, msg("   ")))
    }

    @Test fun unreadRulePortsLoopTs() {
        assertTrue(RowBodies.countsUnread(RowKind.MESSAGE, msg("Done.")))
        assertFalse("the user's own words", RowBodies.countsUnread(RowKind.MESSAGE, msg("hi", "user")))
        assertFalse("still streaming", RowBodies.countsUnread(RowKind.MESSAGE, msg("Wor", streaming = true)))
        assertFalse("an empty reply", RowBodies.countsUnread(RowKind.MESSAGE, msg(" ")))
        assertTrue(RowBodies.countsUnread(RowKind.CARD, RowBodies.card("approval", mapOf("title" to "Pay"))))
        assertFalse(RowBodies.countsUnread(RowKind.CARD, RowBodies.card("draft", emptyMap())))
        assertFalse(RowBodies.countsUnread(RowKind.EVENT, RowBodies.event("routine-run", "ran")))
        assertFalse(RowBodies.countsUnread(RowKind.TOOL_CALL, "{}"))
    }

    @Test fun triggerTextPortsLoopTs() {
        assertEquals("plan my week", Trigger.User("plan my week").text())
        assertNull(Trigger.User("x").untrusted); assertNull(Trigger.User("x").via)
        assertEquals("voice", Trigger.User("x", byVoice = true).via!!.kind)
        assertEquals("[Routine \"Morning brief\" is running now. Do this:]\nSummarise today", Trigger.Routine("r1", "Morning brief", "Summarise today").text())
        val b = Trigger.Bot("ledger", "Ledger", "Is Friday free?")
        assertEquals("[Message from Ledger, another Bot on the team. Reply to them with send_to_bot if they asked you something.]\nIs Friday free?", b.text())
        assertEquals("bot:ledger", b.untrusted); assertEquals(RowBodies.Via("bot", "ledger", "Ledger"), b.via)
        assertEquals("[System notice] Calendar connected", Trigger.System("Calendar connected").text())
        val m = RowBodies.read(RowBodies.message("user", "x", via = b.via))
        assertEquals(mapOf("kind" to "bot", "id" to "ledger", "name" to "Ledger"), m["via"])
        assertFalse("streaming=false is left out", RowBodies.message("tempo", "x").contains("streaming"))
    }
}
