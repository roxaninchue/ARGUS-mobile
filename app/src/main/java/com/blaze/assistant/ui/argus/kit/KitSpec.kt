package com.blaze.assistant.ui.argus.kit

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * P1-SH-02: the pure rules behind the ARGUS kit (`ui/argus/kit/`), so they run on a plain JVM (KitSpecTest).
 * No Android or Compose imports here: the Compose components beside this file only draw what these rules decide.
 *
 * Numbers come from docs/overhaul/04-design-ia.md §2 (sizes, radii, type) and §5.2 (contrast, touch targets).
 * Colours are the 04 §2.1 effective A-team values; the Compose side reads them through [KitLook] until the P0
 * `ui/argus` tokens (ArgusColors) are on the branch, then KitLook maps from those instead (one file to re-point).
 */
object KitPalette {
    /** One mode's colours as ARGB longs (alpha included for the translucent ones). */
    data class Tones(
        val bg: Long, val ink: Long, val onInk: Long, val mute: Long, val dim: Long,
        val soft: Long, val soft2: Long, val line: Long, val line2: Long,
        val glassFill: Long, val glassFill2: Long, val glassRim: Long, val glassRim2: Long,
        val chrome: Long, val sheet: Long, val scrim: Long,
        /** Fills/dots and the AA text variant (04 §2.1: light fills fail AA as text, so text gets the darker one). */
        val danger: Long, val dangerText: Long,
        val success: Long, val successText: Long,
        val waiting: Long, val waitingText: Long,
        val unread: Long, val manager: Long,
        /** ARGUS-only (Manager orb glow, focus ring, wordmark). */
        val cobalt: Long,
    )

    private fun white(pct: Double): Long = (Math.round(pct * 255 / 100.0) shl 24) or 0xFFFFFFL
    private fun black(pct: Double): Long = (Math.round(pct * 255 / 100.0) shl 24)

    val DARK = Tones(
        bg = 0xFF0B0B0D, ink = 0xFFF2F2F4, onInk = 0xFF0B0B0D, mute = 0xFF9B9BA3, dim = 0xFF6B6B73,
        soft = white(7.0), soft2 = 0xFF24242A, line = white(9.0), line2 = white(22.0),
        glassFill = white(5.5), glassFill2 = white(9.0), glassRim = white(14.0), glassRim2 = white(26.0),
        chrome = 0x940E0E12, sheet = 0xED16161B, scrim = black(35.0),
        danger = 0xFFFF5A5F, dangerText = 0xFFFF5A5F,
        success = 0xFF3ECF8E, successText = 0xFF3ECF8E,
        waiting = 0xFFE0A020, waitingText = 0xFFE0A020,
        unread = 0xFF3B82F6, manager = 0xFFD4A72C, cobalt = 0xFF3466FF,
    )

    val LIGHT = Tones(
        bg = 0xFFEEF0F4, ink = 0xFF111114, onInk = 0xFFFFFFFF, mute = 0xFF64646C, dim = 0xFFA2A2AA,
        soft = black(4.5), soft2 = 0xFFE8E8EC, line = black(8.0), line2 = black(22.0),
        glassFill = white(62.0), glassFill2 = white(78.0), glassRim = white(90.0), glassRim2 = black(12.0),
        chrome = 0x99FFFFFF, sheet = 0xF0FAFAFC, scrim = black(35.0),
        danger = 0xFFE5484D, dangerText = 0xFFC4282E,
        success = 0xFF16A36A, successText = 0xFF0E7A4E,
        waiting = 0xFFE0A020, waitingText = 0xFF8A5D00,
        unread = 0xFF2F7BFF, manager = 0xFFD4A72C, cobalt = 0xFF0D28FF,
    )

    fun of(dark: Boolean): Tones = if (dark) DARK else LIGHT

    /** The bot identity swatches (04 §2.1), in picker order. */
    val BOT_COLORS: List<Pair<String, Long>> = listOf(
        "violet" to 0xFF8B5CF6, "green" to 0xFF22C55E, "amber" to 0xFFF59E0B, "red" to 0xFFEF4444,
        "cyan" to 0xFF06B6D4, "indigo" to 0xFF6366F1, "blue" to 0xFF3B82F6, "silver" to 0xFFCBD5E1,
    )

    /** [top] (possibly translucent) laid over the opaque [under]. */
    fun over(top: Long, under: Long): Long {
        val a = ((top shr 24) and 0xFF) / 255.0
        fun ch(v: Long, s: Int) = ((v shr s) and 0xFF).toDouble()
        fun m(s: Int) = Math.round(ch(top, s) * a + ch(under, s) * (1 - a)).coerceIn(0, 255)
        return 0xFF000000L or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    private fun lin(c: Long): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
    fun luminance(argb: Long): Double =
        0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)

    /** WCAG contrast of two opaque colours. */
    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}

/** Sizes in dp (04 §2.3, §2.7, §5.2). */
object KitSize {
    /** Every tappable thing gets at least this hit area (04 §5.2). */
    const val TOUCH = 48
    const val ICON_BUTTON = 38
    const val MENU_DISC = 44
    const val COMPOSER = 58
    const val COMPOSER_PLUS = 42
    const val TOP_BAR = 62
    const val ORB_XS = 18
    const val ORB_SM = 34
    const val ORB_ROW = 50
    const val ORB_LG = 72
    const val ORB_XL = 84
    const val UNREAD_DOT = 10
    const val PIN_DOT = 13
    const val ATTACH_TILE = 52
    const val ATTACH_THUMB = 40
    const val PROGRESS_LINE = 44
    const val PROGRESS_BAR_W = 44
    const val PROGRESS_BAR_H = 4
    const val MENTION_ROW = 44
    const val SHEET_HANDLE_W = 40
    const val SHEET_HANDLE_H = 5
    const val PAGE = 16
    const val SECTION_GAP = 22

    /** The hit area for a visual of [visualDp]: never under [TOUCH]. */
    fun target(visualDp: Int): Int = maxOf(visualDp, TOUCH)
}

/** Corner radii in dp (04 §2.4). */
object KitRadius {
    const val COMPOSER = 29
    const val SHEET = 26
    const val CARD = 20
    const val BANNER = 18
    const val ROW = 16
    const val TOOL = 14
    const val BUTTON = 13
    const val BUTTON_SM = 11
    const val FIELD = 12
    const val CHECKBOX = 7
    const val SEGMENT = 99
}

/** Type scale (04 §2.2): size sp, line height sp, weight. */
object KitText {
    data class Style(val size: Float, val line: Float, val weight: Int, val tracking: Float = 0f, val upper: Boolean = false)
    val DISPLAY = Style(32f, 38f, 750, -0.8f)
    val SERIF_HELLO = Style(30f, 36f, 400, -0.3f)
    val TITLE2 = Style(20f, 26f, 650)
    val SHEET_TITLE = Style(19f, 25f, 650)
    val TOP_TITLE = Style(17f, 22f, 650)
    val ROW_TITLE = Style(16f, 21f, 620)
    val BODY = Style(15f, 21f, 400)
    val INPUT = Style(16f, 22f, 400)
    val BODY_STRONG = Style(15.5f, 21f, 600)
    val SUB = Style(14.5f, 20f, 400)
    val PREVIEW = Style(14f, 19f, 400)
    val MENU_SUB = Style(13f, 18f, 400)
    val TOOL = Style(13.5f, 18f, 560)
    val META = Style(12.5f, 16f, 400)
    val SECTION = Style(12f, 16f, 650, 1.0f, upper = true)
    val CHIP = Style(13.5f, 18f, 500)
    val CHIP_STATUS = Style(11.5f, 15f, 500)
    val PILL = Style(11f, 14f, 600)
    val MONO = Style(12.5f, 18f, 400)
}

/** The Need-you banner (04 §2.8 #9): "3 need you · Nova, Atlas", ink normally, amber when only approvals wait. */
object NeedYou {
    data class Banner(val count: Int, val title: String, val names: String, val amber: Boolean) {
        /** TalkBack reads one line. */
        val spoken: String get() = if (names.isBlank()) "$title. Review" else "$title: $names. Review"
    }

    /** Most names shown before "+N". */
    const val MAX_NAMES = 2

    /**
     * [approvals] waiting approvals, [others] questions/connects/failures, [bots] who they're from (most recent first,
     * duplicates allowed). Null = hidden (nothing waits). Static until P2 wires the Inbox; the rule is ready now.
     */
    fun of(approvals: Int, others: Int, bots: List<String>): Banner? {
        val n = approvals.coerceAtLeast(0) + others.coerceAtLeast(0)
        if (n == 0) return null
        val uniq = bots.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val shown = uniq.take(MAX_NAMES).joinToString(", ")
        val more = uniq.size - MAX_NAMES
        val names = if (more > 0) "$shown +$more" else shown
        return Banner(n, "$n need you", names, amber = others <= 0)
    }
}

/** What a chat row (BotChatRow, 04 §2.8 #7) shows and says. */
object RowSpec {
    enum class Tone { PLAIN, DANGER, SUCCESS }
    data class Preview(val text: String, val tone: Tone, val bold: Boolean)

    /** Overdue beats everything ("2 overdue · Send deck", red bold); then "All clear" (green) when asked; else the last line. */
    fun preview(last: String, overdue: Int = 0, firstOverdue: String = "", allClear: Boolean = false): Preview = when {
        overdue > 0 -> Preview(if (firstOverdue.isBlank()) "$overdue overdue" else "$overdue overdue · ${firstOverdue.trim()}", Tone.DANGER, true)
        allClear && last.isBlank() -> Preview("All clear", Tone.SUCCESS, false)
        else -> Preview(oneLine(last), Tone.PLAIN, false)
    }

    /** A preview is one line: newlines and runs of spaces collapse. */
    fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /** TalkBack, merged per row (04 §5.2): "Ledger, Money lead, 1 overdue: Pay ZESCO bill, 9:12, unread". */
    fun spoken(name: String, role: String, preview: String, time: String, unread: Boolean, lead: Boolean = false, working: Boolean = false): String =
        listOfNotNull(
            name.trim(),
            role.trim().takeIf { it.isNotEmpty() }?.let { if (lead) "$it, lead" else it } ?: if (lead) "lead" else null,
            "working".takeIf { working },
            preview.replace(" · ", ": ").trim().takeIf { it.isNotEmpty() },
            time.trim().takeIf { it.isNotEmpty() },
            "unread".takeIf { unread },
        ).joinToString(", ")

    /**
     * The row's time (04 §4.1): the clock today ("9:41"), "Yesterday", the weekday inside a week ("Mon"), else
     * "12 Nov" (with the year when it isn't this year). Empty for no time.
     */
    fun time(thenMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        if (thenMs <= 0L) return ""
        val then = Instant.ofEpochMilli(thenMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val d = then.toLocalDate()
        val days = ChronoUnit.DAYS.between(d, today)
        return when {
            days <= 0L -> "%d:%02d".format(then.hour, then.minute)
            days == 1L -> "Yesterday"
            days < 7L -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            d.year == today.year -> "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, locale)}"
            else -> "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, locale)} ${d.year}"
        }
    }

    /** True when [ms] is on [today]'s date in [zone]. */
    fun isToday(ms: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == today
}

/** Pins (04 §2.8 #8): three or four orbs. */
object Pins {
    const val MAX = 4
    fun <T> visible(all: List<T>): List<T> = all.take(MAX)
}

/** Segmented / FilterChips (04 §2.8 #13): label with an optional count ("Inbox 3"). */
object Chips {
    fun label(name: String, count: Int? = null): String = if (count != null && count > 0) "$name $count" else name
    fun spoken(name: String, count: Int?, selected: Boolean): String =
        listOfNotNull(name, count?.takeIf { it > 0 }?.let { "$it" }, if (selected) "selected" else null).joinToString(", ")
}

/** The @ / / / # popup above the composer (04 §2.8 #5). */
object Mention {
    enum class Kind(val trigger: Char) { BOT('@'), COMMAND('/'), FILE('#') }
    data class Query(val kind: Kind, val text: String, val start: Int)

    /** Rows shown at most. */
    const val MAX_ROWS = 6

    /**
     * The token being typed at [cursor] when it starts with a trigger at a word start ("hi @at" -> BOT "at").
     * `/` only counts at the very start of the text (a slash command), so "1/2" or a URL never opens it.
     */
    fun at(text: String, cursor: Int = text.length): Query? {
        val c = cursor.coerceIn(0, text.length)
        var i = c - 1
        while (i >= 0 && !text[i].isWhitespace()) i--
        val start = i + 1
        if (start >= c) return null
        val kind = Kind.entries.firstOrNull { it.trigger == text[start] } ?: return null
        if (kind == Kind.COMMAND && start != 0) return null
        val q = text.substring(start + 1, c)
        if (q.any { !(it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') }) return null
        return Query(kind, q, start)
    }

    /** Prefix matches first, then word-start, then contains; case-insensitive; at most [MAX_ROWS]. */
    fun <T> filter(items: List<T>, q: String, name: (T) -> String): List<T> {
        val k = q.trim().lowercase()
        if (k.isEmpty()) return items.take(MAX_ROWS)
        fun rank(t: T): Int {
            val n = name(t).lowercase()
            return when {
                n.startsWith(k) -> 0
                n.split(' ', '-', '_').any { it.startsWith(k) } -> 1
                n.contains(k) -> 2
                else -> 9
            }
        }
        return items.map { it to rank(it) }.filter { it.second < 9 }.sortedBy { it.second }.map { it.first }.take(MAX_ROWS)
    }

    /** Replaces the token [q] in [text] with [insert] plus a space; returns the new text and cursor. */
    fun apply(text: String, q: Query, insert: String, cursor: Int = text.length): Pair<String, Int> {
        val end = cursor.coerceIn(q.start, text.length)
        val ins = q.kind.trigger + insert + " "
        val out = text.substring(0, q.start) + ins + text.substring(end)
        return out to (q.start + ins.length)
    }
}

/** The attachment tiles (04 §2.8 #6). */
object Attach {
    /** A file name that fits a tile: keeps the extension, ellipsizes the middle ("holiday-ph…jpg"). */
    fun shortName(name: String, max: Int = 14): String {
        val n = name.trim()
        if (n.length <= max) return n
        val dot = n.lastIndexOf('.')
        val ext = if (dot > 0 && n.length - dot <= 6) n.substring(dot + 1) else ""
        val keep = (max - ext.length - 1).coerceAtLeast(3)
        return n.substring(0, keep) + "\u2026" + ext
    }
}

/** The one-line progress status (04 §2.8 #37). */
object Progress {
    /** Null = indeterminate; otherwise 0..1. */
    fun fraction(done: Long, total: Long): Float? = if (total <= 0L) null else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
    fun percent(f: Float?): String = if (f == null) "" else "${Math.round(f * 100)}%"
}

/** The cloud chip (04 §2.8 #35): hidden until the P7 cloud desk exists. */
object CloudChipRule {
    /** P1: off. P7-CP flips it when leases exist. */
    const val ENABLED = false
    fun visible(running: Boolean, enabled: Boolean = ENABLED): Boolean = enabled && running
    fun label(perHourUsd: Double?): String = if (perHourUsd == null || perHourUsd <= 0.0) "Cloud on" else "Cloud on · $" + "%.3f".format(Locale.US, perHourUsd) + "/h"
}

/** Buttons (04 §2.9 `.btn`): kinds and sizes. */
object ButtonSpec {
    enum class Kind { GLASS, PRIMARY, DANGER }
    enum class Size(val heightDp: Int, val radiusDp: Int, val textSp: Float) { M(44, KitRadius.BUTTON, 15f), S(34, KitRadius.BUTTON_SM, 13.5f) }
    /** Danger text (04 §2.9): `#FF6B62` dark, `#D70015` light; fill red 16 %, border red 50 %. */
    fun dangerText(dark: Boolean): Long = if (dark) 0xFFFF6B62 else 0xFFD70015
    const val DANGER_FILL_ALPHA = 0.16f
    const val DANGER_RIM_ALPHA = 0.50f
}
