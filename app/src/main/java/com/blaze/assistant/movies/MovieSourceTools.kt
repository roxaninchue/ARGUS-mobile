package com.blaze.assistant.movies

import com.blaze.assistant.BrandName
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.55.13 agent tool `movie_sources`: the Providers screen of Argus Movie from the chat. A catalog tool (docs/LEAN-TOOLS.md):
 * it is never in a turn's list until `find_tools` finds it. Before this, Argus told the user it "couldn't add a provider"
 * and guessed a website URL that the Add source field can't read (phone report 2026-11-24).
 *
 * A source is a repo folder with manifest.json (Vega-format provider layout, see [ProviderRepo]): "owner/repo", a GitHub or
 * GitLab link, or a raw folder URL. A streaming website or an app's homepage is not a source. Argus suggests no source
 * (MoviesLegalTest): the repo comes from the user, or from a web search the user asked for.
 */
object MovieSourceTools {
    const val TOOL = "movie_sources"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "${BrandName.NAME} Movie sources (Providers): list, add a provider repo, remove, turn on/off, test, update. " +
            "add takes a repo with manifest.json: owner/repo, a GitHub/GitLab link or a folder URL, not a website or app homepage; " +
            "if you only know a name, find its GitHub provider repo with web_search first. Built in: YouTube, the phone's videos, Jellyfin.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("list", "add", "remove", "enable", "disable", "test", "update", "open"))))
            .put("url", JSONObject().put("type", "string").put("description", "add / remove: the repo (owner/repo, GitHub or GitLab link, folder URL)"))
            .put("provider", JSONObject().put("type", "string").put("description", "enable / disable / test / remove: a provider's name or id")))
            .put("required", JSONArray(listOf("action")))))

    fun describe(a: JSONObject): String = when (a.optString("action")) {
        "add" -> "Adding the source"; "remove" -> "Removing the source"; "test" -> "Testing the provider"; "update" -> "Checking for updates"
        "enable", "disable" -> "Switching the provider"; "open" -> "Opening Providers"; else -> "Looking at your sources"
    }

    /** What a failed add says, so the model can fix it on the next step instead of guessing. Pure; MovieSourceToolsTest. */
    fun addFailed(input: String, why: String): String =
        "Couldn't add \u201c${input.take(120)}\u201d: $why. A source must be a repo folder with manifest.json " +
            "(owner/repo, a GitHub or GitLab repo link, or a raw folder URL). A website or an app's download page isn't one: " +
            "find the project's provider repo (web_search \"<name> providers github manifest.json\") or ask the user for the repo link."

    /** One line per provider for list. Pure; MovieSourceToolsTest. */
    fun listText(st: ProviderStore.State, jellyfin: String?): String = buildString {
        append("Built in: YouTube, the phone's videos").append(if (jellyfin != null) ", Jellyfin ($jellyfin)" else "").append(".")
        if (st.repos.isEmpty() && st.installed.isEmpty()) { append(" No added sources."); return@buildString }
        st.repos.forEach { r ->
            append("\nSource ").append(r.label).append(" (").append(r.base).append("):")
            val mine = st.installed.filter { it.repo == r.base }
            if (mine.isEmpty()) append(" no providers installed")
            mine.forEach { i -> append("\n- ").append(i.entry.name).append(" [").append(i.value).append("] ").append(if (i.enabled) "on" else "off")
                .append(", ").append(i.status.label.lowercase()).append(if (i.detail.isNotBlank()) " (${i.detail.take(80)})" else "") }
        }
    }

    /** A provider by its id or display name (case-insensitive), else null. Pure. */
    fun findProvider(st: ProviderStore.State, q: String): ProviderStore.Installed? {
        val t = q.trim(); if (t.isEmpty()) return null
        return st.installed.firstOrNull { it.value.equals(t, true) } ?: st.installed.firstOrNull { it.entry.name.equals(t, true) }
            ?: st.installed.singleOrNull { it.entry.name.contains(t, true) || it.value.contains(t, true) }
    }

    suspend fun run(ctx: Context, a: JSONObject): String = withContext(Dispatchers.IO) {
        Movies.init(ctx)
        val store = Movies.providers(ctx)
        val prov = a.optString("provider"); val url = a.optString("url").trim()
        when (a.optString("action")) {
            "list" -> listText(store.state, Movies.jellyfinAccount()?.server)
            "add" -> {
                if (url.isBlank()) return@withContext "Which source? Give the repo (owner/repo or a GitHub / GitLab link)."
                val first = SourceNotice.shouldShow(store.state.noticeSeen)
                try {
                    val before = store.state.installed.map { it.value }.toSet()
                    val n = store.addAndInstallAll(url)
                    if (first) store.markNoticeSeen()
                    val added = store.state.installed.filter { it.value !in before }
                    Movies.gen.value++
                    (if (n == 0) "Added the source, but its manifest lists no provider that's switched on." else
                        "Added the source: ${added.joinToString(", ") { it.entry.name }.ifBlank { "$n providers" }}. They now show in ${BrandName.NAME} Movie (Home, Search, Sources).") +
                        (if (first) " Tell the user once: ${SourceNotice.TEXT}" else "")
                } catch (e: Exception) { addFailed(url, e.message ?: "error") }
            }
            "remove" -> {
                findProvider(store.state, prov.ifBlank { url })?.let { store.remove(it.value); Movies.gen.value++; return@withContext "Removed ${it.entry.name}." }
                val repo = store.state.repos.firstOrNull { url.isNotBlank() && (it.base.equals(url, true) || it.label.equals(url, true) || it.base.contains(url.removePrefix("https://").trimEnd('/'), true)) }
                    ?: return@withContext "No added source or provider like \u201c${prov.ifBlank { url }}\u201d. " + listText(store.state, null)
                store.removeRepo(repo.base); Movies.gen.value++; "Removed the source ${repo.label} and its providers."
            }
            "enable", "disable" -> {
                val i = findProvider(store.state, prov) ?: return@withContext "No provider like \u201c$prov\u201d. " + listText(store.state, null)
                val on = a.optString("action") == "enable"
                store.setEnabled(i.value, on); Movies.gen.value++; "${i.entry.name} is ${if (on) "on" else "off"}."
            }
            "test" -> {
                val i = findProvider(store.state, prov) ?: return@withContext "No provider like \u201c$prov\u201d. " + listText(store.state, null)
                val st = store.test(i.value)
                "${i.entry.name}: ${st.label}" + (store.state.installed.firstOrNull { it.value == i.value }?.detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + "."
            }
            "update" -> try { val n = store.refresh(); Movies.gen.value++; if (n == 0) "Every provider is up to date." else "Updated $n providers." }
                catch (e: Exception) { "Couldn't check for updates: ${e.message}" }
            "open" -> withContext(Dispatchers.Main) { com.blaze.assistant.mediaapps.VideoActivity.open(ctx, "sources"); "Opened Sources in ${BrandName.NAME} Movie." }
            else -> "Unknown action."
        }
    }
}
