package com.blaze.assistant.connect

import com.blaze.assistant.memory.MemScopes
import com.blaze.assistant.team.Souls
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3-API-01: scoped memory + souls in the E2E sync; private scopes stay home unless "Sync private memory" is on. */
class ScopedSyncTest {
    private fun l(id: Long, text: String, scope: String, sens: Int = 0, source: String = "chat") =
        MemScopes.Line(id, text, scope, sensitivity = sens, source = source, updated = 100 + id)

    private class Src(var lines: MutableList<MemScopes.Line>, var priv: Boolean = false) : ScopeSource {
        val puts = ArrayList<String>()
        override fun lines() = lines
        override fun syncPrivate() = priv
        override fun put(text: String, scope: String, botId: String?, kind: String?, stated: Boolean, sensitivity: Int, shares: Set<String>, source: String, expires: Long?) {
            puts += "$text|$scope|$botId|$stated|$sensitivity|${shares.sorted()}"
        }
    }

    @Test fun privateScopesAreExcludedByDefault() {
        assertTrue(ScopedSync.syncable(l(1, "a", "team"), false))
        assertTrue(ScopedSync.syncable(l(1, "a", "user:core"), false))
        assertTrue(ScopedSync.syncable(l(1, "a", "travel"), false))
        assertFalse(ScopedSync.syncable(l(1, "a", "money"), false))
        assertFalse(ScopedSync.syncable(l(1, "a", "health:mind"), false))
        assertFalse(ScopedSync.syncable(l(1, "a", "bot:atlas", sens = 1), false))
        assertTrue(ScopedSync.syncable(l(1, "a", "money"), true))
        assertTrue(ScopedSync.syncable(l(1, "a", "bot:atlas", sens = 1), true))
        assertFalse("secrets never", ScopedSync.syncable(l(1, "a", "team", sens = 2), true))
        assertFalse(ScopedSync.syncable(l(1, "a", "session"), true))
    }

    @Test fun scopeItemsShareTheMemoryItemIdAndSkipLegacyAndPrivate() {
        val src = Src(mutableListOf(l(1, "Trip is 14-20 Dec", "team"), l(2, "Netflix on the 3rd", "money", sens = 1), l(3, "Old line", "team", source = "legacy")))
        val a = MemScopeAdapter(src)
        assertEquals(setOf(MemoryAdapter.idOf("Trip is 14-20 Dec")), a.stamps().keys)
        val it = a.read(MemoryAdapter.idOf("Trip is 14-20 Dec"))!!
        assertEquals("team", it.data!!.getString("scope")); assertEquals("mem_scope", it.kind)
        assertNull(a.read(MemoryAdapter.idOf("Netflix on the 3rd")))
        src.priv = true
        assertEquals(2, a.stamps().size)
        assertFalse(a.pushDeletes)
    }

    @Test fun applyingAScopeItemPutsTheScopeAndIgnoresJunk() {
        val src = Src(mutableListOf())
        val a = MemScopeAdapter(src)
        val text = "Atlas prefers aisle seats"
        a.apply(SyncMerge.Item("mem_scope", MemoryAdapter.idOf(text), 5, JSONObject().put("text", text).put("scope", "bot:atlas").put("bot", "atlas")
            .put("stated", false).put("sens", 1).put("shares", org.json.JSONArray(listOf("share:travel")))))
        assertEquals(listOf("$text|bot:atlas|atlas|false|1|[share:travel]"), src.puts)
        a.apply(SyncMerge.Item("mem_scope", "m-wrong-id", 5, JSONObject().put("text", text).put("scope", "team")))       // id doesn't match the words
        a.apply(SyncMerge.Item("mem_scope", MemoryAdapter.idOf(text), 5, JSONObject().put("text", text).put("scope", "not a scope")))
        a.apply(SyncMerge.Item("mem_scope", MemoryAdapter.idOf(text), 5, null))                                           // tombstones do nothing here
        a.apply(SyncMerge.Item("mem_scope", MemoryAdapter.idOf(text), 5, JSONObject().put("text", text).put("scope", "team").put("sens", 2)))
        assertEquals(2, src.puts.size); assertTrue(src.puts[1].endsWith("|team|null|true|1|[]"))   // a "secret" mark from the wire is clamped
    }

    private val ledger = Souls.Profile("money", "Ledger", "Money", "money", false)
    private fun soul(v: Int, rule: String? = null): String {
        var s = Souls.template(ledger).copy(version = v)
        if (rule != null) s = (Souls.addRule(s, rule) as Souls.Edit.Saved).soul.copy(version = v)
        return Souls.render(s)
    }

    @Test fun soulsSyncByBotAndNeverGoBackwards() {
        val files = HashMap<String, String>(); files["money"] = soul(3)
        val a = SoulAdapter({ files[it] }, { id, b -> files[id] = b }, { listOf("money", "atlas") }, { 1234L })
        assertEquals(setOf("money"), a.stamps().keys)
        val item = a.read("money")!!; assertEquals(3, item.data!!.getInt("version")); assertEquals(1234L, item.t)
        a.apply(SyncMerge.Item("soul", "money", 9, JSONObject().put("body", soul(2, "Older rule")).put("version", 2)))
        assertFalse(files["money"]!!.contains("Older rule"))
        a.apply(SyncMerge.Item("soul", "money", 9, JSONObject().put("body", soul(4, "Never suggest crypto")).put("version", 4)))
        assertTrue(files["money"]!!.contains("Never suggest crypto"))
        a.apply(SyncMerge.Item("soul", "ghost", 9, JSONObject().put("body", soul(1)).put("version", 1)))
        assertNull("a deleted bot's soul doesn't come back", files["ghost"])
        a.apply(SyncMerge.Item("soul", "atlas", 9, JSONObject().put("body", "no headings here, just text").put("version", 1)))
        assertNull(files["atlas"])
        a.apply(SyncMerge.Item("soul", "money", 9, null)); assertTrue(files.containsKey("money"))
    }

    @Test fun soulConflictsGoToTheHigherVersion() {
        fun it(v: Int, t: Long) = SyncMerge.Item("soul", "money", t, JSONObject().put("body", soul(v)).put("version", v))
        assertEquals(SyncMerge.Outcome.KeepLocal, SyncMerge.resolve(it(5, 10), it(4, 99)))
        assertEquals(SyncMerge.Outcome.TakeRemote, SyncMerge.resolve(it(5, 99), it(6, 10)))
        assertEquals(SyncMerge.Outcome.TakeRemote, SyncMerge.resolve(it(5, 10), it(5, 20)))   // same version: last writer
    }
}
