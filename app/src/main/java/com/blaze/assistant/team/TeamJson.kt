package com.blaze.assistant.team

/**
 * P0-TM-01: JSON for the team models ([Bot], [Job], [JobStep], [Row], [BotMessage], [Routine], [Suggestion], [Approval],
 * [MemScope]) and for the JSON-typed team.db columns (bots.avatar / voice / engine / computer / permissions / skills /
 * tool_groups / tool_grants / members / routing_hints, bot_apps.tools, jobs.deps, suggestions.risk,
 * mem_scope.share_tags). Pure Kotlin with its own small reader and writer, no org.json (android.jar only has stubs of
 * it), so every round trip is a JVM test (TeamJsonTest).
 *
 * Field names are the Kotlin property names. Writing leaves out nulls. Reading is forgiving where data may come from a
 * newer or older build (sync, export): a missing optional field takes the model default, an unknown enum name takes
 * the safest value (Rule → ASK, Autonomy → ASSIST, SendScope → NONE, BotKind → CUSTOM, JobRun → QUEUED…), unknown
 * action classes in a map or set are skipped, and a permissions map missing a class falls back to [DEFAULT_RULES].
 * A missing required field (an id, a name, a timestamp) or text that isn't JSON throws [BadJson].
 */
object TeamJson {

    class BadJson(msg: String) : IllegalArgumentException(msg)

    // ---------------- models ----------------

    fun encode(b: Bot): String = write(botMap(b))
    fun decodeBot(s: String): Bot = bot(obj(read(s), "bot"))

    fun encode(j: Job): String = write(jobMap(j))
    fun decodeJob(s: String): Job = job(obj(read(s), "job"))

    fun encode(s: JobStep): String = write(stepMap(s))
    fun decodeJobStep(s: String): JobStep = step(obj(read(s), "job step"))

    fun encode(r: Row): String = write(rowMap(r))
    fun decodeRow(s: String): Row = row(obj(read(s), "row"))

    fun encode(m: BotMessage): String = write(linkedMapOf("threadId" to m.threadId, "fromBot" to m.fromBot, "toBot" to m.toBot,
        "text" to m.text, "depth" to m.depth, "expectsReply" to m.expectsReply, "budgetLeft" to m.budgetLeft))
    fun decodeBotMessage(s: String): BotMessage = obj(read(s), "bot message").let { o ->
        BotMessage(o.str("threadId"), o.str("fromBot"), o.str("toBot"), o.str("text"), o.int("depth"), o.bool("expectsReply"), o.int("budgetLeft"))
    }

    fun encode(r: Routine): String = write(linkedMapOf("id" to r.id, "botId" to r.botId, "name" to r.name, "prompt" to r.prompt,
        "kind" to r.kind, "schedule" to r.schedule, "timezone" to r.timezone, "runsIn" to r.runsIn, "enabled" to r.enabled,
        "silentIfNothing" to r.silentIfNothing, "silentStreak" to r.silentStreak, "lastRunAt" to r.lastRunAt, "u" to r.u))
    fun decodeRoutine(s: String): Routine = obj(read(s), "routine").let { o ->
        Routine(o.str("id"), o.str("botId"), o.str("name"), o.str("prompt"), o.str("kind"), o.str("schedule"), o.str("timezone"),
            runsIn = o.strOr("runsIn", "phone"), enabled = o.bool("enabled"), silentIfNothing = o.boolOr("silentIfNothing", true),
            silentStreak = o.intOr("silentStreak", 0), lastRunAt = o.longOrNull("lastRunAt"), u = o.long("u"))
    }

    fun encode(s: Suggestion): String = write(linkedMapOf("id" to s.id, "botId" to s.botId, "title" to s.title, "why" to s.why,
        "task" to s.task, "topic" to s.topic, "urgency" to s.urgency, "risk" to classList(s.risk), "createdAt" to s.createdAt, "state" to s.state))
    fun decodeSuggestion(s: String): Suggestion = obj(read(s), "suggestion").let { o ->
        Suggestion(o.str("id"), o.str("botId"), o.str("title"), o.str("why"), o.str("task"), o.str("topic"), o.int("urgency"),
            classSet(o["risk"]), o.long("createdAt"), o.str("state"))
    }

    fun encode(a: Approval): String = write(linkedMapOf("id" to a.id, "botId" to a.botId, "jobId" to a.jobId, "callId" to a.callId,
        "tool" to a.tool, "actionClass" to a.actionClass.name, "title" to a.title, "detail" to a.detail, "argsRedacted" to a.argsRedacted,
        "tainted" to a.tainted, "status" to a.status, "onDeviceOnly" to a.onDeviceOnly, "createdAt" to a.createdAt,
        "resolvedAt" to a.resolvedAt, "resolvedBy" to a.resolvedBy))
    fun decodeApproval(s: String): Approval = obj(read(s), "approval").let { o ->
        // An approval's class decides how it is shown and gated: an unknown one is an error, never a guess.
        val cls = o.str("actionClass").let { n -> ActionClass.values().firstOrNull { it.name == n } ?: throw BadJson("unknown actionClass $n") }
        Approval(o.str("id"), o.str("botId"), o.strOrNull("jobId"), o.str("callId"), o.str("tool"), cls, o.str("title"), o.str("detail"),
            o.str("argsRedacted"), o.bool("tainted"), o.strOr("status", "pending"), o.boolOr("onDeviceOnly", false), o.long("createdAt"),
            o.longOrNull("resolvedAt"), o.strOrNull("resolvedBy"))
    }

    fun encode(m: MemScope): String = write(linkedMapOf("memId" to m.memId, "scope" to m.scope, "botId" to m.botId, "kind" to m.kind,
        "stated" to m.stated, "sensitivity" to m.sensitivity, "shareTags" to m.shareTags.toList(), "source" to m.source, "expires" to m.expires))
    fun decodeMemScope(s: String): MemScope = obj(read(s), "mem scope").let { o ->
        MemScope(o.long("memId"), o.str("scope"), o.strOrNull("botId"), o.strOrNull("kind"), o.boolOr("stated", true),
            o.intOr("sensitivity", 0), strings(o["shareTags"]).toSet(), o.strOr("source", "chat"), o.longOrNull("expires"))
    }

    // ---------------- team.db JSON columns ----------------

    fun avatar(a: Avatar): String = write(avatarMap(a))
    fun avatar(s: String): Avatar = avatarOf(obj(read(s), "avatar"))
    fun voice(v: Voice): String = write(voiceMap(v))
    fun voice(s: String?): Voice = if (s.isNullOrBlank()) Voice() else voiceOf(obj(read(s), "voice"))
    fun engine(e: EngineSpec): String = write(engineMap(e))
    fun engine(s: String): EngineSpec = engineOf(obj(read(s), "engine"))
    fun computer(c: ComputerSpec): String = write(computerMap(c))
    fun computer(s: String): ComputerSpec = computerOf(obj(read(s), "computer"))
    fun permissions(p: Permissions): String = write(permissionsMap(p))
    fun permissions(s: String): Permissions = permissionsOf(obj(read(s), "permissions"))
    /** A JSON array of strings (skills, tool_groups, members, routing_hints, deps, share_tags). */
    fun strings(l: Collection<String>): String = write(l.toList())
    fun strings(s: String?): List<String> = if (s.isNullOrBlank()) emptyList() else strings(read(s))
    /** A JSON object tool → rule (bots.tool_grants, bot_apps.tools). */
    fun grants(m: Map<String, Rule>): String = write(grantMap(m))
    fun grants(s: String?): Map<String, Rule> = if (s.isNullOrBlank()) emptyMap() else grantsOf(read(s))
    /** A JSON array of action-class names (suggestions.risk). */
    fun classes(c: Set<ActionClass>): String = write(classList(c))
    fun classes(s: String?): Set<ActionClass> = if (s.isNullOrBlank()) emptySet() else classSet(read(s))

    // ---------------- model ↔ map ----------------

    private fun avatarMap(a: Avatar) = linkedMapOf<String, Any?>("shape" to a.shape, "color" to a.color, "face" to a.face, "image" to a.image)
    private fun avatarOf(o: Map<String, Any?>) = Avatar(o.str("shape"), o.str("color"), o.intOrNull("face"), o.strOrNull("image"))

    private fun voiceMap(v: Voice) = linkedMapOf<String, Any?>("id" to v.id, "speed" to v.speed, "language" to v.language)
    private fun voiceOf(o: Map<String, Any?>) = Voice(o.strOr("id", "default"), (o.num("speed")?.toFloat()) ?: 1f, o.strOr("language", "auto"))

    private fun modelMap(m: ModelPolicy) = linkedMapOf<String, Any?>("lane" to m.lane, "tier" to m.tier, "pinned" to m.pinned,
        "effort" to m.effort, "zeroRetention" to m.zeroRetention)
    private fun modelOf(o: Map<String, Any?>) = ModelPolicy(o.strOr("lane", "agent"), o.strOrNull("tier"), o.strOrNull("pinned"),
        o.strOr("effort", "medium"), o.boolOr("zeroRetention", false))

    private fun engineMap(e: EngineSpec) = linkedMapOf<String, Any?>("kind" to e.kind.name, "model" to modelMap(e.model),
        "hermesUrl" to e.hermesUrl, "hermesSecretRef" to e.hermesSecretRef)
    private fun engineOf(o: Map<String, Any?>) = EngineSpec(enumOr(o["kind"], EngineKind.LOCAL),
        (o["model"] as? Map<*, *>)?.let { modelOf(it.cast()) } ?: ModelPolicy(), o.strOrNull("hermesUrl"), o.strOrNull("hermesSecretRef"))

    private fun computerMap(c: ComputerSpec) = linkedMapOf<String, Any?>("kind" to c.kind.name, "provider" to c.provider?.name,
        "askEachSession" to c.askEachSession, "idleStopMin" to c.idleStopMin)
    private fun computerOf(o: Map<String, Any?>) = ComputerSpec(enumOr(o["kind"], ComputerKind.NONE),
        (o["provider"] as? String)?.let { n -> CloudProvider.values().firstOrNull { it.name == n } }, o.boolOr("askEachSession", true), o.intOr("idleStopMin", 10))

    private fun permissionsMap(p: Permissions) = linkedMapOf<String, Any?>("autonomy" to p.autonomy.name,
        "actions" to ActionClass.values().filter { it in p.actions }.associateTo(LinkedHashMap<String, Any?>()) { it.name to p.actions.getValue(it).name },
        "spendCapMonthlyMinor" to p.spendCapMonthlyMinor, "currency" to p.currency, "sendScope" to p.sendScope.name,
        "dataScopes" to p.dataScopes.toList(), "maxStepsPerTurn" to p.maxStepsPerTurn, "maxJobMinutes" to p.maxJobMinutes, "maxHops" to p.maxHops)
    private fun permissionsOf(o: Map<String, Any?>): Permissions {
        val d = Permissions()
        val actions = LinkedHashMap(DEFAULT_RULES)
        (o["actions"] as? Map<*, *>)?.forEach { (k, v) ->
            val cls = ActionClass.values().firstOrNull { it.name == k } ?: return@forEach
            actions[cls] = enumOr(v, Rule.ASK)
        }
        return Permissions(enumOr(o["autonomy"], Autonomy.ASSIST, missing = d.autonomy), actions,
            o.longOr("spendCapMonthlyMinor", d.spendCapMonthlyMinor), o.strOr("currency", d.currency),
            enumOr(o["sendScope"], SendScope.NONE, missing = d.sendScope),
            if (o.containsKey("dataScopes")) strings(o["dataScopes"]).toSet() else d.dataScopes,
            o.intOr("maxStepsPerTurn", d.maxStepsPerTurn), o.intOr("maxJobMinutes", d.maxJobMinutes), o.intOr("maxHops", d.maxHops))
    }

    private fun grantMap(m: Map<String, Rule>) = m.entries.associateTo(LinkedHashMap<String, Any?>()) { it.key to it.value.name }
    private fun grantsOf(v: Any?): Map<String, Rule> = (v as? Map<*, *>)?.entries?.associateTo(LinkedHashMap()) { (k, r) ->
        k.toString() to enumOr(r, Rule.ASK) } ?: emptyMap()

    private fun appMap(a: BotAppGrant) = linkedMapOf<String, Any?>("appId" to a.appId, "source" to a.source, "tools" to grantMap(a.tools))
    private fun appOf(o: Map<String, Any?>) = BotAppGrant(o.str("appId"), o.str("source"), grantsOf(o["tools"]))

    private fun botMap(b: Bot) = linkedMapOf<String, Any?>(
        "id" to b.id, "kind" to b.kind.name, "name" to b.name, "label" to b.label, "domain" to b.domain,
        "avatar" to avatarMap(b.avatar), "voice" to voiceMap(b.voice), "mode" to b.mode, "soulPath" to b.soulPath, "soulVersion" to b.soulVersion,
        "skills" to b.skills, "toolGroups" to b.toolGroups.toList(), "toolGrants" to grantMap(b.toolGrants),
        "engine" to engineMap(b.engine), "computer" to computerMap(b.computer), "apps" to b.apps.map { appMap(it) },
        "permissions" to permissionsMap(b.permissions), "reportsTo" to (b.reportsTo ?: JsonNull), "parentId" to b.parentId, "members" to b.members,
        "hidden" to b.hidden, "pinned" to b.pinned, "enabled" to b.enabled, "routingHints" to b.routingHints,
        "createdAt" to b.createdAt, "u" to b.u, "email" to b.email, "portfolio" to b.portfolio)
    private fun bot(o: Map<String, Any?>) = Bot(
        id = o.str("id"), kind = enumOr(o["kind"], BotKind.CUSTOM), name = o.str("name"), label = o.strOr("label", ""), domain = o.strOrNull("domain"),
        avatar = avatarOf(obj(o["avatar"], "avatar")), voice = (o["voice"] as? Map<*, *>)?.let { voiceOf(it.cast()) } ?: Voice(),
        mode = o.strOr("mode", "personal"), soulPath = o.str("soulPath"), soulVersion = o.intOr("soulVersion", 1),
        skills = strings(o["skills"]), toolGroups = strings(o["toolGroups"]).toSet(), toolGrants = grantsOf(o["toolGrants"]),
        engine = (o["engine"] as? Map<*, *>)?.let { engineOf(it.cast()) } ?: EngineSpec(),
        computer = (o["computer"] as? Map<*, *>)?.let { computerOf(it.cast()) } ?: ComputerSpec(),
        apps = (o["apps"] as? List<*>)?.map { appOf(obj(it, "app grant")) } ?: emptyList(),
        permissions = (o["permissions"] as? Map<*, *>)?.let { permissionsOf(it.cast()) } ?: Permissions(),
        // reportsTo: absent → the model default ("manager"); an explicit null (the Manager itself) stays null
        reportsTo = if (o.containsKey("reportsTo")) o.strOrNull("reportsTo") else "manager",
        parentId = o.strOrNull("parentId"), members = strings(o["members"]),
        hidden = o.boolOr("hidden", false), pinned = o.boolOr("pinned", false), enabled = o.boolOr("enabled", true),
        routingHints = strings(o["routingHints"]), createdAt = o.long("createdAt"), u = o.long("u"),
        email = o.strOr("email", ""), portfolio = o.strOr("portfolio", ""))

    private fun jobMap(j: Job) = linkedMapOf<String, Any?>("id" to j.id, "title" to j.title, "botId" to j.botId, "due" to j.due,
        "priority" to j.priority, "status" to j.status, "run" to j.run.name, "deps" to j.deps, "parentJob" to j.parentJob,
        "source" to j.source, "brief" to j.brief, "result" to j.result, "notes" to j.notes, "confirmed" to j.confirmed,
        "retryAt" to j.retryAt, "createdAt" to j.createdAt, "doneAt" to j.doneAt, "u" to j.u)
    private fun job(o: Map<String, Any?>) = Job(o.str("id"), o.str("title"), o.strOrNull("botId"), o.strOrNull("due"),
        o.strOr("priority", "normal"), o.strOr("status", "todo"), enumOr(o["run"], JobRun.QUEUED), strings(o["deps"]),
        o.strOrNull("parentJob"), o.str("source"), o.str("brief"), o.strOrNull("result"), o.strOr("notes", ""),
        // a job of unknown provenance is never treated as confirmed
        o.boolOr("confirmed", false), o.longOrNull("retryAt"), o.long("createdAt"), o.longOrNull("doneAt"), o.long("u"))

    private fun stepMap(s: JobStep) = linkedMapOf<String, Any?>("jobId" to s.jobId, "seq" to s.seq, "label" to s.label, "state" to s.state, "at" to s.at)
    private fun step(o: Map<String, Any?>) = JobStep(o.str("jobId"), o.int("seq"), o.str("label"), o.str("state"), o.long("at"))

    private fun rowMap(r: Row) = linkedMapOf<String, Any?>("botId" to r.botId, "generation" to r.generation, "seq" to r.seq,
        "updatedSeq" to r.updatedSeq, "kind" to r.kind, "body" to r.body, "createdAt" to r.createdAt)
    private fun row(o: Map<String, Any?>) = Row(o.str("botId"), o.int("generation"), o.long("seq"), o.long("updatedSeq"),
        o.str("kind"), o.str("body"), o.long("createdAt"))

    private fun classList(c: Set<ActionClass>): List<String> = ActionClass.values().filter { it in c }.map { it.name }
    private fun classSet(v: Any?): Set<ActionClass> = strings(v).mapNotNullTo(LinkedHashSet()) { n -> ActionClass.values().firstOrNull { it.name == n } }

    // ---------------- field access ----------------

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.cast(): Map<String, Any?> = this as Map<String, Any?>

    private fun obj(v: Any?, what: String): Map<String, Any?> = (v as? Map<*, *>)?.cast() ?: throw BadJson("$what: expected a JSON object")

    private fun strings(v: Any?): List<String> = when (v) {
        null -> emptyList()
        is List<*> -> v.map { it as? String ?: throw BadJson("expected an array of strings") }
        else -> throw BadJson("expected an array of strings")
    }

    private inline fun <reified E : Enum<E>> enumOr(v: Any?, unknown: E, missing: E = unknown): E =
        if (v == null) missing else enumValues<E>().firstOrNull { it.name == v } ?: unknown

    private fun Map<String, Any?>.need(k: String): Any = this[k] ?: throw BadJson("missing \"$k\"")
    private fun Map<String, Any?>.str(k: String): String = need(k) as? String ?: throw BadJson("\"$k\" is not a string")
    private fun Map<String, Any?>.strOrNull(k: String): String? = this[k]?.let { it as? String ?: throw BadJson("\"$k\" is not a string") }
    private fun Map<String, Any?>.strOr(k: String, d: String): String = strOrNull(k) ?: d
    private fun Map<String, Any?>.num(k: String): Number? = this[k]?.let { it as? Number ?: throw BadJson("\"$k\" is not a number") }
    private fun Map<String, Any?>.long(k: String): Long = (need(k) as? Number ?: throw BadJson("\"$k\" is not a number")).toLong()
    private fun Map<String, Any?>.longOrNull(k: String): Long? = num(k)?.toLong()
    private fun Map<String, Any?>.longOr(k: String, d: Long): Long = longOrNull(k) ?: d
    private fun Map<String, Any?>.int(k: String): Int = long(k).toInt()
    private fun Map<String, Any?>.intOrNull(k: String): Int? = num(k)?.toInt()
    private fun Map<String, Any?>.intOr(k: String, d: Int): Int = intOrNull(k) ?: d
    private fun Map<String, Any?>.bool(k: String): Boolean = need(k) as? Boolean ?: throw BadJson("\"$k\" is not a boolean")
    private fun Map<String, Any?>.boolOr(k: String, d: Boolean): Boolean = this[k]?.let { it as? Boolean ?: throw BadJson("\"$k\" is not a boolean") } ?: d

    // ---------------- writer ----------------

    /** An explicit JSON null inside a map (a plain null value leaves the key out). */
    object JsonNull

    /** Writes maps (string keys), lists, strings, numbers, booleans and null. Map entries whose value is null are left out; [JsonNull] is written. */
    fun write(v: Any?): String = StringBuilder().also { put(it, v) }.toString()

    private fun put(sb: StringBuilder, v: Any?) {
        when (v) {
            null, JsonNull -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> { require(v.isFinite()) { "not a JSON number: $v" }; sb.append(v.toString()) }
            is Double -> { require(v.isFinite()) { "not a JSON number: $v" }; sb.append(v.toString()) }
            is Map<*, *> -> {
                sb.append('{'); var first = true
                for ((k, x) in v) {
                    if (x == null) continue
                    if (!first) sb.append(','); first = false
                    quote(sb, k.toString()); sb.append(':'); put(sb, x)
                }
                sb.append('}')
            }
            is Collection<*> -> { sb.append('['); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); put(sb, x) }; sb.append(']') }
            is Enum<*> -> quote(sb, v.name)
            else -> throw IllegalArgumentException("cannot write ${v::class.java.simpleName} as JSON")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c == '\b' -> sb.append("\\b")
            c == '\u000C' -> sb.append("\\f")
            c < ' ' || c == '\u2028' || c == '\u2029' -> sb.append("\\u").append(String.format("%04x", c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    // ---------------- reader ----------------

    /** Parses one JSON value: objects → LinkedHashMap, arrays → List, integers → Long, other numbers → Double. */
    fun read(s: String): Any? {
        val p = Reader(s)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != s.length) throw p.bad("trailing text")
        return v
    }

    private class Reader(val s: String) {
        var i = 0

        fun bad(msg: String) = BadJson("$msg at $i")

        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }

        fun value(depth: Int): Any? {
            if (depth > 64) throw bad("nested too deep")
            if (i >= s.length) throw bad("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw bad("unexpected '$c'")
            }
        }

        private fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw bad("unexpected token")
            i += w.length; return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++; ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw bad("expected a key")
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') throw bad("expected ':'")
                i++; ws()
                out[k] = value(depth + 1); ws()
                if (i >= s.length) throw bad("unexpected end")
                when (s[i]) { ',' -> i++; '}' -> { i++; return out }; else -> throw bad("expected ',' or '}'") }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            i++; ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws(); out += value(depth + 1); ws()
                if (i >= s.length) throw bad("unexpected end")
                when (s[i]) { ',' -> i++; ']' -> { i++; return out }; else -> throw bad("expected ',' or ']'") }
            }
        }

        private fun str(): String {
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) throw bad("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw bad("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw bad("bad \\u escape")
                                val h = s.substring(i, i + 4).toIntOrNull(16) ?: throw bad("bad \\u escape")
                                sb.append(h.toChar()); i += 4
                            }
                            else -> throw bad("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw bad("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var frac = false
            if (i < s.length && s[i] == '.') { frac = true; i++; while (i < s.length && s[i] in '0'..'9') i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                frac = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val t = s.substring(start, i)
            return (if (frac) t.toDoubleOrNull() else t.toLongOrNull() ?: t.toDoubleOrNull()) ?: throw bad("bad number '$t'")
        }
    }
}
