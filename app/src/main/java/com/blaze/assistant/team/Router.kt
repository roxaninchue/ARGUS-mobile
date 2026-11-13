package com.blaze.assistant.team

import com.blaze.assistant.BrandName
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * P1-TM-06: Routing v1 (06 AT-30 / AT-38, A-team `app.js` interpret … applyActions, `chat.js` CHAT.home / mention,
 * `agentSend` CMDISH). Pure: the on-device model and the gateway come in through [Lanes], the team through [TeamStore],
 * [JobStore] and [TeamEdits], so RoutingTest runs it on the JVM on a real team.db.
 *
 * What a message from the Team composer ("Tell the team…") or a thread does, in this order ([route]):
 *  1. `$ cmd` runs in the shell (Alpine until P7) and its output is the answer.
 *  2. `@Name rest` (or a bot's name at the start, "Atlas, find flights…", "ask Atlas to …") goes to that bot: rest =
 *     a job for it plus a HandoffCard ("Atlas is on it"); no rest = open the bot. `@Argus` is the Manager: plain chat.
 *  3. Exact commands (A-team structuredParse / cardParse / agentList, "add job …") apply at once with a receipt.
 *  4. Command-ish words (A-team CMDISH) are interpreted: exact parse → Needle on the phone → the gateway (online and
 *     signed in, Needle found nothing) → quick rules; the result shows on the "Here's what I'll do" sheet, or applies
 *     at once when the user turned the preview off. Removing a bot or a job always shows the sheet.
 *  5. Anything else is conversation: ARGUS (the Manager) answers.
 * Inside a thread (the ARGUS chat or a bot's), "add job …" is a job for that thread's bot (or the bot it names), and
 * other command-ish words are tried as commands only while the cloud can't answer (A-team agentSend): online the bot
 * has the job tools itself.
 */
object TeamCommands {
    /** A-team NEEDLE_TOOLS (app.js), the five commands Needle 3 was tuned on, as the runner's tools.json. */
    const val NEEDLE_TOOLS = """[
{"name":"add_agent","triggers":["\\b(new|add|hire|create|onboard)\\b.*\\bagent\\b"],"description":"Create a new agent on the team","parameters":{"type":"object","properties":{"name":{"type":"string","description":"agent name"},"email":{"type":"string","description":"agent email address"},"role":{"type":"string","description":"role, portfolio or speciality"}},"required":["name"]}},
{"name":"add_task","triggers":["\\b(task|job|give|assign|needs? to|has to|should|remind)\\b"],"description":"Add a task or job for an agent","parameters":{"type":"object","properties":{"agent":{"type":"string","description":"agent name"},"title":{"type":"string","description":"what the task is"},"due":{"type":"string","enum":["today","tomorrow","this_week","next_week","later","none"],"description":"when it is due"},"priority":{"type":"string","enum":["low","normal","high"],"description":"urgency"}},"required":["title"]}},
{"name":"complete_task","triggers":["\\b(done|finished|finish|completed|complete|mark|closed)\\b"],"description":"Mark a task or job as done or completed","parameters":{"type":"object","properties":{"title":{"type":"string","description":"the task"},"agent":{"type":"string","description":"agent name"}},"required":["title"]}},
{"name":"reschedule_task","triggers":["\\b(move|reschedule|push|postpone|reassign|shift)\\b"],"description":"Move a task to another day or reassign it to another agent","parameters":{"type":"object","properties":{"title":{"type":"string","description":"the task"},"due":{"type":"string","enum":["today","tomorrow","this_week","next_week","later","none"]},"agent":{"type":"string","description":"new agent name"}},"required":["title"]}},
{"name":"remove","triggers":["\\b(delete|remove|fire|drop|cancel)\\b"],"description":"Delete or remove an agent or a task","parameters":{"type":"object","properties":{"kind":{"type":"string","enum":["agent","task"]},"name":{"type":"string","description":"agent name or task title"}},"required":["kind","name"]}}
]"""
    val NAMES = setOf("add_agent", "add_task", "complete_task", "reschedule_task", "remove")

    /** A-team Needle system line. */
    fun needleSystem(today: LocalDate) = "You manage a team of AI agents and their tasks (jobs). Today is " +
        today.dayOfWeek.name.lowercase(Locale.ROOT) + "."

    /** A-team CMDISH: messages that look like a command. */
    val CMDISH = Regex("""^(please\s+)?(add|new|create|give|assign|schedule|move|reschedule|push|postpone|mark|done|complete|finish(ed)?|delete|remove|cancel|set|change|update|rename|job|task|to-?do)\b""", RegexOption.IGNORE_CASE)

    /** "add job send the deck Friday", "job: call Zamtel" → the job words (A-team home / agentSend). */
    private val JOB1 = Regex("""^(?:please\s+)?(?:add|new|create)\s+(?:an?\s+)?(?:job|task|to-?do)s?\s*[:\-]?\s*(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val JOB2 = Regex("""^(?:job|task|to-?do)\s*[:\-]\s*(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    fun jobWords(text: String): String? = (JOB1.find(text.trim()) ?: JOB2.find(text.trim()))?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** `$ cmd` (A-team agentSend → WS.chatRun). */
    fun shell(text: String): String? = Regex("""^\$\s*(\S[\s\S]*)$""").find(text.trim())?.groupValues?.get(1)?.trim()

    /**
     * A cheap check (no team.db read) for the ARGUS chat: could [text] be for the router at all? `$ cmd`, an @mention,
     * "ask X to …" / "X, …", "add job …" or command-ish words. Everything else goes to ARGUS untouched, with no detour.
     */
    fun maybeTeam(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 2000) return false
        return t.startsWith("$") || t.startsWith("@") || CMDISH.containsMatchIn(t) || jobWords(t) != null ||
            Regex("""^(?:please\s+)?(?:ask|tell|have|get)\s+@?[\w'.-]+\s""", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
            Regex("""^[\w'.-]+\s*[,:]\s*\S""").containsMatchIn(t)
    }

    private val EMAIL = Regex("""[\w.+-]+@[\w.-]+\.\w+""")
    fun email(s: String): String = EMAIL.find(s)?.value.orEmpty()

    fun urgent(text: String) = Regex("""\b(urgent|asap|high priority)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** A-team cleanTitle: due words out, first letter up. */
    fun cleanTitle(s: String?): String {
        var t = (s ?: "").replace(Regex("""\b(due|by|on)?\s*(today|tonight|tomorrow|next week|this week|(mon|tues|wednes|thurs|fri|satur|sun)day)\b""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""\s{2,}"""), " ").replace(Regex("""^[\s,.-]+|[\s,.-]+$"""), "")
        return t.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

    // ---------------- due words (A-team dueFromWord / dueFromText on DueWords) ----------------

    /** A Needle / gateway due word (today, tomorrow, this_week, next_week, later, none, a weekday, a date) → jobs.due. */
    fun dueWord(w: String?, today: LocalDate): String? {
        val t = w?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when (t) {
            "none", "no date", "someday" -> null
            "now" -> today.toString()
            "this_week", "this week" -> thisWeek(today).toString()
            "next_week", "next week" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toString()
            "later" -> today.plusDays(14).toString()
            else -> DueWords.parse(t, today)
        }
    }

    /** A-team this_week: Friday, or tomorrow on a Friday or Saturday. */
    private fun thisWeek(today: LocalDate): LocalDate = when (today.dayOfWeek) {
        DayOfWeek.FRIDAY, DayOfWeek.SATURDAY -> today.plusDays(1)
        else -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
    }

    /** The due a sentence names (weekday, "in 3 days", a date, today / tomorrow / next week), or null when it names none. */
    data class Due(val value: String?)

    fun dueIn(text: String, today: LocalDate): Due? {
        val s = text.lowercase(Locale.ROOT)
        Regex("""\b(\d{4}-\d{2}-\d{2})\b""").find(s)?.let { return Due(DueWords.parse(it.groupValues[1], today)) }
        Regex("""\b(\d{1,2})[/.](\d{1,2})(?:[/.](\d{2,4}))?\b""").find(s)?.let { m ->
            return Due(DueWords.parse(m.groupValues[1] + "/" + m.groupValues[2] + (m.groupValues[3].takeIf { it.isNotEmpty() }?.let { "/$it" } ?: ""), today))
        }
        Regex("""\bin (\d+) days?\b""").find(s)?.let { return Due(today.plusDays(it.groupValues[1].toLong()).toString()) }
        if (Regex("""\btoday\b|\btonight\b""").containsMatchIn(s)) return Due(today.toString())
        if (Regex("""\btomorrow\b|\btmrw\b""").containsMatchIn(s)) return Due(today.plusDays(1).toString())
        if (Regex("""\bnext week\b""").containsMatchIn(s)) return Due(dueWord("next_week", today))
        if (Regex("""\bthis week\b""").containsMatchIn(s)) return Due(dueWord("this_week", today))
        Regex("""\b(?:on |by |next |this )?(mon|tue|wed|thu|fri|sat|sun)[a-z]*\b""").find(s)?.let { return Due(DueWords.parse(it.groupValues[1], today)) }
        return null
    }

    fun dueLabel(due: String?, today: LocalDate): String = DueWords.label(due, today) ?: "No date"

    // ---------------- exact parsers (A-team, kept as written) ----------------

    data class Call(val name: String, val args: Map<String, String>) {
        fun a(k: String): String = args[k]?.trim().orEmpty()
    }

    private val FIELD = linkedMapOf("name" to "name", "agent" to "name", "bot" to "name", "email" to "email", "mail" to "email", "e-mail" to "email",
        "role" to "role", "title" to "role", "position" to "role", "portfolio" to "portfolio", "task" to "task", "job" to "task", "todo" to "task",
        "to-do" to "task", "due" to "due", "when" to "due", "deadline" to "due", "date" to "due", "priority" to "priority", "urgency" to "priority")

    /** A-team structuredParse: "Name: Nova, email: n@x.ai, role: research, task: draft deck, due: tomorrow". */
    fun structured(text: String, known: (String) -> Boolean): List<Call>? {
        val keyRe = Regex("(?:^|[\\n,;|]\\s*|\\s+)(" + FIELD.keys.joinToString("|") { Regex.escape(it) } + ")\\s*[:=]\\s*", RegexOption.IGNORE_CASE)
        val hits = keyRe.findAll(text).toList()
        if (hits.size < 2) return null
        val recs = ArrayList<MutableMap<String, Any>>(); var cur: MutableMap<String, Any>? = null
        hits.forEachIndexed { i, h ->
            val key = FIELD.getValue(h.groupValues[1].lowercase(Locale.ROOT))
            val end = if (i + 1 < hits.size) hits[i + 1].range.first else text.length
            val v = text.substring(h.range.last + 1, end).replace(Regex("""[\s,;|]+$"""), "").trim()
            if (v.isEmpty()) return@forEachIndexed
            val c = cur
            if (c == null || (key == "name" && c.containsKey("name")) || (key == "task" && c.containsKey("task") && !c.containsKey("name"))) {
                val n = LinkedHashMap<String, Any>(); recs += n; cur = n
            }
            val r = cur!!
            if (key == "task" && r.containsKey("task")) {
                @Suppress("UNCHECKED_CAST") val ts = (r["tasks"] as? MutableList<String>) ?: mutableListOf(r["task"] as String).also { r["tasks"] = it }
                ts += v
            }
            r[key] = v
        }
        val calls = ArrayList<Call>()
        for (r in recs) {
            fun g(k: String) = (r[k] as? String).orEmpty()
            val name = g("name")
            if (name.isNotEmpty() && (g("email").isNotEmpty() || g("role").isNotEmpty() || g("portfolio").isNotEmpty() || !known(name)))
                calls += Call("add_agent", mapOf("name" to name, "email" to g("email"), "role" to g("role"), "portfolio" to g("portfolio")))
            @Suppress("UNCHECKED_CAST") val tasks = (r["tasks"] as? List<String>) ?: listOfNotNull(g("task").takeIf { it.isNotEmpty() })
            val p = g("priority")
            for (t in tasks) calls += Call("add_task", mapOf("agent" to name, "title" to t, "due" to g("due"),
                "priority" to if (Regex("high|urgent|asap", RegexOption.IGNORE_CASE).containsMatchIn(p)) "high" else if (Regex("low", RegexOption.IGNORE_CASE).containsMatchIn(p)) "low" else ""))
        }
        return calls.takeIf { it.isNotEmpty() }
    }

    /** A-team cardParse: "Nova, nova@hitch.io" or "Nova nova@hitch.io research". */
    fun card(text: String): List<Call>? {
        val t = text.trim()
        val e = EMAIL.find(t) ?: return null
        val before = t.substring(0, e.range.first).replace(Regex("""[\s,;:\-–(]+$"""), "")
            .replace(Regex("""(?:\s*\b(?:e-?mail(?:\s+address)?|mail|address|is|at|=|:)\s*)+$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""['’]s$""", RegexOption.IGNORE_CASE), "").trim()
        val after = t.substring(e.range.last + 1).replace(Regex("""^[\s,;:\-–)]+"""), "").trim()
        if (before.isEmpty() || before.split(Regex("\\s+")).size > 3 || Regex("""\b(add|give|task|job|move|delete|remove|mark|send)\b""", RegexOption.IGNORE_CASE).containsMatchIn(before)) return null
        if (after.isNotEmpty() && after.split(Regex("\\s+")).size > 6) return null
        return listOf(Call("add_agent", mapOf("name" to before.replace(Regex("""^(?:agent|bot|name)\s*:?\s*""", RegexOption.IGNORE_CASE), ""), "email" to e.value,
            "role" to after.replace(Regex("""^(?:role|as|for|who does|who handles)\s*:?\s*""", RegexOption.IGNORE_CASE), ""))))
    }

    /** A-team agentList: "add Ann, Bob (bob@x.ai, sales), Cy and Dee" → one add_agent each, never losing a name. */
    fun agentList(text: String): List<Call>? {
        val m = Regex("""^\s*(?:please\s+)?(?:add|create|new|hire|onboard)\s+(?:(?:these|the following|\d+|some|a few|more|new)\s+)*(?:new\s+)?(?:agents?|bots?|names?|people|members?|team members?)?\s*(?:called|named)?\s*:?\s*([\s\S]+)$""", RegexOption.IGNORE_CASE).find(text)
        if (m == null || Regex("""\b(task|job|todo|to-do|due|remind|deadline)s?\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        var body = m.groupValues[1].replace(Regex("""\s+to (?:the )?team\b.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        val groups = ArrayList<String>()
        body = Regex("""\(([^)]*)\)""").replace(body) { groups += it.groupValues[1]; " \u0001${groups.size - 1} " }
        val toks = body.split(Regex("""\s*(?:,|;|\n|\band\b|&)\s*""", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
        fun cap(w: String) = Regex("""(^|\s)\S""").replace(w) { it.value.uppercase(Locale.ROOT) }
        if (toks.size >= 2 && groups.isEmpty() && toks.all { !it.contains('@') && it.split(Regex("\\s+")).size <= 2 && !Regex("""\b(as|who|for|does|handles)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) })
            return toks.map { Call("add_agent", mapOf("name" to cap(it), "email" to "", "role" to "")) }
        val out = ArrayList<MutableMap<String, String>>(); var cur: MutableMap<String, String>? = null
        for (tk0 in toks) {
            var tk = tk0; var extra = ""
            Regex("""\u0001(\d+)""").find(tk)?.let { g -> extra = groups[g.groupValues[1].toInt()]; tk = tk.replace(Regex("""\u0001\d+"""), "").trim() }
            val em = email(tk)
            val rest = (if (em.isEmpty()) tk else tk.replace(em, "")).replace(Regex("""^(?:email|role)\s*:?\s*""", RegexOption.IGNORE_CASE), "").trim()
            val looksName = rest.isNotEmpty() && Regex("""^[A-Z][\w'.-]*(?:\s+[A-Z][\w'.-]*){0,2}(?:\s+(?:who|as|for|-|–)\s+.*)?$""").matches(rest)
            if (looksName || cur == null) {
                val nm = rest.split(Regex("""\s+(?:who|as|for|-|–)\s+"""))[0]
                val role = Regex("""\s+(?:who does|who handles|who is|as|for|-|–)\s+(.+)$""").find(rest)?.groupValues?.get(1).orEmpty()
                cur = mutableMapOf("name" to nm.ifEmpty { rest }, "email" to em, "role" to role); out += cur
            } else {
                if (em.isNotEmpty()) cur["email"] = em
                if (rest.isNotEmpty()) cur["role"] = if (cur["role"].isNullOrEmpty()) rest else cur["role"] + ", " + rest
            }
            val c = cur
            if (extra.isNotEmpty()) extra.split(Regex("""\s*,\s*""")).forEach { x ->
                val e2 = email(x)
                if (e2.isNotEmpty()) c["email"] = e2 else if (x.isNotEmpty()) c["role"] = if (c["role"].isNullOrEmpty()) x else c["role"] + ", " + x
            }
        }
        val ok = out.filter { !it["name"].isNullOrEmpty() && it["name"]!!.split(Regex("\\s+")).size <= 4 }
        return if (ok.size >= 2) ok.map { Call("add_agent", it.toMap()) } else null
    }

    /** A-team clauses: one long instruction → clauses, so each gets the model's full attention. */
    fun clauses(text: String): List<String> =
        text.split(Regex("""\n+|;\s*|\.\s+(?=[A-Z])|,?\s+(?:and then|then|also)\s+""", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.length > 2 }

    /** A-team ruleParse: the last resort. */
    fun rules(t: String, known: (String) -> Boolean): List<Call> {
        val s = t.trim()
        Regex("""^(?:add|new|create|hire)\s+(?:an?\s+)?(?:agent|bot)\s+(?:called\s+|named\s+)?([^,;@]+?)\s*(?:[,;]|$)(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(s)?.let { m ->
            val em = email(m.groupValues[2])
            val role = (if (em.isEmpty()) m.groupValues[2] else m.groupValues[2].replace(em, "")).split(Regex("""\band\b"""))[0].replace(Regex("[,;]"), " ").trim()
            return listOf(Call("add_agent", mapOf("name" to m.groupValues[1].trim(), "email" to em, "role" to role)))
        }
        Regex("""^([\w .'-]+?)\s*[:\-–]\s*(.+)$""", RegexOption.DOT_MATCHES_ALL).find(s)?.let { m ->
            if (known(m.groupValues[1])) return listOf(Call("add_task", mapOf("agent" to m.groupValues[1], "title" to m.groupValues[2])))
        }
        Regex("""^(?:done|finish(?:ed)?|complete(?:d)?|mark)\s+(.+?)(?:\s+(?:as\s+)?done)?$""", RegexOption.IGNORE_CASE).find(s)?.let { return listOf(Call("complete_task", mapOf("title" to it.groupValues[1]))) }
        return emptyList()
    }

    /** The gateway's prompt (A-team viaGateway). */
    fun gatewaySystem(bots: List<String>, openJobs: List<String>, today: LocalDate) =
        "You turn a manager's instruction into tool calls for an agent-management app. Tools (JSON schema): " + NEEDLE_TOOLS.replace("\n", "") +
            "\nExisting agents: " + (bots.joinToString(", ").ifEmpty { "none" }) + ". Open tasks: " + (openJobs.take(40).joinToString("; ").ifEmpty { "none" }) +
            ". Today is " + today + ". Rules: add_agent with an EXISTING name means update that agent (e.g. setting their email or role). One call per person or task. Messy notes count as instructions." +
            "\nExamples:\n\"Lukas's email is l@x.io\" -> {\"calls\":[{\"name\":\"add_agent\",\"arguments\":{\"name\":\"Lukas\",\"email\":\"l@x.io\"}}]}" +
            "\n\"Nova, nova@hitch.io\" -> {\"calls\":[{\"name\":\"add_agent\",\"arguments\":{\"name\":\"Nova\",\"email\":\"nova@hitch.io\"}}]}" +
            "\n\"Name: Zed, task: call Zamtel, due: tomorrow\" -> {\"calls\":[{\"name\":\"add_task\",\"arguments\":{\"agent\":\"Zed\",\"title\":\"call Zamtel\",\"due\":\"tomorrow\"}}]}" +
            "\nReply with ONLY the JSON object, no prose, no code fences. Use {\"calls\":[]} only if nothing in the text is an instruction."

    /** The gateway's reply → calls (A-team viaGateway's tolerant parse: think tags, fences, bare arrays, OpenAI shape). */
    fun parseCalls(raw: String?): List<Call> {
        val body = (raw ?: "").replace(Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE), "").replace(Regex("""```(?:json)?""", RegexOption.IGNORE_CASE), "")
        fun tryRead(x: String): Any? = try { TeamJson.read(x) } catch (_: Exception) { null }
        val obj = Regex("""\{[\s\S]*\}""").find(body); val arr = Regex("""\[[\s\S]*\]""").find(body)
        // a bare array of calls ([{…}, {…}]) is read as the array, not as its first object
        val first = if (arr != null && (obj == null || arr.range.first < obj.range.first)) listOf(arr, obj) else listOf(obj, arr)
        val j: Any? = first.firstNotNullOfOrNull { m -> m?.let { tryRead(it.value) } }
        @Suppress("UNCHECKED_CAST")
        val list: List<Any?> = when (j) {
            is List<*> -> j
            is Map<*, *> -> (j["calls"] as? List<Any?>) ?: (j["tool_calls"] as? List<Any?>) ?: (if (j["name"] != null) listOf(j) else emptyList())
            else -> emptyList()
        }
        return list.mapNotNull { callOf(it) }
    }

    /** The Needle runner's /complete reply → team calls: its `function_calls`, else its `suppressed_calls` (A-team needleCalls). */
    fun needleReply(reply: String): List<Call> {
        @Suppress("UNCHECKED_CAST")
        val o = (try { TeamJson.read(reply) } catch (_: Exception) { null } as? Map<String, Any?>) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val fc = (o["function_calls"] as? List<Any?>).orEmpty().ifEmpty { (o["suppressed_calls"] as? List<Any?>).orEmpty() }
        return fc.mapNotNull { callOf(it) }.filter { it.name in NAMES }
    }

    /** One call in any of the shapes Needle, the gateway or OpenAI use ({name, arguments}, {function: {name, arguments}}). */
    @Suppress("UNCHECKED_CAST")
    fun callOf(o: Any?): Call? {
        val m = o as? Map<String, Any?> ?: return null
        val f = (m["function"] as? Map<String, Any?>) ?: m
        val name = (f["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val a = when (val x = f["arguments"]) {
            is Map<*, *> -> x as Map<String, Any?>
            is String -> (try { TeamJson.read(x) } catch (_: Exception) { null } as? Map<String, Any?>) ?: emptyMap()
            else -> emptyMap()
        }
        return Call(name, a.mapNotNull { (k, v) -> v?.let { k to (if (v is Double && v % 1.0 == 0.0) v.toLong().toString() else v.toString()) } }.toMap())
    }
}

/**
 * P1-TM-06: the router over one team. [today] and [clock] are passed in so RoutingTest controls them; [kick] ticks the
 * jobs queue after a change; [post] writes a row into a bot's thread (the HandoffCard); [changed] tells the Team screen.
 */
class Router(
    private val team: TeamStore,
    private val jobs: JobStore,
    private val edits: TeamEdits = TeamEdits(team),
    private val queue: () -> JobQueue? = { null },
    private val today: () -> LocalDate = { LocalDate.now() },
    private val kick: () -> Unit = {},
    private val post: (botId: String, kind: String, body: String) -> Unit = { _, _, _ -> },
    private val changed: (botId: String) -> Unit = {},
) {
    /** Where the message was typed. */
    sealed class Where {
        /** The Team page's composer ("Tell the team…", A-team's home box). */
        object Team : Where()
        /** A thread: ARGUS's chat ([BotRules.MANAGER_ID]) or a bot's. */
        data class Thread(val botId: String) : Where()
    }

    /** What the message turned out to be. */
    sealed class Route {
        data class Shell(val cmd: String) : Route()
        /** To a bot: [task] becomes its job (+ HandoffCard); blank = just open its thread. */
        data class Delegate(val bot: Bot, val task: String) : Route()
        /** Exact commands: apply now with a receipt. */
        data class Apply(val plan: Plan) : Route()
        /** Command-ish: [interpret] it, then preview or apply. [direct] = in a thread (A-team applies there with no sheet). */
        data class Command(val text: String, val direct: Boolean, val botId: String?) : Route()
        /** Conversation: ARGUS (or the thread's bot) answers. [text] may have lost an `@Argus` prefix. */
        data class Chat(val text: String) : Route()
    }

    /** One change a command makes. [label] is plain text for the plan sheet and the receipt; [icon] a Lucide name. */
    sealed class Act {
        abstract val label: String
        abstract val icon: String
        /** Removing something always shows the plan sheet first, even with the preview turned off. */
        open val risky: Boolean get() = false

        data class AddBot(val name: String, val email: String, val role: String, val portfolio: String) : Act() {
            override val label get() = "New bot $name" + (if (email.isNotEmpty()) " \u00b7 $email" else "") + (if (role.isNotEmpty()) " \u00b7 $role" else "")
            override val icon get() = "user-plus"
        }
        data class UpdateBot(val id: String, val name: String, val changes: Map<String, String>) : Act() {
            override val label get() = "Update $name \u00b7 " + changes.entries.joinToString(" \u00b7 ") { "${it.key} \u2192 ${it.value}" }
            override val icon get() = "pencil"
        }
        data class AddJob(val title: String, val botId: String?, val botName: String, val createBot: Boolean, val due: String?, val priority: String,
                          val dueText: String) : Act() {
            override val label get() = "Job $title \u2192 " + (if (botName.isEmpty()) "unassigned" else botName + if (createBot) " (new bot)" else "") + " \u00b7 $dueText"
            override val icon get() = "plus"
        }
        data class Complete(val jobId: String, val title: String) : Act() {
            override val label get() = "Complete $title"
            override val icon get() = "check"
        }
        data class Move(val jobId: String, val title: String, val due: String?, val dueSet: Boolean, val dueText: String, val botId: String?, val botName: String?) : Act() {
            override val label get() = "Move $title" + (if (dueSet) " to $dueText" else "") + (botName?.let { " \u2192 $it" } ?: "")
            override val icon get() = "calendar-clock"
        }
        data class DeleteBot(val id: String, val name: String) : Act() {
            override val label get() = "Remove bot $name"
            override val icon get() = "trash-2"
            override val risky get() = true
        }
        data class DeleteJob(val id: String, val title: String) : Act() {
            override val label get() = "Delete job $title"
            override val icon get() = "trash-2"
            override val risky get() = true
        }
        data class Miss(val what: String, val kind: String = "job") : Act() {
            override val label get() = if (kind == "bot") "No bot named \u201c$what\u201d" else "No job matches \u201c$what\u201d"
            override val icon get() = "triangle-alert"
        }
    }

    /** [via] = where the reading came from: [VIA_EXACT], [VIA_PHONE] (+ ms), [VIA_CLOUD], [VIA_RULES]. */
    data class Plan(val text: String, val actions: List<Act>, val via: String) {
        val real: List<Act> get() = actions.filter { it !is Act.Miss }
        /** Every reading found its target. */
        val clean: Boolean get() = actions.isNotEmpty() && actions.none { it is Act.Miss }
        val risky: Boolean get() = actions.any { it.risky }
    }

    /** The on-device model and the gateway. A lane that can't run (no model, offline, signed out) returns null. */
    interface Lanes {
        /** Needle 3 on the phone with [TeamCommands.NEEDLE_TOOLS] for one clause; null = not available. */
        suspend fun needle(clause: String): Needle?
        /** True when the gateway may be asked (online and signed in). */
        fun cloud(): Boolean
        /** The gateway's raw reply to [system] + [text]; null = it failed. */
        suspend fun gateway(system: String, text: String): String?
    }
    data class Needle(val calls: List<TeamCommands.Call>, val ms: Long)

    /** No lanes: exact parse and quick rules only (the JVM, or a phone with no model and no network). */
    object NoLanes : Lanes {
        override suspend fun needle(clause: String): Needle? = null
        override fun cloud() = false
        override suspend fun gateway(system: String, text: String): String? = null
    }

    companion object {
        const val VIA_EXACT = "Exact"
        const val VIA_PHONE = "On this phone"
        const val VIA_CLOUD = "Cloud"
        const val VIA_RULES = "Quick rules"
        private val ASK = Regex("""^(?:please\s+)?(?:ask|tell|have|get)\s+@?([\w'.-]+)\s+(?:to\s+)?(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val LEAD = Regex("""^@?([\w'.-]+)\s*[,:]\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)
        private val MENTION = Regex("""^@([^\s,:]+)[\s,:]*([\s\S]*)$""")
    }

    // ---------------- finding bots and jobs (A-team findAgent / findTask / mentionedAgent) ----------------

    private fun bots(): List<Bot> = team.roster()

    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    /** A-team findAgent: exact name or email, first name or mailbox, contains, then up to 2 typos. */
    fun findBot(name: String?): Bot? {
        val q = name?.trim()?.removePrefix("@")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        var best: Bot? = null; var bs = 99.0
        for (b in bots()) {
            val n = b.name.lowercase(Locale.ROOT); val e = b.email.lowercase(Locale.ROOT)
            val s = when {
                n == q || (e.isNotEmpty() && e == q) -> 0.0
                n.split(Regex("\\s+"))[0] == q || (e.isNotEmpty() && e.substringBefore('@') == q) -> 0.5
                n.contains(q) || q.contains(n) -> 1.0
                // A-team allows 2 typos; a short name only 1, so "Vega" never lands on "Nova"
                else -> lev(n, q).let { if (it <= (if (minOf(n.length, q.length) <= 4) 1 else 2)) 1.5 + it else 99.0 }
            }
            if (s < bs) { bs = s; best = b }
        }
        return if (bs < 5) best else null
    }

    private fun known(name: String) = findBot(name) != null

    /** A bot named exactly (whole name or first name), never by a typo: for routing a message by name. */
    fun named(word: String?): Bot? {
        val q = word?.trim()?.removePrefix("@")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val all = bots()
        return all.firstOrNull { it.name.lowercase(Locale.ROOT) == q } ?: all.firstOrNull { it.name.lowercase(Locale.ROOT).split(Regex("\\s+"))[0] == q }
    }

    /** A-team mentionedAgent: the bot whose first name shows earliest in [text] (never the Manager: "Argus" is the app). */
    fun mentioned(text: String): Bot? {
        val low = " " + text.lowercase(Locale.ROOT).replace(Regex("""[^\w@. ]"""), " ") + " "
        var best: Bot? = null; var at = Int.MAX_VALUE
        for (b in bots()) {
            if (BotRules.isManager(b)) continue
            val first = b.name.lowercase(Locale.ROOT).split(Regex("\\s+"))[0]
            val i = low.indexOf(" $first ")
            if (i in 0 until at) { at = i; best = b }
        }
        return best
    }

    /** A-team findTask: open jobs first, word overlap, ≥ 10 points. */
    fun findJob(title: String?, bot: Bot? = null): Job? {
        val q = title?.lowercase(Locale.ROOT)?.replace(Regex("""^(the|my|a)\s+"""), "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val all = jobs.all().filter { it.run != JobRun.CANCELLED }
        var pool = all.filter { bot == null || it.botId == bot.id }
        if (pool.isEmpty()) pool = all
        val stop = hashSetOf("the", "my", "job", "task", "done", "mark", "as", "for", "to", "is", "was", "has", "finished", "completed", "complete", "delete",
            "remove", "move", "next", "week", "today", "tomorrow", "and", "of", "on", "a", "an", "it")
        bots().forEach { stop += it.name.lowercase(Locale.ROOT).split(Regex("\\s+"))[0] }
        val qw = q.split(Regex("\\W+")).filter { it.length > 1 && it !in stop }.toSet()
        var best: Job? = null; var bs = 0
        for (j in pool) {
            val n = j.title.lowercase(Locale.ROOT)
            var s = if (n == q) 100 else if (n.contains(q) || q.contains(n)) 50 else 0
            n.split(Regex("\\W+")).filter { it.length > 1 }.forEach { if (it in qw) s += 10 }
            if (j.run != JobRun.DONE) s += 1
            if (s > bs) { bs = s; best = j }
        }
        return if (bs >= 10) best else null
    }

    // ---------------- routing ----------------

    /** What [text] typed at [where] is (see the file comment). Reads the roster; no change is made. */
    fun route(text: String, where: Where): Route {
        val t = text.trim()
        TeamCommands.shell(t)?.let { return Route.Shell(it) }
        val thread = (where as? Where.Thread)?.botId
        // @Name rest (A-team CHAT.mention: whole name, first name, then a prefix)
        MENTION.find(t)?.let { m ->
            val n = m.groupValues[1].lowercase(Locale.ROOT)
            val all = bots()
            val b = all.firstOrNull { it.name.lowercase(Locale.ROOT) == n } ?: all.firstOrNull { it.name.lowercase(Locale.ROOT).split(Regex("\\s+"))[0] == n }
                ?: all.firstOrNull { it.name.lowercase(Locale.ROOT).startsWith(n) }
            if (b != null) {
                val rest = m.groupValues[2].trim()
                return when {
                    BotRules.isManager(b) -> if (rest.isEmpty()) Route.Delegate(b, "") else Route.Chat(rest)
                    thread == b.id -> if (rest.isEmpty()) Route.Chat(t) else route(rest, where)
                    else -> Route.Delegate(b, rest)
                }
            }
        }
        // by name: "ask Atlas to …", "Atlas, …", "Atlas: …" (an exact bot name only, never a contact card or a form)
        if (exact(t) == null) (ASK.find(t) ?: LEAD.find(t))?.let { m ->
            val b = named(m.groupValues[1])
            val rest = m.groupValues[2].trim()
            if (b != null && !BotRules.isManager(b) && thread != b.id && rest.isNotEmpty()) return Route.Delegate(b, rest)
        }
        if (where is Where.Team) {
            // A-team CHAT.home: exact first, then "add job …", then command-ish words
            exact(t)?.let { calls -> val p = Plan(t, actions(calls, t), VIA_EXACT); if (p.clean) return if (p.risky) Route.Command(t, false, null) else Route.Apply(p) }
            TeamCommands.jobWords(t)?.let { return Route.Apply(Plan(t, listOf(jobAct(it, mentioned(it), t)), VIA_EXACT)) }
            if (TeamCommands.CMDISH.containsMatchIn(t)) return Route.Command(t, direct = false, botId = null)
            return Route.Chat(t)
        }
        // A-team agentSend: "add job …" in a thread is a job for that thread's bot (or the bot it names)
        TeamCommands.jobWords(t)?.let { words ->
            val who = mentioned(words) ?: thread?.let { team.get(it) }
            return Route.Apply(Plan(t, listOf(jobAct(words, who, t)), VIA_EXACT))
        }
        if (TeamCommands.CMDISH.containsMatchIn(t)) return Route.Command(t, direct = true, botId = thread)
        return Route.Chat(t)
    }

    /** A job for [who] from the words after "add job" (A-team CHAT.home jm). */
    private fun jobAct(words: String, who: Bot?, whole: String): Act.AddJob {
        var title = words
        if (who != null) title = title.replace(Regex("""\b(for|to)\s+@?""" + Regex.escape(who.name.split(Regex("\\s+"))[0]) + """\b""", RegexOption.IGNORE_CASE), "")
        val due = TeamCommands.dueIn(words, today())?.value
        return Act.AddJob(TeamCommands.cleanTitle(title), who?.id, who?.name.orEmpty(), false, due,
            if (TeamCommands.urgent(whole)) "high" else "normal", TeamCommands.dueLabel(due, today()))
    }

    private fun exact(text: String): List<TeamCommands.Call>? = TeamCommands.structured(text, ::known) ?: TeamCommands.card(text) ?: TeamCommands.agentList(text)

    /**
     * A-team interpret: exact parse → Needle on the phone (clause by clause) → the gateway when Needle found nothing and
     * it may be asked → quick rules. In a thread ([botId]), a job with no bot goes to that bot (A-team agentSend).
     */
    suspend fun interpret(text: String, lanes: Lanes, botId: String? = null): Plan {
        var calls: List<TeamCommands.Call> = emptyList(); var via = ""
        val ex = exact(text)
        if (ex != null) { calls = ex; via = VIA_EXACT }
        else {
            val out = ArrayList<TeamCommands.Call>(); var ms = 0L; var ran = false
            for (part in TeamCommands.clauses(text).ifEmpty { listOf(text.trim()) }) {
                val list = TeamCommands.agentList(part)
                if (list != null) { out += list; continue }
                val r = try { lanes.needle(part) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null } ?: continue
                ran = true; ms += r.ms; out += r.calls.filter { it.name in TeamCommands.NAMES }
            }
            if (out.isNotEmpty()) { calls = out; via = if (ran) "$VIA_PHONE \u00b7 $ms ms" else VIA_EXACT }
            if (calls.isEmpty() && lanes.cloud()) {
                val sys = TeamCommands.gatewaySystem(bots().map { it.name }, jobs.open().map { it.title }, today())
                val raw = try { lanes.gateway(sys, text) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                calls = TeamCommands.parseCalls(raw).filter { it.name in TeamCommands.NAMES }
                if (calls.isNotEmpty()) via = VIA_CLOUD
            }
        }
        if (calls.isEmpty()) { calls = TeamCommands.rules(text, ::known); if (calls.isNotEmpty()) via = VIA_RULES }
        var acts = actions(calls, text)
        val home = botId?.let { team.get(it) }
        if (home != null) acts = acts.map { if (it is Act.AddJob && it.botId == null && it.botName.isEmpty()) it.copy(botId = home.id, botName = home.name) else it }
        return Plan(text, acts, via)
    }

    /** A-team toActions: calls → the changes they make, reading the roster and the jobs. */
    fun actions(calls: List<TeamCommands.Call>, text: String): List<Act> {
        val acts = ArrayList<Act>(); val made = HashSet<String>()
        val override = TeamCommands.dueIn(text, today())
        for (c in calls) when (c.name) {
            "add_agent" -> {
                val name = c.a("name")
                if (name.isEmpty()) continue
                val low = name.lowercase(Locale.ROOT)
                val ex = bots().firstOrNull { it.name.lowercase(Locale.ROOT) == low }
                    ?: findBot(name)?.takeIf { name.split(Regex("\\s+")).size == 1 && it.name.lowercase(Locale.ROOT).split(Regex("\\s+"))[0] == low }
                if (ex != null && ex.name.lowercase(Locale.ROOT) !in made) {
                    val ch = LinkedHashMap<String, String>()
                    if (c.a("email").isNotEmpty() && c.a("email") != ex.email) ch["email"] = c.a("email")
                    if (c.a("role").isNotEmpty() && c.a("role") != ex.label) ch["role"] = c.a("role")
                    if (c.a("portfolio").isNotEmpty()) ch["portfolio"] = c.a("portfolio")
                    if (ch.isNotEmpty()) acts += Act.UpdateBot(ex.id, ex.name, ch)
                    continue
                }
                if (low in made) continue
                acts += Act.AddBot(name, c.a("email").ifEmpty { TeamCommands.email(text).takeIf { calls.count { k -> k.name == "add_agent" } == 1 }.orEmpty() }, c.a("role"), c.a("portfolio"))
                made += low
            }
            "add_task" -> {
                val agent = c.a("agent")
                var bot = if (agent.lowercase(Locale.ROOT) in made) null else findBot(agent)
                if (bot == null && agent.lowercase(Locale.ROOT) !in made) bot = mentioned(text)
                val own = c.a("due").takeIf { it.isNotEmpty() }?.let { TeamCommands.dueIn(it, today()) ?: TeamCommands.Due(TeamCommands.dueWord(it, today())) }
                val due = own?.value ?: override?.value ?: TeamCommands.dueWord(c.a("due"), today())
                val willMake = bot == null && agent.isNotEmpty() && agent.lowercase(Locale.ROOT) !in made
                val title = TeamCommands.cleanTitle(c.a("title"))
                if (title.isEmpty()) continue
                acts += Act.AddJob(title, bot?.id, bot?.name ?: agent, willMake, due,
                    c.a("priority").takeIf { it in JobStore.PRIORITIES } ?: if (TeamCommands.urgent(text)) "high" else "normal", TeamCommands.dueLabel(due, today()))
                if (willMake) made += agent.lowercase(Locale.ROOT)
            }
            "complete_task" -> {
                val b = findBot(c.a("agent")) ?: mentioned(text)
                val j = findJob(c.a("title"), b) ?: findJob(text, b)
                acts += if (j != null) Act.Complete(j.id, j.title) else Act.Miss(c.a("title").ifEmpty { text })
            }
            "reschedule_task" -> {
                val j = findJob(c.a("title")) ?: findJob(text)
                val b = c.a("agent").takeIf { it.isNotEmpty() }?.let { findBot(it) }
                val dueSet = override != null || c.a("due").isNotEmpty()
                val due = override?.value ?: TeamCommands.dueWord(c.a("due"), today())
                acts += if (j == null) Act.Miss(c.a("title").ifEmpty { text })
                    else Act.Move(j.id, j.title, due, dueSet, TeamCommands.dueLabel(due, today()), b?.id, b?.name)
            }
            "remove" -> {
                val nm = c.a("name")
                var kind = c.a("kind")
                if (Regex("""\b(job|task|todo|to-do)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) kind = "task"
                else if (Regex("""\b(agent|bot)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) kind = "agent"
                else if (findBot(nm) == null && findJob(nm) != null) kind = "task"
                if (Regex("""\b(done|finished|completed)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) && kind == "task") {
                    val done = findJob(nm) ?: findJob(text)
                    if (done != null) { acts += Act.Complete(done.id, done.title); continue }
                }
                acts += if (kind == "agent") findBot(nm)?.let { b -> if (BotRules.isManager(b)) Act.Miss(nm, "bot") else Act.DeleteBot(b.id, b.name) } ?: Act.Miss(nm, "bot")
                    else (findJob(nm) ?: findJob(text))?.let { Act.DeleteJob(it.id, it.title) } ?: Act.Miss(nm.ifEmpty { text })
            }
        }
        return acts
    }

    /** What [handle] did, for the shell to show. */
    sealed class Outcome {
        /** Run [cmd] in the shell and show its output. */
        data class Shell(val cmd: String) : Outcome()
        /** Handed to a bot ([Handed.line] = "Atlas is on it"). */
        data class Delegated(val handed: Handed) : Outcome()
        /** Open [bot]'s thread (an @mention with nothing after it). */
        data class Open(val bot: Bot) : Outcome()
        /** Applied at once: the receipt in [applied]. */
        data class Done(val plan: Plan, val applied: Applied) : Outcome()
        /** Show the "Here's what I'll do" sheet; [apply] it when the user taps Apply. */
        data class Preview(val plan: Plan) : Outcome()
        /** Conversation: send [text] to ARGUS (or the thread's bot). */
        data class Chat(val text: String) : Outcome()
    }

    /**
     * The whole of routing v1 for one message: [route], then interpret a command (in a thread only while the cloud can't
     * answer), then apply, preview ([instant] off, or a removal) or hand over to the conversation (nothing found, or a
     * reading that missed its target: A-team sends those to the Manager).
     */
    suspend fun handle(text: String, where: Where, lanes: Lanes, instant: Boolean): Outcome = when (val r = route(text, where)) {
        is Route.Shell -> Outcome.Shell(r.cmd)
        is Route.Chat -> Outcome.Chat(r.text)
        is Route.Delegate -> if (r.task.isEmpty()) Outcome.Open(r.bot) else Outcome.Delegated(delegate(r.bot, r.task))
        is Route.Apply -> Outcome.Done(r.plan, apply(r.plan.actions))
        is Route.Command -> {
            if (r.direct && lanes.cloud()) Outcome.Chat(text.trim())
            else {
                val plan = interpret(r.text, lanes, r.botId)
                when {
                    r.direct -> if (plan.real.isEmpty() || plan.risky) (if (plan.risky) Outcome.Preview(plan) else Outcome.Chat(text.trim()))
                        else Outcome.Done(plan, apply(plan.real).let { a -> a.copy(lines = a.lines + plan.actions.filterIsInstance<Act.Miss>().map { it.label }) })
                    !plan.clean -> Outcome.Chat(text.trim())
                    instant && !plan.risky -> Outcome.Done(plan, apply(plan.actions))
                    else -> Outcome.Preview(plan)
                }
            }
        }
    }

    // ---------------- doing it ----------------

    data class Applied(val count: Int, val lines: List<String>, val jobs: List<String> = emptyList()) {
        /** A-team receipt: "Done · 2 changes", with the first two labels. */
        val receipt: String get() = if (count == 0) lines.firstOrNull() ?: "Nothing changed" else
            lines.take(2).joinToString(" \u00b7 ") + (if (lines.size > 2) " \u00b7 +${lines.size - 2}" else "")
    }

    /** A-team applyActions. Skips [Act.Miss]; a refusal (a bad email, the Manager) is a line that counts nothing. */
    fun apply(acts: List<Act>, source: String = "user"): Applied {
        var n = 0; val lines = ArrayList<String>(); val newJobs = ArrayList<String>()
        val by = if (source == "user") "user" else source
        for (a in acts) when (a) {
            is Act.Miss -> {}
            is Act.AddBot -> {
                if (named(a.name)?.name?.equals(a.name, true) == true) continue
                when (val r = edits.create(BotDraft(name = a.name, label = a.role.take(BotDraft.LABEL_MAX), email = a.email, portfolio = a.portfolio,
                    color = BotDraft.nextColor(team.roster().size)), by)) {
                    is TeamStore.Result.Ok -> { n++; lines += a.label; changed(r.bot.id) }
                    is TeamStore.Result.Refused -> lines += "${a.name}: ${r.why}"
                }
            }
            is Act.UpdateBot -> {
                val b = team.get(a.id) ?: continue
                val d0 = BotDraft.of(b, team.soul(b.id))
                val d = d0.copy(email = a.changes["email"] ?: d0.email, label = (a.changes["role"] ?: d0.label).take(BotDraft.LABEL_MAX),
                    portfolio = (a.changes["portfolio"] ?: d0.portfolio).take(BotDraft.PORTFOLIO_MAX))
                when (val r = edits.update(b.id, d, by)) {
                    is TeamStore.Result.Ok -> { n++; lines += a.label; changed(b.id) }
                    is TeamStore.Result.Refused -> lines += "${b.name}: ${r.why}"
                }
            }
            is Act.AddJob -> {
                var botId = a.botId
                if (botId == null && a.botName.isNotEmpty()) {
                    botId = findBot(a.botName)?.id ?: (edits.create(BotDraft(name = a.botName, color = BotDraft.nextColor(team.roster().size)), by) as? TeamStore.Result.Ok)
                        ?.bot?.also { changed(it.id) }?.id
                }
                when (val r = jobs.add(a.title, a.title, botId, a.due, a.priority, source = source)) {
                    is JobStore.Added.Duplicate -> lines += "Already in the queue: ${r.job.title}"
                    else -> { n++; lines += a.label; newJobs += r.job.id }
                }
            }
            is Act.Complete -> { if ((queue()?.done(a.jobId, null) ?: jobs.update(a.jobId) { it.copy(run = JobRun.DONE, status = "done") }) != null) { n++; lines += a.label } }
            is Act.Move -> {
                val j = jobs.update(a.jobId) { it.copy(due = if (a.dueSet) a.due else it.due, botId = a.botId ?: it.botId) }
                if (j != null) { n++; lines += a.label }
            }
            is Act.DeleteBot -> when (val r = edits.delete(a.id)) {
                is TeamStore.Result.Ok -> { n++; lines += a.label; changed(a.id) }
                is TeamStore.Result.Refused -> lines += "${a.name}: ${r.why}"
            }
            is Act.DeleteJob -> { if (jobs.get(a.id) != null) { jobs.delete(a.id); n++; lines += a.label } }
        }
        if (n > 0) kick()
        return Applied(n, lines, newJobs)
    }

    data class Handed(val job: Job?, val bot: Bot, val line: String, val duplicate: Boolean)

    /**
     * Delegate = Job + HandoffCard: [task] becomes a job for [bot] (the user asked, ARGUS routed it), a HandoffCard
     * ("Argus → Atlas: …") lands in the bot's thread, and the line for the chat is "Atlas is on it".
     */
    fun delegate(bot: Bot, task: String, source: String = "user"): Handed {
        val words = task.trim()
        val due = TeamCommands.dueIn(words, today())?.value
        val title = TeamCommands.cleanTitle(words.lineSequence().first()).take(60).ifEmpty { "Job" }
        val r = jobs.add(title, words, bot.id, due, if (TeamCommands.urgent(words)) "high" else "normal", source = source)
        val dup = r is JobStore.Added.Duplicate
        if (!dup) {
            post(bot.id, RowKind.CARD, RowBodies.card("handoff", linkedMapOf("from" to BotRules.MANAGER_ID, "fromName" to BrandName.NAME,
                "to" to bot.id, "toName" to bot.name, "text" to words, "status" to "queued", "jobId" to r.job.id)))
            kick()
        }
        val line = if (dup) "${bot.name} already has that job (${r.job.status})." else "${bot.name} is on it."
        return Handed(r.job, bot, line, dup)
    }
}

/** P1-TM-06: how a `$ cmd` run shows in the chat (pure; RoutingTest). */
object ShellNote {
    const val MAX = 3000
    fun of(cmd: String, output: String): String {
        val o = output.trimEnd().let { if (it.length > MAX) it.take(MAX) + "\n\u2026" else it }.ifEmpty { "(no output)" }
        return "$ $cmd\n```\n$o\n```"
    }
}
