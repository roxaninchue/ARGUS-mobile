package com.blaze.assistant.connect

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.blaze.assistant.agent.Settings
import com.blaze.assistant.connect.ArgusAppsModel.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The full app and connector list from the ARGUS gateway (argus-connect, the gateway admin's Apps tab), for the
 * signed-in ARGUS account. Every call carries the account's session token (`bz_…`); argus-connect checks it with
 * blaze-auth, so each person only ever sees and changes their own connections. Credentials stay on the server,
 * encrypted; this phone keeps only the last list (no secrets in it) to show while offline.
 *
 * Endpoints (argus-connect 0.2.0, HANDOFF-connectors.md): GET /v1/me/apps, PUT /v1/me/apps/:id {enabled},
 * PUT|DELETE /v1/me/apps/:id/connection {token, fields}, POST /v1/me/apps/:id/oauth/start → {url},
 * POST /v1/me/apps/:id/test.
 */
object ArgusApps {
    const val DEFAULT_BASE = "https://argus-connect.blaze-app.workers.dev"
    private const val CACHE = "conn_tok_argus_apps_cache"   // conn_tok_: Keystore-encrypted, never synced or backed up
    private const val BASE_PREF = "argus_connect_url"

    fun base(): String = Settings.str(BASE_PREF).trim().trimEnd('/').takeIf { it.startsWith("https://") } ?: DEFAULT_BASE

    data class View(val apps: List<App> = emptyList(), val loading: Boolean = false, val error: String = "", val loadedAt: Long = 0L)

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view

    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Per app id: the last thing that happened ("Connected", "Waiting for your browser…", an error). */
    val status: StateFlow<Map<String, String>> = _status
    private fun status(id: String, s: String) { _status.value = _status.value + (id to s) }

    /** The saved list, shown at once while a refresh runs. */
    fun loadCached() {
        if (_view.value.apps.isNotEmpty()) return
        val list = runCatching { ArgusAppsModel.parse(Settings.secret(CACHE)) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) _view.value = _view.value.copy(apps = list)
    }

    private fun token(): String = BlazeAccount.sessionToken()

    /** One call to argus-connect with the account's session token (also used by ConnectedApps). Blocking: call it off the main thread. */
    internal fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val t = token()
        if (t.isBlank()) return 401 to ""
        return try {
            Http.req(method, base() + path, body?.toString(), mapOf("Authorization" to "Bearer $t", "Accept" to "application/json"), timeoutMs = 25_000)
        } catch (e: Exception) { -1 to (e.message ?: "network error") }
    }

    /** The server's own sentence for a failed call, else a plain one. */
    internal fun errorText(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
        return when {
            code == -1 -> "Couldn't reach the ARGUS gateway. Check your internet connection."
            code == 401 || code == 403 -> "Sign in to your ARGUS account to use your gateway's apps."
            msg.isNotBlank() -> msg
            else -> "The ARGUS gateway answered $code."
        }
    }

    /** Reloads the list. Returns "" or a plain sentence. */
    suspend fun refresh(): String = withContext(Dispatchers.IO) {
        loadCached()
        _view.value = _view.value.copy(loading = true, error = "")
        val (code, body) = call("GET", "/v1/me/apps")
        if (code in 200..299) {
            val list = ArgusAppsModel.parse(body)
            Settings.setSecret(CACHE, body)
            ConnectedApps.invalidate()   // app shelf: a connection may have changed, so action lists are read again
            _view.value = View(apps = list, loading = false, error = "", loadedAt = System.currentTimeMillis())
            ""
        } else {
            val err = errorText(code, body)
            _view.value = _view.value.copy(loading = false, error = err)
            err
        }
    }

    private fun app(id: String): App? = _view.value.apps.firstOrNull { it.id == id }

    /** OAuth: opens the provider in the browser, then watches for the connection for up to three minutes. */
    suspend fun connectOAuth(ctx: Context, id: String): String {
        val (code, body) = withContext(Dispatchers.IO) { call("POST", "/v1/me/apps/$id/oauth/start", JSONObject()) }
        val url = if (code in 200..299) runCatching { JSONObject(body).optString("url") }.getOrDefault("") else ""
        if (!url.startsWith("https://")) return errorText(code, body).also { status(id, it) }
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { return "Couldn't open a browser.".also { s -> status(id, s) } }
        status(id, "Waiting for your browser\u2026")
        repeat(45) {
            delay(4_000)
            refresh()
            if (app(id)?.connected == true) return "Connected".also { status(id, it) }
        }
        return "Not connected yet. Approve in the browser, then pull to refresh.".also { status(id, it) }
    }

    /** Pasted key and/or fields. Blank values keep what the server already has. */
    suspend fun saveConnection(id: String, token: String, fields: Map<String, String>): String {
        val b = JSONObject()
        if (token.isNotBlank()) b.put("token", token.trim())
        val f = JSONObject(); fields.forEach { (k, v) -> if (v.isNotBlank()) f.put(k, v.trim()) }
        if (f.length() > 0) b.put("fields", f)
        val (code, body) = withContext(Dispatchers.IO) { call("PUT", "/v1/me/apps/$id/connection", b) }
        if (code !in 200..299) return errorText(code, body).also { status(id, it) }
        refresh()
        return test(id)
    }

    suspend fun disconnect(id: String): String {
        val (code, body) = withContext(Dispatchers.IO) { call("DELETE", "/v1/me/apps/$id/connection") }
        if (code !in 200..299) return errorText(code, body).also { status(id, it) }
        refresh()
        return "Disconnected".also { status(id, it) }
    }

    suspend fun setEnabled(id: String, on: Boolean): String {
        val (code, body) = withContext(Dispatchers.IO) { call("PUT", "/v1/me/apps/$id", JSONObject().put("enabled", on)) }
        if (code !in 200..299) return errorText(code, body).also { status(id, it) }
        refresh()
        return (if (on) "On" else "Off").also { status(id, it) }
    }

    /** Runs the app's own test call with this account's connection. */
    suspend fun test(id: String): String {
        val (code, body) = withContext(Dispatchers.IO) { call("POST", "/v1/me/apps/$id/test", JSONObject()) }
        if (code !in 200..299) return errorText(code, body).also { status(id, it) }
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return "Saved".also { status(id, it) }
        val s = if (o.optBoolean("ok")) "Connected \u00b7 test passed"
        else o.optJSONObject("error")?.let { e -> if (e.optString("code") == "no_test") "Saved" else "Test failed: " + e.optString("message").ifBlank { "unknown error" } } ?: "Saved"
        return s.also { status(id, it) }
    }
}
