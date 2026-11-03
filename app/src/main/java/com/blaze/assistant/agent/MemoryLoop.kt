package com.blaze.assistant.agent

/**
 * 0.52.1 (owner: the song-download loop). "Download that song" turns kept calling memory_search / recall_memory over
 * and over (each lookup found a hint, the model looked again) and never called media_download. Now:
 *  - every turn has at most [MAX_LOOKUPS] memory lookups; the next one is answered "Not run" with what to do instead;
 *  - once a concrete song / artist is known (the user named it, a lookup returned one, or a download was asked for),
 *    a song turn gets no more memory lookups at all: the answer tells the model to download it now.
 * Pure Kotlin; MemoryLoopTest.
 */
object MemoryLoop {
    const val MAX_LOOKUPS = 3
    val LOOKUPS = setOf("memory_search", "recall_memory", "search_past_chats")
    val DOWNLOADS = setOf("media_download", "download_file")

    fun isLookup(name: String) = name in LOOKUPS

    private val songWords = Regex("""(?i)\b(song|songs|track|tracks|music|mp3|audio|album|single|tune|banger)\b""")
    private val getWords = Regex("""(?i)\b(download|get|save|grab|fetch|rip|find)\b""")

    /** The request is about getting a song / track. */
    fun songAsk(user: String): Boolean = songWords.containsMatchIn(user) && getWords.containsMatchIn(user) ||
        Regex("""(?i)\bdownload\b""").containsMatchIn(user) && concrete(user) != null

    // "Title by Artist", "Artist - Title", "“Title”" / "\"Title\""
    private val byForm = Regex("""(?i)(?:^|[\s:“"'])([\p{L}\p{N}][\p{L}\p{N}'’&.!?,\- ]{1,60}?)\s+by\s+([\p{L}\p{N}][\p{L}\p{N}'’&.\- ]{1,40})""")
    private val dashForm = Regex("""([\p{Lu}\p{N}][\p{L}\p{N}'’&. ]{1,40}?)\s+[-–—]\s+([\p{L}\p{N}][\p{L}\p{N}'’&.!?, ]{1,60})""")
    private val quoted = Regex("""[“"]([^”"\n]{2,80})[”"]""")
    private val vague = Regex("""(?i)^(?:the |that |this |a |my |some |any |it|one|song|track|music|download|get|save|please|can you|could you|i liked|i like|from yesterday|again)+\s*$""")

    /** A concrete "title by artist" (or quoted title) in [text], or null. */
    fun concrete(text: String): String? {
        val t = text.trim()
        byForm.find(t)?.let { m ->
            val title = m.groupValues[1].trim().replace(Regex("""(?i)^(?:please\s+)?(?:download|get|save|grab|fetch|find)\s+(?:me\s+)?(?:the\s+)?(?:song\s+|track\s+)?"""), "").trim(' ', '"', '“', '”')
            val artist = m.groupValues[2].trim().trimEnd('.', '!', '?', ',')
            if (title.length >= 2 && !vague.matches(title) && !Regex("""(?i)^(?:the )?(?:way|end|time|day|now|me|you|us)$""").matches(artist)) return "$title by $artist"
        }
        quoted.find(t)?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 2 && !vague.matches(it) }?.let { return it }
        dashForm.find(t)?.let { m -> return "${m.groupValues[2].trim().trimEnd('.', ',')} by ${m.groupValues[1].trim()}" }
        return null
    }

    /** What a finished tool call taught us about the song (for a song turn): the concrete song, or null. */
    fun learn(name: String, args: String, result: String): String? = when {
        name in DOWNLOADS -> concrete(args) ?: args.take(80).ifBlank { null }
        isLookup(name) && !result.startsWith("Not run") -> concrete(result.take(2000))
        else -> null
    }

    /**
     * Gate for a memory lookup in a turn whose user text is [user], after [lookups] lookups, with [resolved] the song
     * known so far ("" = none). Null = run it; otherwise the tool's answer.
     */
    fun gate(name: String, user: String, lookups: Int, resolved: String): String? {
        if (!isLookup(name)) return null
        if (resolved.isNotBlank() && songAsk(user)) return "Not run: the song is already known ($resolved). Don't check memory again; download it now with media_download, then tell the user where it saved."
        if (lookups >= MAX_LOOKUPS) return "Not run: memory was already checked $lookups times this turn. Use what you found and carry on with the task; ask the user only if something essential is still missing."
        return null
    }
}
