package com.blaze.assistant.ui.living

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Living UI 0.2.0: pictures of the pages ARGUS works in, for the browser cards ("show the actual pages, not just the
 * URL", Finn, 15 Nov 2026). BrowserEngine.agentShot() draws the agent's tab (laid out offscreen at the phone's size) into
 * a small card-shaped picture after each browser step; the card shows the newest one taken while it was the current
 * page ([LivingPage.pick]). Pages read without the browser (fetch_page) get a site masthead with its icon instead.
 * In memory only, the newest [KEEP] pictures (RGB_565, [W]×[H] ≈ 190 KB each).
 */
object LivingShots {
    const val W = 360
    const val H = 270
    const val KEEP = 12

    class Shot(val url: String, val title: String, val at: Long, val image: ImageBitmap)

    private val _shots = MutableStateFlow<List<Shot>>(emptyList())
    val shots: StateFlow<List<Shot>> = _shots

    fun add(s: Shot) { _shots.value = (_shots.value.filter { it.at != s.at } + s).takeLast(KEEP) }

    fun refs(list: List<Shot>): List<LivingPage.ShotRef> = list.map { LivingPage.ShotRef(it.url, it.at) }

    // ------------------------------------------------------------------ site icons for the masthead
    private val icons = HashMap<String, ImageBitmap?>()

    /** The site's icon (Google's favicon service, 64 px), cached for the session; null when it can't be had. */
    suspend fun icon(host: String): ImageBitmap? {
        if (host.isBlank()) return null
        synchronized(icons) { if (icons.containsKey(host)) return icons[host] }
        val bmp = withContext(Dispatchers.IO) {
            try {
                val c = URL("https://www.google.com/s2/favicons?sz=64&domain=" + java.net.URLEncoder.encode(host, "UTF-8")).openConnection() as HttpURLConnection
                c.connectTimeout = 6_000; c.readTimeout = 6_000
                c.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
            } catch (_: Exception) { null }
        }
        synchronized(icons) { icons[host] = bmp }
        return bmp
    }
}
