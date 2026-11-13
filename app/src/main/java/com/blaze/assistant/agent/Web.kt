package com.blaze.assistant.agent

import com.blaze.assistant.connect.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/** Keyless web search (DuckDuckGo HTML) and page reading, so Blaze can look things up without opening a browser. */
object Web {
    /** Feeds (Bing, Google News) answer a desktop browser with RSS more reliably than a phone. */
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private const val UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

    /**
     * v0.32.0 ("it failed to find REA"): DuckDuckGo's HTML endpoint now often answers phones with a bot check, which used
     * to read as "No results" and made Blaze conclude real things don't exist. Now: DuckDuckGo, then Bing (RSS), then
     * GitHub's repo search for code-ish queries; a block is reported as a block, never as "nothing exists".
     *
     * 0.53.0 (owner, 13 Nov 2026: "web search must ALWAYS work"; the phone got 'No results for "latest news NVIDIA CEO" on
     * DuckDuckGo or Bing'). Causes: DuckDuckGo answers rate-limited clients with HTTP 202 and a page without results (not
     * always with the old bot-check words), and Bing sometimes answers format=rss with an HTML page (consent / locale) that
     * parsed as "no results". Both are blocks now ([ddgBlocked], [isRss]), and the chain goes on ([SearchChain] order):
     * DuckDuckGo → Bing RSS (en-US) → Bing News RSS → Google News RSS → Wikipedia → the same with simpler words → GitHub.
     * "No results" is said only when every source answered and none had anything.
     */
    suspend fun search(q: String, n: Int = 6): String = withContext(Dispatchers.IO) {
        val notes = ArrayList<String>()
        val newsy = SearchChain.newsy(q)
        for (src in SearchChain.order(newsy)) source(src, q, n, notes)?.let { return@withContext it }
        // the same sources again with simpler words ("latest news NVIDIA CEO" -> "NVIDIA CEO")
        SearchChain.simpler(q)?.let { s ->
            for (src in SearchChain.retryOrder(newsy)) source(src, s, n, notes)?.let { return@withContext "Results for \"$s\" (simpler words):\n$it" }
        }
        github(q, n, notes, force = false)?.let { return@withContext it }
        if (notes.any { it.contains("blocked") || it.startsWith("Error") })
            "Error: web search is blocked or failing right now (${notes.joinToString("; ")}). This does NOT mean nothing exists. " +
                "Try another wording, or fetch_page a likely URL directly (for example https://github.com/search?q=${URLEncoder.encode(q, "UTF-8")}&type=repositories)."
        else "No results for \"$q\" on ${SearchChain.NAMES}. Try different words before concluding it doesn't exist."
    }

    private fun source(src: SearchChain.Source, q: String, n: Int, notes: MutableList<String>): String? = when (src) {
        SearchChain.Source.DDG -> ddg(q, n, notes)
        SearchChain.Source.BING -> rss("Bing", "https://www.bing.com/search?format=rss&setlang=en-US&cc=US&q=", q, n, notes)
        SearchChain.Source.BING_NEWS -> rss("Bing News", "https://www.bing.com/news/search?format=RSS&setlang=en-US&cc=US&q=", q, n, notes)
        SearchChain.Source.GOOGLE_NEWS -> rss("Google News", "https://news.google.com/rss/search?hl=en-US&gl=US&ceid=US:en&q=", q, n, notes)
        SearchChain.Source.WIKIPEDIA -> wiki(q, n, notes)
    }

    private fun ddg(q: String, n: Int, notes: MutableList<String>): String? {
        val (code, html) = try {
            Http.req("GET", "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8"), headers = mapOf("User-Agent" to UA), timeoutMs = 8_000)
        } catch (e: Exception) { notes += "Error: DuckDuckGo ${e.message}"; return null }
        if (code !in 200..299) { notes += "Error: DuckDuckGo HTTP $code"; return null }
        if (ddgBlocked(code, html)) { notes += "DuckDuckGo blocked (bot check)"; return null }
        val r = parseDdg(html, n)
        if (r == null) notes += "DuckDuckGo: no results"
        return r
    }

    /** DuckDuckGo's HTML results as a numbered list (pure; tested). */
    fun parseDdg(html: String, n: Int): String? {
        val blocks = html.split("class=\"result__body").drop(1)
        val out = StringBuilder()
        var i = 0
        for (b in blocks) {
            val link = Regex("class=\"result__a\" href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>").find(b) ?: continue
            var url = link.groupValues[1]
            Regex("uddg=([^&]+)").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.contains("duckduckgo.com/y.js")) continue
            val title = strip(link.groupValues[2])
            val snip = Regex("class=\"result__snippet\"[^>]*>([\\s\\S]*?)</a>").find(b)?.groupValues?.get(1)?.let { strip(it) }.orEmpty()
            out.append("${++i}. $title\n   $url\n   $snip\n")
            if (i >= n) break
        }
        return if (i == 0) null else out.toString()
    }

    /** A bot-check / captcha page instead of results. */
    fun blocked(html: String): Boolean = html.contains("anomaly-modal") || html.contains("challenge-form") ||
        html.contains("bots use DuckDuckGo") || (html.contains("captcha", ignoreCase = true) && !html.contains("result__body"))

    /**
     * 0.53.0: DuckDuckGo's rate limit is HTTP 202 with a page that has no results; a page with no results that doesn't
     * say "No results" is a block too (a real empty answer has DuckDuckGo's own no-results text).
     */
    fun ddgBlocked(code: Int, html: String): Boolean = blocked(html) || (!html.contains("result__body") &&
        (code == 202 || !Regex("(?i)no (?:more )?results").containsMatchIn(html)))

    /** True when [body] is really an RSS feed (Bing sometimes answers format=rss with an HTML page). */
    fun isRss(body: String): Boolean { val t = body.trimStart().take(400); return t.startsWith("<?xml") || t.contains("<rss") }

    private fun rss(name: String, base: String, q: String, n: Int, notes: MutableList<String>): String? {
        val (code, xml) = try {
            Http.req("GET", base + URLEncoder.encode(q, "UTF-8"), headers = mapOf("User-Agent" to DESKTOP_UA, "Accept-Language" to "en-US,en;q=0.9"), timeoutMs = 8_000)
        } catch (e: Exception) { notes += "Error: $name ${e.message}"; return null }
        if (code !in 200..299) { notes += "Error: $name HTTP $code"; return null }
        if (!isRss(xml)) { notes += "$name blocked (no feed)"; return null }
        val r = parseRss(xml, n)
        if (r == null) notes += "$name: no results"
        return r
    }

    /**
     * An RSS feed's items as a numbered list (pure; tested): Bing, Bing News and Google News. A Bing News click link is
     * unwrapped to the article ("apiclick.aspx?…&url=…"); a dated item carries its date and source in the snippet.
     */
    fun parseRss(xml: String, n: Int): String? {
        val out = StringBuilder(); var i = 0
        for (item in xml.split("<item>").drop(1)) {
            fun tag(t: String) = Regex("<$t(?:\\s[^>]*)?>([\\s\\S]*?)</$t>").find(item)?.groupValues?.get(1)?.let { strip(it.replace("<![CDATA[", "").replace("]]>", "")) }.orEmpty()
            var url = tag("link"); if (url.isBlank() || url.contains("bing.com/search")) continue
            if (url.contains("bing.com/news/apiclick")) Regex("[?&]url=([^&]+)").find(url)?.let { m -> try { url = URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (_: Exception) { } }
            val date = tag("pubDate").let { d -> Regex("""^\w{3}, (\d{1,2} \w{3} \d{4})""").find(d)?.groupValues?.get(1) ?: "" }
            val from = tag("News:Source").ifBlank { tag("source") }
            // Google News escapes its HTML description (&lt;a…), so it is stripped twice; when it only repeats the title it is dropped.
            val desc = strip(tag("description")).let { if (from.isNotBlank() && it.startsWith(tag("title").substringBefore(" - ").take(30))) "" else it }
            // a date only on news feeds (Bing's web feed dates are crawl dates)
            val meta = if (from.isBlank()) "" else listOf(date, from).filter { it.isNotBlank() }.joinToString(", ")
            out.append("${++i}. ${tag("title")}\n   $url\n   ${if (meta.isNotEmpty()) "($meta) " else ""}$desc\n")
            if (i >= n) break
        }
        return if (i == 0) null else out.toString().replace(Regex(" +\n"), "\n")
    }

    private fun wiki(q: String, n: Int, notes: MutableList<String>): String? {
        val (code, body) = try {
            Http.req("GET", "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=${n.coerceAtMost(5)}&srsearch=" + URLEncoder.encode(q, "UTF-8"),
                headers = mapOf("User-Agent" to "ArgusMobile/0.53 (https://gitlab.com/blazeinchue/argus-mobile)"), timeoutMs = 8_000)
        } catch (e: Exception) { notes += "Error: Wikipedia ${e.message}"; return null }
        if (code !in 200..299) { notes += "Error: Wikipedia HTTP $code"; return null }
        val r = parseWiki(body, n)
        if (r == null) notes += "Wikipedia: no results"
        return r
    }

    /** Wikipedia's search API answer as a numbered list (pure; tested). */
    fun parseWiki(json: String, n: Int): String? {
        val arr = try { JSONObject(json).optJSONObject("query")?.optJSONArray("search") } catch (_: Exception) { null } ?: return null
        if (arr.length() == 0) return null
        val out = StringBuilder()
        for (k in 0 until minOf(n, arr.length())) { val o = arr.getJSONObject(k); val t = o.optString("title")
            out.append("${k + 1}. $t (Wikipedia)\n   https://en.wikipedia.org/wiki/${URLEncoder.encode(t.replace(' ', '_'), "UTF-8").replace("%2F", "/")}\n   ${strip(o.optString("snippet")).take(240)}\n") }
        return out.toString()
    }

    private val codeish = Regex("(?i)\\b(github|repo|repository|library|sdk|npm|pip|package|cli|mcp|skill|open[- ]source|framework|plugin)\\b")

    private fun github(q: String, n: Int, notes: MutableList<String>, force: Boolean): String? {
        if (!force && !codeish.containsMatchIn(q) && notes.none { it.contains("blocked") || it.startsWith("Error") }) return null
        val words = q.replace(Regex("(?i)\\b(github|repo|repository|skill|agent)\\b"), " ").trim().ifBlank { q }
        val (code, body) = try {
            Http.req("GET", "https://api.github.com/search/repositories?per_page=$n&q=" + URLEncoder.encode(words, "UTF-8"),
                headers = mapOf("User-Agent" to UA, "Accept" to "application/vnd.github+json"), timeoutMs = 12_000)
        } catch (e: Exception) { notes += "Error: GitHub ${e.message}"; return null }
        if (code !in 200..299) { notes += "Error: GitHub HTTP $code"; return null }
        val items = try { JSONObject(body).optJSONArray("items") } catch (_: Exception) { null } ?: return null
        if (items.length() == 0) return null
        val out = StringBuilder("GitHub repositories:\n")
        for (k in 0 until minOf(n, items.length())) { val o = items.getJSONObject(k)
            out.append("${k + 1}. ${o.optString("full_name")} (${o.optInt("stargazers_count")} stars)\n   ${o.optString("html_url")}\n   ${o.optString("description").take(200)}\n") }
        return out.toString()
    }

    suspend fun fetch(url: String, max: Int = 9000): String = withContext(Dispatchers.IO) {
        val (code, body) = try { Http.req("GET", url, headers = mapOf("User-Agent" to UA, "Accept" to "text/html,application/json,text/plain"), timeoutMs = 20_000, strict = false) }
            catch (e: Exception) { return@withContext "Error: couldn't open $url (${e.message})" }
        if (code !in 200..299) return@withContext "Error: HTTP $code from $url"
        val t = body.trimStart()
        val text = if (t.startsWith("{") || t.startsWith("[")) t else {
            val titled = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.let { strip(it) }.orEmpty()
            val main = body.replace(Regex("<(script|style|nav|footer|header|svg|noscript)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<(br|p|div|li|h[1-6]|tr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            (if (titled.isNotBlank()) "# $titled\n" else "") + strip(main).lines().map { it.trim() }.filter { it.length > 1 }.joinToString("\n")
        }
        val imgs = if (t.startsWith("{") || t.startsWith("[")) "" else images(body, url)
        (if (text.length > max) text.take(max) + "\n…(cut, ${text.length} chars total)" else text) + imgs
    }

    /**
     * 0.46.2: the page's own pictures (og:image / twitter:image, else the first large <img> in its article) as "Image: <url> (WxH)"
     * lines at the end of fetch_page's result; the reply shows them in a small strip with the domain credited (ReplySources).
     */
    fun images(html: String, pageUrl: String): String {
        fun abs(u: String) = try { java.net.URI(pageUrl).resolve(u.trim().replace("&amp;", "&")).toString() } catch (_: Exception) { "" }
        fun meta(p: String) = Regex("""<meta[^>]+(?:property|name)=["']$p["'][^>]*content=["']([^"']+)["']|<meta[^>]+content=["']([^"']+)["'][^>]*(?:property|name)=["']$p["']""", RegexOption.IGNORE_CASE)
            .find(html)?.let { m -> m.groupValues[1].ifBlank { m.groupValues[2] } }.orEmpty()
        val out = LinkedHashMap<String, String>()
        val ow = meta("og:image:width").toIntOrNull() ?: 0; val oh = meta("og:image:height").toIntOrNull() ?: 0
        listOf(meta("og:image"), meta("og:image:secure_url"), meta("twitter:image"), meta("twitter:image:src")).map { abs(it) }.filter { it.isNotBlank() }
            .forEach { u -> if (ReplySources.okImage(u, ow, oh)) out.putIfAbsent(ReplySources.norm(u), u + if (ow > 0 && oh > 0) " (${ow}x$oh)" else "") }
        if (out.isEmpty()) {
            val art = Regex("<article[\\s\\S]*?</article>", RegexOption.IGNORE_CASE).find(html)?.value ?: ""
            Regex("""<img[^>]+>""", RegexOption.IGNORE_CASE).findAll(art).take(12).forEach { tag ->
                val src = Regex("""\ssrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag.value)?.groupValues?.get(1).orEmpty()
                val w = Regex("""\swidth=["']?(\d+)""", RegexOption.IGNORE_CASE).find(tag.value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val h = Regex("""\sheight=["']?(\d+)""", RegexOption.IGNORE_CASE).find(tag.value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val u = abs(src)
                if (out.size < 2 && w >= 300 && ReplySources.okImage(u, w, h)) out.putIfAbsent(ReplySources.norm(u), "$u (${w}x${h})")
            }
        }
        return if (out.isEmpty()) "" else "\n" + out.values.take(2).joinToString("\n") { "Image: $it" }
    }

    private fun strip(h: String) = h.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace(Regex("[ \\t]+"), " ").trim()

    val names = setOf("web_search", "fetch_page")
    fun tools(): List<JSONObject> = listOf(
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "web_search")
            .put("description", "Search the web (no browser needed). Returns titles, links and snippets. Use for news, facts, prices, how-tos.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("query", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("query"))))),
        JSONObject().put("type", "function").put("function", JSONObject().put("name", "fetch_page")
            .put("description", "Read a web page or JSON API as plain text.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("url", JSONObject().put("type", "string")))
                .put("required", JSONArray(listOf("url")))))
    )
}
