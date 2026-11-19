package com.blaze.assistant.glance

import com.blaze.assistant.stage.ShelfCard
import com.blaze.assistant.stage.StageKind
import com.blaze.assistant.stage.StageShelf
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 2026-11-19 (owner spec, feat/notch-glance): the glance layer, what shows on the blurred page while the notch's mode
 * pill is open. Kept light: a digital clock, the next reminder or alarm in the coming day, the task ARGUS (or a helper)
 * is working on, and the Stage cards that were taken off the Stage ([StageShelf]). Every input comes from data the app
 * already holds in memory; nothing here fetches.
 *
 * Pure (GlanceTest): [select] decides what shows and in what order, [act] is the `glance` tool's and the layer's own
 * pin / unpin / remove. GlanceStore is the phone side.
 */
object Glance {
    /** The Settings key of [Prefs] (GlanceStore saves it; StageStore reads the pins so pinned cards stay on the shelf). */
    const val PREFS_KEY = "glance_prefs_v1"
    const val CLOCK = "clock"
    const val REMINDER = "reminder"
    const val TASK = "task"
    const val STAGE = "stage:"
    /** Built-in items (not Stage cards): removing one hides it until it is pinned again. */
    val BUILT_IN = listOf(CLOCK, REMINDER, TASK)

    /** At most this many items on the layer, clock included. */
    const val MAX = 6
    /** At most this many pins. */
    const val MAX_PINS = 4
    /** A reminder further off than this isn't "at a glance". */
    const val NEXT_WINDOW_MS = 24 * 60 * 60 * 1000L

    enum class Kind { CLOCK, REMINDER, TASK, STAGE_CARD }

    data class Item(val id: String, val kind: Kind, val title: String, val sub: String = "", val icon: String, val pinned: Boolean = false)

    /** What the user (or ARGUS) changed: pinned ids in pin order, and built-ins taken off. */
    data class Prefs(val pins: List<String> = emptyList(), val hidden: Set<String> = emptySet()) {
        fun toJson(): JSONObject = JSONObject().put("pins", JSONArray(pins)).put("hidden", JSONArray(hidden.sorted()))
        companion object {
            fun from(s: String?): Prefs = try {
                val o = JSONObject(s ?: "{}")
                fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
                Prefs(list("pins").distinct().take(MAX_PINS), list("hidden").filter { it in BUILT_IN }.toSet())
            } catch (_: Exception) { Prefs() }
        }
    }

    data class Next(val title: String, val at: Long)

    /** The layer's inputs, read by GlanceStore from what the app already has. */
    data class Inputs(val now: Long, val zone: ZoneId, val next: Next? = null, val task: String? = null,
                      val shelf: List<ShelfCard> = emptyList(), val h24: Boolean = true)

    fun icon(kind: StageKind): String = when (kind) {
        StageKind.TIMER -> "clock"; StageKind.MUSIC -> "music"; StageKind.VIDEO -> "play"; StageKind.QUIZ -> "graduation-cap"
        StageKind.FLASHCARDS -> "library"; StageKind.PLAN -> "list-checks"; StageKind.NOTE -> "notebook"; StageKind.BROWSER -> "globe"
    }

    fun clockText(now: Long, zone: ZoneId, h24: Boolean): String =
        DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm", Locale.US).format(Instant.ofEpochMilli(now).atZone(zone))

    fun dateText(now: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US).format(Instant.ofEpochMilli(now).atZone(zone))

    /** "in 25 min" within the hour, else "14:30" today, "tomorrow 07:00" after midnight. */
    fun whenText(now: Long, at: Long, zone: ZoneId, h24: Boolean): String {
        val mins = ((at - now) / 60_000L).coerceAtLeast(0)
        if (mins < 60) return if (mins <= 0) "now" else "in $mins min"
        val a = Instant.ofEpochMilli(at).atZone(zone)
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate()
        val t = DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", Locale.US).format(a)
        return if (a.toLocalDate() == today) t else "tomorrow $t"
    }

    /** Everything that could show, in default order, before prefs: clock, next reminder, active task, shelf (newest first). */
    fun candidates(i: Inputs): List<Item> {
        val out = ArrayList<Item>()
        out += Item(CLOCK, Kind.CLOCK, clockText(i.now, i.zone, i.h24), dateText(i.now, i.zone), "clock")
        i.next?.takeIf { it.at > i.now && it.at - i.now <= NEXT_WINDOW_MS && it.title.isNotBlank() }?.let { n ->
            out += Item(REMINDER, Kind.REMINDER, n.title.trim().take(60), whenText(i.now, n.at, i.zone, i.h24), "bell")
        }
        i.task?.trim()?.takeIf { it.isNotEmpty() }?.let { out += Item(TASK, Kind.TASK, it.take(60), "In progress", "workflow") }
        i.shelf.sortedByDescending { it.at }.forEach { c ->
            out += Item(StageShelf.glanceId(c), Kind.STAGE_CARD, c.title, c.kind.label + " · off the Stage", icon(c.kind))
        }
        return out
    }

    /**
     * What the layer shows: hidden built-ins left out; the clock first, then pinned items in pin order, then the rest
     * in default order; at most [max] in all. A pin whose item isn't there now (no reminder today) is simply skipped.
     */
    fun select(i: Inputs, p: Prefs, max: Int = MAX): List<Item> {
        val all = candidates(i).filter { it.id !in p.hidden }
        val byId = all.associateBy { it.id }
        val clock = byId[CLOCK]?.let { listOf(it.copy(pinned = CLOCK in p.pins)) }.orEmpty()
        val pinned = p.pins.filter { it != CLOCK }.mapNotNull { byId[it]?.copy(pinned = true) }
        val rest = all.filter { it.id != CLOCK && it.id !in p.pins }
        return (clock + pinned + rest).take(max)
    }

    // ---------------------------------------------------------------- actions (the tool and the layer's buttons)

    enum class Op { LIST, PIN, UNPIN, REMOVE, RESTORE;
        companion object { fun of(s: String?): Op? = entries.firstOrNull { it.name.equals(s?.trim(), true) } ?: when (s?.trim()?.lowercase()) {
            "read", "show", "get" -> LIST; "keep" -> PIN; "hide", "delete", "dismiss" -> REMOVE; "put_back", "back", "unarchive" -> RESTORE; else -> null } }
    }

    /**
     * What an action does to the prefs and the shelf. [dropShelf] = the shelf card to delete (REMOVE of a Stage card);
     * [restore] = the shelf card to put back on the Stage (the phone side does it). [text] is the tool's answer.
     */
    data class Outcome(val prefs: Prefs, val text: String, val changed: Boolean = false, val dropShelf: String? = null, val restore: String? = null)

    /** Accepts "stage:music3", "music3", "clock", and an item's title ("Lo-fi") when exactly one item has it. */
    fun resolve(id: String, items: List<Item>, shelf: List<ShelfCard>): String? {
        val t = id.trim()
        if (t.isEmpty()) return null
        if (t.lowercase() in BUILT_IN) return t.lowercase()
        val asShelf = if (t.startsWith(STAGE)) t else STAGE + t
        if (shelf.any { StageShelf.glanceId(it) == asShelf }) return asShelf
        items.firstOrNull { it.id == t }?.let { return it.id }
        val named = items.filter { it.title.equals(t, true) }
        return named.singleOrNull()?.id
    }

    fun act(op: Op, id: String, i: Inputs, p: Prefs): Outcome {
        val shown = select(i, p)
        if (op == Op.LIST) return Outcome(p, describe(shown, p))
        val everything = candidates(i)
        val target = resolve(id, everything, i.shelf) ?: return Outcome(p,
            if (id.isBlank()) "Say which item (id). " + describe(shown, p) else "Nothing called \u201c$id\u201d on the glance layer. " + describe(shown, p))
        val isCard = target.startsWith(STAGE)
        val title = everything.firstOrNull { it.id == target }?.title ?: target
        return when (op) {
            Op.PIN -> when {
                target in p.pins -> Outcome(p.copy(hidden = p.hidden - target), "$title is already pinned.", changed = target in p.hidden)
                p.pins.size >= MAX_PINS -> Outcome(p, "Already $MAX_PINS pins (${p.pins.joinToString()}); unpin one first.")
                else -> Outcome(p.copy(pins = p.pins + target, hidden = p.hidden - target), "Pinned $title on the glance layer.", changed = true)
            }
            Op.UNPIN -> if (target !in p.pins) Outcome(p, "$title isn't pinned.")
                else Outcome(p.copy(pins = p.pins - target), "Unpinned $title.", changed = true)
            Op.REMOVE -> if (isCard) Outcome(p.copy(pins = p.pins - target), "Removed $title from the glance layer.", changed = true, dropShelf = target)
                else Outcome(p.copy(pins = p.pins - target, hidden = p.hidden + target), "Took $title off the glance layer (pin it to bring it back).", changed = true)
            Op.RESTORE -> if (!isCard) Outcome(p, "Only Stage cards can go back on the Stage; $title is part of the glance layer itself.")
                else Outcome(p.copy(pins = p.pins - target), "Putting $title back on the Stage.", changed = target in p.pins, restore = target)
            Op.LIST -> Outcome(p, describe(shown, p))
        }
    }

    /** The tool's view of the layer: one line per item with its id, plus what is hidden. */
    fun describe(items: List<Item>, p: Prefs): String = buildString {
        append("Glance layer (behind the notch's mode pill): ")
        if (items.isEmpty()) append("empty.") else append(items.joinToString("; ") { "${it.id} = ${it.title}" + (if (it.sub.isNotBlank()) " (${it.sub})" else "") + (if (it.pinned) " [pinned]" else "") } + ".")
        if (p.hidden.isNotEmpty()) append(" Hidden: ${p.hidden.sorted().joinToString()}.")
    }
}
