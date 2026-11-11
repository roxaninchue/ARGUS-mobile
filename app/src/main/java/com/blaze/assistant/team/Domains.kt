package com.blaze.assistant.team

import com.blaze.assistant.memory.MemScopes
import org.json.JSONArray
import org.json.JSONObject

/**
 * P5-AG-01 (05 §3, 06 R5, NEW-02): the life-domain catalog. Pure Kotlin (org.json only, like the rest of the team rules);
 * DomainTemplateTest.
 *
 * Each of the 14 domain agents ships as data, not code, under `assets/domains/<id>/`:
 * ```
 * assets/domains/index.json          format, the 14 ids in roster order, the 10 Life areas
 * assets/domains/<id>/domain.json    the template ([Template]; schema in docs/overhaul/P5-AG-01-LOG.md)
 * assets/domains/<id>/SOUL.md        the seed soul (parses with [Souls.parse]; Boundaries locked)
 * assets/domains/<id>/PLAYBOOK.md    the field playbook (passes [com.blaze.assistant.agent.Playbooks.validateDomain])
 * assets/domains/<id>/skills/<slug>.md the domain's own skills (SKILL.md front matter: name, description, tags, version)
 * ```
 * The id is the persona slug (`ledger`, `atlas`…), the same id the roster uses ([Bots.TEMPO_ID], [Bots.INBOX_ID]); the
 * life-domain *label* ("Money") is fixed, the *name* ("Ledger") is the renamable default (D2). `memory.owns` is the
 * memory scope the bot owns and becomes [Bot.domain] (MemScopes reads it as the viewer's own domain).
 *
 * 06 R5: the 14 agents are canonical; 04's 10 domains are *Life areas* that group them ([AREAS]). Mind and Phone are
 * Manager-owned areas with no domain agent of their own.
 *
 * Reading: [loader] reads a path under `assets/domains/` (DomainAssets.kt sets it on the phone; tests read the source
 * tree). User-made domains (create-domain-from-template, [fromTemplate]) live in filesDir/domains/<id>/ with the same
 * files ([UserDomains]) and are merged into [catalog] by [userLoader].
 */
object Domains {
    const val FORMAT = 1

    /** The 14 domain agents, in roster / setup-picker order (05 §3.1). */
    val IDS = listOf("tempo", "inbox", "pulse", "ledger", "career", "forge", "hearth", "pantry", "atlas", "circle", "tutor", "clerk", "scout", "studio")

    /** Seeded on at first run with the Manager (05 §3.1, 06 R7); the rest wait for setup. Matches [Bots.SEED_DOMAINS]. */
    val SEEDED = listOf(Bots.TEMPO_ID, Bots.INBOX_ID)

    /** The domains whose memory scope is private (05 §3.2): their scope is in [MemScopes.PRIVATE_DOMAINS]. */
    val PRIVATE = setOf("pulse", "ledger", "clerk")

    /**
     * Tool groups a domain may list: the groups [com.blaze.assistant.agent.Specialists.groupNames] enforces today plus the
     * 05 §6.1 registry groups (team, jobs, user, files, ubuntu, cloud, apps, dev, media, bank, health), which stay inert
     * until the P4 ToolRegistry maps them. Unknown groups fail validation.
     */
    val TOOL_GROUPS = setOf("web", "phone", "comms", "connectors", "memory", "routines", "terminal", "device", "notifications", "browser", "study", "design",
        "team", "jobs", "user", "files", "ubuntu", "cloud", "apps", "dev", "media", "bank", "health")

    /** Worker templates a domain may spawn: the 10 built-in specialists + Reviewer (P5-TM-03). */
    val WORKERS = setOf("researcher", "operator", "writer", "planner", "coder", "shopper", "booker", "tutor", "designer", "inbox", "reviewer")

    val ROUTINE_KINDS = setOf("CRON", "EVENT", "CHECKIN", "WATCH")

    // ------------------------------------------------------------------------------------------------ Life areas

    /**
     * A Life tile (04 §3.3 hues, 06 R5 grouping). [domains] = its domain agents, the first is the default lead; empty for
     * the Manager-owned areas ([lead] = "manager"). [related] = domains that feed the area without belonging to it.
     */
    data class Area(val id: String, val label: String, val hue: String, val glyph: String, val domains: List<String>,
                    val related: List<String> = emptyList()) {
        val managerOwned: Boolean get() = domains.isEmpty()
        val lead: String get() = domains.firstOrNull() ?: Bots.MANAGER_ID
    }

    val AREAS = listOf(
        Area("health", "Health", "#22C55E", "heart-pulse", listOf("pulse")),
        Area("money", "Money", "#F59E0B", "wallet", listOf("ledger", "scout")),
        Area("work", "Work", "#6366F1", "briefcase", listOf("career", "inbox", "tempo", "forge")),
        Area("home", "Home", "#06B6D4", "house", listOf("hearth", "pantry", "clerk")),
        Area("people", "People", "#EC4899", "users", listOf("circle")),
        Area("learning", "Learning", "#8B5CF6", "graduation-cap", listOf("tutor")),
        Area("travel", "Travel", "#3B82F6", "plane", listOf("atlas")),
        Area("create", "Create", "#EF4444", "palette", listOf("studio")),
        Area("mind", "Mind", "#CBD5E1", "notebook-pen", emptyList(), related = listOf("pulse")),
        Area("phone", "Phone", "#9B9BA3", "smartphone", emptyList()),
    )

    fun area(id: String?): Area? = AREAS.firstOrNull { it.id == id }

    /** The Life area a domain agent belongs to (built-ins by [AREAS], user domains by their own `area`). */
    fun areaOf(domainId: String): Area? = AREAS.firstOrNull { domainId in it.domains } ?: get(domainId)?.let { area(it.area) }

    // ------------------------------------------------------------------------------------------------ the template

    data class Look(val shape: String = "orb", val face: Int = 0, val color: String, val glyph: String)
    data class Memory(val owns: String, val private: Boolean = false, val reads: List<String> = emptyList(), val optional: List<String> = emptyList())
    data class Perms(val autonomy: String = "ACT", val actions: Map<String, String> = emptyMap(), val spendCapMonthly: Long = 0,
                     val currency: String = "ZMW", val sendScope: String = "DRAFT_ONLY")
    /** A default routine (off until the user accepts it, 05 §3.2). [schedule] = 5-field cron for CRON/WATCH, an event spec for EVENT, "HH:MM" for CHECKIN. */
    data class RoutineT(val name: String, val kind: String, val schedule: String, val prompt: String, val silentIfNothing: Boolean = true)
    data class SuggestionRules(val maxPerWeek: Int = 2, val topics: List<String> = emptyList())

    data class Template(
        val id: String, val label: String, val defaultName: String, val area: String, val about: String,
        val avatar: Look, val routingHints: List<String>, val toolGroups: List<String>,
        val suggestedApps: List<String> = emptyList(), val workers: List<String> = emptyList(),
        val skills: List<String> = emptyList(), val sharedSkills: List<String> = emptyList(),
        val memory: Memory, val permissions: Perms = Perms(), val approvals: List<String> = emptyList(),
        val routines: List<RoutineT> = emptyList(), val signals: List<String> = emptyList(),
        val suggestions: SuggestionRules = SuggestionRules(),
        val seeded: Boolean = false, val proposeFor: List<String> = emptyList(),
        val format: Int = FORMAT, val builtIn: Boolean = true, val basedOn: String? = null,
    ) {
        val private: Boolean get() = memory.private
        /** The bot's data scopes: core + team + what it owns + what it reads (05 §9.1 Permissions.dataScopes). */
        val dataScopes: Set<String> get() = linkedSetOf(MemScopes.USER_CORE, MemScopes.TEAM, memory.owns) + memory.reads
        /** "Ledger · Money" (D2). */
        val display: String get() = "$defaultName \u00b7 $label"
    }

    sealed class Parsed {
        data class Ok(val t: Template) : Parsed()
        data class Bad(val why: String) : Parsed()
    }

    private fun JSONObject.strs(k: String): List<String> = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } } ?: emptyList()

    fun parse(json: String, builtIn: Boolean = true): Parsed = try {
        val o = JSONObject(json)
        val av = o.optJSONObject("avatar") ?: JSONObject()
        val mem = o.optJSONObject("memory") ?: return Parsed.Bad("domain.json has no \"memory\" block")
        val pm = o.optJSONObject("permissions") ?: JSONObject()
        val acts = LinkedHashMap<String, String>()
        pm.optJSONObject("actions")?.let { a -> a.keys().forEach { k -> acts[k.trim().uppercase()] = a.optString(k).trim().uppercase() } }
        val routines = o.optJSONArray("routines")?.let { a -> (0 until a.length()).map { i ->
            val r = a.getJSONObject(i)
            RoutineT(r.optString("name").trim(), r.optString("kind").trim().uppercase(), r.optString("schedule").trim(), r.optString("prompt").trim(), r.optBoolean("silentIfNothing", true))
        } } ?: emptyList()
        val sg = o.optJSONObject("suggestions") ?: JSONObject()
        val id = o.optString("id").trim()
        if (id.isEmpty()) Parsed.Bad("domain.json has no \"id\"") else Parsed.Ok(Template(
            id = id, label = o.optString("label").trim(), defaultName = o.optString("defaultName").trim(),
            area = o.optString("area").trim(), about = o.optString("about").trim(),
            avatar = Look(av.optString("shape", "orb"), av.optInt("face", 0), av.optString("color").trim(), av.optString("glyph").trim()),
            routingHints = o.strs("routingHints").map { it.lowercase() }, toolGroups = o.strs("toolGroups"),
            suggestedApps = o.strs("suggestedApps"), workers = o.strs("workers"), skills = o.strs("skills"), sharedSkills = o.strs("sharedSkills"),
            memory = Memory(mem.optString("owns").trim().lowercase(), mem.optBoolean("private", false), mem.strs("reads").map { it.lowercase() }, mem.strs("optional").map { it.lowercase() }),
            permissions = Perms(pm.optString("autonomy", "ACT").uppercase(), acts, pm.optLong("spendCapMonthly", 0), pm.optString("currency", "ZMW"), pm.optString("sendScope", "DRAFT_ONLY").uppercase()),
            approvals = o.strs("approvals"), routines = routines, signals = o.strs("signals"),
            suggestions = SuggestionRules(sg.optInt("maxPerWeek", 2), sg.strs("topics")),
            seeded = o.optBoolean("seeded", false), proposeFor = o.strs("proposeFor"),
            format = o.optInt("format", FORMAT), builtIn = builtIn, basedOn = o.optString("basedOn").trim().ifEmpty { null },
        ))
    } catch (e: Exception) { Parsed.Bad("domain.json is not valid JSON: ${e.message}") }

    fun toJson(t: Template): JSONObject = JSONObject().apply {
        put("format", t.format); put("id", t.id); put("label", t.label); put("defaultName", t.defaultName); put("area", t.area); put("about", t.about)
        put("avatar", JSONObject().put("shape", t.avatar.shape).put("face", t.avatar.face).put("color", t.avatar.color).put("glyph", t.avatar.glyph))
        put("seeded", t.seeded); if (t.proposeFor.isNotEmpty()) put("proposeFor", JSONArray(t.proposeFor))
        t.basedOn?.let { put("basedOn", it) }
        put("routingHints", JSONArray(t.routingHints)); put("toolGroups", JSONArray(t.toolGroups)); put("suggestedApps", JSONArray(t.suggestedApps))
        put("workers", JSONArray(t.workers)); put("skills", JSONArray(t.skills)); put("sharedSkills", JSONArray(t.sharedSkills))
        put("memory", JSONObject().put("owns", t.memory.owns).put("private", t.memory.private).put("reads", JSONArray(t.memory.reads)).put("optional", JSONArray(t.memory.optional)))
        put("permissions", JSONObject().put("autonomy", t.permissions.autonomy).put("actions", JSONObject(t.permissions.actions as Map<*, *>))
            .put("spendCapMonthly", t.permissions.spendCapMonthly).put("currency", t.permissions.currency).put("sendScope", t.permissions.sendScope))
        put("approvals", JSONArray(t.approvals))
        put("routines", JSONArray(t.routines.map { JSONObject().put("name", it.name).put("kind", it.kind).put("schedule", it.schedule).put("prompt", it.prompt).put("silentIfNothing", it.silentIfNothing) }))
        put("signals", JSONArray(t.signals))
        put("suggestions", JSONObject().put("maxPerWeek", t.suggestions.maxPerWeek).put("topics", JSONArray(t.suggestions.topics)))
    }

    fun render(t: Template): String = toJson(t).toString(2)

    // ------------------------------------------------------------------------------------------------ validation

    data class Check(val ok: Boolean, val problems: List<String>)

    private val slugRe = Regex("^[a-z][a-z0-9-]{1,23}$")
    private val scopeRe = Regex("^[a-z0-9][a-z0-9_.:-]{0,47}$")
    private val cronField = Regex("^(\\*|\\d{1,2}(-\\d{1,2})?)(/\\d{1,2})?(,(\\*|\\d{1,2}(-\\d{1,2})?)(/\\d{1,2})?)*$")
    private val eventRe = Regex("^[a-z]+(\\.[a-z0-9-]+)+(:[a-z0-9-]+)?$")
    private val hhmm = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    fun isCron(s: String): Boolean = s.trim().split(Regex("\\s+")).let { f -> f.size == 5 && f.all { cronField.matches(it) } }

    /**
     * The template rules (data only; the files are checked by [validateFiles]). [others] = the rest of the catalog, for
     * unique ids / names / scopes / hints.
     */
    fun validate(t: Template, others: List<Template> = emptyList()): Check {
        val p = ArrayList<String>()
        if (t.format != FORMAT) p += "format ${t.format} is not $FORMAT"
        if (!slugRe.matches(t.id) || t.id == Bots.MANAGER_ID || t.id.startsWith("worker")) p += "id \"${t.id}\" must be a lower-case slug (not manager / worker…)"
        if (t.label.isBlank()) p += "label is empty"
        if (t.defaultName.isBlank()) p += "defaultName is empty"
        if (t.defaultName.trim().lowercase() in Bots.RESERVED_NAMES) p += "defaultName \"${t.defaultName}\" reads as the Manager"
        if (t.about.length < 20) p += "about is too short (one line on what this bot does)"
        val ar = area(t.area)
        if (ar == null) p += "area \"${t.area}\" is not one of ${AREAS.joinToString { it.id }}"
        else if (t.builtIn && t.id !in ar.domains) p += "area \"${t.area}\" doesn't list ${t.id} (06 R5)"
        if (!Bots.isHex(t.avatar.color)) p += "avatar.color \"${t.avatar.color}\" is not #RRGGBB"
        if (t.avatar.face !in Bots.FACES.indices) p += "avatar.face ${t.avatar.face} is not 0..${Bots.FACES.size - 1}"
        if (t.avatar.glyph.isBlank()) p += "avatar.glyph is empty (a Lucide glyph name)"
        val minHints = if (t.builtIn) 8 else 3
        if (t.routingHints.size < minHints) p += "routingHints has ${t.routingHints.size}; give at least $minHints"
        if (t.routingHints.size != t.routingHints.distinct().size) p += "routingHints has duplicates"
        if (t.toolGroups.isEmpty()) p += "toolGroups is empty"
        (t.toolGroups - TOOL_GROUPS).takeIf { it.isNotEmpty() }?.let { p += "unknown tool groups $it" }
        if ("memory" !in t.toolGroups) p += "toolGroups must include memory"
        (t.workers - WORKERS).takeIf { it.isNotEmpty() }?.let { p += "unknown workers $it" }
        t.skills.filterNot { slugRe.matches(it) }.takeIf { it.isNotEmpty() }?.let { p += "skill slugs $it are not lower-case slugs" }
        // memory
        val owns = t.memory.owns
        val sc = MemScopes.parse(owns)
        if (sc !is MemScopes.Scope.Domain) p += "memory.owns \"$owns\" must be a domain scope (not user:/team/bot:)"
        if (t.memory.private != MemScopes.isPrivateDomain(owns)) p += "memory.private=${t.memory.private} but MemScopes says \"$owns\" is ${if (MemScopes.isPrivateDomain(owns)) "private" else "not private"}"
        for (r in t.memory.reads + t.memory.optional) if (!scopeRe.matches(r) || r.startsWith(MemScopes.SHARE_PREFIX) || r == MemScopes.SESSION) p += "memory scope \"$r\" is not a scope"
        for (r in t.memory.reads) if (MemScopes.isPrivateDomain(r) && r != owns) p += "memory.reads \"$r\" is another domain's private scope"
        if (MemScopes.USER_CORE !in t.memory.reads) p += "memory.reads must include user:core"
        // permissions
        if (runCatching { Autonomy.valueOf(t.permissions.autonomy) }.isFailure) p += "permissions.autonomy \"${t.permissions.autonomy}\" is not ASSIST/ACT/AUTOPILOT"
        if (runCatching { SendScope.valueOf(t.permissions.sendScope) }.isFailure) p += "permissions.sendScope \"${t.permissions.sendScope}\" is not a SendScope"
        for ((k, v) in t.permissions.actions) {
            if (runCatching { ActionClass.valueOf(k) }.isFailure) p += "permissions.actions key \"$k\" is not an action class"
            if (runCatching { Rule.valueOf(v) }.isFailure) p += "permissions.actions.$k \"$v\" is not ALLOW/ASK/NEVER"
        }
        // 05 §7: spending, sending, deleting and credentials are never pre-allowed by a template.
        for (k in listOf("SPEND", "SEND", "DELETE", "CREDENTIAL")) if (t.permissions.actions[k] == "ALLOW") p += "permissions.actions.$k may not be ALLOW in a template"
        if (t.permissions.spendCapMonthly != 0L) p += "spendCapMonthly must start at 0 (the user raises it)"
        if (t.private) {
            if (t.permissions.sendScope !in setOf("NONE", "DRAFT_ONLY")) p += "a private domain may only draft (sendScope NONE or DRAFT_ONLY)"
            if (t.approvals.none { it.contains("shar", true) }) p += "a private domain must list sharing its data under approvals"
        }
        if (t.approvals.isEmpty()) p += "approvals is empty (what always shows a card, 05 §3.2)"
        // routines
        for (r in t.routines) {
            if (r.name.isBlank() || r.prompt.length < 15) p += "routine \"${r.name}\" needs a name and a real prompt"
            if (r.kind !in ROUTINE_KINDS) p += "routine \"${r.name}\" kind \"${r.kind}\" is not ${ROUTINE_KINDS.joinToString("/")}"
            val okSched = when (r.kind) { "CRON", "WATCH" -> isCron(r.schedule); "EVENT" -> eventRe.matches(r.schedule); "CHECKIN" -> hhmm.matches(r.schedule); else -> false }
            if (!okSched) p += "routine \"${r.name}\" schedule \"${r.schedule}\" doesn't fit kind ${r.kind}"
        }
        for (s in t.signals) if (!eventRe.matches(s) && !Regex("^[a-z]+(\\.[a-z0-9-]+)*$").matches(s)) p += "signal \"$s\" is not a dotted name"
        if (t.suggestions.maxPerWeek !in 0..7) p += "suggestions.maxPerWeek must be 0..7"
        // uniqueness across the catalog
        for (o in others) if (o.id != t.id) {
            if (o.defaultName.equals(t.defaultName, true)) p += "defaultName \"${t.defaultName}\" is also ${o.id}'s"
            if (o.memory.owns == owns) p += "memory scope \"$owns\" is also owned by ${o.id}"
            val shared = t.routingHints.intersect(o.routingHints.toSet())
            if (shared.isNotEmpty()) p += "routingHints $shared are also ${o.id}'s (keep hints unique so routing has one winner)"
        }
        return Check(p.isEmpty(), p)
    }

    /** The files next to domain.json: SOUL.md parses and matches, PLAYBOOK.md passes the playbook gate, every skill exists. */
    fun validateFiles(t: Template, soul: String?, playbook: String?, skill: (String) -> String?, otherPlaybooks: List<String> = emptyList()): Check {
        val p = ArrayList<String>()
        if (soul == null) p += "SOUL.md is missing" else when (val s = Souls.parse(soul)) {
            is Souls.Parsed.Bad -> p += "SOUL.md doesn't parse: ${s.why} (line ${s.line})"
            is Souls.Parsed.Ok -> {
                val so = s.soul
                if (so.id != t.id) p += "SOUL.md id \"${so.id}\" is not ${t.id}"
                if (so.name != t.defaultName) p += "SOUL.md name \"${so.name}\" is not ${t.defaultName}"
                if (so.label != t.label) p += "SOUL.md label \"${so.label}\" is not ${t.label}"
                for (h in Souls.HEADINGS) if (h !in so.sections) p += "SOUL.md has no \"# $h\""
                for (h in listOf(Souls.WHO, Souls.HOW, Souls.OWN, Souls.BOUNDARIES)) if (so.section(h).length < 40) p += "SOUL.md \"# $h\" is too thin"
                if (Souls.BOUNDARIES !in so.locked) p += "SOUL.md must lock Boundaries"
                if (Souls.RULES in so.locked) p += "SOUL.md must leave Standing rules editable"
                if (so.rules.isNotEmpty()) p += "SOUL.md ships with standing rules; those are the user's"
                for (b in Souls.BOUNDARIES_TEXT) if (!so.section(Souls.BOUNDARIES).contains(b)) p += "SOUL.md Boundaries lacks the shared line \"${b.take(40)}…\""
                if (Souls.hasSecret(soul)) p += "SOUL.md looks like it holds a secret"
                if (Souls.render(so).length > Souls.MAX_CHARS) p += "SOUL.md is over ${Souls.MAX_CHARS} characters"
            }
        }
        if (playbook == null) p += "PLAYBOOK.md is missing"
        else com.blaze.assistant.agent.Playbooks.validateDomain(playbook, fieldOf(t), otherPlaybooks).let { if (!it.ok) p += it.problems.map { "PLAYBOOK.md: $it" } }
        for (slug in t.skills) {
            val md = skill(slug)
            if (md == null) { p += "skills/$slug.md is missing"; continue }
            val fm = frontMatter(md)
            if (fm["name"] != slug) p += "skills/$slug.md front matter name is \"${fm["name"]}\", not $slug"
            if ((fm["description"]?.length ?: 0) < 40) p += "skills/$slug.md needs a description (when to use it)"
            if (md.length < 400) p += "skills/$slug.md is too thin"
        }
        return Check(p.isEmpty(), p)
    }

    /** The words the playbook gate checks a domain playbook against (name, label, what it does, its routing hints). */
    fun fieldOf(t: Template): String = com.blaze.assistant.agent.Playbooks.domainField(t.defaultName, t.label, t.about, t.routingHints)

    /** SKILL.md-style front matter (`key: value` between `---` lines). */
    fun frontMatter(md: String): Map<String, String> {
        val l = md.replace("\r\n", "\n").lines()
        if (l.firstOrNull()?.trim() != "---") return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (x in l.drop(1)) { if (x.trim() == "---") break; val c = x.indexOf(':'); if (c > 0) out[x.substring(0, c).trim()] = x.substring(c + 1).trim() }
        return out
    }

    // ------------------------------------------------------------------------------------------------ loading

    /** Reads `assets/domains/<path>` ("index.json", "ledger/domain.json", "ledger/skills/bills-calendar.md"). */
    @Volatile var loader: ((String) -> String?)? = null
    /** Reads `filesDir/domains/<path>` for user-made domains ([UserDomains.reader]). */
    @Volatile var userLoader: ((String) -> String?)? = null
    /** Ids of user-made domains ([UserDomains.ids]). */
    @Volatile var userIds: (() -> List<String>)? = null

    @Volatile private var cached: List<Template>? = null

    fun reset() { cached = null }

    private fun read(path: String): String? = try { loader?.invoke(path) } catch (_: Throwable) { null }
    private fun readUser(path: String): String? = try { userLoader?.invoke(path) } catch (_: Throwable) { null }

    /** The index order from assets/domains/index.json ([IDS] when it can't be read). */
    fun indexIds(): List<String> = read("index.json")?.let { runCatching { JSONObject(it).strs("domains") }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: IDS

    /** Built-ins (index order) then user-made domains. A file that doesn't parse is skipped (the test keeps built-ins clean). */
    fun catalog(): List<Template> = cached ?: run {
        val b = indexIds().mapNotNull { id -> read("$id/domain.json")?.let { (parse(it, builtIn = true) as? Parsed.Ok)?.t } }
        val u = (try { userIds?.invoke() } catch (_: Throwable) { null }).orEmpty().filter { it !in IDS }
            .mapNotNull { id -> readUser("$id/domain.json")?.let { (parse(it, builtIn = false) as? Parsed.Ok)?.t } }
        (b + u).also { if (b.isNotEmpty()) cached = it }
    }

    fun get(id: String?): Template? = id?.let { i -> catalog().firstOrNull { it.id == i } }
    fun byArea(areaId: String): List<Template> = catalog().filter { it.area == areaId }
    /** The domain that owns memory scope [scope] ("money" → Ledger). */
    fun forScope(scope: String): Template? = catalog().firstOrNull { it.memory.owns == scope.trim().lowercase() }

    private fun file(t: Template, name: String) = if (t.builtIn) read("${t.id}/$name") else readUser("${t.id}/$name")
    fun soul(t: Template): String? = file(t, "SOUL.md")
    fun playbook(t: Template): String? = file(t, "PLAYBOOK.md")
    fun skill(t: Template, slug: String): String? = file(t, "skills/$slug.md")

    // ------------------------------------------------------------------------------------------------ to a Bot

    /** The roster record for a template (05 §9.1). [name] = the user's rename (D2); [enabled] defaults to [Template.seeded]. */
    fun toBot(t: Template, now: Long, id: String = t.id, name: String = t.defaultName, enabled: Boolean = t.seeded): Bot {
        val actions = LinkedHashMap(DEFAULT_RULES)
        for ((k, v) in t.permissions.actions) runCatching { actions[ActionClass.valueOf(k)] = Rule.valueOf(v) }
        return Bot(
            id = id, kind = BotKind.DOMAIN, name = name, label = t.label, domain = t.memory.owns,
            avatar = Bots.avatar(t.avatar.face, t.avatar.color), soulPath = Bots.soulPath(id),
            skills = t.skills.map { "domain:${t.id}/$it" } + t.sharedSkills,
            toolGroups = t.toolGroups.toSet(), reportsTo = Bots.MANAGER_ID, enabled = enabled,
            routingHints = t.routingHints, createdAt = now, u = now,
            permissions = Permissions(
                autonomy = runCatching { Autonomy.valueOf(t.permissions.autonomy) }.getOrDefault(Autonomy.ACT), actions = actions,
                spendCapMonthlyMinor = t.permissions.spendCapMonthly, currency = t.permissions.currency,
                sendScope = runCatching { SendScope.valueOf(t.permissions.sendScope) }.getOrDefault(SendScope.DRAFT_ONLY),
                dataScopes = t.dataScopes,
            ),
        )
    }

    /** The SoulStore profile for a domain bot, so regenerate / reset keep the domain's "What you own" line. */
    fun profile(t: Template, b: Bot? = null, owner: String = "the user"): Souls.Profile =
        Souls.Profile(id = b?.id ?: t.id, name = b?.name ?: t.defaultName, label = t.label, domain = t.memory.owns, isManager = false, owns = t.about, owner = owner)

    /**
     * Writes the template's SOUL.md as the bot's first soul when it has none yet (instead of SoulStore's generic
     * template). A renamed bot gets its own name and id in the front matter. Returns true when it wrote.
     */
    fun installSoul(port: SoulPort, t: Template, b: Bot, now: Long): Boolean {
        if (port.read(b.id) != null) return false
        val raw = soul(t) ?: return false
        val s = (Souls.parse(raw) as? Souls.Parsed.Ok)?.soul ?: return false
        val who = if (b.name != t.defaultName) s.sections[Souls.WHO].orEmpty().replace(t.defaultName, b.name) else s.section(Souls.WHO)
        val sec = LinkedHashMap(s.sections); sec[Souls.WHO] = who
        val body = Souls.render(s.copy(id = b.id, name = b.name, label = t.label, version = 1, sections = sec))
        port.write(b.id, body); port.putVersion(Souls.Version(b.id, 1, body, now, "template"))
        return true
    }

    /** How many of [hints] occur in [text] (word-boundary, case-insensitive). The cheap signal rooms and routing v2 start from. */
    fun hintHits(text: String, hints: List<String>): Int {
        val low = " " + text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ") + " "
        return hints.count { h -> low.contains(" " + h.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " ") }
    }

    // ------------------------------------------------------------------------------------------------ create from template

    /** "A bot for my dog": what the user (or ARGUS via agent_create template=…) asks for. */
    data class NewDomain(val name: String, val label: String, val about: String, val area: String? = null,
                         val hints: List<String> = emptyList(), val basedOn: String? = null, val private: Boolean = false)

    sealed class Created {
        /**
         * [playbook] is always null: a copied playbook would fail the copy check and isn't about the new field, so the
         * caller generates one ([com.blaze.assistant.agent.Playbooks.generateMessages]) and gates it with
         * [com.blaze.assistant.agent.Playbooks.validateDomain] against [fieldOf] before [UserDomains.save].
         */
        data class Ok(val template: Template, val soul: String, val playbook: String?) : Created()
        data class Refused(val why: String) : Created()
    }

    /**
     * Create a domain from a template (05 §3.3): copies [base] (or a blank domain) with a new id, name, label, scope and
     * hints. The new scope is the label's slug; it may not take a built-in's or an existing one's scope. Private is
     * honoured only for a scope MemScopes treats as private (a user can't invent a private scope yet; logged for P3).
     * Hints that another domain already owns are dropped (routing keeps one winner); at least 3 must remain.
     */
    fun fromTemplate(req: NewDomain, base: Template?, existing: List<Template>, takenBotIds: Collection<String>): Created {
        val name = req.name.trim(); val label = req.label.trim()
        if (name.isEmpty() || label.isEmpty()) return Created.Refused("a domain needs a name and a label")
        if (name.lowercase() in Bots.RESERVED_NAMES) return Created.Refused("\"$name\" reads as the Manager; pick another name")
        if (existing.any { it.defaultName.equals(name, true) }) return Created.Refused("there is already a domain called $name")
        val id = Bots.newId(name, takenBotIds + existing.map { it.id })
        var scope = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { id }
        if (MemScopes.parse(scope) !is MemScopes.Scope.Domain || existing.any { it.memory.owns == scope } || MemScopes.isPrivateDomain(scope)) scope = "x-$id"
        val taken = existing.flatMap { it.routingHints }.toSet()
        val hints = (req.hints.map { it.trim().lowercase() } + Words.of(label + " " + req.about))
            .filter { it.length >= 3 && it !in taken }.distinct().take(16)
        if (hints.size < 3) return Created.Refused("give a few words people would use for $label (the ones offered are already another bot's)")
        val areaId = req.area?.takeIf { area(it) != null } ?: base?.area ?: "home"
        val about = req.about.trim().ifEmpty { base?.about ?: "Everything about ${label.lowercase()} the user hands over." }
        val look = base?.avatar?.copy(face = Bots.faceFor(name)) ?: Look("orb", Bots.faceFor(name), area(areaId)?.hue ?: Bots.colorFor(existing.size), "sparkles")
        val t = Template(
            id = id, label = label, defaultName = name, area = areaId, about = about, avatar = look, routingHints = hints,
            toolGroups = base?.toolGroups ?: listOf("web", "memory", "routines"), suggestedApps = base?.suggestedApps ?: emptyList(),
            workers = base?.workers ?: listOf("researcher", "writer"), skills = emptyList(), sharedSkills = base?.sharedSkills ?: emptyList(),
            memory = Memory(scope, private = false, reads = listOf(MemScopes.USER_CORE)),
            permissions = (base?.permissions ?: Perms()).copy(spendCapMonthly = 0),
            approvals = base?.approvals ?: listOf("any payment or purchase", "any message to another person", "sharing its notes with another bot"),
            routines = emptyList(), signals = base?.signals ?: emptyList(),
            suggestions = SuggestionRules(1, emptyList()), seeded = false, builtIn = false, basedOn = base?.id,
        )
        val soul = Souls.template(profile(t)).let { s ->
            val b = LinkedHashMap(s.sections)
            base?.let { bt -> soul(bt)?.let { (Souls.parse(it) as? Souls.Parsed.Ok)?.soul }?.let { bs ->
                b[Souls.HOW] = bs.section(Souls.HOW)
                b[Souls.BOUNDARIES] = bs.section(Souls.BOUNDARIES).replace(bt.defaultName, name)
            } }
            Souls.render(s.copy(sections = b))
        }
        val check = validate(t, existing)
        if (!check.ok) return Created.Refused(check.problems.first())
        return Created.Ok(t, soul, null)
    }

    /** Hint words from a label and its about line (plain split; Playbooks.words stems, which hints must not). */
    private object Words {
        private val stop = setOf("the", "and", "for", "with", "that", "this", "your", "about", "everything", "user", "hands", "over", "from", "into", "their", "them")
        fun of(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 && it !in stop }
    }
}

/**
 * filesDir/domains/<id>/{domain.json, SOUL.md, PLAYBOOK.md}: user-made domains (create-domain-from-template). Pure
 * java.io; the phone points it at `context.filesDir`.
 */
class UserDomains(private val root: java.io.File) {
    private fun dir(id: String) = java.io.File(java.io.File(root, "domains"), id.replace(Regex("[^a-z0-9-]"), "_"))
    fun ids(): List<String> = (java.io.File(root, "domains").listFiles() ?: emptyArray()).filter { java.io.File(it, "domain.json").isFile }.map { it.name }.sorted()
    fun reader(path: String): String? = java.io.File(java.io.File(root, "domains"), path).takeIf { it.isFile && !path.contains("..") }?.readText()
    fun save(t: Domains.Template, soul: String, playbook: String?) {
        val d = dir(t.id); d.mkdirs()
        java.io.File(d, "domain.json").writeText(Domains.render(t))
        java.io.File(d, "SOUL.md").writeText(soul)
        if (playbook != null) java.io.File(d, "PLAYBOOK.md").writeText(playbook)
    }
    fun delete(id: String) { if (id !in Domains.IDS) dir(id).deleteRecursively() }
    /** Wires [Domains.userLoader] / [Domains.userIds] to this folder. */
    fun install() { Domains.userLoader = ::reader; Domains.userIds = ::ids; Domains.reset() }
}
