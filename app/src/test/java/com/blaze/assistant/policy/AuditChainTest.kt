package com.blaze.assistant.policy

import com.blaze.assistant.team.ActionClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P2-SF-05: the hash chain catches an edited, deleted, reordered or inserted row, and a cut or rewritten tail via the head. */
class AuditChainTest {

    private fun ev(i: Int, decision: Decision = Decision.AUTO, cls: ActionClass = ActionClass.WRITE_LOCAL) = AuditEvent(
        at = 1_762_000_000_000L + i * 1000L, botId = if (i % 2 == 0) "money" else "scout", chain = listOf("manager", "money"),
        trigger = "user", tool = "tool_$i", actionClass = cls, args = """{"n":$i,"note":"row $i"}""", decision = decision,
        decidedBy = if (decision == Decision.AUTO) "policy" else "user", device = "Pixel 7", result = "ok $i",
        undo = if (i % 3 == 0) "undo:$i" else null, costMinor = if (i % 4 == 0) 150L else null)

    private fun log(n: Int): List<AuditEntry> {
        var l = emptyList<AuditEntry>()
        for (i in 1..n) l = Audit.appendTo(l, ev(i, if (i % 5 == 0) Decision.DENIED else Decision.AUTO))
        return l
    }

    private fun head(l: List<AuditEntry>) = Head(l.last().id, l.last().hash)

    @Test fun freshChainVerifies() {
        val l = log(10)
        assertEquals((1L..10L).toList(), l.map { it.id })
        assertEquals(Audit.GENESIS, l.first().prevHash)
        for (i in 1 until l.size) assertEquals(l[i - 1].hash, l[i].prevHash)
        assertTrue(l.all { it.hash.length == 64 && it.hash.all { c -> c in "0123456789abcdef" } })
        assertEquals(ChainCheck.OK, Audit.verify(l))
        assertEquals(ChainCheck.OK, Audit.verify(l, head(l)))
        assertEquals(ChainCheck.OK, Audit.verify(emptyList()))
        assertEquals("History intact", Audit.verify(l).line())
    }

    @Test fun hashIsDeterministicAndCoversEveryColumn() {
        val a = Audit.append(null, ev(3))
        assertEquals(a.hash, Audit.append(null, ev(3)).hash)
        assertEquals(a.hash, Audit.hashOf(a))
        val variants = listOf(
            a.copy(at = a.at + 1), a.copy(botId = "x"), a.copy(chain = listOf("manager")), a.copy(trigger = "routine"),
            a.copy(tool = "other"), a.copy(actionClass = ActionClass.SEND), a.copy(argsRedacted = a.argsRedacted + " "),
            a.copy(decision = Decision.ALWAYS), a.copy(decidedBy = "money"), a.copy(device = "web"), a.copy(result = "fail"),
            a.copy(undo = null), a.copy(undo = ""), a.copy(costMinor = 1), a.copy(prevHash = "1".repeat(64)), a.copy(id = 2))
        for (v in variants) assertNotEquals("column change must change the hash: $v", a.hash, Audit.hashOf(v))
        // null and "" are different values
        val n = a.copy(device = null); val e = a.copy(device = "")
        assertNotEquals(Audit.hashOf(n), Audit.hashOf(e))
    }

    @Test fun editedRowIsCaught() {
        val l = log(8)
        val edits = listOf<(AuditEntry) -> AuditEntry>(
            { it.copy(argsRedacted = "{\"n\":4,\"note\":\"harmless\"}") },
            { it.copy(decision = Decision.ALLOWED) },
            { it.copy(result = "never happened") },
            { it.copy(botId = "manager") },
            { it.copy(costMinor = 0) },
            { it.copy(at = it.at - 60_000) })
        for (edit in edits) {
            val t = l.toMutableList(); t[3] = edit(t[3])
            val c = Audit.verify(t)
            assertFalse(c.ok)
            assertEquals(ChainCheck.Reason.BAD_HASH, c.reason)
            assertEquals(3, c.badIndex); assertEquals(4L, c.badId)
        }
    }

    @Test fun editedRowWithRecomputedHashBreaksTheNextLink() {
        val l = log(8)
        val t = l.toMutableList()
        val forged = t[3].copy(decision = Decision.ALLOWED).let { it.copy(hash = Audit.hashOf(it)) }
        t[3] = forged
        val c = Audit.verify(t)
        assertEquals(ChainCheck.Reason.BROKEN_LINK, c.reason)
        assertEquals(4, c.badIndex)
    }

    @Test fun editedLastRowWithRecomputedHashIsCaughtByTheHead() {
        val l = log(6)
        val t = l.toMutableList()
        t[5] = t[5].copy(result = "rewritten").let { it.copy(hash = Audit.hashOf(it)) }
        assertTrue(Audit.verify(t).ok)                                     // the rows alone can't tell
        assertEquals(ChainCheck.Reason.HEAD_MISMATCH, Audit.verify(t, head(l)).reason)
    }

    @Test fun deletedRowIsCaught() {
        val l = log(8)
        val mid = l.toMutableList().apply { removeAt(4) }
        Audit.verify(mid).let { assertEquals(ChainCheck.Reason.BROKEN_LINK, it.reason); assertEquals(4, it.badIndex); assertEquals(6L, it.badId) }
        val first = l.drop(1)
        assertEquals(ChainCheck.Reason.BAD_GENESIS, Audit.verify(first).reason)
        val last = l.dropLast(1)
        assertTrue(Audit.verify(last).ok)                                  // a cut tail looks fine without the head …
        Audit.verify(last, head(l)).let { assertEquals(ChainCheck.Reason.TRUNCATED, it.reason); assertEquals(8L, it.badId) }
        assertEquals(ChainCheck.Reason.TRUNCATED, Audit.verify(emptyList(), head(l)).reason)  // … and so does an emptied table
    }

    @Test fun reorderedRowsAreCaught() {
        val l = log(8)
        val swapped = l.toMutableList().apply { val x = this[2]; this[2] = this[5]; this[5] = x }
        val c = Audit.verify(swapped)
        assertFalse(c.ok); assertEquals(ChainCheck.Reason.BROKEN_LINK, c.reason); assertEquals(2, c.badIndex)
        val adjacent = l.toMutableList().apply { val x = this[6]; this[6] = this[7]; this[7] = x }
        assertEquals(ChainCheck.Reason.BROKEN_LINK, Audit.verify(adjacent).reason)
        assertFalse(Audit.verify(l.reversed()).ok)
    }

    @Test fun insertedRowIsCaught() {
        val l = log(8)
        // A well-formed row sealed onto row 3 and slipped in after it: its own hash is valid, the next link is not.
        val fake = Audit.append(l[2], ev(99, Decision.ALLOWED, ActionClass.SPEND))
        val t = l.toMutableList().apply { add(3, fake) }
        val c = Audit.verify(t)
        assertFalse(c.ok); assertEquals(ChainCheck.Reason.BROKEN_LINK, c.reason); assertEquals(4, c.badIndex)
        // Inserted at the very front with a fake genesis link.
        val front = listOf(Audit.append(null, ev(100))) + l
        assertFalse(Audit.verify(front).ok)
        // Re-numbered ids alone (same hashes) fail the hash check.
        val renumbered = l.mapIndexed { i, e -> if (i >= 3) e.copy(id = e.id + 1) else e }
        assertEquals(ChainCheck.Reason.BAD_HASH, Audit.verify(renumbered).reason)
    }

    @Test fun fullyRebuiltForgeryIsCaughtByTheHead() {
        val l = log(6)
        // Attacker re-seals everything from row 3 on, with an inserted row: the chain is self-consistent …
        var forged = l.take(3)
        forged = Audit.appendTo(forged, ev(77, Decision.ALWAYS, ActionClass.SPEND))
        for (i in 4..6) forged = Audit.appendTo(forged, ev(i))
        assertTrue(Audit.verify(forged).ok)
        // … but not with the head kept outside team.db.
        assertFalse(Audit.verify(forged, head(l)).ok)
    }

    @Test fun laggingHeadIsFineButAWrongOneIsNot() {
        val l = log(6)
        assertTrue(Audit.verify(l, Head(l[3].id, l[3].hash)).ok)                 // head written before the last 2 rows
        assertEquals(ChainCheck.Reason.HEAD_MISMATCH, Audit.verify(l, Head(l[3].id, l[2].hash)).reason)
        val rebuilt = Audit.appendTo(l.take(3), ev(55))
        assertFalse(Audit.verify(rebuilt, Head(l[3].id, l[3].hash)).ok)          // row 4 replaced → head no longer matches
    }

    @Test fun sequenceGapIsCaught() {
        val l = log(4)
        val a = l[3]
        val gap = a.copy(id = 9).let { it.copy(hash = Audit.hashOf(it)) }
        assertEquals(ChainCheck.Reason.BAD_SEQUENCE, Audit.verify(l.dropLast(1) + gap).reason)
    }

    @Test fun pageVerifiesFromTheRowBeforeIt() {
        val l = log(12)
        assertTrue(Audit.verify(l.subList(5, 12), head(l), after = l[4]).ok)
        assertFalse(Audit.verify(l.subList(6, 12), after = l[4]).ok)
        assertEquals(ChainCheck.Reason.BROKEN_LINK, Audit.verify(l.subList(6, 12), after = l[4]).reason)
    }

    @Test fun headRoundTrips() {
        val h = Head(42, "a".repeat(64))
        assertEquals(h, Head.decode(h.encode()))
        assertEquals(null, Head.decode("junk")); assertEquals(null, Head.decode(null)); assertEquals(null, Head.decode("1:short"))
    }

    @Test fun onlyNonReadOrDecidedCallsAreRecorded() {
        assertFalse(Audit.shouldRecord(ActionClass.READ, Decision.AUTO))
        assertTrue(Audit.shouldRecord(ActionClass.READ, Decision.DENIED))
        for (c in ActionClass.entries.filter { it != ActionClass.READ }) assertTrue(Audit.shouldRecord(c, Decision.AUTO))
        assertEquals(Decision.EXPIRED, Decision.of("Expired")); assertEquals(Decision.AUTO, Decision.of(null))
    }

    @Test fun chainColumnRoundTrips() {
        assertEquals("manager>money>scout", Audit.chainText(listOf("manager", "money", "scout")))
        assertEquals(listOf("manager", "money"), Audit.chainOf("manager> money>"))
        assertEquals(emptyList<String>(), Audit.chainOf(""))
    }

    @Test fun filterPerBotAndTeamWide() {
        val l = log(10)
        assertEquals(10, Audit.filter(l, Audit.Filter()).size)
        assertEquals(5, Audit.filter(l, Audit.Filter(botId = "scout")).size)
        assertEquals(10, Audit.filter(l, Audit.Filter(botId = "manager")).size)  // in every row's chain
        assertEquals(listOf(5L, 10L), Audit.filter(l, Audit.Filter(decisions = setOf(Decision.DENIED))).map { it.id })
        assertEquals(listOf(7L), Audit.filter(l, Audit.Filter(query = "ROW 7")).map { it.id })
        assertEquals(3, Audit.filter(l, Audit.Filter(sinceMs = l[7].at)).size)
        assertEquals(0, Audit.filter(l, Audit.Filter(classes = setOf(ActionClass.SPEND))).size)
    }

    @Test fun csvExportEscapesAndDefusesFormulas() {
        val e = Audit.append(null, ev(1).copy(args = "=HYPERLINK(\"x\"), \"quoted\"\nline", result = "+1"))
        val csv = Audit.toCsv(listOf(e))
        val lines = csv.split("\r\n")
        assertEquals(Audit.CSV_HEADER.joinToString(","), lines[0])
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"x\"\"), \"\"quoted\"\" line\""))   // whitespace folded, quotes doubled, ' prefix
        assertTrue(csv.contains(",'+1,"))
        assertTrue(csv.contains(e.hash))
        assertEquals("a", Audit.csvCell("a")); assertEquals("\"a,b\"", Audit.csvCell("a,b")); assertEquals("", Audit.csvCell(null))
    }

    @Test fun jsonExportCarriesChainStatusAndEveryRow() {
        val l = log(3)
        val j = org.json.JSONObject(Audit.toJson(l))
        assertEquals("argus-activity.v1", j.getString("format"))
        assertEquals("ok", j.getString("chain"))
        val rows = j.getJSONArray("entries")
        assertEquals(3, rows.length())
        assertEquals(l[1].hash, rows.getJSONObject(1).getString("hash"))
        assertEquals(l[1].prevHash, rows.getJSONObject(1).getString("prev_hash"))
        assertEquals("manager", rows.getJSONObject(0).getJSONArray("chain").getString(0))
        assertTrue(rows.getJSONObject(1).isNull("undo"))
        val bad = l.toMutableList().apply { removeAt(1) }
        assertEquals("broken_link", org.json.JSONObject(Audit.toJson(bad)).getString("chain"))
    }
}
