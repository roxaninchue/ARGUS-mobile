package com.blazeinchue.argus.composer

import com.blazeinchue.argus.BrandName
import com.blazeinchue.argus.composer.SpotlightSearch.Act
import com.blazeinchue.argus.composer.SpotlightSearch.Icon
import com.blazeinchue.argus.composer.SpotlightSearch.Row

/**
 * 0.52.2 the ARGUS Home's pull-down Spotlight, pure part (SpotlightGroupsTest). It builds on [SpotlightSearch] (views,
 * actions, settings, models, library, terminal and @ modes, fuzzy via [Fuzzy]) and adds what the Home sheet shows:
 *  - results in groups, in this order: Apps, Actions, Settings, Chats & memory, Web;
 *  - the phone's own apps (fuzzy on their labels) and words you or ARGUS said in the chat;
 *  - before typing: Recent searches and Suggested asks (time-aware), then the curated rows.
 * Phone apps and chat hits ride on [Act.RunAction] ("app:<package>", "history") so [SpotlightSearch.Act] stays as it is.
 */
object SpotlightGroups {
    enum class Group(val title: String) {
        RECENT("Recent"), SUGGESTED("Suggested"), APPS("Apps"), ACTIONS("Actions"), SETTINGS("Settings"), CHATS("Chats & memory"), WEB("Web")
    }

    data class Section(val group: Group, val rows: List<Row>)

    /** A phone app as the sheet needs it (InstalledApps → label + package). */
    data class App(val label: String, val pkg: String)

    /** Something said in the chat ([mine] = the user's words). */
    data class Said(val id: Long, val text: String, val mine: Boolean)

    const val APPS_MAX = 4
    const val CHATS_MAX = 3
    const val PER_GROUP_MAX = 5
    const val APP_MIN = 45
    const val CHAT_MIN = 60
    const val APP_PREFIX = "app:"

    /** The package an [Act.RunAction] launches, or null when it isn't an app row. */
    fun appPackage(a: Act): String? = (a as? Act.RunAction)?.action?.takeIf { it.startsWith(APP_PREFIX) }?.removePrefix(APP_PREFIX)?.ifBlank { null }

    /** Which group a [SpotlightSearch] row belongs in. */
    fun groupOf(r: Row): Group = when {
        r.id.startsWith(APP_PREFIX) -> Group.APPS
        r.id.startsWith("chat:") || r.id.startsWith("mem:") -> Group.CHATS
        r.act is Act.Browse -> Group.WEB
        r.id.startsWith("setting:") || r.icon == Icon.SETTINGS -> Group.SETTINGS
        // ARGUS's own apps (Music, Video, Browser, Terminal, Gallery…) sit with the phone's apps.
        r.act is Act.OpenView || r.act is Act.Preview -> Group.APPS
        else -> Group.ACTIONS
    }

    fun appRows(q: String, apps: List<App>): List<Row> {
        if (q.isBlank()) return emptyList()
        return apps.asSequence().map { it to Fuzzy.best(q, listOf(it.label, it.pkg.substringAfterLast('.'))) }
            .filter { it.second >= APP_MIN }.sortedWith(compareByDescending<Pair<App, Int>> { it.second }.thenBy { it.first.label.length })
            .take(APPS_MAX)
            .map { (a, s) -> Row(APP_PREFIX + a.pkg, Icon.ACTION, a.label, "Open the app", Act.RunAction(APP_PREFIX + a.pkg), s) }.toList()
    }

    /** Chat lines that contain the query (newest first), with a short snippet around it, plus "search my memory" for 2+ letters. */
    fun chatRows(q: String, said: List<Said>): List<Row> {
        val t = q.trim()
        if (t.length < 2) return emptyList()
        val hits = said.asReversed().asSequence().filter { it.text.isNotBlank() }
            .map { it to Fuzzy.score(t, it.text.take(600)) }.filter { it.second >= CHAT_MIN }.take(CHATS_MAX)
            .map { (m, s) -> Row("chat:${m.id}", Icon.CHAT, snippet(m.text, t), if (m.mine) "You said \u00b7 open the chat" else "${BrandName.NAME} said \u00b7 open the chat", Act.RunAction("history"), s) }
            .toList()
        val mem = Row("mem:$t", Icon.SEARCH, "Search my memory for \u2018$t\u2019", "${BrandName.NAME} looks it up in what it remembers", Act.AskArgus("Search your memory for: $t"), 1)
        return hits + mem
    }

    /** About [width] characters of [text] around the first match of [q], with ellipses where it was cut. */
    fun snippet(text: String, q: String, width: Int = 60): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= width) return flat
        val i = flat.lowercase().indexOf(q.lowercase().trim()).coerceAtLeast(0)
        val start = (i - width / 3).coerceIn(0, (flat.length - width).coerceAtLeast(0))
        val end = (start + width).coerceAtMost(flat.length)
        return (if (start > 0) "\u2026" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "\u2026" else "")
    }

    /** Asks that fit the time of day (shown before typing). */
    fun suggested(hour: Int): List<String> = when (((hour % 24) + 24) % 24) {
        in 5..11 -> listOf("Show my schedule", "What's the weather today?", "Read me the headlines")
        in 12..16 -> listOf("What's next on my calendar?", "Summarise my unread messages", "Find a place for lunch")
        in 17..21 -> listOf("Plan tomorrow", "Show me the latest tech news", "Play something relaxing")
        else -> listOf("Set an alarm for tomorrow", "Turn on Do Not Disturb", "Play something relaxing")
    }

    fun suggestedRows(hour: Int): List<Row> = suggested(hour).map { s -> Row("sug:$s", Icon.CHAT, s, "Ask ${BrandName.NAME}", Act.AskArgus(s), 0) }

    fun recentRows(recent: List<String>): List<Row> = recent.map { q -> Row("recent:$q", Icon.SEARCH, q, "Recent search", Act.Complete(q), 0) }

    /**
     * The sheet's sections for [query]. [base] is [SpotlightSearch.search] for the same query (its fallbacks included).
     * Empty query: Recent, Suggested, then the curated rows grouped. "!" and "@" modes keep [SpotlightSearch]'s own order
     * in one section (they are a command line, not a search).
     */
    fun sections(query: String, base: SpotlightSearch.Result, apps: List<App> = emptyList(), said: List<Said> = emptyList(),
                 recent: List<String> = emptyList(), hour: Int = 12): List<Section> {
        val q = query.trim()
        if (q.isEmpty()) {
            val out = ArrayList<Section>()
            if (recent.isNotEmpty()) out += Section(Group.RECENT, recentRows(recent).take(PER_GROUP_MAX))
            out += Section(Group.SUGGESTED, suggestedRows(hour))
            out += group(base.rows)
            return out
        }
        if (base.mode == SpotlightSearch.Mode.TERMINAL || base.mode == SpotlightSearch.Mode.VIEWS) return listOf(Section(Group.ACTIONS, base.rows))
        val rows = appRows(q, apps) + base.rows + chatRows(q, said)
        return group(rows)
    }

    /** Rows into sections in [Group] order, best first inside each, at most [PER_GROUP_MAX] each (Apps [APPS_MAX] + ARGUS's own). */
    fun group(rows: List<Row>): List<Section> {
        val by = rows.distinctBy { it.id }.groupBy { groupOf(it) }
        return listOf(Group.APPS, Group.ACTIONS, Group.SETTINGS, Group.CHATS, Group.WEB).mapNotNull { g ->
            val list = by[g].orEmpty().sortedByDescending { it.score }.take(if (g == Group.APPS) APPS_MAX + 2 else PER_GROUP_MAX)
            if (list.isEmpty()) null else Section(g, list)
        }
    }

    /** The row Enter runs: the first row of the first section. */
    fun top(sections: List<Section>): Row? = sections.firstOrNull { it.rows.isNotEmpty() }?.rows?.firstOrNull()
}

/** Recent searches (pure; stored newline-separated in the `argus_spot_recent` pref by the sheet). */
object SpotlightRecents {
    const val PREF = "argus_spot_recent"
    const val MAX = 6

    /** [q] goes first; the same search (case-insensitive) moves up instead of repeating; blank, very long and "!" terminal input is never kept. */
    fun push(list: List<String>, q: String, max: Int = MAX): List<String> {
        val t = q.trim().replace('\n', ' ')
        if (t.isEmpty() || t.length > 80 || t.startsWith("!")) return list.take(max)
        return (listOf(t) + list.filterNot { it.equals(t, ignoreCase = true) }).take(max)
    }

    fun decode(s: String?): List<String> = s.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.take(MAX)
    fun encode(list: List<String>): String = list.take(MAX).joinToString("\n")
}
