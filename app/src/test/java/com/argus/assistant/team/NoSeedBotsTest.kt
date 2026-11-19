package com.argus.assistant.team

import com.argus.assistant.store.JdbcSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-17 (owner): users don't find Tempo and Inbox on start; ones an older build seeded and nobody used leave. */
class NoSeedBotsTest {
    private val sql = JdbcSql()
    private val store = TeamStore(sql) { 1_731_400_000_000L }

    init {
        sql.exec("PRAGMA foreign_keys = ON")
        sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
    }

    @After fun close() = sql.close()

    @Test fun aNewTeamIsArgusAlone() {
        val e = store.ensure(seedDomains = false)
        assertTrue(e.seededDomains.isEmpty())
        assertEquals(listOf("manager"), store.roster().map { it.id })
    }

    @Test fun unusedSeedsRetireUsedOnesStay() {
        store.ensure()                                   // an older build seeded Tempo and Inbox
        RowStore(sql) { 1_731_400_000_000L }.append("inbox", RowKind.MESSAGE, RowBodies.message(RowBodies.USER, "hi"))
        assertEquals(listOf("tempo"), store.retireUnusedSeeds())
        assertEquals(listOf("manager", "inbox"), store.roster().map { it.id })
        store.ensure(seedDomains = false)                // and nothing brings Tempo back
        assertEquals(listOf("manager", "inbox"), store.roster().map { it.id })
    }

    @Test fun theAppNoLongerSeeds() {
        val src = java.io.File(listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.isDirectory },
            "com/argus/assistant/team/TeamSql.kt").readText()
        assertTrue(src.contains("store.ensure(seedDomains = false)") && src.contains("retireUnusedSeeds()"))
    }
}
