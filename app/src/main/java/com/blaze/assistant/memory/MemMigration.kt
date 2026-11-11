package com.blaze.assistant.memory

import com.blaze.assistant.store.BlazeDb

/**
 * P3-AG-02 (05 §9.2 "Migration"): gives every memory line its scope. Pure over [MemScopeStore] + a [Writer], so it runs
 * on the JVM (MemMigrationTest) and on the phone (Writer = Mind.save, which keeps mind_mem and the Memory screen in step).
 *
 *  1. [scopeExisting]: lines already in blaze.db with no mem_scope row → pinned ⇒ `user:core`, else `team`. Only rows
 *     that have none are written, so it is idempotent and never moves a line the user or a bot already scoped.
 *  2. [importATeam]: an A-team export (argus-team-export.v1 `memory` part, P3-MG-01): profile items → `user:<kind>`
 *     (name/style/rule → `user:core`) with stated = !inferred; bot private notes → `bot:<id>`; team notes → `team`.
 *     Lines that are already saved (same words) keep their row and scope; secrets are dropped and counted.
 *
 * "No row lost": neither step deletes anything; the report counts every input line exactly once.
 */
object MemMigration {
    /** Adds one memory line (dedupes like Mind.save / BlazeDb.add) and returns its id (0 = not saved). */
    fun interface Writer { fun add(text: String, kind: String): Long }

    data class Report(
        val scoped: Int = 0, val added: Int = 0, val existed: Int = 0, val secrets: Int = 0, val empty: Int = 0,
        val byScope: Map<String, Int> = emptyMap(),
    ) {
        val total get() = added + existed + secrets + empty
        operator fun plus(o: Report) = Report(scoped + o.scoped, added + o.added, existed + o.existed, secrets + o.secrets, empty + o.empty,
            (byScope.keys + o.byScope.keys).associateWith { (byScope[it] ?: 0) + (o.byScope[it] ?: 0) }.toSortedMap())
    }

    /** Step 1. Returns how many lines got a row. */
    fun scopeExisting(store: MemScopeStore): Report {
        var n = 0; val by = HashMap<String, Int>()
        for (id in store.unscoped()) {
            val l = store.line(id) ?: continue
            if (store.put(id, l.scope, kind = l.kind, stated = true, source = "migrated") is MemScopeStore.Put.Ok) { n++; by.merge(l.scope, 1, Int::plus) }
        }
        return Report(scoped = n, byScope = by.toSortedMap())
    }

    // ------------------------------------------------------------------------------------------------ A-team

    /** One A-team profile entry (manager.js pAdd): kind, text, optional context, inferred. */
    data class ProfileItem(val kind: String, val text: String, val context: String = "", val inferred: Boolean = false, val at: Long = 0)
    /** One A-team bot / team note (memory.js MEM.add). */
    data class Note(val text: String, val at: Long = 0, val src: String = "user")
    data class ATeamMemory(val profile: List<ProfileItem> = emptyList(), val bots: Map<String, List<Note>> = emptyMap(), val team: List<Note> = emptyList())

    /** A-team memory.js MEM.SECRET + manager.js SECRET: never imported. */
    private val secret = Regex("""(?i)\b(gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{16,}|xox[abp]-[A-Za-z0-9-]{10,}|EAA[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{20,}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+)\b|\b(password|passwd|api[_ -]?key|secret|token|pin)\s*[:=]\s*\S{4,}|\b(?:\d[ -]?){13,19}\b""")
    fun isSecret(s: String) = secret.containsMatchIn(s)

    /** The kind the A-team profile kind maps to in Mind's kinds (profile, preference, person, project, decision, fact). */
    fun mindKind(ateamKind: String): String = when (ateamKind.lowercase()) {
        "name", "style", "place", "date", "goal" -> "profile"
        "preference", "rule" -> "preference"
        "person" -> "person"
        "project" -> "project"
        else -> "fact"
    }

    /** A profile line as one memory sentence: "text (context)". */
    fun profileText(p: ProfileItem): String = BlazeDb.clean(p.text).let { t -> val c = BlazeDb.clean(p.context); if (c.isEmpty() || t.contains(c)) t else "$t ($c)" }

    /**
     * Step 2. [botIds] maps A-team agent ids to ARGUS bot ids (P1-MG-01 import keeps a map; "manager" → "manager");
     * an id not in the map keeps its A-team id so the notes are never lost (they show under that id in Settings).
     */
    fun importATeam(m: ATeamMemory, store: MemScopeStore, w: Writer, botIds: Map<String, String> = emptyMap()): Report {
        var r = Report()
        val known = store.lines().map { it.id }.toHashSet()
        for (p in m.profile) {
            val k = p.kind.lowercase().let { if (it in MemScopes.KIND_SCOPE) it else "note" }
            r += one(profileText(p), MemScopes.KIND_SCOPE.getValue(k), k, mindKind(k), !p.inferred, null, store, w, known)
        }
        for ((aid, notes) in m.bots) {
            val bid = botIds[aid] ?: if (aid == "manager") "manager" else aid
            val scope = MemScopes.BOT_PREFIX + bid
            if (MemScopes.parse(scope) == null) { r += Report(empty = notes.size); continue }
            for (n in notes) r += one(n.text, scope, null, "fact", true, bid, store, w, known)
        }
        for (n in m.team) r += one(n.text, MemScopes.TEAM, null, "fact", true, null, store, w, known)
        return r
    }

    private fun one(text0: String, scope: String, kind: String?, mindKind: String, stated: Boolean, botId: String?, store: MemScopeStore, w: Writer, known: MutableSet<Long>): Report {
        val text = BlazeDb.clean(text0)
        if (text.length < 3) return Report(empty = 1)
        if (isSecret(text)) return Report(secrets = 1)
        val id = w.add(text, mindKind)
        if (id <= 0) return Report(empty = 1)
        val existed = !known.add(id)
        val cur = store.line(id)
        // An existing line keeps the scope it has unless it never had one (legacy default).
        if (existed && cur != null && cur.source != "legacy") return Report(existed = 1)
        store.put(id, scope, botId, kind, stated, source = "ateam")
        return if (existed) Report(existed = 1, scoped = 1, byScope = mapOf(scope to 1)) else Report(added = 1, scoped = 1, byScope = mapOf(scope to 1))
    }
}
