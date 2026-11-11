package com.blaze.assistant.connect

import com.blaze.assistant.agent.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.blaze.assistant.connect.ConnectToolsCore.Gate
import com.blaze.assistant.connect.ConnectToolsCore.Meta
import com.blaze.assistant.connect.ConnectToolsCore.appOf
import com.blaze.assistant.connect.ConnectToolsCore.cuedApps
import com.blaze.assistant.connect.ConnectToolsCore.fnName
import com.blaze.assistant.connect.ConnectToolsCore.gate
import com.blaze.assistant.connect.ConnectToolsCore.listText
import com.blaze.assistant.connect.ConnectToolsCore.metaOf
import com.blaze.assistant.connect.ConnectToolsCore.select
import org.json.JSONObject

/**
 * P4-CN-03: argus-connect tools in the agent's tool list.
 *
 * - Names: the server's tool name with an `app__` prefix (`app__github_list_repos`), so they never collide with the
 *   on-phone device connectors (`github_*`, `gmail_*`) or MCP (`mcp__*`). [handles] / [run] strip it again.
 * - Per bot: only apps [AppGrants] lets the bot use (the Manager and the plain chat see every enabled app).
 * - Cap: at most [CAP] connector schemas per request (ToolCapTest), connected apps first, then apps the request names,
 *   then apps the model loaded with `connectors_list app=…`; the rest stay reachable through `connectors_list`.
 * - Reads run straight away; anything else goes through the agent's confirm card (ConnectRisk, Agent.confirm), and
 *   app output marks the turn tainted (ConfirmGate.sourceOf `app__*`).
 * - Never blocks the turn on the network: the tool list is the 5-minute cache, refreshed in the background.
 *
 * Wired through [Connectors] (tools / handles / run / promptBlock), so Agent.kt needs no new lines.
 */
object ConnectTools {
    const val PREFIX = ConnectToolsCore.PREFIX
    const val LIST = ConnectToolsCore.LIST
    const val CAP = ConnectToolsCore.CAP
    const val PROMPT = ConnectToolsCore.PROMPT

    fun handles(name: String) = ConnectToolsCore.handles(name)
    fun serverName(name: String) = ConnectToolsCore.serverName(name)

    // ------------------------------------------------------------------ phone glue

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var meta: Map<String, Meta> = emptyMap()
    @Volatile private var apps: List<ArgusConnectApi.AppInfo> = emptyList()
    @Volatile private var appsAt = 0L
    @Volatile private var refreshing = false
    /** Apps the model loaded with connectors_list this process (kept to the last 6). */
    private val loaded = LinkedHashSet<String>()
    /** Apps whose tools went into the last request (for connectors_list's notes). */
    @Volatile private var lastInPrompt: Set<String> = emptySet()
    /** The request text of the current turn (set by ToolRouter.guess), for [cuedApps]. */
    @Volatile var lastText: String = ""

    /**
     * Seam for P1/P3: the bot whose turn is running. Null = ARGUS / the plain chat. The team runtime (LocalEngine
     * TeamHooks) sets it around a bot's turn; until then every turn is the Manager's.
     */
    @Volatile var currentBot: String? = null

    private const val GRANTS = "bot_app_grants"
    val grantStore = object : AppGrants.Store {
        override fun read() = Settings.str(GRANTS)
        override fun write(v: String) = Settings.setStr(GRANTS, v)
    }
    fun grants(): Map<String, AppGrants.Grants> = AppGrants.parse(grantStore.read())
    fun saveGrants(all: Map<String, AppGrants.Grants>) { grantStore.write(AppGrants.encode(all)); ArgusConnect.api.dropToolCache() }

    fun rememberApps(list: List<ArgusConnectApi.AppInfo>) { apps = list; meta = metaOf(list); appsAt = System.currentTimeMillis() }

    private fun refreshSoon() {
        if (refreshing || !ArgusConnect.api.signedIn()) return
        refreshing = true
        scope.launch {
            try {
                if (System.currentTimeMillis() - appsAt > ArgusConnectApi.CACHE_MS) rememberApps(ArgusConnect.api.apps())
                ArgusConnect.api.tools()
            } catch (_: Exception) { } finally { refreshing = false }
        }
    }

    /** Cached server tools without touching the network; a stale or empty cache starts a background refresh. */
    private fun cachedServerTools(): List<JSONObject> {
        val c = ArgusConnect.api.cachedTools()
        if (c == null || System.currentTimeMillis() - appsAt > ArgusConnectApi.CACHE_MS) refreshSoon()
        return c ?: emptyList()
    }

    /** The schemas for this request (called from Connectors.tools() while the tool list is built). */
    fun tools(): List<JSONObject> {
        if (!ArgusConnect.on || !ArgusConnect.api.signedIn()) return emptyList()
        val server = cachedServerTools()
        val all = grants(); val bot = currentBot
        val allowed = { app: String -> AppGrants.allows(all, bot, app) }
        val (kept, _) = select(server, meta, allowed, cuedApps(lastText, apps), synchronized(loaded) { loaded.toSet() })
        lastInPrompt = kept.mapNotNull { appOf(serverName(fnName(it)), meta, meta.values.map { m -> m.appId }.toSet()) }.toSet()
        val list = JSONObject().put("type", "function").put("function", JSONObject().put("name", LIST)
            .put("description", "List the user's apps (argus-connect), or load one app's tools for your next step with app=<id>.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("app", JSONObject().put("type", "string").put("description", "an app id or name to load its tools")))))
        return listOf(list) + kept
    }

    /** Plan mode (Modes.planBlock): connectors_list, and tools the catalog marks read-only. */
    fun readOnly(name: String): Boolean = name == LIST || meta[serverName(name)]?.let { ConnectRisk.classify(it.full, it.readOnly) == ConnectRisk.Action.READ } == true

    /** ToolRouter.guess: the request names one of the user's apps (no network, the last app list). */
    fun cued(text: String): Boolean = apps.isNotEmpty() && cuedApps(text, apps).isNotEmpty()

    fun promptLine(): String = if (!ArgusConnect.on || !ArgusConnect.api.signedIn()) "" else "\n$PROMPT\n"

    fun describe(name: String): String = if (name == LIST) "Checking your apps"
        else "Using " + (meta[serverName(name)]?.appName ?: serverName(name).substringBefore('_').replaceFirstChar { it.uppercase() })

    /** Runs a connector tool for the current bot; writes ask first through the agent's confirm card. */
    suspend fun run(name: String, args: JSONObject): String = withContext(Dispatchers.IO) {
        if (!ArgusConnect.api.signedIn()) return@withContext "Apps need the user's ARGUS account. Ask them to sign in (Settings › Account)."
        val all = grants(); val bot = currentBot
        if (name == LIST) {
            if (System.currentTimeMillis() - appsAt > 60_000) runCatching { rememberApps(ArgusConnect.api.apps()) }
            val app = args.optString("app").takeIf { it.isNotBlank() }
            val a = app?.let { q -> apps.firstOrNull { it.id.equals(q, true) || it.name.equals(q, true) } }
            if (a != null && AppGrants.allows(all, bot, a.id) && a.ready) synchronized(loaded) { loaded.remove(a.id); loaded.add(a.id); while (loaded.size > 6) loaded.remove(loaded.first()) }
            return@withContext listText(apps, { AppGrants.allows(all, bot, it) }, lastInPrompt, app)
        }
        val full = serverName(name)
        val m = meta[full]
        val appId = appOf(full, meta, meta.values.map { it.appId }.toSet()) ?: full.substringBefore('_')
        val tainted = kotlin.coroutines.coroutineContext[com.blaze.assistant.agent.ConfirmGate.Taint]?.source != null
        when (val g = gate(full, args, m, AppGrants.grantFor(all, bot, appId), tainted)) {
            is Gate.Refuse -> return@withContext "Refused: ${g.why}"
            is Gate.Ask -> if (!com.blaze.assistant.agent.Agent.confirm(g.title, g.detail, g.button, always = true))
                return@withContext "The user did NOT approve $full. Don't retry it; say what you were about to do and ask how they'd like to proceed."
            Gate.Run -> { }
        }
        val r = ArgusConnect.api.execute(full, args)
        if (r.ok) r.text else "Couldn't run $full: ${r.text}"
    }
}
