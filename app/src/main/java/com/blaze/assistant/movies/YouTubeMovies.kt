package com.blaze.assistant.movies

import com.blaze.assistant.mediaapps.store.StoreDetail
import com.blaze.assistant.mediaapps.store.StoreFormat
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.YouTubeStoreSource

/**
 * 0.55.13 YouTube as a built-in Movie source (Finn: "leave YouTube and open source"; Internet Archive and Blender are gone).
 * Search, Home rows and playback go through [YouTubeStoreSource] (the same working clients as Music and "Download by link").
 * Links are "yt:<videoId>". Playback prefers one muxed stream (audio + video in one file) at or under the height cap.
 */
object YouTubeMovies : MovieSource {
    override val id = "youtube"
    override val name = "YouTube"
    override val official = true

    /** Home rows: (title, search words). Kept to two so opening Movie stays quick. */
    val HOME = listOf("Full movies on YouTube" to "full movie", "Trailers" to "official trailer 2026")

    override suspend fun rows() = HOME.map { (t, q) -> t to runCatching { search(q) }.getOrDefault(emptyList()).take(20) }

    override suspend fun search(query: String): List<MoviePost> =
        YouTubeStoreSource.search("video", query).items.map { post(it) }

    override suspend fun info(post: MoviePost) =
        MovieInfo(post.title, "", post.image, "movie", listOf(MovieGroup("Watch", direct = listOf(MovieLink("Play", post.link)))), licence = "YouTube")

    override suspend fun streams(link: MovieLink): List<MovieStream> {
        val vid = link.link.removePrefix("yt:")
        val d = YouTubeStoreSource.detail(StoreItem(id, vid, "", ""), "video")
        return streams(d, YouTubeStoreSource.streamPick("video", d), mapOf("User-Agent" to YouTubeStoreSource.userAgent))
    }

    // ---------------------------------------------------------------- pure (YouTubeMoviesTest)
    fun post(it: StoreItem) = MoviePost(it.title, "yt:" + it.id.removePrefix("yt:"), it.thumb, id, it.durationText)

    /** Muxed streams best-first (tallest at or under 1080p first), then the picker's own pick; subtitles carried over. */
    fun streams(d: StoreDetail, pick: List<StoreFormat>, headers: Map<String, String>, maxHeight: Int = 1080): List<MovieStream> {
        val subs = d.subtitles.map { MovieSubtitle(it.url, it.lang, it.name) }
        val muxed = d.formats.filter { it.muxed && it.url.isNotBlank() }
            .sortedWith(compareBy<StoreFormat>({ if (it.height in 1..maxHeight) 0 else 1 }, { -it.height }))
        val single = pick.singleOrNull()?.takeIf { it.url.isNotBlank() }
        return (muxed + listOfNotNull(single)).distinctBy { it.url }.map { f ->
            MovieStream("YouTube", f.url, MovieJson.guessType(f.url).ifBlank { f.container }, if (f.height > 0) "${f.height}p" else f.qualityLabel, subs, headers, f.contentLength)
        }
    }
}
