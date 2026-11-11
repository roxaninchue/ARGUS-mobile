package com.blaze.assistant.connect

import com.blaze.assistant.memory.MemScopes
import com.blaze.assistant.team.Souls
import org.json.JSONObject

/**
 * P3-API-01 (05 §4.6, 06 R12): scoped memory and souls in the end-to-end encrypted Blaze account sync (CloudSync +
 * SyncCrypto), never through argus-agents-api. Pure adapters (SyncMergeTest / ScopedSyncTest) wired in CloudSync.
 *
 *  - Memory lines keep syncing through [MemoryAdapter] (one item per line, id = hash of its words). [syncable] now
 *    filters what that adapter offers: **private lines** (sensitivity 1, and every line in a private domain — health,
 *    money, admin, health:mind) sync only when the user turns on "Sync private memory" (off by default); secret-refused
 *    rows and `session` never sync.
 *  - [MemScopeAdapter] (kind `mem_scope`) carries each syncable line's scope row under the **same id** as its memory
 *    item, so a line arrives on the other phone with its owner scope, bot, stated flag and share tags. If the line itself
 *    hasn't arrived yet, the scope item brings its text and the line is written with it.
 *  - [SoulAdapter] (kind `soul`, one item per bot id) carries SOUL.md. Merge: the higher version wins, then last writer
 *    (SyncMerge.resolve); a locked Manager core is put back by SoulStore whatever arrives.
 * Both new kinds follow the Memory switch in Settings › Sync (CloudSync.group); SyncKinds.ALL is unchanged.
 */
object ScopedSync {
    const val MEM_SCOPE = "mem_scope"
    const val SOUL = "soul"
    /** Settings key (this phone only, never synced itself): "Sync private memory". Default off. */
    const val PRIVATE_PREF = "sync_private_memory"

    /** Whether a line may leave this phone. */
    fun syncable(l: MemScopes.Line, syncPrivate: Boolean): Boolean {
        if (l.sensitivity >= MemScopes.SENSITIVE_SECRET) return false
        if (MemScopes.parse(l.scope) is MemScopes.Scope.Session) return false
        val private = l.sensitivity >= MemScopes.SENSITIVE_PRIVATE || MemScopes.isPrivateDomain(l.scope)
        return syncPrivate || !private
    }

    fun scopeData(l: MemScopes.Line): JSONObject = JSONObject().put("text", l.text).put("scope", l.scope)
        .put("bot", l.botId ?: "").put("kind", l.kind ?: "").put("stated", l.stated).put("sens", l.sensitivity)
        .put("shares", org.json.JSONArray(l.shareTags.sorted())).put("source", l.source)
        .apply { l.expires?.let { put("expires", it) } }
}

/** Where scope rows live on this phone (MemScopeStore + Mind on the phone; a fake in tests). */
interface ScopeSource {
    fun lines(): List<MemScopes.Line>
    fun syncPrivate(): Boolean
    /** Sets the scope of the line with [text] (adding the line when it isn't here yet). */
    fun put(text: String, scope: String, botId: String?, kind: String?, stated: Boolean, sensitivity: Int, shares: Set<String>, source: String, expires: Long?)
}

class MemScopeAdapter(private val src: ScopeSource) : SyncEngine.Adapter {
    override val kind = ScopedSync.MEM_SCOPE

    private fun stampOf(l: MemScopes.Line) = "${l.scope}|${l.botId}|${l.stated}|${l.sensitivity}|${l.shareTags.sorted().joinToString(",")}|${l.expires}"

    /** Only lines that have a real (non-legacy) scope row and may leave the phone. */
    private fun mine(): Map<String, MemScopes.Line> = src.lines().filter { it.source != "legacy" && ScopedSync.syncable(it, src.syncPrivate()) }
        .associateBy { MemoryAdapter.idOf(it.text) }

    override fun stamps(): Map<String, String> = mine().mapValues { stampOf(it.value) }

    override fun read(id: String): SyncMerge.Item? = mine()[id]?.let { SyncMerge.Item(kind, id, maxOf(it.updated, 1) * 1000, ScopedSync.scopeData(it)) }

    /** A scope row follows its line: a tombstone does nothing here (the memory item's tombstone deletes the line). */
    override val pushDeletes: Boolean get() = false

    override fun apply(item: SyncMerge.Item) {
        if (item.deleted) return
        val d = item.data!!
        val text = d.optString("text")
        if (text.isBlank() || MemoryAdapter.idOf(text) != item.id) return
        val scope = d.optString("scope")
        if (MemScopes.parse(scope) == null) return
        val shares = d.optJSONArray("shares")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        src.put(text, scope, d.optString("bot").ifEmpty { null }, d.optString("kind").ifEmpty { null }, d.optBoolean("stated", true),
            d.optInt("sens", 0).coerceIn(0, 1), shares, d.optString("source", "sync").ifEmpty { "sync" }, d.optLong("expires", 0).takeIf { it > 0 })
    }
}

/** One item per bot: its SOUL.md. [bots] = the ids on the roster (souls of deleted bots don't sync back). */
class SoulAdapter(private val load: (String) -> String?, private val store: (String, String) -> Unit, private val bots: () -> List<String>,
                  private val at: (String) -> Long = { 0L }) : SyncEngine.Adapter {
    override val kind = ScopedSync.SOUL

    private fun parsed(id: String): Souls.Soul? = load(id)?.let { (Souls.parse(it) as? Souls.Parsed.Ok)?.soul }

    override fun stamps(): Map<String, String> = bots().filter { SyncKinds.ID_RE.matches(it) }.mapNotNull { id ->
        val body = load(id) ?: return@mapNotNull null
        id to "${parsed(id)?.version ?: 0}:${body.hashCode()}"
    }.toMap()

    override fun read(id: String): SyncMerge.Item? {
        val body = load(id) ?: return null
        val v = parsed(id)?.version ?: 0
        return SyncMerge.Item(kind, id, at(id).takeIf { it > 0 } ?: 1L, JSONObject().put("body", body).put("version", v))
    }

    override val pushDeletes: Boolean get() = false

    override fun apply(item: SyncMerge.Item) {
        if (item.deleted || item.id !in bots()) return
        val body = item.data!!.optString("body")
        val r = Souls.parse(body) as? Souls.Parsed.Ok ?: return
        val here = parsed(item.id)
        if (here != null && here.version > r.soul.version) return
        if (Souls.hasSecret(body)) return
        store(item.id, body)
    }
}
