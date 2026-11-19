package com.blaze.assistant.team

import com.blaze.assistant.BrandName
import com.blaze.assistant.store.Sql
import org.json.JSONArray
import org.json.JSONObject

/**
 * P1-MG-01: A-team backup import (Settings › Backup & import › Import A-team backup). Takes either file of
 * docs/overhaul/ateam-export.md (A-team's Copy backup `{agents, tasks}`, or `argus-team-export.v1`), checks it with
 * [ATeamExport.validate] (a file that breaks a rule is not imported at all) and maps, per §4.1 / §4.2:
 *
 *  - bots → team.db `bots`, kind CUSTOM, id `at:<A-team id>`: name, role → label, portfolio, look → orb colour + face
 *    (null face → A-team's agentFace, stored, so the orb matches A-team), lead → reports_to (`manager` → ARGUS),
 *    prompt + notes → SOUL; a Gemini model (`gm` / `gemini`) is not applied, it becomes a note in the SOUL and on the
 *    preview ([geminiNote]). The file's Manager is never a second Manager: it is skipped. Emails are dropped (§4.1).
 *  - jobs → team.db `jobs`, id `at:<A-team id>`, source `a-team`: title, notes, brief = notes or title, bot, due,
 *    priority, deps (mapped), status: done → DONE; open → QUEUED ("blocked" while a dep is open), FAILED or WAITING as
 *    A-team's `run` says, and **confirmed = 0**: imported work never starts by itself (§4.2).
 *  - v1 settings: nickname → the user's name when ARGUS has none; theme → ThemeMode (dark / light / Match phone) when
 *    the user leaves "Use A-team's theme" on; pins → pinned. A backup has no settings.
 *  - Not imported here (they say so on the preview): chats, profile, memory, routines, proactivity (P3-MG-01 / §4.6).
 *
 * Re-import is idempotent by last-writer-wins on `u` (A-team's own stamp, kept as the row's `u`): a row is written only
 * when it is new or the file's `u` is newer than the stored one. Importing the same file twice changes nothing, and an
 * edit made in ARGUS after the import (which stamps a newer `u`) is kept. Pure apart from [Sql]; JVM-tested
 * (ATeamImportTest). Disk I/O: call off the main thread.
 */
object ATeamImport {
    const val PREFIX = "at:"
    const val SOURCE = "a-team"
    const val SOUL_BY = "a-team"

    fun id(aTeamId: String) = PREFIX + aTeamId

    /** One bot as the file has it, mapped ([bot] carries the wanted name; the importer may number it). */
    data class BotIn(val aTeamId: String, val bot: Bot, val soul: String?, val gemini: String?)

    /** A file that passed the rules, mapped. [skipped] = what the file holds that this import leaves out, in words. */
    data class File(
        val kind: ATeamExport.Kind,
        val bots: List<BotIn>,
        val jobs: List<Job>,
        val nickname: String?,
        val theme: String?,
        val skipped: List<String>,
    )

    sealed class Read {
        data class Ok(val file: File) : Read()
        data class Bad(val problems: List<String>) : Read()
    }

    /** Reads pasted or picked text: JSON, then the §3 rules, then the mapping. */
    fun read(text: String): Read {
        val o = try { JSONObject(text.trim().removePrefix("\uFEFF")) } catch (_: Exception) {
            return Read.Bad(listOf("That isn't an A-team backup: it isn't JSON."))
        }
        val problems = ATeamExport.validate(o)
        if (problems.isNotEmpty()) return Read.Bad(problems)
        return Read.Ok(map(o, ATeamExport.detect(o)!!))
    }

    private fun isManager(b: JSONObject) = b.optString("id") == ATeamExport.MANAGER_ID || b.optString("builtin") == ATeamExport.MANAGER_ID

    /** The file's bot id → the ARGUS id (the file's Manager → ARGUS's Manager). */
    fun botId(aTeamId: String?, managerIds: Set<String>): String? = when {
        aTeamId.isNullOrBlank() -> null
        aTeamId in managerIds -> BotRules.MANAGER_ID
        else -> id(aTeamId)
    }

    /** Last-writer-wins stamp of a record: its `u`, else its creation time. */
    fun stamp(o: JSONObject): Long = maxOf(o.optLong("u", 0L), o.optLong("createdAt", 0L))

    fun map(o: JSONObject, kind: ATeamExport.Kind): File {
        val v1 = kind == ATeamExport.Kind.V1
        val botsA = objs(o.optJSONArray(if (v1) "bots" else "agents"))
        val jobsA = objs(o.optJSONArray(if (v1) "jobs" else "tasks"))
        val managerIds = botsA.filter(::isManager).map { it.optString("id") }.toSet()
        val settings = if (v1) o.optJSONObject("settings") else null
        val pins = strings(settings?.optJSONArray("pins")).toSet()

        val bots = botsA.filterNot(::isManager).mapIndexed { i, b ->
            val aid = b.optString("id")
            val color = (if (v1) b.optJSONObject("look")?.optString("color") else b.optString("color"))
                ?.takeIf { it.isNotBlank() }?.lowercase() ?: BotDraft.nextColor(i)
            val gemini = str(b, if (v1) "gemini" else "gm")
            val u = stamp(b)
            BotIn(aid, Bot(
                id = id(aid), kind = BotKind.CUSTOM, name = b.optString("name").trim().take(BotRules.NAME_MAX),
                label = b.optString("role").trim().take(BotDraft.LABEL_MAX),
                avatar = Avatar("orb", color, face = ATeamExport.faceOf(b, kind)),
                soulPath = BotRules.soulPath(id(aid)), toolGroups = BotDraft.DEFAULT_GROUPS,
                reportsTo = botId(str(b, "lead") ?: str(b, "reportsTo"), managerIds) ?: BotRules.MANAGER_ID,
                pinned = aid in pins, createdAt = b.optLong("createdAt", u), u = u,
                email = "", portfolio = b.optString("portfolio").trim().take(BotDraft.PORTFOLIO_MAX)),
                soul = soulOf(str(b, "prompt"), str(b, "notes"), gemini), gemini = gemini)
        }

        val doneA = jobsA.filter { it.optString("status") == "done" }.map { it.optString("id") }.toSet()
        val jobs = jobsA.map { j ->
            val run = runOf(j)
            val deps = strings(j.optJSONArray("deps"))
            val u = stamp(j)
            val title = j.optString("title").trim().take(120)
            val notes = j.optString("notes").trim()
            Job(id = id(j.optString("id")), title = title, botId = botId(str(j, if (v1) "bot" else "agentId"), managerIds),
                due = str(j, "due"), priority = j.optString("priority", "normal").ifBlank { "normal" },
                status = statusOf(run, deps.all { it in doneA }), run = run, deps = deps.map(::id), source = SOURCE,
                brief = notes.ifEmpty { title }, notes = notes, confirmed = run == JobRun.DONE,
                createdAt = j.optLong("createdAt", u), doneAt = if (run == JobRun.DONE) j.optLong("doneAt") else null, u = u)
        }

        val skipped = ArrayList<String>()
        val chats = if (v1) o.optJSONObject("chats")?.let { c -> c.keys().asSequence().sumOf { c.optJSONArray(it)?.length() ?: 0 } } ?: 0
            else botsA.filter(::isManager).sumOf { it.optJSONArray("_chat")?.length() ?: 0 }
        val otherChats = if (v1) 0 else botsA.filterNot(::isManager).count { (it.optJSONArray("_chat")?.length() ?: 0) > 0 }
        if (chats > 0) skipped += "the Manager's chat (${plural(chats, "message")})"
        if (otherChats > 0) skipped += "${plural(otherChats, "other bot's chat", "other bots' chats")}"
        if (v1) {
            o.optJSONArray("profile")?.length()?.takeIf { it > 0 }?.let { skipped += "${plural(it, "profile line")}" }
            o.optJSONObject("memory")?.let { m ->
                val n = (m.optJSONArray("team")?.length() ?: 0) +
                    (m.optJSONObject("bots")?.let { b -> b.keys().asSequence().sumOf { b.optJSONArray(it)?.length() ?: 0 } } ?: 0)
                if (n > 0) skipped += "${plural(n, "memory line")}"
            }
            o.optJSONArray("routines")?.length()?.takeIf { it > 0 }?.let { skipped += "${plural(it, "routine")}" }
            if (o.optJSONObject("proactivity") != null) skipped += "proactivity settings"
        }
        return File(kind, bots, jobs, settings?.let { str(it, "nickname")?.trim()?.take(60) }, themeOf(settings?.let { str(it, "theme") }), skipped)
    }

    /** A-team status + run → ARGUS run (§4.2). A run A-team had in flight starts over here, after the user's OK. */
    fun runOf(j: JSONObject): JobRun = when {
        j.optString("status") == "done" -> JobRun.DONE
        j.optString("run") == "failed" -> JobRun.FAILED
        j.optString("run") == "waiting" -> JobRun.WAITING
        else -> JobRun.QUEUED
    }

    /** The list word ([JobStore.statusOf]); a queued job waiting on an open dep is "blocked", as JobQueue.tick would say. */
    fun statusOf(run: JobRun, depsDone: Boolean): String = when (run) {
        JobRun.QUEUED -> if (depsDone) "todo" else "blocked"
        JobRun.RUNNING -> "doing"; JobRun.WAITING -> "waiting"; JobRun.DONE -> "done"
        JobRun.FAILED -> "failed"; JobRun.CANCELLED -> "cancelled"
    }

    /** A-team's theme word → ThemeMode ("phone" = Match phone); null when it isn't one. */
    fun themeOf(t: String?): String? = when (t?.trim()?.lowercase()) {
        "dark" -> "dark"
        "light" -> "light"
        "system", "auto", "phone", "os", "match" -> "phone"
        else -> null
    }

    fun themeLabel(mode: String): String = when (mode) { "phone" -> "Match phone"; "light" -> "Light"; else -> "Dark" }

    /** The per-bot pin note (P3-AG-06 decides pins; until then the model is ARGUS's own choice). */
    fun geminiNote(model: String) = "In A-team this bot ran on Gemini ($model) with your own key. ${BrandName.UPPER} picks the model for now."

    /** SOUL.md from A-team: the custom instructions, then the notes, then the Gemini note; null when there is none. */
    fun soulOf(prompt: String?, notes: String?, gemini: String?): String? {
        val parts = listOfNotNull(prompt?.trim()?.ifEmpty { null }, notes?.trim()?.ifEmpty { null }?.let { "Notes: $it" },
            gemini?.let { geminiNote(it) })
        return parts.joinToString("\n\n").take(BotDraft.INSTRUCTIONS_MAX).ifEmpty { null }
    }

    /** [want], or "[want] 2", "[want] 3"… so no two bots share a name and none takes the Manager's (TeamStore.ensure's rule). */
    fun uniqueName(want: String, taken: Set<String>): String {
        fun free(n: String) = n.lowercase() !in taken && n.lowercase() !in BotRules.RESERVED_NAMES
        if (free(want)) return want
        var n = 2
        while (!free("$want $n")) n++
        return "$want $n"
    }

    private fun str(o: JSONObject, k: String): String? = if (!o.has(k) || o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }
    private fun objs(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

    // ---------------- what an import does, in words (the Backup page) ----------------

    enum class Fate { ADDED, UPDATED, SAME, KEPT }

    /** New, or the file's stamp is newer than the stored one, else the same, else ARGUS's copy is newer (kept). */
    fun fate(stored: Long?, incoming: Long): Fate = when {
        stored == null -> Fate.ADDED
        incoming > stored -> Fate.UPDATED
        incoming == stored -> Fate.SAME
        else -> Fate.KEPT
    }

    data class Tally(val added: Int = 0, val updated: Int = 0, val same: Int = 0, val kept: Int = 0) {
        val total get() = added + updated + same + kept
        val changes get() = added + updated
        fun plus(f: Fate) = when (f) {
            Fate.ADDED -> copy(added = added + 1); Fate.UPDATED -> copy(updated = updated + 1)
            Fate.SAME -> copy(same = same + 1); Fate.KEPT -> copy(kept = kept + 1)
        }
    }

    /** What [ATeamImporter.preview] would do or [ATeamImporter.apply] did. */
    data class Report(
        val bots: Tally, val jobs: Tally,
        /** Open jobs added that wait for the user's OK before they start. */
        val toConfirm: Int,
        /** A-team name → the name it got here, when it was taken. */
        val renamed: List<Pair<String, String>>,
        /** Bot name → its Gemini model (a note, not applied). */
        val gemini: List<Pair<String, String>>,
        /** The name set (only when ARGUS had none). */
        val nickname: String?,
        /** The theme set (ThemeMode value). */
        val theme: String?,
        val skipped: List<String>,
        val applied: Boolean,
    ) {
        val changes get() = bots.changes + jobs.changes + (if (nickname != null) 1 else 0) + (if (theme != null) 1 else 0)
    }

    private fun tallyLine(n: Tally, one: String, many: String): String {
        val parts = listOfNotNull(
            n.added.takeIf { it > 0 }?.let { "$it new" }, n.updated.takeIf { it > 0 }?.let { "$it updated" },
            n.same.takeIf { it > 0 }?.let { "$it already here" }, n.kept.takeIf { it > 0 }?.let { "$it changed here since, kept" })
        return "${plural(n.total, one, many)}: ${parts.joinToString(", ")}"
    }

    /** The preview / receipt lines, in order. */
    fun lines(r: Report): List<String> {
        val out = ArrayList<String>()
        if (r.changes == 0) out += if (r.applied) "Nothing changed: this backup was already imported." else "Already imported: nothing to change."
        if (r.bots.total > 0) out += tallyLine(r.bots, "bot", "bots")
        if (r.jobs.total > 0) out += tallyLine(r.jobs, "job", "jobs") +
            (if (r.toConfirm > 0) ". ${plural(r.toConfirm, "open job waits", "open jobs wait")} for your OK in Desk before ${if (r.toConfirm == 1) "it starts" else "they start"}" else "")
        for ((a, b) in r.renamed) out += "$a is called $b here: the name was taken."
        for ((n, m) in r.gemini) out += "$n ran on Gemini ($m) in A-team; ${BrandName.UPPER} picks the model for now."
        r.nickname?.let { out += "Your name: $it" }
        r.theme?.let { out += "Theme: ${themeLabel(it)}" }
        if (r.skipped.isNotEmpty()) out += "Not imported yet: ${r.skipped.joinToString(", ")}."
        return out
    }
}

/** The two app settings an import may set (Settings.userName, ThemeMode); the page passes the real ones, tests a map. */
interface ImportPrefs {
    var userName: String
    var themeMode: String
}

/**
 * P1-MG-01: applies an [ATeamImport.File] to team.db. [preview] writes nothing; [apply] writes every bot, soul and job in
 * one transaction (nothing is half-imported), then the name and theme. Runs [TeamStore.ensure] first so the Manager the
 * file's bots report to is there.
 */
class ATeamImporter(
    private val sql: Sql,
    private val store: TeamStore,
    private val jobs: JobStore,
    private val prefs: ImportPrefs,
) {
    fun preview(f: ATeamImport.File, useTheme: Boolean = true): ATeamImport.Report = run(f, useTheme, write = false)

    fun apply(f: ATeamImport.File, useTheme: Boolean = true): ATeamImport.Report {
        store.ensure()
        return run(f, useTheme, write = true)
    }

    private fun run(f: ATeamImport.File, useTheme: Boolean, write: Boolean): ATeamImport.Report {
        var bots = ATeamImport.Tally(); var js = ATeamImport.Tally(); var confirm = 0
        val renamed = ArrayList<Pair<String, String>>()
        val writes = ArrayList<() -> Unit>()
        val roster = store.all()
        val byId = roster.associateBy { it.id }
        val incomingIds = f.bots.map { it.bot.id }.toSet()
        // names held by bots this import doesn't touch, then each imported bot's name as it is decided
        val taken = roster.filter { it.id !in incomingIds }.map { it.name.trim().lowercase() }.toMutableSet()

        val fates = f.bots.map { ATeamImport.fate(byId[it.bot.id]?.u, it.bot.u) }
        f.bots.zip(fates).filter { it.second == ATeamImport.Fate.SAME || it.second == ATeamImport.Fate.KEPT }
            .forEach { (b, _) -> taken += byId.getValue(b.bot.id).name.trim().lowercase() }
        for ((b, fate) in f.bots.zip(fates)) {
            bots = bots.plus(fate)
            if (fate != ATeamImport.Fate.ADDED && fate != ATeamImport.Fate.UPDATED) continue
            val name = ATeamImport.uniqueName(b.bot.name, taken)
            taken += name.lowercase()
            if (name != b.bot.name) renamed += b.bot.name to name
            val old = byId[b.bot.id]
            val nb = if (old == null) b.bot.copy(name = name)
                else old.copy(name = name, label = b.bot.label, avatar = old.avatar.copy(color = b.bot.avatar.color, face = b.bot.avatar.face),
                    reportsTo = b.bot.reportsTo, portfolio = b.bot.portfolio, kind = BotKind.CUSTOM,
                    pinned = if (f.kind == ATeamExport.Kind.V1) b.bot.pinned else old.pinned, createdAt = b.bot.createdAt, u = b.bot.u)
            writes += { store.putImported(nb, b.soul, ATeamImport.SOUL_BY) }
        }

        for (j in f.jobs) {
            val old = jobs.get(j.id)
            val fate = ATeamImport.fate(old?.u, j.u)
            js = js.plus(fate)
            when (fate) {
                ATeamImport.Fate.ADDED -> {
                    if (!j.confirmed && j.run in JobStore.OPEN) confirm++
                    writes += { jobs.write(j); jobs.step(j.id, "Imported from A-team") }
                }
                ATeamImport.Fate.UPDATED -> {
                    val nj = j.copy(confirmed = j.confirmed || (old!!.confirmed && j.run in JobStore.OPEN), result = old!!.result)
                    writes += { jobs.write(nj) }
                }
                else -> Unit
            }
        }

        val nick = f.nickname?.takeIf { it.isNotBlank() && prefs.userName.isBlank() }
        val theme = f.theme?.takeIf { useTheme && it != prefs.themeMode }
        if (write) {
            if (writes.isNotEmpty()) sql.tx { writes.forEach { it() } }
            nick?.let { prefs.userName = it }
            theme?.let { prefs.themeMode = it }
        }
        return ATeamImport.Report(bots, js, confirm, renamed, f.bots.mapNotNull { b -> b.gemini?.let { b.bot.name to it } },
            nick, theme, f.skipped, applied = write)
    }
}
