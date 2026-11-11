package com.blaze.assistant.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-TM-01: ports A-team's Manager cases (app/tests/v215.test.js §2 "the Manager", manager.js ensureState / insert /
 * canDelete / locked v2.16c) to ARGUS. Pure JVM: MemoryTeamPort, fixed clock.
 */
class TeamStoreTest {
    private var now = 1_000_000L
    private fun store(port: TeamPort = MemoryTeamPort()) = TeamStore(port) { now }
    private fun custom(id: String, name: String, reportsTo: String? = Bots.MANAGER_ID) = Bot(id = id, kind = BotKind.CUSTOM, name = name, label = "",
        avatar = Bots.avatar(0, Bots.COLORS[0]), soulPath = Bots.soulPath(id), toolGroups = setOf("web"), reportsTo = reportsTo, createdAt = 1, u = 1)

    @Test fun firstRunSeedsArgusTempoAndInboxOnceWithAFixedId() {
        val port = MemoryTeamPort(); val s = store(port)
        val e = s.ensure()
        assertTrue(e.seeded); assertEquals(listOf("tempo", "inbox"), e.seededDomains)
        val all = s.all()
        assertEquals(listOf("manager", "tempo", "inbox"), all.map { it.id })
        val m = all.first()
        assertEquals("ARGUS", m.name); assertEquals("Manager", m.label); assertEquals(BotKind.MANAGER, m.kind)
        assertNull("the Manager reports to nobody", m.reportsTo)
        assertEquals(Bots.MANAGER_COLOR, m.avatar.color)
        assertEquals(1, port.bots().count { it.id == "manager" })
        // a second ensure changes nothing
        assertFalse(s.ensure().changed)
        assertEquals(3, port.bots().size)
    }

    @Test fun tempoAndInboxAreLabelledDomainsReportingToTheManager() {
        val s = store(); s.ensure()
        val t = s.get("tempo")!!; val i = s.get("inbox")!!
        assertEquals("Tempo \u00b7 Time & calendar", Bots.display(t)); assertEquals("Inbox \u00b7 Comms", Bots.display(i))
        assertEquals(BotKind.DOMAIN, t.kind); assertEquals("manager", t.reportsTo); assertEquals("manager", i.reportsTo)
        assertTrue(t.routingHints.contains("calendar")); assertTrue(i.routingHints.contains("email"))
    }

    @Test fun deletedSeedBotsAreNotBroughtBack() {
        val s = store(); s.ensure()
        assertTrue(s.delete("tempo") is TeamStore.Result.Ok)
        s.ensure()
        assertNull(s.get("tempo"))
    }

    @Test fun theManagerCanNeverBeDeleted() {
        val port = MemoryTeamPort(); val s = store(port); s.ensure()
        val r = s.delete("manager")
        assertTrue(r is TeamStore.Result.No); assertEquals(TeamStore.NEVER_DELETE, (r as TeamStore.Result.No).why)
        assertTrue(s.delete("MANAGER") is TeamStore.Result.No)
        assertNotNull(s.get("manager"))
        assertFalse(Bots.canDelete("manager", s.all()))
        assertTrue(Bots.canDelete("inbox", s.all()))
    }

    @Test fun missingManagerIsReseededOnTheNextRead() {
        val port = MemoryTeamPort(); val s = store(port); s.ensure()
        port.deleteBot("manager")   // an import / restore without it
        val all = s.all()
        assertEquals("manager", all.first().id); assertEquals("ARGUS", all.first().name)
    }

    @Test fun editsToTheManagerArePutBackAndRefused() {
        val port = MemoryTeamPort(); val s = store(port); s.ensure()
        // written behind the store's back (old build, backup, sync)
        port.putBot(port.bots().first { it.id == "manager" }.copy(name = "Chief", label = "x", reportsTo = "inbox", toolGroups = setOf("web")))
        val e = s.ensure()
        assertTrue(e.repaired)
        val m = s.manager()
        assertEquals("ARGUS", m.name); assertEquals("Manager", m.label); assertNull(m.reportsTo); assertEquals(Bots.MANAGER_GROUPS, m.toolGroups)
        // through the store: refused
        val r = s.update("manager") { it.copy(name = "Boss") }
        assertTrue(r is TeamStore.Result.No); assertEquals("ARGUS", s.manager().name)
        // pinning is allowed
        assertTrue(s.setPinned("manager", true) is TeamStore.Result.Ok); assertTrue(s.manager().pinned)
    }

    @Test fun corruptRecordsAreDroppedOrRepaired() {
        val bad = listOf(null, custom("", "x"), custom("a", "Ann"), custom("a", "Ann dup"),
            custom("manager", "").copy(kind = BotKind.CUSTOM))
        val e = Bots.ensure(bad, now, firstRun = false)
        assertTrue(e.repaired)
        assertEquals(listOf("manager", "a"), e.bots.map { it.id })
        assertEquals("ARGUS", e.bots.first().name)
    }

    @Test fun upgradeAddsTheManagerAndAdoptsExistingBots() {
        val port = MemoryTeamPort()
        port.putBot(custom("nova", "Nova", reportsTo = null)); port.putBot(custom("atlas", "Atlas", reportsTo = null))
        port.setFlag(TeamStore.FLAG_SEEDED, "1")   // an upgrade, not a first run: no Tempo/Inbox
        val e = store(port).ensure()
        assertTrue(e.seeded); assertEquals(2, e.adopted); assertTrue(e.seededDomains.isEmpty())
        assertTrue(port.bots().filter { it.id != "manager" }.all { it.reportsTo == "manager" })
    }

    @Test fun aSecondManagerKindAndTheReservedNamesAreRepaired() {
        val e = Bots.ensure(listOf(custom("x", "ARGUS"), custom("y", "Boss").copy(kind = BotKind.MANAGER)), now)
        val x = e.bots.first { it.id == "x" }; val y = e.bots.first { it.id == "y" }
        assertEquals("ARGUS 2", x.name)
        assertEquals(BotKind.CUSTOM, y.kind)
        assertEquals(1, e.bots.count { it.kind == BotKind.MANAGER })
    }

    @Test fun createIsManagedByTheManagerWithUniqueNameAndId() {
        val s = store(); s.ensure()
        val r = s.create(custom("", "Ledger", reportsTo = null)) as TeamStore.Result.Ok
        assertEquals("ledger", r.bot.id); assertEquals("manager", r.bot.reportsTo); assertEquals("bots/ledger/SOUL.md", r.bot.soulPath)
        assertTrue(s.create(custom("", "ledger")) is TeamStore.Result.No)
        assertTrue(s.create(custom("", "Argus")) is TeamStore.Result.No)
        assertTrue(s.create(custom("", "Boss").copy(kind = BotKind.MANAGER)) is TeamStore.Result.No)
        val r2 = s.create(custom("manager", "Second")) as TeamStore.Result.Ok
        assertEquals("second", r2.bot.id)
        assertEquals("manager", s.all().first().id)
    }

    @Test fun findMatchesIdNameFirstNameAndPrefixButNeverHiddenWorkers() {
        val s = store(); s.ensure()
        s.create(custom("", "Atlas Travel"))
        val all = s.all(hidden = true) + Bots.worker("researcher", "Researcher", setOf("web"), emptyList(), now)
        assertEquals("tempo", Bots.find(all, "tempo")?.id)
        assertEquals("inbox", Bots.find(all, "@Inbox")?.id)
        assertEquals("atlas-travel", Bots.find(all, "atlas")?.id)
        assertEquals("atlas-travel", Bots.find(all, "Atl")?.id)
        assertEquals("manager", Bots.find(all, "argus")?.id)
        assertEquals("manager", Bots.find(all, "Manager")?.id)
        assertNull(Bots.find(all, "Researcher"))
        assertNull(Bots.find(all, "at"))
    }

    @Test fun deleteTakesChildrenAndTellsListeners() {
        val port = MemoryTeamPort(); val s = store(port); s.ensure()
        val gone = ArrayList<String>(); s.onDeleted { gone += it.id }
        port.putBot(Bots.worker("coder", "Coder", setOf("terminal"), emptyList(), now).copy(id = "sub1", kind = BotKind.SUBAGENT, parentId = "inbox"))
        assertTrue(s.delete("inbox") is TeamStore.Result.Ok)
        assertNull(port.bots().firstOrNull { it.id == "sub1" })
        assertEquals(listOf("inbox"), gone)
    }

    @Test fun workerTemplatesAreHiddenWorkers() {
        val w = Bots.worker("shopper", "Shopper", setOf("web", "browser"), listOf("shopping"), now)
        assertEquals(BotKind.WORKER, w.kind); assertTrue(w.hidden); assertEquals("worker:shopper", w.id)
        assertEquals("manager", w.reportsTo)
    }

    @Test fun stampIsMonotonic() {
        val s = store(); val a = s.stamp(); val b = s.stamp()
        assertTrue(b > a)
    }
}
