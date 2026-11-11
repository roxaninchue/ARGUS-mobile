package com.blaze.assistant.team

/**
 * P1-TM-01: the roster rules for [Bot] records (05 §1.1, 06 R6/R7, D3). Pure: no Android, no I/O.
 *
 * - ARGUS is the Manager: fixed id [MANAGER_ID], kind MANAGER, undeletable, identity locked (name, label, look, kind,
 *   lead position). It is seeded on first run and on upgrade, re-seeded when its record is missing, and repaired when
 *   corrupt (A-team manager.js ensureState / canDelete, v2.15–v2.16c).
 * - Tempo (Time & calendar) and Inbox (Comms) are seeded once with ARGUS (06 R7); deleting them later is the user's
 *   call and they are not brought back.
 * - The built-in specialists become WORKER templates ([worker]): hidden, never on the roster, spawned per job.
 */
object Bots {
    const val MANAGER_ID = "manager"
    const val MANAGER_NAME = "ARGUS"
    const val MANAGER_LABEL = "Manager"
    /** The brand cobalt (04 §2 brandCobalt): only the Manager's orb uses it. */
    const val MANAGER_COLOR = "#0D28FF"
    /** The Manager's orb is the ARGUS "A" sphere (ArgusMark), not one of the six faces. */
    const val MANAGER_SHAPE = "argus"

    /** A-team BOT_FACES (app.js): the six orb ring styles, by index. */
    val FACES = listOf("Orbit", "Tilt", "Steep", "Pure", "Twin", "Polar")
    /** A-team COLORS (app.js v2.16): the eight identity swatches; any other #RRGGBB is allowed too. */
    val COLORS = listOf("#8b5cf6", "#22c55e", "#f59e0b", "#ef4444", "#06b6d4", "#6366f1", "#3b82f6", "#cbd5e1")

    /** Names nobody else may take: they would read as the Manager. */
    val RESERVED_NAMES = setOf("argus", "manager", "team manager")

    /** Tool groups the Manager gets (05 §3.2 row Manager). "team" and "jobs" are the P1 team tool groups. */
    val MANAGER_GROUPS = setOf("team", "jobs", "memory", "routines", "web", "phone")

    const val TEMPO_ID = "tempo"
    const val INBOX_ID = "inbox"
    /** Seeded with the Manager on first run (06 R7). */
    val SEED_DOMAINS = listOf(TEMPO_ID, INBOX_ID)

    fun soulPath(id: String) = "bots/$id/SOUL.md"

    fun isManager(b: Bot?) = b != null && (b.id == MANAGER_ID || b.kind == BotKind.MANAGER)

    /** Only the Manager is protected: everything else on the roster can be deleted (with a yes, see ManagerTools). */
    fun canDelete(id: String, bots: List<Bot>): Boolean {
        if (id.trim().equals(MANAGER_ID, true)) return false
        val b = bots.firstOrNull { it.id == id } ?: return false
        return !isManager(b)
    }

    fun isHex(c: String) = Regex("^#[0-9a-fA-F]{6}$").matches(c.trim())

    /** The colour for the n-th bot when none was chosen (A-team: COLORS[agents.length % 8]). */
    fun colorFor(n: Int) = COLORS[((n % COLORS.size) + COLORS.size) % COLORS.size]

    /** A-team agentFace: a stable face from the name when none was chosen. */
    fun faceFor(name: String) = name.sumOf { it.code } % FACES.size

    fun avatar(face: Int, color: String) = Avatar(shape = "orb", color = color, face = ((face % FACES.size) + FACES.size) % FACES.size)

    // ---------------------------------------------------------------- seeds

    /** The Manager as the app fixes it. [keep] carries over what the user may change (pinned, soul version, enabled). */
    fun manager(now: Long, keep: Bot? = null): Bot = Bot(
        id = MANAGER_ID, kind = BotKind.MANAGER, name = MANAGER_NAME, label = MANAGER_LABEL, domain = null,
        avatar = Avatar(shape = MANAGER_SHAPE, color = MANAGER_COLOR, face = null, image = null),
        voice = keep?.voice ?: Voice(), mode = "personal",
        soulPath = soulPath(MANAGER_ID), soulVersion = keep?.soulVersion?.coerceAtLeast(1) ?: 1,
        skills = keep?.skills ?: emptyList(),
        toolGroups = MANAGER_GROUPS, toolGrants = keep?.toolGrants ?: emptyMap(),
        engine = keep?.engine ?: EngineSpec(), computer = keep?.computer ?: ComputerSpec(),
        apps = keep?.apps ?: emptyList(), permissions = keep?.permissions ?: Permissions(),
        reportsTo = null, parentId = null, members = emptyList(),
        hidden = false, pinned = keep?.pinned ?: false, enabled = true,
        routingHints = emptyList(), createdAt = keep?.createdAt?.takeIf { it > 0 } ?: now, u = now,
    )

    /** Tempo · Time & calendar (05 §3.2). */
    fun tempo(now: Long) = domain(TEMPO_ID, "Tempo", "Time & calendar", "time", face = 1, color = COLORS[4],
        groups = setOf("comms", "routines", "device", "memory"),
        hints = listOf("calendar", "meeting", "schedule", "remind", "reminder", "alarm", "tomorrow", "tonight", "appointment", "free time", "busy", "reschedule"),
        now = now)

    /** Inbox · Comms (05 §3.2). */
    fun inbox(now: Long) = domain(INBOX_ID, "Inbox", "Comms", "comms", face = 3, color = COLORS[6],
        groups = setOf("connectors", "comms", "notifications", "memory"),
        hints = listOf("email", "mail", "inbox", "message", "messages", "reply", "notification", "whatsapp", "slack", "draft", "unread"),
        now = now)

    fun domain(id: String, name: String, label: String, domain: String, face: Int, color: String, groups: Set<String>,
               hints: List<String>, now: Long): Bot = Bot(
        id = id, kind = BotKind.DOMAIN, name = name, label = label, domain = domain, avatar = avatar(face, color),
        soulPath = soulPath(id), toolGroups = groups, reportsTo = MANAGER_ID, routingHints = hints, createdAt = now, u = now,
        permissions = Permissions(dataScopes = setOf("user:core", "team", domain)),
    )

    fun seed(id: String, now: Long): Bot? = when (id) { TEMPO_ID -> tempo(now); INBOX_ID -> inbox(now); else -> null }

    /** A built-in or custom specialist as a WORKER template (06 P1-TM-01): hidden, never on the roster. */
    fun worker(id: String, name: String, groups: Set<String>, skills: List<String>, now: Long, color: String = "", face: Int? = null): Bot = Bot(
        id = "worker:$id", kind = BotKind.WORKER, name = name, label = "Worker", domain = null,
        avatar = avatar(face ?: faceFor(name), color.takeIf { isHex(it) } ?: COLORS[7]),
        soulPath = soulPath("worker-$id"), skills = skills, toolGroups = groups, reportsTo = MANAGER_ID,
        hidden = true, createdAt = now, u = now,
    )

    // ---------------------------------------------------------------- ensure (seed, repair, adopt)

    data class Ensured(val bots: List<Bot>, val seeded: Boolean, val repaired: Boolean, val adopted: Int, val seededDomains: List<String>) {
        val changed get() = seeded || repaired || adopted > 0 || seededDomains.isNotEmpty()
    }

    /**
     * Seeds, repairs and adopts (A-team ensureState). [firstRun] = the team was never seeded on this phone: Tempo and
     * Inbox are added once (not again after the user deletes them). Rules:
     * - broken records (blank id or name) are dropped; duplicates of an id keep the first;
     * - exactly one Manager: id "manager", every locked field put back; a second MANAGER-kind record becomes CUSTOM;
     * - no other bot may carry a reserved name: an imported "ARGUS"/"Manager" is renamed "<name> 2";
     * - every DOMAIN / CUSTOM / REMOTE / ROOM bot without a lead reports to the Manager (adopted).
     * Order: Manager first, then the rest as they were.
     */
    fun ensure(input: List<Bot?>, now: Long, firstRun: Boolean = false): Ensured {
        var repaired = false
        val seen = HashSet<String>()
        val clean = ArrayList<Bot>()
        for (b in input) {
            if (b == null || b.id.isBlank() || b.name.isBlank() || !seen.add(b.id)) { repaired = true; continue }
            clean += b
        }
        val old = clean.firstOrNull { it.id == MANAGER_ID }
        val seeded = old == null
        val m = manager(now, old)
        if (old != null && !sameIdentity(old, m)) repaired = true
        var adopted = 0
        val others = clean.filter { it.id != MANAGER_ID }.map { b0 ->
            var b = b0
            if (b.kind == BotKind.MANAGER) { b = b.copy(kind = BotKind.CUSTOM, u = now); repaired = true }
            if (b.name.trim().lowercase() in RESERVED_NAMES) { b = b.copy(name = uniqueName(b.name.trim() + " 2", clean.map { it.name }), u = now); repaired = true }
            if (b.reportsTo == null && b.kind in setOf(BotKind.DOMAIN, BotKind.CUSTOM, BotKind.REMOTE, BotKind.ROOM)) { b = b.copy(reportsTo = MANAGER_ID, u = now); adopted++ }
            b
        }
        val add = if (firstRun) SEED_DOMAINS.filter { id -> others.none { it.id == id } }.mapNotNull { seed(it, now) } else emptyList()
        val keepOld = old != null && sameIdentity(old, m)
        return Ensured(listOf(if (keepOld) old!! else m) + others + add, seeded, repaired, adopted, add.map { it.id })
    }

    /** The locked fields only (u and createdAt aside). */
    fun sameIdentity(a: Bot, b: Bot) = a.id == b.id && a.kind == b.kind && a.name == b.name && a.label == b.label && a.domain == b.domain &&
        a.avatar == b.avatar && a.mode == b.mode && a.soulPath == b.soulPath && a.toolGroups == b.toolGroups && a.reportsTo == b.reportsTo &&
        a.parentId == b.parentId && a.members.isEmpty() && !a.hidden && a.enabled && a.routingHints == b.routingHints

    fun uniqueName(want: String, taken: Collection<String>): String {
        val low = taken.map { it.lowercase() }.toSet()
        if (want.lowercase() !in low && want.lowercase() !in RESERVED_NAMES) return want
        val base = want.replace(Regex("\\s+\\d+$"), "")
        var n = 2
        while ("$base $n".lowercase() in low) n++
        return "$base $n"
    }

    // ---------------------------------------------------------------- lookup

    /** Finds a bot by id, exact name, first name, then a ≥3-letter name prefix (A-team api findAgent). Hidden bots never match. */
    fun find(bots: List<Bot>, ref: String?): Bot? {
        val r = ref?.trim()?.removePrefix("@")?.trim().orEmpty()
        if (r.isEmpty()) return null
        val vis = bots.filter { !it.hidden }
        val low = r.lowercase()
        return vis.firstOrNull { it.id == r } ?: vis.firstOrNull { it.name.lowercase() == low }
            ?: (if (low == "manager" || low == "argus") vis.firstOrNull { isManager(it) } else null)
            ?: vis.firstOrNull { it.name.lowercase().split(Regex("\\s+")).first() == low }
            ?: (if (low.length >= 3) vis.firstOrNull { it.name.lowercase().startsWith(low) } else null)
    }

    /** "Ledger · Money" (D2: persona name with the life-area label underneath). */
    fun display(b: Bot) = if (b.label.isBlank()) b.name else "${b.name} \u00b7 ${b.label}"

    /** A new bot id from its name: a slug, made unique against [taken]. */
    fun newId(name: String, taken: Collection<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "bot" }
            .let { if (it == MANAGER_ID || it.startsWith("worker")) "bot-$it" else it }
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }
}
