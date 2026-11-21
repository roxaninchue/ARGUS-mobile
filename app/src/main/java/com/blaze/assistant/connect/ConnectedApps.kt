package com.blaze.assistant.connect

import com.blaze.assistant.BrandName
import com.blaze.assistant.agent.AppShelf
import com.blaze.assistant.connect.ArgusAppsModel.App
import com.blaze.assistant.device.CrashLog
import com.blaze.assistant.team.ActionClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * App shelf, the phone side (docs/APP-SHELF.md). The account's apps from argus-connect ([ArgusApps], the list Settings ›
 * Apps & connectors shows) become the shelf in the prompt and the two tools [AppShelf.LIST] / [AppShelf.RUN].
 *
 *  - [promptBlock]: the shelf from the cached list (no network on the send path). A list older than [STALE_MS] is
 *    refreshed in the background, so the next turn sees apps connected on another device or the admin page.
 *  - [list]: one app's actions from argus-connect's MCP `tools/list` (`?apps=<id>`, stateless JSON-RPC, the account's
 *    session token), kept [TOOLS_TTL_MS]; with no app, the catalogue.
 *  - [run]: one action through `POST /v1/me/execute` with the account's session token. The approval card (if any) has
 *    already been answered by then: PolicyRuntime classes the call with [classOf] before it runs.
 *
 * Credentials never touch the phone: argus-connect holds them, encrypted, per account.
 */
object ConnectedApps {
    const val RUN = AppShelf.RUN
    const val STALE_MS = 30 * 60_000L
    const val TOOLS_TTL_MS = 10 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var refreshing = false

    private data class Cached(val at: Long, val actions: List<AppShelf.Action>)
    private val actions = ConcurrentHashMap<String, Cached>()

    /** Signed in to the ARGUS account: the tools work. Also flips [AppShelf.live] so the tools ride every turn. */
    fun on(): Boolean {
        val v = try { BlazeAccount.sessionToken().isNotBlank() } catch (_: Throwable) { false }
        AppShelf.live = v
        AppShelf.readOnlyOf = { t -> readOnly(t) }
        return v
    }

    /** The account's list (cached; refreshed by the Apps page and [promptBlock]). */
    fun apps(): List<App> {
        try { ArgusApps.loadCached() } catch (e: Throwable) { CrashLog.quiet("ConnectedApps.apps", e) }
        return ArgusApps.view.value.apps
    }

    /** The shelf for this turn (SLOW tier). Empty when signed out. */
    fun promptBlock(): String {
        val signed = on()
        if (!signed) return ""
        val v = ArgusApps.view.value
        if (!refreshing && System.currentTimeMillis() - v.loadedAt > STALE_MS) {
            refreshing = true
            scope.launch { try { ArgusApps.refresh() } catch (e: Throwable) { CrashLog.quiet("ConnectedApps.refresh", e) } finally { refreshing = false } }
        }
        return AppShelf.promptBlock(apps(), signed)
    }

    fun app(id: String): App? = apps().firstOrNull { it.id == id }

    /** The app [tool] belongs to, from the account's list. */
    fun appOf(tool: String): String? = AppShelf.appOf(tool, apps().map { it.id })

    /** Whether [tool] only reads: true / false from the app's action list, null when it hasn't been read yet. */
    fun readOnly(tool: String): Boolean? {
        val id = appOf(tool) ?: return null
        return actions[id]?.actions?.firstOrNull { it.name == tool }?.readOnly
    }

    /** The approval class of an [AppShelf.RUN] call for [tool]; reads the app's action list first when it isn't cached. */
    suspend fun classOf(tool: String): ActionClass {
        val id = appOf(tool)
        if (id != null && readOnly(tool) == null) withContext(Dispatchers.IO) {
            try { fetch(id) } catch (e: Throwable) { CrashLog.quiet("ConnectedApps.classOf", e) } }
        return AppShelf.classOf(tool, readOnly(tool), id)
    }

    /** [AppShelf.LIST]: one app's actions, or with a blank [appId] the catalogue. */
    suspend fun list(appId: String): String = withContext(Dispatchers.IO) {
        if (!on()) return@withContext "Sign in to the ${BrandName.NAME} account to use connected apps."
        val id = appId.trim().lowercase()
        if (id.isEmpty()) {
            if (ArgusApps.view.value.apps.isEmpty()) ArgusApps.refresh()
            return@withContext AppShelf.renderCatalogue(apps())
        }
        val a = app(id) ?: return@withContext "No app with the id \"$id\". $LIST_HINT"
        if (!a.enabled) return@withContext "${a.name} is switched off in Apps & connectors. The user can switch it on there."
        if (!AppShelf.usable(a)) return@withContext "${a.name} isn't connected yet. request_app_connection asks the user to connect it."
        val (err, list) = fetch(id)
        if (err.isNotEmpty()) err else AppShelf.renderActions(id, list)
    }

    private const val LIST_HINT = "app_tools with no app lists every app."

    /** Reads [id]'s actions (cached [TOOLS_TTL_MS]). Returns (error, actions). */
    private fun fetch(id: String): Pair<String, List<AppShelf.Action>> {
        val now = System.currentTimeMillis()
        actions[id]?.takeIf { now - it.at < TOOLS_TTL_MS }?.let { return "" to it.actions }
        val req = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list").put("params", JSONObject())
        val (code, body) = ArgusApps.call("POST", "/mcp?apps=" + Http.enc(id), req)
        if (code !in 200..299) return ArgusApps.errorText(code, body) to emptyList()
        val o = try { JSONObject(body) } catch (_: Exception) { null }
        o?.optJSONObject("error")?.let { e -> return e.optString("message").ifBlank { "argus-connect refused tools/list" } to emptyList() }
        val list = AppShelf.parseActions(o?.optJSONObject("result"))
        actions[id] = Cached(now, list)
        return "" to list
    }

    /** [AppShelf.RUN]: one action with [arguments]. */
    suspend fun run(tool: String, arguments: JSONObject): String = withContext(Dispatchers.IO) {
        if (!on()) return@withContext "Sign in to the ${BrandName.NAME} account to use connected apps."
        val t = tool.trim()
        if (t.isEmpty()) return@withContext "Error: app_run needs the action's full name (from app_tools)."
        val (code, body) = ArgusApps.call("POST", "/v1/me/execute", JSONObject().put("name", t).put("arguments", arguments))
        AppShelf.renderResult(t, appOf(t), code, body)
    }

    /** The step line for a call: "Notion · search". */
    fun step(tool: String): String = AppShelf.step(tool, appOf(tool)?.let { app(it) })

    /** The user connected or disconnected something: drop the cached action lists. */
    fun invalidate() = actions.clear()
}
