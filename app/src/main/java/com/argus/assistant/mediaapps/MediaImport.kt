package com.argus.assistant.mediaapps

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 0.53.4 Import into Music and Video: the user picks audio or video files with Android's file picker (any folder, any
 * app's files, SD card, Drive) and they join the library in place, with no copy. The picker grants a lasting read
 * permission (takePersistableUriPermission), so they keep playing after a restart. Removing an import takes it out of
 * the library only; the user's file is never deleted. Pure naming / kind rules in [ImportRules] (MediaImportTest).
 */
object ImportRules {
    const val SOURCE = "import"

    val audioExt = setOf("mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "wma", "amr", "mka", "weba", "3ga", "mid", "midi", "aiff", "aif", "alac")
    val videoExt = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "flv", "wmv", "mpg", "mpeg")

    /** "audio", "video" or null, from the picker's mime type, else the extension. */
    fun kindOf(mime: String?, name: String): String? {
        val m = mime.orEmpty().lowercase()
        if (m.startsWith("audio/") || m == "application/ogg") return "audio"
        if (m.startsWith("video/")) return "video"
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) { in audioExt -> "audio"; in videoExt -> "video"; else -> null }
    }

    private val noise = Regex("(?i)\\s*[(\\[](official( music)? (video|audio)|official|audio|lyrics?|lyric video|visuali[sz]er|hd|hq|4k)[)\\]]")

    /** (title, artist) from a file name like "Artist - Title (Official Video).m4a"; tags, when present, win over this. */
    fun titleArtist(fileName: String): Pair<String, String> {
        val base = fileName.substringBeforeLast('.').ifBlank { fileName }.replace('_', ' ').trim()
        val clean = base.replace(noise, "").trim().ifBlank { base }
        val dash = Regex("\\s+[-\u2013\u2014]\\s+").find(clean)
        return if (dash != null && dash.range.first > 0) clean.substring(dash.range.last + 1).trim() to clean.substring(0, dash.range.first).trim()
        else clean to ""
    }

    /** A tag only counts when it says something (some rippers write "<unknown>" or the file name). */
    fun goodTag(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("<unknown>", true) && !it.equals("unknown", true) }

    /** What the toast says after an import. */
    fun summary(added: Int, already: Int, skipped: Int, kind: String): String {
        val noun = if (kind == "video") "video" else "song"
        val parts = mutableListOf<String>()
        if (added > 0) parts += "Imported $added $noun${if (added == 1) "" else "s"}"
        if (already > 0) parts += "$already already in your library"
        if (skipped > 0) parts += "$skipped skipped (not ${if (kind == "video") "a video" else "audio"})"
        return parts.joinToString(" \u00b7 ").ifEmpty { "Nothing imported" }
    }
}

object MediaImport {
    data class Result(val added: Int, val already: Int, val skipped: Int) {
        fun text(kind: String) = ImportRules.summary(added, already, skipped, kind)
    }

    /** Mime types the picker offers for [kind]. */
    fun mimes(kind: String): Array<String> = if (kind == "video") arrayOf("video/*") else arrayOf("audio/*", "application/ogg")

    /** Reads one picked file into a library Track (null = not [kind]). Call off the main thread. */
    fun track(ctx: Context, uri: Uri, kind: String, now: Long): Track? {
        val cr = ctx.contentResolver
        runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching { cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }
            .getOrNull() ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        if (ImportRules.kindOf(runCatching { cr.getType(uri) }.getOrNull(), name) != kind) return null
        val (fileTitle, fileArtist) = ImportRules.titleArtist(name)
        var title = fileTitle; var artist = fileArtist; var album = ""; var dur = 0L
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            ImportRules.goodTag(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))?.let { title = it }
            ImportRules.goodTag(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))?.let { artist = it }
            ImportRules.goodTag(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))?.let { album = it }
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { } finally { runCatching { r.release() } }
        val u = uri.toString()
        return Track(u, u, title, artist, album, dur, "", ImportRules.SOURCE, now, "", kind)
    }

    /** Can the library still read this import? A revoked grant or a deleted file drops it; anything unclear keeps it. */
    fun readable(ctx: Context, uri: String): Boolean = try {
        ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.close(); true
    } catch (_: SecurityException) { false } catch (_: java.io.FileNotFoundException) { false } catch (_: Exception) { true }

    fun release(ctx: Context, uri: String) {
        runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
