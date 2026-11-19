package com.blaze.assistant.ui.argus.kit

import com.blaze.assistant.ui.argus.ArgusPalette
import com.blaze.assistant.ui.argus.ArgusTokens

/**
 * P1-SH-02: the pure rules behind the kit components in this package (04 §2.8 items 5–9, 11–13, 32, 35–37 and the
 * `.btn` / `.ib` / `.field` / `.search` rows of §2.9), as plain values so KitSpecTest checks them on the JVM. The
 * composables only read from here, so a size, a label or a colour is decided in one place.
 *
 * Touch targets (04 §5.1): every tappable element is at least [TARGET] dp on both axes, whatever its visual size
 * ([target]). TalkBack: each row or tile is one merged node whose description is built here ([KitA11y]).
 */
object KitSpec {
    /** The minimum touch target (dp), Material and 04 §5.1. */
    const val TARGET = 48

    /** The touch target for something drawn [visualDp] wide (never smaller than [TARGET]). */
    fun target(visualDp: Int): Int = maxOf(visualDp, TARGET)

    // ---------------- visual sizes (dp), 04 §2.8 / §2.9 ----------------

    object Button { const val HEIGHT_M = 44; const val HEIGHT_S = 34; const val PAD_M = 16; const val PAD_S = 12 }
    object IconButton { const val VISUAL = 38; const val ICON = 20; const val AVATAR = 38 }
    object Field { const val HEIGHT = 48; const val FOCUS_RING = 3; const val SEARCH_ICON = 18 }
    object Row { const val MIN_HEIGHT = 72; const val ORB = 50; const val UNREAD_DOT = 10; const val PAD_H = 16; const val PAD_V = 11; const val GAP = 12 }
    object Pins { const val MAX = 4; const val ORB = 72; const val OVERDUE_DOT = 13; const val LABEL_MAX_CHARS = 10 }
    object Banner { const val MIN_HEIGHT = 52; const val ICON = 18 }
    object Menu { const val DISC = 44; const val ICON = 22; const val MIN_HEIGHT = 60 }
    object Settings { const val MIN_HEIGHT = 52; const val ICON = 20 }
    object Chips { const val HEIGHT = 34; const val COUNT_ALPHA = 0.6f; const val GAP = 6 }
    object Sheet { const val HANDLE_W = 36; const val HANDLE_H = 5; const val ACTION_ROW = 64 }
    object Mention { const val ROW = 44; const val ORB = 18; const val MAX_ROWS = 6 }
    object Attachment { const val TILE = 52; const val THUMB = 40; const val NAME_MAX = 18 }
    object Empty { const val ICON = 30; const val ICON_ALPHA = 0.55f }
    object Progress { const val HEIGHT = 44; const val BAR_W = 44; const val BAR_H = 4 }
    object Cloud { const val HEIGHT = 28 }

    // ---------------- colours the kit adds (AA, 04 §5.2) ----------------

    /**
     * Danger button (`.btn.d`): red fill + 50 % red border + red text. 04 §2.9 gives #FF6B62 dark / #D70015 light on a 16 %
     * fill; those reach 4.47:1 (dark, Plain) and 3.6:1 (light). The kit keeps the look with passing values: dark text =
     * the palette's dangerText #FF8085 on 16 %; light text #B3141A on a 10 % fill (≥ 4.5:1 on every ground).
     */
    fun dangerFillAlpha(dark: Boolean): Float = if (dark) 0.16f else 0.10f
    fun dangerText(p: ArgusPalette): Long = if (p.dark) p.dangerText else 0xFFB3141AL
    const val DANGER_BORDER_ALPHA = 0.5f

    /** The "Lead" badge: near-black on gold in both themes (white on #D4A72C is 2.2:1). */
    const val LEAD_TEXT = 0xFF111114L

    /** Manager row (BotChatRow, `Lead`): gold tint at 7 %. */
    const val MANAGER_TINT_ALPHA = 0.07f

    /**
     * The preview text colour on the Manager row. Light mute #64646C reaches 4.39:1 on the gold tint, so the light row uses
     * #5C5C64; dark keeps mute.
     */
    fun managerPreview(p: ArgusPalette): Long = if (p.dark) p.mute else 0xFF5C5C64L

    /** The time on the Manager row: light meta #646469 is 4.40:1 on the tint, so it takes [managerPreview]'s value too. */
    fun managerMeta(p: ArgusPalette): Long = if (p.dark) p.meta else 0xFF5C5C64L

    /** NeedYouBanner fill and its text: ink with onInk; the amber variant (only approvals waiting) = amber with page ink. */
    fun bannerFill(p: ArgusPalette, v: NeedYou.Variant): Long = if (v == NeedYou.Variant.AMBER) p.waiting else p.ink
    fun bannerText(p: ArgusPalette, v: NeedYou.Variant): Long = if (v == NeedYou.Variant.AMBER) (if (p.dark) p.onInk else p.ink) else p.onInk

    /** Danger fill as ARGB. */
    fun dangerFill(p: ArgusPalette): Long = ArgusTokens.a(p.danger, dangerFillAlpha(p.dark))
}

/** ArgusButton kinds and sizes (`.btn`, `.btn.p`, `.btn.d`, `.btn.sm`). */
enum class ButtonKind { GLASS, PRIMARY, DANGER }
enum class ButtonSize(val heightDp: Int, val padDp: Int) { M(KitSpec.Button.HEIGHT_M, KitSpec.Button.PAD_M), S(KitSpec.Button.HEIGHT_S, KitSpec.Button.PAD_S) }

/** ArgusIconButton kinds (`.ib`, `.ib.flat`, `.me`). */
enum class IconButtonKind { GLASS, FLAT, AVATAR }

/** BotChatRow preview tone (04 §2.8 item 7): mute; red bold for overdue; green for all clear. */
enum class PreviewTone { NORMAL, OVERDUE, CLEAR }

/** The data a BotChatRow draws. Nothing here reads a store: Team mode (P1-TM-03) builds these from BotThreads. */
data class BotRowModel(
    val id: String,
    val name: String,
    val preview: String,
    val time: String = "",
    val unread: Int = 0,
    val tone: PreviewTone = PreviewTone.NORMAL,
    val manager: Boolean = false,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    /** "Working", "Waiting for you"… for TalkBack only; the orb shows it as motion and a dot. */
    val status: String? = null,
)

/** 04 §2.8 item 7 rules. */
object BotRow {
    const val LEAD = "Lead"

    /** Unread dot shows for any unread (the count is TalkBack-only, as A-team); never on a muted row. */
    fun showDot(m: BotRowModel): Boolean = m.unread > 0 && !m.muted

    /** One line, trimmed; an empty preview reads as nothing rather than a blank line. */
    fun previewLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()
}

/** NeedYouBanner (04 §2.8 item 9): "3 need you · Nova, Atlas" + "Review ›". Static until P2 (nothing feeds it yet). */
object NeedYou {
    enum class Variant { HIDDEN, INK, AMBER }
    const val ACTION = "Review"
    const val MAX_NAMES = 2

    /** Hidden at zero; amber when everything waiting is an approval; ink otherwise. */
    fun variant(count: Int, approvalsOnly: Boolean): Variant = when {
        count <= 0 -> Variant.HIDDEN
        approvalsOnly -> Variant.AMBER
        else -> Variant.INK
    }

    fun headline(count: Int): String = "$count need${if (count == 1) "s" else ""} you"

    /** The bots, distinct, first [MAX_NAMES], then "+n". */
    fun names(bots: List<String>): String {
        val d = bots.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (d.isEmpty()) return ""
        val shown = d.take(MAX_NAMES).joinToString(", ")
        return if (d.size > MAX_NAMES) "$shown +${d.size - MAX_NAMES}" else shown
    }

    fun line(count: Int, bots: List<String>): String = names(bots).let { if (it.isEmpty()) headline(count) else "${headline(count)} · $it" }
}

/** PinStrip (04 §2.8 item 8). */
data class PinModel(val id: String, val name: String, val overdue: Boolean = false, val manager: Boolean = false)

object Pins {
    /** At most [KitSpec.Pins.MAX], in the user's order. */
    fun visible(pins: List<PinModel>): List<PinModel> = pins.distinctBy { it.id }.take(KitSpec.Pins.MAX)

    /** The label under an orb: first word, cut with an ellipsis past [KitSpec.Pins.LABEL_MAX_CHARS]. */
    fun label(name: String): String {
        val w = name.trim().substringBefore(' ')
        return if (w.length > KitSpec.Pins.LABEL_MAX_CHARS) w.take(KitSpec.Pins.LABEL_MAX_CHARS - 1) + "…" else w
    }

    /** Long-press reorder: move [from] to [to] (clamped). */
    fun move(pins: List<PinModel>, from: Int, to: Int): List<PinModel> {
        if (from !in pins.indices) return pins
        val m = pins.toMutableList(); val p = m.removeAt(from); m.add(to.coerceIn(0, m.size), p); return m
    }
}

/** Segmented / FilterChips (04 §2.8 item 13). */
data class ChipModel(val key: String, val label: String, val count: Int? = null)

object Chips {
    /** The count after the label, as `em` at 60 %; nothing for null or zero. */
    fun countText(c: ChipModel): String? = c.count?.takeIf { it > 0 }?.let { if (it > 99) "99+" else it.toString() }
}

/** MentionPopup (04 §2.8 item 5): what the composer's current word is asking for. */
object Mention {
    enum class Kind(val sigil: Char) { BOT('@'), COMMAND('/'), FILE('#') }
    data class Query(val kind: Kind, val text: String, val start: Int)
    data class Item(val kind: Kind, val key: String, val title: String, val sub: String = "")

    /**
     * The word at [cursor] when it starts with @, # (anywhere after a space or at the start) or / (only at the very start of
     * the message, so "1/2" and URLs don't open the palette). Null when the word has none.
     */
    fun query(text: String, cursor: Int = text.length): Query? {
        val c = cursor.coerceIn(0, text.length)
        var i = c
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        if (i >= text.length || i == c) return null
        val sig = text[i]
        val kind = Kind.entries.firstOrNull { it.sigil == sig } ?: return null
        if (kind == Kind.COMMAND && text.substring(0, i).isNotBlank()) return null
        val word = text.substring(i + 1, c)
        if (word.any { it == '@' || it == '#' || it == '/' }) return null
        return Query(kind, word, i)
    }

    /** Items of the query's kind whose title or key starts with (then contains) the query, case-insensitive, at most [KitSpec.Mention.MAX_ROWS]. */
    fun filter(items: List<Item>, q: Query): List<Item> {
        val t = q.text.lowercase()
        val mine = items.filter { it.kind == q.kind }
        val starts = mine.filter { it.title.lowercase().startsWith(t) || it.key.lowercase().startsWith(t) }
        val contains = mine.filter { it !in starts && (it.title.lowercase().contains(t) || it.key.lowercase().contains(t)) }
        return (starts + contains).take(KitSpec.Mention.MAX_ROWS)
    }

    /** The text after picking [item]: the word is replaced by sigil + key and one space. Returns (text, new cursor). */
    fun apply(text: String, q: Query, item: Item, cursor: Int = text.length): Pair<String, Int> {
        val end = cursor.coerceIn(q.start, text.length)
        val ins = "${q.kind.sigil}${item.key} "
        val out = text.substring(0, q.start) + ins + text.substring(end).trimStart()
        return out to (q.start + ins.length)
    }
}

/** AttachmentBar tile (04 §2.8 item 6). */
data class AttachmentModel(val id: String, val name: String, val image: Boolean = false)

object Attachments {
    /** Middle-ellipsised name that keeps the extension: "quarterly-rep….pdf". */
    fun shortName(name: String, max: Int = KitSpec.Attachment.NAME_MAX): String {
        if (name.length <= max) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 6) name.substring(dot) else ""
        val keep = (max - ext.length - 1).coerceAtLeast(1)
        return name.substring(0, keep) + "…" + ext
    }
}

/** ProgressLine (04 §2.8 item 37). */
object Progress {
    fun fraction(done: Long, total: Long): Float? = if (total <= 0) null else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)

    /** "42 %" or nothing when indeterminate. */
    fun percent(f: Float?): String? = f?.let { "${Math.round(it * 100)} %" }
}

/** CloudChip (04 §2.8 item 35): hidden until P7 (cloud computers). */
object CloudChipSpec {
    /** Off until P7-CP-* turns it on; the composable draws nothing while false. */
    const val ENABLED = false

    /** "Cloud on · $0.055/h": cost per hour in dollars, 3 decimals under $1, 2 above. */
    fun text(costPerHour: Double?): String {
        val c = costPerHour ?: return "Cloud on"
        val s = if (c < 1.0) String.format(java.util.Locale.US, "%.3f", c) else String.format(java.util.Locale.US, "%.2f", c)
        return "Cloud on · \$$s/h"
    }
}

/** TalkBack descriptions for the merged nodes (one per row, tile or chip). */
object KitA11y {
    fun botRow(m: BotRowModel): String = buildList {
        add(m.name)
        if (m.manager) add(BotRow.LEAD)
        m.status?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (m.unread > 0 && !m.muted) add(if (m.unread == 1) "1 unread" else "${m.unread} unread")
        if (m.pinned) add("Pinned")
        if (m.muted) add("Muted")
        BotRow.previewLine(m.preview).takeIf { it.isNotEmpty() }?.let { add(it) }
        if (m.time.isNotBlank()) add(m.time)
    }.joinToString(", ")

    fun pin(p: PinModel): String = if (p.overdue) "${p.name}, overdue" else p.name

    fun chip(c: ChipModel, selected: Boolean): String = buildList {
        add(c.label); Chips.countText(c)?.let { add(it) }; if (selected) add("Selected")
    }.joinToString(", ")

    fun attachment(a: AttachmentModel): String = "${a.name}, attachment"
    fun removeAttachment(a: AttachmentModel): String = "Remove ${a.name}"

    fun progress(label: String, f: Float?): String = Progress.percent(f)?.let { "$label, $it" } ?: label

    fun banner(count: Int, bots: List<String>): String = "${NeedYou.line(count, bots).replace(" · ", ", ")}. ${NeedYou.ACTION}"

    /** P1-SH-01: the shell's controls. */
    object Shell {
        const val ME = "You and settings"
        const val SEARCH = "Search"
        const val BELL = "Inbox"
        const val BELL_WAITING = "Inbox, something needs you"
        const val NEW = "New"
        const val PLUS = "Add"
        const val PLUS_CLOSE = "Close menu"
        fun notch(mode: String, open: Boolean): String = "${ShellSpec.label(mode)} mode. " + if (open) "Close mode switcher" else "Switch mode"
    }
}
