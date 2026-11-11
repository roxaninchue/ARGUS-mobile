package com.blaze.assistant.memory

import com.blaze.assistant.memory.MemScopes.Line
import com.blaze.assistant.memory.MemScopes.Viewer
import com.blaze.assistant.memory.MemScopes.Why
import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.JdbcSql
import com.blaze.assistant.store.Sql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** P3-AG-01: mem_scope rules (05 §4.2) and the side table on blaze.db (sqlite-jdbc, like BlazeDbTest). */
class MemScopeTest {
    private lateinit var dir: File
    private val open = ArrayList<Sql>()

    @Before fun setUp() { dir = Files.createTempDirectory("memscope").toFile() }
    @After fun tearDown() { open.forEach { try { it.close() } catch (_: Exception) { } }; dir.deleteRecursively() }

    private fun sql(name: String = "blaze.db"): JdbcSql = JdbcSql("jdbc:sqlite:" + File(dir, name).path).also { open += it }

    // roster: ARGUS leads all; Ledger (money, private) and Atlas (travel) report to ARGUS; Scout reports to Ledger.
    private val roster = listOf(
        Triple("manager", null, null), Triple("ledger", "money", "manager"), Triple("atlas", "travel", "manager"),
        Triple("scout", "shopping", "ledger"), Triple("pulse", "health", "manager"),
    )
    private val argus = MemScopes.viewer("manager", null, true, setOf("user:core", "team"), roster)
    private val ledger = MemScopes.viewer("ledger", "money", false, setOf("user:core", "team", "money"), roster)
    private val atlas = MemScopes.viewer("atlas", "travel", false, setOf("user:core", "team", "travel", "user:places"), roster)
    private val scout = MemScopes.viewer("scout", "shopping", false, setOf("user:core", "team", "shopping"), roster)

    private fun l(scope: String, id: Long = 1, sens: Int = 0, shares: Set<String> = emptySet(), expires: Long? = null) =
        Line(id, "x$id", scope, sensitivity = sens, shareTags = shares, expires = expires)

    // ---------------------------------------------------------------- parse

    @Test fun parsesEveryScopeKind() {
        assertTrue(MemScopes.parse("user:core") is MemScopes.Scope.UserCore)
        assertEquals("people", (MemScopes.parse("USER:people") as MemScopes.Scope.User).kind)
        assertTrue(MemScopes.parse(" team ") is MemScopes.Scope.Team)
        assertEquals("money", (MemScopes.parse("money") as MemScopes.Scope.Domain).name)
        assertEquals("atlas", (MemScopes.parse("bot:atlas") as MemScopes.Scope.BotNotes).botId)
        assertEquals("health:mind", MemScopes.parse("health:mind")!!.key)
        for (bad in listOf("", "  ", "user:", "user:*", "bot:", "share:travel", "two words", "user:a:b", null)) assertNull(bad, MemScopes.parse(bad))
    }

    @Test fun defaultScopeAndKindMap() {
        assertEquals("user:core", MemScopes.defaultScope(pinned = true))
        assertEquals("team", MemScopes.defaultScope(pinned = false))
        assertEquals("user:core", MemScopes.KIND_SCOPE["rule"])
        assertEquals("user:people", MemScopes.KIND_SCOPE["person"])
        assertEquals(10, MemScopes.KIND_SCOPE.size)
        assertEquals(setOf("share:travel", "share:bot:atlas"), MemScopes.cleanShares(listOf("travel", "share:bot:atlas", " Travel ", "team", "user:core", "")))
    }

    // ---------------------------------------------------------------- visibility table

    @Test fun visibilityTable() {
        data class C(val v: Viewer, val line: Line, val want: Why)
        val cases = listOf(
            C(atlas, l("user:core"), Why.CORE), C(scout, l("user:core"), Why.CORE),
            C(atlas, l("user:places"), Why.USER_KIND), C(scout, l("user:places"), Why.HIDDEN), C(argus, l("user:people"), Why.USER_KIND),
            C(scout, l("team"), Why.TEAM),
            C(atlas, l("travel"), Why.OWN_DOMAIN), C(argus, l("travel"), Why.LEAD), C(ledger, l("travel"), Why.HIDDEN),
            C(ledger, l("money"), Why.OWN_DOMAIN), C(argus, l("money"), Why.HIDDEN), C(atlas, l("money"), Why.HIDDEN),
            C(ledger, l("shopping"), Why.LEAD), C(argus, l("shopping"), Why.LEAD), C(atlas, l("shopping"), Why.HIDDEN),
            C(argus, l("health"), Why.HIDDEN), C(argus, l("health:mind"), Why.HIDDEN),
            C(atlas, l("bot:atlas"), Why.OWN_NOTES), C(argus, l("bot:atlas"), Why.LEAD), C(ledger, l("bot:atlas"), Why.HIDDEN),
            C(ledger, l("bot:scout"), Why.LEAD), C(atlas, l("bot:scout"), Why.HIDDEN),
            C(atlas, l("session"), Why.HIDDEN),
            // share tags open one line, not the scope
            C(atlas, l("money", shares = setOf("share:travel")), Why.SHARED), C(atlas, l("money"), Why.HIDDEN),
            C(atlas, l("bot:ledger", shares = setOf("share:bot:atlas")), Why.SHARED),
            // private lines: owner only (+ shares), even for the lead
            C(argus, l("bot:atlas", sens = 1), Why.HIDDEN), C(atlas, l("bot:atlas", sens = 1), Why.OWN_NOTES),
            C(argus, l("travel", sens = 1), Why.HIDDEN), C(argus, l("travel", sens = 1, shares = setOf("share:bot:manager")), Why.SHARED),
            // a secret row is never shown, an expired one neither
            C(atlas, l("user:core", sens = 2), Why.HIDDEN), C(atlas, l("team", expires = 100), Why.HIDDEN),
        )
        for ((i, c) in cases.withIndex()) assertEquals("case $i ${c.v.botId} reads ${c.line.scope}", c.want, MemScopes.why(c.v, c.line, now = 200))
        assertEquals(Why.TEAM, MemScopes.why(atlas, l("team", expires = 300), now = 200))
    }

    @Test fun managerSeesPrivateNotesExistWithoutReadingThem() {
        val lines = listOf(l("money", 1), l("money", 2), l("health", 3), l("team", 4), l("bot:atlas", 5, sens = 1))
        assertEquals(mapOf("bot:atlas" to 1, "health" to 1, "money" to 2), MemScopes.hiddenCounts(argus, lines))
    }

    @Test fun whoWritesWhere() {
        assertTrue(MemScopes.canWrite(atlas, "team")); assertTrue(MemScopes.canWrite(atlas, "bot:atlas")); assertTrue(MemScopes.canWrite(atlas, "travel"))
        assertFalse(MemScopes.canWrite(atlas, "bot:ledger")); assertFalse(MemScopes.canWrite(atlas, "money")); assertFalse(MemScopes.canWrite(atlas, "session"))
        assertFalse(MemScopes.canWrite(atlas, "user:core")); assertTrue(MemScopes.needsProposal(atlas, "user:core")); assertTrue(MemScopes.needsProposal(atlas, "user:people"))
        assertFalse(MemScopes.needsProposal(atlas, "money"))
        assertTrue(MemScopes.canWrite(argus, "user:core")); assertTrue(MemScopes.canWrite(argus, "travel")); assertFalse(MemScopes.canWrite(argus, "health"))
    }

    // ---------------------------------------------------------------- prompt blocks

    @Test fun coreBlockKeepsTheBudgetAndOnlyCore() {
        val many = (1..80L).map { Line(it, "Core fact number $it about Finn and his day", "user:core", created = it) } + Line(999, "team only", "team")
        val b = MemScopes.coreBlock(many)
        assertTrue(b.length <= MemScopes.CORE_CHARS + 60)
        assertTrue(b.startsWith("ABOUT THE USER"))
        assertFalse(b.contains("team only"))
        assertTrue(b.contains("Core fact number 1 "))
        assertEquals("", MemScopes.coreBlock(listOf(Line(1, "t", "team"))))
        assertTrue(MemScopes.coreBlock(listOf(Line(1, "Likes tea", "user:core", stated = false))).contains("Likes tea (inferred)"))
    }

    @Test fun relevantBlockBudgetsAndLabels() {
        val hits = (1..12L).map { Line(it, "Trip note $it for Cape Town", if (it % 2 == 0L) "travel" else "team") }
        val own = listOf(Line(50, "Prefers aisle seats", "bot:atlas", uses = 9), Line(51, "Uses Kiwi first", "bot:atlas", uses = 1))
        val b = MemScopes.relevantBlock(atlas, hits, own, core = "- Trip note 1 for Cape Town\n")
        val lines = b.lines().filter { it.startsWith("- #") }
        assertTrue(b.length <= MemScopes.RELEVANT_CHARS + 120)
        assertFalse("core lines are not repeated", lines.any { it.contains("Trip note 1 ") })
        assertTrue(lines.take(MemScopes.RELEVANT_LINES).all { it.contains("Trip note") })
        assertTrue(lines.size <= MemScopes.RELEVANT_LINES + MemScopes.OWN_NOTES_LINES)
        assertTrue(b.contains("[your notes] Prefers aisle seats"))
        assertTrue(b.indexOf("Prefers aisle") < b.indexOf("Uses Kiwi"))
        assertEquals("", MemScopes.relevantBlock(atlas, emptyList(), emptyList(), ""))
        assertTrue(MemScopes.relevantBlock(argus, listOf(Line(7, "Atlas books trains", "bot:atlas")), emptyList(), "", mapOf("atlas" to "Atlas")).contains("[from Atlas]"))
        val d = MemScopes.delegationBlock(listOf("Budget ZMW 18,000", " ", "Budget ZMW 18,000", "x".repeat(900)))
        assertEquals(1, d.lines().count { it.startsWith("- ") })
    }

    // ---------------------------------------------------------------- the side table

    private fun store(s: Sql = sql()): Pair<BlazeDb, MemScopeStore> { val b = BlazeDb(s); return b to MemScopeStore(s) }

    private fun master(s: Sql, like: String) =
        s.query("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE ? ORDER BY type, name", like) { "${it.text(0)}|${it.text(1)}|${it.text(2)}" }

    @Test fun desktopSchemaIsUntouched() {
        val plain = sql("plain.db"); BlazeDb(plain)
        val with = sql("with.db"); BlazeDb(with); MemScopeStore(with); MemScopeStore(with)   // twice: idempotent
        assertEquals("every Desktop table, index and trigger byte for byte", master(plain, "mem_scope%"), master(with, "mem_scope%"))
        assertEquals(1L, with.query("PRAGMA user_version") { it.long(0) }.first())
        assertEquals(listOf("index|mem_scope_scope", "table|mem_scope"),
            with.query("SELECT type, name FROM sqlite_master WHERE name LIKE 'mem_scope%' ORDER BY type, name") { "${it.text(0)}|${it.text(1)}" })
        // A Desktop build (BlazeDb only) still opens the file and reads its memories.
        val (b, sc) = store(sql("both.db")); val (id, _) = b.add("Finn lives in Lusaka"); sc.put(id, "user:core")
        assertEquals(1, BlazeDb(sql("both.db")).countMemories())
    }

    @Test fun putReadDefaultsAndRefusals() {
        val (b, sc) = store()
        val a = b.add("Finn lives in Lusaka").first
        val t = b.add("ARGUS v2.18 ships Friday").first
        val p = b.add("Pinned old fact").first; b.setPinned(p, true)
        assertEquals(MemScopeStore.Put.Ok, sc.put(a, "user:places", kind = "place", stated = true, source = "ateam"))
        assertEquals("user:places", sc.line(a)!!.scope)
        assertEquals("team", sc.line(t)!!.scope); assertEquals("legacy", sc.line(t)!!.source)
        assertEquals("user:core", sc.line(p)!!.scope)
        assertEquals(listOf(t, p), sc.unscoped())
        assertTrue(sc.put(t, "nonsense scope") is MemScopeStore.Put.No)
        assertTrue(sc.put(t, "session") is MemScopeStore.Put.No)
        assertTrue(sc.put(t, "team", sensitivity = 2) is MemScopeStore.Put.No)
        assertTrue(sc.put(4242, "team") is MemScopeStore.Put.No)
        // a private domain is private whatever the caller said
        val m = b.add("Netflix renews on the 3rd").first
        sc.put(m, "money", botId = "ledger"); assertEquals(1, sc.line(m)!!.sensitivity)
        // move keeps flags; moving out of a private domain clears the private mark
        sc.setShares(m, listOf("travel")); assertEquals(setOf("share:travel"), sc.line(m)!!.shareTags)
        sc.move(m, "team"); assertEquals(0, sc.line(m)!!.sensitivity); assertEquals(setOf("share:travel"), sc.line(m)!!.shareTags)
        sc.setStated(a, false); assertFalse(sc.line(a)!!.stated)
        assertEquals(mapOf("team" to 2, "user:core" to 1, "user:places" to 1), sc.counts())
    }

    @Test fun reusedIdNeverInheritsAPrivateScope() {
        val s = sql(); val (b, sc) = store(s)
        val id = b.add("Resting heart rate trending down").first
        sc.put(id, "health", botId = "pulse")
        // Desktop deletes it and writes another line that gets the same id, but a different created stamp.
        s.exec("DELETE FROM memories WHERE id=?", id)
        s.exec("INSERT INTO memories(id, text, norm, created, updated) VALUES(?,?,?,?,?)", id, "Shared grocery list lives in Keep", "shared grocery list lives in keep", 1, 1)
        assertEquals("team", sc.line(id)!!.scope)
        assertEquals(listOf(id), sc.unscoped())
        assertEquals(1, sc.prune()); assertEquals(0, MemScopeStore(s).prune())
        s.exec("DELETE FROM memories WHERE id=?", id)
        assertEquals(0, s.query("SELECT count(*) FROM mem_scope") { it.long(0) }.first().toInt() + sc.prune())
    }

    @Test fun searchReturnsOnlyWhatTheBotMaySee() {
        val (b, sc) = store()
        fun add(t: String, scope: String, bot: String? = null) = b.add(t).first.also { sc.put(it, scope, bot) }
        add("Cape Town trip budget is ZMW 18,000", "money", "ledger")
        add("Cape Town trip is 14 to 20 December", "team")
        add("Atlas prefers the Cape Town airport shuttle", "bot:atlas")
        add("Pulse: Cape Town hike needs knee care", "health", "pulse")
        val forAtlas = sc.search(atlas, "cape town trip").map { it.text }
        assertTrue(forAtlas.contains("Cape Town trip is 14 to 20 December"))
        assertTrue(forAtlas.contains("Atlas prefers the Cape Town airport shuttle"))
        assertFalse(forAtlas.any { it.contains("ZMW") || it.contains("knee") })
        val forLedger = sc.search(ledger, "cape town").map { it.text }
        assertTrue(forLedger.any { it.contains("ZMW") }); assertFalse(forLedger.any { it.contains("shuttle") || it.contains("knee") })
        // share one money line with travel: Atlas now reads it, and only it
        val shared = sc.lines().first { it.text.contains("ZMW") }.id
        sc.setShares(shared, listOf("travel"))
        assertTrue(sc.search(atlas, "budget").any { it.id == shared })
        val (core, rel) = sc.promptBlocks(atlas, "what's the cape town budget?", now = BlazeDb.now())
        assertEquals("", core)
        assertTrue(rel.contains("ZMW 18,000")); assertFalse(rel.contains("knee"))
        assertTrue(sc.search(atlas, "").size <= 8)
    }
}
