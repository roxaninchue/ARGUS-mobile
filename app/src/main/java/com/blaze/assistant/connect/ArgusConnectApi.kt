package com.blaze.assistant.connect

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * P4-CN-01: the argus-connect user API (`/v1/me/…`, argus-connect 0.2.0, HANDOFF-connectors.md) as a pure client.
 *
 * Every call carries the ARGUS session token (`bz_…`) as its bearer and nothing else: third-party credentials live
 * encrypted on argus-connect, never on the phone (D5). Signed out = no token = no call and no connector tools.
 * Pure: the network is a [Transport] and time a clock, so ArgusConnectTest runs it against a mock server on the JVM.
 * The Android glue (base URL, session token, loopback OAuth, state for the Apps page) is [ArgusConnect].
 */
class ArgusConnectApi(
    private val base: () -> String,
    private val token: () -> String,
    private val transport: Transport,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun interface Transport { fun req(method: String, url: String, body: String?, headers: Map<String, String>): Pair<Int, String> }

    /** A failed call: [status] 0 = unreachable, [code] the server's error code (`not_found`, `missing`, `unauthorized`…). */
    class ConnectError(val status: Int, val code: String, override val message: String) : Exception(message)

    // ------------------------------------------------------------------ models (GET /v1/me/apps, admin.ts appSummary)

    data class Field(val key: String, val label: String, val secret: Boolean, val optional: Boolean)
    data class Auth(val none: Boolean, val tokenLabel: String?, val tokenOptional: Boolean, val fields: List<Field>,
                    val oauthProvider: String?, val oauthReady: Boolean) {
        val oauth get() = oauthProvider != null
        /** Connect by pasting a key or filling fields (as opposed to OAuth only, or nothing to connect). */
        val byKey get() = tokenLabel != null || fields.isNotEmpty()
    }
    /** One tool of an app. [full] is the server's tool name (`<app>_<tool>`), what /v1/me/execute takes. */
    data class ToolInfo(val name: String, val full: String, val description: String, val readOnly: Boolean)
    data class AppInfo(
        val id: String, val name: String, val category: String, val description: String, val setup: String, val free: Boolean,
        val auth: Auth, val test: String?, val tools: List<ToolInfo>,
        val enabled: Boolean, val disabledTools: Set<String>, val connected: Boolean,
        /** `oauth` or `pasted`, and the last four of a pasted key (`…ab12`): never a secret. */
        val via: String?, val tokenHint: String?, val updatedAt: Long?,
    ) {
        /** Usable now: switched on, and connected unless the app needs no connection. */
        val ready get() = enabled && (connected || auth.none)
        val needsConnect get() = !auth.none && !connected
    }

    data class TestResult(val ok: Boolean, val tool: String?, val detail: String)
    data class ExecResult(val ok: Boolean, val text: String, val code: String? = null)
    data class Skill(val slug: String, val name: String, val description: String, val content: String, val apps: List<String>,
                     val enabled: Boolean, val original: Boolean, val updatedAt: Long? = null)
    data class Polished(val name: String, val description: String, val content: String)

    // ------------------------------------------------------------------ plumbing

    fun signedIn() = token().isNotBlank()

    private fun url(path: String) = base().trim().trimEnd('/') + path

    internal fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val t = token()
        if (t.isBlank()) throw ConnectError(401, "signed_out", "Sign in to your ARGUS account to use apps.")
        val (status, text) = try {
            transport.req(method, url(path), body?.toString(), mapOf("Authorization" to "Bearer $t", "Accept" to "application/json"))
        } catch (e: ConnectError) { throw e } catch (e: Exception) {
            throw ConnectError(0, "unreachable", "Apps are unreachable right now (${e.message ?: e.javaClass.simpleName}).")
        }
        val o = try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (_: Exception) { JSONObject().put("raw", text.take(300)) }
        if (status !in 200..299) {
            val err = o.optJSONObject("error")
            val code = err?.optString("code")?.takeIf { it.isNotBlank() } ?: o.optString("error").takeIf { it.isNotBlank() } ?: "http_$status"
            val msg = err?.optString("message")?.takeIf { it.isNotBlank() } ?: o.optString("message").takeIf { it.isNotBlank() }
                ?: when (status) { 401, 403 -> "Your ARGUS session ended. Sign in again."; 404 -> "Not found."; else -> "Apps answered $status." }
            if (status == 401 || status == 403) dropToolCache()
            throw ConnectError(status, code, msg)
        }
        return o
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------------------ apps

    fun apps(): List<AppInfo> = parseApps(call("GET", "/v1/me/apps"))

    /** PUT /v1/me/apps/:id: switch an app on or off and/or set its disabled tools (server keeps only real tool names). */
    fun setApp(id: String, enabled: Boolean? = null, disabledTools: Collection<String>? = null): JSONObject {
        val b = JSONObject()
        if (enabled != null) b.put("enabled", enabled)
        if (disabledTools != null) b.put("disabled_tools", JSONArray(disabledTools.distinct()))
        return call("PUT", "/v1/me/apps/${enc(id)}", b).also { dropToolCache() }
    }

    /** PUT /v1/me/apps/:id/connection: a pasted key and/or fields, stored encrypted for this user on argus-connect. */
    fun connectWithKey(id: String, token: String?, fields: Map<String, String> = emptyMap()) {
        val b = JSONObject()
        if (!token.isNullOrBlank()) b.put("token", token.trim())
        if (fields.isNotEmpty()) b.put("fields", JSONObject(fields.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }))
        call("PUT", "/v1/me/apps/${enc(id)}/connection", b)
        dropToolCache()
    }

    fun disconnect(id: String): Boolean = call("DELETE", "/v1/me/apps/${enc(id)}/connection").optBoolean("deleted", true).also { dropToolCache() }

    /** POST /v1/me/apps/:id/oauth/start → the provider URL to open in the browser; [returnTo] = the app's loopback. */
    fun oauthStart(id: String, returnTo: String?): String {
        val b = JSONObject(); if (!returnTo.isNullOrBlank()) b.put("return_to", returnTo)
        return call("POST", "/v1/me/apps/${enc(id)}/oauth/start", b).optString("url").ifBlank {
            throw ConnectError(502, "no_url", "Apps didn't return a sign-in page.") }
    }

    fun test(id: String): TestResult {
        val o = call("POST", "/v1/me/apps/${enc(id)}/test")
        val ok = o.optBoolean("ok")
        val detail = if (ok) o.optString("data").take(300)
            else o.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "The test call failed." }
        return TestResult(ok, o.optString("tool").takeIf { it.isNotBlank() }, detail)
    }

    // ------------------------------------------------------------------ tools (5-min cache) + execute (12 KB cap)

    private class Cached(val key: String, val at: Long, val tools: List<JSONObject>)
    @Volatile private var cache: Cached? = null

    fun dropToolCache() { cache = null }

    private fun tokenKey(t: String): String = MessageDigest.getInstance("SHA-256").digest(t.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

    /**
     * GET /v1/me/tools: OpenAI function defs of this user's enabled tools. Cached [CACHE_MS] per session token, so a
     * new sign-in never sees the last account's list. Signed out → empty, no call. [force] skips the cache.
     */
    fun tools(force: Boolean = false): List<JSONObject> {
        val t = token(); if (t.isBlank()) return emptyList()
        val key = tokenKey(t)
        val c = cache
        if (!force && c != null && c.key == key && now() - c.at in 0 until CACHE_MS) return c.tools
        val arr = call("GET", "/v1/me/tools").optJSONArray("tools") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optJSONObject("function")?.optString("name").orEmpty().isNotBlank() }
        cache = Cached(key, now(), list)
        return list
    }

    /** The cached list for the current session token when still fresh, else null. Never calls out. */
    fun cachedTools(): List<JSONObject>? {
        val t = token(); if (t.isBlank()) return null
        val c = cache ?: return null
        return if (c.key == tokenKey(t) && now() - c.at in 0 until CACHE_MS) c.tools else null
    }

    /** POST /v1/me/execute {name, arguments}. Output capped at [MAX_OUTPUT] chars with a plain-words note when cut. */
    fun execute(name: String, args: JSONObject): ExecResult {
        val o = try { call("POST", "/v1/me/execute", JSONObject().put("name", name).put("arguments", args)) }
            catch (e: ConnectError) {
                val hint = when (e.code) {
                    "not_connected" -> " Connect it in Settings › Apps & connectors."
                    "disabled" -> " It's switched off in Settings › Apps & connectors."
                    else -> ""
                }
                return ExecResult(false, e.message + hint, e.code)
            }
        if (!o.optBoolean("ok", true)) {
            val err = o.optJSONObject("error")
            return ExecResult(false, err?.optString("message").orEmpty().ifBlank { "The app call failed." }, err?.optString("code"))
        }
        val data = o.opt("data")
        val text = when (data) { null, JSONObject.NULL -> "Done."; is String -> data; is JSONObject -> data.toString(); is JSONArray -> data.toString(); else -> data.toString() }
        return ExecResult(true, cap(text))
    }

    // ------------------------------------------------------------------ skills

    fun skills(): Pair<List<Skill>, List<Skill>> {
        val o = call("GET", "/v1/me/skills")
        return parseSkills(o.optJSONArray("mine"), false) to parseSkills(o.optJSONArray("originals"), true)
    }

    fun putSkill(s: Skill) {
        require(s.content.length <= MAX_SKILL) { "Skill content is limited to 100k characters" }
        call("PUT", "/v1/me/skills/${enc(s.slug)}", JSONObject().put("name", s.name).put("description", s.description)
            .put("content", s.content).put("apps", JSONArray(s.apps)).put("enabled", s.enabled))
    }

    fun deleteSkill(slug: String) { call("DELETE", "/v1/me/skills/${enc(slug)}") }

    fun polish(name: String, description: String, content: String): Polished {
        val o = call("POST", "/v1/me/skills/polish", JSONObject().put("name", name).put("description", description).put("content", content))
        return Polished(o.optString("name").ifBlank { name }, o.optString("description").ifBlank { description }, o.optString("content").ifBlank { content })
    }

    companion object {
        const val DEFAULT_BASE = "https://argus-connect.blaze-app.workers.dev"
        const val CACHE_MS = 5 * 60_000L
        const val MAX_OUTPUT = 12_000
        const val MAX_SKILL = 100_000

        fun cap(s: String, max: Int = MAX_OUTPUT): String =
            if (s.length <= max) s else s.take(max) + "\n…(cut: the app returned ${s.length} characters; ask for less, e.g. a smaller page or a filter)"

        private fun JSONObject.str(k: String) = optString(k).takeIf { has(k) && !isNull(k) }.orEmpty()

        fun parseApps(o: JSONObject): List<AppInfo> {
            val arr = o.optJSONArray("apps") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseApp) }
        }

        fun parseApp(a: JSONObject): AppInfo? {
            val id = a.str("id"); if (id.isBlank()) return null
            val au = a.optJSONObject("auth") ?: JSONObject()
            val tok = au.optJSONObject("token")
            val oa = au.optJSONObject("oauth")
            val fa = au.optJSONArray("fields") ?: JSONArray()
            val fields = (0 until fa.length()).mapNotNull { fa.optJSONObject(it) }.map {
                Field(it.str("key"), it.str("label").ifBlank { it.str("key") }, it.optBoolean("secret"), it.optBoolean("optional")) }
            val ta = a.optJSONArray("tools") ?: JSONArray()
            val tools = (0 until ta.length()).mapNotNull { ta.optJSONObject(it) }.map {
                val n = it.str("name"); ToolInfo(n, it.str("full").ifBlank { "${id}_$n" }, it.str("description"), it.optBoolean("readOnly")) }
            val dt = a.optJSONArray("disabled_tools") ?: JSONArray()
            val con = a.optJSONObject("connection")
            val meta = con?.optJSONObject("meta")
            return AppInfo(
                id = id, name = a.str("name").ifBlank { id }, category = a.str("category").ifBlank { "Other" },
                description = a.str("description"), setup = a.str("setup"), free = a.optBoolean("free"),
                auth = Auth(au.optBoolean("none"), tok?.str("label")?.ifBlank { "API key" }, tok?.optBoolean("optional") ?: false, fields,
                    oa?.str("provider")?.takeIf { it.isNotBlank() }, oa?.optBoolean("ready") ?: false),
                test = a.str("test").takeIf { it.isNotBlank() }, tools = tools,
                enabled = a.optBoolean("enabled"), disabledTools = (0 until dt.length()).map { dt.optString(it) }.filter { it.isNotBlank() }.toSet(),
                connected = a.optBoolean("connected"),
                via = meta?.str("via")?.takeIf { it.isNotBlank() }, tokenHint = meta?.str("token_hint")?.takeIf { it.isNotBlank() },
                updatedAt = con?.optLong("updated_at")?.takeIf { it > 0 },
            )
        }

        fun parseSkills(arr: JSONArray?, original: Boolean): List<Skill> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { s ->
                val slug = s.str("slug"); if (slug.isBlank()) return@mapNotNull null
                val ap = s.optJSONArray("apps") ?: JSONArray()
                Skill(slug, s.str("name").ifBlank { slug }, s.str("description"), s.str("content"),
                    (0 until ap.length()).map { ap.optString(it) }.filter { it.isNotBlank() },
                    if (s.has("enabled")) s.optBoolean("enabled", true) else true, original, s.optLong("updated_at").takeIf { it > 0 })
            }
        }
    }
}
