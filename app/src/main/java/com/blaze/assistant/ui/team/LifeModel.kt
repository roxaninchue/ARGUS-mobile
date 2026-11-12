package com.blaze.assistant.ui.team

import com.blaze.assistant.agent.ScheduleDay
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.icons.LucideSpec
import com.blaze.assistant.ui.space.SpaceAnswer
import com.blaze.assistant.ui.space.SpaceGreeting
import com.blaze.assistant.ui.space.SpaceHomeCards
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * P1-SH-05 Life mode v1 (04 §3.2 Life row, §4.12; 06 row 5): what the Life page shows, as plain values so LifeModelTest
 * checks every rule on the JVM. Built from the day's schedule ([ScheduleDay.itemsOn]: routines, alarms, calendar, the
 * same items Schedule and the [context] line use), the AM Home's inputs (SpaceHome: latest answer, projects, sign-in,
 * setup) and the Home board's cached weather reading.
 *
 * Top to bottom ([Section]): the serif day line; Sign in / Finishing setup when they apply (the AM Home cards, moved);
 * the Today strip; the life-domain tiles, each saying "Set up my life team" until the domain leads arrive (P5); Projects
 * (at most three, All projects, New project); Panels (weather, latest answer, at most three suggested asks).
 * The People card of 04 §4.12 waits for the People graph (P5).
 *
 * Every AM Home card has a place here ([HOME_CARDS]); the 0.52 Home (SpaceHome) itself stays for the shell with
 * `argus_look` off, so its cards stay reachable both ways.
 */
object LifeModel {
    enum class Section { DAY_LINE, SIGN_IN, SETUP, TODAY, DOMAINS, PROJECTS, PANELS }

    /** Where each AM Home card lives in Life mode (SpaceHome HERO's greeting becomes the day line). */
    val HOME_CARDS: Map<SpaceHomeCards.Card, Section> = mapOf(
        SpaceHomeCards.Card.HERO to Section.DAY_LINE,
        SpaceHomeCards.Card.SIGN_IN to Section.SIGN_IN,
        SpaceHomeCards.Card.SETUP to Section.SETUP,
        SpaceHomeCards.Card.ANSWER to Section.PANELS,
        SpaceHomeCards.Card.PROJECTS to Section.PROJECTS,
        SpaceHomeCards.Card.SUGGESTIONS to Section.PANELS,
        SpaceHomeCards.Card.NEW_CHAT to Section.PROJECTS,
    )

    /** The words on every domain tile until P5 seeds the leads (04 §3.3, the Team empty state's third action). */
    const val SET_UP = "Set up my life team"

    /** What a domain tile sends the Manager in v1 (the 3-question interview is P5's; the Manager answers in Chat). */
    fun setUpAsk(domain: DomainTile?): String = if (domain == null) SET_UP else "$SET_UP, starting with ${domain.label}"

    /** At most this many suggested asks (04 §5.2: never more than 3 chips). */
    const val MAX_ASKS = 3

    /** The strip's window ends at midnight; it starts at 06 unless something is earlier (then 00). */
    const val DAY_START = 6

    data class StripItem(
        val title: String,
        val kind: String,
        /** "17:00" / "09:00–10:30", null for all-day. */
        val time: String?,
        /** 0..1 along the strip, null for all-day. */
        val at: Float?,
        val passed: Boolean,
        /** "from Tue 10 Nov" for a span that started earlier, as ScheduleDay notes it. */
        val note: String = "",
    )

    data class TodayStrip(
        val startHour: Int,
        /** Hours with a tick label: 06 12 18 24 (or 00 06 12 18 24). */
        val ticks: List<Int>,
        /** 0..1 along the strip; null when now is outside the window or unknown. */
        val now: Float?,
        val timed: List<StripItem>,
        val allDay: List<StripItem>,
    ) {
        val empty: Boolean get() = timed.isEmpty() && allDay.isEmpty()
        fun tickAt(h: Int): Float = (h - startHour).toFloat() / (24 - startHour)
    }

    data class DomainTile(val key: String, val label: String, val glyph: String, val color: Long, val line: String = SET_UP)

    data class Weather(val value: String, val line: String)

    data class LifeState(
        val dayLine: String,
        val strip: TodayStrip,
        val domains: List<DomainTile>,
        /** (id, name), newest first, at most [SpaceHomeCards.MAX_PROJECTS]. */
        val projects: List<Pair<String, String>>,
        val weather: Weather?,
        val answer: SpaceAnswer.Card?,
        val asks: List<String>,
        val signedIn: Boolean,
        val setupLeft: Int,
        val setupPercent: Int,
        val sections: List<Section>,
    )

    // ---------------- day line ----------------

    private val NUMBERS = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
    fun count(n: Int): String = NUMBERS.getOrNull(n) ?: n.toString()

    fun weekday(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    /** Items that count as "things today": repeating every-N-minutes jobs are noise, not plans. */
    fun counted(items: List<ScheduleDay.Item>): List<ScheduleDay.Item> = items.filterNot { it.note.startsWith("every") }

    /**
     * 04 §6.4 the day line, one serif sentence: "Thursday — three things today.", "Thursday — three things today, one still
     * ahead.", "Thursday — nothing left today." once all of them are over, "Thursday — nothing on today." with none.
     */
    fun dayLine(date: LocalDate, items: List<ScheduleDay.Item>, now: LocalTime?): String {
        val day = weekday(date)
        val list = counted(items)
        if (list.isEmpty()) return "$day \u2014 nothing on today."
        val ahead = list.count { !it.passed(now) }
        val things = if (list.size == 1) "one thing today" else "${count(list.size)} things today"
        return when {
            ahead == 0 -> "$day \u2014 nothing left today."
            ahead == list.size -> "$day \u2014 $things."
            else -> "$day \u2014 $things, ${count(ahead)} still ahead."
        }
    }

    // ---------------- Today strip ----------------

    private fun minutes(t: LocalTime) = t.hour * 60 + t.minute

    fun strip(items: List<ScheduleDay.Item>, now: LocalTime?): TodayStrip {
        val list = counted(items)
        val timedSrc = list.filter { it.from != null }
        val start = if (timedSrc.any { it.from!!.hour < DAY_START }) 0 else DAY_START
        val span = (24 - start) * 60f
        fun pos(t: LocalTime) = ((minutes(t) - start * 60) / span).coerceIn(0f, 1f)
        val timed = timedSrc.map { i ->
            val time = ScheduleDay.hm(i.from!!) + (i.to?.takeIf { it != i.from }?.let { "\u2013" + ScheduleDay.hm(it) } ?: "")
            StripItem(i.title, i.kind, time, pos(i.from), i.passed(now), i.note)
        }
        val allDay = list.filter { it.from == null }.map { StripItem(it.title, it.kind, null, null, false, it.note) }
        val nowAt = now?.takeIf { it.hour >= start }?.let { pos(it) }
        return TodayStrip(start, (start..24 step 6).toList(), nowAt, timed, allDay)
    }

    // ---------------- domains ----------------

    /** The ten life domains of 04 §3.3 in their order, hue and glyph (ArgusTokens / LucideSpec, P0-SH-02). */
    fun domains(): List<DomainTile> = LucideSpec.DOMAIN.map { (k, glyph) ->
        DomainTile(k, k.replaceFirstChar { it.uppercase() }, glyph, ArgusTokens.DOMAIN_COLORS[k] ?: 0xFF9B9BA3L)
    }

    // ---------------- weather panel ----------------

    /** A cached reading older than this is not shown (TurnContextSource uses the same limit). */
    const val WEATHER_MAX_AGE_MS = 3 * 3_600_000L

    /**
     * The Home board's weather card reading → the panel: value "24°C", line "Clear · Lusaka". Null when there is none,
     * it never fetched, or it is older than [WEATHER_MAX_AGE_MS] (Life never fetches; the board does).
     */
    fun weather(value: String, change: String, place: String, fetched: Long, now: Long): Weather? {
        if (value.isBlank() || fetched <= 0L || now - fetched > WEATHER_MAX_AGE_MS) return null
        val cond = change.split(" \u00b7 ").map { it.trim() }.firstOrNull {
            it.isNotBlank() && !it.startsWith("feels") && !it.startsWith("wind") && !Regex("^\\d{1,2}:\\d{2}").containsMatchIn(it)
        }
        return Weather(value.trim(), listOfNotNull(cond, place.trim().ifBlank { null }).joinToString(" \u00b7 "))
    }

    // ---------------- the page ----------------

    fun sections(signedIn: Boolean, setupLeft: Int): List<Section> = buildList {
        add(Section.DAY_LINE)
        if (!signedIn) add(Section.SIGN_IN)
        if (setupLeft > 0) add(Section.SETUP)
        add(Section.TODAY)
        add(Section.DOMAINS)
        add(Section.PROJECTS)
        add(Section.PANELS)
    }

    fun state(
        date: LocalDate,
        now: LocalTime?,
        items: List<ScheduleDay.Item>,
        projects: List<Pair<String, String>> = emptyList(),
        weather: Weather? = null,
        answer: SpaceAnswer.Card? = null,
        signedIn: Boolean = true,
        setupLeft: Int = 0,
        setupPercent: Int = 100,
    ): LifeState = LifeState(
        dayLine = dayLine(date, items, now),
        strip = strip(items, now),
        domains = domains(),
        projects = projects.take(SpaceHomeCards.MAX_PROJECTS),
        weather = weather,
        answer = answer,
        asks = SpaceGreeting.SUGGESTIONS.take(MAX_ASKS),
        signedIn = signedIn,
        setupLeft = setupLeft.coerceAtLeast(0),
        setupPercent = setupPercent.coerceIn(0, 100),
        sections = sections(signedIn, setupLeft),
    )
}
