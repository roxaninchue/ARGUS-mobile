package com.blaze.assistant.movies

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 0.46.2 the user's added source repos and installed providers. Starts EMPTY: Argus bundles, pre-fills and suggests no repo.
 * State (one JSON blob via [kv]) + module files under [dir]/<repo-hash>/<provider>/<file>.js. Network via [fetch] (guarded by
 * [UrlGuard]). Pure JVM (no Android): ProviderStoreTest runs it against the local mock repo.
 */
class ProviderStore(private val dir: File, private val kv: Kv,
                    /** 0.55.14: how a private source's token is kept at rest (the app seals it with the Android Keystore). */
                    private val seal: (String) -> String = { it }, private val unseal: (String) -> String = { it },
                    /** 0.55.14: a fetch that sends headers (a private source's Authorization); null = [defaultFetch]. */
                    private val fetchAuth: ((String, Map<String, String>) -> String)? = null,
                    private val fetch: (String) -> String = { url -> defaultFetch(url) }) {
    interface Kv { fun get(key: String): String; fun put(key: String, value: String) }

    enum class Status(val label: String) { NOT_TESTED("Not tested"), WORKING("Working"), FAILING("Failing") }

    /** [manifest] = a manifest file the user linked other than <base>/manifest.json; [auth] = the only URL prefix its token may go to. */
    data class Repo(val base: String, val label: String, val added: Long, val manifest: String = "", val auth: String = "", val isPrivate: Boolean = false)
    data class Installed(val entry: ProviderRepo.Entry, val repo: String, val status: Status = Status.NOT_TESTED, val detail: String = "",
                         val enabled: Boolean = true, val hangs: Int = 0, val latest: String = "", val settings: Map<String, String> = emptyMap()) {
        val value get() = entry.value
        val updateAvailable get() = latest.isNotBlank() && ProviderRepo.newer(entry.version, latest)
    }
    data class State(val repos: List<Repo> = emptyList(), val installed: List<Installed> = emptyList(), val active: String = "", val noticeSeen: Boolean = false,
                     /** Blaze Official was preinstalled once (removing it later keeps it removed). */
                     val seeded: Boolean = false)

    companion object {
        const val KEY = "movies_providers_v1"
        /** 0.55.14: private sources' tokens, sealed, by repo base (kept apart from the state so they never land in a log or export). */
        const val TOKENS_KEY = "movies_source_tokens_v1"
        const val MAX_MODULE = 3 * 1024 * 1024
        fun defaultFetch(url: String, extra: Map<String, String> = emptyMap()): String {
            UrlGuard.check(url)?.let { throw IllegalArgumentException(it) }
            val r = DefaultSandboxHttp.request(SandboxHttp.Req(url, headers = mapOf("Cache-Control" to "no-cache") + extra))
            if (r.status !in 200..299) throw IllegalStateException("The source answered ${r.status}")
            return r.body
        }
        fun hash(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

        fun encode(s: State): String = JSONObject().put("v", 1).put("active", s.active).put("notice", s.noticeSeen).put("seeded", s.seeded)
            .put("repos", JSONArray(s.repos.map { JSONObject().put("base", it.base).put("label", it.label).put("added", it.added)
                .put("manifest", it.manifest).put("auth", it.auth).put("private", it.isPrivate) }))
            .put("installed", JSONArray(s.installed.map { i -> JSONObject().put("value", i.entry.value).put("name", i.entry.name).put("version", i.entry.version)
                .put("icon", i.entry.icon).put("type", i.entry.type).put("repo", i.repo).put("status", i.status.name).put("detail", i.detail).put("enabled", i.enabled)
                .put("hangs", i.hangs).put("latest", i.latest).put("settings", JSONObject(i.settings as Map<*, *>)) })).toString()

        fun decode(json: String?): State = try {
            val o = JSONObject(json ?: "")
            val repos = o.optJSONArray("repos")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { r ->
                r.optString("base").takeIf { it.isNotBlank() }?.let { Repo(it, r.optString("label", ProviderRepo.label(it)), r.optLong("added"),
                    r.optString("manifest"), r.optString("auth"), r.optBoolean("private")) } } }.orEmpty()
            val inst = o.optJSONArray("installed")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { i ->
                val v = i.optString("value"); if (!ProviderRepo.SAFE_ID.matches(v)) return@mapNotNull null
                val st = runCatching { Status.valueOf(i.optString("status")) }.getOrDefault(Status.NOT_TESTED)
                val set = LinkedHashMap<String, String>(); i.optJSONObject("settings")?.let { so -> so.keys().forEach { k -> set[k] = so.optString(k) } }
                Installed(ProviderRepo.Entry(v, i.optString("name", v), i.optString("version", "0"), i.optString("icon"), i.optString("type", "global")),
                    i.optString("repo"), st, i.optString("detail"), i.optBoolean("enabled", true), i.optInt("hangs"), i.optString("latest"), set)
            } }.orEmpty()
            State(repos, inst, o.optString("active"), o.optBoolean("notice"), o.optBoolean("seeded"))
        } catch (_: Exception) { State() }
    }

    @Volatile var state: State = decode(kv.get(KEY)); private set
    private fun save(s: State) { state = s; kv.put(KEY, encode(s)) }
    /** Per-provider kvStore contents live in memory for the app session. */
    private val kvs = HashMap<String, MutableMap<String, String>>()

    fun markNoticeSeen() = save(state.copy(noticeSeen = true))

    /** 0.55.14 what [resolveSource] found: the folder, its providers, and how it was written ([SourceInput.Spec]). */
    data class Found(val base: String, val entries: List<ProviderRepo.Entry>, val spec: SourceInput.Spec)

    /**
     * Find a source's manifest from what the user typed or a link carried, the forms provider apps share: an author name
     * (`author`, `author@cb`, `author@bb`, `author@gl`), `owner/repo[@branch]`, a GitHub / Codeberg / Bitbucket / GitLab repo
     * link, a raw folder or a manifest link ([SourceInput]). Tries the repo root, dist/, and main / master. Saves nothing.
     * [token] = a private source's access token (sent only under [SourceInput.Spec.authPrefix]).
     */
    fun resolveSource(input: String, token: String = ""): Found {
        val p = SourceInput.parse(input)
        val spec = p.spec ?: throw IllegalArgumentException(p.error)
        val tok = if (token.isBlank()) "" else SourceInput.token(token) ?: throw IllegalArgumentException(SourceInput.BAD_TOKEN)
        if (tok.isNotEmpty() && !spec.privateOk) throw IllegalArgumentException(SourceInput.PRIVATE_HOSTS)
        var last: Exception? = null
        if (spec.manifest.isNotBlank()) try {
            val e = ProviderRepo.parseManifest(get(spec.manifest, spec.authPrefix, tok))
            if (e.isNotEmpty()) return Found(spec.bases.first(), e, spec)
        } catch (e: Exception) { last = e }
        for (base in spec.bases.take(8)) {
            try {
                val entries = ProviderRepo.parseManifest(get(ProviderRepo.manifestUrl(base), spec.authPrefix, tok))
                if (entries.isNotEmpty()) return Found(base, entries, spec.copy(manifest = ""))
            } catch (e: Exception) { last = e }
        }
        val why = last?.message?.let { " ($it)" } ?: ""
        throw IllegalStateException(if (tok.isEmpty() && spec.privateOk && why.contains("40")) "No manifest.json found at ${spec.label}$why. If the repo is private, add it as Private with a token."
            else "No manifest.json found at ${spec.label}$why")
    }

    /** Kept for callers that only want the folder and its providers. */
    fun resolve(input: String): Pair<String, List<ProviderRepo.Entry>> = resolveSource(input).let { it.base to it.entries }

    /** Add a source (the user typed / pasted it, or opened an add-source link) and read its manifest. Returns the providers it offers. */
    fun addRepo(input: String, token: String = ""): Pair<String, List<ProviderRepo.Entry>> {
        val f = resolveSource(input, token)
        val tok = SourceInput.token(token).orEmpty()
        val repo = Repo(f.base, f.spec.label, state.repos.firstOrNull { it.base == f.base }?.added ?: System.currentTimeMillis(),
            f.spec.manifest, if (tok.isEmpty()) "" else f.spec.authPrefix, tok.isNotEmpty())
        save(state.copy(repos = state.repos.filterNot { it.base == f.base } + repo))
        setToken(f.base, tok)
        return f.base to f.entries
    }

    // ---------- 0.55.14 private sources ----------

    private fun tokens(): Map<String, String> = try {
        val o = JSONObject(kv.get(TOKENS_KEY).ifBlank { "{}" }); o.keys().asSequence().associateWith { o.optString(it) }
    } catch (_: Exception) { emptyMap() }

    private fun setToken(base: String, token: String) {
        val m = tokens().toMutableMap()
        if (token.isEmpty()) { if (m.remove(base) == null) return } else m[base] = seal(token)
        kv.put(TOKENS_KEY, JSONObject(m as Map<*, *>).toString())
    }

    private fun tokenFor(repo: String): String = tokens()[repo]?.let { runCatching { unseal(it) }.getOrDefault("") }.orEmpty()

    /** GET [url]; a token goes along only when [url] sits under that source's own [authPrefix] (never another repo or host). */
    private fun get(url: String, authPrefix: String, token: String): String =
        if (token.isNotEmpty() && SourceInput.authAllowed(authPrefix, url)) (fetchAuth ?: { u, h -> defaultFetch(u, h) })(url, SourceInput.authHeader(token))
        else fetch(url)

    /** A fetch for a saved repo's files (its token, if it's private). */
    private fun repoGet(repo: String, url: String): String {
        val r = state.repos.firstOrNull { it.base == repo } ?: return fetch(url)
        return if (r.isPrivate) get(url, r.auth, tokenFor(repo)) else fetch(url)
    }

    /** Add and install every provider of a repo in one go (the add-source link's single Install button). */
    fun addAndInstallAll(input: String): Int {
        val (base, entries) = addRepo(input)
        val todo = entries.filter { !it.disabled }
        todo.forEach { install(base, it) }
        return todo.size
    }

    /**
     * 0.55.13: the old preinstalled "Blaze Official" repo (Internet Archive + Blender providers) is gone. Phones that had it
     * seeded drop it once; a repo the user added themselves is never touched.
     */
    fun dropLegacyOfficial() {
        if (state.repos.any { it.base == ProviderRepo.LEGACY_OFFICIAL_BASE } || state.installed.any { it.repo == ProviderRepo.LEGACY_OFFICIAL_BASE })
            removeRepo(ProviderRepo.LEGACY_OFFICIAL_BASE)
    }

    fun manifest(base: String): List<ProviderRepo.Entry> {
        val m = state.repos.firstOrNull { it.base == base }?.manifest?.takeIf { it.isNotBlank() } ?: ProviderRepo.manifestUrl(base)
        return ProviderRepo.parseManifest(repoGet(base, m))
    }

    private fun moduleDir(repo: String, value: String) = File(dir, hash(repo) + "/" + value)

    /** Download a provider's modules (required + optional) and record it installed. */
    fun install(repo: String, e: ProviderRepo.Entry) {
        require(ProviderRepo.SAFE_ID.matches(e.value)) { "Bad provider id" }
        val got = LinkedHashMap<String, String>()
        for (f in ProviderRepo.REQUIRED + ProviderRepo.OPTIONAL) {
            var err: Exception? = null
            val text = ProviderRepo.moduleUrls(repo, e.value, f).firstNotNullOfOrNull { u -> try { repoGet(repo, u) } catch (x: Exception) { err = x; null } }
                ?: if (f in ProviderRepo.REQUIRED) throw IllegalStateException("${e.name}: couldn't get $f.js (${err?.message})") else null
            if (text != null) { if (text.length > MAX_MODULE) throw IllegalStateException("${e.name}: $f.js is too big"); got[f] = text }
        }
        val d = moduleDir(repo, e.value); d.deleteRecursively(); d.mkdirs()
        got.forEach { (f, t) -> File(d, "$f.js").writeText(t) }
        val keep = state.installed.firstOrNull { it.value == e.value && it.repo == repo }
        val others = state.installed.filterNot { it.value == e.value }
        save(state.copy(installed = others + Installed(e, repo, Status.NOT_TESTED, "", keep?.enabled ?: true, 0, e.version, keep?.settings.orEmpty()),
            active = state.active.ifBlank { e.value }))
    }

    fun remove(value: String) {
        state.installed.firstOrNull { it.value == value }?.let { moduleDir(it.repo, value).deleteRecursively() }
        kvs.remove(value)
        val left = state.installed.filterNot { it.value == value }
        save(state.copy(installed = left, active = if (state.active == value) left.firstOrNull()?.value.orEmpty() else state.active))
    }

    fun removeRepo(base: String) {
        state.installed.filter { it.repo == base }.forEach { remove(it.value) }
        save(state.copy(repos = state.repos.filterNot { it.base == base }))
        setToken(base, "")
    }

    fun setActive(value: String) = save(state.copy(active = value))
    fun setEnabled(value: String, on: Boolean) = update(value) { it.copy(enabled = on, hangs = if (on) 0 else it.hangs) }
    fun setSettings(value: String, settings: Map<String, String>) = update(value) { it.copy(settings = settings.filterKeys { k -> k.length <= 40 }.mapValues { v -> v.value.take(500) }) }
    private fun update(value: String, f: (Installed) -> Installed) = save(state.copy(installed = state.installed.map { if (it.value == value) f(it) else it }))

    /** Re-read every repo's manifest; returns how many installed providers have a newer version. */
    fun refresh(): Int {
        var n = 0
        val latest = HashMap<String, String>()
        for (r in state.repos) runCatching { manifest(r.base) }.getOrNull()?.forEach { latest[r.base + "|" + it.value] = it.version }
        save(state.copy(installed = state.installed.map { i -> val l = latest[i.repo + "|" + i.value] ?: i.latest; if (ProviderRepo.newer(i.entry.version, l)) n++; i.copy(latest = l) }))
        return n
    }

    fun update(value: String) {
        val i = state.installed.firstOrNull { it.value == value } ?: return
        val e = manifest(i.repo).firstOrNull { it.value == value } ?: throw IllegalStateException("${i.entry.name} is no longer in its source")
        install(i.repo, e)
    }

    fun module(value: String, file: String): String? = state.installed.firstOrNull { it.value == value }?.let { File(moduleDir(it.repo, value), "$file.js").takeIf { f -> f.isFile }?.readText() }

    fun source(value: String, sandbox: ProviderSandbox = ProviderSandbox()): JsProviderSource? = state.installed.firstOrNull { it.value == value }?.let { i ->
        JsProviderSource(i.entry, { f -> module(value, f) }, ProviderSandbox.ProviderEnv(i.settings, kvs.getOrPut(value) { LinkedHashMap() }), sandbox,
            official = false)
    }

    /** Enabled providers as movie sources (the active one first). */
    fun sources(): List<JsProviderSource> = state.installed.filter { it.enabled }.sortedBy { if (it.value == state.active) 0 else 1 }.mapNotNull { source(it.value) }

    /** Test: catalog + the first catalog row (or a search) must answer within the sandbox limits. */
    suspend fun test(value: String): Status {
        val before = ProviderSandbox.abandoned.get()
        val s = source(value) ?: return Status.FAILING
        val (st, why) = try {
            val cat = s.catalog()
            val posts = if (cat.isNotEmpty()) s.rows().firstOrNull()?.second.orEmpty() else s.search("the")
            if (posts.isEmpty()) Status.FAILING to "It answered but listed nothing" else Status.WORKING to "${posts.size} titles"
        } catch (e: Exception) { Status.FAILING to (e.message ?: "Error").take(160) }
        val hung = ProviderSandbox.abandoned.get() > before
        update(value) { it.copy(status = st, detail = why, hangs = if (hung) it.hangs + 1 else it.hangs, enabled = if (hung && it.hangs + 1 >= 2) false else it.enabled) }
        return st
    }

    /** A call failed while browsing: mark it Failing (and turn it off after repeated hangs). */
    fun noteFailure(value: String, why: String) {
        val hung = why.contains("longer than")
        update(value) { it.copy(status = Status.FAILING, detail = why.take(160), hangs = if (hung) it.hangs + 1 else it.hangs, enabled = if (hung && it.hangs + 1 >= 3) false else it.enabled) }
    }
    fun noteWorking(value: String) { if (state.installed.firstOrNull { it.value == value }?.status != Status.WORKING) update(value) { it.copy(status = Status.WORKING, detail = "") } }
}
