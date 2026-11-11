package com.blaze.assistant.memory

import com.blaze.assistant.memory.MemMigration.ATeamMemory
import com.blaze.assistant.memory.MemMigration.Note
import com.blaze.assistant.memory.MemMigration.ProfileItem
import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.JdbcSql
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** P3-AG-02: every memory gets a scope; idempotent; no row lost (05 §9.2 migration). */
class MemMigrationTest {
    private lateinit var sql: JdbcSql
    private lateinit var db: BlazeDb
    private lateinit var store: MemScopeStore
    private val w = MemMigration.Writer { text, _ -> db.add(text).first }

    @Before fun setUp() { sql = JdbcSql(); db = BlazeDb(sql); store = MemScopeStore(sql) }
    @After fun tearDown() = sql.close()

    private fun ids() = db.memories().map { it.id }.toSet()

    private val demo = ATeamMemory(
        profile = listOf(
            ProfileItem("name", "Call me Finn"), ProfileItem("rule", "Never book red-eye flights"),
            ProfileItem("person", "Gloria is my sister", context = "lives in Ndola"), ProfileItem("place", "Time zone: Africa/Lusaka"),
            ProfileItem("preference", "Likes aisle seats", inferred = true), ProfileItem("goal", "Run a 10k in March"),
            ProfileItem("weird-kind", "Keeps receipts in a shoebox"), ProfileItem("note", "api_key = sk-abcdefghijklmnopqrst"),
            ProfileItem("note", "  "),
        ),
        bots = mapOf("nova7" to listOf(Note("Site lives in /root/shopping_website"), Note("Uses pnpm, not npm")),
            "manager" to listOf(Note("Finn reviews jobs on Friday evenings"))),
        team = listOf(Note("ARGUS v2.18 ships Friday"), Note("ghp_abcdefghijklmnopqrstuvwxyz123456")),
    )

    @Test fun existingLinesGetDefaultScopesOnceAndNothingIsLost() {
        val a = db.add("Finn lives in Lusaka").first; db.setPinned(a, true)
        db.add("Shared grocery list lives in Keep"); db.add("Prefers WhatsApp to SMS")
        val before = ids()
        val r1 = MemMigration.scopeExisting(store)
        assertEquals(3, r1.scoped); assertEquals(mapOf("team" to 2, "user:core" to 1), r1.byScope)
        assertEquals(before, ids())
        assertTrue(store.unscoped().isEmpty())
        assertTrue(store.lines().all { it.source == "migrated" })
        // idempotent: nothing left to do, and a line the user moved stays where it is
        store.move(a, "user:places")
        assertEquals(0, MemMigration.scopeExisting(store).scoped)
        assertEquals("user:places", store.line(a)!!.scope)
        assertEquals(before, ids())
    }

    @Test fun ateamProfileNotesAndTeamLandInTheirScopes() {
        val r = MemMigration.importATeam(demo, store, w, botIds = mapOf("nova7" to "nova"))
        assertEquals(demo.profile.size + 3 + demo.team.size, r.total)    // every input line counted once
        assertEquals(2, r.secrets); assertEquals(1, r.empty); assertEquals(r.added, db.countMemories())
        fun scopeOf(t: String) = store.lines().first { it.text.startsWith(t) }
        assertEquals("user:core", scopeOf("Call me Finn").scope)
        assertEquals("user:core", scopeOf("Never book red-eye").scope)
        assertEquals("user:people", scopeOf("Gloria is my sister").scope)
        assertEquals("Gloria is my sister (lives in Ndola)", scopeOf("Gloria").text)
        assertEquals("user:places", scopeOf("Time zone").scope)
        assertFalse(scopeOf("Likes aisle seats").stated); assertTrue(scopeOf("Run a 10k").stated)
        assertEquals("user:notes", scopeOf("Keeps receipts").scope)
        assertEquals("bot:nova", scopeOf("Site lives in").scope); assertEquals("nova", scopeOf("Uses pnpm").botId)
        assertEquals("bot:manager", scopeOf("Finn reviews jobs").scope)
        assertEquals("team", scopeOf("ARGUS v2.18").scope)
        assertTrue(store.lines().all { it.source == "ateam" })
        assertFalse(db.memories().any { it.text.contains("sk-") || it.text.contains("ghp_") })
    }

    @Test fun reImportIsIdempotent() {
        MemMigration.importATeam(demo, store, w)
        val n = db.countMemories(); val scopes = store.lines().associate { it.id to it.scope }
        val again = MemMigration.importATeam(demo, store, w)
        assertEquals(0, again.added); assertEquals(n, again.existed)
        assertEquals(n, db.countMemories())
        assertEquals(scopes, store.lines().associate { it.id to it.scope })
    }

    @Test fun anImportedLineThatAlreadyExistedKeepsAScopeItHadButFillsALegacyOne() {
        val kept = db.add("ARGUS v2.18 ships Friday").first; store.put(kept, "bot:atlas")
        val legacy = db.add("Uses pnpm, not npm").first
        val r = MemMigration.importATeam(demo, store, w, mapOf("nova7" to "nova"))
        assertEquals("bot:atlas", store.line(kept)!!.scope)
        assertEquals("bot:nova", store.line(legacy)!!.scope)
        assertEquals(2, r.existed)
        assertEquals(db.memories().size, ids().size)
    }

    @Test fun kindsMapAndTextsJoinContext() {
        assertEquals("profile", MemMigration.mindKind("name")); assertEquals("preference", MemMigration.mindKind("rule"))
        assertEquals("person", MemMigration.mindKind("person")); assertEquals("fact", MemMigration.mindKind("note"))
        assertEquals("Lives in Lusaka", MemMigration.profileText(ProfileItem("place", "Lives in  Lusaka", context = "Lusaka")))
        assertTrue(MemMigration.isSecret("card 4111 1111 1111 1111")); assertFalse(MemMigration.isSecret("Born in 2003"))
        // a bot id that isn't a valid scope is counted, never silently dropped
        val r = MemMigration.importATeam(ATeamMemory(bots = mapOf("bad id!" to listOf(Note("x y z")))), store, w)
        assertEquals(1, r.empty); assertEquals(1, r.total)
        assertEquals(BlazeDb.ADDED, db.add("fresh line after").second)
    }
}
