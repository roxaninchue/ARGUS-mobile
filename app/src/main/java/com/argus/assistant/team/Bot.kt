package com.blaze.assistant.team

import com.blaze.assistant.BrandName
import com.blaze.assistant.agent.Specialists

/**
 * P1-TM-01: the rules over a [Bot] that every store, screen and tool shares. Pure (no Android, no I/O), so each rule is
 * a JVM test. Ported from A-team `manager.js` (ensureState / insert / canDelete / the v2.16c lock) and 05 §3.
 *
 *  - ARGUS is the Manager: one bot with the fixed id [MANAGER_ID], kind MANAGER, its identity locked by the app
 *    ([manager] says what it is; [lockedDiff] says what an edit tried to change). It can never be deleted ([canDelete]).
 *  - Tempo (Time & calendar) and Inbox (Comms) are the two domain bots seeded on day one (05 §3.1: "seeded off except
 *    Manager, Tempo and Inbox"). Domain bots are switched off, never removed, so a re-seed can't bring one back that the
 *    user turned off.
 *  - The built-in specialists are WORKER templates ([workerTemplate]): bots of their own kind, hidden from the roster,
 *    that any bot may hand a task to. `delegate` still runs them through [Specialists] unchanged.
 */
object BotRules {
    const val MANAGER_ID = "manager"
    const val TEMPO_ID = "tempo"
    const val INBOX_ID = "inbox"

    /** The Manager's shown name: the app's own (BrandName, "Argus"). Locked. */
    val MANAGER_NAME: String get() = BrandName.NAME
    const val MANAGER_LABEL = "Lead"
    /** 04 §2 brandCobalt: ARGUS-only (Manager orb glow). Locked. */
    const val MANAGER_COLOR = "#3466FF"
    const val MANAGER_SHAPE = "sphere"
    /** P1-TM-04: the Manager's portfolio line (A-team manager.js PORTFOLIO, in our words). Locked like its name. */
    const val MANAGER_PORTFOLIO = "Runs the team: routes work, creates bots, keeps the roster tidy, and keeps track of what you need."

    /** 05 §3.2 Manager tool groups. */
    val MANAGER_GROUPS = setOf("team", "jobs", "memory", "routines", "web", "phone")

    /** Names no other bot may take (case-insensitive): the Manager's, and A-team's old name for it. */
    val RESERVED_NAMES: Set<String> get() = setOf(MANAGER_NAME.lowercase(), BrandName.UPPER.lowercase(), "manager")

    const val NAME_MAX = 60

    fun soulPath(id: String) = "bots/$id/SOUL.md"

    fun isManager(b: Bot) = b.id == MANAGER_ID

    /** The Manager exactly as the app defines it. [keep] carries over what the user may set on it (voice, permissions, computer). */
    fun manager(now: Long, keep: Bot? = null): Bot = Bot(
        id = MANAGER_ID, kind = BotKind.MANAGER, name = MANAGER_NAME, label = MANAGER_LABEL, domain = null,
        avatar = Avatar(MANAGER_SHAPE, MANAGER_COLOR), voice = keep?.voice ?: Voice(), mode = "personal",
        soulPath = soulPath(MANAGER_ID), soulVersion = 1, skills = emptyList(),
        toolGroups = MANAGER_GROUPS, toolGrants = emptyMap(), engine = EngineSpec(),
        computer = keep?.computer ?: ComputerSpec(), apps = keep?.apps ?: emptyList(),
        permissions = keep?.permissions ?: Permissions(),
        reportsTo = null, parentId = null, members = emptyList(),
        hidden = false, pinned = true, enabled = true, routingHints = emptyList(),
        createdAt = keep?.createdAt?.takeIf { it > 0 } ?: now, u = keep?.u ?: now,
        email = "", portfolio = MANAGER_PORTFOLIO)

    /**
     * The fields of [b] (a Manager row) that differ from what the app fixes: empty means the row is the Manager as
     * defined (allowed fields aside). Used both to repair a stored row and to refuse an edit.
     */
    fun lockedDiff(b: Bot): List<String> {
        val m = manager(b.createdAt, b)
        val out = ArrayList<String>()
        fun chk(name: String, a: Any?, z: Any?) { if (a != z) out += name }
        chk("kind", b.kind, m.kind); chk("name", b.name, m.name); chk("label", b.label, m.label); chk("domain", b.domain, m.domain)
        chk("avatar", b.avatar, m.avatar); chk("mode", b.mode, m.mode); chk("soul", b.soulPath, m.soulPath)
        chk("skills", b.skills, m.skills); chk("tools", b.toolGroups, m.toolGroups); chk("toolGrants", b.toolGrants, m.toolGrants)
        chk("engine", b.engine, m.engine); chk("reportsTo", b.reportsTo, m.reportsTo); chk("parent", b.parentId, m.parentId)
        chk("members", b.members, m.members); chk("hidden", b.hidden, m.hidden); chk("pinned", b.pinned, m.pinned)
        chk("enabled", b.enabled, m.enabled); chk("routingHints", b.routingHints, m.routingHints)
        chk("email", b.email, m.email); chk("portfolio", b.portfolio, m.portfolio)
        return out
    }

    /** Tempo, 05 §3.2: calendar, reminders, scheduling, conflicts, alarms. */
    fun tempo(now: Long) = domain(TEMPO_ID, "Tempo", "Time & calendar", "time", "#7C5CFF",
        setOf("routines", "device", "comms", "connectors"), now)

    /** Inbox, 05 §3.2: mail, messages, notification triage, drafts. Never sends without approval (Permissions default DRAFT_ONLY). */
    fun inbox(now: Long) = domain(INBOX_ID, "Inbox", "Comms", "comms", "#2BB673",
        setOf("connectors", "comms", "notifications", "memory"), now)

    /** The bots seeded on day one besides the Manager, in roster order. */
    fun seeds(now: Long): List<Bot> = listOf(tempo(now), inbox(now + 1))   // +1 ms keeps the roster order (sorted by creation)

    /** First-version souls for the seeded domains (the role line of 05 §3.2). The Manager's soul is the app's own prompt. */
    val SEED_SOULS = mapOf(
        TEMPO_ID to "You are Tempo, the user's time and calendar bot. You handle the calendar, reminders, scheduling, conflicts, focus blocks and alarms. " +
            "Ask before sending invites or RSVPs to other people, and before cancelling a meeting that has guests.",
        INBOX_ID to "You are Inbox, the user's comms bot. You triage mail, messages and notifications, draft replies and track follow-ups owed. " +
            "Never send anything without the user's approval of the full draft and recipients.")

    private fun domain(id: String, name: String, label: String, domain: String, color: String, groups: Set<String>, now: Long) = Bot(
        id = id, kind = BotKind.DOMAIN, name = name, label = label, domain = domain, avatar = Avatar("orb", color),
        soulPath = soulPath(id), toolGroups = groups, reportsTo = MANAGER_ID, createdAt = now, u = now)

    /** A built-in or custom specialist as a WORKER template: hidden from the roster, reports to its manager (default ARGUS). */
    fun workerTemplate(s: Specialists.Spec, now: Long = 0L): Bot = Bot(
        id = workerId(s.id), kind = BotKind.WORKER, name = s.name, label = "Worker", domain = null,
        avatar = Avatar(s.avatar.ifBlank { "orb" }, s.colour.ifBlank { WORKER_COLOR }),
        voice = if (s.voice.isBlank()) Voice() else Voice(id = s.voice),
        soulPath = soulPath(workerId(s.id)), skills = s.skills, toolGroups = s.groups,
        engine = EngineSpec(model = ModelPolicy(pinned = s.modelPref.ifBlank { null })),
        permissions = Permissions(dataScopes = if (s.memoryScope.isBlank()) Permissions().dataScopes else setOf("user:core", "team", s.memoryScope)),
        reportsTo = s.managedBy.ifBlank { MANAGER_ID }, hidden = true, createdAt = now, u = now)

    /**
     * Worker templates live in their own id space, `worker-<specialist id>`, so the Inbox-manager specialist (`inbox`)
     * never collides with the Inbox domain bot (`inbox`). [specialistId] maps back for `delegate`, which keeps taking the
     * plain specialist id. No other bot may take an id in this space ([TeamStore.insert]).
     */
    const val WORKER_PREFIX = "worker-"
    fun workerId(specialistId: String) = WORKER_PREFIX + specialistId
    fun specialistId(workerId: String): String? = workerId.takeIf { it.startsWith(WORKER_PREFIX) }?.removePrefix(WORKER_PREFIX)?.ifEmpty { null }

    /** Monochrome ink for workers (04 §2: colour is for a bot's identity; workers have none of their own until set). */
    const val WORKER_COLOR = "#8A8F98"

    /** Only the Manager can't be deleted (A-team canDelete). Domain bots are deletable but are switched off, not removed. */
    fun canDelete(id: String) = id.trim().lowercase() != MANAGER_ID

    /** Why [name] can't be a bot's name next to [others] (null = fine). Case-insensitive, like A-team agent_update. */
    fun nameProblem(name: String, others: Collection<Bot>, selfId: String? = null): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Give it a name."
        if (n.length > NAME_MAX) return "Keep the name under $NAME_MAX characters."
        if (selfId != MANAGER_ID && n.lowercase() in RESERVED_NAMES) return "$n is ${MANAGER_NAME}'s name."
        if (others.any { it.id != selfId && it.name.trim().equals(n, true) }) return "Another bot is already called $n."
        return null
    }

    fun slug(s: String) = Specialists.slug(s)
}
