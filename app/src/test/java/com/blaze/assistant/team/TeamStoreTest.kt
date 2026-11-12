package com.blaze.assistant.team

import com.blaze.assistant.agent.Specialists
import com.blaze.assistant.store.JdbcSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-TM-01: the roster on a real SQLite (sqlite-jdbc, team.db's own [TeamSchema]). Ports A-team's Manager cases
 * (app/tests/v215.test.js §2 and v216c.test.js §7, manager.js ensureState / insert / canDelete / the v2.16c lock):
 * seeded once with a fixed id, every other bot managed by it, edits put back on load, refused by the store, re-seeded
 * when an import drops it, a corrupt record repaired, an upgrade adopts old bots. Plus 05 §3.1: Tempo and Inbox seeded,
 * domain bots switched off rather than removed, and the built-in specialists as WORKER templates (delegate unchanged).
 */
class TeamStoreTest {
    private val sql = JdbcSql()
    private var now = 1_731_400_000_000L
    private val store = TeamStore(sql) { now }

    init {
        sql.exec("PRAGMA foreign_keys = ON")
        sql.tx { for (st in TeamSchema.steps(0)) sql.exec(st) }
    }

    @After fun close() = sql.close()

    private fun bot(id: String, name: String, kind: BotKind = BotKind.CUSTOM) = Bot(id = id, kind = kind, name = name, label = "",
        avatar = Avatar("orb", "#123456"), soulPath = "", toolGroups = setOf("web"), reportsTo = null, createdAt = 0, u = 0)

    private fun ok(r: TeamStore.Result): Bot = (r as? TeamStore.Result.Ok)?.bot ?: throw AssertionError("refused: $r")
    private fun refused(r: TeamStore.Result): String = (r as? TeamStore.Result.Refused)?.why ?: throw AssertionError("not refused: $r")
    private fun count(where: String = "1=1") = sql.query("SELECT count(*) FROM bots WHERE $where") { it.long(0) }.first()

    @Test fun managerSeededOnceWithFixedIdAndTempoInbox() {
        val e = store.ensure()
        assertTrue(e.seeded); assertEquals(listOf("tempo", "inbox"), e.seededDomains)
        val m = store.get("manager")!!
        assertEquals(BotKind.MANAGER, m.kind); assertEquals("Argus", m.name); assertEquals("Lead", m.label)
        assertEquals(Avatar("sphere", "#3466FF"), m.avatar); assertNull(m.reportsTo); assertTrue(m.pinned)
        assertEquals(1L, count("id='manager'")); assertEquals(1L, count("kind='MANAGER'"))
        assertEquals(listOf("manager", "tempo", "inbox"), store.roster().map { it.id })
        assertEquals(BotKind.DOMAIN, store.get("tempo")!!.kind); assertEquals("Time & calendar", store.get("tempo")!!.label)
        assertEquals("Comms", store.get("inbox")!!.label)
        assertTrue(store.soul("inbox")!!.contains("Never send"))
        // a second load changes nothing
        val again = store.ensure()
        assertFalse(again.toString(), again.changed); assertEquals(3L, count())
    }

    @Test fun everyOtherBotIsManagedByTheManager() {
        store.ensure()
        ok(store.insert(bot("", "Scout")))
        assertTrue(store.all().filterNot { it.id == "manager" }.all { it.reportsTo == "manager" })
    }

    @Test fun editsToTheManagerArePutBackOnLoad() {
        store.ensure()
        ok(store.update(store.get("manager")!!.copy(voice = Voice("nova", 1.2f))))     // voice is the user's to set
        sql.exec("UPDATE bots SET name='Chief', label='x', avatar=?, tool_groups='[\"terminal\"]', hidden=1 WHERE id='manager'",
            TeamJson.avatar(Avatar("orb", "#ff0000")))
        val e = store.ensure()
        assertTrue(e.repaired)
        val m = store.get("manager")!!
        assertEquals("Argus", m.name); assertEquals("Lead", m.label); assertEquals("#3466FF", m.avatar.color)
        assertEquals(BotRules.MANAGER_GROUPS, m.toolGroups); assertFalse(m.hidden)
        assertEquals(Voice("nova", 1.2f), m.voice)
        assertTrue(BotRules.lockedDiff(m).isEmpty())
    }

    @Test fun theStoreRefusesToChangeTheManager() {
        store.ensure()
        val m = store.get("manager")!!
        assertTrue(refused(store.update(m.copy(name = "Chief"))).contains("locked"))
        assertTrue(refused(store.update(m.copy(engine = EngineSpec(model = ModelPolicy(pinned = "x/y"))))).contains("engine"))
        assertTrue(refused(store.update(m.copy(avatar = Avatar("orb", "#000000")))).contains("avatar"))
        assertTrue(refused(store.saveSoul("manager", "be rude", "user")).contains("locked"))
        assertEquals("Argus", store.get("manager")!!.name)
        // allowed fields go through
        assertEquals(Autonomy.ASSIST, ok(store.update(m.copy(permissions = Permissions(autonomy = Autonomy.ASSIST)))).permissions.autonomy)
    }

    @Test fun theManagerCanNeverBeDeleted() {
        store.ensure()
        for (id in listOf("manager", " Manager ", "MANAGER")) {
            assertFalse(BotRules.canDelete(id))
            assertTrue(refused(store.delete(id)).contains("never be deleted"))
        }
        assertNotNull(store.get("manager"))
        assertTrue(BotRules.canDelete("tempo")); assertTrue(BotRules.canDelete("scout"))
    }

    @Test fun reSeededWhenAnImportDropsItAndCorruptRecordRepaired() {
        store.ensure()
        sql.exec("DELETE FROM bots WHERE id='manager'")
        assertTrue(store.ensure().seeded); assertEquals("Argus", store.get("manager")!!.name)
        // corrupt record: unknown kind and unreadable avatar
        sql.exec("UPDATE bots SET kind='7', avatar='{not json' WHERE id='manager'")
        assertNull(store.get("manager"))
        val e = store.ensure()
        assertTrue(e.repaired)
        val m = store.get("manager")!!
        assertEquals(BotKind.MANAGER, m.kind); assertEquals("Argus", m.name); assertEquals(1L, count("id='manager'"))
    }

    @Test fun upgradeAdoptsOldBotsAndDemotesASecondManager() {
        // an older store: bots without a manager, one claiming to be a manager, one named like it, no Argus
        for (b in listOf(bot("nova", "Nova"), bot("boss", "Boss", BotKind.MANAGER), bot("argus-old", "ARGUS"), bot("w", "Worker", BotKind.WORKER).copy(reportsTo = "gone")))
            sql.exec("INSERT INTO bots(id, kind, name, avatar, engine, computer, permissions, created_at, u) VALUES(?,?,?,?,?,?,?,1,1)",
                b.id, b.kind.name, b.name, TeamJson.avatar(b.avatar), TeamJson.engine(EngineSpec()), TeamJson.computer(ComputerSpec()), TeamJson.permissions(Permissions()))
        val e = store.ensure()
        assertTrue(e.seeded); assertEquals(listOf("boss"), e.demoted); assertEquals(listOf("argus-old"), e.renamed); assertEquals(4, e.adopted)
        assertEquals(BotKind.CUSTOM, store.get("boss")!!.kind)
        assertEquals("ARGUS 2", store.get("argus-old")!!.name)
        assertEquals(1L, count("kind='MANAGER'"))
        assertTrue(store.all().filterNot { it.id == "manager" }.all { it.reportsTo == "manager" })
    }

    @Test fun insertRefusesTheManagerAndDuplicateNames() {
        store.ensure()
        assertTrue(refused(store.insert(bot("manager", "Boss"))).contains("only one"))
        assertTrue(refused(store.insert(bot("x", "Boss", BotKind.MANAGER))).contains("only one"))
        assertTrue(refused(store.insert(bot("", "argus"))).contains("name"))
        assertTrue(refused(store.insert(bot("", "Manager"))).contains("name"))
        assertTrue(refused(store.insert(bot("", "tempo"))).contains("already called"))
        assertTrue(refused(store.insert(bot("", "  "))).contains("name"))
        val a = ok(store.insert(bot("", "Ledger Bot")))
        assertEquals("ledger-bot", a.id); assertEquals("manager", a.reportsTo); assertEquals("bots/ledger-bot/SOUL.md", a.soulPath)
        assertEquals("inbox-2", ok(store.insert(bot("inbox", "Mail sorter"))).id)
        assertTrue(refused(store.update(a.copy(name = "Inbox"))).contains("already called"))
    }

    @Test fun domainBotsSwitchOffAndAreNotReSeededCustomBotsGoAndTheirJobsAreUnassigned() {
        store.ensure()
        val off = ok(store.delete("tempo"))
        assertFalse(off.enabled); assertTrue(off.hidden)
        assertFalse(store.ensure().seededDomains.contains("tempo"))
        assertFalse(store.roster().any { it.id == "tempo" }); assertNotNull(store.get("tempo"))
        val s = ok(store.insert(bot("", "Scout")))
        sql.exec("INSERT INTO jobs(id, title, bot_id, source, brief, created_at, u) VALUES('j1','t',?,'user','b',1,1)", s.id)
        ok(store.delete(s.id))
        assertNull(store.get(s.id))
        assertEquals(listOf(""), sql.query("SELECT bot_id FROM jobs WHERE id='j1'") { it.text(0) })
    }

    @Test fun seedsDoNotOverwriteAUserBotWithTheSameName() {
        sql.exec("INSERT INTO bots(id, kind, name, avatar, engine, computer, permissions, created_at, u) VALUES('mytempo','CUSTOM','Tempo',?,?,?,?,1,1)",
            TeamJson.avatar(Avatar("orb", "#fff")), TeamJson.engine(EngineSpec()), TeamJson.computer(ComputerSpec()), TeamJson.permissions(Permissions()))
        assertEquals(listOf("inbox"), store.ensure().seededDomains)
        assertNull(store.get("tempo"))
    }

    @Test fun soulsAreVersioned() {
        store.ensure()
        assertEquals(2, ok(store.saveSoul("inbox", "v2 soul", "user")).soulVersion)
        assertEquals("v2 soul", store.soul("inbox"))
    }

    @Test fun appGrantsRoundTrip() {
        store.ensure()
        val a = ok(store.insert(bot("", "Mailer").copy(apps = listOf(BotAppGrant("gmail", "argus-connect", mapOf("send" to Rule.ASK))))))
        assertEquals(a.apps, store.get(a.id)!!.apps)
        assertEquals(a.copy(soulPath = a.soulPath), store.get(a.id))
    }

    @Test fun builtInSpecialistsAreWorkerTemplates() {
        val ws = store.workerTemplates()
        assertEquals(10, ws.size)
        assertEquals(Specialists.builtIn.map { it.id }, ws.map { it.id })
        for ((w, s) in ws.zip(Specialists.builtIn)) {
            assertEquals(BotKind.WORKER, w.kind); assertTrue(w.hidden); assertEquals("manager", w.reportsTo)
            assertEquals(s.groups, w.toolGroups); assertEquals(s.name, w.name)
        }
        // workers are not stored, so the roster stays Manager, Tempo, Inbox
        store.ensure(); assertEquals(3L, count())
    }

    @Test fun specBotFieldsRoundTripAndOldJsonStillLoads() {
        val s = Specialists.Spec("farm", "Farming advisor", "Advises on farming in Zambia with local prices and seasons.", setOf("web", "memory"), true,
            listOf("agri"), "## Playbook", Specialists.USER, soul = "You farm.", avatar = "orb", colour = "#2BB673", memoryScope = "farm",
            workspace = "farm", voice = "nova", managedBy = "tempo", modelPref = "x/y")
        assertEquals(listOf(s), Specialists.parse(Specialists.toJson(listOf(s))))
        // pre-0.53 JSON (no bot keys) loads with blank bot fields, and writes back without them
        val old = """[{"id":"farm","name":"Farming advisor","prompt":"p","groups":"web,memory","made_by":"user"}]"""
        val p = Specialists.parse(old).single()
        assertEquals("", p.soul); assertEquals("", p.managedBy); assertEquals("", p.modelPref)
        assertFalse(Specialists.toJson(listOf(p)).contains("managed_by"))
        val w = BotRules.workerTemplate(s)
        assertEquals("tempo", w.reportsTo); assertEquals("x/y", w.engine.model.pinned); assertEquals("#2BB673", w.avatar.color)
        assertTrue("farm" in w.permissions.dataScopes); assertEquals("nova", w.voice.id)
        // delegate still lists every built-in by id (its tool schema is built from Specialists, unchanged)
        assertTrue(Specialists.builtIn.map { it.id }.containsAll(listOf("researcher", "operator", "writer", "planner", "coder", "inbox", "shopper", "booker", "tutor", "designer")))
    }
}
