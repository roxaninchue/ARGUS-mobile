package com.blaze.assistant.team

import com.blaze.assistant.store.JdbcSql
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-MG-01: the A-team backup import on the Demo-team fixtures (app/src/test/resources/ateam, P0-MG-01) into a real
 * team.db (sqlite-jdbc, [TeamSchema]): bots (fields, look → orb face / colour, Gemini → a note), jobs (status, deps, due,
 * priority, confirmed = 0), nickname / theme; idempotent re-import (importing twice changes nothing, rows byte-equal);
 * last-writer-wins against edits made in ARGUS; a broken file imports nothing.
 */
class ATeamImportTest {
    private val sql = JdbcSql()
    private var now = 1_794_470_400_000L   // 2026-11-12, after the fixtures' stamps
    private val store = TeamStore(sql) { now }
    private val jobs = JobStore(sql, { now })
    private val prefs = object : ImportPrefs { override var userName = ""; override var themeMode = "system" }
    private val importer = ATeamImporter(sql, store, jobs, prefs)

    init {
        sql.exec("PRAGMA foreign_keys = ON")
        sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
        store.ensure()
    }

    @After fun close() = sql.close()

    private fun text(name: String) = javaClass.getResource("/ateam/$name")!!.readText()
    private fun file(text: String): ATeamImport.File = (ATeamImport.read(text) as? ATeamImport.Read.Ok)?.file
        ?: throw AssertionError("not read: ${ATeamImport.read(text)}")
    private val backup get() = file(text("demo-team.backup.json"))
    private val v1 get() = file(text("demo-team.export.v1.json"))

    private fun dump(): String = listOf("bots", "bot_soul_versions", "bot_apps", "jobs", "job_steps").joinToString("\n") { t ->
        sql.conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM $t ORDER BY 1, 2").use { rs ->
                val n = rs.metaData.columnCount
                buildString { while (rs.next()) { append(t).append(':'); for (i in 1..n) append(rs.getString(i)).append('|'); append('\n') } }
            }
        }
    } + "\nname=${prefs.userName} theme=${prefs.themeMode}"

    private fun bot(aTeam: String) = store.get(ATeamImport.id(aTeam)) ?: throw AssertionError("no bot $aTeam")
    private fun job(aTeam: String) = jobs.get(ATeamImport.id(aTeam)) ?: throw AssertionError("no job $aTeam")

    @Test fun backupBringsTheDemoTeamBots() {
        val r = importer.apply(backup)
        assertEquals(ATeamImport.Tally(added = 4), r.bots)
        val nova = bot("k3n9q2amhw1x")
        assertEquals("Nova", nova.name); assertEquals("Research lead", nova.label); assertEquals(BotKind.CUSTOM, nova.kind)
        assertEquals("Market intel, competitor tracking, Q4 strategy deck", nova.portfolio)
        assertEquals("", nova.email)                                   // §4.1: A-team's made-up emails are dropped
        assertEquals(Avatar("orb", "#22c55e", face = 2), nova.avatar)    // face from the name, A-team agentFace
        assertEquals(BotRules.MANAGER_ID, nova.reportsTo)
        assertEquals(1794466800100L, nova.u); assertEquals(1794466800000L, nova.createdAt)
        assertEquals(ATeamImport.id("k3n9q2amhw1x"), bot("p7d2r8bmhw1y").reportsTo)   // Atlas and Echo report to Nova
        assertEquals(ATeamImport.id("k3n9q2amhw1x"), bot("w4c6t1cmhw1z").reportsTo)
        val vega = bot("z8f5h3dmhw20")
        assertEquals(Avatar("orb", "#06b6d4", face = 1), vega.avatar)                  // Tilt, as set in A-team
        val soul = store.soul(vega.id)!!
        assertTrue(soul, soul.contains("Gemini (gemini-2.5-flash)"))                   // a note, not a pin
        assertNull(vega.engine.model.pinned)
        assertNull(store.soul(nova.id))
        // the Manager: still the one ARGUS Manager, untouched
        assertEquals(1, store.all().count { it.kind == BotKind.MANAGER })
        assertEquals(emptyList<String>(), BotRules.lockedDiff(store.get(BotRules.MANAGER_ID)!!))
        assertEquals(listOf("Vega" to "gemini-2.5-flash"), r.gemini)
        // a backup has no settings
        assertNull(r.nickname); assertNull(r.theme); assertEquals("", prefs.userName); assertEquals("system", prefs.themeMode)
    }

    @Test fun backupBringsTheDemoTeamJobs() {
        val r = importer.apply(backup)
        assertEquals(ATeamImport.Tally(added = 9), r.jobs)
        assertEquals(8, r.toConfirm)
        val scan = job("t1a2b3cmhw30")
        assertEquals("Competitor pricing scan", scan.title); assertEquals(ATeamImport.id("k3n9q2amhw1x"), scan.botId)
        assertEquals("2026-11-12", scan.due); assertEquals("high", scan.priority)
        assertEquals(JobRun.QUEUED, scan.run); assertEquals("todo", scan.status); assertFalse(scan.confirmed)
        assertEquals("a-team", scan.source); assertEquals("Competitor pricing scan", scan.brief)
        assertEquals(listOf("Imported from A-team"), jobs.steps(scan.id).map { it.label })
        val deck = job("t2b3c4dmhw31")
        assertEquals(listOf(ATeamImport.id("t1a2b3cmhw30")), deck.deps); assertEquals("blocked", deck.status)
        val done = job("t9i0j1kmhw38")
        assertEquals(JobRun.DONE, done.run); assertEquals("done", done.status); assertEquals(1794466800019L, done.doneAt); assertTrue(done.confirmed)
        assertNull(job("t6f7g8hmhw35").due); assertEquals("low", job("t6f7g8hmhw35").priority)
        // imported work never starts by itself
        assertTrue(jobs.open().none { it.confirmed })
        val lines = ATeamImport.lines(r)
        assertTrue(lines.toString(), lines.contains("4 bots: 4 new"))
        assertTrue(lines.toString(), lines.any { it.startsWith("9 jobs: 9 new. 8 open jobs wait for your OK") })
        assertTrue(lines.toString(), lines.any { it.startsWith("Not imported yet: the Manager's chat (3 messages)") })
    }

    @Test fun importingTwiceChangesNothing() {
        importer.apply(backup)
        val first = dump()
        now += 3_600_000
        val again = importer.apply(backup)
        assertEquals(first, dump())
        assertEquals(0, again.changes)
        assertEquals(ATeamImport.Tally(same = 4), again.bots); assertEquals(ATeamImport.Tally(same = 9), again.jobs)
        assertEquals("Nothing changed: this backup was already imported.", ATeamImport.lines(again).first())
        // the v1 file of the same team, twice
        val x = importer.apply(v1)
        val afterV1 = dump()
        now += 3_600_000
        assertEquals(0, importer.apply(v1).changes)
        assertEquals(afterV1, dump())
        assertTrue(x.changes > 0)   // pins, name and theme are new in v1
    }

    @Test fun v1SetsNicknameThemeAndPins() {
        val p = importer.preview(v1)
        assertFalse(p.applied); assertEquals("", prefs.userName); assertEquals(3, store.all().size)   // preview writes nothing
        val r = importer.apply(v1)
        assertEquals("Finn", prefs.userName); assertEquals("dark", prefs.themeMode)
        assertEquals("Finn", r.nickname); assertEquals("dark", r.theme)
        assertTrue(bot("k3n9q2amhw1x").pinned); assertTrue(bot("z8f5h3dmhw20").pinned); assertFalse(bot("p7d2r8bmhw1y").pinned)
        assertEquals(Avatar("orb", "#06b6d4", face = 1), bot("z8f5h3dmhw20").avatar)
        assertEquals(9, jobs.all().size)
        val lines = ATeamImport.lines(r)
        assertTrue(lines.toString(), lines.contains("Your name: Finn") && lines.contains("Theme: Dark"))
        assertTrue(lines.toString(), lines.any { it.startsWith("Not imported yet:") && it.contains("7 profile lines") && it.contains("4 routines") })
    }

    @Test fun nameAndThemeTheUserSetAreKept() {
        prefs.userName = "F"; prefs.themeMode = "light"
        val r = importer.apply(v1, useTheme = false)
        assertEquals("F", prefs.userName); assertEquals("light", prefs.themeMode)
        assertNull(r.nickname); assertNull(r.theme)
    }

    @Test fun editsInArgusWinOverAnOlderFileAndANewerFileUpdates() {
        importer.apply(backup)
        now += 60_000
        val nova = bot("k3n9q2amhw1x")
        store.update(nova.copy(label = "Head of research"))                 // stamps u = now, newer than the file
        val r = importer.apply(backup)
        assertEquals(ATeamImport.Tally(same = 3, kept = 1), r.bots)
        assertEquals("Head of research", bot("k3n9q2amhw1x").label)
        // a newer backup (A-team edited Atlas and finished the pricing scan) updates those two rows only
        val o = JSONObject(text("demo-team.backup.json"))
        o.getJSONArray("agents").getJSONObject(1).put("role", "Sales lead").put("u", now + 1)
        o.getJSONArray("tasks").getJSONObject(0).put("status", "done").put("doneAt", now).put("u", now + 1)
        val r2 = importer.apply(file(o.toString()))
        assertEquals(ATeamImport.Tally(same = 2, kept = 1, updated = 1), r2.bots)
        assertEquals(ATeamImport.Tally(same = 8, updated = 1), r2.jobs)
        assertEquals("Sales lead", bot("p7d2r8bmhw1y").label)
        assertEquals(JobRun.DONE, job("t1a2b3cmhw30").run)
        assertEquals(listOf("Imported from A-team"), jobs.steps(ATeamImport.id("t1a2b3cmhw30")).map { it.label })
    }

    @Test fun aTakenNameGetsANumberOnce() {
        store.insert(Bot(id = "", kind = BotKind.CUSTOM, name = "Nova", label = "", avatar = Avatar("orb", "#22c55e"), soulPath = "",
            toolGroups = setOf("web"), createdAt = 0, u = 0))
        val r = importer.apply(backup)
        assertEquals(listOf("Nova" to "Nova 2"), r.renamed)
        assertEquals("Nova 2", bot("k3n9q2amhw1x").name)
        assertTrue(ATeamImport.lines(r).contains("Nova is called Nova 2 here: the name was taken."))
        val before = dump()
        assertEquals(0, importer.apply(backup).changes)
        assertEquals(before, dump())
        assertEquals("Argus 2", ATeamImport.uniqueName("Argus", emptySet()))
    }

    @Test fun aBrokenFileImportsNothing() {
        val before = dump()
        val o = JSONObject(text("demo-team.backup.json"))
        o.getJSONArray("tasks").getJSONObject(0).put("agentId", "ghost")
        val bad = ATeamImport.read(o.toString())
        assertTrue(bad is ATeamImport.Read.Bad)
        assertTrue((bad as ATeamImport.Read.Bad).problems.any { it.contains("bot ghost") })
        assertEquals(listOf("That isn't an A-team backup: it isn't JSON."), (ATeamImport.read("hello") as ATeamImport.Read.Bad).problems)
        assertEquals(listOf("not an A-team backup or argus-team-export v1"), (ATeamImport.read("{\"x\":1}") as ATeamImport.Read.Bad).problems)
        assertEquals(before, dump())
    }

    @Test fun mappingHelpers() {
        assertEquals("phone", ATeamImport.themeOf("system")); assertEquals("light", ATeamImport.themeOf("Light")); assertNull(ATeamImport.themeOf("sepia"))
        assertNull(ATeamImport.soulOf(null, "  ", null))
        assertEquals("Be brief.\n\nNotes: Likes tables", ATeamImport.soulOf("Be brief.", "Likes tables", null))
        assertEquals(ATeamImport.Fate.KEPT, ATeamImport.fate(10, 5)); assertEquals(ATeamImport.Fate.ADDED, ATeamImport.fate(null, 5))
        assertEquals(JobRun.FAILED, ATeamImport.runOf(JSONObject("""{"status":"todo","run":"failed"}""")))
        assertEquals(JobRun.QUEUED, ATeamImport.runOf(JSONObject("""{"status":"todo","run":"running"}""")))
        assertNotNull(backup.bots.firstOrNull { it.gemini != null })
    }
}
