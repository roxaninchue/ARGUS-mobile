package com.blaze.assistant.ui.team

import com.blaze.assistant.store.Sql
import com.blaze.assistant.team.Avatar
import com.blaze.assistant.team.Bot
import com.blaze.assistant.team.BotKind
import com.blaze.assistant.team.BotRules
import com.blaze.assistant.team.BotThreads
import com.blaze.assistant.team.TeamStore
import com.blaze.assistant.ui.argus.ArgusTokens
import com.blaze.assistant.ui.argus.kit.BotRowModel
import com.blaze.assistant.ui.argus.kit.ChipModel
import com.blaze.assistant.ui.argus.kit.PinModel
import com.blaze.assistant.ui.argus.kit.PreviewTone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * P1-TM-03: what the Team screen shows, built from the roster ([TeamStore]), the bot threads ([BotThreads]) and the
 * jobs table, as plain values so TeamModelTest checks every rule on the JVM (A-team `home()` / 02 §A6, 04 §3.1 Team):
 *  - header: the team name + "N bots + Manager · N jobs today · N overdue" (zero parts dropped, bots always shown);
 *  - domain chips: just "All" until P5 (Life domains);
 *  - pins: pinned bots, the Manager first, at most four (PinStrip), red dot when the bot has an overdue job;
 *  - the Manager row (gold, "Lead"), then bots and groups by last activity (newest first, then roster order);
 *  - an "Unassigned" row when open jobs have no bot, and "All jobs" with the open count;
 *  - empty: only the Manager on the roster → "Build your team" with New bot and Demo team.
 * Preview tone as A-team: "2 overdue · <first overdue job>" in red when a bot has overdue jobs.
 */
data class TeamHeader(val name: String, val sub: String)

data class TeamState(
    val header: TeamHeader,
    val chips: List<ChipModel>,
    val pins: List<PinModel>,
    val manager: BotRowModel?,
    val rows: List<BotRowModel>,
    val unassigned: Int,
    val openJobs: Int,
    val empty: Boolean,
    /** id → avatar colour (ARGB) for the orbs. */
    val colors: Map<String, Long> = emptyMap(),
    /** id → orb face index (A-team `face`), null = from the name. */
    val faces: Map<String, Int?> = emptyMap(),
)

/** Open-job counts the Team screen needs (one query each; the jobs table is P1-TM-05's, read-only here). */
data class JobCounts(
    val today: Int = 0,
    val overdue: Int = 0,
    val unassigned: Int = 0,
    val open: Int = 0,
    /** botId → (overdue count, title of the oldest overdue job). */
    val overdueByBot: Map<String, Pair<Int, String>> = emptyMap(),
) {
    companion object {
        /** A job is open unless status is done or its run ended. */
        const val OPEN = "status <> 'done' AND run NOT IN ('DONE','CANCELLED')"

        /** [today] = the user's local date. `due` is A-team's ISO text; its first 10 characters are the date. */
        fun read(sql: Sql, today: LocalDate): JobCounts {
            val d = today.toString()
            fun n(where: String, vararg a: Any?) = sql.query("SELECT count(*) FROM jobs WHERE $OPEN AND $where", *a) { it.long(0).toInt() }.firstOrNull() ?: 0
            val byBot = LinkedHashMap<String, Pair<Int, String>>()
            sql.query("SELECT bot_id, title FROM jobs WHERE $OPEN AND bot_id IS NOT NULL AND due IS NOT NULL AND due <> '' " +
                "AND substr(due,1,10) < ? ORDER BY due, created_at", d) { it.text(0) to it.text(1) }
                .forEach { (b, t) -> val cur = byBot[b]; byBot[b] = if (cur == null) 1 to t else (cur.first + 1) to cur.second }
            return JobCounts(
                today = n("substr(due,1,10) = ?", d),
                overdue = n("due IS NOT NULL AND due <> '' AND substr(due,1,10) < ?", d),
                unassigned = n("bot_id IS NULL"),
                open = n("1=1"),
                overdueByBot = byBot,
            )
        }
    }
}

object TeamModel {
    const val DEFAULT_NAME = "My team"
    const val ALL = "all"
    const val MAX_PINS = 4

    fun header(name: String?, bots: Int, jobs: JobCounts): TeamHeader {
        val parts = ArrayList<String>()
        parts += when (bots) { 0 -> "Manager"; 1 -> "1 bot + Manager"; else -> "$bots bots + Manager" }
        if (jobs.today > 0) parts += if (jobs.today == 1) "1 job today" else "${jobs.today} jobs today"
        if (jobs.overdue > 0) parts += "${jobs.overdue} overdue"
        return TeamHeader(name?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_NAME, parts.joinToString(" · "))
    }

    /** Domain chips: only All until P5. */
    fun chips(): List<ChipModel> = listOf(ChipModel(ALL, "All"))

    /** "10:42" today, "Tue" within the last 6 days, "12 Nov" this year, "12 Nov 2025" before. */
    fun time(at: Long?, now: Long, zone: ZoneId): String {
        if (at == null || at <= 0) return ""
        val t = Instant.ofEpochMilli(at).atZone(zone); val n = Instant.ofEpochMilli(now).atZone(zone)
        val days = n.toLocalDate().toEpochDay() - t.toLocalDate().toEpochDay()
        return when {
            days <= 0 -> t.format(DateTimeFormatter.ofPattern("HH:mm"))
            days < 7 -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            t.year == n.year -> t.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
            else -> t.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
        }
    }

    fun row(b: Bot, t: BotThreads.Thread?, jobs: JobCounts, now: Long, zone: ZoneId): BotRowModel {
        val od = jobs.overdueByBot[b.id]
        val (preview, tone) = when {
            od != null -> "${od.first} overdue · ${od.second}" to PreviewTone.OVERDUE
            else -> (t?.preview?.takeIf { it.isNotBlank() } ?: b.label) to PreviewTone.NORMAL
        }
        val status = when {
            t?.running == true -> "Working"
            (t?.waiting ?: 0) > 0 -> "Waiting for you"
            else -> null
        }
        return BotRowModel(id = b.id, name = b.name, preview = preview, time = time(t?.lastActivityAt, now, zone),
            unread = t?.unread ?: 0, tone = tone, manager = BotRules.isManager(b), pinned = b.pinned, status = status)
    }

    fun state(teamName: String?, roster: List<Bot>, threads: List<BotThreads.Thread>, jobs: JobCounts, now: Long, zone: ZoneId): TeamState {
        val byId = threads.associateBy { it.botId }
        val mgr = roster.firstOrNull(BotRules::isManager)
        val others = roster.filterNot(BotRules::isManager)
        val order = others.withIndex().sortedWith(compareByDescending<IndexedValue<Bot>> { byId[it.value.id]?.lastActivityAt ?: 0L }.thenBy { it.index }).map { it.value }
        val pins = (listOfNotNull(mgr?.takeIf { it.pinned }) + others.filter { it.pinned })
            .take(MAX_PINS).map { PinModel(it.id, it.name, overdue = jobs.overdueByBot.containsKey(it.id), manager = BotRules.isManager(it)) }
        val colors = roster.associate { it.id to (ArgusTokens.botColor(it.avatar.color) ?: ArgusTokens.BOT_COLORS.getValue("silver")) }
        return TeamState(
            header = header(teamName, others.size, jobs),
            chips = chips(),
            pins = pins,
            manager = mgr?.let { row(it, byId[it.id], jobs, now, zone) },
            rows = order.map { row(it, byId[it.id], jobs, now, zone) },
            unassigned = jobs.unassigned,
            openJobs = jobs.open,
            empty = others.isEmpty(),
            colors = colors,
            faces = roster.associate { it.id to it.avatar.face },
        )
    }
}

/** The long-press quick sheet (A-team bot sheet): Open, Pin / Unpin, Memory, Edit soul, Delete. */
enum class QuickAction(val label: String, val icon: String) {
    OPEN("Open", "message-circle"), PIN("Pin", "pin"), UNPIN("Unpin", "pin-off"), MEMORY("Memory", "brain"),
    EDIT_SOUL("Edit soul", "scroll-text"), DELETE("Delete", "trash");

    companion object {
        /**
         * The actions for [b]. The Manager has no Delete (it can never be deleted) and no Pin / Unpin (it is always pinned:
         * BotRules locks `pinned`). Groups (ROOM) have no soul to edit.
         */
        fun of(b: Bot): List<QuickAction> {
            val mgr = BotRules.isManager(b)
            return buildList {
                add(OPEN)
                if (!mgr) add(if (b.pinned) UNPIN else PIN)
                add(MEMORY)
                if (b.kind != BotKind.ROOM) add(EDIT_SOUL)
                if (BotRules.canDelete(b.id)) add(DELETE)
            }
        }
    }
}

/** What the Team screen writes (all through [TeamStore], so the Manager's locks hold). */
class TeamActions(private val store: TeamStore) {
    /** Persists pinned on [id]; refused for the Manager (always pinned). */
    fun setPinned(id: String, pinned: Boolean): TeamStore.Result {
        val b = store.get(id) ?: return TeamStore.Result.Refused("No bot $id.")
        if (BotRules.isManager(b)) return TeamStore.Result.Refused("${BotRules.MANAGER_NAME} is always pinned.")
        if (b.pinned == pinned) return TeamStore.Result.Ok(b)
        return store.update(b.copy(pinned = pinned))
    }

    fun delete(id: String): TeamStore.Result = store.delete(id)

    /**
     * A-team's Demo team (`demo()` in app.js, the ateam fixtures): Nova, Atlas and Echo (both reporting to Nova), Vega.
     * Bots only; the demo jobs come with the jobs queue (P1-TM-05). A name already on the roster is skipped, so a
     * second tap adds nothing. Returns the bots added.
     */
    fun addDemoTeam(now: Long): List<Bot> {
        val out = ArrayList<Bot>()
        val nova = demoBot("nova", "Nova", "Research lead", "#22c55e", null, now)
        for (b in listOf(nova, demoBot("atlas", "Atlas", "Sales ops", "#f59e0b", "nova", now + 1),
            demoBot("echo", "Echo", "Support", "#ef4444", "nova", now + 2), demoBot("vega", "Vega", "Engineering", "#06b6d4", null, now + 3, face = 1))) {
            if (BotRules.nameProblem(b.name, store.all()) != null) continue
            // Nova's real id (insert may have suffixed it); a lead that isn't on the roster falls back to the Manager.
            val lead = if (b.reportsTo == "nova") (out + store.all()).firstOrNull { it.name == "Nova" }?.id ?: BotRules.MANAGER_ID else b.reportsTo
            val r = store.insert(b.copy(reportsTo = lead))
            if (r is TeamStore.Result.Ok) out += r.bot
        }
        return out
    }

    private fun demoBot(id: String, name: String, label: String, color: String, lead: String?, now: Long, face: Int? = null) = Bot(
        id = id, kind = BotKind.CUSTOM, name = name, label = label, avatar = Avatar("orb", color, face = face),
        soulPath = BotRules.soulPath(id), toolGroups = setOf("web", "memory", "jobs"), reportsTo = lead ?: BotRules.MANAGER_ID,
        createdAt = now, u = now)   // A-team `lead` is reports-to, never parentId (a parent's delete cascades to its children)
}
