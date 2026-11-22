package com.blaze.assistant.agent

import com.blaze.assistant.store.BlazeDb
import com.blaze.assistant.store.Sql
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.55.0 lean harness (docs/LEAN-HARNESS.md): the tool and skill catalog in blaze.db. A lean turn sends ~16 tools
 * ([LeanTurn]); everything else waits here until the model asks with `find_tools` / `load_skill`.
 *
 * Rows come from two places, one table:
 *  - the gateway (`GET /v1/catalog`, gateway 0.9.38): the tools and skills the account's PLAN allows, with a hash and an
 *    ETag. Synced in the background at most every [SYNC_EVERY_MS]; a turn never waits for it.
 *  - this phone (`src = phone`): every tool the app registers (Agent.tools), so the catalog works before the first sync,
 *    offline, and for tools the gateway doesn't list (MCP servers, the account's own). A gateway sync replaces only the
 *    gateway rows; phone rows whose name the gateway lists are then hidden by the gateway row.
 *
 * Search is FTS5 (bm25: name x4, group x2, summary x1), the same way blaze_docs is searched. Limits are never enforced
 * here: the gateway drops what a plan doesn't allow. Prefixed side table like BlazeDocs / ProjectDocs: blaze.db's schema
 * version is untouched and Desktop / the CLI don't read it. Pure JVM over [Sql] (LeanCatalogTest on sqlite-jdbc).
 */
object LeanCatalog {
    const val META_HASH = "lean_catalog:hash"
    const val META_ETAG = "lean_catalog:etag"
    const val META_AT = "lean_catalog:at"
    const val META_PLAN = "lean_catalog:plan"
    const val SYNC_EVERY_MS = 6 * 60 * 60_000L
    const val RETRY_MS = 15 * 60_000L
    const val SRC_GATEWAY = "gateway"
    const val SRC_PHONE = "phone"
    const val KIND_TOOL = "tool"
    const val KIND_SKILL = "skill"

    data class Entry(val kind: String, val name: String, val group: String = "", val summary: String = "", val src: String = SRC_GATEWAY)

    /** The gateway's catalog answer. */
    data class Parsed(val plan: String, val hash: String, val tools: List<Entry>, val skills: List<Entry>)

    private val NAME_RE = Regex("^[A-Za-z0-9_.:-]{1,128}$")

    /** `GET /v1/catalog`'s body, or null when it isn't one (then the phone keeps what it has). */
    fun parse(body: String): Parsed? = try {
        val j = JSONObject(body)
        fun list(k: String, kind: String): List<Entry> {
            val a = j.optJSONArray(k) ?: JSONArray()
            return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.mapNotNull { o ->
                val n = o.optString("name").trim()
                if (!NAME_RE.matches(n)) null else Entry(kind, n, o.optString("group").take(40), o.optString("summary").take(300))
            }.distinctBy { it.name }
        }
        val hash = j.optString("hash")
        if (j.optInt("version") != 1 || hash.isBlank()) null
        else Parsed(j.optString("plan"), hash, list("tools", KIND_TOOL), list("skills", KIND_SKILL))
    } catch (_: Exception) { null }

    /** A sync is due: never synced, or older than [SYNC_EVERY_MS] (a failed try waits [RETRY_MS]). */
    fun due(lastAt: Long, now: Long, lastFailedAt: Long = 0L): Boolean =
        !(lastFailedAt > 0L && now - lastFailedAt < RETRY_MS) && (lastAt <= 0L || now - lastAt >= SYNC_EVERY_MS)

    /** Phone tool rows from an OpenAI tools array (name, first sentence of the description, its ToolRouter group). */
    fun phoneRows(tools: JSONArray, groupOf: (String) -> String? = { null }): List<Entry> =
        (0 until tools.length()).mapNotNull { tools.optJSONObject(it)?.optJSONObject("function") }.mapNotNull { f ->
            val n = f.optString("name")
            if (!NAME_RE.matches(n)) null else Entry(KIND_TOOL, n, groupOf(n).orEmpty(), firstSentence(f.optString("description")), SRC_PHONE)
        }.distinctBy { it.name }

    fun firstSentence(s: String): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        val m = Regex("^(.{20,200}?[.!?])(\\s|$)").find(t)
        return (m?.groupValues?.get(1) ?: t.take(160)).trim()
    }

    const val TABLES = """CREATE TABLE IF NOT EXISTS lean_catalog(kind TEXT NOT NULL, name TEXT NOT NULL, src TEXT NOT NULL, grp TEXT NOT NULL DEFAULT '', summary TEXT NOT NULL DEFAULT '', PRIMARY KEY(kind, name, src));
CREATE VIRTUAL TABLE IF NOT EXISTS lean_catalog_fts USING fts5(kind UNINDEXED, src UNINDEXED, name, grp, summary, tokenize='porter unicode61');"""

    class Store(private val sql: Sql, private val getMeta: (String) -> String, private val setMeta: (String, String) -> Unit) {
        private val lock = Any()

        init { for (st in BlazeDb.statements(TABLES)) sql.exec(st) }

        fun meta(k: String): String = getMeta(k)
        fun hash(): String = getMeta(META_HASH)
        fun syncedAt(): Long = getMeta(META_AT).toLongOrNull() ?: 0L

        fun count(kind: String? = null, src: String? = null): Int = sql.query(
            "SELECT count(*) FROM lean_catalog WHERE (? = '' OR kind = ?) AND (? = '' OR src = ?)",
            kind.orEmpty(), kind.orEmpty(), src.orEmpty(), src.orEmpty()) { it.long(0).toInt() }.first()

        private fun insert(e: Entry) {
            sql.exec("INSERT OR REPLACE INTO lean_catalog(kind, name, src, grp, summary) VALUES(?,?,?,?,?)", e.kind, e.name, e.src, e.group, e.summary)
            sql.exec("INSERT INTO lean_catalog_fts(kind, src, name, grp, summary) VALUES(?,?,?,?,?)", e.kind, e.src, words(e.name), e.group, e.summary)
        }

        private fun deleteSrc(src: String) {
            sql.exec("DELETE FROM lean_catalog WHERE src = ?", src)
            sql.exec("DELETE FROM lean_catalog_fts WHERE src = ?", src)
        }

        /** Replaces every gateway row with [p] (one transaction) and records its hash, plan and [etag]. Idempotent. */
        fun replaceGateway(p: Parsed, etag: String, now: Long) = synchronized(lock) {
            sql.tx {
                deleteSrc(SRC_GATEWAY)
                for (e in p.tools + p.skills) insert(e.copy(src = SRC_GATEWAY))
            }
            setMeta(META_HASH, p.hash); setMeta(META_PLAN, p.plan); setMeta(META_ETAG, etag); setMeta(META_AT, now.toString())
        }

        /** An unchanged catalog (304): only the sync time moves. */
        fun touch(now: Long) = setMeta(META_AT, now.toString())

        /** Replaces the phone's own rows with [rows] (the tools this app registers now). */
        fun replacePhone(rows: List<Entry>) = synchronized(lock) {
            sql.tx { deleteSrc(SRC_PHONE); for (e in rows) insert(e.copy(src = SRC_PHONE)) }
        }

        /** Names of [kind] the catalog holds (gateway and phone rows). */
        fun names(kind: String): Set<String> = sql.query("SELECT DISTINCT name FROM lean_catalog WHERE kind = ?", kind) { it.text(0) }.toSet()

        /** Names the gateway's plan catalog lists (empty before the first sync). */
        fun gatewayNames(kind: String): Set<String> = sql.query("SELECT name FROM lean_catalog WHERE kind = ? AND src = ?", kind, SRC_GATEWAY) { it.text(0) }.toSet()

        /**
         * Best entries for [query] (bm25), [kind] or both, one per name (the gateway's row wins over the phone's). Once a
         * gateway catalog is in, phone tool rows that the gateway knows by another kind aren't doubled.
         */
        fun search(query: String, limit: Int, kind: String? = null): List<Entry> {
            val fq = BlazeDb.ftsQuery(query.replace('_', ' '), 24)
            if (fq.isEmpty()) return emptyList()
            val rows = try {
                sql.query("SELECT kind, src, name, grp, summary FROM lean_catalog_fts WHERE lean_catalog_fts MATCH ? AND (? = '' OR kind = ?) " +
                    "ORDER BY bm25(lean_catalog_fts, 0.0, 0.0, 4.0, 2.0, 1.0) LIMIT ?", fq, kind.orEmpty(), kind.orEmpty(), (limit * 4).coerceIn(4, 80)) {
                    Entry(it.text(0), unwords(it.text(2)), it.text(3), it.text(4), it.text(1))
                }
            } catch (_: Exception) { emptyList() }
            val seen = HashSet<String>()
            return rows.filter { seen.add(it.kind + ":" + it.name) }.take(limit)
        }

        fun get(kind: String, name: String): Entry? = sql.query(
            "SELECT kind, name, grp, summary, src FROM lean_catalog WHERE kind = ? AND name = ? ORDER BY CASE src WHEN 'gateway' THEN 0 ELSE 1 END LIMIT 1", kind, name) {
            Entry(it.text(0), it.text(1), it.text(2), it.text(3), it.text(4))
        }.firstOrNull()
    }

    /**
     * 0.55.0: does a catalog ticket ([cat] = its plan catalog hash) let every one of [names] through? Yes when each is in the
     * ticket's own list ([allowed]) or in the plan catalog the phone synced, and only when that synced catalog IS the
     * ticket's ([have] = hash, tool names). Anything unsure answers false: the turn takes the normal route, no tool lost.
     */
    fun covers(allowed: Set<String>, cat: String, names: Collection<String>, have: Pair<String, Set<String>>): Boolean =
        cat.isNotBlank() && have.first == cat && names.all { it in allowed || it in have.second }

    /** The synced plan catalog's hash and tool names (empty before the first sync); cached until the hash changes. */
    fun coverage(): Pair<String, Set<String>> = try {
        val s = store() ?: return "" to emptySet()
        val h = s.hash()
        coverageCache?.takeIf { it.first == h } ?: (h to s.gatewayNames(KIND_TOOL)).also { coverageCache = it }
    } catch (_: Throwable) { "" to emptySet() }
    @Volatile private var coverageCache: Pair<String, Set<String>>? = null

    /** "github_create_issue" → "github create issue github_create_issue" so FTS finds it by its words and its name. */
    fun words(name: String): String = if ('_' in name || '-' in name) name.replace('_', ' ').replace('-', ' ') + " " + name else name
    private fun unwords(s: String): String = s.substringAfterLast(' ')

    // ---------------- Android glue (blaze.db store + background sync) ----------------

    @Volatile private var store: Store? = null
    @Volatile private var failedAt = 0L
    @Volatile private var phoneHash = ""
    private val syncing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Tests: use [s] as the store (null = open blaze.db again on next use). */
    fun use(s: Store?) { store = s; failedAt = 0L; phoneHash = "" }

    fun store(): Store? = store ?: try {
        (Mind.db() as? MindStore)?.let { m -> Store(m.sql, { m.blaze.meta(it) }, { k, v -> m.blaze.setMeta(k, v) }).also { store = it } }
    } catch (_: Throwable) { null }

    /** The phone's tool list this turn (Agent.tools): when it changed, its rows are rewritten off the turn's path. */
    fun notePhoneTools(tools: JSONArray, groupOf: (String) -> String?) {
        val h = tools.length().toString() + ":" + (0 until tools.length()).joinToString(",") { tools.optJSONObject(it)?.optJSONObject("function")?.optString("name").orEmpty() }.hashCode()
        if (h == phoneHash) return
        phoneHash = h
        Thread({ try { store()?.replacePhone(phoneRows(tools, groupOf)) } catch (_: Throwable) { phoneHash = "" } }, "lean-catalog-phone")
            .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Starts a background sync from the gateway when one is due. Never blocks, never throws. */
    fun syncSoon(now: Long = System.currentTimeMillis()) {
        val s = store() ?: return
        if (!due(s.syncedAt(), now, failedAt) || !syncing.compareAndSet(false, true)) return
        Thread({ try { sync(s) } catch (_: Throwable) { failedAt = System.currentTimeMillis() } finally { syncing.set(false) } }, "lean-catalog-sync")
            .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** One `GET /v1/catalog` (If-None-Match = the last ETag). True when the catalog is current afterwards. */
    fun sync(s: Store): Boolean {
        val token = try { Settings.gatewayToken() } catch (_: Throwable) { "" }
        if (token.isBlank()) return false
        val c = com.blaze.assistant.connect.UrlSafety.open(Providers.GATEWAY_BASE.trimEnd('/') + "/catalog")
        c.requestMethod = "GET"; c.connectTimeout = 6_000; c.readTimeout = 8_000
        c.setRequestProperty("Authorization", "Bearer $token")
        s.meta(META_ETAG).takeIf { it.isNotBlank() && s.count(src = SRC_GATEWAY) > 0 }?.let { c.setRequestProperty("If-None-Match", it) }
        val code = c.responseCode
        val now = System.currentTimeMillis()
        if (code == 304) { s.touch(now); return true }
        if (code !in 200..299) { failedAt = now; return false }
        val body = c.inputStream.use { String(it.readBytes()) }
        val p = parse(body) ?: run { failedAt = now; return false }
        s.replaceGateway(p, c.getHeaderField("ETag").orEmpty(), now)
        return true
    }
}
