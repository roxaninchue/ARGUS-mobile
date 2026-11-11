package com.blaze.assistant.connect

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.blaze.assistant.BrandName
import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket

/**
 * P4-CN-01: Android glue for argus-connect ([ArgusConnectApi]). The phone holds only the ARGUS session token
 * (BlazeAccount, `bz_…`); every third-party credential stays encrypted on argus-connect (D5). Keeps the Apps page
 * state ([view]), runs OAuth in the browser with a loopback return (allowed by argus-connect: http://127.0.0.1:<port>/…),
 * and hands the agent its connector tools through [ConnectTools].
 */
object ArgusConnect {
    /** Loopback for the argus-connect OAuth return (Connectors' own device OAuth keeps 43117). */
    const val PORT = 43118
    const val RETURN_TO = "http://127.0.0.1:$PORT/connected"
    private const val PREF_BASE = "argus_connect_url"
    /** Off switch for the whole argus-connect path (Settings › Apps & connectors). On by default from 0.56.0. */
    const val PREF_ON = "argus_connect_on"

    fun base(): String = Settings.str(PREF_BASE).trim().trimEnd('/').ifBlank { ArgusConnectApi.DEFAULT_BASE }
    val on: Boolean get() = Settings.bool(PREF_ON, true)

    val api = ArgusConnectApi(
        base = ::base,
        token = { if (on) BlazeAccount.sessionToken() else "" },
        transport = { m, u, b, h -> Http.req(m, u, b, h, timeoutMs = 45_000) },
    )

    data class View(
        val apps: List<ArgusConnectApi.AppInfo> = emptyList(),
        val loading: Boolean = false,
        val error: String = "",
        /** app id → one status line ("Connected", "Waiting for you in the browser…", "Test passed", an error). */
        val status: Map<String, String> = emptyMap(),
        val loadedAt: Long = 0,
    ) {
        val connected get() = apps.filter { it.connected }
        val available get() = apps.filter { !it.connected }
        val categories get() = apps.map { it.category }.distinct().sorted()
    }

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun status(id: String, s: String) { _view.value = _view.value.copy(status = _view.value.status + (id to s)) }

    fun signedIn() = api.signedIn()

    /** Reload the app list (Apps page open, after a change). Signed out → empty list, no error. */
    fun refresh() = scope.launch { load() }

    suspend fun load(): View = withContext(Dispatchers.IO) {
        if (!api.signedIn()) { _view.value = View(error = ""); return@withContext _view.value }
        _view.value = _view.value.copy(loading = true, error = "")
        _view.value = try {
            val apps = api.apps()
            ConnectTools.rememberApps(apps)
            _view.value.copy(apps = apps, loading = false, loadedAt = System.currentTimeMillis())
        } catch (e: ArgusConnectApi.ConnectError) { _view.value.copy(loading = false, error = e.message) }
        _view.value
    }

    private suspend fun guarded(id: String, ok: String, block: () -> Unit): String = withContext(Dispatchers.IO) {
        try { block(); status(id, ok); load(); ok }
        catch (e: ArgusConnectApi.ConnectError) { status(id, e.message); e.message }
        catch (e: IllegalArgumentException) { status(id, e.message.orEmpty()); e.message.orEmpty() }
    }

    suspend fun setEnabled(id: String, on: Boolean) = guarded(id, if (on) "On" else "Off") { api.setApp(id, enabled = on) }
    suspend fun setToolOn(app: ArgusConnectApi.AppInfo, tool: String, on: Boolean) =
        guarded(app.id, if (on) "$tool on" else "$tool off") {
            api.setApp(app.id, disabledTools = if (on) app.disabledTools - tool else app.disabledTools + tool) }
    suspend fun connectWithKey(id: String, token: String?, fields: Map<String, String>) = guarded(id, "Connected") { api.connectWithKey(id, token, fields) }
    suspend fun disconnect(id: String) = guarded(id, "Disconnected") { api.disconnect(id) }

    suspend fun test(id: String): String = withContext(Dispatchers.IO) {
        val s = try { api.test(id).let { if (it.ok) "Test passed" else "Test failed: ${it.detail}" } }
            catch (e: ArgusConnectApi.ConnectError) { e.message }
        status(id, s); s
    }

    /**
     * OAuth in the browser: argus-connect returns the provider page, the user consents, argus-connect saves the tokens
     * to this user and redirects to [RETURN_TO]?app=<id>&status=ok|error, which this loopback catches (5 min).
     * [onDone] runs with true when connected (ConnectCard resumes the paused turn with it, P4-CN-04).
     */
    suspend fun connectOAuth(ctx: Context, id: String, onDone: (Boolean) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val result = try {
            ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 300_000
                val url = api.oauthStart(id, RETURN_TO)
                UrlSafety.check(url)
                status(id, "Waiting for you in the browser…")
                withContext(Dispatchers.Main) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                val back = withTimeoutOrNull(300_000) {
                    server.accept().use { sock ->
                        val line = sock.getInputStream().bufferedReader().readLine().orEmpty()
                        val r = ConnectReturn.parse(line, id)
                        val html = ConnectReturn.page(r, BrandName.NAME)
                        sock.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html").toByteArray())
                        r
                    }
                }
                withContext(Dispatchers.Main) {
                    ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                }
                back ?: ConnectReturn.Result.TimedOut
            }
        } catch (e: ArgusConnectApi.ConnectError) { status(id, e.message); onDone(false); return@withContext e.message }
        catch (e: java.io.IOException) { val m = "Couldn't wait for the sign-in (${e.message})."; status(id, m); onDone(false); return@withContext m }
        api.dropToolCache()
        val ok = result == ConnectReturn.Result.Ok
        val msg = ConnectReturn.message(result)
        status(id, msg); load(); onDone(ok); msg
    }
}
