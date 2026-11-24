package com.blaze.assistant.movies

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 0.46.2 a place movies come from. Built in (0.55.13): YouTube ([YouTubeMovies]), the user's own videos, and a Jellyfin server
 * the user adds. Plus any open-source provider the user installed from a source repo they added ([JsProviderSource]).
 * [official] is false only for those user-installed providers. Internet Archive and Blender were removed in 0.55.13.
 */
interface MovieSource {
    val id: String
    val name: String
    val official: Boolean
    /** Home rows: (row title, posts). Empty rows are skipped by the UI. */
    suspend fun rows(): List<Pair<String, List<MoviePost>>>
    suspend fun search(query: String): List<MoviePost>
    suspend fun info(post: MoviePost): MovieInfo
    suspend fun episodes(link: String): List<MovieLink> = emptyList()
    suspend fun streams(link: MovieLink): List<MovieStream>
}

/** Small blocking HTTP GET for the official sources (a Jellyfin server); call off the main thread. */
object MovieHttp {
    @Volatile var override: ((String, Map<String, String>) -> String)? = null
    fun get(url: String, headers: Map<String, String> = emptyMap()): String = override?.invoke(url, headers) ?: send("GET", url, headers, null)
    fun post(url: String, headers: Map<String, String>, body: String): String = send("POST", url, headers, body)
    private fun send(method: String, url: String, headers: Map<String, String>, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12_000; c.readTimeout = 20_000; c.requestMethod = method
            c.setRequestProperty("User-Agent", "Blaze/1 (Android; +https://blazeinchue.pages.dev)")
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            val t = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(if (code == 401) "Sign-in failed (401)" else "The server answered $code")
            return t
        } finally { c.disconnect() }
    }
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

// ---------------------------------------------------------------- Jellyfin

/** A Jellyfin server the user added (URL + sign-in). Token kept encrypted by [MovieAccounts]; never sent anywhere else. */
class JellyfinSource(val server: String, val userId: String, private val token: String, val label: String = "Jellyfin") : MovieSource {
    override val id = "jellyfin"
    override val name get() = label
    override val official = true
    private val auth get() = mapOf("X-Emby-Authorization" to Jellyfin.authHeader(token))
    private fun get(path: String) = JSONObject(MovieHttp.get(server + path, auth))
    private fun items(o: JSONObject) = Jellyfin.posts(o, server, id)
    override suspend fun rows() = listOf(
        "Continue on Jellyfin" to runCatching { items(get("/Users/$userId/Items/Resume?Limit=20&MediaTypes=Video")) }.getOrDefault(emptyList()),
        "Latest on Jellyfin" to runCatching { Jellyfin.posts(JSONObject().put("Items", JSONArray(MovieHttp.get("$server/Users/$userId/Items/Latest?Limit=24", auth))), server, id) }.getOrDefault(emptyList()))
    override suspend fun search(query: String) = items(get("/Users/$userId/Items?Recursive=true&Limit=40&IncludeItemTypes=Movie,Series,Episode&searchTerm=" + MovieHttp.enc(query)))
    override suspend fun info(post: MoviePost): MovieInfo {
        val itemId = post.link.removePrefix("jellyfin:")
        val o = get("/Users/$userId/Items/$itemId")
        val series = o.optString("Type") == "Series"
        val groups = if (series) {
            val seasons = get("/Shows/$itemId/Seasons?UserId=$userId").optJSONArray("Items") ?: JSONArray()
            (0 until seasons.length()).mapNotNull { seasons.optJSONObject(it) }.map { s -> MovieGroup(s.optString("Name", "Season"), episodesLink = "jellyfin-season:$itemId:${s.optString("Id")}") }
        } else listOf(MovieGroup("Watch", direct = listOf(MovieLink("Play", "jellyfin:$itemId"))))
        return MovieInfo(o.optString("Name", post.title), o.optString("Overview"), Jellyfin.image(server, itemId), if (series) "series" else "movie", groups,
            (o.optJSONArray("Genres")?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty() + listOf(o.optString("ProductionYear"))).filter { it.isNotBlank() && it != "0" }.take(6),
            o.optString("OfficialRating"))
    }
    override suspend fun episodes(link: String): List<MovieLink> {
        val (show, season) = link.removePrefix("jellyfin-season:").split(':').let { it[0] to it.getOrElse(1) { "" } }
        val a = get("/Shows/$show/Episodes?UserId=$userId&SeasonId=$season").optJSONArray("Items") ?: JSONArray()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { e -> MovieLink((e.optInt("IndexNumber").takeIf { it > 0 }?.let { "$it. " } ?: "") + e.optString("Name"), "jellyfin:" + e.optString("Id"), "series", Jellyfin.image(server, e.optString("Id"))) }
    }
    override suspend fun streams(link: MovieLink): List<MovieStream> {
        val itemId = link.link.removePrefix("jellyfin:")
        val o = get("/Users/$userId/Items/$itemId")
        return Jellyfin.streams(o, server, itemId, token)
    }
}

object Jellyfin {
    const val CLIENT = "Blaze"; const val DEVICE = "Blaze Android"
    fun authHeader(token: String = "", deviceId: String = "blaze-android") =
        "MediaBrowser Client=\"$CLIENT\", Device=\"$DEVICE\", DeviceId=\"$deviceId\", Version=\"1\"" + if (token.isNotBlank()) ", Token=\"$token\"" else ""
    fun normalize(server: String): String? {
        var s = server.trim().trimEnd('/'); if (s.isEmpty()) return null
        if (!Regex("(?i)^https?://").containsMatchIn(s)) s = "http://$s"
        return try { val u = java.net.URI(s); if (u.host.isNullOrBlank()) null else s } catch (_: Exception) { null }
    }
    fun image(server: String, id: String) = "$server/Items/$id/Images/Primary?maxHeight=480"
    fun posts(o: JSONObject, server: String, source: String): List<MoviePost> {
        val a = o.optJSONArray("Items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { i ->
            val title = if (i.optString("Type") == "Episode" && i.optString("SeriesName").isNotBlank()) i.optString("SeriesName") + " \u00b7 " + i.optString("Name") else i.optString("Name")
            MoviePost(title, "jellyfin:" + i.optString("Id"), image(server, i.optString("Id")), source, i.optString("ProductionYear").takeIf { it != "0" }.orEmpty())
        }
    }
    /** (token, userId) from /Users/AuthenticateByName. */
    fun parseAuth(json: String): Pair<String, String> { val o = JSONObject(json); return o.getString("AccessToken") to o.getJSONObject("User").getString("Id") }
    fun streams(o: JSONObject, server: String, itemId: String, token: String): List<MovieStream> {
        val ms = o.optJSONArray("MediaSources")?.optJSONObject(0)
        val streams = ms?.optJSONArray("MediaStreams") ?: JSONArray()
        val msId = ms?.optString("Id").orEmpty().ifBlank { itemId }
        val subs = (0 until streams.length()).mapNotNull { streams.optJSONObject(it) }.filter { it.optString("Type") == "Subtitle" && it.optBoolean("IsTextSubtitleStream", true) }.map { s ->
            MovieSubtitle("$server/Videos/$itemId/$msId/Subtitles/${s.optInt("Index")}/0/Stream.vtt?api_key=$token", s.optString("Language"), s.optString("DisplayTitle").ifBlank { s.optString("Language") })
        }
        val h = (0 until streams.length()).mapNotNull { streams.optJSONObject(it) }.firstOrNull { it.optString("Type") == "Video" }?.optInt("Height") ?: 0
        return listOf(
            MovieStream("Jellyfin (original)", "$server/Videos/$itemId/stream?static=true&MediaSourceId=$msId&api_key=$token", ms?.optString("Container").orEmpty().ifBlank { "mp4" }, if (h > 0) "${h}p" else "", subs),
            MovieStream("Jellyfin (compatible)", "$server/Videos/$itemId/master.m3u8?MediaSourceId=$msId&VideoCodec=h264&AudioCodec=aac&api_key=$token", "m3u8", "", subs))
    }
}

// ---------------------------------------------------------------- user-installed providers

/** A provider the user installed from a source repo they added ([ProviderStore]); runs only in [ProviderSandbox]. */
class JsProviderSource(val entry: ProviderRepo.Entry, private val modules: (String) -> String?, private val env: ProviderSandbox.ProviderEnv,
                       private val sandbox: ProviderSandbox = ProviderSandbox(), override val official: Boolean = false) : MovieSource {
    override val id = "p:" + entry.value
    override val name = entry.name
    /** [interactive] = the user opened it (details, episodes, streams): it gets the sandbox's kept lane, not the browse queue. */
    private suspend fun call(file: String, export: String?, args: JSONObject, interactive: Boolean = false): Any? {
        // 0.46.5: the crash log never names a source the user added (CrashLog.redact)
        com.blaze.assistant.device.CrashLog.secret(entry.name, entry.value, env.settings["baseUrl"]?.let { u -> runCatching { java.net.URI(u.trim()).host }.getOrNull() })
        val code = modules(file) ?: throw IllegalStateException("${entry.name} is missing $file.js; update it in Providers")
        // 0.46.5: whatever the engine throws (an Error included) comes back as a normal failure of this source; never off to the UI
        val r = try { sandbox.invoke(code, export, args, env, interactive) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { throw IllegalStateException("${entry.name} failed: ${e.javaClass.simpleName}") }
        if (!r.ok) throw IllegalStateException(r.error)
        return r.value
    }
    suspend fun catalog(): List<MovieCatalog> = (call("catalog", null, JSONObject().put("providerValue", entry.value)) as? JSONObject)?.let { MovieJson.catalog(it.opt("catalog")) }.orEmpty()
    override suspend fun rows(): List<Pair<String, List<MoviePost>>> = catalog().take(4).map { c ->
        c.title to runCatching { MovieJson.posts(call("posts", "getPosts", JSONObject().put("filter", c.filter).put("page", 1).put("providerValue", entry.value)), id) }.getOrDefault(emptyList())
    }
    override suspend fun search(query: String) = MovieJson.posts(call("posts", "getSearchPosts", JSONObject().put("searchQuery", query).put("page", 1).put("providerValue", entry.value)), id)
    override suspend fun info(post: MoviePost) = MovieJson.info(call("meta", "getMeta", JSONObject().put("link", post.link).put("provider", entry.value), interactive = true) as? JSONObject)
        ?: throw IllegalStateException("${entry.name} returned no details")
    override suspend fun episodes(link: String) = MovieJson.episodes(call("episodes", "getEpisodes", JSONObject().put("url", link), interactive = true))
    override suspend fun streams(link: MovieLink) = MovieJson.streams(call("stream", "getStream", JSONObject().put("link", link.link).put("type", link.type).put("isDownload", false), interactive = true))
}
