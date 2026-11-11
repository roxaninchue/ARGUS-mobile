package com.blaze.assistant.memory

/**
 * P3-AG-01 (05 §4.2, §9.2): who may read and write which memory line. Pure Kotlin, no Android, no I/O (MemScopeTest).
 *
 * Every line in blaze.db `memories` has exactly one owner scope, kept in the Android-only side table `mem_scope`
 * ([MemScopeStore]); the shared `memories` table is never altered, so Blaze Desktop / CLI keep reading the same file.
 *
 * Scopes:
 *  - `user:core`      the user's core profile (name, time zone, allergies, standing rules). Every bot reads it.
 *  - `user:<kind>`    the rest of the profile (`user:people`, `user:places`, `user:dates`, `user:goals`…): the Manager
 *                     and bots whose data scopes list the kind (or `user:*`).
 *  - `team`           cross-domain facts and the A-team "team notes". Every bot reads and writes it.
 *  - `<domain>`       a life-domain's notes (`money`, `travel`…): that domain's bot, bots granted the scope, and the
 *                     lead of a bot that owns it (the Manager leads everyone). PRIVATE domains ([PRIVATE_DOMAINS])
 *                     are readable only by their own bot, or through a share tag; the Manager sees that they exist.
 *  - `bot:<id>`       a bot's own working notes (Hermes MEMORY.md): that bot, and the bot it reports to.
 *  - `session`        chat-tier only; never stored in `memories` (kept here so a stray tag parses).
 *
 * A line can carry share tags (`share:travel`, `share:bot:atlas`): readable by that domain / bot without copying it.
 * A line marked private ([SENSITIVE_PRIVATE]) is readable only by its owner and explicit shares, whatever its scope.
 * Lines past their `expires` are invisible. Lines with no `mem_scope` row (written by Desktop, the CLI or before 0.55)
 * get [defaultScope]: pinned → `user:core`, else `team` (P3-AG-02 writes the rows for good).
 */
object MemScopes {
    const val USER_CORE = "user:core"
    const val TEAM = "team"
    const val SESSION = "session"
    const val USER_PREFIX = "user:"
    const val BOT_PREFIX = "bot:"
    const val SHARE_PREFIX = "share:"
    /** In a bot's data scopes: may read every `user:<kind>`. */
    const val USER_ALL = "user:*"

    const val SENSITIVE_NORMAL = 0
    const val SENSITIVE_PRIVATE = 1
    /** A line the secret check refused; never stored, never shown (kept as a value so a bad row reads safe). */
    const val SENSITIVE_SECRET = 2

    /** Domains whose notes stay with their own bot (05 §4.2). */
    val PRIVATE_DOMAINS = setOf("health", "money", "admin", "health:mind")

    /** A-team profile kinds (manager.js KINDS) → the scope each lands in (P3-AG-02, 05 §4.3). */
    val KIND_SCOPE = linkedMapOf(
        "name" to USER_CORE, "style" to USER_CORE, "rule" to USER_CORE,
        "goal" to "user:goals", "project" to "user:projects", "preference" to "user:preferences",
        "person" to "user:people", "date" to "user:dates", "place" to "user:places", "note" to "user:notes",
    )

    sealed class Scope(val key: String) {
        object UserCore : Scope(USER_CORE)
        class User(val kind: String) : Scope(USER_PREFIX + kind)
        object Team : Scope(TEAM)
        class Domain(val name: String) : Scope(name)
        class BotNotes(val botId: String) : Scope(BOT_PREFIX + botId)
        object Session : Scope(SESSION)
        override fun toString() = key
        override fun equals(other: Any?) = other is Scope && other.key == key
        override fun hashCode() = key.hashCode()
    }

    private val token = Regex("^[a-z0-9][a-z0-9_.:-]{0,47}$")

    /** Parses a scope string; null when it isn't one (blank, spaces, upper case junk, `share:` tags). */
    fun parse(s0: String?): Scope? {
        val s = s0?.trim()?.lowercase().orEmpty()
        if (s.isEmpty() || !token.matches(s) || s.startsWith(SHARE_PREFIX)) return null
        return when {
            s == USER_CORE -> Scope.UserCore
            s == TEAM -> Scope.Team
            s == SESSION -> Scope.Session
            s.startsWith(USER_PREFIX) -> s.removePrefix(USER_PREFIX).takeIf { it.isNotEmpty() && it != "*" && ':' !in it }?.let { Scope.User(it) }
            s.startsWith(BOT_PREFIX) -> s.removePrefix(BOT_PREFIX).takeIf { it.isNotEmpty() }?.let { Scope.BotNotes(it) }
            else -> Scope.Domain(s)
        }
    }

    fun isPrivateDomain(scope: String): Boolean = scope.lowercase() in PRIVATE_DOMAINS

    /** The scope of a line that has no mem_scope row. */
    fun defaultScope(pinned: Boolean): String = if (pinned) USER_CORE else TEAM

    /** Share tags, cleaned: `share:<domain>` or `share:bot:<id>`, lower case, distinct, sorted. */
    fun cleanShares(tags: Collection<String>): Set<String> = tags.map { it.trim().lowercase() }
        .map { if (it.startsWith(SHARE_PREFIX)) it else SHARE_PREFIX + it }
        .filter { val r = it.removePrefix(SHARE_PREFIX); r.isNotEmpty() && token.matches(r) && r != TEAM && !r.startsWith(USER_PREFIX) }
        .toSortedSet()

    // ------------------------------------------------------------------------------------------------ the line

    /** A memory line with its scope (memories ⨝ mem_scope). [created] in unix seconds, like `memories.created`. */
    data class Line(
        val id: Long, val text: String, val scope: String, val botId: String? = null, val kind: String? = null,
        val stated: Boolean = true, val sensitivity: Int = SENSITIVE_NORMAL, val shareTags: Set<String> = emptySet(),
        val source: String = "chat", val expires: Long? = null, val pinned: Boolean = false,
        val uses: Int = 0, val updated: Long = 0, val created: Long = 0,
    )

    // ------------------------------------------------------------------------------------------------ the reader

    /**
     * Who is reading. [dataScopes] = the bot's Permissions.dataScopes (05 §9.1; may hold `user:<kind>`, `user:*`,
     * domains). [reports] = id → domain of every bot whose lead (reportsTo) is this one. The Manager is the lead of all.
     */
    data class Viewer(
        val botId: String, val domain: String? = null, val isManager: Boolean = false,
        val dataScopes: Set<String> = setOf(USER_CORE, TEAM), val reports: Map<String, String?> = emptyMap(),
    ) {
        val shareKeys: Set<String> get() = buildSet {
            add(SHARE_PREFIX + BOT_PREFIX + botId)
            domain?.let { add(SHARE_PREFIX + it.lowercase()) }
        }
    }

    /** Builds the viewer for [botId] from roster facts (keeps Android and team.Bot out of this file). */
    fun viewer(botId: String, domain: String?, isManager: Boolean, dataScopes: Set<String>, roster: List<Triple<String, String?, String?>>): Viewer {
        // roster: (id, domain, reportsTo)
        val reports = if (isManager) roster.filter { it.first != botId }.associate { it.first to it.second }
        else roster.filter { it.third == botId && it.first != botId }.associate { it.first to it.second }
        return Viewer(botId, domain?.lowercase(), isManager, dataScopes.map { it.lowercase() }.toSet(), reports)
    }

    enum class Why { CORE, USER_KIND, TEAM, OWN_DOMAIN, GRANTED, LEAD, OWN_NOTES, SHARED, HIDDEN }

    /** Whether [v] may read [l] now ([now] unix seconds), and why. */
    fun why(v: Viewer, l: Line, now: Long = 0): Why {
        if (l.sensitivity >= SENSITIVE_SECRET) return Why.HIDDEN
        if (l.expires != null && l.expires > 0 && now > 0 && l.expires <= now) return Why.HIDDEN
        val shared = l.shareTags.any { it in v.shareKeys }
        val scope = parse(l.scope) ?: return if (shared) Why.SHARED else Why.HIDDEN
        val owner: Why = when (scope) {
            is Scope.UserCore -> Why.CORE
            is Scope.User -> if (v.isManager || USER_ALL in v.dataScopes || scope.key in v.dataScopes) Why.USER_KIND else Why.HIDDEN
            is Scope.Team -> Why.TEAM
            is Scope.Session -> Why.HIDDEN
            is Scope.BotNotes -> when {
                scope.botId == v.botId -> Why.OWN_NOTES
                scope.botId in v.reports && l.sensitivity == SENSITIVE_NORMAL -> Why.LEAD
                else -> Why.HIDDEN
            }
            is Scope.Domain -> {
                val d = scope.name
                val private = isPrivateDomain(d)
                when {
                    v.domain != null && v.domain == d -> Why.OWN_DOMAIN
                    private -> Why.HIDDEN
                    d in v.dataScopes -> Why.GRANTED
                    v.reports.values.any { it?.lowercase() == d } -> Why.LEAD
                    else -> Why.HIDDEN
                }
            }
        }
        // A private line: only its owner (own domain / own notes / the profile it belongs to) and explicit shares.
        if (l.sensitivity == SENSITIVE_PRIVATE && owner !in setOf(Why.OWN_DOMAIN, Why.OWN_NOTES, Why.CORE, Why.USER_KIND)) return if (shared) Why.SHARED else Why.HIDDEN
        if (owner == Why.HIDDEN && shared) return Why.SHARED
        return owner
    }

    fun visible(v: Viewer, l: Line, now: Long = 0): Boolean = why(v, l, now) != Why.HIDDEN

    /**
     * The Manager sees that private notes exist without reading them (05 §4.2): scope → count of lines it can't read.
     * The UI says "Ledger keeps 4 private notes"; the user can open them from Settings.
     */
    fun hiddenCounts(v: Viewer, lines: List<Line>, now: Long = 0): Map<String, Int> =
        lines.filter { !visible(v, it, now) && it.sensitivity < SENSITIVE_SECRET && parse(it.scope) !is Scope.Session }
            .groupingBy { it.scope }.eachCount().toSortedMap()

    // ------------------------------------------------------------------------------------------------ the writer

    /**
     * Whether bot [v] may write straight into [scope]. Everyone writes `team` and its own `bot:<id>`; a domain bot writes
     * its own domain; only the Manager writes `user:*` directly — any other bot calls memory_propose (P3-AG-05).
     */
    fun canWrite(v: Viewer, scope: String): Boolean = when (val s = parse(scope)) {
        null, is Scope.Session -> false
        is Scope.Team -> true
        is Scope.UserCore, is Scope.User -> v.isManager
        is Scope.BotNotes -> s.botId == v.botId || v.isManager
        is Scope.Domain -> v.domain == s.name || (v.isManager && !isPrivateDomain(s.name))
    }

    /** True when a write by [v] into [scope] must go through a proposal the Manager accepts. */
    fun needsProposal(v: Viewer, scope: String): Boolean = !canWrite(v, scope) && parse(scope).let { it is Scope.UserCore || it is Scope.User }

    // ------------------------------------------------------------------------------------------------ prompt blocks

    /** Prompt budgets (06 P3-AG-01 "prompt ≤ budgets"). */
    const val CORE_CHARS = 1200
    const val RELEVANT_LINES = 5
    const val RELEVANT_CHARS = 800
    const val OWN_NOTES_LINES = 5
    const val DELEGATION_CHARS = 600

    /** The pinned user:core block: pinned user:core lines first (oldest first), within [CORE_CHARS]. "" when none. */
    fun coreBlock(lines: List<Line>, max: Int = CORE_CHARS): String {
        val pick = lines.filter { parse(it.scope) is Scope.UserCore && it.sensitivity < SENSITIVE_SECRET }
            .sortedWith(compareByDescending<Line> { it.pinned }.thenBy { it.created }.thenBy { it.id })
        val sb = StringBuilder()
        for (l in pick) {
            val s = "- ${l.text}${if (!l.stated) " (inferred)" else ""}\n"
            if (sb.length + s.length > max) break
            sb.append(s)
        }
        return if (sb.isEmpty()) "" else "ABOUT THE USER (pinned; every bot reads this)\n$sb"
    }

    /**
     * RELEVANT NOTES for one turn: [hits] (best first, already filtered to what the viewer may read) minus lines in the
     * core block, at most [RELEVANT_LINES] / [RELEVANT_CHARS]; then the bot's own top notes by use. Labels say where a
     * line is from ("team", "money", "your notes", "from Atlas") so the model knows how far it is shared.
     */
    fun relevantBlock(v: Viewer, hits: List<Line>, ownTop: List<Line>, core: String, names: Map<String, String> = emptyMap(),
                      maxLines: Int = RELEVANT_LINES, maxChars: Int = RELEVANT_CHARS): String {
        val sb = StringBuilder(); var n = 0; val seen = HashSet<Long>()
        fun label(l: Line): String = when (val s = parse(l.scope)) {
            is Scope.BotNotes -> if (s.botId == v.botId) "your notes" else "from ${names[s.botId] ?: s.botId}"
            is Scope.UserCore -> "about the user"
            is Scope.User -> "about the user: ${s.kind}"
            else -> l.scope
        }
        fun put(l: Line, lim: Int): Boolean {
            if (n >= lim || l.id in seen || core.contains(l.text)) return true
            val s = "- #${l.id} [${label(l)}] ${l.text}${if (!l.stated) " (inferred)" else ""}\n"
            if (sb.length + s.length > maxChars) return false
            sb.append(s); n++; seen += l.id; return true
        }
        for (l in hits) if (!put(l, maxLines)) break
        for (l in ownTop.sortedWith(compareByDescending<Line> { it.uses }.thenByDescending { it.updated })) if (!put(l, maxLines + OWN_NOTES_LINES)) break
        return if (sb.isEmpty()) "" else "RELEVANT NOTES (memory you can see that matches this message; memory_search for more)\n$sb"
    }

    /** The lines the Manager attached to a job brief (A-team pRelevant), within [DELEGATION_CHARS]. */
    fun delegationBlock(lines: List<String>, max: Int = DELEGATION_CHARS): String {
        val sb = StringBuilder()
        for (t in lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val s = "- $t\n"; if (sb.length + s.length > max) break; sb.append(s)
        }
        return if (sb.isEmpty()) "" else "FROM YOUR BRIEF (what ARGUS passed on for this job)\n$sb"
    }

    /** One rule every bot's memory block carries (05 §4.3 narrowness + scopes). */
    const val SCOPE_RULE = "MEMORY SCOPES\n" +
        "- You see the user's core profile, team notes, your own notes and what is shared with you. Save as narrowly as the user said it, with its context; never turn \"usually\" into \"always\"; mark what you worked out as inferred.\n" +
        "- Team facts go to team; your working notes to your own notes; a fact about the user that you are not the Manager for goes through memory_propose.\n" +
        "- Never save secrets (passwords, keys, card or ID numbers).\n"
}
