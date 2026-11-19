package com.argus.assistant.ui.team

import com.argus.assistant.store.JdbcSql
import com.argus.assistant.team.Avatar
import com.argus.assistant.team.Bot
import com.argus.assistant.team.BotKind
import com.argus.assistant.team.BotRules
import com.argus.assistant.team.BotThreads
import com.argus.assistant.team.TeamSchema
import com.argus.assistant.team.TeamStore
import com.argus.assistant.ui.argus.kit.PreviewTone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P1-TM-03: the Team screen's rules (TeamModel) on a real SQLite team.db. Ports A-team's home checks (ui.test.js rows /
 * header / quick sheet / pins, v215 canDelete): header line, Manager first then recency, pins with overdue dots, the
 * quick sheet (the Manager has no Delete; no Pin toggle since 2026-11-15), stored pins on the strip, job counts, empty state, Demo team.
 */
class TeamModelTest {
    private val sql = JdbcSql()
    private val zone = ZoneId.of("Africa/Lusaka")
    private val now = ZonedDateTime.of(2026, 11, 12, 11, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 11, 12)
    private var clock = now
    private val store = TeamStore(sql) { clock }

    init {
        sql.exec("PRAGMA foreign_keys = ON")
        sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
        store.ensure()
    }

    @After fun close() = sql.close()

    private fun bot(id: String, name: String, kind: BotKind = BotKind.CUSTOM, pinned: Boolean = false) = Bot(id = id, kind = kind, name = name,
        label = "Role of $name", avatar = Avatar("orb", "#22c55e"), soulPath = "", toolGroups = setOf("web"), pinned = pinned, createdAt = 0, u = 0)

    private fun add(b: Bot): Bot { clock++; return (store.insert(b) as TeamStore.Result.Ok).bot }

    private fun thread(b: Bot, last: Long?, unread: Int = 0, preview: String? = null, running: Boolean = false, waiting: Int = 0) =
        BotThreads.Thread(b.id, b.name, 0, null, unread, preview, last, b.pinned, running, waiting)

    private fun job(id: String, bot: String?, due: String?, status: String = "todo", run: String = "QUEUED") = sql.exec(
        "INSERT INTO jobs(id,title,bot_id,due,status,run,source,brief,created_at,u) VALUES(?,?,?,?,?,?,?,?,?,?)",
        id, "Job $id", bot, due, status, run, "user", "", now, now)

    @Test fun headerLine() {
        assertEquals(TeamHeader("My team", "Manager"), TeamModel.header(null, 0, JobCounts()))
        assertEquals("1 bot + Manager", TeamModel.header(" ", 1, JobCounts()).sub)
        assertEquals(TeamHeader("Finn's team", "9 bots + Manager · 4 jobs today · 1 overdue"), TeamModel.header("Finn's team", 9, JobCounts(today = 4, overdue = 1)))
        assertEquals("2 bots + Manager · 1 job today", TeamModel.header("x", 2, JobCounts(today = 1)).sub)
    }

    @Test fun managerFirstThenRecencyThenRosterOrder() {
        val nova = add(bot("nova", "Nova")); val atlas = add(bot("atlas", "Atlas")); val echo = add(bot("echo", "Echo"))
        val roster = store.roster()
        val threads = listOf(thread(atlas, now - 60_000, unread = 2, preview = "Booked"), thread(nova, null), thread(echo, now - 3_600_000))
        val s = TeamModel.state("T", roster, threads, JobCounts(), now, zone)
        assertEquals(BotRules.MANAGER_ID, s.manager!!.id)
        assertTrue(s.manager!!.manager)
        // Tempo and Inbox are seeded with no activity: they keep roster order after the active ones
        assertEquals(listOf("atlas", "echo", "tempo", "inbox", "nova"), s.rows.map { it.id })
        val a = s.rows.first()
        assertEquals("Booked", a.preview); assertEquals(2, a.unread); assertEquals("10:59", a.time)
        assertEquals("Role of Nova", s.rows.last().preview)   // no thread preview yet → the role
        assertEquals("5 bots + Manager", s.header.sub)
        assertFalse(s.empty)
        assertEquals(listOf("all"), s.chips.map { it.key })
    }

    @Test fun statusAndTimes() {
        val nova = add(bot("nova", "Nova"))
        assertEquals("Working", TeamModel.row(nova, thread(nova, null, running = true), JobCounts(), now, zone).status)
        assertEquals("Waiting for you", TeamModel.row(nova, thread(nova, null, waiting = 1), JobCounts(), now, zone).status)
        val day = 86_400_000L
        assertEquals("", TeamModel.time(null, now, zone))
        assertEquals("Tue", TeamModel.time(now - 2 * day, now, zone))      // 2026-11-10
        assertEquals("1 Nov", TeamModel.time(now - 11 * day, now, zone))
        assertEquals("12 Nov 2025", TeamModel.time(now - 365 * day, now, zone))
    }

    @Test fun pinsManagerFirstAtMostFourWithOverdueDot() {
        for (i in 1..5) add(bot("b$i", "Bot$i", pinned = true))
        val jobs = JobCounts(overdueByBot = mapOf("b2" to (1 to "Send deck")))
        val s = TeamModel.state(null, store.roster(), emptyList(), jobs, now, zone)
        assertEquals(listOf("manager", "b1", "b2", "b3"), s.pins.map { it.id })
        assertTrue(s.pins[0].manager)
        assertEquals(listOf(false, false, true, false), s.pins.map { it.overdue })
        val b2 = s.rows.first { it.id == "b2" }
        assertEquals("1 overdue · Send deck", b2.preview); assertEquals(PreviewTone.OVERDUE, b2.tone)
    }

    @Test fun quickSheetManagerHasNoDelete() {
        val m = store.get(BotRules.MANAGER_ID)!!
        assertEquals(listOf(QuickAction.OPEN, QuickAction.MEMORY, QuickAction.EDIT_SOUL), QuickAction.of(m))
        assertFalse(QuickAction.DELETE in QuickAction.of(m))
        val nova = add(bot("nova", "Nova"))
        assertEquals(listOf(QuickAction.OPEN, QuickAction.MEMORY, QuickAction.EDIT_SOUL, QuickAction.DELETE), QuickAction.of(nova))
        assertEquals(QuickAction.of(nova), QuickAction.of(nova.copy(pinned = true)))   // no Pin / Unpin (owner 2026-11-15)
        assertFalse(QuickAction.EDIT_SOUL in QuickAction.of(bot("room", "Room", BotKind.ROOM)))
        // and the store refuses it anyway
        assertTrue(TeamActions(store).delete(BotRules.MANAGER_ID) is TeamStore.Result.Refused)
        assertTrue(store.get(BotRules.MANAGER_ID) != null)
    }

    @Test fun aStoredPinStillReachesThePinStrip() {
        // Pin / Unpin are gone from the app (owner 2026-11-15); a bot pinned by the Demo team or an A-team import keeps its place.
        add(bot("nova", "Nova", pinned = true))
        assertEquals(listOf("manager", "nova"), TeamModel.state(null, TeamStore(sql).roster(), emptyList(), JobCounts(), now, zone).pins.map { it.id })
        assertTrue(store.get(BotRules.MANAGER_ID)!!.pinned)
    }

    @Test fun jobCounts() {
        add(bot("nova", "Nova")); add(bot("atlas", "Atlas"))
        job("1", "nova", "2026-11-12"); job("2", "nova", "2026-11-12T15:00:00+02:00"); job("3", "nova", "2026-11-10")
        job("4", "nova", "2026-11-09"); job("5", "atlas", "2026-11-11", status = "done"); job("6", "atlas", "2026-11-01", run = "CANCELLED")
        job("7", null, null); job("8", null, "2026-11-20"); job("9", "atlas", null)
        val j = JobCounts.read(sql, today)
        assertEquals(2, j.today); assertEquals(2, j.overdue); assertEquals(2, j.unassigned); assertEquals(7, j.open)
        assertEquals(mapOf("nova" to (2 to "Job 4")), j.overdueByBot)   // oldest due first
        val s = TeamModel.state(null, store.roster(), emptyList(), j, now, zone)
        assertEquals(2, s.unassigned); assertEquals(7, s.openJobs)
        assertEquals("4 bots + Manager · 2 jobs today · 2 overdue", s.header.sub)
        // a deleted bot's jobs move to Unassigned
        TeamActions(store).delete("nova")
        assertEquals(6, JobCounts.read(sql, today).unassigned)
    }

    @Test fun emptyStateAndDemoTeam() {
        // Tempo and Inbox are seeded; the Team screen is empty only once they're switched off
        assertFalse(TeamModel.state(null, store.roster(), emptyList(), JobCounts(), now, zone).empty)
        TeamActions(store).delete("tempo"); TeamActions(store).delete("inbox")
        val e = TeamModel.state(null, store.roster(), emptyList(), JobCounts(), now, zone)
        assertTrue(e.empty); assertEquals(emptyList<Any>(), e.rows); assertEquals("manager", e.manager!!.id)
        val added = TeamActions(store).addDemoTeam(now)
        assertEquals(listOf("Nova", "Atlas", "Echo", "Vega"), added.map { it.name })
        assertEquals(listOf("manager", "nova", "nova", "manager"), added.map { it.reportsTo })
        assertTrue(added.all { it.parentId == null })   // lead is reports-to: deleting Nova must not cascade
        assertEquals(1, added.last().avatar.face)
        assertEquals(emptyList<Bot>(), TeamActions(store).addDemoTeam(now))   // a second tap adds nothing
        TeamActions(store).delete("nova")
        assertEquals(listOf("atlas", "echo", "vega"), store.roster().filterNot(BotRules::isManager).map { it.id })
        assertFalse(TeamModel.state(null, store.roster(), emptyList(), JobCounts(), now, zone).empty)
    }

    @Test fun deleteCopy() {
        assertEquals("Nova leaves the team. Its open jobs move to Unassigned.", TeamCopy.deleteBody(bot("nova", "Nova")))
        assertTrue(TeamCopy.deleteBody(store.get("tempo")!!).startsWith("Tempo is switched off"))
    }
}
