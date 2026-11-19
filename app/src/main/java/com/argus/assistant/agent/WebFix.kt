package com.argus.assistant.agent

import org.json.JSONArray

/**
 * 0.53.3 search fixes (owner 2026-11-14, screenshots 23:41: "Searched the web · Failed: web search is blocked or failing",
 * "Read a page · Failed: couldn't open https://news.google.com/rss/articles/…", and results off the subject). Pure parts of
 * [Web]'s chain, tested on the JVM (WebFixTest):
 *
 *  - [Health]: a source that blocked us (bot check, HTTP 202/429/403, HTML instead of a feed) sits out a cool-down
 *    (90 s, doubling to 10 min) for EVERY search, so parallel searches stop hammering a source that is rate-limiting us
 *    (that is what turned 4 parallel searches into 2 "blocked" failures). A success clears it.
 *  - [Pace]: at least [Pace.GAP_MS] between two requests to the same source.
 *  - [relevant]: results that share none of the query's anchor words (a word with a digit, or one the user capitalised)
 *    or too few of its words are dropped; a source whose results are all off-topic counts as "nothing", so the chain moves
 *    on instead of handing the model unrelated pages to read.
 *  - [gnId] / [gnRequest] / [gnParse]: Google News RSS links (news.google.com/rss/articles/…) are a JavaScript redirect;
 *    they are decoded to the publisher's own URL before reading.
 *  - [readerWorth]: when a page can't be opened (error, 403/429/5xx, a bot check, an empty JS shell), it is read once
 *    through a reader service instead of failing.
 */
object WebFix {

    // ---------------------------------------------------------------- source health
    class Health(private val now: () -> Long = { System.currentTimeMillis() }) {
        private val until = HashMap<String, Long>()
        private val strikes = HashMap<String, Int>()

        @Synchronized fun ok(src: String) { until.remove(src); strikes.remove(src) }

        /** A block: cool down 90 s, 180 s, 360 s … capped at 10 min. */
        @Synchronized fun blocked(src: String) {
            val n = (strikes[src] ?: 0) + 1
            strikes[src] = n
            until[src] = now() + (BASE_MS shl (n - 1).coerceAtMost(3)).coerceAtMost(MAX_MS)
        }

        @Synchronized fun cooling(src: String): Boolean = (until[src] ?: 0L) > now()

        /** [all] minus the cooling ones; when every one is cooling, all of them (better a try than nothing). */
        fun <T> usable(all: List<T>, key: (T) -> String): List<T> = all.filter { !cooling(key(it)) }.ifEmpty { all }

        companion object { const val BASE_MS = 90_000L; const val MAX_MS = 600_000L }
    }

    /** True when a note from the chain means the source blocked us (as opposed to answering "no results"). */
    fun isBlockNote(note: String): Boolean = note.contains("blocked") || Regex("HTTP (202|403|429|503)").containsMatchIn(note)

    object Pace {
        const val GAP_MS = 700L
        private val last = HashMap<String, Long>()
        /** How long to wait before hitting [src] now (and books the slot). */
        @Synchronized fun wait(src: String, now: Long = System.currentTimeMillis()): Long {
            val at = maxOf(now, (last[src] ?: 0L) + GAP_MS)
            last[src] = at
            return at - now
        }
    }

    // ---------------------------------------------------------------- relevance
    private val stop = ("the and for with that this what how who when where why which are was can does did get use about from into " +
        "your you our their its please find search look show tell give latest recent current new news official info information " +
        "online best top free near any some all more most site page web").split(" ").toSet()

    /** The query's words worth matching: no filler, at least 3 letters (2 with a digit). */
    fun terms(q: String): List<String> = q.split(Regex("[^\\p{L}\\p{N}]+")).map { it.lowercase() }
        .filter { (it.length >= 3 || (it.length >= 2 && it.any(Char::isDigit))) && it !in stop }.distinct()

    /** Anchor words: ones with a digit, or capitalised by the user mid-query (names: "Dots", "Preview", "llm1"). */
    fun anchors(q: String): List<String> {
        val toks = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return toks.withIndex().filter { (i, t) ->
            val low = t.lowercase()
            low !in stop && ((t.any(Char::isDigit) && t.any(Char::isLetter)) || (i > 0 && t.length >= 3 && t[0].isUpperCase()) ||
                (t.length >= 2 && t.all { it.isUpperCase() || it.isDigit() } && t.any(Char::isLetter)))
        }.map { it.value.lowercase() }.distinct()
    }

    private fun stem(t: String) = if (t.length >= 5 && t.endsWith("s") && !t.endsWith("ss")) t.dropLast(1) else t
    private fun has(text: String, term: String) = text.contains(stem(term))

    /** One numbered result block ("1. title\n   url\n   snippet") is about [q]. */
    fun about(q: String, block: String): Boolean {
        val ts = terms(q); if (ts.isEmpty()) return true
        val text = block.lowercase()
        val an = anchors(q).filter { it in ts || it.any(Char::isDigit) }
        if (an.isNotEmpty() && an.none { has(text, it) }) return false
        val need = when { ts.size <= 2 -> 1; else -> (ts.size + 2) / 3 }
        return ts.count { has(text, it) } >= need
    }

    /** Splits a numbered list into its blocks (keeps a heading line such as "GitHub repositories:" as [head]). */
    fun blocks(list: String): Pair<String, List<String>> {
        val lines = list.lines()
        val head = StringBuilder(); val out = ArrayList<StringBuilder>()
        for (l in lines) {
            if (Regex("^\\d+\\. ").containsMatchIn(l)) out += StringBuilder(l.replaceFirst(Regex("^\\d+\\. "), ""))
            else if (out.isEmpty()) { if (l.isNotBlank()) head.append(l).append('\n') }
            else out.last().append('\n').append(l)
        }
        return head.toString() to out.map { it.toString().trimEnd() }
    }

    /** [list] with off-topic results dropped and renumbered; null when none is about [q]. */
    fun relevant(q: String, list: String): String? {
        val (head, bs) = blocks(list)
        if (bs.isEmpty()) return list
        val keep = bs.filter { about(q, it) }
        if (keep.isEmpty()) return null
        return head + keep.withIndex().joinToString("\n", postfix = "\n") { (i, b) -> "${i + 1}. $b" }
    }

    // ---------------------------------------------------------------- Google News links
    private val gn = Regex("""^https?://news\.google\.com/(?:rss/)?(?:articles|read)/([A-Za-z0-9_-]+)""")

    /** The article id of a Google News link, or null. */
    fun gnId(url: String): String? = gn.find(url.trim())?.groupValues?.get(1)

    /** The signature and timestamp on the Google News article page (data-n-a-sg / data-n-a-ts). */
    fun gnParams(html: String): Pair<String, String>? {
        val sg = Regex("""data-n-a-sg="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
        val ts = Regex("""data-n-a-ts="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
        return sg to ts
    }

    /** The batchexecute form body that asks Google News for the article's real URL. */
    fun gnRequest(id: String, ts: String, sg: String): String {
        val inner = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,\"US:en\",null,1,null,null,null,null,null,0,1],\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0],\"$id\",$ts,\"$sg\"]"
        val req = "[[[\"Fbv4je\"," + org.json.JSONObject.quote(inner) + ",null,\"generic\"]]]"
        return "f.req=" + java.net.URLEncoder.encode(req, "UTF-8")
    }

    /** The publisher URL out of the batchexecute answer, or null. */
    fun gnParse(body: String): String? = try {
        val json = body.substringAfter("\n\n", body).trim().let { s -> s.substring(s.indexOf('[')) }
        val outer = JSONArray(json)
        var url: String? = null
        for (i in 0 until outer.length()) {
            val row = outer.optJSONArray(i) ?: continue
            val payload = row.optString(2, "")
            if (payload.startsWith("[")) { val p = JSONArray(payload); url = p.optString(1).takeIf { it.startsWith("http") }; if (url != null) break }
        }
        url
    } catch (_: Exception) { Regex("""https?://(?!news\.google\.com)[^"\\\s]+""").find(body)?.value }

    // ---------------------------------------------------------------- reader fallback
    const val READER = "https://r.jina.ai/"

    /** Worth a second try through the reader: an error, a block code, a bot check, or an empty script-only page. */
    fun readerWorth(result: String): Boolean {
        if (result.startsWith("Error: couldn't open")) return true
        val code = Regex("^Error: HTTP (\\d{3})").find(result)?.groupValues?.get(1)?.toIntOrNull()
        if (code != null) return code == 401 || code == 403 || code == 406 || code == 429 || code >= 500
        val body = result.substringBefore("\nImage: ")
        if (body.length < 400 && Regex("(?i)enable javascript|captcha|access denied|are you a robot|just a moment|attention required").containsMatchIn(body)) return true
        return body.lines().count { it.length > 40 } < 2 && body.length < 300
    }
}
