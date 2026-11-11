package com.blaze.assistant.team

/**
 * P1-TM-01: the roster. Every read goes through [ensure] first, so ARGUS is always there with its locked identity
 * (A-team MANAGER.ensure runs on every load, render, import and sync). Pure over [TeamPort]; the phone singleton
 * is `Team` (TeamAndroid.kt).
 */
class TeamStore(private val port: TeamPort, private val clock: () -> Long = System::currentTimeMillis) {
    private var lastU = 0L

    /** A monotonic stamp for `u` (LWW sync, 05 §9.1): never the same twice, never backwards. */
    @Synchronized fun stamp(): Long { lastU = maxOf(clock(), lastU + 1); return lastU }

    /** Listeners told after a bot is deleted (the job queue unassigns its jobs; threads drop their state). */
    private val onDeleted = ArrayList<(Bot) -> Unit>()
    fun onDeleted(f: (Bot) -> Unit) { synchronized(onDeleted) { onDeleted += f } }

    /** Seeds, repairs and adopts, and writes back what changed. Returns what it did. */
    @Synchronized fun ensure(): Bots.Ensured {
        val before = port.bots()
        val firstRun = port.flag(FLAG_SEEDED) == null
        val e = Bots.ensure(before, stamp(), firstRun)
        if (e.changed) {
            val byId = before.associateBy { it.id }
            for (b in e.bots) if (byId[b.id] != b) port.putBot(b)
            for (b in before) if (e.bots.none { it.id == b.id }) port.deleteBot(b.id)
        }
        if (firstRun) port.setFlag(FLAG_SEEDED, "1")
        return e
    }

    /** The roster: Manager first, then the rest in storage order. [hidden] = include workers/subagents. */
    fun all(hidden: Boolean = false): List<Bot> {
        var l = port.bots()
        if (l.none { it.id == Bots.MANAGER_ID && it.kind == BotKind.MANAGER }) { ensure(); l = port.bots() }
        val m = l.first { it.id == Bots.MANAGER_ID }
        return listOf(m) + l.filter { it.id != Bots.MANAGER_ID && (hidden || !it.hidden) }
    }

    fun manager(): Bot = all().first()
    fun get(id: String): Bot? = all(hidden = true).firstOrNull { it.id == id }
    fun find(ref: String?): Bot? = Bots.find(all(), ref)

    sealed class Result {
        data class Ok(val bot: Bot) : Result()
        data class No(val why: String) : Result()
    }

    /** Adds a bot (never a second Manager). Name must be unique and not reserved. */
    @Synchronized fun create(b0: Bot): Result {
        val bots = all(hidden = true)
        val name = b0.name.trim().take(60)
        if (name.isEmpty()) return Result.No("a name is required")
        if (name.lowercase() in Bots.RESERVED_NAMES) return Result.No("$name is the Manager's name; pick another")
        if (bots.any { it.name.equals(name, true) }) return Result.No("there is already a bot called $name")
        if (b0.kind == BotKind.MANAGER) return Result.No("there is only one Manager")
        val id = if (b0.id.isBlank() || bots.any { it.id == b0.id } || b0.id == Bots.MANAGER_ID) Bots.newId(name, bots.map { it.id }) else b0.id
        val now = stamp()
        val b = b0.copy(id = id, name = name, soulPath = Bots.soulPath(id), reportsTo = b0.reportsTo ?: Bots.MANAGER_ID,
            createdAt = if (b0.createdAt > 0) b0.createdAt else now, u = now)
        port.putBot(b)
        return Result.Ok(b)
    }

    /**
     * Changes a bot. The Manager's identity is locked: [update] on it keeps only what the user may change there
     * (pinned, voice, engine, permissions, apps, skills, soul version) and refuses everything else.
     */
    @Synchronized fun update(id: String, change: (Bot) -> Bot): Result {
        val old = get(id) ?: return Result.No("no bot matches \"$id\"")
        val new = change(old)
        if (Bots.isManager(old)) {
            val locked = Bots.manager(old.createdAt, new)
            if (!Bots.sameIdentity(new.copy(pinned = locked.pinned), locked)) return Result.No(MANAGER_LOCKED)
            val b = locked.copy(createdAt = old.createdAt, u = stamp()); port.putBot(b); return Result.Ok(b)
        }
        val name = new.name.trim().take(60)
        if (name.isEmpty()) return Result.No("a name is required")
        if (name.lowercase() in Bots.RESERVED_NAMES) return Result.No("$name is the Manager's name; pick another")
        if (all(hidden = true).any { it.id != id && it.name.equals(name, true) }) return Result.No("another bot is already called $name")
        if (new.kind == BotKind.MANAGER) return Result.No("there is only one Manager")
        val b = new.copy(id = old.id, name = name, soulPath = old.soulPath, createdAt = old.createdAt, u = stamp())
        port.putBot(b)
        return Result.Ok(b)
    }

    fun setPinned(id: String, pinned: Boolean): Result = update(id) { it.copy(pinned = pinned) }

    /** Deletes a bot. Refuses the Manager (UI and store both refuse, 06 P1-TM-01). Children (workers, subagents) go with it. */
    @Synchronized fun delete(id: String): Result {
        val bots = all(hidden = true)
        if (!Bots.canDelete(id, bots)) return Result.No(if (id.trim().equals(Bots.MANAGER_ID, true) || Bots.isManager(bots.firstOrNull { it.id == id })) NEVER_DELETE else "no bot matches \"$id\"")
        val b = bots.first { it.id == id }
        bots.filter { it.parentId == id }.forEach { port.deleteBot(it.id) }
        port.deleteBot(id)
        synchronized(onDeleted) { onDeleted.toList() }.forEach { runCatching { it(b) } }
        return Result.Ok(b)
    }

    companion object {
        const val FLAG_SEEDED = "team_seeded_v1"
        const val MANAGER_LOCKED = "the Manager (ARGUS) is locked: its name, role, look and instructions can't be changed by anyone"
        const val NEVER_DELETE = "the Manager (ARGUS) can never be deleted"
    }
}
