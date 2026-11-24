package com.blaze.assistant.movies


import com.blaze.assistant.BrandName
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.blaze.assistant.mediaapps.Downloads
import com.blaze.assistant.mediaapps.store.Choice
import com.blaze.assistant.mediaapps.store.StoreDetail
import com.blaze.assistant.mediaapps.store.StoreFormat
import com.blaze.assistant.mediaapps.store.StoreItem

/**
 * 0.46.2 Movies downloads (0.55.13: the Internet Archive / Blender branch is gone). Direct files (YouTube, Jellyfin, a provider's mp4) are queued with Android's DownloadManager (with the source's headers).
 * Adaptive streams (HLS / DASH) can't be saved as one file and say so.
 */
object MovieDownloads {
    /** null when it can be downloaded, else why not. */
    fun why(s: MovieStream): String? = when (s.type.lowercase()) {
        "m3u8", "hls", "dash", "mpd" -> "This stream can't be saved as a file"
        else -> if (!s.link.startsWith("http", true)) "Already on this phone" else null
    }

    fun start(ctx: Context, src: MovieSource, play: MoviePlay, s: MovieStream): String {
        why(s)?.let { return it }
        val dm = ctx.getSystemService(DownloadManager::class.java) ?: return "Downloads aren't available on this phone"
        val file = (play.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().ifBlank { "video" }.take(80)) + "." + s.type.lowercase().ifBlank { "mp4" }.take(5)
        val r = DownloadManager.Request(Uri.parse(s.link)).setTitle(play.title).setDescription("${BrandName.NAME} Movies")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val pub = try { r.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "Blaze/$file"); true }
            catch (_: Exception) { r.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_MOVIES, file); false }
        s.headers.forEach { (k, v) -> runCatching { r.addRequestHeader(k, v) } }
        return try { dm.enqueue(r); "Downloading \u201c${play.title}\u201d to " + (if (pub) "Movies/Blaze." else "${BrandName.NAME}'s Movies folder.") }
            catch (e: Exception) { "Couldn't start the download: ${e.message}" }
    }
}
