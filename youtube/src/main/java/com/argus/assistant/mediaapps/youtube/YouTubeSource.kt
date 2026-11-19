package com.argus.assistant.mediaapps.youtube

import com.argus.assistant.mediaapps.store.Choice
import com.argus.assistant.mediaapps.store.StoreDetail
import com.argus.assistant.mediaapps.store.StoreError
import com.argus.assistant.mediaapps.store.StoreFormat
import com.argus.assistant.mediaapps.store.StoreItem
import com.argus.assistant.mediaapps.store.StorePage
import com.argus.assistant.mediaapps.store.StoreSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The YouTube Store plug-in (Gradle :youtube), rebuilt in this repo on 2026-11-19 (owner's call; it replaces the old
 * private plug-in, whose source is no longer reachable). The app finds it by class name:
 * StoreSources.plugin(StoreSources.YOUTUBE_CLASS) → this object's INSTANCE.
 *
 * - search: YouTube's own search (InnerTube WEB, videos only; live streams, premieres and duplicates dropped; more pages
 *   through the continuation token).
 * - detail: title, channel, length, picture, caption tracks and the plain-url streams of one video, from the first app
 *   client ([YtClients.streams]) whose stream really downloads (a ranged read ~1.5 MB in; see [YtParse.probeAt]).
 * - choices / defaultChoice / streamPick: the quality picker (AAC / Opus for music, H.264 + AAC up to 1080p and
 *   progressive mp4 for video). refresh: fresh urls for an expired download.
 *
 * Not done (no signature / n-parameter JavaScript, no PO tokens): ciphered-only and DRM videos, live streams, and videos
 * YouTube hides behind a sign-in or bot check on the phone's network fail with a plain message.
 */
object YouTubeSource : StoreSource {
    private val engine = YouTubeEngine(HttpTransport)

    override val id = "youtube"
    override val name = "YouTube"
    override val kinds = setOf("audio", "video")
    override val userAgent: String get() = engine.userAgent

    override suspend fun search(kind: String, query: String, next: String): StorePage = withContext(Dispatchers.IO) { engine.search(query, next) }
    override suspend fun detail(item: StoreItem, kind: String): StoreDetail = withContext(Dispatchers.IO) { engine.detail(item, kind) }
    override fun choices(kind: String, d: StoreDetail): List<Choice> = YtParse.choices(kind, d)
    override fun defaultChoice(kind: String, choices: List<Choice>): Choice? = YtParse.defaultChoice(kind, choices)
    override suspend fun refresh(itemId: String, kind: String): StoreDetail? = withContext(Dispatchers.IO) { engine.refresh(itemId, kind) }

    /** Title and channel of a video id or link (oEmbed), for a pasted link before the streams are loaded. */
    suspend fun describe(link: String): StoreItem? = withContext(Dispatchers.IO) { engine.describe(link) }
}

/** The plug-in's logic over a [YtTransport] (blocking; [YouTubeSource] moves it off the main thread). Tested with a fake transport. */
class YouTubeEngine(net: YtTransport, private val clients: List<YtClient> = YtClients.streams, private val source: String = "youtube") {
    private val api = YtApi(net)

    /** The user-agent of the client whose stream urls were handed out last (googlevideo urls can be tied to it). */
    @Volatile var userAgent: String = clients.first().ua
        private set

    fun search(query: String, next: String = ""): StorePage {
        val q = query.trim()
        if (q.isEmpty() && next.isBlank()) return StorePage(emptyList(), "")
        val page = YtParse.search(api.search(q, next), source)
        if (page.items.isEmpty() && next.isBlank()) throw StoreError("YouTube found nothing for \u201c$q\u201d.")
        return page
    }

    fun detail(item: StoreItem, kind: String): StoreDetail {
        val vid = YtParse.videoId(item.id) ?: item.extra["url"]?.let(YtParse::videoId) ?: throw StoreError("That isn't a YouTube video id or link")
        val d = load(vid, kind)
        return d.copy(title = d.title.takeUnless { it == "YouTube video" } ?: item.title.ifBlank { d.title }, author = d.author.ifBlank { item.author })
    }

    fun refresh(itemId: String, kind: String): StoreDetail? = try { YtParse.videoId(itemId)?.let { load(it, kind) } } catch (_: Exception) { null }

    fun describe(link: String): StoreItem? {
        val vid = YtParse.videoId(link) ?: return null
        val (t, a) = try { api.oembed(vid) } catch (_: Exception) { "" to "" }
        return StoreItem(source, vid, t.ifBlank { "YouTube video" }, a, thumb = YtParse.thumb(vid), licence = "YouTube",
            extra = mapOf("url" to "https://www.youtube.com/watch?v=$vid"))
    }

    /** The first stream to fetch for [kind] (what Play / Download would read first). */
    private fun firstStream(kind: String, d: StoreDetail): StoreFormat? =
        YtParse.defaultChoice(kind, YtParse.choices(kind, d))?.keys?.firstOrNull()?.let { k -> d.formats.firstOrNull { it.key == k } }

    /** Tries each client in turn; keeps the first whose streams parse and really download. */
    fun load(vid: String, kind: String): StoreDetail {
        val errors = ArrayList<String>()
        val visitor = try { api.visitorId() } catch (_: Exception) { "" }
        for (c in clients) {
            try {
                val d = YtParse.player(api.player(c, vid, visitor), vid)
                val probe = firstStream(kind, d) ?: throw StoreError("${c.name}: no downloadable stream")
                if (!api.probe(probe.url, c.ua, probe.contentLength)) throw StoreError("${c.name}: stream refused")
                userAgent = c.ua
                return d
            } catch (e: StoreError) { errors += e.message.orEmpty() }
            catch (e: Exception) { errors += "${c.name}: ${e.message}" }
        }
        val why = errors.firstOrNull { e -> listOf("bot", "age", "private", "unavailable", "sign in", "live").any { e.contains(it, true) } }
        throw StoreError("YouTube didn't hand out this video" + (why?.let { ": $it." } ?: ". It may be private, age-restricted, live, or blocked on this network.") +
            " Try again in a bit.")
    }
}
