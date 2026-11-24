package com.blaze.assistant.movies


import com.blaze.assistant.BrandName
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.46.2 agent tool `movies`: Argus Movie from the chat. Everything lands on the Stage video card (shown in place; its
 * open-in-full-view button goes to Argus Movie). 0.55.13: built-in sources are YouTube, the user's own videos and their
 * Jellyfin server (Internet Archive and Blender removed). The user's added providers join when the model asks for them
 * (use_providers, or source = a provider's name); no regex on the user's words decides that any more (docs/MODEL-FIRST.md).
 * Adding / removing sources is the separate catalog tool `movie_sources`.
 */
object MovieTools {
    const val TOOL = "movies"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", TOOL)
        .put("description", "${BrandName.NAME} Movie: find and play movies, shown on the Stage video card. action: play (query = title), search (query), " +
            "continue (resume the last unfinished one), watchlist. Sources: YouTube, the user's videos, Jellyfin; providers the user added " +
            "join with use_providers=true or source=<provider name>. Add or remove sources: movie_sources.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("play", "search", "continue", "watchlist"))))
            .put("query", JSONObject().put("type", "string"))
            .put("source", JSONObject().put("type", "string").put("description", "optional: youtube | local | jellyfin | a provider's name"))
            .put("use_providers", JSONObject().put("type", "boolean")))
            .put("required", JSONArray(listOf("action")))))

    fun describe(a: JSONObject): String = when (a.optString("action")) { "play" -> "Finding the movie"; "search" -> "Searching movies"; "continue" -> "Picking up where you left off"; else -> "Movies" }

    /** Providers join when the model asked (use_providers) or named one as the source. Pure; MovieToolsTest. */
    fun allowProviders(a: JSONObject, installedNames: List<String>): Boolean =
        a.optBoolean("use_providers") || installedNames.any { it.equals(a.optString("source").trim(), true) }

    suspend fun run(ctx: Context, a: JSONObject): String = withContext(Dispatchers.IO) {
        Movies.init(ctx)
        val installed = runCatching { Movies.providers().state.installed.map { it.entry.name } }.getOrDefault(emptyList())
        val pool = MovieHub.allowed(Movies.sources(), allowProviders(a, installed), a.optString("source"))
        when (a.optString("action")) {
            "play" -> play(ctx, pool, a.optString("query"))
            "search" -> search(pool, a.optString("query"))
            "continue" -> resume(ctx)
            "watchlist" -> Movies.lib.value.watchlist.let { w -> if (w.isEmpty()) "The watchlist is empty." else "Watchlist: " + w.take(15).joinToString("; ") { it.title } }
            else -> "Unknown action."
        }
    }

    suspend fun play(ctx: Context, pool: List<MovieSource>, query: String): String {
        if (query.isBlank()) return "Which movie?"
        val s = MovieHub.search(pool, query)
        s.results.forEach { Movies.note(it.source, it.error) }
        val pick = MovieHub.best(s.posts, query)
        if (pick == null) {
            if (s.posts.isNotEmpty()) { withContext(Dispatchers.Main) { StageMovies.showResults(query, s) }; return "No exact match for \u201c$query\u201d; the closest results are on the video card." + failed(s) }
            return "Couldn't find \u201c$query\u201d in " + pool.joinToString(", ") { it.name }.ifBlank { "any source" } + "." + failed(s)
        }
        val src = pool.first { it.id == pick.source }
        val play = try { MovieHub.resolve(src, pick, Movies.maxHeight(ctx)).also { Movies.note(src, null) } }
            catch (e: Exception) { Movies.note(src, e.message); return "Found \u201c${pick.title}\u201d but couldn't play it: ${e.message}" }
        return withContext(Dispatchers.Main) { show(ctx, play) } + " Source: ${src.name}."
    }

    suspend fun search(pool: List<MovieSource>, query: String): String {
        if (query.isBlank()) return "What should I search for?"
        val s = MovieHub.search(pool, query)
        s.results.forEach { Movies.note(it.source, it.error) }
        withContext(Dispatchers.Main) { StageMovies.showResults(query, s) }
        if (s.posts.isEmpty()) return "Nothing for \u201c$query\u201d." + failed(s)
        return "On the video card: " + s.posts.take(8).joinToString("; ") { it.title + (if (it.tag.isNotBlank()) " (${it.tag})" else "") } + "." + failed(s)
    }

    suspend fun resume(ctx: Context): String {
        val pr = Movies.lib.value.continueWatching.firstOrNull() ?: return "Nothing to continue: no unfinished movie."
        val src = Movies.source(pr.play.source)
        val p = try { if (pr.play.url.isNotBlank()) pr.play else MovieHub.refresh(src ?: return "That movie's source isn't available any more.", pr.play, Movies.maxHeight(ctx)) }
            catch (e: Exception) { return "Couldn't continue \u201c${pr.play.title}\u201d: ${e.message}" }
        return withContext(Dispatchers.Main) { show(ctx, p) } + " From " + com.blaze.assistant.mediaapps.TimeText.clock(pr.posMs) + "."
    }

    private fun show(ctx: Context, p: MoviePlay): String =
        if (com.blaze.assistant.stage.StageStore.enabled) StageMovies.show(p)
        else { com.blaze.assistant.mediaapps.VideoActivity.openMovie(ctx, p); "Playing \u201c${p.title}\u201d in ${BrandName.NAME} Movie." }

    private fun failed(s: MovieHub.Search) = if (s.failed.isEmpty()) "" else " (Didn't answer: " + s.failed.joinToString(", ") { it.source.name } + ".)"
}
