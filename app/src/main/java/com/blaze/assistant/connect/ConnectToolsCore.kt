package com.blaze.assistant.connect

import org.json.JSONObject

/**
 * P4-CN-03: the pure rules behind [ConnectTools] (naming, per-bot filter, the 64-schema cap and its order, the
 * per-call gate, connectors_list text). No Android, no network: ToolCapTest / ReadOnlySlugTest / ConnectGateTest.
 */
object ConnectToolsCore {
    const val PREFIX = "app__"
    const val LIST = "connectors_list"
    const val CAP = 64

    fun handles(name: String) = name.startsWith(PREFIX) || name == LIST
    fun serverName(name: String) = name.removePrefix(PREFIX)

    /** What the client knows about one server tool (from /v1/me/apps). */
    data class Meta(val full: String, val appId: String, val appName: String, val readOnly: Boolean, val connected: Boolean)

    fun metaOf(apps: List<ArgusConnectApi.AppInfo>): Map<String, Meta> = apps.flatMap { a ->
        a.tools.map { t -> t.full to Meta(t.full, a.id, a.name, t.readOnly, a.connected || a.auth.none) } }.toMap()

    /** The app a server tool belongs to: the catalog's answer, else the longest app id that prefixes the name. */
    fun appOf(full: String, meta: Map<String, Meta>, appIds: Collection<String>): String? =
        meta[full]?.appId ?: appIds.filter { full.startsWith(it + "_") }.maxByOrNull { it.length }

    fun fnName(t: JSONObject) = t.optJSONObject("function")?.optString("name").orEmpty()

    /**
     * The connector schemas for one request. [server] = /v1/me/tools (OpenAI defs, server names); [allowed] = may this
     * bot use the app; [cued] = apps the request names; [loaded] = apps the model asked for with connectors_list.
     * Returns at most [cap] defs with the `app__` prefix, in priority order, plus how many were left out.
     */
    fun select(server: List<JSONObject>, meta: Map<String, Meta>, allowed: (String) -> Boolean, cued: Set<String> = emptySet(),
               loaded: Set<String> = emptySet(), cap: Int = CAP): Pair<List<JSONObject>, Int> {
        val ids = meta.values.map { it.appId }.toSet()
        val usable = server.mapNotNull { t ->
            val n = fnName(t); val app = appOf(n, meta, ids) ?: return@mapNotNull null
            if (!allowed(app)) null else Triple(t, n, app)
        }
        fun rank(app: String, n: String): Int {
            val m = meta[n]
            return (if (app in loaded) 0 else 8) + (if (app in cued) 0 else 4) + (if (m?.connected != false) 0 else 2) + (if (m?.readOnly == true) 0 else 1)
        }
        val ordered = usable.withIndex().sortedWith(compareBy({ rank(it.value.third, it.value.second) }, { it.index })).map { it.value }
        val kept = ordered.take(cap.coerceAtLeast(0)).map { (t, n, _) -> prefixed(t, n) }
        return kept to (ordered.size - kept.size)
    }

    private fun prefixed(t: JSONObject, n: String): JSONObject {
        val f = t.optJSONObject("function") ?: JSONObject()
        val nf = JSONObject(f.toString()).put("name", PREFIX + n)
        return JSONObject().put("type", "function").put("function", nf)
    }

    /** Apps whose name or id the text mentions (word match, any case). */
    fun cuedApps(text: String, apps: List<ArgusConnectApi.AppInfo>): Set<String> {
        val t = text.lowercase()
        return apps.filter { a ->
            listOf(a.id.replace('_', ' '), a.name.lowercase()).any { k -> k.length >= 3 && Regex("\\b" + Regex.escape(k) + "\\b").containsMatchIn(t) }
        }.map { it.id }.toSet()
    }

    /** The per-call decision: refuse (the tool is Off), ask (card), or run. */
    sealed class Gate { object Run : Gate(); data class Ask(val title: String, val detail: String, val button: String) : Gate(); data class Refuse(val why: String) : Gate() }

    fun gate(full: String, args: JSONObject, meta: Meta?, grant: AppGrants.Grant?, tainted: Boolean): Gate {
        if (grant == null) return Gate.Refuse("This bot isn't allowed to use ${meta?.appName ?: "that app"}. The user can add it in the bot's profile › Apps.")
        val tool = meta?.let { full.removePrefix(it.appId + "_") } ?: full
        val rule = grant.rule(tool) ?: grant.rule(full)
        if (rule == ConnectRisk.Rule.NEVER) return Gate.Refuse("The user turned $tool off for this bot.")
        val action = ConnectRisk.classify(full, meta?.readOnly?.takeIf { it } ?: if (meta == null) null else false)
        if (!ConnectRisk.needsCard(action, rule, tainted)) return Gate.Run
        val app = meta?.appName ?: full.substringBefore('_').replaceFirstChar { it.uppercase() }
        val shown = args.toString(2).let { if (it.length > 900) it.take(900) + "\n…" else it }
        return Gate.Ask("Allow $app · ${tool.replace('_', ' ')}?", "This ${ConnectRisk.words(action)} in $app.\n$shown",
            when (action) { ConnectRisk.Action.SEND -> "Send"; ConnectRisk.Action.DELETE -> "Delete"; ConnectRisk.Action.SPEND -> "Pay"; else -> "Allow" })
    }

    fun listText(apps: List<ArgusConnectApi.AppInfo>, allowed: (String) -> Boolean, inPrompt: Set<String>, app: String?): String {
        if (apps.isEmpty()) return "No apps yet. The user can connect apps in Settings › Apps & connectors (signed in to their ARGUS account)."
        if (!app.isNullOrBlank()) {
            val a = apps.firstOrNull { it.id.equals(app, true) || it.name.equals(app, true) } ?: return "No app called $app. Call $LIST with no app to see them all."
            if (!allowed(a.id)) return "This bot isn't allowed to use ${a.name}. The user can add it in the bot's profile › Apps."
            if (a.needsConnect) return "${a.name} isn't connected yet. Ask the user to connect it (Settings › Apps & connectors), then try again."
            val on = a.tools.filter { it.name !in a.disabledTools }
            return "${a.name} tools (available from your next step):\n" + on.joinToString("\n") { "- $PREFIX${it.full}: ${it.description.take(140)}${if (it.readOnly) " (read)" else ""}" }
        }
        return "Apps (✓ connected, ○ needs connecting, — off):\n" + apps.sortedWith(compareBy({ !it.connected }, { it.name })).joinToString("\n") { a ->
            val mark = if (!a.enabled) "—" else if (a.ready) "✓" else "○"
            val note = when { !allowed(a.id) -> " · not allowed for this bot"; a.id in inPrompt -> " · tools loaded"; a.ready -> " · call $LIST app=${a.id} to load its tools"; else -> "" }
            "$mark ${a.name} (${a.id}, ${a.category}, ${(a.tools.size - a.disabledTools.size).let { if (it == 1) "1 tool" else "$it tools" }})$note"
        }
    }

    const val PROMPT = "Apps through argus-connect: use the app__ tools (or $LIST to see and load more). Never reach these apps with the shell, curl, environment variables, a pasted token or the browser; their credentials live only on argus-connect. If an app isn't connected, say so and ask the user to connect it in Settings › Apps & connectors."
}
