package com.blaze.assistant.team

import com.blaze.assistant.agent.Specialists
import com.blaze.assistant.store.Sql

/**
 * P1-TM-01: the team roster in team.db (the `bots` + `bot_apps` + `bot_soul_versions` tables of [TeamSchema]), over
 * the same [Sql] seam as blaze.db, so it runs on the phone (AndroidSql on team.db) and in JVM tests (JdbcSql). The rules
 * live in [BotRules]; this class applies them to the stored rows. Ported from A-team `manager.js`:
 *
 *  - [ensure] runs on every open (A-team: "on every load, render, import, wipe and sync"): unreadable rows are left
 *    alone but skipped; any other bot claiming kind MANAGER is demoted to CUSTOM; the Manager is re-seeded when missing
 *    and repaired when a row was edited (the lock); a bot holding a reserved name is renamed; bots without a manager
 *    are adopted; Tempo and Inbox are seeded when absent. It reports what it did ([Ensured]).
 *  - [insert] refuses the Manager's id, kind and name and duplicate names; [update] refuses any change to the
 *    Manager's locked fields (voice, permissions, computer and apps stay editable); [delete] refuses the Manager,
 *    switches a domain bot off instead of removing it, and leaves a removed bot's jobs unassigned.
 *
 * Every call does disk I/O: run it off the main thread. Nothing in the UI reads it yet (P1-SH-01/P1-TM-02 do).
 */
class TeamStore(private val sql: Sql, private val clock: () -> Long = { System.currentTimeMillis() }) {

    data class Ensured(val seeded: Boolean = false, val repaired: Boolean = false, val adopted: Int = 0,
                       val seededDomains: List<String> = emptyList(), val demoted: List<String> = emptyList(),
                       val renamed: List<String> = emptyList(), val unreadable: List<String> = emptyList()) {
        val changed get() = seeded || repaired || adopted > 0 || seededDomains.isNotEmpty() || demoted.isNotEmpty() || renamed.isNotEmpty()
    }

    sealed class Result {
        data class Ok(val bot: Bot) : Result()
        data class Refused(val why: String) : Result()
    }

    // ---------------- reads ----------------

    /** Every readable bot, the Manager first, then by creation time. */
    fun all(): List<Bot> = read().first.sortedWith(compareBy<Bot>({ !BotRules.isManager(it) }, { it.createdAt }, { it.id }))

    /** The roster the user sees: enabled, not hidden (so not workers), the Manager first. */
    fun roster(): List<Bot> = all().filter { it.enabled && !it.hidden }

    fun get(id: String): Bot? = all().firstOrNull { it.id == id }

    /** The worker templates: one per built-in specialist plus the user's own (05 §3.1 "task workers"). Not stored. */
    fun workerTemplates(specs: List<Specialists.Spec> = Specialists.builtIn): List<Bot> = specs.map { BotRules.workerTemplate(it) }

    /** The latest SOUL.md body stored for [id], or null. */
    fun soul(id: String): String? =
        sql.query("SELECT body FROM bot_soul_versions WHERE bot_id=? ORDER BY version DESC LIMIT 1", id) { it.text(0) }.firstOrNull()

    // ---------------- seeding / repair ----------------

    fun ensure(): Ensured = sql.tx {
        val now = clock()
        val (bots, bad) = read()
        var seeded = false; var repaired = false; var adopted = 0
        val demoted = ArrayList<String>(); val renamed = ArrayList<String>(); val domains = ArrayList<String>()

        // 1. only one bot is the Manager: any other MANAGER-kind row becomes a custom bot (A-team keeps one, drops the rest; we keep the data)
        for (b in bots) if (b.kind == BotKind.MANAGER && !BotRules.isManager(b)) {
            write(b.copy(kind = BotKind.CUSTOM, u = now)); demoted += b.id
        }
        // 2. the Manager: re-seed when missing or unreadable, put the locked fields back when edited
        val m0 = bots.firstOrNull(BotRules::isManager)
        if (m0 == null) {
            if (BotRules.MANAGER_ID in bad) { sql.exec("DELETE FROM bot_apps WHERE bot_id=?", BotRules.MANAGER_ID); sql.exec("DELETE FROM bots WHERE id=?", BotRules.MANAGER_ID); repaired = true }
            else seeded = true
            write(BotRules.manager(now))
        } else if (BotRules.lockedDiff(m0).isNotEmpty()) {
            write(BotRules.manager(now, m0).copy(u = now)); repaired = true
        }
        // 3. nobody else holds the Manager's name (an import or an older build): they get a number, the Manager keeps its name
        val taken = bots.filterNot(BotRules::isManager).map { it.name.trim().lowercase() }.toMutableSet()
        for (b in bots) if (!BotRules.isManager(b) && b.name.trim().lowercase() in BotRules.RESERVED_NAMES) {
            var n = 2; var nn: String
            do { nn = "${b.name.trim()} $n"; n++ } while (nn.lowercase() in taken || nn.lowercase() in BotRules.RESERVED_NAMES)
            taken += nn.lowercase(); write(b.copy(name = nn, u = now)); renamed += b.id
        }
        // 4. adoption: every bot reports to someone who exists (A-team: managed_by = manager)
        val ids = bots.map { it.id }.toSet() + BotRules.MANAGER_ID
        for (b in read().first) if (!BotRules.isManager(b) && (b.reportsTo.isNullOrBlank() || b.reportsTo !in ids)) {
            write(b.copy(reportsTo = BotRules.MANAGER_ID, u = now)); adopted++
        }
        // 5. Tempo and Inbox, once: a row that exists (even switched off) is never touched again
        for (s in BotRules.seeds(now)) if (s.id !in ids && s.id !in bad) {
            if (bots.any { it.name.trim().equals(s.name, true) }) continue   // the user already has a bot by that name
            write(s); writeSoul(s.id, 1, BotRules.SEED_SOULS[s.id].orEmpty(), "seed", now); domains += s.id
        }
        Ensured(seeded, repaired, adopted, domains, demoted, renamed, bad.filter { it != BotRules.MANAGER_ID })
    }

    // ---------------- writes ----------------

    /** Adds a new bot. The id is made from the name when blank or taken. */
    fun insert(b: Bot): Result = sql.tx {
        val bots = read().first
        if (b.kind == BotKind.MANAGER || BotRules.isManager(b)) return@tx Result.Refused("There is only one ${BotRules.MANAGER_NAME}; it can't be created.")
        BotRules.nameProblem(b.name, bots)?.let { return@tx Result.Refused(it) }
        val taken = bots.map { it.id }.toSet() + BotRules.MANAGER_ID + read().second
        val base = BotRules.slug(b.id.ifBlank { b.name }).ifBlank { "bot" }
            .let { if (it.startsWith(BotRules.WORKER_PREFIX)) "bot-$it" else it }   // worker-* is the templates' id space
        var id = base; var n = 2
        while (id in taken) { id = "$base-$n"; n++ }
        val now = clock()
        val nb = b.copy(id = id, name = b.name.trim(), soulPath = BotRules.soulPath(id),
            reportsTo = b.reportsTo?.takeIf { it in taken } ?: BotRules.MANAGER_ID,
            createdAt = if (b.createdAt > 0) b.createdAt else now, u = now)
        write(nb)
        Result.Ok(nb)
    }

    /** Saves changes to an existing bot. The Manager takes only voice, permissions, computer and apps (A-team v2.16c lock). */
    fun update(b: Bot): Result = sql.tx {
        val bots = read().first
        val old = bots.firstOrNull { it.id == b.id } ?: return@tx Result.Refused("No bot ${b.id}.")
        val now = clock()
        if (BotRules.isManager(old)) {
            val diff = BotRules.lockedDiff(b)
            if (diff.isNotEmpty()) return@tx Result.Refused("${BotRules.MANAGER_NAME} is locked: its ${diff.joinToString(", ")} can't be changed.")
            val nb = BotRules.manager(now, b).copy(createdAt = old.createdAt, u = now)
            write(nb); return@tx Result.Ok(nb)
        }
        if (b.kind == BotKind.MANAGER) return@tx Result.Refused("Only ${BotRules.MANAGER_NAME} is the Manager.")
        BotRules.nameProblem(b.name, bots, b.id)?.let { return@tx Result.Refused(it) }
        val nb = b.copy(name = b.name.trim(), soulPath = BotRules.soulPath(b.id), createdAt = old.createdAt, u = now)
        write(nb)
        Result.Ok(nb)
    }

    /** Deletes a bot: never the Manager; a domain bot is switched off (kept, so it isn't re-seeded); its jobs become unassigned. */
    fun delete(id: String): Result = sql.tx {
        if (!BotRules.canDelete(id)) return@tx Result.Refused("${BotRules.MANAGER_NAME} can never be deleted.")
        val b = read().first.firstOrNull { it.id == id } ?: return@tx Result.Refused("No bot $id.")
        val now = clock()
        if (b.kind == BotKind.DOMAIN) {
            val off = b.copy(enabled = false, hidden = true, pinned = false, u = now)
            write(off); return@tx Result.Ok(off)
        }
        sql.exec("UPDATE jobs SET bot_id=NULL, u=? WHERE bot_id=?", now, id)
        sql.exec("DELETE FROM bots WHERE id=?", id)   // bot_apps, souls, routines and child workers cascade (05 §9.2)
        Result.Ok(b)
    }

    /** Stores a new SOUL.md version for [id] (not for the Manager, whose soul is the app's prompt). */
    fun saveSoul(id: String, body: String, by: String): Result = sql.tx {
        if (id == BotRules.MANAGER_ID) return@tx Result.Refused("${BotRules.MANAGER_NAME} is locked: its instructions can't be changed.")
        val b = read().first.firstOrNull { it.id == id } ?: return@tx Result.Refused("No bot $id.")
        val v = (sql.query("SELECT max(version) FROM bot_soul_versions WHERE bot_id=?", id) { it.long(0) }.firstOrNull() ?: 0L).toInt() + 1
        val now = clock()
        writeSoul(id, v, body, by, now)
        val nb = b.copy(soulVersion = v, u = now); write(nb)
        Result.Ok(nb)
    }

    /**
     * P1-MG-01: writes an imported bot as it is (its id, `u` and created time kept, for last-writer-wins re-import) and,
     * when [soul] differs from the stored one, a new SOUL version. Never the Manager. No transaction of its own: the
     * importer calls it inside one [Sql.tx] with the jobs ([ATeamImporter]).
     */
    internal fun putImported(b: Bot, soul: String?, by: String): Bot {
        require(!BotRules.isManager(b) && b.kind != BotKind.MANAGER) { "the Manager is never imported" }
        var nb = b.copy(soulPath = BotRules.soulPath(b.id))
        val body = soul?.trim().orEmpty()
        val newSoul = body.isNotEmpty() && body != soul(b.id)?.trim()
        if (newSoul) nb = nb.copy(soulVersion = (sql.query("SELECT max(version) FROM bot_soul_versions WHERE bot_id=?", b.id) { it.long(0) }.firstOrNull() ?: 0L).toInt() + 1)
        write(nb)
        if (newSoul) writeSoul(b.id, nb.soulVersion, body, by, b.u)
        return nb
    }

    // ---------------- rows ----------------

    private val COLS = "id, kind, name, label, domain, avatar, voice, mode, soul_version, skills, tool_groups, tool_grants, engine, " +
        "computer, permissions, routing_hints, reports_to, parent_id, members, hidden, pinned, enabled, created_at, u, email, portfolio"

    /** (readable bots, ids of rows that could not be read). */
    private fun read(): Pair<List<Bot>, List<String>> {
        val apps = HashMap<String, MutableList<BotAppGrant>>()
        sql.query("SELECT bot_id, app_id, source, tools FROM bot_apps ORDER BY bot_id, app_id") { r ->
            runCatching { apps.getOrPut(r.text(0)) { ArrayList() } += BotAppGrant(r.text(1), r.text(2), TeamJson.grants(r.text(3))) }
        }
        val bad = ArrayList<String>()
        val bots = sql.query("SELECT $COLS FROM bots") { r ->
            val id = r.text(0)
            try {
                Bot(id = id, kind = BotKind.valueOf(r.text(1)), name = r.text(2), label = r.text(3), domain = r.text(4).ifEmpty { null },
                    avatar = TeamJson.avatar(r.text(5)), voice = TeamJson.voice(r.text(6)), mode = r.text(7).ifEmpty { "personal" },
                    soulPath = BotRules.soulPath(id), soulVersion = r.long(8).toInt(), skills = TeamJson.strings(r.text(9)),
                    toolGroups = TeamJson.strings(r.text(10)).toSet(), toolGrants = TeamJson.grants(r.text(11)),
                    engine = TeamJson.engine(r.text(12)), computer = TeamJson.computer(r.text(13)), apps = apps[id].orEmpty(),
                    permissions = TeamJson.permissions(r.text(14)), routingHints = TeamJson.strings(r.text(15)),
                    reportsTo = r.text(16).ifEmpty { null }, parentId = r.text(17).ifEmpty { null }, members = TeamJson.strings(r.text(18)),
                    hidden = r.long(19) != 0L, pinned = r.long(20) != 0L, enabled = r.long(21) != 0L,
                    createdAt = r.long(22), u = r.long(23), email = r.text(24), portfolio = r.text(25))
            } catch (_: Exception) { bad += id; null }
        }.filterNotNull()
        return bots to bad
    }

    /** Upserts the row (keeping its chat counters: generation, unread, preview) and replaces its app grants. */
    private fun write(b: Bot) {
        sql.exec("INSERT INTO bots($COLS) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) " +
            "ON CONFLICT(id) DO UPDATE SET kind=excluded.kind, name=excluded.name, label=excluded.label, domain=excluded.domain, " +
            "avatar=excluded.avatar, voice=excluded.voice, mode=excluded.mode, soul_version=excluded.soul_version, skills=excluded.skills, " +
            "tool_groups=excluded.tool_groups, tool_grants=excluded.tool_grants, engine=excluded.engine, computer=excluded.computer, " +
            "permissions=excluded.permissions, routing_hints=excluded.routing_hints, reports_to=excluded.reports_to, parent_id=excluded.parent_id, " +
            "members=excluded.members, hidden=excluded.hidden, pinned=excluded.pinned, enabled=excluded.enabled, created_at=excluded.created_at, u=excluded.u, " +
            "email=excluded.email, portfolio=excluded.portfolio",
            b.id, b.kind.name, b.name, b.label, b.domain, TeamJson.avatar(b.avatar), TeamJson.voice(b.voice), b.mode, b.soulVersion,
            TeamJson.strings(b.skills), TeamJson.strings(b.toolGroups.sorted()), TeamJson.grants(b.toolGrants), TeamJson.engine(b.engine),
            TeamJson.computer(b.computer), TeamJson.permissions(b.permissions), TeamJson.strings(b.routingHints), b.reportsTo, b.parentId,
            TeamJson.strings(b.members), b.hidden, b.pinned, b.enabled, b.createdAt, b.u, b.email, b.portfolio)
        sql.exec("DELETE FROM bot_apps WHERE bot_id=?", b.id)
        for (a in b.apps) sql.exec("INSERT OR REPLACE INTO bot_apps(bot_id, app_id, source, tools) VALUES(?,?,?,?)", b.id, a.appId, a.source, TeamJson.grants(a.tools))
    }

    private fun writeSoul(id: String, v: Int, body: String, by: String, at: Long) =
        sql.exec("INSERT OR REPLACE INTO bot_soul_versions(bot_id, version, body, at, by) VALUES(?,?,?,?,?)", id, v, body, at, by)
}
