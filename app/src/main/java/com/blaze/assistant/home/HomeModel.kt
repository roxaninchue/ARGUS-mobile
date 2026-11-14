package com.blaze.assistant.home

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.29.0 Home board: the pure model. A [Widget] is a definition (what to show and how big); its last fetched content is a
 * [WidgetData] (a headline, an optional big value and a few lines). Both live in blaze.db (`home_widgets`, `home_cache`,
 * see [HomeStore]). Everything here is plain Kotlin + org.json so it is unit tested on the JVM.
 */
data class Widget(
    val id: String,
    val type: String,
    val title: String,
    val category: String,
    /** Type-specific settings as a JSON object string (city, query, channel, team, ids…). */
    val config: String = "{}",
    /** S, M or L. */
    val size: String = WidgetSize.M,
    val order: Int = 0,
    val hidden: Boolean = false,
    val created: Long = 0L,
    val updated: Long = 0L,
    /** Times the user opened something from it (used to refine suggestions). */
    val taps: Int = 0,
) {
    fun cfg(): JSONObject = try { JSONObject(config) } catch (_: Exception) { JSONObject() }
    fun opt(key: String): String = cfg().optString(key).trim()
}

object WidgetSize {
    const val S = "S"; const val M = "M"; const val L = "L"
    fun norm(s: String?): String = when (s?.trim()?.uppercase()) { "S", "SMALL" -> S; "L", "LARGE", "BIG" -> L; else -> M }
    /** How many lines a card of this size shows. */
    fun lines(size: String): Int = when (norm(size)) { S -> 1; L -> 6; else -> 3 }
    fun label(size: String): String = when (norm(size)) { S -> "Small"; L -> "Large"; else -> "Medium" }
}

/** One line of a widget: a title, an optional second line, an optional link (or "app:<package>"), an optional right-hand note. */
data class WidgetLine(val title: String, val sub: String = "", val url: String = "", val note: String = "", val image: String = "") {
    fun toJson(): JSONObject = JSONObject().put("t", title).put("s", sub).put("u", url).put("n", note).also { if (image.isNotBlank()) it.put("i", image) }
    companion object {
        fun from(o: JSONObject): WidgetLine = WidgetLine(o.optString("t"), o.optString("s"), o.optString("u"), o.optString("n"), o.optString("i"))
    }
}

/**
 * A widget's content. [error] is a plain sentence-case line shown on the card (never a stack trace); [fetched] is when the
 * content was last fetched successfully (0 = never). [action] asks the card for a button ("location", "usage", "calendar").
 */
data class WidgetData(
    val headline: String = "",
    val value: String = "",
    val change: String = "",
    val lines: List<WidgetLine> = emptyList(),
    val fetched: Long = 0L,
    val error: String = "",
    val action: String = "",
    val link: String = "",
    /** 0.47 weather board: today's sunset at the place, "HH:mm" in its local time ("" when unknown or not weather). */
    val sunset: String = "",
) {
    val empty: Boolean get() = headline.isBlank() && value.isBlank() && lines.isEmpty()

    fun toJson(): String = JSONObject().put("h", headline).put("v", value).put("c", change).put("a", action).put("l", link)
        .put("lines", JSONArray(lines.map { it.toJson() })).also { if (sunset.isNotBlank()) it.put("ss", sunset) }.toString()

    companion object {
        fun parse(json: String, fetched: Long, error: String): WidgetData = try {
            val o = JSONObject(json.ifBlank { "{}" })
            val a = o.optJSONArray("lines") ?: JSONArray()
            WidgetData(o.optString("h"), o.optString("v"), o.optString("c"),
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(WidgetLine::from) }, fetched, error, o.optString("a"), o.optString("l"),
                sunset = o.optString("ss"))
        } catch (_: Exception) { WidgetData(fetched = fetched, error = error) }

        fun failed(msg: String, action: String = ""): WidgetData = WidgetData(error = msg, action = action)
    }
}

/** The widget types and the categories they stack under by default. */
object WidgetTypes {
    const val WEATHER = "weather"
    const val NEWS = "news"
    const val CREATORS = "creators"
    const val SPORTS = "sports"
    const val PRICES = "prices"
    const val CALENDAR = "calendar"
    const val ROUTINES = "routines"
    const val DEADLINES = "deadlines"
    const val GITHUB = "github"
    const val APPS = "apps"
    const val CUSTOM = "custom"
    /** Owner 2026-11-14: "create a to-do list on my dashboard" → a checklist on Life ([HomeTodo]). */
    const val TODO = "todo"
    /** The "tell me what you're into" starter card (opens the interest picker). */
    const val INTO = "into"

    val all = listOf(WEATHER, NEWS, CREATORS, SPORTS, PRICES, CALENDAR, ROUTINES, DEADLINES, GITHUB, APPS, CUSTOM, INTO, TODO)

    fun norm(t: String?): String? {
        val l = t.orEmpty().trim().lowercase().replace(' ', '_')
        return when (l) {
            in all -> l
            "forecast" -> WEATHER
            "rss", "feed", "headlines", "news_topic", "topic" -> NEWS
            "youtube", "creator", "channel", "videos", "tiktok" -> CREATORS
            "sport", "scores", "team", "football", "soccer" -> SPORTS
            "price", "crypto", "fx", "currency", "stock", "stocks", "exchange_rate" -> PRICES
            "events", "agenda" -> CALENDAR
            "routine", "reminders", "reminder" -> ROUTINES
            "deadline", "study", "exams", "homework" -> DEADLINES
            "repo", "git" -> GITHUB
            "most_used_apps", "usage", "app_usage" -> APPS
            "to_do", "todos", "todo_list", "to_do_list", "checklist", "tasks", "task_list", "shopping_list" -> TODO
            "text", "list", "link", "note" -> CUSTOM
            else -> null
        }
    }

    fun defaultCategory(type: String): String = when (type) {
        WEATHER -> "Weather"
        NEWS -> "News"
        CREATORS -> "Creators"
        SPORTS -> "Sports"
        PRICES -> "Finance"
        CALENDAR, ROUTINES, DEADLINES -> "Today"
        GITHUB -> "Tech"
        APPS -> "Phone"
        INTO -> "For you"
        TODO -> "To-do"
        else -> "Notes"
    }

    fun label(type: String): String = when (type) {
        WEATHER -> "Weather"; NEWS -> "News"; CREATORS -> "Creators"; SPORTS -> "Sports"; PRICES -> "Prices"
        CALENDAR -> "Calendar"; ROUTINES -> "Next routine"; DEADLINES -> "Deadlines"; GITHUB -> "GitHub"
        APPS -> "Most-used apps"; INTO -> "For you"; TODO -> "To-do list"; else -> "Custom"
    }

    /** Types that need the network to refresh (others are read on the phone). */
    fun online(type: String): Boolean = type in setOf(WEATHER, NEWS, CREATORS, SPORTS, PRICES, GITHUB)
}

/** "Updated 12 min ago", "Updated just now", "Updated 3 h ago", "Updated yesterday". */
object Ago {
    fun of(then: Long, now: Long): String {
        if (then <= 0L) return "Not updated yet"
        val m = ((now - then).coerceAtLeast(0L)) / 60_000L
        return "Updated " + when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> "${m / 60} h ago"
            m < 48 * 60 -> "yesterday"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    /** Content older than this shows the subtle "Stale" tag when it couldn't be refreshed. */
    const val STALE_MS = 3 * 60 * 60_000L
    fun stale(fetched: Long, now: Long, offline: Boolean, error: String): Boolean =
        fetched > 0L && (offline || error.isNotBlank() || now - fetched > STALE_MS)
}

/**
 * Owner 2026-11-14: a to-do list on the Life dashboard. Its items live in the widget's config (`items`: strings or
 * `{t, done}`), so ARGUS adds or ticks them with home_widget_update and a tap on Life ticks them too. Nothing is fetched.
 */
object HomeTodo {
    data class Item(val text: String, val done: Boolean)

    const val MAX = 50

    fun items(cfg: JSONObject): List<Item> {
        val a = cfg.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            when (val x = a.opt(i)) {
                is JSONObject -> Item(x.optString("t").ifBlank { x.optString("title").ifBlank { x.optString("text") } }.trim(), x.optBoolean("done", false))
                is String -> Item(x.trim(), false)
                else -> null
            }
        }.filter { it.text.isNotBlank() }.take(MAX)
    }

    fun toJson(items: List<Item>): JSONArray = JSONArray(items.map { JSONObject().put("t", it.text).put("done", it.done) })

    /** The list with item [index] ticked or unticked. */
    fun toggle(items: List<Item>, index: Int): List<Item> =
        items.mapIndexed { i, it -> if (i == index) it.copy(done = !it.done) else it }

    /** The card's content: open items first, done ones after; the headline counts what is left. */
    fun data(w: Widget): WidgetData {
        val all = items(w.cfg())
        val left = all.count { !it.done }
        return WidgetData(headline = w.title, value = if (all.isEmpty()) "" else "$left left",
            change = if (all.isEmpty()) "Empty. Ask me to add something." else "",
            lines = all.map { WidgetLine(it.text, note = if (it.done) "done" else "") })
    }
}
