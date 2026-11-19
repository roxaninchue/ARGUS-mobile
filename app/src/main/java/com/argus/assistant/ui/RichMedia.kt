package com.blaze.assistant.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 0.52.0 smarter rich replies, the pure part (RichMediaTest): four more blocks a reply can carry as fenced JSON, so
 * answers show pictures, lists with artwork and cards instead of walls of text. Each parser returns null on anything it
 * can't use (the caller then shows the salvaged text, never raw JSON).
 *
 *  ```gallery  {"title":"Amapiano 2025","layout":"list|grid","items":[{"title":"Song","subtitle":"Artist · 2025","image":"https://…","url":"https://…","note":"why"}]}
 *  ```links    {"title":"Read more","items":[{"title":"..","url":"https://…","source":"bbc.com","image":"https://…","summary":".."}]}
 *  ```doc      {"title":"Solar in Zambia","path":"/storage/…/solar.md","format":"md","summary":"5 sections · 1,240 words"}
 *  ```details  {"title":"How I worked it out","body":"Markdown shown when opened"}
 *
 * Charts (bar / line / pie / scatter), tables, comparisons, inline pictures between paragraphs and picture rows were
 * already there (RichKit, RichReplies); [PROMPT] tells the model to prefer all of them and keep its text short.
 */
object RichMedia {
    const val MAX_ITEMS = 24
    const val MAX_LINKS = 8

    data class Item(val title: String, val subtitle: String = "", val image: String = "", val url: String = "", val note: String = "")
    data class Gallery(val title: String, val items: List<Item>, val grid: Boolean)
    data class Link(val title: String, val url: String, val source: String = "", val image: String = "", val summary: String = "")
    data class Links(val title: String, val items: List<Link>)
    data class Doc(val title: String, val path: String, val format: String, val summary: String)
    data class Details(val title: String, val body: String)

    private fun obj(body: String): JSONObject? = RichKit.json(body) as? JSONObject
    private fun JSONObject.s(vararg k: String): String = k.firstNotNullOfOrNull { key -> optString(key).trim().takeIf { it.isNotEmpty() && it != "null" } } ?: ""
    private fun httpsOrEmpty(u: String): String = u.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()
    private fun arr(o: JSONObject, vararg k: String): JSONArray? = k.firstNotNullOfOrNull { o.optJSONArray(it) }

    fun gallery(body: String): Gallery? {
        val o = obj(body) ?: return null
        val a = arr(o, "items", "songs", "tracks", "results", "list") ?: return null
        val items = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { i ->
            val t = i.s("title", "name", "track", "song"); if (t.isEmpty()) null
            else Item(t, i.s("subtitle", "artist", "author", "by", "year"), httpsOrEmpty(i.s("image", "artwork", "art", "cover", "thumbnail", "poster")),
                httpsOrEmpty(i.s("url", "link", "href")), i.s("note", "why", "description"))
        }.take(MAX_ITEMS)
        if (items.isEmpty()) return null
        val layout = o.s("layout").lowercase()
        // a grid when asked, or when every item is a picture with no subtitle (posters, wallpapers); songs read best as a list
        val grid = layout == "grid" || (layout != "list" && items.all { it.image.isNotEmpty() && it.subtitle.isEmpty() })
        return Gallery(o.s("title"), items, grid)
    }

    fun links(body: String): Links? {
        val o = obj(body) ?: return null
        val a = arr(o, "items", "links", "cards", "sources") ?: return null
        val items = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { i ->
            val url = httpsOrEmpty(i.s("url", "link", "href")); if (url.isEmpty()) return@mapNotNull null
            Link(i.s("title", "name").ifEmpty { host(url) }, url, i.s("source", "site").ifEmpty { host(url) }, httpsOrEmpty(i.s("image", "thumbnail")), i.s("summary", "description", "snippet"))
        }.distinctBy { it.url }.take(MAX_LINKS)
        return if (items.isEmpty()) null else Links(o.s("title"), items)
    }

    fun doc(body: String): Doc? {
        val o = obj(body) ?: return null
        val title = o.s("title", "name"); val path = o.s("path", "file", "uri")
        if (title.isEmpty() || path.isEmpty()) return null
        return Doc(title, path, o.s("format").ifEmpty { path.substringAfterLast('.', "md") }.lowercase(), o.s("summary"))
    }

    fun details(body: String): Details? {
        val o = obj(body) ?: return null
        val t = o.s("title", "summary", "label"); val b = o.s("body", "content", "text", "md")
        return if (t.isEmpty() || b.isEmpty()) null else Details(t, b)
    }

    /** "bbc.co.uk" from a url. */
    fun host(url: String): String = url.substringAfter("://").substringBefore('/').removePrefix("www.").take(40)

    /** "MD" / "PDF" badge on a doc card. */
    fun badge(format: String): String = format.uppercase().take(4).ifEmpty { "DOC" }

    /** The prompt lines (appended to RichReplies.PROMPT; static so the cached prefix holds). */
    const val PROMPT = " MORE VISUAL BLOCKS (0.52): replies are read on a phone, so show, don't tell: keep prose to 1-3 short sentences around the visuals " +
        "and prefer a picture, chart, table or card over a paragraph whenever one carries the answer. " +
        "A list of songs / films / books / products / places -> a ```gallery {\"title\":\"..\",\"items\":[{\"title\":\"..\",\"subtitle\":\"artist · year\",\"image\":\"https artwork url\",\"url\":\"https link\",\"note\":\"one line\"}]} " +
        "(\"layout\":\"grid\" for posters / wallpapers); links worth opening -> ```links {\"items\":[{\"title\":\"..\",\"url\":\"https…\",\"source\":\"site\",\"image\":\"\",\"summary\":\"one line\"}]}; " +
        "a document you saved -> ```doc {\"title\":\"..\",\"path\":\"the saved path\",\"format\":\"md|pdf|docx\",\"summary\":\"5 sections · 1,200 words\"}; " +
        "long detail most readers can skip (working, raw data, full lists) -> ```details {\"title\":\"..\",\"body\":\"markdown\"}. " +
        "Put a relevant real picture (![alt](https url)) between paragraphs when it helps. Only real urls you found; leave image empty rather than invent one."
}
