package com.argus.assistant.mediaapps.youtube

import com.argus.assistant.mediaapps.store.Choice
import com.argus.assistant.mediaapps.store.StoreDetail
import com.argus.assistant.mediaapps.store.StoreError
import com.argus.assistant.mediaapps.store.StoreFormat
import com.argus.assistant.mediaapps.store.StoreItem
import com.argus.assistant.mediaapps.store.StorePage
import com.argus.assistant.mediaapps.store.StoreSubtitle
import com.argus.assistant.mediaapps.store.StoreText
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Pure parsing of InnerTube JSON and links (YtParseTest, fixtures in src/test/resources/youtube). No network. */
object YtParse {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** A video id from a bare id, a "yt:<id>" key, or any YouTube link (watch, youtu.be, shorts, embed, live, music); else null. */
    fun videoId(s: String): String? {
        val t = s.trim().removePrefix("yt:")
        if (ID.matches(t)) return t
        val url = if (t.startsWith("http")) t else if (Regex("^(?:www\\.|m\\.|music\\.)?(?:youtube\\.com|youtu\\.be|youtube-nocookie\\.com)/").containsMatchIn(t)) "https://$t" else return null
        val u = try { URI(url) } catch (_: Exception) { return null }
        val host = u.host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.").removePrefix("music.")
        val path = u.path.orEmpty()
        val ok = { x: String? -> x?.takeIf { ID.matches(it) } }
        return when (host) {
            "youtu.be" -> ok(path.trim('/').substringBefore('/'))
            "youtube.com", "youtube-nocookie.com" -> {
                val q = u.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
                ok(q["v"]) ?: Regex("^/(?:shorts|embed|live|v|e)/([^/?#]+)").find(path)?.groupValues?.get(1)?.let(ok)
            }
            else -> null
        }
    }

    /** Where the stream probe reads: ~1.5 MB in for files over 2 MB (unknown size counts as big), else the start. */
    fun probeAt(length: Long): Long = if (length in 1..2_000_000) 0 else 1_500_000

    /** "simpleText" or the joined "runs" of an InnerTube text object. */
    fun text(o: JSONObject?): String = o?.optString("simpleText")?.takeIf { it.isNotBlank() }
        ?: o?.optJSONArray("runs")?.let { a -> (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()

    fun visitorData(json: String): String = try {
        JSONObject(json).optJSONObject("responseContext")?.optString("visitorData").orEmpty()
    } catch (_: Exception) { "" }

    fun thumb(videoId: String) = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    /**
     * A search page (first page or a continuation) → videos (no live streams, premieres or duplicates) and the
     * next-page token. Only the results' own continuationItemRenderer counts (filter chips carry tokens too).
     */
    fun search(json: String, source: String): StorePage {
        val root = try { JSONObject(json) } catch (_: Exception) { throw StoreError("YouTube answered with something that isn't a search reply") }
        val items = ArrayList<StoreItem>(); var next = ""
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> {
                    x.optJSONObject("videoRenderer")?.let { v -> video(v, source)?.let { if (items.none { o -> o.id == it.id }) items += it }; return }
                    x.optJSONObject("continuationItemRenderer")?.let { c ->
                        c.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }?.let { next = it }
                        return
                    }
                    if (x.has("searchHeaderRenderer") || x.has("chipCloudRenderer")) return
                    x.keys().forEach { walk(x.opt(it)) }
                }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(root.opt("contents")); walk(root.opt("onResponseReceivedCommands"))
        return StorePage(items, next)
    }

    private fun video(v: JSONObject, source: String): StoreItem? {
        val id = v.optString("videoId"); if (!ID.matches(id)) return null
        val len = text(v.optJSONObject("lengthText"))
        val live = v.optJSONArray("badges")?.toString()?.contains("LIVE", true) == true
        if (live || len.isBlank() || v.has("upcomingEventData")) return null
        val author = text(v.optJSONObject("ownerText")).ifBlank { text(v.optJSONObject("longBylineText")) }.ifBlank { text(v.optJSONObject("shortBylineText")) }
        return StoreItem(source, id, text(v.optJSONObject("title")), author, StoreText.parseClock(len).coerceAtLeast(0), len, thumb(id), "YouTube",
            "https://www.youtube.com/t/terms", views = text(v.optJSONObject("shortViewCountText")).ifBlank { text(v.optJSONObject("viewCountText")) },
            extra = mapOf("url" to "https://www.youtube.com/watch?v=$id"))
    }

    /** A player reply → title, channel, length, picture, the plain-url streams (ciphered ones skipped) and caption tracks. */
    fun player(json: String, videoId: String): StoreDetail {
        val root = try { JSONObject(json) } catch (_: Exception) { throw StoreError("YouTube answered with something that isn't a player reply") }
        val ps = root.optJSONObject("playabilityStatus")
        val st = ps?.optString("status").orEmpty()
        if (st != "OK") throw StoreError(ps?.optString("reason")?.ifBlank { null } ?: "YouTube says this video can't be played (${st.ifBlank { "no status" }})")
        val vd = root.optJSONObject("videoDetails") ?: JSONObject()
        if (vd.optBoolean("isLive") || vd.optBoolean("isUpcoming")) throw StoreError("This is a live stream; it can be watched on YouTube but not downloaded")
        val sd = root.optJSONObject("streamingData") ?: throw StoreError("YouTube gave no streams for this video")
        val out = ArrayList<StoreFormat>()
        for (arr in listOf(sd.optJSONArray("formats"), sd.optJSONArray("adaptiveFormats"))) {
            if (arr == null) continue
            for (i in 0 until arr.length()) format(arr.optJSONObject(i) ?: continue)?.let { f -> if (out.none { it.key == f.key }) out += f }
        }
        if (out.isEmpty()) throw StoreError("YouTube only offered protected streams for this video")
        val thumbs = vd.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: JSONArray()
        val pic = (if (thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty() else "")
            .ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" }
        return StoreDetail(videoId, vd.optString("title").ifBlank { "YouTube video" }, vd.optString("author"),
            vd.optString("lengthSeconds").toIntOrNull() ?: 0, pic, out, "YouTube", "https://www.youtube.com/t/terms", captions(root))
    }

    private fun format(f: JSONObject): StoreFormat? {
        val url = f.optString("url"); if (url.isBlank()) return null               // signatureCipher / DRM: skipped
        if (f.has("drmFamilies")) return null
        val mt = f.optString("mimeType")
        val mime = mt.substringBefore(';').trim()
        val codecs = Regex("codecs=\"([^\"]+)\"").find(mt)?.groupValues?.get(1).orEmpty()
        val video = mime.startsWith("video/")
        val audio = mime.startsWith("audio/") || (video && codecs.contains(','))
        if (!audio && !video) return null
        return StoreFormat(f.optInt("itag").toString(), url, mime, codecs, f.optInt("bitrate"), f.optString("contentLength").toLongOrNull() ?: 0,
            f.optInt("height"), f.optString("qualityLabel"), audio, video, f.optInt("fps"))
    }

    /** Caption tracks (ids "yt-.en" / "yt-a.en" like the app's own YouTube source, so the player treats them the same). */
    fun captions(player: JSONObject): List<StoreSubtitle> {
        val arr = player.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks") ?: return emptyList()
        val out = ArrayList<StoreSubtitle>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val url = t.optString("baseUrl"); if (url.isBlank()) continue
            val lang = t.optString("languageCode")
            val auto = t.optString("kind") == "asr" || t.optString("vssId").startsWith("a.")
            val id = "yt-" + t.optString("vssId").ifBlank { (if (auto) "a." else ".") + lang }.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (out.none { it.id == id }) out += StoreSubtitle(id, url, lang, text(t.optJSONObject("name")).ifBlank { lang }, auto)
        }
        return out
    }

    private fun est(f: StoreFormat, lengthSec: Int): Long = if (f.contentLength > 0) f.contentLength else f.bitrate.toLong() * lengthSec / 8

    private fun sized(ext: String, bytes: Long) = listOf(ext, StoreText.size(bytes)).filter { it.isNotBlank() }.joinToString(" \u00b7 ")

    /**
     * Download / play options. Music: AAC (m4a) qualities best first, then the best Opus; with no audio-only stream,
     * the sound of the progressive mp4. Video: H.264 heights up to 1080p, each the best video stream of that height
     * plus the best AAC (two streams, merged), then progressive mp4 files, then VP9 + Opus (.webm) for heights still not
     * covered; tallest first.
     */
    fun choices(kind: String, d: StoreDetail): List<Choice> {
        val aac = d.formats.filter { it.hasAudio && !it.hasVideo && it.mime == "audio/mp4" }.sortedByDescending { it.bitrate }
        if (kind != "video") {
            val opus = d.formats.filter { it.hasAudio && !it.hasVideo && it.mime == "audio/webm" }.sortedByDescending { it.bitrate }
            val a = aac.mapIndexed { i, f -> Choice("a" + f.key, (if (i == 0) "High" else "Smaller") + " \u00b7 AAC ${f.kbps} kbps", sized("m4a", est(f, d.lengthSec)),
                listOf(f.key), "m4a", est(f, d.lengthSec), kbps = f.kbps) }.distinctBy { it.kbps }
            val o = opus.take(1).map { Choice("o" + it.key, "Opus ${it.kbps} kbps", sized("opus", est(it, d.lengthSec)), listOf(it.key), "opus", est(it, d.lengthSec), kbps = it.kbps) }
            if (a.isEmpty() && o.isEmpty())
                return d.formats.filter { it.muxed && it.mime == "video/mp4" }.take(1).map { Choice("p" + it.key, "Audio \u00b7 from the video", "m4a", listOf(it.key), "m4a", est(it, d.lengthSec)) }
            return a + o
        }
        val bestA = aac.firstOrNull()
        val out = ArrayList<Choice>()
        val avc = d.formats.filter { it.hasVideo && !it.hasAudio && it.mime == "video/mp4" && it.codecs.startsWith("avc1") && it.height in 1..1080 }
            .groupBy { it.height }.map { (_, v) -> v.maxBy { it.bitrate } }.sortedByDescending { it.height }
        if (bestA != null) for (v in avc) {
            val b = est(v, d.lengthSec) + est(bestA, d.lengthSec)
            out += Choice("v" + v.key, v.qualityLabel.ifBlank { "${v.height}p" }, sized("mp4", b), listOf(v.key, bestA.key), "mp4", b, height = v.height)
        }
        d.formats.filter { it.muxed && it.mime == "video/mp4" }.sortedByDescending { it.height }.forEach { p ->
            if (out.none { it.height == p.height }) out += Choice("p" + p.key, p.qualityLabel.ifBlank { "${p.height}p" } + " \u00b7 one file",
                sized("mp4", est(p, d.lengthSec)), listOf(p.key), "mp4", est(p, d.lengthSec), height = p.height)
        }
        // VP9 + Opus (merged into .webm) only for heights nothing above covers
        val bestOpus = d.formats.filter { it.hasAudio && !it.hasVideo && it.mime == "audio/webm" }.maxByOrNull { it.bitrate }
        if (bestOpus != null) d.formats.filter { it.hasVideo && !it.hasAudio && it.mime == "video/webm" && it.codecs.startsWith("vp9") && it.height in 1..1080 }
            .groupBy { it.height }.map { (_, v) -> v.maxBy { it.bitrate } }.forEach { v ->
                if (out.none { it.height == v.height }) {
                    val b = est(v, d.lengthSec) + est(bestOpus, d.lengthSec)
                    out += Choice("w" + v.key, v.qualityLabel.ifBlank { "${v.height}p" } + " \u00b7 WebM", sized("webm", b), listOf(v.key, bestOpus.key), "webm", b, height = v.height)
                }
            }
        return out.sortedByDescending { it.height }
    }

    /** Video: the tallest option up to 720p (fits phones and data plans), else the first. Music: the first (best AAC). */
    fun defaultChoice(kind: String, choices: List<Choice>): Choice? =
        if (kind == "video") choices.firstOrNull { it.height in 1..720 } ?: choices.firstOrNull() else choices.firstOrNull()
}
