package com.blaze.assistant.mediaapps

import android.content.Context
import android.widget.Toast
import androidx.media3.session.MediaController
import com.blaze.assistant.mediaapps.store.StoreDetail
import com.blaze.assistant.mediaapps.store.StoreItem
import com.blaze.assistant.mediaapps.store.YouTubeStoreSource
import com.blaze.assistant.mediaapps.store.YtMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * 0.55.11: the song shelves every Music page opens on ([MusicFeedPlan]). The last good shelves are kept on disk
 * (filesDir/mediaapps/music-feed.json) and shown at once, also offline; when they are old (or the plan changed) they
 * are fetched again in the background, shelf by shelf, and a shelf that fails keeps what it had.
 */
object MusicFeed {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _shelves = MutableStateFlow<List<MusicFeedPlan.Shelf>>(emptyList())
    val shelves: StateFlow<List<MusicFeedPlan.Shelf>> = _shelves
    /** Songs played from the shelves and search, newest first (the "Jump back in" shelf). */
    private val _recent = MutableStateFlow<List<StoreItem>>(emptyList())
    val recent: StateFlow<List<StoreItem>> = _recent
    /** True while a refresh is running (pages show skeleton shelves for what isn't there yet). */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    /** The last refresh found no network and nothing was saved. */
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline
    @Volatile private var loaded = false
    @Volatile private var savedAt = 0L
    private var job: Job? = null

    private fun file(ctx: Context) = File(File(ctx.filesDir, "mediaapps").apply { mkdirs() }, "music-feed.json")

    private fun plan(ctx: Context): List<MusicFeedPlan.Spec> {
        val (artists, styles) = runCatching { Taste.favourites(ctx, "audio") }.getOrDefault(emptyList<String>() to emptyList())
        val country = runCatching { java.util.Locale("", java.util.Locale.getDefault().country).getDisplayCountry(java.util.Locale.ENGLISH) }.getOrDefault("")
        return MusicFeedPlan.plan(artists, styles, country, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
    }

    /** Shows the saved shelves (first call), then refreshes them in the background when they are stale. */
    fun open(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        io.launch {
            if (!loaded) {
                val s = MusicFeedPlan.decode(runCatching { file(app).readText() }.getOrDefault(""))
                if (_shelves.value.isEmpty()) _shelves.value = s.shelves
                if (_recent.value.isEmpty()) _recent.value = s.recent
                savedAt = s.at; loaded = true
            }
            refresh(app, force)
        }
    }

    private fun refresh(ctx: Context, force: Boolean) {
        val p = plan(ctx)
        if (!force && !MusicFeedPlan.stale(savedAt, System.currentTimeMillis(), _shelves.value, p)) return
        if (job?.isActive == true) return
        if (!NetWatch.now(ctx)) { _offline.value = _shelves.value.isEmpty(); return }
        _offline.value = false
        job = io.launch {
            _loading.value = true
            try {
                val old = _shelves.value.associateBy { it.id }
                // Shelves land one by one, in plan order; the old copy stands in until its new one arrives.
                val got = arrayOfNulls<MusicFeedPlan.Shelf>(p.size)
                fun publish() = synchronized(got) { _shelves.value = p.mapIndexedNotNull { i, s -> got[i] ?: old[s.id]?.copy(title = s.title) } }
                p.mapIndexed { i, s ->
                    async {
                        val items = runCatching { withTimeout(20_000) { MusicFeedPlan.songs(YtMusic.songs(s.query, 30)) } }.getOrDefault(emptyList())
                        if (items.isNotEmpty()) { synchronized(got) { got[i] = MusicFeedPlan.Shelf(s.id, s.title, items) }; publish() }
                    }
                }.awaitAll()
                publish()
                if (got.any { it != null }) { savedAt = System.currentTimeMillis(); save(ctx) }
                _offline.value = _shelves.value.isEmpty() && !NetWatch.now(ctx)
            } finally { _loading.value = false }
        }
    }

    private fun save(ctx: Context) {
        runCatching { file(ctx).writeText(MusicFeedPlan.encode(savedAt, _shelves.value.filter { it.items.isNotEmpty() }, _recent.value)) }
    }

    /** Notes a song played from the shelves or search ("Jump back in", and the taste the shelves learn from). */
    fun played(ctx: Context, item: StoreItem) {
        _recent.value = MusicFeedPlan.pushRecent(_recent.value, item)
        Taste.note(ctx, "audio", item.title, item.author, Taste.PLAY)
        io.launch { save(ctx.applicationContext) }
    }

    /** Songs for a typed search (YouTube Music first). */
    suspend fun search(query: String): List<StoreItem> = withTimeout(20_000) { YtMusic.songs(query, 30) }

    /** The streams of a song (cached a while, so Play after a preview is instant). */
    suspend fun detail(item: StoreItem): StoreDetail {
        val k = StreamCache.detailKey(YouTubeStoreSource.id, item.id, "audio")
        StreamCache.details.get(k)?.let { return it }
        val d = withContext(Dispatchers.IO) { withTimeout(25_000) { YouTubeStoreSource.detail(item, "audio") } }
        StreamCache.details.put(k, d)
        return d
    }
}

/** 0.55.11: playing and downloading songs from the shelves and search (no Store sheet in between). */
object MusicOnline {
    private var job: Job? = null

    fun key(item: StoreItem) = "stream:${YouTubeStoreSource.id}:${item.id}"

    private fun track(item: StoreItem, d: StoreDetail, url: String) = Track(key(item), url, item.title.ifBlank { d.title }, item.author.ifBlank { d.author },
        item.extra["album"].orEmpty(), (if (item.durationSec > 0) item.durationSec else d.lengthSec) * 1000L, item.thumb.ifBlank { d.thumb },
        "stream", 0, item.id, "audio", "", YouTubeStoreSource.id)

    private suspend fun media(ctx: Context, item: StoreItem): androidx.media3.common.MediaItem? {
        val d = MusicFeed.detail(item)
        val f = VideoSmart.streamFor("audio", d, YouTubeStoreSource, MediaPrefs.saving(ctx)).firstOrNull() ?: return null
        return MusicService.streamItem(track(item, d, f.url), f.url)
    }

    /**
     * Plays [list] from [index]: that song starts as soon as its stream is found, then the next few are added to the
     * queue behind it in the background (so a shelf plays on like an album). A newer tap cancels the rest.
     */
    fun play(ctx: Context, scope: CoroutineScope, ctl: MediaController?, list: List<StoreItem>, index: Int, from: String) {
        val p = ctl ?: return
        val item = list.getOrNull(index) ?: return
        job?.cancel()
        job = scope.launch {
            val first = runCatching { media(ctx, item) }.getOrNull()
            if (first == null) { Toast.makeText(ctx, "Couldn't play \u201c${item.title.take(40)}\u201d right now.", Toast.LENGTH_SHORT).show(); return@launch }
            MusicService.current?.streamUa(YouTubeStoreSource.userAgent)
            MusicContext.set(from)
            p.setMediaItem(first); p.shuffleModeEnabled = false; p.prepare(); p.play()
            MusicFeed.played(ctx, item)
            for (next in list.drop(index + 1).take(8)) {
                val m = runCatching { media(ctx, next) }.getOrNull() ?: continue
                if (p.mediaItemCount == 0 || p.getMediaItemAt(0).mediaId != key(item)) break   // the user started something else
                p.addMediaItem(m)
            }
        }
    }

    /** Adds a song right after the one playing. */
    fun playNext(ctx: Context, scope: CoroutineScope, ctl: MediaController?, item: StoreItem) {
        val p = ctl ?: return
        scope.launch {
            val m = runCatching { media(ctx, item) }.getOrNull() ?: return@launch
            if (p.mediaItemCount == 0) { p.setMediaItem(m); p.prepare(); p.play() }
            else p.addMediaItem((p.currentMediaItemIndex + 1).coerceAtMost(p.mediaItemCount), m)
            Toast.makeText(ctx, "Playing next: ${item.title.take(40)}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Saves a song to the phone (private Library downloads), best quality, or the small one on Data Saver. */
    fun download(ctx: Context, scope: CoroutineScope, item: StoreItem) {
        scope.launch {
            try {
                val d = MusicFeed.detail(item)
                val ch = MediaPick.choose("audio", YouTubeStoreSource.choices("audio", d), if (MediaPrefs.saving(ctx)) "small" else "high") ?: error("no stream")
                StoragePermission.askNotifications()
                Downloads.add(ctx, item, d.copy(title = item.title, author = item.author, thumb = item.thumb.ifBlank { d.thumb }), ch, "audio")
                Toast.makeText(ctx, "Downloading \u201c${item.title.take(40)}\u201d", Toast.LENGTH_SHORT).show()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                Toast.makeText(ctx, "Couldn't download \u201c${item.title.take(40)}\u201d right now.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
