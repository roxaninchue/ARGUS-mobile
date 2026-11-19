package com.argus.assistant.connect

import org.json.JSONArray
import org.json.JSONObject

/**
 * The apps and connectors your ARGUS gateway offers (argus-connect `GET /v1/me/apps`), as plain data.
 * Pure (org.json only), so parsing, search, grouping and the icon lookup are unit-tested (ArgusAppsModelTest).
 * The network side is [ArgusApps]; the page is Settings › Apps & connectors (ConnectionsPage).
 */
object ArgusAppsModel {

    enum class Auth { OAUTH, TOKEN, FIELDS, NONE }

    data class Field(val key: String, val label: String, val secret: Boolean, val optional: Boolean)

    data class App(
        val id: String,
        val name: String,
        val category: String,
        val description: String,
        val setup: String = "",
        val free: String = "",
        /** OAuth provider id (google, microsoft, github, …) or "" when the app has no OAuth sign-in. */
        val oauthProvider: String = "",
        /** The gateway admin has set up this provider's OAuth client, so Connect works. */
        val oauthReady: Boolean = false,
        /** Label of the pasted key/token ("API key", "Personal access token"), or "" when there is none. */
        val tokenLabel: String = "",
        val tokenOptional: Boolean = false,
        val fields: List<Field> = emptyList(),
        /** Keyless public app: works without connecting. */
        val none: Boolean = false,
        val enabled: Boolean = false,
        val connected: Boolean = false,
        /** "oauth" or "pasted" when connected. */
        val via: String = "",
        /** "…abcd" for a pasted token, never the token. */
        val tokenHint: String = "",
        val tools: Int = 0,
        val hasTest: Boolean = false,
        /** Optional https icon URL from the gateway (a later argus-connect may send one); bundled icons win. */
        val iconUrl: String = "",
    ) {
        val oauth: Boolean get() = oauthProvider.isNotEmpty()
        val pastes: Boolean get() = tokenLabel.isNotEmpty() || fields.isNotEmpty()
        /** How Connect works for this app, in the order the page offers them. */
        val auth: Auth get() = when {
            none -> Auth.NONE
            oauth && oauthReady -> Auth.OAUTH
            fields.isNotEmpty() -> Auth.FIELDS
            tokenLabel.isNotEmpty() -> Auth.TOKEN
            oauth -> Auth.OAUTH
            else -> Auth.NONE
        }
        /** Usable right now: connected, or keyless and switched on. */
        val ready: Boolean get() = connected || (none && enabled)
    }

    fun parse(json: String): List<App> {
        if (json.isBlank()) return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("apps") ?: return emptyList()
        val out = ArrayList<App>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseApp(o)?.let { out.add(it) }
        }
        return out
    }

    private fun parseApp(o: JSONObject): App? {
        val id = o.optString("id").trim()
        if (id.isEmpty() || !ID.matches(id)) return null
        val auth = o.optJSONObject("auth") ?: JSONObject()
        val oauth = auth.optJSONObject("oauth")
        val token = auth.optJSONObject("token")
        val fields = auth.optJSONArray("fields") ?: JSONArray()
        val fl = (0 until fields.length()).mapNotNull { k ->
            fields.optJSONObject(k)?.let { f ->
                val key = f.optString("key").trim()
                if (key.isEmpty()) null else Field(key, f.optString("label", key), f.optBoolean("secret"), f.optBoolean("optional"))
            }
        }
        val conn = o.optJSONObject("connection")
        val meta = conn?.optJSONObject("meta")
        val icon = o.optString("icon").trim().takeIf { it.startsWith("https://") } ?: ""
        return App(
            id = id,
            name = o.optString("name", id).ifBlank { id },
            category = o.optString("category").ifBlank { "Other" },
            description = o.optString("description"),
            setup = o.optString("setup"),
            free = o.optString("free"),
            oauthProvider = oauth?.optString("provider").orEmpty(),
            oauthReady = oauth?.optBoolean("ready", false) ?: false,
            tokenLabel = token?.optString("label", "API key")?.ifBlank { "API key" }.orEmpty(),
            tokenOptional = token?.optBoolean("optional") ?: false,
            fields = fl,
            none = auth.optBoolean("none"),
            enabled = o.optBoolean("enabled"),
            connected = o.optBoolean("connected"),
            via = meta?.optString("via").orEmpty(),
            tokenHint = meta?.optString("token_hint").orEmpty(),
            tools = o.optJSONArray("tools")?.length() ?: 0,
            hasTest = !o.isNull("test") && o.optString("test").isNotBlank(),
            iconUrl = icon,
        )
    }

    private val ID = Regex("^[a-z0-9_]{1,64}$")

    /**
     * Search (name, category, description, id) and group by category. Ready apps first inside a group, then by name;
     * groups with something connected first, then alphabetical. An empty [query] keeps everything.
     */
    fun grouped(apps: List<App>, query: String = ""): List<Pair<String, List<App>>> {
        val q = query.trim().lowercase()
        val hit = if (q.isEmpty()) apps else apps.filter { a ->
            a.name.lowercase().contains(q) || a.category.lowercase().contains(q) || a.id.contains(q) || a.description.lowercase().contains(q)
        }
        return hit.groupBy { it.category }
            .map { (cat, list) -> cat to list.sortedWith(compareByDescending<App> { it.connected }.thenBy { it.name.lowercase() }) }
            .sortedWith(compareByDescending<Pair<String, List<App>>> { p -> p.second.any { it.connected } }.thenBy { it.first.lowercase() })
    }

    /** The status pill on an app row. */
    fun pill(a: App): String = when {
        a.connected -> "Connected"
        a.none -> if (a.enabled) "On" else "Off"
        a.auth == Auth.OAUTH && !a.oauthReady && !a.pastes -> "Soon"
        else -> "Connect"
    }

    /** The sub line on an app row. */
    fun sub(a: App): String {
        val tools = if (a.tools == 1) "1 tool" else "${a.tools} tools"
        val how = when {
            a.connected && a.via == "oauth" -> "signed in"
            a.connected && a.tokenHint.isNotEmpty() -> "key ${a.tokenHint}"
            a.none -> "no account needed"
            else -> ""
        }
        return listOf(a.description.trim(), listOf(tools, how).filter { it.isNotEmpty() }.joinToString(" \u00b7 "))
            .filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** Bundled brand icon (assets/connect-icons/<id>.png), or "" when the app has none and gets a letter tile. */
    fun iconAsset(id: String): String = if (id in BUNDLED) "connect-icons/$id.png" else ""

    /** The letter on a tile for an app without an icon. */
    fun initial(name: String): String = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

    /** Apps whose icon ships in assets/connect-icons (ArgusAppsModelTest checks every file is listed and the other way round). */
    val BUNDLED: Set<String> = setOf(
        "airtable", "arxiv", "asana", "bluesky", "brave_search", "calcom", "clickup", "cloudflare", "coingecko", "discord",
        "dropbox", "elevenlabs", "exa", "figma", "firecrawl", "gdelt", "github", "gitlab", "gmail", "google_calendar",
        "google_contacts", "google_docs", "google_drive", "google_sheets", "google_tasks", "hackernews", "hubspot",
        "huggingface", "jira", "linear", "mastodon", "microsoft_todo", "nasa", "notion", "npm", "ntfy", "onedrive",
        "open_meteo", "openlibrary", "openstreetmap", "outlook", "peopledatalabs", "pexels", "pypi", "raindrop", "reddit",
        "resend", "restcountries", "rss", "serpapi", "slack", "spotify", "stackexchange", "strava", "supabase", "tavily",
        "telegram", "tmdb", "todoist", "trello", "unsplash", "upstash_redis", "vercel", "whatsapp", "wikipedia", "youtube",
    )

    /** A few apps for screenshots (ShotsTest) and previews: no network. */
    val preview: List<App> = listOf(
        App("gmail", "Gmail", "Email", "Read, search, send and organise Gmail.", oauthProvider = "google", oauthReady = true, enabled = true, connected = true, via = "oauth", tools = 15, hasTest = true),
        App("github", "GitHub", "Developer", "Repos, issues, pull requests and code search.", oauthProvider = "github", oauthReady = true, tokenLabel = "Personal access token", tools = 25, hasTest = true),
        App("cloudflare", "Cloudflare", "Developer", "Workers, DNS, D1 and Pages.", tokenLabel = "API token", tools = 16, hasTest = true),
        App("notion", "Notion", "Notes & docs", "Search, read and write pages and databases.", oauthProvider = "notion", tokenLabel = "Internal integration secret", tools = 14),
        App("open_meteo", "Open-Meteo Weather", "Weather", "Forecasts and current weather anywhere.", none = true, enabled = true, tools = 5),
    )
}
