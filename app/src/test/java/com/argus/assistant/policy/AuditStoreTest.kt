package com.argus.assistant.policy

import com.argus.assistant.agent.SecretTestKit
import com.argus.assistant.policy.Audit.Decision
import com.argus.assistant.store.JdbcSql
import com.argus.assistant.team.TeamSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** P2-SF-05: audit_log / spend_ledger rows in a real team.db (sqlite-jdbc): the chain survives the round trip; an edit in SQL breaks it. */
class AuditStoreTest {
    private val sql = JdbcSql().also { s -> s.tx { for (st in TeamSchema.steps(0)) s.exec(st) } }
    private val store = AuditStore(sql) { 1_000L }

    private fun draft(i: Int, d: Decision = Decision.ALLOW, cost: Long? = null) = Audit.Draft(at = 100L + i, botId = "b$i", chain = if (i == 1) listOf("lead", "b0") else emptyList(),
        trigger = "routine", tool = "send_email", actionClass = "SEND", args = """{"to":"x@y.z","key":"${SecretTestKit.GHP}"}""", decision = d,
        decidedBy = if (d == Decision.APPROVED) "user:allowed" else null, result = if (i == 2) null else "ok", costMinor = cost)

    @Test fun roundTripVerifiesAndSqlEditBreaksIt() {
        store.append(draft(0)); store.append(draft(1, Decision.APPROVED, 1250)); store.append(draft(2, Decision.DENIED)); store.append(draft(3))
        val rows = store.all()
        assertEquals(4, rows.size)
        assertEquals(-1, Audit.verify(rows))
        assertEquals(listOf("lead", "b0"), rows[1].chain)
        assertEquals(1250L, rows[1].costMinor); assertNull(rows[0].costMinor); assertNull(rows[2].result); assertNull(rows[0].decidedBy)
        assertFalse(rows.any { it.argsRedacted.contains(SecretTestKit.GHP) })
        sql.exec("UPDATE audit_log SET decision='APPROVED' WHERE id=3")
        assertEquals(2, Audit.verify(store.all()))
        sql.exec("UPDATE audit_log SET decision='DENIED' WHERE id=3")
        assertEquals(-1, Audit.verify(store.all()))
        sql.exec("DELETE FROM audit_log WHERE id=2")
        assertEquals(1, Audit.verify(store.all()))
    }

    @Test fun ledgerRowsOncePerApproval() {
        store.spend("b_shop", "ap1", 1250, "ZMW", "Zesco", 5L)
        store.spend("b_shop", "ap1", 1250, "ZMW", "Zesco", 5L)
        store.spend("b_shop", "ap2", 300, "ZMW", "", 6L)
        assertEquals(listOf(SpendGuard.Entry("b_shop", 1250, "ZMW", 5L), SpendGuard.Entry("b_shop", 300, "ZMW", 6L)), store.ledger().sortedBy { it.at })
    }
}
