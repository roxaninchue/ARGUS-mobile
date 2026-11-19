package com.argus.assistant.policy

import com.argus.assistant.agent.SecretTestKit
import com.argus.assistant.policy.Audit.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** P2-SF-05: the audit log's hash chain (tamper, delete, reorder are caught), args redaction, and the spend ledger month sum. */
class AuditChainTest {
    private fun chain(n: Int): List<Audit.Entry> {
        val out = ArrayList<Audit.Entry>()
        for (i in 0 until n) out += Audit.append(out.lastOrNull(), Audit.Draft(at = 1_000L + i, botId = if (i % 2 == 0) "argus" else "b_mail",
            chain = if (i == 3) listOf("b_lead") else emptyList(), trigger = "user", tool = "send_email", actionClass = "SEND",
            args = """{"to":"ann@example.com","n":$i}""", decision = if (i % 3 == 0) Decision.APPROVED else Decision.ALLOW,
            decidedBy = if (i % 3 == 0) "card" else null, result = "ok", costMinor = if (i == 2) 1250L else null))
        return out
    }

    @Test fun cleanChainVerifies() {
        val c = chain(6)
        assertEquals(-1, Audit.verify(c))
        assertEquals(Audit.GENESIS, c[0].prevHash)
        for (i in 1 until c.size) assertEquals(c[i - 1].hash, c[i].prevHash)
        assertEquals((1L..6L).toList(), c.map { it.seq })
        assertEquals(-1, Audit.verify(emptyList()))
    }

    @Test fun tamperedFieldIsCaughtAtThatRow() {
        val c = chain(6)
        assertEquals(2, Audit.verify(c.toMutableList().also { it[2] = it[2].copy(decision = Decision.DENIED) }))
        assertEquals(4, Audit.verify(c.toMutableList().also { it[4] = it[4].copy(argsRedacted = "{}") }))
        assertEquals(2, Audit.verify(c.toMutableList().also { it[2] = it[2].copy(costMinor = 1L) }))
        assertEquals(0, Audit.verify(c.toMutableList().also { it[0] = it[0].copy(at = 5L) }))
        assertEquals(3, Audit.verify(c.toMutableList().also { it[3] = it[3].copy(chain = emptyList()) }))
        // a re-hashed edit still breaks the next row's link
        val forged = c[1].copy(result = "error").let { it.copy(hash = Audit.hashOf(it)) }
        assertEquals(2, Audit.verify(c.toMutableList().also { it[1] = forged }))
    }

    @Test fun deletedRowIsCaught() {
        val c = chain(6)
        assertEquals(3, Audit.verify(c.filterIndexed { i, _ -> i != 3 }))
        assertEquals(0, Audit.verify(c.drop(1)))
    }

    @Test fun reorderedRowsAreCaught() {
        val c = chain(6).toMutableList()
        val t = c[2]; c[2] = c[3]; c[3] = t
        assertEquals(2, Audit.verify(c))
    }

    @Test fun canonicalIsUnambiguous() {
        val a = Audit.canonical(1, 2, "ab", emptyList(), "u", "t", "SEND", "c", Decision.ALLOW, null, null, null)
        val b = Audit.canonical(1, 2, "a", emptyList(), "u", "t", "SEND", "bc", Decision.ALLOW, null, null, null)
        assertNotEquals(a, b)
        assertNotEquals(Audit.canonical(1, 2, "a", emptyList(), "u", "t", "S", "", Decision.ALLOW, "", null, null),
            Audit.canonical(1, 2, "a", emptyList(), "u", "t", "S", "", Decision.ALLOW, null, null, null))
    }

    @Test fun argsAreRedactedBeforeHashing() {
        val gl = SecretTestKit.GL; val sk = SecretTestKit.SK; val ghp = SecretTestKit.GHP; val akia = SecretTestKit.AKIA; val jwt = SecretTestKit.JWT
        val args = """{"headers":"Authorization: Bearer $ghp","gl":"$gl","openai":"$sk","aws":"$akia","jwt":"$jwt"}"""
        val r = Audit.redact(args)
        for (s in listOf(gl, sk, ghp, akia, jwt)) assertFalse("leaked ${s.take(4)}", r.contains(s))
        assertTrue(r, r.contains("[GitHub token]") && r.contains("[GitLab token]"))
        val card = Audit.redact("""{"card":"${SecretTestKit.CARD}","note":"my password is hunter22x"}""")
        assertFalse(card.contains(SecretTestKit.CARD)); assertFalse(card.contains("hunter22x"))
        // saved-secret handles are already safe and stay readable
        assertEquals("""{"token":"{{secret:gitlab_token}}"}""", Audit.redact("""{"token":"{{secret:gitlab_token}}"}"""))
        // ordinary args are kept
        assertEquals("""{"to":"ann@example.com","subject":"Lunch"}""", Audit.redact("""{"to":"ann@example.com","subject":"Lunch"}"""))
        val e = Audit.append(null, Audit.Draft(at = 1, botId = "argus", tool = "http_request", actionClass = "WRITE_REMOTE", args = args, decision = Decision.ALLOW))
        assertFalse(e.argsRedacted.contains(ghp))
        assertEquals(-1, Audit.verify(listOf(e)))
        // a secret on the cut line is redacted whole before the cut
        val long = "x".repeat(Audit.ARGS_MAX - 10) + " " + ghp
        val cut = Audit.redact(long)
        assertTrue(cut.length <= Audit.ARGS_MAX); assertFalse(cut.contains(ghp.take(12)))
    }

    @Test fun amountsFromArgs() {
        assertEquals(Audit.Amount(1250, "ZMW", "Zesco"), Audit.amountOf("""{"amount":"12.50","currency":"zmw","merchant":"Zesco"}"""))
        assertEquals(Audit.Amount(999, null, "Shoprite"), Audit.amountOf("""{"amount_minor":999,"payee":"Shoprite"}"""))
        assertEquals(500L, Audit.amountOf("""{"amount":5}""")?.minor)
        assertNull(Audit.amountOf("""{"code":"*115#"}"""))
        assertNull(Audit.amountOf("""{"amount":"-3"}"""))
        assertNull(Audit.amountOf("not json"))
    }

    @Test fun ledgerMonthSum() {
        val zone = ZoneId.of("Africa/Lusaka")
        fun t(m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(2026, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
        val rows = listOf(
            SpendGuard.Entry("b_shop", 1250, "ZMW", t(11, 1, 0)),     // first minute of the month counts
            SpendGuard.Entry("b_shop", 3000, "ZMW", t(11, 17)),
            SpendGuard.Entry("b_shop", 700, "USD", t(11, 17)),        // other currency: not converted, not counted
            SpendGuard.Entry("b_shop", 9999, "ZMW", t(10, 31, 23)),   // last month
            SpendGuard.Entry("b_food", 400, "ZMW", t(11, 3)),
        )
        val now = t(11, 17)
        assertEquals(4250L, SpendGuard.spentThisMonth(rows, now, zone, "ZMW", "b_shop"))
        assertEquals(4650L, SpendGuard.spentThisMonth(rows, now, zone, "ZMW"))
        assertEquals(0L, SpendGuard.spentThisMonth(rows, t(12, 1), zone, "ZMW", "b_shop"))
    }

    @Test fun resultStatus() {
        assertEquals("not_run", Audit.resultOf("whatever", false))
        assertEquals("error", Audit.resultOf("Error: timed out", true))
        assertEquals("ok", Audit.resultOf("Sent.", true))
    }

    @Test fun activityGroupsFiltersAndExports() {
        val c = chain(6)   // even rows bot "argus", odd rows "b_mail"; rows 0 and 3 APPROVED
        val byBot = AuditView.group(c, AuditView.By.BOT, AuditView.Filter.ALL, { it.uppercase() }, { null })
        assertEquals(listOf("b_mail", "argus"), byBot.map { it.key })      // newest row (seq 6) is b_mail's
        assertEquals(listOf(6L, 4L, 2L), byBot[0].rows.map { it.seq })
        assertEquals("B_MAIL", byBot[0].title)
        val byTeam = AuditView.group(c, AuditView.By.TEAM, AuditView.Filter.ALL, { it }, { if (it == "b_mail") "t_ops" else null })
        assertEquals(setOf("t_ops", "argus"), byTeam.map { it.key }.toSet())
        val asked = AuditView.group(c, AuditView.By.BOT, AuditView.Filter.ASKED, { it }, { null }).flatMap { it.rows }.map { it.seq }.sorted()
        assertEquals(listOf(1L, 4L), asked)
        assertEquals("Chain verified ✓", AuditView.chainLine(c))
        assertEquals("Chain broken at #3", AuditView.chainLine(c.toMutableList().also { it[2] = it[2].copy(tool = "x") }))
        val csv = AuditView.csv(c).split("\r\n").filter { it.isNotEmpty() }
        assertEquals(7, csv.size)
        assertTrue(csv[1].contains("\"{\"\"to\"\":\"\"ann@example.com\"\",\"\"n\"\":0}\""))
        val arr = org.json.JSONArray(AuditView.json(c))
        assertEquals(6, arr.length()); assertEquals(c[5].hash, arr.getJSONObject(5).getString("hash"))
        assertTrue(arr.getJSONObject(0).isNull("cost_minor")); assertEquals(1250L, arr.getJSONObject(2).getLong("cost_minor"))
    }
}
