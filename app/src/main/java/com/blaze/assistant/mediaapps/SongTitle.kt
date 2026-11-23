package com.blaze.assistant.mediaapps

/**
 * 0.55.11: song titles the way a music app shows them. A video title such as
 * "MaWhoo, GL Ceejay & Thukuthela - Bengicela feat. JAZZWRLD (Official Lyric Video)" from the channel "MaWhoo VEVO"
 * becomes the title "Bengicela (feat. JAZZWRLD)" by "MaWhoo, GL Ceejay & Thukuthela": the video words
 * ("Official Video", "[Lyrics]", "HD", "4K", "| YouTube", hashtags) go, "Artist - Title" is split, and the channel name
 * loses " - Topic" / "VEVO". Pure (SongTitleTest).
 */
object SongTitle {
    data class Clean(val title: String, val artist: String)

    /** Words that only describe the video, never the song. Matched inside ( ) / [ ] / 【 】 and at the end of a title. */
    private const val NOISE = "(?:official\\s*)?(?:music\\s*|lyrics?\\s*|lyric\\s*|audio\\s*|hd\\s*|4k\\s*|hq\\s*|vertical\\s*|animated\\s*)*" +
        "(?:video(?:\\s*clip)?|audio|lyrics?|visuali[sz]er|mv|m/v|clip(?:\\s*officiel)?|hd|hq|4k|1080p|720p|full\\s*song|official)" +
        "(?:\\s*(?:hd|4k|hq))?"
    private val bracketNoise = Regex("\\s*[(\\[【]\\s*(?:" + NOISE + "|prod\\.?\\s*(?:by)?[^)\\]】]*|free\\s*download|out\\s*now|new\\s*song[^)\\]】]*|" +
        "with\\s*lyrics|lyrics?\\s*video|official\\s*[^)\\]】]*video[^)\\]】]*|official\\s*[^)\\]】]*audio[^)\\]】]*)\\s*[)\\]】]", RegexOption.IGNORE_CASE)
    private val tailNoise = Regex("(?:\\s*[-|:/]\\s*|\\s+)(?:" + NOISE + ")\\s*$", RegexOption.IGNORE_CASE)
    private val hashtag = Regex("\\s*#[\\p{L}\\p{N}_]+")
    private val dash = Regex("\\s+[-\u2013\u2014~]\\s+")
    /** A loose " ft. X" / " feat. X" outside brackets (up to a bracket or the end). */
    private val looseFeat = Regex("\\s+(?:ft\\.?|feat\\.?|featuring)\\s+([^()\\[\\]]+?)\\s*(?=[(\\[]|$)", RegexOption.IGNORE_CASE)
    private val bracketFeat = Regex("[(\\[]\\s*(?:ft\\.?|feat\\.?|featuring)\\s+([^)\\]]+)[)\\]]", RegexOption.IGNORE_CASE)
    /** A version word: "Song - Remastered 2011" is a title, not "Artist - Title". */
    private val version = Regex("(?i)^(?:\\d{4}\\s+)?(?:remaster(?:ed)?|live|acoustic|radio edit|edit|extended|remix|version|mono|stereo|demo|instrumental|sped up|slowed)\\b.*")

    /** The channel as an artist name: "Burna Boy - Topic" → "Burna Boy", "BurnaBoyVEVO" → "Burna Boy". */
    fun artist(channel: String): String {
        var a = channel.trim()
            .replace(Regex("(?i)\\s*-\\s*topic$"), "")
            .replace(Regex("(?i)\\s*vevo$"), "")
            .replace(Regex("(?i)\\s*(?:official(?:\\s*(?:channel|music|youtube))?|music\\s*official|tv|-\\s*official)$"), "")
            .trim().trimEnd('-', '|', ':').trim()
        if (a.startsWith("@")) a = a.drop(1)
        // "BurnaBoy" (no spaces, camel case) → "Burna Boy"
        if (' ' !in a && a.length > 3 && Regex("\\p{Ll}\\p{Lu}").containsMatchIn(a)) a = a.replace(Regex("(?<=\\p{Ll})(?=\\p{Lu})"), " ")
        return a.ifBlank { channel.trim() }
    }

    /** Only the video words go; for titles that are already song titles (YouTube Music metadata). */
    fun strip(title: String): String {
        var t = title.replace('\u00a0', ' ').trim()
        repeat(3) { t = t.replace(bracketNoise, "") }
        t = t.replace(hashtag, "")
        t = t.substringBefore(" | ").substringBefore(" \u2502 ").substringBefore(" // ")
        repeat(2) { t = t.replace(tailNoise, "") }
        t = t.replace(bracketFeat) { "(feat. " + it.groupValues[1].trim() + ")" }
        t = t.replace(looseFeat) { " (feat. " + it.groupValues[1].trim().trimEnd(',', '-') + ")" }
        t = t.replace(Regex("\\(\\s*\\)|\\[\\s*]"), "")
        return tidy(t)
    }

    /**
     * A video's title and channel as a song title and artist. [fromMusic] = the title and artist already come from
     * YouTube Music's song data (then nothing is split, only video words dropped).
     */
    fun clean(title: String, channel: String, fromMusic: Boolean = false): Clean {
        val ch = artist(channel)
        if (fromMusic) return Clean(strip(title).ifBlank { title.trim() }, channel.trim().ifBlank { ch })
        val raw = title.replace('\u00a0', ' ').trim()
        // Split "Artist - Title" before stripping, so "(Official Video)" on either side goes.
        val parts = raw.split(dash, limit = 2)
        if (parts.size == 2) {
            val left = parts[0].trim().trim('"', '\u201c', '\u201d')
            val right = parts[1].trim()
            val rightClean = strip(right).trim('"', '\u201c', '\u201d', '\'').trim()
            if (left.isNotBlank() && rightClean.isNotBlank() && !version.matches(rightClean)) {
                val who = if (left.startsWith("@")) ch.ifBlank { artist(left) } else strip(left).ifBlank { ch }
                return Clean(rightClean, who)
            }
        }
        val t = strip(raw).trim('"', '\u201c', '\u201d').trim()
        return Clean(t.ifBlank { raw }, ch)
    }

    /** For a library or queue track: streams and YouTube downloads get cleaned, the user's own files stay as they are. */
    fun forTrack(t: Track): Clean =
        if (t.source == "stream" || t.videoId.isNotBlank() || t.storeSource == "youtube") clean(t.title, t.artist, fromMusic = t.source == "stream")
        else Clean(t.title, t.artist)

    /** A cover picture as a big square: YouTube Music art at [px], a video frame stays (the view crops it to a square). */
    fun art(url: String, px: Int = 544): String = when {
        url.contains("googleusercontent.com") || url.contains("ggpht.com") ->
            if (Regex("=w\\d+-h\\d+[^/]*$").containsMatchIn(url)) url.replace(Regex("=w\\d+-h\\d+[^/]*$"), "=w$px-h$px-l90-rj")
            else if (Regex("=s\\d+[^/]*$").containsMatchIn(url)) url.replace(Regex("=s\\d+[^/]*$"), "=s$px") else url
        else -> url
    }

    private fun tidy(s: String): String = s.replace(Regex("\\s{2,}"), " ").trim().trim('-', '|', ':', '~', ',', '/').trim()
}
